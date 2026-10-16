package rep01;

import java.io.*;
import java.util.TreeMap;

public class PubSubReplica {
    static final String TOPIC = "rep01-temperaturas";
    static final int IDLE_MS = 2000;                                // sem tráfego durante este tempo = fim de rajada

    // registos fora de ordem, à espera dos que faltam
    static final TreeMap<Long, SensorRecord> retained = new TreeMap<>();
    static long last;
    // estatísticas da rajada corrente
    static long firstRx, lastRx, received, written, duplicates;

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        String port = args.length > 1 ? args[1] : "5001";
        RecordFile file = new RecordFile("replica-" + id + ".txt");
        IpfsPubSub pubsub = new IpfsPubSub("http://127.0.0.1:" + port + "/api/v0");
        try {
            last = file.lastSeq();
            System.out.println("Réplica " + id + " subscreve " + TOPIC + " no nó :" + port + " (último seq=" + last + ")");
            startIdleWatcher();
            pubsub.subscribe(TOPIC, line -> {                       // bloqueia aqui; chamado por cada mensagem
                try {
                    onMessage(line, file);
                } catch (IOException e) {
                    System.out.println("IO: " + e.getMessage());
                }
            });
            System.out.println("Subscrição terminada.");
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static synchronized void onMessage(String line, RecordFile file) throws IOException {
        long now = System.currentTimeMillis();
        if (received == 0) firstRx = now;
        lastRx = now;
        received++;
        SensorRecord r;
        try {
            r = SensorRecord.fromLine(line);                        // aqui a réplica confia no conteúdo recebido
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO: " + line);
            return;
        }
        long seq = r.getSeq();
        if (seq <= last || retained.containsKey(seq)) {
            duplicates++;
            System.out.println("DUPLICADO: " + seq);
        } else if (seq == last + 1) {
            file.append(r);
            last = seq;
            written++;
            System.out.println("Aplicado: " + r.toLine());
            while (!retained.isEmpty() && retained.firstKey() == last + 1) {
                SensorRecord next = retained.pollFirstEntry().getValue();
                file.append(next);
                last = next.getSeq();
                written++;
                System.out.println("Aplicado (retido): " + next.toLine());
            }
        } else {
            retained.put(seq, r);
            System.out.println("EM ESPERA: " + seq + " (falta o registo " + (last + 1) + ")");
        }
    }

    // imprime as estatísticas quando a rajada termina (nada recebido há IDLE_MS)
    static void startIdleWatcher() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (PubSubReplica.class) {
                    if (received > 0 && System.currentTimeMillis() - lastRx >= IDLE_MS) {
                        System.out.printf("RAJADA: primeira receção=%d ms, última receção=%d ms, duração=%d ms, recebidos=%d, escritos=%d, duplicados=%d, retidos=%d%n",
                                firstRx, lastRx, lastRx - firstRx, received, written, duplicates, retained.size());
                        received = written = duplicates = 0;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
