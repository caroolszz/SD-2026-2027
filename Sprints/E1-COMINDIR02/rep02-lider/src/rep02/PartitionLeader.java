package rep02;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Líder da partição k. Duas atividades concorrentes:
 *  - entrada: thread que subscreve rep02-in-p<k>, atribui seq, escreve e publica;
 *  - recuperação: thread principal, ServerSocket que atende pedidos PEDIDO <de> <até>.
 * Estado partilhado: o último seq atribuído (lock). A entrada escreve-o, a recuperação só o lê.
 */
public class PartitionLeader {
    private static final Object LOCK = new Object();
    private static long seq;                                        // último seq atribuído e escrito (protegido por LOCK)
    private static long firstIn, lastIn, burstCount;                // estatísticas da rajada corrente (LOCK)

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: PartitionLeader <k> <N> [endereço] [api]");
            return;
        }
        final int k, n;
        try {
            k = Integer.parseInt(args[0]);
            n = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.out.println("k e N têm de ser inteiros");
            return;
        }
        final String host = args.length > 2 ? args[2] : "127.0.0.1";
        final String api = args.length > 3 ? args[3] : "http://127.0.0.1:5001/api/v0";
        final Partitioner partitioner = new Partitioner(n);
        if (k < 0 || k >= n) {
            System.out.println("Partição inválida: " + k + " (N=" + n + ")");
            return;
        }
        final boolean semFim = Boolean.getBoolean("semfim");        // teste: fecha a ligação a meio, sem FIM

        IpfsPubSub ps = new IpfsPubSub(api);
        RecordFile file = new RecordFile("lider-p" + k + ".txt");
        int porto = Wire.PORTO_BASE + k;
        synchronized (LOCK) {
            seq = file.lastSeq();                                   // continua a numeração após reinício
        }

        // abre o porto antes de aceitar leituras: o endereço anunciado tem de estar a servir
        ServerSocket server;
        try {
            server = new ServerSocket(porto);
        } catch (IOException e) {
            System.out.println("Não foi possível abrir o porto de recuperação " + porto + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        startIdleWatcher(k);

        Thread entrada = new Thread(() -> {
            try {
                ps.subscribe(Wire.topicIn(k), msg -> onInput(msg, k, partitioner, host, porto, file, ps));
                System.out.println("Subscrição terminada.");
            } catch (IOException e) {
                System.out.println("IO (entrada): " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "entrada-p" + k);
        entrada.start();

        System.out.println("Líder da partição " + k + "/" + n + " (último seq=" + seq + ") anuncia " + host + ":" + porto
                + ", entrada em " + Wire.topicIn(k) + ", saída em " + Wire.topicData(k));

        try (ServerSocket s0 = server) {
            while (true) {
                try (Socket s = s0.accept();                        // bloqueia até uma réplica se ligar
                     BufferedReader in = new BufferedReader(
                             new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                     PrintWriter out = new PrintWriter(
                             new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
                    s.setSoTimeout(Wire.T_TCP_MS);
                    String pedido = in.readLine();                  // bloqueia até chegar o pedido
                    serve(pedido, s, out, file, semFim);
                } catch (IOException | RuntimeException e) {         // RuntimeException: linha estragada no ficheiro
                    System.out.println("Recuperação interrompida: " + e.getMessage());
                }
            }
        }
    }

    // trata uma mensagem do tópico de entrada (chamado pela thread de subscrição)
    private static void onInput(String msg, int k, Partitioner part, String host, int porto,
                                RecordFile file, IpfsPubSub ps) {
        String sensor;
        double temp;
        try {
            if (!Wire.type(msg).equals("IN")) {
                throw new IllegalArgumentException("tipo desconhecido");
            }
            String[] f = Wire.body(msg).split(";");
            if (f.length != 2 || f[0].isBlank()) {
                throw new IllegalArgumentException("campos");
            }
            sensor = f[0].trim();
            temp = Double.parseDouble(f[1]);
        } catch (IllegalArgumentException e) {                      // inclui NumberFormatException
            System.out.println("REJEITADO: " + msg);
            return;
        }
        int j = part.of(sensor);
        if (j != k) {
            System.out.println("PARTIÇÃO ERRADA: " + sensor + " pertence a p" + j);
            return;
        }
        SensorRecord r;
        try {
            synchronized (LOCK) {                                   // seq e append na mesma secção sincronizada
                r = SensorRecord.now(seq + 1, sensor, temp);
                file.append(r);                                     // 1) escreve localmente
                seq = r.getSeq();
                long now = System.currentTimeMillis();
                if (burstCount == 0) firstIn = now;
                lastIn = now;
                burstCount++;
            }
            publicarComRepeticao(ps, Wire.topicData(k), Wire.reg(host, porto, r)); // 2) propaga, já com o endereço de recuperação
            System.out.println("Registado e publicado: " + r.toLine());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // imprime as estatísticas quando a rajada termina (nada registado há 2 s)
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
                        long dur = Math.max(1, lastIn - firstIn);
                        System.out.printf("RAJADA p%d: primeiro registo=%d ms, último registo=%d ms, registados=%d, débito=%.0f reg/s, último seq=%d%n",
                                k, firstIn, lastIn, burstCount, burstCount * 1000.0 / dur, seq);
                        burstCount = 0;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // O Kubo no Windows pode atribuir o mesmo número de sequência a duas publicações muito próximas
    // e responder 500 «validation ignored» à segunda; basta repetir a publicação passado instantes.
    static int publicarComRepeticao(IpfsPubSub ps, String topic, String msg) throws IOException, InterruptedException {
        for (int tentativa = 1; ; tentativa++) {
            try {
                ps.publish(topic, msg);
                return tentativa - 1;
            } catch (IOException e) {
                if (tentativa >= 8 || !String.valueOf(e.getMessage()).contains("validation ignored")) {
                    throw e;
                }
                Thread.sleep(2);
            }
        }
    }

    private static long lastSeq() {
        synchronized (LOCK) {
            return seq;
        }
    }

    // atende um pedido de recuperação numa ligação já aberta
    private static void serve(String pedido, Socket s, PrintWriter out, RecordFile file, boolean semFim)
            throws IOException {
        long de, ate;
        try {
            if (pedido == null) {
                throw new IllegalArgumentException("pedido vazio");
            }
            String[] f = pedido.trim().split("\\s+");
            if (f.length != 3 || !f[0].equals("PEDIDO")) {
                throw new IllegalArgumentException("formato");
            }
            de = Long.parseLong(f[1]);
            ate = Long.parseLong(f[2]);
        } catch (IllegalArgumentException e) {                      // inclui NumberFormatException
            out.println("ERRO pedido mal formado: " + pedido);
            return;
        }
        if (de > ate || de < 1) {
            out.println("ERRO intervalo inválido: " + de + ".." + ate);
            return;
        }
        ate = Math.min(ate, lastSeq());                             // nunca além do último seq escrito
        List<SensorRecord> lista = file.range(de, ate);             // lido do ficheiro do líder
        int enviados = 0;
        for (SensorRecord r : lista) {
            out.println(r.toLine());
            enviados++;
            if (semFim && enviados == Math.max(1, lista.size() / 2)) {
                System.out.println("TESTE semfim: ligação fechada após " + enviados + " registos, sem FIM");
                s.setSoLinger(true, 0);                             // RST: a réplica vê a resposta interrompida
                return;
            }
        }
        out.println("FIM");
        System.out.println("RECUPERAÇÃO: " + de + ".." + ate + " para " + s.getRemoteSocketAddress()
                + " (" + enviados + " registos)");
    }
}
