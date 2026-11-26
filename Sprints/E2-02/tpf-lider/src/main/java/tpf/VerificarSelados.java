package tpf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ferramenta de verificação (não altera nada): abre todos os chunks de {@code selados/} com a
 * {@code master.key} e confirma, para cada um, que
 * <ol>
 *   <li>o nome do {@code .bin} é o SHA-256 do seu conteúdo;</li>
 *   <li>{@code open} tem sucesso (hash, versão da chave, GCM);</li>
 *   <li>o texto decifrado tem tamanho múltiplo de PAD_BYTES;</li>
 *   <li>os registos (LSN, instante, autor, canal, texto) são exatamente os de {@code registos.log};</li>
 *   <li>o intervalo de LSN coincide com o do evento {@code SELADO} em {@code eventos-lider.txt};</li>
 *   <li>nenhum texto registado aparece em claro no {@code .bin} (equivalente ao teste com grep).</li>
 * </ol>
 *
 * Uso: {@code mvn -q compile exec:java -Dexec.mainClass=tpf.VerificarSelados [-Dtpf.dir=pasta]}
 */
public final class VerificarSelados {

    private static final Pattern SELADO =
            Pattern.compile(";SELADO;chunkId=([0-9a-f]{64}) lsn=(\\d+)-(\\d+) ");

    public static void main(String[] args) throws IOException {
        Path pasta = Path.of(args.length > 0 ? args[0] : Wire.PASTA);
        Path selados = pasta.resolve(Wire.PASTA_SELADOS);
        ChunkCrypto crypto = new ChunkCrypto(MasterKey.ler(pasta.resolve(Wire.FICHEIRO_MASTER_KEY)));

        Map<Long, Registo> log = new HashMap<>();
        for (Registo r : RegistoLog.ler(pasta.resolve(Wire.FICHEIRO_REGISTOS)).registos()) log.put(r.lsn(), r);

        Map<String, long[]> eventos = new HashMap<>();
        Path fEventos = pasta.resolve(Wire.FICHEIRO_EVENTOS);
        if (Files.exists(fEventos)) {
            for (String linha : Files.readAllLines(fEventos, StandardCharsets.UTF_8)) {
                Matcher m = SELADO.matcher(linha);
                if (m.find()) eventos.put(m.group(1), new long[]{Long.parseLong(m.group(2)), Long.parseLong(m.group(3))});
            }
        }

        List<String> ids = Arquivo.listarSelados(selados);
        int ok = 0;
        long totalRegistos = 0;
        System.out.printf("%d registos em registos.log, %d chunks em %s%n%n", log.size(), ids.size(), selados);
        for (String id : ids) {
            try {
                byte[] bin = Files.readAllBytes(selados.resolve(id + Sealed.EXT_BIN));
                exigir(ChunkCrypto.sha256Hex(bin).equals(id), "nome != SHA-256 do conteúdo");

                byte[] texto = crypto.open(Sealed.ler(selados, id));
                exigir(texto.length % Wire.PAD_BYTES == 0, "tamanho " + texto.length + " não é múltiplo de " + Wire.PAD_BYTES);

                List<Registo> regs = Chunk.desserializar(texto);
                exigir(!regs.isEmpty(), "chunk sem registos");
                for (Registo r : regs) {
                    exigir(r.equals(log.get(r.lsn())), "registo LSN " + r.lsn() + " difere de registos.log");
                    exigir(r.texto().length() < 8 || !contem(bin, r.texto().getBytes(StandardCharsets.UTF_8)),
                            "texto do LSN " + r.lsn() + " aparece em claro no .bin");
                }
                long de = regs.get(0).lsn(), ate = regs.get(regs.size() - 1).lsn();
                long[] ev = eventos.get(id);
                exigir(ev != null, "sem evento SELADO");
                exigir(ev[0] == de && ev[1] == ate, "evento SELADO diz " + ev[0] + "-" + ev[1]);

                System.out.printf("OK    %s  lsn=%d-%d  registos=%d  texto=%d B  bin=%d B%n",
                        id.substring(0, 16) + "...", de, ate, regs.size(), texto.length, bin.length);
                ok++;
                totalRegistos += regs.size();
            } catch (Exception e) {
                System.out.printf("FALHA %s  %s%n", id.substring(0, Math.min(16, id.length())) + "...", e.getMessage());
            }
        }
        System.out.printf("%n%d/%d chunks válidos, %d registos selados.%n", ok, ids.size(), totalRegistos);
        if (ok != ids.size()) System.exit(2);
    }

    private static void exigir(boolean condicao, String erro) {
        if (!condicao) throw new IllegalStateException(erro);
    }

    static boolean contem(byte[] palheiro, byte[] agulha) {
        outer:
        for (int i = 0; i + agulha.length <= palheiro.length; i++) {
            for (int j = 0; j < agulha.length; j++) {
                if (palheiro[i + j] != agulha[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
