package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Análise a posteriori dos registos: V[k] = seq, relação causal entre partições, condição de relógio
// de Lamport e proporção de concorrentes ordenados por Lamport.
// Executado como: CausalCheck <N> <pasta>
// ALTERADO na COMINDIR05: já não há lider-p<k>.txt. Lê os ficheiros no-p<k>-n<i>.txt:
//  - a verificação V[k] = seq (e L crescente) é feita em CADA ficheiro de nó: é ela que mostra que
//    V[k] = seq se mantém através das mudanças de líder;
//  - a análise entre partições usa, por partição, o ficheiro de referência: o do nó cujo último
//    registo tem o maior (termo, seq), ou seja, a história do líder mais recente.
// Escreve relatorio-causal.txt (o mesmo texto que mostra no ecrã) e vista-lamport.txt.
public class CausalCheck {
    private static final Pattern FILE = Pattern.compile("no-p(\\d+)-n(\\d+)\\.txt");

    private static final StringBuilder report = new StringBuilder();

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

        // ---- leitura: files.get(k) = ficheiros (nó -> registos) da partição k ----
        List<Map<Integer, List<SensorRecord>>> files = new ArrayList<>();
        for (int k = 0; k < n; k++) files.add(new TreeMap<>());
        int formatErrors = 0;
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "no-p*-n*.txt")) {
            for (Path p : ds) paths.add(p);
        }
        Collections.sort(paths);
        say("CausalCheck N=" + n + " pasta=" + dir.toAbsolutePath());
        for (Path p : paths) {
            Matcher m = FILE.matcher(p.getFileName().toString());
            if (!m.matches()) continue;
            int k = Integer.parseInt(m.group(1)), node = Integer.parseInt(m.group(2));
            if (k >= n) {
                say("ERRO: " + p.getFileName() + " tem partição " + k + " >= N=" + n);
                formatErrors++;
                continue;
            }
            List<SensorRecord> regs = new ArrayList<>();
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) continue;
                try {
                    regs.add(SensorRecord.fromLine(lines.get(i), n));
                } catch (IllegalArgumentException e) {
                    say("ERRO de formato em " + p.getFileName() + ":" + (i + 1) + ": " + e.getMessage());
                    formatErrors++;
                }
            }
            files.get(k).put(node, regs);
        }

        // 1) V[k] = seq e L crescente em cada ficheiro de nó
        say("");
        say("== 1. V[k] = seq e L crescente em cada ficheiro de nó ==");
        long vkErrors = 0, lErrors = 0;
        for (int k = 0; k < n; k++) {
            for (Map.Entry<Integer, List<SensorRecord>> e : files.get(k).entrySet()) {
                long bad = 0, badL = 0, prevL = -1;
                for (SensorRecord r : e.getValue()) {
                    if (r.getVector().get(k) != r.getSeq()) {
                        bad++;
                        if (bad <= 5) say("  VIOLAÇÃO p" + k + "-n" + e.getKey() + ": seq=" + r.getSeq()
                                + " termo=" + r.getTerm() + " V=" + r.getVector());
                    }
                    if (r.getLamport() <= prevL) {
                        badL++;
                        if (badL <= 5) say("  VIOLAÇÃO p" + k + "-n" + e.getKey() + ": seq=" + r.getSeq()
                                + " termo=" + r.getTerm() + " L=" + r.getLamport() + " <= anterior " + prevL);
                    }
                    prevL = r.getLamport();
                }
                say("  p" + k + "-n" + e.getKey() + ": " + e.getValue().size() + " registos, V[k]!=seq: " + bad
                        + ", L não crescente: " + badL);
                vkErrors += bad;
                lErrors += badL;
            }
        }

        // ficheiro de referência por partição: maior (termo, seq) do último registo
        List<List<SensorRecord>> regs = new ArrayList<>();
        say("");
        say("== Ficheiro de referência por partição ==");
        for (int k = 0; k < n; k++) {
            int best = -1;
            long bt = -1, bs = -1;
            for (Map.Entry<Integer, List<SensorRecord>> e : files.get(k).entrySet()) {
                List<SensorRecord> l = e.getValue();
                long t = l.isEmpty() ? 0 : l.get(l.size() - 1).getTerm();
                long s = l.size();
                if (t > bt || (t == bt && s > bs)) { bt = t; bs = s; best = e.getKey(); }
            }
            regs.add(best == -1 ? new ArrayList<>() : files.get(k).get(best));
            say("  p" + k + ": " + (best == -1 ? "(nenhum ficheiro)" : "no-p" + k + "-n" + best + ".txt (último termo "
                    + bt + ", " + bs + " registos)"));
        }

        // 2) pares entre partições; 3) condição de relógio; 4) concorrentes com L distinto
        long causal = 0, causalAB = 0, causalBA = 0, concurrent = 0, concurrentLDistinct = 0, equal = 0;
        long clockViolations = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (SensorRecord ra : regs.get(i)) {
                    for (SensorRecord rb : regs.get(j)) {
                        switch (ra.getVector().compare(rb.getVector())) {
                            case BEFORE:
                                causal++;
                                causalAB++;
                                if (!(ra.getLamport() < rb.getLamport())) {
                                    clockViolations++;
                                    if (clockViolations <= 5) say("  VIOLAÇÃO p" + i + "#" + ra.getSeq() + " -> p" + j
                                            + "#" + rb.getSeq() + ": L=" + ra.getLamport() + " >= " + rb.getLamport());
                                }
                                break;
                            case AFTER:
                                causal++;
                                causalBA++;
                                if (!(rb.getLamport() < ra.getLamport())) {
                                    clockViolations++;
                                    if (clockViolations <= 5) say("  VIOLAÇÃO p" + j + "#" + rb.getSeq() + " -> p" + i
                                            + "#" + ra.getSeq() + ": L=" + rb.getLamport() + " >= " + ra.getLamport());
                                }
                                break;
                            case CONCURRENT:
                                concurrent++;
                                if (ra.getLamport() != rb.getLamport()) concurrentLDistinct++;
                                break;
                            default:
                                equal++;
                        }
                    }
                }
            }
        }
        long totalPairs = 0;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++) totalPairs += (long) regs.get(i).size() * regs.get(j).size();

        say("");
        say("== 2. Pares de registos de partições diferentes (ficheiros de referência) ==");
        say("  total de pares:            " + totalPairs);
        say("  a -> b (partição menor primeiro): " + causalAB);
        say("  b -> a (partição menor depois):   " + causalBA);
        say("  causalmente relacionados:  " + causal);
        say("  concorrentes:              " + concurrent);
        if (equal > 0) say("  ANOMALIA: vetores iguais entre partições diferentes: " + equal);
        say("");
        say("== 3. Condição de relógio de Lamport (a -> b implica L(a) < L(b)) ==");
        say("  violações entre partições:  " + clockViolations + " (em " + causal + " pares causais)");
        say("");
        say("== 4. Concorrentes ordenados por Lamport ==");
        say("  concorrentes com L distinto: " + concurrentLDistinct + " de " + concurrent
                + (concurrent == 0 ? "" : String.format(" (%.2f%%)", 100.0 * concurrentLDistinct / concurrent)));

        // 5) vista de Lamport: ordem total por (L, k, seq)
        List<String[]> all = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            for (SensorRecord r : regs.get(k)) {
                all.add(new String[] { String.valueOf(r.getLamport()), String.valueOf(k), String.valueOf(r.getSeq()), r.toLine() });
            }
        }
        all.sort(Comparator.<String[]>comparingLong(a -> Long.parseLong(a[0]))
                .thenComparingInt(a -> Integer.parseInt(a[1]))
                .thenComparingLong(a -> Long.parseLong(a[2])));
        List<String> view = new ArrayList<>();
        for (String[] a : all) view.add(a[0] + ";p" + a[1] + ";" + a[3]);
        Files.write(dir.resolve("vista-lamport.txt"), view, StandardCharsets.UTF_8);

        say("");
        long violations = vkErrors + lErrors + clockViolations;
        say("== Resultado ==");
        say("  vista-lamport.txt escrito com " + all.size() + " registos");
        say(formatErrors > 0 ? "  ERROS DE FORMATO: " + formatErrors
                : violations > 0 ? "  INCONSISTENTE: " + violations + " violações" : "  OK: sem violações");
        Files.writeString(dir.resolve("relatorio-causal.txt"), report.toString(), StandardCharsets.UTF_8);
        if (formatErrors > 0) System.exit(2);
        if (violations > 0) System.exit(1);
    }
}
