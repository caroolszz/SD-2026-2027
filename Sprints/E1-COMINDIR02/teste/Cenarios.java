import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Executa os cenários de teste da COMINDIR02 contra nós IPFS simulados (MockIpfs).
 * Uso (a partir de E1-COMINDIR02):  java -cp teste/out Cenarios <pastaDeTrabalho> <cenário|todos>
 */
public class Cenarios {
    static final String SEP = File.pathSeparator;
    static Path root, base;
    static final String CP_PROD, CP_LID, CP_REP, CP_01;

    static {
        base = Paths.get("").toAbsolutePath();
        CP_PROD = base.resolve("rep02-produtor/out").toString();
        CP_LID = base.resolve("rep02-lider/out").toString();
        CP_REP = base.resolve("rep02-replica/out").toString();
        CP_01 = base.resolve("teste/out01").toString();
    }

    // ---------------------------------------------------------------- processos
    static class Proc {
        final String name;
        final Path dir, log;
        Process p;

        Proc(String name, Path dir, String cp, String main, List<String> jvm, String... args) throws IOException {
            this.name = name;
            this.dir = dir;
            Files.createDirectories(dir);
            this.log = dir.resolve(name + "-" + System.nanoTime() + ".log");
            List<String> cmd = new ArrayList<>(List.of("java", "-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8"));
            cmd.addAll(jvm);
            cmd.addAll(List.of("-cp", cp, main));
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            p = pb.start();
        }

        String text() {
            try {
                return Files.readString(log, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }

        long count(String s) {
            long c = 0;
            for (String l : text().split("\n")) if (l.contains(s)) c++;
            return c;
        }

        boolean waitFor(String s, long ms) throws InterruptedException {
            return waitCount(s, 1, ms);
        }

        boolean waitCount(String s, long n, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                if (count(s) >= n) return true;
                Thread.sleep(50);
            }
            return false;
        }

        void send(String line) throws IOException {
            OutputStream o = p.getOutputStream();
            o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
        }

        void kill() throws InterruptedException {
            p.destroyForcibly();
            p.waitFor();
        }

        List<String> lines(String contains) {
            List<String> out = new ArrayList<>();
            for (String l : text().split("\n")) if (l.contains(contains)) out.add(l.trim());
            return out;
        }
    }

    static List<Proc> all = new ArrayList<>();

    static Proc spawn(String name, Path dir, String cp, String main, List<String> jvm, String... args) throws IOException {
        Proc pr = new Proc(name, dir, cp, main, jvm, args);
        all.add(pr);
        return pr;
    }

    static Proc mock(int port, int... peers) throws Exception {
        if (Boolean.getBoolean("real") && peers.length == 0 && port == 5001) return null;   // usa o daemon Kubo real
        String[] a = new String[1 + peers.length];
        a[0] = "" + port;
        for (int i = 0; i < peers.length; i++) a[i + 1] = "" + peers[i];
        Proc m = spawn("mock" + port, root, base.resolve("teste/out").toString(), "MockIpfs", List.of(), a);
        if (!m.waitFor("MockIpfs em", 5000)) throw new IllegalStateException("mock não arrancou");
        return m;
    }

    static Proc replica(Path wd, String id, String parts, int api, String... props) throws Exception {
        Proc r = spawn("replica" + id, wd, CP_REP, "rep02.PartitionReplica", List.of(props), id, parts, "" + api);
        String[] ks = parts.split(",");
        r.waitCount("subscreve", ks.length, 5000);
        sleep(1500);   // a linha "subscreve" é escrita antes de o pedido pubsub/sub estar ativo no nó
        return r;
    }

    static Proc leader(Path wd, int k, int n, String addr, int api, String... props) throws Exception {
        Proc l = spawn("lider" + k, wd, CP_LID, "rep02.PartitionLeader", List.of(props),
                "" + k, "" + n, addr, "http://127.0.0.1:" + api + "/api/v0");
        l.waitFor("anuncia", 5000);
        sleep(1500);   // idem: a subscrição de rep02-in-p<k> só fica ativa depois de "anuncia"
        return l;
    }

    static Proc producer(Path wd, int n, int api) throws Exception {
        Proc p = spawn("produtor", wd, CP_PROD, "rep02.Producer", List.of(), "" + n, "http://127.0.0.1:" + api + "/api/v0");
        p.waitFor("Produtor pronto", 5000);
        return p;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void killAll() {
        for (Proc p : all) p.p.destroyForcibly();
        for (Proc p : all) {
            try { p.p.waitFor(); } catch (InterruptedException ignored) { }
        }
        all.clear();
        sleep(500);
    }

    // ---------------------------------------------------------------- utilitários
    static List<String> fileLines(Path f) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.exists(f)) return out;
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!l.isBlank()) out.add(l.trim());
        return out;
    }

    static String compare(Path leader, Path replica) throws IOException {
        List<String> a = fileLines(leader), b = fileLines(replica);
        boolean prefix = a.size() >= b.size() && a.subList(0, b.size()).equals(b);
        String st = !prefix ? "DIVERGE" : a.size() == b.size() ? "IGUAL" : "PREFIXO";
        return replica.getFileName() + ": " + b.size() + "/" + a.size() + " " + st + " (faltam " + (a.size() - b.size()) + ")";
    }

    /** espera até todas as réplicas igualarem o líder, ou até não haver progresso durante quiet ms. */
    static void converge(Path leader, List<Path> reps, long maxMs, long quietMs) throws IOException {
        long end = System.currentTimeMillis() + maxMs, lastChange = System.currentTimeMillis();
        long prev = -1;
        while (System.currentTimeMillis() < end) {
            long tot = 0;
            boolean eq = true;
            int ls = fileLines(leader).size();
            for (Path r : reps) {
                int n = fileLines(r).size();
                tot += n;
                if (n != ls) eq = false;
            }
            if (eq && ls > 0) return;
            if (tot != prev) {
                prev = tot;
                lastChange = System.currentTimeMillis();
            }
            if (System.currentTimeMillis() - lastChange > quietMs) return;
            sleep(100);
        }
    }

    static long num(String line, String key) {
        Matcher m = Pattern.compile(Pattern.quote(key) + "=(-?\\d+)").matcher(line);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    static void out(String s) {
        System.out.println(s);
    }

    static Path wd(String scen, String name) {
        return root.resolve(scen).resolve(name);
    }

    static void coletar(Path dest, Path... dirs) throws IOException {
        Files.createDirectories(dest);
        for (Path d : dirs) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.txt")) {
                for (Path f : ds) Files.copy(f, dest.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static String check(int n, Path folder) throws Exception {
        Proc c = spawn("check", folder, CP_REP, "rep02.PartitionCheck", List.of(), "" + n, folder.toString(), n == 1 ? "3" : "2");   // com N = 1 as 3 réplicas guardam p0
        c.p.waitFor();
        return c.text();
    }

    static void rajada(Proc prod, String cmd, long waitMs) throws Exception {
        long before = prod.count("Rajada de");
        prod.send(cmd);
        prod.waitCount("Rajada de", before + 1, waitMs);
    }

    // ---------------------------------------------------------------- Parte A
    static void a_a() throws Exception {
        String s = "A-a";
        out("### " + s + ": 5 leituras, quatro ficheiros iguais");
        mock(5001);
        Path lw = wd(s, "lider");
        List<Path> reps = new ArrayList<>();
        for (int i = 1; i <= 3; i++) { replica(wd(s, "r" + i), "" + i, "0", 5001); reps.add(wd(s, "r" + i).resolve("replica-" + i + "-p0.txt")); }
        leader(lw, 0, 1, "127.0.0.1", 5001);
        Proc pr = producer(wd(s, "prod"), 1, 5001);
        for (int i = 0; i < 5; i++) pr.send("S0" + (i + 1) + " " + (20 + i));
        converge(lw.resolve("lider-p0.txt"), reps, 10000, 3000);
        for (Path r : reps) out("  " + compare(lw.resolve("lider-p0.txt"), r));
    }

    static void a_b() throws Exception {
        String s = "A-b";
        out("### " + s + ": réplica 2 parada; retidos e primeira recuperação");
        mock(5001);
        Path lw = wd(s, "lider"), lf = lw.resolve("lider-p0.txt");
        Proc r1 = replica(wd(s, "r1"), "1", "0", 5001);
        Proc r2 = replica(wd(s, "r2"), "2", "0", 5001);
        leader(lw, 0, 1, "127.0.0.1", 5001);
        Proc pr = producer(wd(s, "prod"), 1, 5001);
        for (int i = 1; i <= 5; i++) pr.send("S01 " + (20 + i));
        converge(lf, List.of(wd(s, "r2").resolve("replica-2-p0.txt")), 8000, 2000);
        r2.kill();
        for (int i = 6; i <= 8; i++) pr.send("S01 " + (20 + i));
        sleep(1500);
        r2 = replica(wd(s, "r2"), "2", "0", 5001);
        for (int i = 9; i <= 10; i++) pr.send("S01 " + (20 + i));
        sleep(1500);
        Path f2 = wd(s, "r2").resolve("replica-2-p0.txt");
        out("  após 2 leituras (seq 9,10): " + compare(lf, f2) + "; EM ESPERA=" + r2.count("EM ESPERA")
                + "; recuperações=" + r2.count("pedida a"));
        int seq = 10;
        while (r2.count("pedida a") == 0 && seq < 40) {
            seq++;
            pr.send("S01 " + (20 + seq));
            sleep(150);
        }
        sleep(1500);
        out("  recuperação desencadeada pela leitura seq=" + seq + " (retidos nesse momento: " + (seq - 8) + ")");
        for (String l : r2.lines("RECUPERA")) out("    " + l);
        converge(lf, List.of(f2), 8000, 2000);
        out("  fim: " + compare(lf, f2));
    }

    static void a_c() throws Exception {
        String s = "A-c";
        out("### " + s + ": perda da última leitura da série (-Ddescartar)");
        mock(5001);
        Path lw = wd(s, "lider"), lf = lw.resolve("lider-p0.txt");
        replica(wd(s, "r1"), "1", "0", 5001);
        Proc r3 = replica(wd(s, "r3"), "3", "0", 5001, "-Ddescartar=6");
        leader(lw, 0, 1, "127.0.0.1", 5001);
        Proc pr = producer(wd(s, "prod"), 1, 5001);
        for (int i = 1; i <= 5; i++) pr.send("S01 " + (20 + i));
        sleep(1500);
        pr.send("S01 99");
        sleep(2500);
        Path f3 = wd(s, "r3").resolve("replica-3-p0.txt");
        out("  após a leitura 6 (descartada na réplica 3): " + compare(lf, f3));
        int extra = 0;
        while (r3.count("pedida a") == 0 && extra < 30) {
            extra++;
            pr.send("S01 " + (30 + extra));
            sleep(150);
        }
        sleep(1500);
        converge(lf, List.of(f3), 8000, 2000);
        out("  leituras adicionais até à recuperação: " + extra + "; fim: " + compare(lf, f3));
        for (String l : r3.lines("RECUPERA")) out("    " + l);
    }

    /** rajada 1000 10 três vezes com perda 0.2 na réplica 3 (nós: 1 ou 2). */
    static void a_d(boolean doisNos) throws Exception {
        String s = doisNos ? "A-f" : "A-d";
        out("### " + s + ": rajada 1000 10 x3, réplica 3 com -Dperda=0.2" + (doisNos ? ", dois nós IPFS" : ""));
        if (!Boolean.getBoolean("real")) { if (doisNos) { mock(5001, 5002); mock(5002, 5001); } else mock(5001); }
        int apiR = doisNos ? 5002 : 5001;
        Path lw = wd(s, "lider"), lf = lw.resolve("lider-p0.txt");
        replica(wd(s, "r1"), "1", "0", 5001);
        replica(wd(s, "r2"), "2", "0", apiR);
        Proc r3 = replica(wd(s, "r3"), "3", "0", apiR, "-Dperda=0.2");
        leader(lw, 0, 1, "127.0.0.1", 5001);
        Proc pr = producer(wd(s, "prod"), 1, 5001);
        for (int run = 1; run <= 3; run++) {
            rajada(pr, "rajada 1000 10", 120000);
            sleep(3500);
            String env = pr.lines("Rajada de").get(run - 1);
            out("  execução " + run + ": produtor: " + env);
            List<String> rj = r3.lines("RAJADA p0");
            out("    réplica 3: " + (rj.size() >= run ? rj.get(run - 1) : "(sem linha RAJADA)"));
            List<String> lj = new ArrayList<>(); // líder
            for (Proc p : all) if (p.name.equals("lider0")) lj = p.lines("RAJADA p0");
            out("    líder: " + (lj.size() >= run ? lj.get(run - 1) : "(sem linha)"));
            if (rj.size() >= run) {
                long first = num(env, "primeiro envio");
                out("    tempo até à convergência (1.ª publicação → última escrita da réplica 3): "
                        + (num(rj.get(run - 1), "última escrita") - first) + " ms");
            }
            out("    " + compare(lf, wd(s, "r3").resolve("replica-3-p0.txt")));
            out("    " + compare(lf, wd(s, "r1").resolve("replica-1-p0.txt")));
            out("    " + compare(lf, wd(s, "r2").resolve("replica-2-p0.txt")));
        }
    }

    /** colunas "sem perdas" da tabela de recuperação: rajada 1000 10 sem perdas simuladas. */
    static void a_semperdas() throws Exception {
        String s = "A-semperdas";
        out("### " + s + ": rajada 1000 10 x3 sem perdas simuladas (COMINDIR02)");
        mock(5001);
        Path lw = wd(s, "lider"), lf = lw.resolve("lider-p0.txt");
        Proc[] r = new Proc[3];
        for (int i = 1; i <= 3; i++) r[i - 1] = replica(wd(s, "r" + i), "" + i, "0", 5001);
        leader(lw, 0, 1, "127.0.0.1", 5001);
        Proc pr = producer(wd(s, "prod"), 1, 5001);
        for (int run = 1; run <= 3; run++) {
            rajada(pr, "rajada 1000 10", 120000);
            sleep(3500);
            String env = pr.lines("Rajada de").get(run - 1);
            out("  execução " + run + ": " + env);
            long worst = 0;
            for (int i = 0; i < 3; i++) {
                List<String> rj = r[i].lines("RAJADA p0");
                if (rj.size() >= run) {
                    worst = Math.max(worst, num(rj.get(run - 1), "última escrita"));
                    out("    réplica " + (i + 1) + ": " + rj.get(run - 1));
                }
            }
            out("    tempo até à convergência: " + (worst - num(env, "primeiro envio")) + " ms");
            for (Proc p : all) if (p.name.equals("lider0")) out("    líder: " + (p.lines("RAJADA p0").size() >= run ? p.lines("RAJADA p0").get(run - 1) : "(sem linha)"));
        }
    }

    static void base01() throws Exception {
        String s = "base01";
        out("### " + s + ": COMINDIR01 (PubSub, um nó), rajada 1000 x3, três réplicas");
        mock(5001);
        Proc[] r = new Proc[3];
        for (int i = 1; i <= 3; i++) {
            r[i - 1] = spawn("rep01-r" + i, wd(s, "r" + i), CP_01, "rep01.PubSubReplica", List.of(), "" + i, "5001");
            r[i - 1].waitFor("subscreve", 5000);
        }
        Proc l = spawn("rep01-lider", wd(s, "lider"), CP_01, "rep01.PubSubLeader", List.of(), "http://127.0.0.1:5001/api/v0");
        l.waitFor("Líder pronto", 5000);
        for (int run = 1; run <= 3; run++) {
            long before = l.count("Rajada de");
            l.send("rajada 1000");
            l.waitCount("Rajada de", before + 1, 120000);
            sleep(3500);
            String env = l.lines("Rajada de").get(run - 1);
            out("  execução " + run + ": " + env);
            long worst = 0;
            for (int i = 0; i < 3; i++) {
                List<String> rj = r[i].lines("RAJADA:");
                if (rj.size() >= run) worst = Math.max(worst, num(rj.get(run - 1), "última receção"));
            }
            out("    tempo até à convergência: " + (worst - num(env, "primeiro envio")) + " ms");
        }
    }

    static void a_e() throws Exception {
        String s = "A-e";
        out("### " + s + ": réplica nova (ficheiro apagado) recupera o histórico");
        mock(5001);
        Path lw = wd(s, "lider"), lf = lw.resolve("lider-p0.txt");
        replica(wd(s, "r1"), "1", "0", 5001);
        Proc r3 = replica(wd(s, "r3"), "3", "0", 5001);
        leader(lw, 0, 1, "127.0.0.1", 5001);
        Proc pr = producer(wd(s, "prod"), 1, 5001);
        rajada(pr, "rajada 200 10", 60000);
        sleep(2500);
        r3.kill();
        Files.deleteIfExists(wd(s, "r3").resolve("replica-3-p0.txt"));
        r3 = replica(wd(s, "r3"), "3", "0", 5001);
        rajada(pr, "rajada 20 10", 60000);
        long t0 = num(pr.lines("Rajada de").get(1), "primeiro envio");
        sleep(3500);
        Path f3 = wd(s, "r3").resolve("replica-3-p0.txt");
        out("  " + compare(lf, f3));
        List<String> rj = r3.lines("RAJADA p0");
        out("  " + (rj.isEmpty() ? "(sem linha)" : rj.get(rj.size() - 1)));
        if (!rj.isEmpty()) out("  convergência: " + (num(rj.get(rj.size() - 1), "última escrita") - t0) + " ms desde a 1.ª publicação");
        for (String l : r3.lines("pedida a")) out("    " + l);
    }

    // ---------------------------------------------------------------- Parte B
    static class Sys3 {
        String s;
        int n;
        Proc[] leaders;
        Proc[] reps = new Proc[3];
        Proc prod;
        Path[] lw, rw;

        Sys3(String s, int n, String perda3) throws Exception {
            this.s = s;
            this.n = n;
            mock(5001);
            rw = new Path[3];
            String[] parts = n == 1 ? new String[] { "0", "0", "0" } : new String[] { "0,1", "1,2", "2,0" };
            for (int i = 1; i <= 3; i++) {
                rw[i - 1] = wd(s, "r" + i);
                reps[i - 1] = (i == 3 && perda3 != null)
                        ? replica(rw[i - 1], "" + i, parts[i - 1], 5001, "-Dperda=" + perda3)
                        : replica(rw[i - 1], "" + i, parts[i - 1], 5001);
            }
            leaders = new Proc[n];
            lw = new Path[n];
            for (int k = 0; k < n; k++) {
                lw[k] = wd(s, "lider" + k);
                leaders[k] = leader(lw[k], k, n, "127.0.0.1", 5001);
            }
            prod = producer(wd(s, "prod"), n, 5001);
        }

        Path collect() throws IOException {
            Path dest = wd(s, "coleta-" + System.nanoTime());
            List<Path> dirs = new ArrayList<>(Arrays.asList(lw));
            dirs.addAll(Arrays.asList(rw));
            coletar(dest, dirs.toArray(new Path[0]));
            return dest;
        }

        void settle(long ms) throws IOException {
            sleep(ms);
        }

        void waitConverged(long maxMs) throws IOException {
            long end = System.currentTimeMillis() + maxMs;
            long stable = 0;
            String prev = "";
            while (System.currentTimeMillis() < end) {
                StringBuilder sb = new StringBuilder();
                for (Path d : lw) sb.append(fileLines(d.resolve(d.getFileName().toString().replace("lider", "lider-p") + ".txt")).size()).append(',');
                for (Path d : rw) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "replica-*.txt")) {
                        for (Path f : ds) sb.append(fileLines(f).size()).append(',');
                    }
                }
                if (sb.toString().equals(prev)) { if (++stable >= 20) return; } else { stable = 0; prev = sb.toString(); }
                sleep(100);
            }
        }
    }

    static void b_a() throws Exception {
        String s = "B-a";
        out("### " + s + ": S01..S06, N=3");
        Sys3 x = new Sys3(s, 3, null);
        for (int i = 1; i <= 6; i++) x.prod.send("S0" + i + " " + (20 + i));
        sleep(1000);
        for (String l : x.prod.lines("Publicado")) out("  " + l);
        out("  portos de recuperação: " + portsListening());
        x.waitConverged(10000);
        out(check(3, x.collect()));
    }

    static String portsListening() {
        StringBuilder sb = new StringBuilder();
        for (int p = 7000; p <= 7002; p++) {
            try (java.net.Socket sk = new java.net.Socket()) {
                sk.connect(new java.net.InetSocketAddress("127.0.0.1", p), 500);
                sb.append(p).append(" à escuta; ");
            } catch (IOException e) {
                sb.append(p).append(" FECHADO; ");
            }
        }
        return sb.toString();
    }

    static void b_burst(String s, int n, String cmd, String perda3, int runs) throws Exception {
        Sys3 x = new Sys3(s, n, perda3);
        for (int run = 1; run <= runs; run++) {
            long conv0 = System.currentTimeMillis();
            rajada(x.prod, cmd, 300000);
            x.waitConverged(60000);
            sleep(3000);
            String env = x.prod.lines("Rajada de").get(run - 1);
            out("  execução " + run + ": " + env);
            long worst = 0;
            for (int i = 0; i < 3; i++) {
                for (String l : x.reps[i].lines("RAJADA p")) {
                    // última linha RAJADA de cada partição referente a esta execução
                }
            }
            // último instante de escrita por réplica/partição nesta execução = máximo "última escrita" das linhas RAJADA recentes
            long first = num(env, "primeiro envio");
            for (int i = 0; i < 3; i++) {
                List<String> rj = x.reps[i].lines("RAJADA p");
                // as `run`-ésimas linhas por partição
                Map<String, Integer> seen = new HashMap<>();
                for (String l : rj) {
                    String p = l.substring(0, l.indexOf(':'));
                    int c = seen.merge(p, 1, Integer::sum);
                    if (c == run) {
                        worst = Math.max(worst, num(l, "última escrita"));
                        out("    réplica " + (i + 1) + " " + l);
                    }
                }
            }
            out("    tempo até à convergência: " + (worst - first) + " ms");
            for (int k = 0; k < n; k++) {
                List<String> lj = x.leaders[k].lines("RAJADA p");
                if (lj.size() >= run) out("    líder " + lj.get(run - 1));
            }
        }
        out(check(n, x.collect()));
    }

    static void b_b() throws Exception {
        out("### B-b: rajada 3000 20 x3, N=3 uniforme");
        b_burst("B-b", 3, "rajada 3000 20", null, 3);
    }

    static void b_c() throws Exception {
        out("### B-c: rajada-quente 3000 20 x3, N=3 chave quente");
        b_burst("B-c", 3, "rajada-quente 3000 20", null, 3);
    }

    static void b_f() throws Exception {
        out("### B-f: rajada 3000 20 x3, N=1");
        b_burst("B-f", 1, "rajada 3000 20", null, 3);
    }

    static void b_d() throws Exception {
        String s = "B-d";
        out("### " + s + ": líder da partição 1 parado a meio de uma rajada");
        Sys3 x = new Sys3(s, 3, null);
        x.prod.send("rajada 3000 20");
        sleep(1200);
        x.leaders[1].kill();
        out("  líder p1 parado; leituras registadas até agora: " + fileLines(x.lw[1].resolve("lider-p1.txt")).size());
        x.prod.waitFor("Rajada de", 120000);
        out("  produtor: " + x.prod.lines("Rajada de").get(0) + " (sem exceções: " + x.prod.count("IO:") + " erros)");
        long p1 = fileLines(x.lw[1].resolve("lider-p1.txt")).size();
        long p0 = fileLines(x.lw[0].resolve("lider-p0.txt")).size();
        long p2 = fileLines(x.lw[2].resolve("lider-p2.txt")).size();
        out("  após a rajada: p0=" + p0 + " p1=" + p1 + " p2=" + p2 + " (p1 parada)");
        sleep(2000);
        x.leaders[1] = leader(x.lw[1], 1, 3, "127.0.0.1", 5001);
        x.prod.send("S01 20");  // S01 → partição?
        x.prod.send("rajada 60 20");
        x.waitConverged(30000);
        sleep(3000);
        out("  p1 depois de reiniciar e de 60 leituras novas: " + fileLines(x.lw[1].resolve("lider-p1.txt")).size());
        for (String l : x.leaders[1].lines("Registado")) { /* ignorado */ }
        for (int i = 0; i < 3; i++) for (String l : x.reps[i].lines("pedida a")) out("  réplica " + (i + 1) + ": " + l);
        out(check(3, x.collect()));
    }

    static void b_e() throws Exception {
        String s = "B-e";
        out("### " + s + ": réplica 3 com perda 0.2, réplica 1 parada a meio de uma rajada");
        Sys3 x = new Sys3(s, 3, "0.2");
        x.prod.send("rajada 3000 20");
        sleep(1200);
        x.reps[0].kill();
        sleep(2000);
        x.reps[0] = replica(x.rw[0], "1", "0,1", 5001);
        x.prod.waitFor("Rajada de", 120000);
        sleep(3000);
        out("  antes de leituras extra:");
        String c0 = check(3, x.collect());
        for (String l : c0.split("\n")) if (l.contains("réplica") || l.contains("Partição")) out(l);
        x.prod.send("rajada 60 20");   // leituras posteriores desencadeiam a recuperação do que falta
        x.prod.waitCount("Rajada de", 2, 60000);
        x.waitConverged(30000);
        sleep(3000);
        for (int i = 0; i < 3; i++) {
            Map<String, Integer> con = new TreeMap<>();
            for (String l : x.reps[i].lines("pedida a")) con.merge("p?", 1, Integer::sum);
            out("  réplica " + (i + 1) + ": ligações de recuperação=" + x.reps[i].count("pedida a") + ", registos via TCP=" + x.reps[i].count("RECUPERADO:")
                    + (i == 0 ? " (processo reiniciado: só conta desde o reinício)" : ""));
        }
        out("  depois de leituras extra:");
        out(check(3, x.collect()));
    }

    // ---------------------------------------------------------------- Tabela de falhas (ponto 14)
    static void f_endereco() throws Exception {
        String s = "F-endereco";
        out("### " + s + ": líder anuncia 10.255.255.1");
        mock(5001);
        Path lw = wd(s, "lider"), lf = lw.resolve("lider-p0.txt");
        Proc r1 = replica(wd(s, "r1"), "1", "0", 5001, "-Dperda=0.5");
        leader(lw, 0, 1, "10.255.255.1", 5001);
        Proc pr = producer(wd(s, "prod"), 1, 5001);
        rajada(pr, "rajada 60 5", 60000);
        converge(lf, List.of(wd(s, "r1").resolve("replica-1-p0.txt")), 25000, 12000);
        sleep(1000);
        for (String l : r1.lines("RECUPERA")) out("  " + l);
        out("  " + compare(lf, wd(s, "r1").resolve("replica-1-p0.txt")));
    }

    static void f_porto() throws Exception {
        String s = "F-porto";
        out("### " + s + ": porto 7000 ocupado");
        mock(5001);
        Proc l1 = leader(wd(s, "lider-a"), 0, 1, "127.0.0.1", 5001);
        Proc l2 = spawn("lider-b", wd(s, "lider-b"), CP_LID, "rep02.PartitionLeader", List.of(), "0", "1", "127.0.0.1", "http://127.0.0.1:5001/api/v0");
        l2.p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        out("  segundo líder: terminou=" + !l2.p.isAlive() + " código=" + (l2.p.isAlive() ? "-" : l2.p.exitValue()));
        for (String l : l2.text().split("\n")) out("  " + l.trim());
    }

    static void f_semfim() throws Exception {
        String s = "F-semfim";
        out("### " + s + ": líder fecha a ligação a meio da resposta, sem FIM");
        mock(5001);
        Path lw = wd(s, "lider"), lf = lw.resolve("lider-p0.txt");
        Proc r1 = replica(wd(s, "r1"), "1", "0", 5001, "-Dperda=0");
        Proc r2 = replica(wd(s, "r2"), "2", "0", 5001);
        leader(lw, 0, 1, "127.0.0.1", 5001, "-Dsemfim=true");
        Proc pr = producer(wd(s, "prod"), 1, 5001);
        for (int i = 1; i <= 3; i++) pr.send("S01 " + (20 + i));
        sleep(1500);
        r2.kill();
        for (int i = 4; i <= 14; i++) pr.send("S01 " + (20 + i));    // 11 leituras com a réplica parada
        sleep(1500);
        r2 = replica(wd(s, "r2"), "2", "0", 5001);
        for (int i = 15; i <= 40; i++) pr.send("S01 " + (20 + i));   // retidos acima de 10 → recuperação (que falha)
        sleep(2500);
        Path f2 = wd(s, "r2").resolve("replica-2-p0.txt");
        for (String l : r2.lines("RECUPERA")) out("  " + l);
        out("  " + compare(lf, f2));
        out("  retidos ainda em memória: " + (r2.lines("RAJADA").isEmpty() ? "?" : r2.lines("RAJADA").get(r2.lines("RAJADA").size() - 1)));
    }

    static void f_apagado() throws Exception {
        String s = "F-apagado";
        out("### " + s + ": lider-p0.txt apagado, réplicas intactas, líder reiniciado");
        mock(5001);
        Path lw = wd(s, "lider"), lf = lw.resolve("lider-p0.txt");
        Proc r1 = replica(wd(s, "r1"), "1", "0", 5001);
        Proc ld = leader(lw, 0, 1, "127.0.0.1", 5001);
        Proc pr = producer(wd(s, "prod"), 1, 5001);
        for (int i = 1; i <= 5; i++) pr.send("S01 " + (20 + i));
        sleep(1500);
        ld.kill();
        Files.delete(lf);
        ld = leader(lw, 0, 1, "127.0.0.1", 5001);
        for (int i = 1; i <= 15; i++) pr.send("S01 " + (50 + i));
        sleep(2500);
        out("  líder: " + fileLines(lf).size() + " registos (seq reiniciou em 1)");
        out("  réplica: DUPLICADO=" + r1.count("DUPLICADO") + ", EM ESPERA=" + r1.count("EM ESPERA") + ", recuperações=" + r1.count("pedida a"));
        out("  " + compare(lf, wd(s, "r1").resolve("replica-1-p0.txt")));
    }

    static void f_n4() throws Exception {
        String s = "F-n4";
        out("### " + s + ": produtor com N=4, líderes com N=3");
        mock(5001);
        Path[] lw = new Path[3];
        Proc[] ld = new Proc[3];
        for (int k = 0; k < 3; k++) { lw[k] = wd(s, "lider" + k); ld[k] = leader(lw[k], k, 3, "127.0.0.1", 5001); }
        Proc pr = producer(wd(s, "prod"), 4, 5001);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) pr.send(String.format("S%02d 20", i));
        sleep(2500);
        int tot = 0;
        for (int k = 0; k < 3; k++) {
            long reg = fileLines(lw[k].resolve("lider-p" + k + ".txt")).size();
            tot += reg;
            out("  líder p" + k + ": registados=" + reg + ", PARTIÇÃO ERRADA=" + ld[k].count("PARTIÇÃO ERRADA"));
        }
        out("  das 20 leituras, registadas=" + tot + "; enviadas para p3 (sem líder) = " + pr.count("→ p3"));
        for (String l : ld[0].lines("PARTIÇÃO ERRADA")) out("    " + l);
    }

    static void f_duas() throws Exception {
        String s = "F-duas";
        out("### " + s + ": duas instâncias de PartitionReplica 2 1,2 na mesma pasta");
        mock(5001);
        Path rw = wd(s, "r2");
        Path[] lw = new Path[3];
        for (int k = 0; k < 3; k++) { lw[k] = wd(s, "lider" + k); leader(lw[k], k, 3, "127.0.0.1", 5001); }
        Proc a = replica(rw, "2", "1,2", 5001);
        Proc b = replica(rw, "2", "1,2", 5001);
        Proc pr = producer(wd(s, "prod"), 3, 5001);
        for (int i = 1; i <= 6; i++) pr.send(String.format("S%02d 20", i));
        sleep(3000);
        for (int k = 1; k <= 2; k++) {
            List<String> rl = fileLines(rw.resolve("replica-2-p" + k + ".txt"));
            out("  " + compare(lw[k].resolve("lider-p" + k + ".txt"), rw.resolve("replica-2-p" + k + ".txt")));
            for (String l : rl) out("      " + l);
        }
    }

    // ---------------------------------------------------------------- main
    public static void main(String[] args) throws Exception {
        root = Paths.get(args[0]).toAbsolutePath();
        Map<String, ThrowingRunnable> sc = new LinkedHashMap<>();
        sc.put("A-a", Cenarios::a_a);
        sc.put("A-b", Cenarios::a_b);
        sc.put("A-c", Cenarios::a_c);
        sc.put("A-d", () -> a_d(false));
        sc.put("A-semperdas", Cenarios::a_semperdas);
        sc.put("A-e", Cenarios::a_e);
        sc.put("A-f", () -> a_d(true));
        sc.put("base01", Cenarios::base01);
        sc.put("B-a", Cenarios::b_a);
        sc.put("B-b", Cenarios::b_b);
        sc.put("B-c", Cenarios::b_c);
        sc.put("B-d", Cenarios::b_d);
        sc.put("B-e", Cenarios::b_e);
        sc.put("B-f", Cenarios::b_f);
        sc.put("F-endereco", Cenarios::f_endereco);
        sc.put("F-porto", Cenarios::f_porto);
        sc.put("F-semfim", Cenarios::f_semfim);
        sc.put("F-apagado", Cenarios::f_apagado);
        sc.put("F-n4", Cenarios::f_n4);
        sc.put("F-duas", Cenarios::f_duas);
        List<String> chosen = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("todos")) chosen.addAll(sc.keySet()); else chosen.add(args[i]);
        }
        for (String c : chosen) {
            Path d = root.resolve(c);
            if (Files.exists(d)) {
                try (var w = Files.walk(d)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
            }
            try {
                sc.get(c).run();
            } catch (Exception e) {
                out("  ERRO no cenário " + c + ": " + e);
                e.printStackTrace(System.out);
            } finally {
                killAll();
            }
            System.out.flush();
        }
    }

    interface ThrowingRunnable { void run() throws Exception; }
}