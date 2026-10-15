package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

public class MulticastReplica {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;
    static final int IDLE_MS = 2000;                                // sem tráfego durante este tempo = fim de rajada

    // registos fora de ordem, à espera dos que faltam
    static final TreeMap<Long, SensorRecord> retained = new TreeMap<>();
    static long last;
    // estatísticas da rajada corrente
    static long firstRx, lastRx, received, written, duplicates;

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        RecordFile file = new RecordFile("replica-" + id + ".txt");
        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            InetAddress group = InetAddress.getByName(GROUP);
            socket.joinGroup(group);                                // passa a receber o que é enviado ao grupo
            socket.setSoTimeout(IDLE_MS);
            last = file.lastSeq();                                  // último seq já aplicado
            System.out.println("Réplica " + id + " à escuta em " + GROUP + ":" + PORT + " (último seq=" + last + ")");
            byte[] buffer = new byte[1000];
            while (true) {
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(p);                              // bloqueia até chegar um datagrama
                } catch (SocketTimeoutException e) {
                    printStats();
                    continue;
                }
                long now = System.currentTimeMillis();
                if (received == 0) firstRx = now;
                lastRx = now;
                received++;
                String line = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                handle(line, file);
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }

    static void handle(String line, RecordFile file) throws IOException {
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

    static void printStats() {
        if (received == 0) return;
        System.out.printf("RAJADA: primeira receção=%d ms, última receção=%d ms, duração=%d ms, recebidos=%d, escritos=%d, duplicados=%d, retidos=%d%n",
                firstRx, lastRx, lastRx - firstRx, received, written, duplicates, retained.size());
        received = written = duplicates = 0;
    }
}
