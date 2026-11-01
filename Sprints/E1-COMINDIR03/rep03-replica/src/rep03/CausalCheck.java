package rep03;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Análise a posteriori dos ficheiros lider-p<k>.txt: V[k] = seq, relação causal entre partições,
// condição de relógio de Lamport e proporção de concorrentes ordenados por Lamport.
// Executado como: CausalCheck <N> <pasta>
// Lê os ficheiros dos LÍDERES (a história "oficial" de cada partição), não os das réplicas.
// Escreve relatorio-causal.txt (o mesmo texto que mostra no ecrã) e vista-lamport.txt.
public class CausalCheck {
    private static final Pattern FILE = Pattern.compile("lider-p(\\d+)\\.txt");   // extrai o k do nome

    private static final StringBuilder report = new StringBuilder();

    // escreve no ecrã e acumula para o relatório
    private static void say(String s) {
        System.out.println(s);
        report.append(s).append(System.lineSeparator());
    }

    // devolve 0 se tudo estiver consistente, 1 se houve violações, 2 se houve erros de formato
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.out.println("Uso: CausalCheck <N> <pasta>");
            return;
        }
        int n = Integer.parseInt(args[0]);
        Path dir = Paths.get(args[1]);

        // ---- leitura: regs.get(k) = registos da partição k, pela ordem do ficheiro ----
        List<List<SensorRecord>> regs = new ArrayList<>();
        for (int k = 0; k < n; k++) regs.add(new ArrayList<>());
        int formatErrors = 0;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "lider-p*.txt")) {
            for (Path p : ds) files.add(p);
        }
        Collections.sort(files);
        say("CausalCheck N=" + n + " pasta=" + dir.toAbsolutePath());
        for (Path p : files) {
            Matcher m = FILE.matcher(p.getFileName().toString());
            if (!m.matches()) continue;
            int k = Integer.parseInt(m.group(1));
            if (k >= n) {
                say("ERRO: " + p.getFileName() + " tem partição " + k + " >= N=" + n);
                formatErrors++;
                continue;
            }
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) continue;
                try {
                    regs.get(k).add(SensorRecord.fromLine(lines.get(i), n));   // exige vetores de dimensão N
                } catch (IllegalArgumentException e) {
                    say("ERRO de formato em " + p.getFileName() + ":" + (i + 1) + ": " + e.getMessage());
                    formatErrors++;
                }
            }
        }

        // 1) V[k] = seq
        // só o líder k incrementa V[k], uma vez por seq: qualquer diferença indica um líder com estado errado
        say("");
        say("== 1. V[k] = seq em cada ficheiro ==");
        long vkErrors = 0;
        for (int k = 0; k < n; k++) {
            long bad = 0;
            for (SensorRecord r : regs.get(k)) {
                if (r.getVector().get(k) != r.getSeq()) {
                    bad++;
                    if (bad <= 5) {                       // mostra só as primeiras
                        say("  VIOLAÇÃO p" + k + ": seq=" + r.getSeq() + " V=" + r.getVector());
                    }
                }
            }
            say("  p" + k + ": " + regs.get(k).size() + " registos, " + bad + " violações");
            vkErrors += bad;
        }

        // 2) classificação dos pares entre partições; 3) condição de relógio; 4) concorrentes com L distinto
        // O vetor dá a verdade causal (a -> b sse V(a) < V(b)); com ela testa-se se L respeita a
        // condição de relógio (a -> b => L(a) < L(b)) e quantos concorrentes L "ordena" à toa.
        long causal = 0, causalAB = 0, causalBA = 0, concurrent = 0, concurrentLDistinct = 0, equal = 0;
        long clockViolations = 0, intraViolations = 0, intraPairs = 0;
        for (int i = 0; i < n; i++) {
            List<SensorRecord> a = regs.get(i);
            // pares da mesma partição: a ordem é total (seq); a condição de relógio também se aplica
            // (não entram nas contagens entre partições; só servem para detetar violações de L)
            for (int x = 0; x < a.size(); x++) {
                for (int y = x + 1; y < a.size(); y++) {
                    VectorClock.Order o = a.get(x).getVector().compare(a.get(y).getVector());
                    intraPairs++;
                    if (o == VectorClock.Order.BEFORE && !(a.get(x).getLamport() < a.get(y).getLamport())) {
                        intraViolations++;
                        if (intraViolations <= 5) {
                            say("  VIOLAÇÃO (mesma partição p" + i + ") seq " + a.get(x).getSeq() + " -> seq "
                                    + a.get(y).getSeq() + ": L " + a.get(x).getLamport() + " >= " + a.get(y).getLamport());
                        }
                    } else if (o == VectorClock.Order.AFTER && !(a.get(y).getLamport() < a.get(x).getLamport())) {
                        intraViolations++;
                    }
                }
            }
            // pares de partições diferentes: cada par (i < j) é visto uma só vez
            for (int j = i + 1; j < n; j++) {
                for (SensorRecord ra : a) {
                    for (SensorRecord rb : regs.get(j)) {
                        VectorClock.Order o = ra.getVector().compare(rb.getVector());
                        switch (o) {
                            case BEFORE:                  // ra -> rb
                                causal++;
                                causalAB++;
                                if (!(ra.getLamport() < rb.getLamport())) {
                                    clockViolations++;
                                    if (clockViolations <= 5) {
                                        say("  VIOLAÇÃO p" + i + "#" + ra.getSeq() + " -> p" + j + "#" + rb.getSeq()
                                                + ": L=" + ra.getLamport() + " >= " + rb.getLamport());
                                    }
                                }
                                break;
                            case AFTER:                   // rb -> ra
                                causal++;
                                causalBA++;
                                if (!(rb.getLamport() < ra.getLamport())) {
                                    clockViolations++;
                                    if (clockViolations <= 5) {
                                        say("  VIOLAÇÃO p" + j + "#" + rb.getSeq() + " -> p" + i + "#" + ra.getSeq()
                                                + ": L=" + rb.getLamport() + " >= " + ra.getLamport());
                                    }
                                }
                                break;
                            case CONCURRENT:              // ra || rb: Lamport ordena-os se L for diferente
                                concurrent++;
                                if (ra.getLamport() != rb.getLamport()) concurrentLDistinct++;
                                break;
                            default:                      // EQUAL: impossível se V[k] = seq (V[i] difere)
                                equal++;
                        }
                    }
                }
            }
        }

        // total de pares entre partições = soma de |p_i| * |p_j| para i < j
        long totalPairs = 0;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++) totalPairs += (long) regs.get(i).size() * regs.get(j).size();

        say("");
        say("== 2. Pares de registos de partições diferentes ==");
        say("  total de pares:            " + totalPairs);
        say("  a -> b (partição menor primeiro): " + causalAB);
        say("  b -> a (partição menor depois):   " + causalBA);
        say("  causalmente relacionados:  " + causal);
        say("  concorrentes:              " + concurrent);
        if (equal > 0) say("  ANOMALIA: vetores iguais entre partições diferentes: " + equal);

        say("");
        say("== 3. Condição de relógio de Lamport (a -> b implica L(a) < L(b)) ==");
        say("  violações entre partições:  " + clockViolations + " (em " + causal + " pares causais)");
        say("  violações na mesma partição: " + intraViolations + " (em " + intraPairs + " pares)");

        say("");
        say("== 4. Concorrentes ordenados por Lamport ==");
        say("  concorrentes com L distinto: " + concurrentLDistinct + " de " + concurrent
                + (concurrent == 0 ? "" : String.format(" (%.2f%%)", 100.0 * concurrentLDistinct / concurrent)));

        // 5) vista de Lamport: ordem total por (L, k); o seq desempata dentro da mesma partição
        // (na prática não é preciso: dentro de uma partição L é estritamente crescente)
        // Consistente com a causalidade, mas a ordem entre concorrentes é arbitrária.
        List<SensorRecord> all = new ArrayList<>();
        Map<SensorRecord, Integer> partitionOf = new IdentityHashMap<>();   // registo -> k (o registo não guarda k)
        for (int k = 0; k < n; k++) {
            for (SensorRecord r : regs.get(k)) {
                all.add(r);
                partitionOf.put(r, k);
            }
        }
        all.sort(Comparator.<SensorRecord>comparingLong(SensorRecord::getLamport)
                .thenComparingInt(partitionOf::get)
                .thenComparingLong(SensorRecord::getSeq));
        List<String> view = new ArrayList<>();
        for (SensorRecord r : all) {
            view.add(r.getLamport() + ";p" + partitionOf.get(r) + ";" + r.toLine());   // L;p<k>;linha original
        }
        Files.write(dir.resolve("vista-lamport.txt"), view, StandardCharsets.UTF_8);

        say("");
        long violations = vkErrors + clockViolations + intraViolations;
        say("== Resultado ==");
        say("  vista-lamport.txt escrito com " + all.size() + " registos");
        say(formatErrors > 0 ? "  ERROS DE FORMATO: " + formatErrors
                : violations > 0 ? "  INCONSISTENTE: " + violations + " violações" : "  OK: sem violações");
        Files.writeString(dir.resolve("relatorio-causal.txt"), report.toString(), StandardCharsets.UTF_8);
        // código de saída, útil para scripts de teste
        if (formatErrors > 0) System.exit(2);
        if (violations > 0) System.exit(1);
    }
}
