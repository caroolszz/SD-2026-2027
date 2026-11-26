package tpf;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Chunk: um conjunto de registos consecutivos que é selado (serializado + cifrado) de uma vez.
 *
 * <p>Enquanto está <b>aberto</b> aceita registos com {@link #adicionar}. Depois de {@link #selar}
 * fica <b>imutável</b>: {@code adicionar} lança exceção e o registo seguinte vai para um chunk novo.
 *
 * <h2>Formato binário (big-endian, antes da cifra)</h2>
 * <pre>
 * Cabeçalho (9 bytes)
 *   magic        4 bytes   "TPFC" (0x54 0x50 0x46 0x43)
 *   versão       1 byte    0x01
 *   nRegistos    int32     número de registos que se seguem
 * Registo (repetido nRegistos vezes, 24 bytes + textos)
 *   lsn          int64
 *   instante     int64     milissegundos desde 1970-01-01T00:00Z
 *   autorLen     uint16    + autorLen bytes UTF-8
 *   canalLen     uint16    + canalLen bytes UTF-8
 *   textoLen     int32     + textoLen bytes UTF-8
 * Padding
 *   0x00 até o total ser múltiplo de PAD_BYTES (zero bytes se já for)
 * </pre>
 * A leitura usa {@code nRegistos} para saber onde acabam os registos e ignora o resto (padding).
 */
public final class Chunk {

    static final byte[] MAGIC = {'T', 'P', 'F', 'C'};
    static final byte VERSAO_FORMATO = 1;
    static final int CABECALHO_BYTES = MAGIC.length + 1 + 4;
    static final int REGISTO_FIXO_BYTES = 8 + 8 + 2 + 2 + 4;

    private final List<Registo> registos = new ArrayList<>();
    private int tamanho = CABECALHO_BYTES;
    private byte[] selado;   // null enquanto aberto

    /** Acrescenta um registo ao chunk aberto. */
    public void adicionar(Registo r) {
        if (selado != null) throw new IllegalStateException("Chunk já selado: é imutável.");
        int t = tamanhoRegisto(r);   // valida os limites dos campos antes de alterar o estado
        registos.add(r);
        tamanho += t;
    }

    /** Tamanho serializado atual, sem padding. É este valor que se compara com CHUNK_MAX_BYTES. */
    public int tamanho() { return tamanho; }

    public int numRegistos() { return registos.size(); }

    public boolean vazio() { return registos.isEmpty(); }

    public boolean selado() { return selado != null; }

    public long primeiroLsn() { return registos.get(0).lsn(); }

    public long ultimoLsn() { return registos.get(registos.size() - 1).lsn(); }

    public List<Registo> registos() { return List.copyOf(registos); }

    /**
     * Sela o chunk: serializa os registos com padding até um múltiplo de {@code padBytes}
     * e torna-o imutável. Chamadas repetidas devolvem os mesmos bytes.
     */
    public byte[] selar(int padBytes) {
        if (selado == null) selado = serializar(registos, padBytes);
        return selado.clone();
    }

    // ------------------------------------------------------------------
    // Serialização
    // ------------------------------------------------------------------

    static int tamanhoRegisto(Registo r) {
        int a = utf8(r.autor()).length, c = utf8(r.canal()).length, t = utf8(r.texto()).length;
        if (a > 0xFFFF || c > 0xFFFF) throw new IllegalArgumentException("autor/canal com mais de 65535 bytes");
        return REGISTO_FIXO_BYTES + a + c + t;
    }

    /** Serializa os registos no formato descrito na documentação da classe. */
    public static byte[] serializar(List<Registo> registos, int padBytes) {
        if (padBytes < 1) throw new IllegalArgumentException("padBytes tem de ser >= 1");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(MAGIC);
            out.writeByte(VERSAO_FORMATO);
            out.writeInt(registos.size());
            for (Registo r : registos) {
                byte[] autor = utf8(r.autor()), canal = utf8(r.canal()), texto = utf8(r.texto());
                out.writeLong(r.lsn());
                out.writeLong(r.instante().toEpochMilli());
                out.writeShort(autor.length);
                out.write(autor);
                out.writeShort(canal.length);
                out.write(canal);
                out.writeInt(texto.length);
                out.write(texto);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);   // não acontece com ByteArrayOutputStream
        }
        int util = bytes.size();
        int total = ((util + padBytes - 1) / padBytes) * padBytes;   // arredonda para cima
        return Arrays.copyOf(bytes.toByteArray(), total);            // completa com 0x00
    }

    /** Leitura inversa: reconstrói os registos e ignora o padding. */
    public static List<Registo> desserializar(byte[] dados) {
        try {
            ByteBuffer b = ByteBuffer.wrap(dados);
            byte[] magic = new byte[MAGIC.length];
            b.get(magic);
            if (!Arrays.equals(magic, MAGIC)) throw new IllegalArgumentException("magic inválido");
            byte versao = b.get();
            if (versao != VERSAO_FORMATO) throw new IllegalArgumentException("versão de formato " + versao + " desconhecida");
            int n = b.getInt();
            if (n < 0) throw new IllegalArgumentException("número de registos negativo");

            List<Registo> registos = new ArrayList<>(Math.min(n, 1 << 16));
            for (int i = 0; i < n; i++) {
                long lsn = b.getLong();
                Instant instante = Instant.ofEpochMilli(b.getLong());
                String autor = lerTexto(b, Short.toUnsignedInt(b.getShort()));
                String canal = lerTexto(b, Short.toUnsignedInt(b.getShort()));
                String texto = lerTexto(b, b.getInt());
                registos.add(new Registo(lsn, instante, autor, canal, texto));
            }
            return registos;   // o que sobra em b é padding: ignorado
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("chunk truncado", e);
        }
    }

    private static String lerTexto(ByteBuffer b, int len) {
        if (len < 0 || len > b.remaining()) throw new IllegalArgumentException("comprimento inválido: " + len);
        byte[] s = new byte[len];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
