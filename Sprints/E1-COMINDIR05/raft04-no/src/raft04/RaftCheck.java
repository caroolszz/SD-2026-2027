package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// NOVO na COMINDIR05. Análise a posteriori de um ensaio (passo 8 da ficha).
// Executado como: RaftCheck <N> <pasta> [falhas.txt]
// Lê da pasta os ficheiros no-*, estado-*, hb-*, eventos-*, truncados-*, monitor-* e produtor.txt e
// escreve no ecrã e em <pasta>/relatorio-raft.txt:
//   1. por partição, a sequência de termos: candidatos, votos, líder ou "voto dividido", duração da eleição;
//   2. a segurança da eleição: termos com mais de um LIDER (tem de ser 0) e termo nunca decrescente em cada nó;
//   3. o tempo até ao primeiro líder de cada partição, desde o arranque do primeiro nó;
//   4. os períodos de liderança e os líderes simultâneos (em termos diferentes), ensaio 9(d);
//   5. com falhas.txt (linhas p<k>;n<i>;instante): a indisponibilidade de cada falha e as leituras enviadas nela;
//   6. as leituras perdidas (linhas de produtor.txt que não estão no registo final de nenhum nó);
//   7. os eventos TRUNCADO e quantos registos descartados existem, com outro seq, no registo do líder final;
//   8. a coincidência final dos três ficheiros de cada partição;
//   9. o que observaram os detetores do produtor e do processador.
// Todos os instantes vêm de System.currentTimeMillis() de processos na MESMA máquina (comparáveis).
public class RaftCheck {
    private static final Locale PT = Locale.forLanguageTag("pt-PT");
    private static final StringBuilder report = new StringBuilder();

    private static void say(String s) {
        System.out.println(s);
        report.append(s).append(System.lineSeparator());
    }

    // um evento de eventos-p<k>-n<i>.txt: instante;TIPO;p<k>;detalhe...
    static final class Ev {
        long t; int k; int node; String type; String[] f; final Map<String, String> kv = new HashMap<>();
        long term(String key) { return kv.containsKey(key) ? Long.parseLong(kv.get(key)) : -1; }
    }

    // uma linha de hb-p<k>-n<i>.txt: termo;hb;instante;ultimoSeq;ultimoTermo;n;bytes
    static final class Hb { int k, node; long term, hb, t, lastSeq, lastTerm, n, bytes; }

    // uma linha de produtor.txt: instante;p<k>;sensor;temperatura
    static final class Prod { long t; int k; String sensor; double temp; boolean matched; }

    // um período de liderança: de LIDER até DEMISSAO, falha, novo ARRANQUE ou fim do ensaio
    static final class Lead { int k, node; long term, start, end; String endReason; }

    private static final Pattern NODE_FILE = Pattern.compile("(no|estado|hb|eventos|truncados)-p(\\d+)-n(\\d+)\\.txt");

    static long t0;                                     // instante de referência (primeiro ARRANQUE)

    static String rel(long t) { return String.format(PT, "t=%.3f s", (t - t0) / 1000.0); }
    static String secs(long ms) { return String.format(PT, "%.3f s", ms / 1000.0); }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Uso: RaftCheck <N> <pasta> [falhas.txt]");
            return;
        }
        int n = Integer.parseInt(args[0]);
        Path dir = Paths.get(args[1]);
        Path falhasPath = args.length > 2 ? Paths.get(args[2]) : null;
        if (falhasPath != null && !falhasPath.isAbsolute() && !Files.exists(falhasPath)) falhasPath = dir.resolve(falhasPath);

        // ===================== leitura =====================
        List<Ev> events = new ArrayList<>();
        List<Hb> hbs = new ArrayList<>();
        Map<String, List<SensorRecord>> logs = new TreeMap<>();          // "k#i" -> registos finais
        Map<String, String> estados = new TreeMap<>();                   // "k#i" -> termo;votouEm
        List<Object[]> truncated = new ArrayList<>();                    // {k, node, instante, SensorRecord}
        int formatErrors = 0;

        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) paths.add(p);
        }
        Collections.sort(paths);
        for (Path p : paths) {
            Matcher m = NODE_FILE.matcher(p.getFileName().toString());
            if (!m.matches()) continue;
            String kind = m.group(1);
            int k = Integer.parseInt(m.group(2)), node = Integer.parseInt(m.group(3));
            String key = k + "#" + node;
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (int li = 0; li < lines.size(); li++) {
                String line = lines.get(li).trim();
                if (line.isEmpty()) continue;
                try {
                    switch (kind) {
                        case "no":
                            logs.computeIfAbsent(key, x -> new ArrayList<>()).add(SensorRecord.fromLine(line));
                            break;
                        case "estado":
                            estados.put(key, line);
                            break;
                        case "hb": {
                            String[] f = line.split(";");
                            Hb h = new Hb();
                            h.k = k; h.node = node; h.term = Long.parseLong(f[0]); h.hb = Long.parseLong(f[1]);
                            h.t = Long.parseLong(f[2]); h.lastSeq = Long.parseLong(f[3]); h.lastTerm = Long.parseLong(f[4]);
                            h.n = Long.parseLong(f[5]); h.bytes = Long.parseLong(f[6]);
                            hbs.add(h);
                            break;
                        }
                        case "eventos": {
                            String[] f = line.split(";");
                            Ev e = new Ev();
                            e.t = Long.parseLong(f[0]); e.type = f[1]; e.k = k; e.node = node; e.f = f;
                            for (int j = 3; j < f.length; j++) {
                                int eq = f[j].indexOf('=');
                                if (eq > 0) e.kv.put(f[j].substring(0, eq), f[j].substring(eq + 1));
                            }
                            events.add(e);
                            break;
                        }
                        case "truncados": {
                            int sc = line.indexOf(';');
                            truncated.add(new Object[] { k, node, Long.parseLong(line.substring(0, sc)),
                                    SensorRecord.fromLine(line.substring(sc + 1)) });
                            break;
                        }
                        default:
                    }
                } catch (RuntimeException ex) {
                    say("ERRO de formato em " + p.getFileName() + ":" + (li + 1) + ": " + ex.getMessage());
                    formatErrors++;
                }
            }
            if (kind.equals("no")) logs.computeIfAbsent(key, x -> new ArrayList<>());
        }
        // ordem estável por instante (dentro do mesmo nó mantém a ordem do ficheiro)
        events.sort(Comparator.comparingLong(e -> e.t));

        List<Prod> prods = new ArrayList<>();
        Path prodFile = dir.resolve("produtor.txt");
        if (Files.exists(prodFile)) {
            for (String line : Files.readAllLines(prodFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] f = line.trim().split(";");
                Prod pr = new Prod();
                pr.t = Long.parseLong(f[0]); pr.k = Integer.parseInt(f[1].substring(1)); pr.sensor = f[2];
                pr.temp = Double.parseDouble(f[3]);
                prods.add(pr);
            }
        }
        List<long[]> falhas = new ArrayList<>();                         // {k, node, instante}
        if (falhasPath != null && Files.exists(falhasPath)) {
            for (String line : Files.readAllLines(falhasPath, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] f = line.trim().split(";");
                falhas.add(new long[] { Long.parseLong(f[0].substring(1)), Long.parseLong(f[1].substring(1)),
                        Long.parseLong(f[2]) });
            }
        } else if (falhasPath != null) {
            say("AVISO: " + falhasPath + " não existe");
        }

        t0 = Long.MAX_VALUE;
        long tEnd = 0;
        for (Ev e : events) {
            if (e.type.equals("ARRANQUE")) t0 = Math.min(t0, e.t);
            tEnd = Math.max(tEnd, e.t);
        }
        if (t0 == Long.MAX_VALUE) t0 = events.isEmpty() ? 0 : events.get(0).t;
        for (Hb h : hbs) tEnd = Math.max(tEnd, h.t);

        say("RaftCheck N=" + n + " pasta=" + dir.toAbsolutePath() + (falhasPath != null ? " falhas=" + falhasPath : ""));
        say("  eventos=" + events.size() + " heartbeats=" + hbs.size() + " ficheiros de registos=" + logs.size()
                + " leituras do produtor=" + prods.size() + " falhas anotadas=" + falhas.size()
                + " registos truncados=" + truncated.size());
        say("  t=0 é o primeiro ARRANQUE de um nó; duração observada " + secs(tEnd - t0));

        long multiLeaderTerms = 0, termDecreases = 0, divergentSeqs = 0, erros = 0;
        for (Ev e : events) if (e.type.equals("ERRO")) erros++;

        // ===================== 1. termos =====================
        say("");
        say("== 1. Termos por partição (candidatos, votos, líder, duração da eleição) ==");
        Map<Integer, String> resumo = new TreeMap<>();
        for (int k = 0; k < n; k++) {
            final int fk = k;
            TreeMap<Long, List<Ev>> cand = new TreeMap<>(), lid = new TreeMap<>();
            Map<Long, Map<Integer, List<Integer>>> votes = new HashMap<>();   // termo -> candidato -> eleitores
            for (Ev e : events) {
                if (e.k != fk) continue;
                if (e.type.equals("CANDIDATO")) cand.computeIfAbsent(e.term("termo"), x -> new ArrayList<>()).add(e);
                if (e.type.equals("LIDER")) lid.computeIfAbsent(e.term("termo"), x -> new ArrayList<>()).add(e);
                if (e.type.equals("VOTO") && e.f.length > 5 && e.f[5].equals("concedido")) {
                    int c = Integer.parseInt(e.kv.get("candidato").substring(1));
                    votes.computeIfAbsent(e.term("termo"), x -> new HashMap<>())
                         .computeIfAbsent(c, x -> new ArrayList<>()).add(e.node);
                }
            }
            TreeSet<Long> terms = new TreeSet<>(cand.keySet());
            terms.addAll(lid.keySet());
            say("  p" + k + ": " + terms.size() + " termos com candidaturas/líder");
            long firstLeaderTerm = -1, firstLeaderT = -1;
            int splitBeforeFirst = 0;
            for (long t : terms) {
                List<Ev> cs = cand.getOrDefault(t, List.of());
                List<Ev> ls = lid.getOrDefault(t, List.of());
                StringBuilder sb = new StringBuilder();
                Set<Integer> candNodes = new TreeSet<>();
                for (Ev c : cs) candNodes.add(c.node);
                for (int c : candNodes) {
                    List<Integer> vs = new ArrayList<>(votes.getOrDefault(t, Map.of()).getOrDefault(c, List.of()));
                    TreeSet<Integer> voters = new TreeSet<>(vs);
                    voters.add(c);                                       // o voto em si próprio
                    sb.append(" n").append(c).append("=").append(voters.size()).append(" voto(s) ").append(voters);
                }
                long firstCand = cs.isEmpty() ? -1 : cs.stream().mapToLong(e -> e.t).min().getAsLong();
                Set<Integer> leaders = new TreeSet<>();
                for (Ev l : ls) leaders.add(l.node);
                String outcome;
                if (leaders.isEmpty()) {
                    outcome = candNodes.size() >= 2 ? "sem líder: VOTO DIVIDIDO"
                            : "sem líder: candidato sem maioria (isolado ou sem quórum)";
                    if (firstLeaderTerm == -1) splitBeforeFirst++;
                } else {
                    long tl = ls.stream().mapToLong(e -> e.t).min().getAsLong();
                    outcome = "LÍDER " + leaders.stream().map(x -> "n" + x).reduce((a, b) -> a + "," + b).get()
                            + " em " + rel(tl) + (firstCand >= 0 ? ", eleição " + secs(tl - firstCand) : "");
                    if (leaders.size() > 1) {
                        outcome += "  <-- VIOLAÇÃO: " + leaders.size() + " líderes no mesmo termo";
                        multiLeaderTerms++;
                    }
                    if (firstLeaderTerm == -1) { firstLeaderTerm = t; firstLeaderT = tl; }
                }
                say("    termo " + t + ": candidatos" + (candNodes.isEmpty() ? " -" : "")
                        + (firstCand >= 0 ? " (1.º em " + rel(firstCand) + ")" : "") + sb + " -> " + outcome);
            }
            long arr = Long.MAX_VALUE;
            for (Ev e : events) if (e.k == fk && e.type.equals("ARRANQUE")) arr = Math.min(arr, e.t);
            long termsUntil = firstLeaderTerm == -1 ? -1 : terms.headSet(firstLeaderTerm, true).size();
            resumo.put(k, "RESUMO;p" + k + ";primeiroLider_s="
                    + (firstLeaderT == -1 || arr == Long.MAX_VALUE ? "-" : String.format(PT, "%.3f", (firstLeaderT - arr) / 1000.0))
                    + ";termoPrimeiroLider=" + (firstLeaderTerm == -1 ? "-" : firstLeaderTerm)
                    + ";termosAtePrimeiroLider=" + (termsUntil == -1 ? "-" : termsUntil)
                    + ";votosDivididosAntes=" + splitBeforeFirst
                    + ";termosTotal=" + terms.size());
        }

        // ===================== 2. segurança =====================
        say("");
        say("== 2. Segurança da eleição ==");
        say("  termos com mais de um LIDER: " + multiLeaderTerms + " (tem de ser 0)");
        Map<String, Long> cur = new HashMap<>();
        for (Ev e : events) {
            long v;
            switch (e.type) {
                case "ARRANQUE": case "CANDIDATO": case "LIDER": case "VOTO": case "NOVO_LIDER": case "DEMISSAO":
                case "RECEBIDO":
                    v = e.term("termo"); break;
                case "TERMO":
                    v = Long.parseLong(e.f[3].substring(e.f[3].indexOf("->") + 2)); break;
                case "IGNORADO":
                    v = e.kv.containsKey("meuTermo") ? e.term("meuTermo") : e.term("termo"); break;
                default:
                    v = -1;
            }
            if (v < 0) continue;
            String key = e.k + "#" + e.node;
            Long prev = cur.get(key);
            if (prev != null && v < prev) {
                termDecreases++;
                if (termDecreases <= 10) say("  VIOLAÇÃO: termo de n" + e.node + " de p" + e.k + " diminuiu " + prev
                        + " -> " + v + " (" + e.type + " em " + rel(e.t) + ")");
            }
            cur.put(key, prev == null ? v : Math.max(prev, v));
        }
        say("  diminuições do termo num nó: " + termDecreases + " (tem de ser 0)");
        for (Map.Entry<String, String> e : estados.entrySet()) {
            String[] kn = e.getKey().split("#");
            say("  estado-p" + kn[0] + "-n" + kn[1] + ".txt = " + e.getValue() + " (termo;votouEm)"
                    + (cur.containsKey(e.getKey()) && Long.parseLong(e.getValue().split(";")[0]) < cur.get(e.getKey())
                       ? "  <-- INFERIOR ao maior termo observado (" + cur.get(e.getKey()) + ")" : ""));
        }
        say("  eventos ERRO: " + erros);
        for (Ev e : events) if (e.type.equals("ERRO")) say("    p" + e.k + "-n" + e.node + " " + rel(e.t) + " " + String.join(";", e.f));

        // ===================== 3. primeiro líder =====================
        say("");
        say("== 3. Tempo até ao primeiro líder (desde o arranque do primeiro nó da partição) ==");
        for (String r : resumo.values()) say("  " + r);

        // ===================== 4. períodos de liderança =====================
        say("");
        say("== 4. Períodos de liderança e líderes simultâneos ==");
        List<Lead> leads = new ArrayList<>();
        for (Ev e : events) {
            if (!e.type.equals("LIDER")) continue;
            Lead l = new Lead();
            l.k = e.k; l.node = e.node; l.term = e.term("termo"); l.start = e.t; l.end = tEnd; l.endReason = "fim do ensaio";
            for (Ev x : events) {
                if (x.k != l.k || x.node != l.node || x.t < l.start) continue;
                if (x.type.equals("DEMISSAO") && x.term("termo") == l.term && x.t < l.end) { l.end = x.t; l.endReason = "DEMISSAO"; }
                if (x.type.equals("ARRANQUE") && x.t > l.start && x.t < l.end) { l.end = x.t; l.endReason = "reinício"; }
            }
            for (long[] f : falhas) {
                if (f[0] == l.k && f[1] == l.node && f[2] >= l.start && f[2] < l.end) { l.end = f[2]; l.endReason = "falha"; }
            }
            leads.add(l);
        }
        for (int k = 0; k < n; k++) {
            say("  p" + k + ":");
            for (Lead l : leads) {
                if (l.k != k) continue;
                long hbCount = hbs.stream().filter(h -> h.k == l.k && h.node == l.node && h.term == l.term).count();
                say("    n" + l.node + " termo " + l.term + ": " + rel(l.start) + " .. " + rel(l.end) + " ("
                        + secs(l.end - l.start) + ", fim: " + l.endReason + ", lotes publicados=" + hbCount + ")");
            }
            for (int a = 0; a < leads.size(); a++) {
                for (int b = a + 1; b < leads.size(); b++) {
                    Lead x = leads.get(a), y = leads.get(b);
                    if (x.k != k || y.k != k || x.node == y.node) continue;
                    long s = Math.max(x.start, y.start), e = Math.min(x.end, y.end);
                    if (e > s) {
                        long assigned = 0;
                        for (Object[] tr : truncated) {
                            SensorRecord r = (SensorRecord) tr[3];
                            if ((int) tr[0] == k && (r.getTerm() == x.term || r.getTerm() == y.term)) {
                                long rt = r.epochMillis();
                                if (rt >= s && rt <= e) assigned++;
                            }
                        }
                        say("    LÍDERES SIMULTÂNEOS: n" + x.node + " (termo " + x.term + ") e n" + y.node + " (termo "
                                + y.term + ") de " + rel(s) + " a " + rel(e) + " (" + secs(e - s) + "); registos"
                                + " atribuídos nesse intervalo e depois truncados: " + assigned);
                    }
                }
            }
        }

        // ---- arranques e reinícios: havia líder? quanto tempo até haver? (ensaios 9(a) e 9(e)) ----
        say("");
        say("== 4b. Arranques dos nós e tempo até haver líder na partição ==");
        for (Ev e : events) {
            if (!e.type.equals("ARRANQUE")) continue;
            Lead active = null;
            for (Lead l : leads) if (l.k == e.k && l.node != e.node && l.start <= e.t && l.end > e.t) active = l;
            String s;
            if (active != null) {
                s = "já havia líder n" + active.node + " (termo " + active.term + ")";
            } else {
                Ev next = null;
                for (Ev x : events) if (x.k == e.k && x.type.equals("LIDER") && x.t >= e.t) { next = x; break; }
                s = next == null ? "sem líder até ao fim do ensaio"
                        : "líder n" + next.node + " (termo " + next.term("termo") + ") " + secs(next.t - e.t) + " depois";
            }
            say("  p" + e.k + "-n" + e.node + " ARRANQUE em " + rel(e.t) + " com termo=" + e.kv.get("termo")
                    + " ultimoSeq=" + e.kv.get("ultimoSeq") + ": " + s);
        }

        // ===================== correspondência leituras <-> registos =====================
        // registos finais de cada partição (união dos três nós, um por par (seq, termo))
        Map<Integer, List<SensorRecord>> finalRecs = new HashMap<>();
        for (Map.Entry<String, List<SensorRecord>> e : logs.entrySet()) {
            int k = Integer.parseInt(e.getKey().split("#")[0]);
            Map<String, SensorRecord> uniq = new LinkedHashMap<>();
            for (SensorRecord r : finalRecs.getOrDefault(k, List.of())) uniq.put(r.getSeq() + "#" + r.getTerm(), r);
            for (SensorRecord r : e.getValue()) uniq.putIfAbsent(r.getSeq() + "#" + r.getTerm(), r);
            finalRecs.put(k, new ArrayList<>(uniq.values()));
        }
        // emparelhamento guloso: cada leitura com o primeiro registo ainda livre do mesmo sensor e temperatura
        // cujo instante esteja em [envio - 1 s, envio + 15 s]
        prods.sort(Comparator.comparingLong(p -> p.t));
        Set<SensorRecord> used = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Prod p : prods) {
            SensorRecord best = null;
            long bestT = Long.MAX_VALUE;
            for (SensorRecord r : finalRecs.getOrDefault(p.k, List.of())) {
                if (used.contains(r) || !r.getSensorId().equals(p.sensor) || r.getTemperature() != p.temp) continue;
                long rt = r.epochMillis();
                if (rt >= p.t - 1000 && rt <= p.t + 15000 && rt < bestT) { best = r; bestT = rt; }
            }
            if (best != null) { used.add(best); p.matched = true; }
        }

        // ===================== 5. falhas =====================
        if (falhasPath != null) {
            say("");
            say("== 5. Falhas provocadas (falhas.txt): indisponibilidade = falha -> 1.º LOTE de um líder de termo superior ==");
            say("  previsto pela ficha: ]ELEICAO_MIN-Δ, ELEICAO_MAX+VERIFICACAO] = ]"
                    + secs(Wire.ELEICAO_MIN_MS - Wire.PERIODO_HB_MS) + "; " + secs(Wire.ELEICAO_MAX_MS + Wire.VERIFICACAO_MS) + "]");
            for (long[] f : falhas) {
                int k = (int) f[0], node = (int) f[1];
                long tf = f[2];
                Lead l = null;
                for (Lead x : leads) if (x.k == k && x.node == node && x.start <= tf && x.end >= tf) l = x;
                String head = "  p" + k + " n" + node + " em " + rel(tf) + ": ";
                if (l == null) {
                    say(head + "n" + node + " não era líder nesse instante (sem indisponibilidade a medir)");
                    continue;
                }
                final long lt = l.term;
                Hb next = null;
                for (Hb h : hbs) if (h.k == k && h.term > lt && h.t >= tf && (next == null || h.t < next.t)) next = h;
                long until = next == null ? tEnd : next.t;
                long sent = 0, lost = 0;
                for (Prod p : prods) {
                    if (p.k == k && p.t >= tf && p.t <= until) { sent++; if (!p.matched) lost++; }
                }
                say(head + "líder do termo " + lt + "; "
                        + (next == null ? "SEM novo líder até ao fim do ensaio (" + secs(until - tf) + " observados)"
                                        : "novo líder n" + next.node + " termo " + next.term + " (1.º LOTE em " + rel(next.t)
                                          + "), indisponibilidade = " + secs(next.t - tf))
                        + "; leituras enviadas nesse intervalo=" + sent + ", das quais perdidas=" + lost);
            }
        }

        // ===================== 6. leituras perdidas =====================
        say("");
        say("== 6. Leituras perdidas (em produtor.txt mas no registo final de nenhum nó da partição) ==");
        for (int k = 0; k < n; k++) {
            final int fk = k;
            long total = prods.stream().filter(p -> p.k == fk).count();
            List<Prod> lost = new ArrayList<>();
            for (Prod p : prods) if (p.k == k && !p.matched) lost.add(p);
            long byTrunc = 0;
            for (Prod p : lost) {
                boolean tr = false;
                for (Object[] t : truncated) {
                    SensorRecord r = (SensorRecord) t[3];
                    long rt = r.epochMillis();
                    if ((int) t[0] == k && r.getSensorId().equals(p.sensor) && r.getTemperature() == p.temp
                            && rt >= p.t - 1000 && rt <= p.t + 15000) { tr = true; break; }
                }
                if (tr) byTrunc++;
            }
            say("  p" + k + ": enviadas=" + total + ", perdidas=" + lost.size() + " (por truncagem=" + byTrunc
                    + ", sem líder/durante a eleição=" + (lost.size() - byTrunc) + ")");
            for (int i = 0; i < Math.min(10, lost.size()); i++) {
                Prod p = lost.get(i);
                say("    " + rel(p.t) + " " + p.sensor + " " + p.temp);
            }
            if (lost.size() > 10) say("    ... (+" + (lost.size() - 10) + ")");
        }

        // ===================== 7. truncagens =====================
        say("");
        say("== 7. Registos truncados ==");
        Map<Integer, Integer> finalLeader = new HashMap<>();             // p -> nó do LIDER de maior termo
        Map<Integer, Long> finalLeaderTerm = new HashMap<>();
        for (Ev e : events) {
            if (!e.type.equals("LIDER")) continue;
            if (e.term("termo") >= finalLeaderTerm.getOrDefault(e.k, -1L)) {
                finalLeaderTerm.put(e.k, e.term("termo"));
                finalLeader.put(e.k, e.node);
            }
        }
        long truncEvents = 0;
        for (Ev e : events) {
            if (!e.type.equals("TRUNCADO")) continue;
            truncEvents++;
            say("  p" + e.k + "-n" + e.node + " " + rel(e.t) + ": de=" + e.kv.get("de") + " descartados="
                    + e.kv.get("descartados") + " termos=" + e.kv.get("termos"));
        }
        for (int k = 0; k < n; k++) {
            long cnt = 0, inLeader = 0;
            Integer fl = finalLeader.get(k);
            List<SensorRecord> leaderLog = fl == null ? List.of() : logs.getOrDefault(k + "#" + fl, List.of());
            for (Object[] t : truncated) {
                if ((int) t[0] != k) continue;
                cnt++;
                SensorRecord r = (SensorRecord) t[3];
                long rt = r.epochMillis();
                for (SensorRecord x : leaderLog) {
                    if (x.getSensorId().equals(r.getSensorId()) && x.getTemperature() == r.getTemperature()
                            && Math.abs(x.epochMillis() - rt) <= 2000 && (x.getSeq() != r.getSeq() || x.getTerm() != r.getTerm())) {
                        inLeader++;
                        break;
                    }
                }
            }
            if (cnt > 0 || fl != null) {
                say("  p" + k + ": descartados=" + cnt + ", dos quais existem com outro seq no registo do líder final"
                        + (fl == null ? "" : " (n" + fl + ", termo " + finalLeaderTerm.get(k) + ")") + "=" + inLeader);
            }
        }
        say("  eventos TRUNCADO: " + truncEvents);

        // ===================== 8. coincidência final =====================
        say("");
        say("== 8. Coincidência final dos registos ==");
        for (int k = 0; k < n; k++) {
            List<Integer> nodes = new ArrayList<>();
            for (String key : logs.keySet()) if (key.startsWith(k + "#")) nodes.add(Integer.parseInt(key.split("#")[1]));
            if (nodes.isEmpty()) continue;
            long min = Long.MAX_VALUE;
            StringBuilder sizes = new StringBuilder();
            for (int nd : nodes) {
                List<SensorRecord> l = logs.get(k + "#" + nd);
                min = Math.min(min, l.size());
                sizes.append(" n").append(nd).append("=").append(l.size())
                     .append("(t").append(l.isEmpty() ? 0 : l.get(l.size() - 1).getTerm()).append(")");
            }
            List<Long> diff = new ArrayList<>();
            for (long s = 1; s <= min; s++) {
                String ref = null;
                boolean d = false;
                for (int nd : nodes) {
                    String line = logs.get(k + "#" + nd).get((int) (s - 1)).toLine();
                    if (ref == null) ref = line; else if (!ref.equals(line)) d = true;
                }
                if (d) diff.add(s);
            }
            divergentSeqs += diff.size();
            say("  p" + k + ": ultimoSeq" + sizes + "; comparados 1.." + min + "; seq divergentes=" + diff.size()
                    + (diff.isEmpty() ? "" : " " + diff.subList(0, Math.min(10, diff.size()))));
        }

        // ===================== 9. detetores =====================
        say("");
        say("== 9. O que observaram os detetores (monitor-<id>.txt) ==");
        for (Path p : paths) {
            String name = p.getFileName().toString();
            if (!name.startsWith("monitor-") || !name.endsWith(".txt")) continue;
            Map<String, Integer> counts = new TreeMap<>();
            List<String> timeline = new ArrayList<>();
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String[] f = line.trim().split(";");
                if (f.length < 3 || f[1].equals("RECEBIDO")) continue;
                counts.merge(f[2] + " " + f[1], 1, Integer::sum);
                if (timeline.size() < 25) timeline.add(rel(Long.parseLong(f[0])) + " " + String.join(";", Arrays.copyOfRange(f, 1, f.length)));
            }
            say("  " + name + ": " + counts);
            for (String s : timeline) say("    " + s);
        }

        // ===================== resultado =====================
        say("");
        say("== Resultado ==");
        boolean ok = multiLeaderTerms == 0 && termDecreases == 0 && divergentSeqs == 0 && formatErrors == 0;
        say("  termos com >1 líder=" + multiLeaderTerms + ", diminuições de termo=" + termDecreases
                + ", seq divergentes=" + divergentSeqs + ", ERRO=" + erros + ", erros de formato=" + formatErrors);
        say(ok ? "  OK: eleição segura e registos coincidentes" : "  PROBLEMAS DETETADOS (ver acima)");
        Files.writeString(dir.resolve("relatorio-raft.txt"), report.toString(), StandardCharsets.UTF_8);
        if (formatErrors > 0) System.exit(2);
        if (!ok) System.exit(1);
    }
}
