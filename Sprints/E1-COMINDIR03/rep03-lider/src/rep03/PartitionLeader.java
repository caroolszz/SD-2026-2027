package rep03;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

// Líder da partição k. Executado como: PartitionLeader <k> <N> [endereço] [api]
//  - subscreve rep03-in-p<k> e, por cada IN válido, gera um EVENTO: atribui seq, L e V,
//    escreve a linha em lider-p<k>.txt e publica um REG em rep03-p<k>;
//  - escuta no porto 7000+k os pedidos de recuperação das réplicas (PEDIDO <de> <ate>).
// Os líderes são os únicos processos cujo tempo lógico se regista: um evento = uma atribuição de seq.
public class PartitionLeader {
    // protege, em conjunto, seq, L, V e o ficheiro: um evento é tudo-ou-nada
    // (é o monitor que o enunciado pede para o VectorClock, que não é sincronizado)
    private static final Object LOCK = new Object();
    private static long seq;                        // último seq atribuído
    private static long lamport;                    // L
    private static VectorClock vector;              // V
    private static long firstIn, lastIn, burstCount;   // estatísticas da rajada (só para o log RAJADA)

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: PartitionLeader <k> <N> [endereço] [api]");
            return;
        }
        int k, n;
        try {
            k = Integer.parseInt(args[0]);
            n = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.out.println("k e N têm de ser inteiros");
            return;
        }
        String address = args.length > 2 ? args[2] : "127.0.0.1";     // endereço anunciado nos REG
        String api = args.length > 3 ? args[3] : "http://127.0.0.1:5001/api/v0";
        Partitioner part = new Partitioner(n);
        if (k < 0 || k >= n) {
            System.out.println("k=" + k + " fora do intervalo [0," + (n - 1) + "]");
            return;
        }
        boolean semFim = Boolean.getBoolean("semfim");   // teste: corta a ligação TCP a meio, sem FIM
        IpfsPubSub pubsub = new IpfsPubSub(api);
        RecordFile file = new RecordFile("lider-p" + k + ".txt");
        int port = Wire.PORTO_BASE + k;

        // ---- arranque: L e V continuam a partir da última linha do ficheiro ----
        // Partir de zero quebraria a condição de relógio (L voltava atrás) e a igualdade V[k] = seq.
        synchronized (LOCK) {
            SensorRecord last = file.last();             // o último registo guarda o estado completo do líder
            if (last == null) {
                // ficheiro inexistente ou vazio: primeiro arranque
                seq = 0;
                lamport = 0;
                vector = new VectorClock(n);
            } else {
                try {
                    seq = last.getSeq();
                    lamport = last.getLamport();
                    vector = VectorClock.parse(last.getVector().toString(), n);   // falha se a dimensão != N
                } catch (IllegalArgumentException e) {
                    // ex.: ficheiro escrito com N=3 e líder arrancado com N=4
                    System.out.println("Ficheiro incompatível com N=" + n + ": " + e.getMessage());
                    return;
                }
                if (vector.get(k) != seq) {
                    System.out.println("AVISO: último registo com V[k]=" + vector.get(k) + " != seq=" + seq);
                }
            }
        }

        ServerSocket server;
        try {
            server = new ServerSocket(port);
        } catch (IOException e) {
            System.out.println("Não foi possível escutar no porto " + port + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        startIdleWatcher(k);
        final int fk = k, fn = n;
        final String faddress = address;
        // thread de entrada: recebe os IN de rep03-in-p<k> (produtor e processador)
        new Thread(() -> {
            try {
                pubsub.subscribe(Wire.topicIn(fk), msg -> onInput(msg, fk, fn, part, faddress, port, file, pubsub));
                System.out.println("Subscrição terminada.");
            } catch (IOException e) {
                System.out.println("IO (entrada): " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "entrada-p" + k).start();
        System.out.println("Líder p" + k + " pronto (N=" + n + ", seq=" + lastSeq() + ", L=" + lastLamport()
                + ", V=" + lastVector() + "), TCP " + address + ":" + port
                + ", entrada=" + Wire.topicIn(k) + ", dados=" + Wire.topicData(k));

        // thread principal: servidor TCP de recuperação (igual à COMINDIR02), um pedido de cada vez
        try {
            while (true) {
                try (Socket s = server.accept();
                     BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    s.setSoTimeout(Wire.T_TCP_MS);
                    String request = in.readLine();
                    try {
                        serve(request, s, out, file, semFim);
                    } catch (IllegalArgumentException e) {
                        out.println("ERRO pedido mal formado: " + e.getMessage());
                    }
                } catch (IOException | RuntimeException e) {
                    System.out.println("IO: " + e.getMessage());
                }
            }
        } finally {
            server.close();
        }
    }

    // uma mensagem IN = um evento: relógios, seq e append numa única secção sincronizada
    private static void onInput(String msg, int k, int n, Partitioner part, String address, int port,
                                RecordFile file, IpfsPubSub pubsub) {
        // 1) validação (fora do lock: não mexe no estado)
        Wire.In in;
        try {
            in = Wire.parseIn(msg, n);                              // recusa vetores com dimensão != N
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO: " + msg + " (" + e.getMessage() + ")");
            return;
        }
        if (part.of(in.sensor) != k) {                              // mensagem no tópico errado
            System.out.println("REJEITADO: " + in.sensor + " não pertence a p" + k);
            return;
        }
        // 2) o evento propriamente dito
        // Tudo dentro do mesmo lock: se L/V fossem atualizados fora, dois IN podiam intercalar-se e
        // gerar um registo com V[k] != seq, L que não cresce com o seq, ou linhas fora de ordem.
        // Os novos valores são calculados em variáveis locais e só são confirmados depois do append:
        // se algo falhar, o estado do líder fica como estava (nenhum seq "perdido").
        SensorRecord r;
        synchronized (LOCK) {
            long newL = Math.max(lamport, in.lamport) + 1;          // regra de Lamport
            VectorClock newV = vector.copy();
            newV.merge(in.vector);                                  // regra vetorial: máximo...
            newV.tick(k);                                           // ...e incremento da componente k
            long newSeq = seq + 1;
            // invariante: só este líder incrementa V[k], e fá-lo uma vez por seq
            // (falha se o estado tiver sido mal inicializado, ex.: V a zero com seq já avançado)
            if (newV.get(k) != newSeq) {
                System.out.println("ERRO: V[k]=" + newV.get(k) + " != seq=" + newSeq + " (mensagem descartada)");
                return;
            }
            r = SensorRecord.now(newSeq, in.sensor, in.temperature, newL, newV.copy());   // cópia: V continua a mudar
            try {
                file.append(r);
            } catch (IOException e) {
                System.out.println("IO: " + e.getMessage());
                return;                                             // nada foi confirmado: o estado fica como estava
            }
            // confirmação do evento
            seq = newSeq;
            lamport = newL;
            vector = newV;
            long now = System.currentTimeMillis();
            if (burstCount == 0) firstIn = now;
            lastIn = now;
            burstCount++;
            System.out.println("EVENTO: seq=" + r.getSeq() + " L=" + r.getLamport() + " V=" + r.getVector());
        }
        // 3) publicação do REG fora do lock (é lenta e não altera o estado);
        // r é imutável e tem a sua própria cópia de V, por isso é seguro usá-lo aqui
        try {
            publicarComRepeticao(pubsub, Wire.topicData(k), Wire.reg(address, port, r));
            System.out.println("Registado e publicado: " + r.toLine());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // o daemon pode recusar uma publicação sem subscritores ("validation ignored"): repete até 8 vezes
    static int publicarComRepeticao(IpfsPubSub pubsub, String topic, String message)
            throws IOException, InterruptedException {
        int attempt = 1;
        while (true) {
            try {
                pubsub.publish(topic, message);
                return attempt - 1;
            } catch (IOException e) {
                if (attempt >= 8 || !String.valueOf(e.getMessage()).contains("validation ignored")) {
                    throw e;
                }
                Thread.sleep(2);
                attempt++;
            }
        }
    }

    // thread auxiliar: 2 s sem receber IN => escreve o resumo da rajada (RAJADA ...) e reinicia o contador
    private static void startIdleWatcher(int k) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (LOCK) {
                    if (burstCount > 0 && System.currentTimeMillis() - lastIn >= 2000) {
                        long ms = Math.max(1, lastIn - firstIn);
                        System.out.printf("RAJADA p%d: primeiro registo=%d ms, último registo=%d ms, registados=%d, "
                                + "débito=%.0f reg/s, último seq=%d%n",
                                k, firstIn, lastIn, burstCount, burstCount * 1000.0 / ms, seq);
                        burstCount = 0;
                    }
                }
            }
        });
        t.setDaemon(true);                          // não impede o processo de terminar
        t.start();
    }

    // leituras do estado também sob o lock (para não ver um evento a meio)
    private static long lastSeq() {
        synchronized (LOCK) { return seq; }
    }

    private static long lastLamport() {
        synchronized (LOCK) { return lamport; }
    }

    private static String lastVector() {
        synchronized (LOCK) { return vector.toString(); }
    }

    // recuperação: PEDIDO <de> <ate> -> linhas de registo (com carimbos) e FIM
    // o protocolo é o da COMINDIR02; como cada linha é r.toLine(), L e V seguem automaticamente
    private static void serve(String request, Socket s, PrintWriter out, RecordFile file, boolean semFim)
            throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("pedido vazio");
        }
        String[] p = request.trim().split("\\s+");
        if (p.length != 3 || !p[0].equals("PEDIDO")) {
            throw new IllegalArgumentException("formato");
        }
        long from, to;
        try {
            from = Long.parseLong(p[1]);
            to = Long.parseLong(p[2]);
        } catch (NumberFormatException e) {
            out.println("ERRO pedido mal formado: " + request);
            return;
        }
        if (from > to || from < 1) {
            out.println("ERRO intervalo inválido: " + from + ".." + to);
            return;
        }
        // nunca envia além do último seq confirmado
        List<SensorRecord> records = file.range(from, Math.min(to, lastSeq()));
        int sent = 0;
        for (SensorRecord r : records) {
            out.println(r.toLine());
            sent++;
            if (semFim && sent == Math.max(1, records.size() / 2)) {
                // modo de teste (-Dsemfim=true): fecha a ligação a meio, sem enviar FIM
                System.out.println("TESTE: ligação cortada após " + sent + " registos, sem FIM");
                s.setSoLinger(true, 0);
                return;
            }
        }
        out.println("FIM");
        System.out.println("Recuperação servida: " + from + ".." + to + " (" + sent + " registos) a "
                + s.getRemoteSocketAddress());
    }
}
