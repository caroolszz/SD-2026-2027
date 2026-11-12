package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;

// Observa todas as partições e publica um alarme derivado (ALARM-<sensor>) quando a temperatura >= LIMIAR_ALARME.
// Não gera eventos: apenas transporta, nos IN, o conhecimento causal (Lp, Vp) que acumulou.
// Executado como: AlarmProcessor <N> [api]
// ALTERADO na COMINDIR05:
//  - tópicos raft04-*;
//  - só trata lotes cujo termo seja >= ao maior termo já observado nessa partição (os de um líder
//    obsoleto são descartados);
//  - cada registo é identificado pelo par (seq, termo): (7, termo 3) e (7, termo 2) são registos
//    DIFERENTES (o segundo foi substituído pelo primeiro após uma mudança de líder);
//  - HeartbeatMonitor com o novo onHeartbeat(termo, líder, hb); AVISO quando a partição do alarme
//    está sem líder conhecido (e o alarme é publicado na mesma).
public class AlarmProcessor {
    private static final Object LOCK = new Object();        // protege Lp, Vp, seen e maxTerm
    private static long lp;                                 // Lp
    private static VectorClock vp;                          // Vp
    private static final Set<String> seen = new HashSet<>();   // registos já tratados, chave "k#seq#termo"
    private static long[] maxTerm;                          // maior termo observado por partição
    private static HeartbeatMonitor[] monitors;
    private static final Path MONITOR_FILE = Paths.get("monitor-processador.txt");

    // um evento do detetor = uma linha "instante;evento;p<k>;detalhe" na consola e no ficheiro
    static synchronized void event(String e) {
        String line = System.currentTimeMillis() + ";" + e;
        if (!e.startsWith("RECEBIDO")) System.out.println("MONITOR " + line);
        try {
            Files.writeString(MONITOR_FILE, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            System.out.println("IO (monitor): " + ex.getMessage());
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: AlarmProcessor <N> [api]");
            return;
        }
        int n;
        try {
            n = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("N tem de ser inteiro: " + args[0]);
            return;
        }
        String api = args.length > 1 ? args[1] : "http://127.0.0.1:5001/api/v0";
        Partitioner part = new Partitioner(n);
        IpfsPubSub pubsub = new IpfsPubSub(api);
        lp = 0;
        vp = new VectorClock(n);
        maxTerm = new long[n];
        monitors = new HeartbeatMonitor[n];
        for (int k = 0; k < n; k++) {
            monitors[k] = new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, AlarmProcessor::event);
        }
        for (int k = 0; k < n; k++) {
            final int fk = k;
            new Thread(() -> {
                try {
                    pubsub.subscribe(Wire.topicData(fk), msg -> {
                        try {
                            onBatch(fk, n, msg, part, pubsub);
                        } catch (IOException | RuntimeException e) {
                            System.out.println("IO p" + fk + ": " + e.getMessage());
                        }
                    });
                    System.out.println("Subscrição p" + fk + " terminada.");
                } catch (IOException e) {
                    System.out.println("IO p" + fk + ": " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "processador-p" + k).start();
        }
        Thread checker = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(Wire.VERIFICACAO_MS);
                } catch (InterruptedException e) {
                    return;
                }
                for (HeartbeatMonitor m : monitors) m.check();
            }
        }, "verificacao");
        checker.setDaemon(true);
        checker.start();
        System.out.println("Processador pronto (N=" + n + ", " + api + "), limiar=" + Wire.LIMIAR_ALARME
                + ", periodo=" + Wire.PERIODO_HB_MS + " ms, TIMEOUT=" + Wire.FATOR_TIMEOUT + "x periodo");
    }

    // trata um LOTE recebido no tópico de dados da partição k
    private static void onBatch(int k, int n, String msg, Partitioner part, IpfsPubSub pubsub) throws IOException {
        Batch b;
        try {
            b = Batch.decode(msg, n);
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO p" + k + ": " + e.getMessage());
            return;
        }
        synchronized (LOCK) {
            if (b.term < maxTerm[k]) {                              // líder obsoleto
                event("IGNORADO;p" + k + ";termo=" + b.term + ";lider=n" + b.leader + ";maiorTermo=" + maxTerm[k]);
                return;
            }
            maxTerm[k] = b.term;
        }
        monitors[k].onHeartbeat(b.term, b.leader, b.hb);
        event("RECEBIDO;p" + k + ";termo=" + b.term + ";lider=n" + b.leader + ";hb=" + b.hb + ";ultimoSeq="
                + b.lastSeq + ";ultimoTermo=" + b.lastTerm + ";n=" + b.records.size() + ";seq=" + b.seqRange());
        for (SensorRecord r : b.records) {
            onRecord(k, r, part, pubsub);
        }
    }

    // trata um registo da partição k
    private static void onRecord(int k, SensorRecord r, Partitioner part, IpfsPubSub pubsub)
            throws IOException {
        long lpSnapshot;
        VectorClock vpSnapshot;
        synchronized (LOCK) {
            // o par (seq, termo) identifica o registo: o PubSub pode entregar o mesmo LOTE duas vezes
            if (!seen.add(k + "#" + r.getSeq() + "#" + r.getTerm())) {
                return;
            }
            lp = Math.max(lp, r.getLamport());
            vp.merge(r.getVector());
            lpSnapshot = lp;
            vpSnapshot = vp.copy();
        }
        System.out.println("OBSERVADO: p" + k + "#" + r.getSeq() + "(t" + r.getTerm() + ") " + r.getSensorId() + " "
                + r.getTemperature() + " -> Lp=" + lpSnapshot + " Vp=" + vpSnapshot);
        if (r.getSensorId().startsWith("ALARM-")) {
            return;                                                 // sem alarmes sobre alarmes
        }
        if (r.getTemperature() >= Wire.LIMIAR_ALARME) {
            String alarm = "ALARM-" + r.getSensorId();
            int j = part.of(alarm);
            if (monitors[j].state() == HeartbeatMonitor.State.SUSPEITO) {
                System.out.println("AVISO: partição p" + j + " sem líder conhecido; alarme " + r.getSensorId()
                        + " pode perder-se");
            }
            try {
                publicar(pubsub, Wire.topicIn(j), Wire.in(alarm, r.getTemperature(), lpSnapshot, vpSnapshot));
                System.out.println("ALARME: " + r.getSensorId() + " p" + k + "#" + r.getSeq() + " → p" + j);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void publicar(IpfsPubSub pubsub, String topic, String message)
            throws IOException, InterruptedException {
        int attempt = 1;
        while (true) {
            try {
                pubsub.publish(topic, message);
                return;
            } catch (IOException e) {
                if (attempt >= 8 || !String.valueOf(e.getMessage()).contains("validation ignored")) {
                    throw e;
                }
                Thread.sleep(2);
                attempt++;
            }
        }
    }
}
