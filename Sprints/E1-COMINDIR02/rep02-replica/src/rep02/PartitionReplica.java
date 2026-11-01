package rep02;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

/**
 * Réplica de várias partições: uma thread de subscrição e um estado independente por partição.
 * A recuperação de uma partição corre na thread dessa partição, por isso não bloqueia as outras.
 * Os estados são disjuntos (cada thread só toca no seu), pelo que a sincronização é por estado.
 */
public class PartitionReplica {
    static final int IDLE_MS = 2000;                                // sem tráfego durante este tempo = fim de rajada
    static final double PERDA = Double.parseDouble(System.getProperty("perda", "0"));
    static final long DESCARTAR = Long.getLong("descartar", -1L);
    static final Random RND = new Random();

    /** Estado de uma partição nesta réplica. */
    static class Part {
        final int k;
        final RecordFile file;
        long last;                                                  // último seq escrito
        final TreeMap<Long, SensorRecord> retained = new TreeMap<>();
        String leaderHost;                                          // anunciados em cada REG
        int leaderPort = -1;
        boolean discarded;                                          // -Ddescartar já aplicado?
        // estatísticas da rajada corrente
        long firstRx, lastRx, lastWrite, received, written, duplicates, connections, viaTcp;

        Part(int k, RecordFile file) throws IOException {
            this.k = k;
            this.file = file;
            this.last = file.lastSeq();
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: PartitionReplica <id> <partições> [portoApi]   (ex.: PartitionReplica 1 0,1)");
            return;
        }
        String id = args[0];
        String portoApi = args.length > 2 ? args[2] : "5001";
        String api = "http://127.0.0.1:" + portoApi + "/api/v0";
        List<Part> parts = new ArrayList<>();
        try {
            for (String s : args[1].split(",")) {
                int k = Integer.parseInt(s.trim());
                parts.add(new Part(k, new RecordFile("replica-" + id + "-p" + k + ".txt")));
            }
        } catch (NumberFormatException e) {
            System.out.println("Lista de partições inválida: " + args[1]);
            return;
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
            return;
        }
        IpfsPubSub ps = new IpfsPubSub(api);
        for (Part p : parts) {
            System.out.println("Réplica " + id + " subscreve " + Wire.topicData(p.k) + " no nó :" + portoApi
                    + " (último seq=" + p.last + ")");
            Thread t = new Thread(() -> {
                try {
                    ps.subscribe(Wire.topicData(p.k), msg -> {      // bloqueia aqui; chamado por cada mensagem
                        try {
                            onMessage(p, msg);
                        } catch (IOException e) {
                            System.out.println("IO (p" + p.k + "): " + e.getMessage());
                        }
                    });
                    System.out.println("Subscrição p" + p.k + " terminada.");
                } catch (IOException e) {
                    System.out.println("IO (p" + p.k + "): " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "sub-p" + p.k);
            t.start();
        }
        startIdleWatcher(parts);
    }

    static void onMessage(Part p, String msg) throws IOException {
        synchronized (p) {
            long now = System.currentTimeMillis();
            if (p.received == 0) p.firstRx = now;
            p.lastRx = now;
            p.received++;
            String[] parts;
            SensorRecord r;
            try {
                parts = Wire.regParts(msg);
                r = SensorRecord.fromLine(parts[2]);
                p.leaderPort = Integer.parseInt(parts[1]);
                p.leaderHost = parts[0];                            // aprende (e atualiza) o líder com cada REG
            } catch (IllegalArgumentException e) {                  // inclui NumberFormatException
                System.out.println("REJEITADO: " + msg);
                return;
            }
            // simulação de perda, só para mensagens do PubSub
            if (PERDA > 0 && RND.nextDouble() < PERDA) {
                System.out.println("DESCARTADO (simulação): " + r.getSeq());
                return;
            }
            if (!p.discarded && r.getSeq() == DESCARTAR) {
                p.discarded = true;
                System.out.println("DESCARTADO (simulação): " + r.getSeq());
                return;
            }
            apply(p, r, false);
            if (p.retained.size() > Wire.LIMITE_RETIDOS) {
                recover(p);
            }
        }
    }

    // regra de aplicação; devolve true se escreveu algo
    static void apply(Part p, SensorRecord r, boolean quiet) throws IOException {
        long seq = r.getSeq();
        if (seq <= p.last || p.retained.containsKey(seq)) {
            p.duplicates++;
            if (!quiet) System.out.println("DUPLICADO: " + seq);
        } else if (seq == p.last + 1) {
            write(p, r, "Aplicado");
            flush(p);
        } else {
            p.retained.put(seq, r);
            if (!quiet) System.out.println("EM ESPERA: " + seq + " (falta o registo " + (p.last + 1) + ")");
        }
    }

    static void write(Part p, SensorRecord r, String label) throws IOException {
        p.file.append(r);
        p.last = r.getSeq();
        p.written++;
        p.lastWrite = System.currentTimeMillis();
        System.out.println(label + " (p" + p.k + "): " + r.toLine());
    }

    // escreve os retidos que passaram a estar em sequência
    static void flush(Part p) throws IOException {
        while (!p.retained.isEmpty() && p.retained.firstKey() == p.last + 1) {
            write(p, p.retained.pollFirstEntry().getValue(), "Aplicado (retido)");
        }
    }

    // chamado com o monitor de p: recupera o intervalo último+1 .. menor retido-1 por TCP
    static void recover(Part p) throws IOException {
        long de = p.last + 1;
        long ate = p.retained.firstKey() - 1;
        String host = p.leaderHost;
        int port = p.leaderPort;
        System.out.println("RECUPERAÇÃO: " + de + ".." + ate + " pedida a " + host + ":" + port);
        p.connections++;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), Wire.T_TCP_MS);   // bloqueia até a ligação ser aceite
            s.setSoTimeout(Wire.T_TCP_MS);                                  // readLine deixa de bloquear indefinidamente
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println("PEDIDO " + de + " " + ate);
            while (true) {
                String line = in.readLine();                        // bloqueia; a thread de subscrição fica parada aqui
                if (line == null) {
                    throw new IOException("ligação fechada antes de FIM");
                }
                if (line.equals("FIM")) {
                    break;
                }
                if (line.startsWith("ERRO")) {
                    throw new IOException(line);
                }
                SensorRecord r = SensorRecord.fromLine(line);
                System.out.println("RECUPERADO: " + r.getSeq());
                p.viaTcp++;
                apply(p, r, true);
            }
            flush(p);
        } catch (IOException | RuntimeException e) {                // registos já escritos mantêm-se; tenta-se na próxima msg
            System.out.println("RECUPERAÇÃO FALHOU: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // imprime as estatísticas quando a rajada termina (nada recebido há IDLE_MS)
    static void startIdleWatcher(List<Part> parts) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                for (Part p : parts) {
                    synchronized (p) {
                        if (p.received > 0 && System.currentTimeMillis() - p.lastRx >= IDLE_MS) {
                            System.out.printf("RAJADA p%d: primeira receção=%d ms, última receção=%d ms, última escrita=%d ms, recebidos=%d, escritos=%d, duplicados=%d, ligações=%d, via TCP=%d, último seq=%d, retidos=%d%n",
                                    p.k, p.firstRx, p.lastRx, p.lastWrite, p.received, p.written, p.duplicates,
                                    p.connections, p.viaTcp, p.last, p.retained.size());
                            p.received = p.written = p.duplicates = p.connections = p.viaTcp = 0;
                        }
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
