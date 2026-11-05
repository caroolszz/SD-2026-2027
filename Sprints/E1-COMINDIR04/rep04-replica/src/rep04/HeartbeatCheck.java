package rep04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// NOVO na COMINDIR04. Análise a posteriori do heartbeat, do envio em lote e do detetor de falhas.
// Executado como: HeartbeatCheck <N> <pasta> [falhas.txt]   (com o mesmo -Drep04.periodo/-Drep04.fator dos ensaios)
// Lê da pasta:
//   hb-p<k>.txt       inc;hb;instante;ultimoSeq;n;bytes     (uma linha por lote publicado, escrita pelo líder)
//   lider-p<k>.txt    seq;sensor;temperatura;instante;L;V   (instante de atribuição do seq)
//   monitor-<id>.txt  instante;evento;p<k>;detalhe          (eventos do detetor de cada participante)
//   replica-<r>-p<k>.txt (opcional: só para a verificação de completude)
// falhas.txt: linhas p<k>;instante (ms), anotadas no momento de cada kill -9.
// Escreve relatorio-heartbeat.txt na pasta (o mesmo texto que mostra no ecrã).
// ATENÇÃO: compara instantes de processos diferentes (líder vs participantes); só é válido porque todos
// correm na mesma máquina e usam o mesmo relógio físico. O detetor, esse, só usa o relógio local.
public class HeartbeatCheck {
    private static final Pattern HB_FILE = Pattern.compile("hb-p(\\d+)\\.txt");
    private static final Pattern LEADER_FILE = Pattern.compile("lider-p(\\d+)\\.txt");
    private static final Pattern MONITOR_FILE = Pattern.compile("monitor-(.+)\\.txt");
    private static final Pattern REPLICA_FILE = Pattern.compile("replica-(.+)-p(\\d+)\\.txt");
    private static final Pattern KV = Pattern.compile("(\\w+)=([^;]*)");

    private static final StringBuilder report = new StringBuilder();

    // uma linha de hb-p<k>.txt
    static final class Hb {
        final long inc, hb, instant, lastSeq;
        final int n, bytes;
        Hb(String line) {
            String[] f = line.trim().split(";");
            if (f.length != 6) throw new IllegalArgumentException("linha hb mal formada: " + line);
            inc = Long.parseLong(f[0]);
            hb = Long.parseLong(f[1]);
            instant = Long.parseLong(f[2]);
            lastSeq = Long.parseLong(f[3]);
            n = Integer.parseInt(f[4]);
            bytes = Integer.parseInt(f[5]);
        }
    }

    // uma linha de monitor-<id>.txt
    static final class Ev {
        final String who;                    // replica1, processador, produtor
        final long instant;
        final String type;                   // RECEBIDO, SUSPEITA, RETOMA, REINICIO, HB_PERDIDO, RECUPERACAO
        final int k;
        final Map<String, String> kv = new HashMap<>();
        Ev(String who, String line) {
            this.who = who;
            String[] f = line.trim().split(";", 4);
            if (f.length < 3 || !f[2].startsWith("p")) throw new IllegalArgumentException("evento mal formado: " + line);
            instant = Long.parseLong(f[0]);
            type = f[1];
            k = Integer.parseInt(f[2].substring(1));
            if (f.length == 4) {
                Matcher m = KV.matcher(f[3]);
                while (m.find()) kv.put(m.group(1), m.group(2));
            }
        }
        long num(String key, long def) {
            String v = kv.get(key);
            if (v == null) return def;
            int arrow = v.indexOf("->");                // REINICIO: inc=<antiga>-><nova>
            return Long.parseLong(arrow >= 0 ? v.substring(arrow + 2) : v);
        }
    }

    // acumulador de média/máximo/mínimo
    static final class Stat {
        long count; double sum; double max = Double.NEGATIVE_INFINITY; double min = Double.POSITIVE_INFINITY;
        void add(double v) { count++; sum += v; max = Math.max(max, v); min = Math.min(min, v); }
        double avg() { return count == 0 ? 0 : sum / count; }
        String fmt(String unit) {
            return count == 0 ? "sem dados" : String.format("média=%.1f%s máx=%.1f%s (n=%d)", avg(), unit, max, unit, count);
        }
    }

    private static void say(String s) {
        System.out.println(s);
        report.append(s).append(System.lineSeparator());
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            System.out.println("Uso: HeartbeatCheck <N> <pasta> [falhas.txt]");
            return;
        }
        int n = Integer.parseInt(args[0]);
        Path dir = Paths.get(args[1]);
        long delta = Wire.PERIODO_HB_MS;
        long timeout = delta * Wire.FATOR_TIMEOUT;
        say("HeartbeatCheck N=" + n + " pasta=" + dir.toAbsolutePath());
        say("Parâmetros assumidos: periodo=" + delta + " ms, TIMEOUT=" + Wire.FATOR_TIMEOUT + "x periodo=" + timeout
                + " ms, verificação=" + Wire.VERIFICACAO_MS + " ms  (usar os mesmos -Drep04.* dos ensaios)");
        say("Intervalo previsto para o tempo de deteção: ]" + (timeout - delta) / 1000.0 + " s, "
                + (timeout + Wire.VERIFICACAO_MS) / 1000.0 + " s]");

        // ---------------- leitura ----------------
        List<List<Hb>> hbs = new ArrayList<>();
        List<Map<Long, SensorRecord>> leader = new ArrayList<>();      // por partição: seq -> registo
        for (int k = 0; k < n; k++) {
            hbs.add(new ArrayList<>());
            leader.add(new TreeMap<>());
        }
        Map<String, List<Ev>> events = new TreeMap<>();                 // por participante, pela ordem do ficheiro
        Map<String, Map<Integer, TreeSet<Long>>> replicas = new TreeMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                Matcher m;
                if ((m = HB_FILE.matcher(name)).matches()) {
                    int k = Integer.parseInt(m.group(1));
                    if (k >= n) continue;
                    for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                        if (!l.isBlank()) hbs.get(k).add(new Hb(l));
                    }
                } else if ((m = LEADER_FILE.matcher(name)).matches()) {
                    int k = Integer.parseInt(m.group(1));
                    if (k >= n) continue;
                    for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                        if (!l.isBlank()) {
                            SensorRecord r = SensorRecord.fromLine(l, n);
                            leader.get(k).put(r.getSeq(), r);
                        }
                    }
                } else if ((m = MONITOR_FILE.matcher(name)).matches()) {
                    List<Ev> list = new ArrayList<>();
                    for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                        if (l.isBlank()) continue;
                        try {
                            Ev e = new Ev(m.group(1), l);
                            if (e.k < n) list.add(e);
                        } catch (IllegalArgumentException e) {
                            say("AVISO: " + name + ": " + e.getMessage());
                        }
                    }
                    events.put(m.group(1), list);
                } else if ((m = REPLICA_FILE.matcher(name)).matches()) {
                    TreeSet<Long> seqs = new TreeSet<>();
                    for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                        if (!l.isBlank()) seqs.add(Long.parseLong(l.split(";")[0].trim()));
                    }
                    replicas.computeIfAbsent(m.group(1), x -> new TreeMap<>()).put(Integer.parseInt(m.group(2)), seqs);
                }
            }
        }
        List<long[]> failures = new ArrayList<>();                      // {k, instante}
        if (args.length == 3) {
            for (String l : Files.readAllLines(Paths.get(args[2]), StandardCharsets.UTF_8)) {
                if (l.isBlank()) continue;
                String[] f = l.trim().split(";");
                failures.add(new long[] { Integer.parseInt(f[0].trim().substring(1)), Long.parseLong(f[1].trim()) });
            }
        }

        section1(n, hbs);
        section2(n, hbs, events, failures);
        if (!failures.isEmpty()) section3(failures, events, hbs, timeout, delta);
        section4(n, leader, events);
        section5(events);
        section6(n, hbs, leader);
        section7(n, leader, replicas);

        Files.writeString(dir.resolve("relatorio-heartbeat.txt"), report.toString(), StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("Relatório escrito em " + dir.resolve("relatorio-heartbeat.txt"));
    }

    // 1) regularidade dos heartbeats e tamanho dos lotes, por partição e incarnação
    private static void section1(int n, List<List<Hb>> hbs) {
        say("");
        say("== 1. Lotes por partição e incarnação ==");
        for (int k = 0; k < n; k++) {
            List<Hb> list = hbs.get(k);
            if (list.isEmpty()) {
                say("  p" + k + ": sem hb-p" + k + ".txt");
                continue;
            }
            long total = list.size();
            long span = list.get(list.size() - 1).instant - list.get(0).instant;
            say(String.format("  p%d: %d lotes no total, %.1f lotes/min", k, total,
                    span > 0 ? (total - 1) * 60000.0 / span : 0));
            Map<Long, List<Hb>> byInc = new LinkedHashMap<>();
            for (Hb h : list) byInc.computeIfAbsent(h.inc, x -> new ArrayList<>()).add(h);
            for (Map.Entry<Long, List<Hb>> e : byInc.entrySet()) {
                List<Hb> l = e.getValue();
                long empty = 0, gaps = 0, incoherent = 0;
                Stat perBatch = new Stat(), interval = new Stat();
                for (int i = 0; i < l.size(); i++) {
                    Hb h = l.get(i);
                    if (h.n == 0) empty++;
                    perBatch.add(h.n);
                    if (i > 0) {
                        Hb prev = l.get(i - 1);
                        interval.add(h.instant - prev.instant);
                        if (h.hb != prev.hb + 1) gaps += h.hb - prev.hb - 1;   // lotes não publicados
                        // coerência do ultimoSeq: entre dois lotes consecutivos, o ultimoSeq cresce exatamente n
                        // (falha se ultimoSeq for lido fora da secção sincronizada, ou enviado a 0)
                        else if (h.lastSeq - prev.lastSeq != h.n) incoherent++;
                    }
                }
                say(String.format("    inc=%d: %d lotes (hb %d..%d), vazios=%d (%.1f%%), registos/lote média=%.2f máx=%.0f, "
                                + "intervalo entre heartbeats %s, hb em falta=%d, ultimoSeq incoerente=%d",
                        e.getKey(), l.size(), l.get(0).hb, l.get(l.size() - 1).hb, empty, 100.0 * empty / l.size(),
                        perBatch.avg(), perBatch.max, interval.fmt(" ms"), gaps, incoherent));
            }
        }
    }

    // 2) suspeitas, classificadas em falha real / falsa suspeita / inconclusiva
    private static void section2(int n, List<List<Hb>> hbs, Map<String, List<Ev>> events, List<long[]> failures) {
        say("");
        say("== 2. Suspeitas por participante e partição ==");
        long reais = 0, falsas = 0, inconclusivas = 0, iniciais = 0;
        for (Map.Entry<String, List<Ev>> e : events.entrySet()) {
            for (int k = 0; k < n; k++) {
                List<String> lines = new ArrayList<>();
                long inc = -1;                                  // incarnação observada antes da suspeita
                List<Ev> evs = e.getValue();
                for (int i = 0; i < evs.size(); i++) {
                    Ev ev = evs.get(i);
                    if (ev.k != k) continue;
                    if (ev.type.equals("RECEBIDO")) inc = ev.num("inc", inc);
                    if (!ev.type.equals("SUSPEITA")) continue;
                    Hb lastHb = lastBefore(hbs.get(k), ev.instant);
                    String cls = classify(ev, inc, hbs.get(k), lastHb, failures, evs, i);
                    if (cls.startsWith("FALHA")) reais++;
                    else if (cls.startsWith("FALSA")) falsas++;
                    else if (cls.startsWith("INICIAL")) iniciais++;
                    else inconclusivas++;
                    String silence = lastHb == null ? "?" : String.valueOf(ev.instant - lastHb.instant);
                    lines.add("    " + ev.instant + " " + cls + (inc == -1 ? " (nunca tinha recebido um lote)" : " inc=" + inc)
                            + ", silêncio desde o último heartbeat publicado=" + silence
                            + " ms (local: " + ev.kv.getOrDefault("silencio_ms", "?") + " ms)");
                }
                if (!lines.isEmpty()) {
                    say("  " + e.getKey() + " p" + k + ": " + lines.size() + " suspeitas");
                    for (String l : lines) say(l);
                }
            }
        }
        say("  Total: falhas reais=" + reais + ", falsas suspeitas=" + falsas + ", inconclusivas=" + inconclusivas
                + ", iniciais (líder ainda não arrancado)=" + iniciais);
    }

    // Como inc é o instante de arranque do líder, "inc < instante da suspeita" quer dizer que essa
    // incarnação já existia quando a suspeita surgiu. Assim:
    //  - FALSA SUSPEITA: depois da suspeita, o líder publicou um heartbeat de uma incarnação que já
    //    existia antes dela (em particular, a MESMA incarnação): estava vivo, só lento/suspenso/calado;
    //  - FALHA REAL: não houve novo heartbeat da mesma incarnação, e ou apareceu uma incarnação nova
    //    (reinício) ou há uma falha anotada em falhas.txt entre o último heartbeat e a suspeita;
    //  - INCONCLUSIVA: não há nenhum heartbeat depois da suspeita nem falha anotada (ex.: fim do ensaio);
    //  - INICIAL: o participante nunca recebeu um lote e o líder só arrancou depois da suspeita
    //    (arranque dos participantes mais de TIMEOUT antes do líder; não é falha nem falsa suspeita).
    private static String classify(Ev s, long inc, List<Hb> hbs, Hb lastHb, List<long[]> failures, List<Ev> evs, int idx) {
        boolean aliveAfter = false, newIncAfter = false;
        for (Hb h : hbs) {
            if (h.instant <= s.instant) continue;
            if (h.inc == inc || h.inc < s.instant) aliveAfter = true;
            else newIncAfter = true;
        }
        boolean annotated = false;
        long from = lastHb == null ? Long.MIN_VALUE : lastHb.instant;
        for (long[] f : failures) {
            if (f[0] == s.k && f[1] >= from && f[1] <= s.instant) annotated = true;
        }
        String retoma = "sem RETOMA";
        for (int j = idx + 1; j < evs.size(); j++) {
            Ev r = evs.get(j);
            if (r.k == s.k && r.type.equals("RETOMA")) {
                long ri = r.num("inc", -1);
                retoma = "RETOMA inc=" + ri + (ri == inc ? " (mesma)" : ri < s.instant ? " (já existia)" : " (nova)");
                break;
            }
        }
        String cls = aliveAfter ? "FALSA SUSPEITA"
                : (inc == -1 && newIncAfter && !annotated) ? "INICIAL (o líder ainda não tinha arrancado)"
                : (newIncAfter || annotated) ? "FALHA REAL"
                : "INCONCLUSIVA (sem heartbeats posteriores nem falha anotada: fim do ensaio?)";
        return cls + " [" + retoma + "]";
    }

    private static Hb lastBefore(List<Hb> hbs, long t) {
        Hb best = null;
        for (Hb h : hbs) if (h.instant <= t && (best == null || h.instant > best.instant)) best = h;
        return best;
    }

    // 3) tempo de deteção de cada falha provocada, por participante
    private static void section3(List<long[]> failures, Map<String, List<Ev>> events, List<List<Hb>> hbs,
                                 long timeout, long delta) {
        say("");
        say("== 3. Tempo de deteção das falhas anotadas em falhas.txt ==");
        Stat all = new Stat();
        for (int f = 0; f < failures.size(); f++) {
            int k = (int) failures.get(f)[0];
            long tf = failures.get(f)[1];
            long next = Long.MAX_VALUE;                         // próxima falha da mesma partição
            for (int g = f + 1; g < failures.size(); g++) {
                if (failures.get(g)[0] == k) { next = failures.get(g)[1]; break; }
            }
            Hb lastHb = k < hbs.size() ? lastBefore(hbs.get(k), tf) : null;
            say("  falha p" + k + " em " + tf + (lastHb == null ? "" : " (último heartbeat publicado "
                    + (tf - lastHb.instant) + " ms antes, hb=" + lastHb.hb + ")"));
            for (Map.Entry<String, List<Ev>> e : events.entrySet()) {
                boolean follows = false;
                Ev found = null;
                for (Ev ev : e.getValue()) {
                    if (ev.k != k) continue;
                    follows = true;
                    if (ev.type.equals("SUSPEITA") && ev.instant >= tf && ev.instant < next) { found = ev; break; }
                }
                if (!follows) continue;                         // este participante não acompanha p<k>
                if (found == null) {
                    say("    " + e.getKey() + ": sem SUSPEITA (completude não observada)");
                } else {
                    double s = (found.instant - tf) / 1000.0;
                    boolean inRange = found.instant - tf > timeout - delta && found.instant - tf <= timeout + Wire.VERIFICACAO_MS;
                    all.add(s);
                    say(String.format("    %s: deteção em %.3f s %s", e.getKey(), s, inRange ? "(dentro do previsto)" : "(FORA do intervalo previsto)"));
                }
            }
        }
        say(String.format("  Tempo de deteção: mín=%.3f s, média=%.3f s, máx=%.3f s (n=%d)",
                all.count == 0 ? 0 : all.min, all.avg(), all.count == 0 ? 0 : all.max, all.count));
    }

    // 4) latência de lote: instante do RECEBIDO que contém o registo - instante do registo no líder
    private static void section4(int n, List<Map<Long, SensorRecord>> leader, Map<String, List<Ev>> events) {
        say("");
        say("== 4. Latência de lote (RECEBIDO - instante do registo) ==");
        Stat[] perPart = new Stat[n];
        for (int k = 0; k < n; k++) perPart[k] = new Stat();
        for (Map.Entry<String, List<Ev>> e : events.entrySet()) {
            Stat[] mine = new Stat[n];
            for (int k = 0; k < n; k++) mine[k] = new Stat();
            Set<String> seenBatches = new HashSet<>();          // o PubSub pode duplicar um LOTE
            for (Ev ev : e.getValue()) {
                if (!ev.type.equals("RECEBIDO")) continue;
                String range = ev.kv.get("seq");
                if (range == null || range.equals("-")) continue;   // lote vazio, ou produtor (só cabeçalho)
                if (!seenBatches.add(ev.k + "#" + ev.kv.get("inc") + "#" + ev.kv.get("hb"))) continue;
                String[] ab = range.split("\\.\\.");
                for (long s = Long.parseLong(ab[0]); s <= Long.parseLong(ab[1]); s++) {
                    SensorRecord r = leader.get(ev.k).get(s);
                    if (r == null) continue;
                    long lat = ev.instant - r.epochMillis();
                    mine[ev.k].add(lat);
                    perPart[ev.k].add(lat);
                }
            }
            for (int k = 0; k < n; k++) {
                if (mine[k].count > 0) say("  " + e.getKey() + " p" + k + ": " + mine[k].fmt(" ms"));
            }
        }
        for (int k = 0; k < n; k++) say("  p" + k + " (todos os participantes): " + perPart[k].fmt(" ms"));
        say("  (referência: com chegadas uniformes espera-se cerca de periodo/2 = " + Wire.PERIODO_HB_MS / 2 + " ms)");
    }

    // 5) contagens de HB_PERDIDO, RECUPERACAO e REINICIO
    private static void section5(Map<String, List<Ev>> events) {
        say("");
        say("== 5. Eventos HB_PERDIDO, RECUPERACAO e REINICIO ==");
        long hbLost = 0, rec = 0, restart = 0;
        for (Map.Entry<String, List<Ev>> e : events.entrySet()) {
            Map<Integer, long[]> c = new TreeMap<>();
            for (Ev ev : e.getValue()) {
                long[] v = c.computeIfAbsent(ev.k, x -> new long[3]);
                if (ev.type.equals("HB_PERDIDO")) v[0]++;
                if (ev.type.equals("RECUPERACAO")) v[1]++;
                if (ev.type.equals("REINICIO")) v[2]++;
            }
            for (Map.Entry<Integer, long[]> x : c.entrySet()) {
                long[] v = x.getValue();
                say("  " + e.getKey() + " p" + x.getKey() + ": HB_PERDIDO=" + v[0] + " RECUPERACAO=" + v[1] + " REINICIO=" + v[2]);
                hbLost += v[0];
                rec += v[1];
                restart += v[2];
            }
        }
        say("  Total: HB_PERDIDO=" + hbLost + " RECUPERACAO=" + rec + " REINICIO=" + restart);
    }

    // 6) bytes por registo: em LOTE (com a parte do cabeçalho que lhe cabe) vs um REG da COMINDIR03
    private static void section6(int n, List<List<Hb>> hbs, List<Map<Long, SensorRecord>> leader) {
        say("");
        say("== 6. Mensagens e bytes por registo ==");
        long lotes = 0, regs = 0, bytesWithData = 0, recsWithData = 0, bytesAll = 0, regBytes = 0, regCount = 0;
        for (int k = 0; k < n; k++) {
            for (Hb h : hbs.get(k)) {
                lotes++;
                bytesAll += h.bytes;
                if (h.n > 0) {
                    bytesWithData += h.bytes;
                    recsWithData += h.n;
                }
            }
            for (SensorRecord r : leader.get(k).values()) {
                // REG;<endereço>;<porto>;<linha>: a mensagem que a COMINDIR03 publicaria por este registo
                String reg = "REG;127.0.0.1;" + (Wire.PORTO_BASE + k) + ";" + r.toLine();
                regBytes += reg.getBytes(StandardCharsets.UTF_8).length;
                regCount++;
            }
            regs += leader.get(k).size();
        }
        say("  Mensagens no tópico de dados: COMINDIR03 (um REG por registo)=" + regs + ", COMINDIR04 (lotes)=" + lotes);
        say(String.format("  Bytes por registo em LOTE (só lotes com registos)=%.1f, contando também os lotes vazios=%.1f",
                recsWithData == 0 ? 0 : (double) bytesWithData / recsWithData,
                recsWithData == 0 ? 0 : (double) bytesAll / recsWithData));
        say(String.format("  Bytes por registo num REG equivalente=%.1f", regCount == 0 ? 0 : (double) regBytes / regCount));
    }

    // 7) extra: completude das réplicas em relação ao ficheiro do líder
    private static void section7(int n, List<Map<Long, SensorRecord>> leader, Map<String, Map<Integer, TreeSet<Long>>> replicas) {
        if (replicas.isEmpty()) return;
        say("");
        say("== 7. Completude das réplicas (extra: seq do líder que faltam em cada réplica) ==");
        for (Map.Entry<String, Map<Integer, TreeSet<Long>>> e : replicas.entrySet()) {
            for (Map.Entry<Integer, TreeSet<Long>> x : e.getValue().entrySet()) {
                int k = x.getKey();
                if (k >= n) continue;
                List<Long> missing = new ArrayList<>();
                for (Long s : leader.get(k).keySet()) if (!x.getValue().contains(s)) missing.add(s);
                say("  réplica " + e.getKey() + " p" + k + ": " + x.getValue().size() + "/" + leader.get(k).size()
                        + " registos" + (missing.isEmpty() ? ", completa"
                        : ", faltam " + missing.size() + " " + missing.subList(0, Math.min(10, missing.size()))));
            }
        }
    }
}
