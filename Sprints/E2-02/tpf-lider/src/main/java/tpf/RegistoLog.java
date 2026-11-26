package tpf;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * O ficheiro {@code registos.log}: uma linha por registo, só acrescentada (append-only),
 * com {@code fsync} antes de se confirmar o registo ao utilizador.
 *
 * <p>No arranque lê o ficheiro inteiro para saber o último LSN. Se a última linha estiver
 * incompleta (o processo morreu a meio de uma escrita), é cortada: esse registo nunca foi
 * confirmado, porque a confirmação só é enviada depois do {@code fsync}.
 */
final class RegistoLog implements Closeable {

    private final FileChannel canal;
    private final List<Registo> existentes;
    private final long bytesCortados;
    private long ultimoLsn;

    RegistoLog(Path ficheiro) throws IOException {
        Leitura l = ler(ficheiro);
        this.existentes = l.registos();
        this.ultimoLsn = existentes.isEmpty() ? 0 : existentes.get(existentes.size() - 1).lsn();

        canal = FileChannel.open(ficheiro, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        long tamanho = canal.size();
        this.bytesCortados = tamanho - l.bytesValidos();
        if (bytesCortados > 0) {
            canal.truncate(l.bytesValidos());
            canal.force(true);
        }
        canal.position(l.bytesValidos());
    }

    /** Registos que já estavam no ficheiro quando o líder arrancou. */
    List<Registo> existentes() { return existentes; }

    /** Bytes de uma última linha incompleta que foram removidos no arranque (0 se nenhum). */
    long bytesCortados() { return bytesCortados; }

    long ultimoLsn() { return ultimoLsn; }

    /**
     * Acrescenta a linha do registo e força-a para o disco ({@code FileChannel.force(true)}).
     * Quando este método volta sem exceção, o registo sobrevive a uma falha do processo ou da máquina.
     * Se a escrita falhar a meio, tenta cortar o que ficou escrito, para não deixar lixo no ficheiro.
     */
    synchronized void acrescentar(Registo r) throws IOException {
        if (r.lsn() != ultimoLsn + 1) {
            throw new IllegalStateException("LSN fora de ordem: " + r.lsn() + " depois de " + ultimoLsn);
        }
        long antes = canal.position();
        ByteBuffer b = ByteBuffer.wrap((r.paraLinha() + "\n").getBytes(StandardCharsets.UTF_8));
        try {
            while (b.hasRemaining()) canal.write(b);
            canal.force(true);   // fsync: dados e metadados (tamanho do ficheiro)
        } catch (IOException e) {
            try {
                canal.truncate(antes);
                canal.position(antes);
            } catch (IOException ignorada) {
                e.addSuppressed(ignorada);
            }
            throw e;
        }
        ultimoLsn = r.lsn();
    }

    @Override
    public synchronized void close() throws IOException {
        canal.close();
    }

    // ------------------------------------------------------------------

    record Leitura(List<Registo> registos, long bytesValidos) {}

    /**
     * Lê e valida o ficheiro sem o alterar. Só conta linhas terminadas em '\n'.
     * Os LSN têm de ser 1, 2, 3, ...; caso contrário o ficheiro está corrompido e o arranque é recusado.
     */
    static Leitura ler(Path ficheiro) throws IOException {
        if (Files.notExists(ficheiro)) return new Leitura(List.of(), 0);
        byte[] dados = Files.readAllBytes(ficheiro);

        int fim = dados.length;
        while (fim > 0 && dados[fim - 1] != '\n') fim--;   // fim = logo a seguir ao último '\n'

        List<Registo> registos = new ArrayList<>();
        String conteudo = new String(dados, 0, fim, StandardCharsets.UTF_8);
        int numLinha = 0;
        for (String linha : conteudo.split("\n")) {
            numLinha++;
            if (linha.isBlank()) continue;
            Registo r;
            try {
                r = Registo.deLinha(linha.strip());
            } catch (RuntimeException e) {
                throw new IllegalStateException(ficheiro + ", linha " + numLinha + " inválida: " + e.getMessage(), e);
            }
            long esperado = registos.size() + 1L;
            if (r.lsn() != esperado) {
                throw new IllegalStateException(ficheiro + ", linha " + numLinha + ": LSN " + r.lsn()
                        + " mas era esperado " + esperado + " (repetição ou salto).");
            }
            registos.add(r);
        }
        return new Leitura(Collections.unmodifiableList(registos), fim);
    }
}
