package rep02;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PartitionCheck <N> <pasta>: verifica lider-p*.txt e replica-*-p*.txt reunidos numa pasta. */
public class PartitionCheck {
    static final Pattern REPLICA = Pattern.compile("replica-(\\d+)-p(\\d+)\\.txt");

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            System.out.println("Uso: PartitionCheck <N> <pasta> [R]");
            return;
        }
        int n = Integer.parseInt(args[0]);
        Path dir = Paths.get(args[1]);
        int rEsperado = args.length > 2 ? Integer.parseInt(args[2]) : 2;   // fator de replicação esperado
        Partitioner part = new Partitioner(n);
        boolean allOk = true;
        long total = 0, sum = 0;
        Set<String> allSensors = new TreeSet<>();

        for (int k = 0; k < n; k++) {
            System.out.println("== Partição " + k + " ==");
            List<String> leader = readLines(dir.resolve("lider-p" + k + ".txt"));
            List<SensorRecord> recs = new ArrayList<>();
            for (String l : leader) recs.add(SensorRecord.fromLine(l));

            final int final_k = k;
            long wrongKey = recs.stream().filter(r -> part.of(r.getSensorId()) != final_k).count();
            boolean seqOk = seqOk(recs);
            Set<String> sensors = new TreeSet<>();
            for (SensorRecord r : recs) sensors.add(r.getSensorId());
            allSensors.addAll(sensors);
            System.out.println("  líder: " + recs.size() + " registos, " + sensors.size() + " sensores distintos " + sensors);
            System.out.println("  chave de partição: " + (wrongKey == 0 ? "OK" : "FALHA (" + wrongKey + " registos de outra partição)"));
            System.out.println("  seq 1.." + recs.size() + " sem saltos nem repetições: " + (seqOk ? "OK" : "FALHA"));
            allOk &= wrongKey == 0 && seqOk;
            sum += recs.size();

            // réplicas que guardam a partição k
            List<String> names = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "replica-*-p" + k + ".txt")) {
                for (Path f : ds) names.add(f.getFileName().toString());
            }
            Collections.sort(names);
            int r = 0;
            for (String name : names) {
                Matcher m = REPLICA.matcher(name);
                if (!m.matches()) continue;
                r++;
                List<String> rl = readLines(dir.resolve(name));
                boolean prefix = leader.size() >= rl.size() && leader.subList(0, rl.size()).equals(rl);
                String estado = !prefix ? "DIVERGE" : rl.size() == leader.size() ? "IGUAL" : "PREFIXO";
                System.out.println("  réplica " + m.group(1) + ": " + rl.size() + " registos, " + estado
                        + ", faltam " + (leader.size() - rl.size()));
                allOk &= prefix;
            }
            System.out.println("  réplicas que guardam a partição: " + r
                    + (r == rEsperado ? " (R = " + rEsperado + ": OK)" : " (esperado R = " + rEsperado + ")"));
            allOk &= r == rEsperado;
        }
        // total do sistema contado à parte: todos os lider-p*.txt da pasta, seja qual for o N
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "lider-p*.txt")) {
            for (Path f : ds) total += readLines(f).size();
        }
        System.out.println("== Total ==");
        System.out.println("  registos do sistema: " + total + ", soma das partições: " + sum
                + (total == sum ? " (iguais)" : " (DIFERENTES)") + ", sensores distintos: " + allSensors.size());
        allOk &= total == sum;
        System.out.println(allOk ? "RESULTADO: OK" : "RESULTADO: FALHA");
    }

    static boolean seqOk(List<SensorRecord> recs) {
        for (int i = 0; i < recs.size(); i++) {
            if (recs.get(i).getSeq() != i + 1) return false;
        }
        return true;
    }

    static List<String> readLines(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.exists(p)) return out;
        for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (!l.isBlank()) out.add(l.trim());
        }
        return out;
    }
}