package rep03;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

// A réplica aplica os registos de cada partição pela regra da COMINDIR02 (ordem do seq, retenção, recuperação por TCP).
// Guarda os carimbos L e V tal como chegam, mas não os consulta nem atualiza qualquer relógio.
// Executado como: PartitionReplica <id> <partições> [portoApi]   (ex.: PartitionReplica 1 0,1)
// Porque não atualiza relógios: não gera eventos (só copia), e o carimbo faz parte do registo,
// tem de ser igual em todas as cópias. Usar V para decidir quando escrever seria entrega causal,
// que está fora do âmbito da ficha; por isso pode escrever um alarme antes da sua causa (ensaio e).
public class PartitionReplica {
    static final double PERDA = Double.parseDouble(System.getProperty("perda", "0"));      // teste: probabilidade de perda
    static final long DESCARTAR = Long.getLong("descartar", -1L);                          // teste: descarta este seq uma vez
    static final Random RND = new Random();

    // estado de uma partição guardada por esta réplica (cada uma tem a sua thread e o seu monitor)
    static class Part {
        final int k;
        final RecordFile file;                           // replica-<id>-p<k>.txt
        long last;                                       // último seq aplicado
        final TreeMap<Long, SensorRecord> retained = new TreeMap<>();   // chegaram adiantados, ordenados por seq
        String leaderHost = "127.0.0.1";                 // atualizados a partir de cada REG
        int leaderPort;
        boolean discarded;                               // teste: o seq DESCARTAR já foi descartado?
        long received, written, duplicates, connections, viaTcp, firstRx, lastRx, lastWrite;   // estatísticas

        Part(int k, RecordFile file) throws IOException {
            this.k = k;
            this.file = file;
            this.last = file.lastSeq();                  // num reinício, continua a partir do ficheiro
            this.leaderPort = Wire.PORTO_BASE + k;
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: PartitionReplica <id> <partições> [portoApi]   (ex.: PartitionReplica 1 0,1)");
            return;
        }
        String id = args[0];
        String apiPort = args.length > 2 ? args[2] : "5001";
        String api = "http://127.0.0.1:" + apiPort + "/api/v0";
        List<Part> parts = new ArrayList<>();
        try {
            for (String s : args[1].split(",")) {        // lista de partições, ex.: "0,1"
                int k = Integer.parseInt(s.trim());
                parts.add(new Part(k, new RecordFile("replica-" + id + "-p" + k + ".txt")));
            }
        } catch (NumberFormatException e) {
            System.out.println("Partições inválidas: " + args[1]);
            return;
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
            return;
        }
        IpfsPubSub pubsub = new IpfsPubSub(api);
        // uma thread por partição: as partições são independentes (não há ordem entre elas)
        for (Part part : parts) {
            System.out.println("Réplica " + id + " subscreve " + Wire.topicData(part.k) + " (último seq=" + part.last + ")");
            new Thread(() -> {
                try {
                    pubsub.subscribe(Wire.topicData(part.k), msg -> {
                        try {
                            onMessage(part, msg);
                        } catch (IOException | RuntimeException e) {
                            System.out.println("IO p" + part.k + ": " + e.getMessage());
                        }
                    });
                    System.out.println("Subscrição p" + part.k + " terminada.");
                } catch (IOException e) {
                    System.out.println("IO p" + part.k + ": " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "replica-p" + part.k).start();
        }
        startIdleWatcher(parts);
    }

    // trata um REG; o monitor é o objeto Part (partições diferentes não se bloqueiam entre si)
    static void onMessage(Part part, String msg) throws IOException {
        synchronized (part) {
            long now = System.currentTimeMillis();
            if (part.received == 0) part.firstRx = now;
            part.lastRx = now;
            part.received++;
            String[] f = Wire.regParts(msg);             // {endereço, porto, linha}
            SensorRecord r = SensorRecord.fromLine(f[2]);   // aceita o vetor como vier (não o usa)
            part.leaderPort = Integer.parseInt(f[1]);
            part.leaderHost = f[0];
            // modos de teste: simular perdas no troço líder -> réplica
            if (PERDA > 0 && RND.nextDouble() < PERDA) {
                System.out.println("PERDIDO (simulado) seq=" + r.getSeq());
                return;
            }
            if (!part.discarded && r.getSeq() == DESCARTAR) {
                part.discarded = true;
                System.out.println("DESCARTADO (simulado) seq=" + r.getSeq());
                return;
            }
            apply(part, r);
            if (part.retained.size() > Wire.LIMITE_RETIDOS) {
                recover(part);                           // lacuna que não se preenche sozinha: pede ao líder
            }
        }
    }

    // aplica pela ordem do seq; os que chegam adiantados ficam retidos até a lacuna ser preenchida
    // (só olha para o seq: L e V não entram nesta decisão)
    static void apply(Part part, SensorRecord r) throws IOException {
        if (r.getSeq() <= part.last || part.retained.containsKey(r.getSeq())) {
            part.duplicates++;
            System.out.println("Duplicado p" + part.k + " seq=" + r.getSeq());
            return;
        }
        if (r.getSeq() == part.last + 1) {
            write(part, r, "Aplicado");
            flush(part);                                 // talvez desbloqueie registos retidos
        } else {
            part.retained.put(r.getSeq(), r);
            System.out.println("Retido p" + part.k + " seq=" + r.getSeq() + " (último aplicado=" + part.last + ")");
        }
    }

    // escreve no ficheiro da réplica; o [ms] no log é o instante de escrita usado no ensaio (e)
    // (o instante dentro da linha é o do líder, não o da réplica)
    static void write(Part part, SensorRecord r, String how) throws IOException {
        part.file.append(r);
        part.last = r.getSeq();
        part.written++;
        part.lastWrite = System.currentTimeMillis();
        System.out.println("[" + part.lastWrite + "] " + how + " p" + part.k + ": " + r.toLine());
    }

    // aplica os retidos que já são consecutivos ao último aplicado
    static void flush(Part part) throws IOException {
        while (!part.retained.isEmpty() && part.retained.firstKey() == part.last + 1) {
            write(part, part.retained.pollFirstEntry().getValue(), "Aplicado (retido)");
        }
    }

    // pede ao líder, por TCP, os registos em falta entre o último aplicado e o primeiro retido
    // as linhas recebidas já trazem L e V (o líder envia r.toLine())
    static void recover(Part part) throws IOException {
        long from = part.last + 1;
        long to = part.retained.firstKey() - 1;
        System.out.println("Recuperação p" + part.k + ": PEDIDO " + from + " " + to + " a "
                + part.leaderHost + ":" + part.leaderPort);
        part.connections++;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(part.leaderHost, part.leaderPort), Wire.T_TCP_MS);
            s.setSoTimeout(Wire.T_TCP_MS);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println("PEDIDO " + from + " " + to);
            String line;
            while (true) {
                line = in.readLine();
                if (line == null) throw new IOException("ligação fechada antes de FIM");
                if (line.equals("FIM")) break;
                if (line.startsWith("ERRO")) throw new IOException(line);
                SensorRecord r = SensorRecord.fromLine(line);
                System.out.println("TCP p" + part.k + " seq=" + r.getSeq());
                part.viaTcp++;
                apply(part, r);                          // mesma regra que para os REG
            }
            flush(part);
        } catch (IOException | RuntimeException e) {
            // o que já foi aplicado fica; a lacuna volta a ser pedida na próxima vez
            System.out.println("Recuperação falhou (" + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
    }

    // thread auxiliar: 2 s sem receções numa partição => escreve o resumo da rajada (RAJADA ...)
    private static void startIdleWatcher(List<Part> parts) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                for (Part p : parts) {
                    synchronized (p) {
                        if (p.received > 0 && System.currentTimeMillis() - p.lastRx >= 2000) {
                            System.out.printf("RAJADA p%d: primeira receção=%d ms, última receção=%d ms, última escrita=%d ms, "
                                    + "recebidos=%d, escritos=%d, duplicados=%d, ligações TCP=%d, via TCP=%d, "
                                    + "último seq=%d, retidos=%d%n",
                                    p.k, p.firstRx, p.lastRx, p.lastWrite, p.received, p.written, p.duplicates,
                                    p.connections, p.viaTcp, p.last, p.retained.size());
                            p.viaTcp = 0;
                            p.connections = 0;
                            p.duplicates = 0;
                            p.written = 0;
                            p.received = 0;
                        }
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
