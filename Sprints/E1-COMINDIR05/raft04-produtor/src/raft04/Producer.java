package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Random;
import java.util.Scanner;

// Produtor de leituras: lê comandos do teclado e publica mensagens IN no tópico de entrada
// da partição de cada sensor. Executado como: Producer <N> [api]
// ALTERADO na COMINDIR05:
//  - tópicos raft04-*; o IN é publicado em raft04-in-p<k>, que os TRÊS nós da partição subscrevem
//    (o produtor não sabe nem precisa de saber quem é o líder: só o líder atribui seq);
//  - um HeartbeatMonitor por partição com o novo onHeartbeat(termo, líder, hb) (monitor-produtor.txt);
//  - uma linha em produtor.txt por cada IN enviado (instante;p<k>;sensor;temperatura), que o
//    RaftCheck usa para contar as leituras perdidas;
//  - "rajada <n> <s> [intervaloMs]": o intervalo opcional espaça os envios, para que uma rajada dure
//    o suficiente para se provocar uma falha a meio (ensaios 9(c) e 9(d)); sem ele, envia de seguida.
// Antes de enviar para p<k> avisa se a partição estiver SUSPEITA (sem líder conhecido) e envia na
// mesma: o produtor não retém nem reenvia leituras (fora do âmbito da ficha).
public class Producer {
    private static HeartbeatMonitor[] monitors;
    private static final Path MONITOR_FILE = Paths.get("monitor-produtor.txt");
    private static final Path PRODUCER_FILE = Paths.get("produtor.txt");

    // um evento do detetor = uma linha "instante;evento;p<k>;detalhe" no ficheiro.
    // Na consola só aparecem os eventos relevantes (não os RECEBIDO), para não afogar o prompt.
    static synchronized void event(String e) {
        String line = System.currentTimeMillis() + ";" + e;
        if (!e.startsWith("RECEBIDO")) {
            System.out.println("MONITOR " + line);
        }
        append(MONITOR_FILE, line);
    }

    private static synchronized void append(Path p, String line) {
        try {
            Files.writeString(p, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            System.out.println("IO (" + p + "): " + ex.getMessage());
        }
    }

    // aviso antes de cada envio para p<k> (o IN é enviado de qualquer forma)
    private static void avisarSeSuspeito(int k, String sensor) {
        if (monitors[k].state() == HeartbeatMonitor.State.SUSPEITO) {
            System.out.println("AVISO: partição p" + k + " sem líder conhecido; leitura " + sensor + " pode perder-se");
        }
    }

    // uma thread por partição: lê só a 1.ª linha do LOTE (termo, líder, hb, ...) e alimenta o detetor
    private static void startMonitors(IpfsPubSub pubsub, int n) {
        monitors = new HeartbeatMonitor[n];
        for (int k = 0; k < n; k++) {
            monitors[k] = new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, Producer::event);
        }
        for (int k = 0; k < n; k++) {
            final int fk = k;
            Thread t = new Thread(() -> {
                try {
                    pubsub.subscribe(Wire.topicData(fk), msg -> {
                        try {
                            // {termo, lider, endereço, porto, hb, ultimoSeq, ultimoTermo, n}
                            String[] h = Batch.header(msg);
                            long term = Long.parseLong(h[0]), hb = Long.parseLong(h[4]);
                            int leader = Integer.parseInt(h[1]);
                            monitors[fk].onHeartbeat(term, leader, hb);
                            event("RECEBIDO;p" + fk + ";termo=" + term + ";lider=n" + leader + ";hb=" + hb
                                    + ";ultimoSeq=" + h[5] + ";n=" + h[7]);
                        } catch (IllegalArgumentException e) {
                            System.out.println("REJEITADO p" + fk + ": " + e.getMessage());
                        }
                    });
                } catch (IOException e) {
                    System.out.println("IO p" + fk + ": " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "produtor-p" + k);
            t.setDaemon(true);
            t.start();
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
    }

    // o produtor não observa registo algum: envia sempre L = 0 e V = (0,...,0)
    private static String in(String sensor, double temperature, int n) {
        return Wire.in(sensor, temperature, 0, new VectorClock(n));
    }

    // o daemon pode recusar uma publicação ("validation ignored") enquanto não há subscritores:
    // repete até 8 vezes, com 2 ms de intervalo; devolve o número de repetições feitas
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

    // envia um IN e regista-o em produtor.txt (instante;p<k>;sensor;temperatura)
    private static int send(IpfsPubSub pubsub, int k, String sensor, double temp, int n)
            throws IOException, InterruptedException {
        avisarSeSuspeito(k, sensor);
        long now = System.currentTimeMillis();
        int retries = publicarComRepeticao(pubsub, Wire.topicIn(k), in(sensor, temp, n));
        append(PRODUCER_FILE, now + ";p" + k + ";" + sensor + ";" + temp);
        return retries;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: Producer <N> [api]");
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
        Random rnd = new Random();
        startMonitors(pubsub, n);
        try (Scanner sc = new Scanner(System.in)) {
            System.out.println("Produtor pronto (N=" + n + ", " + api + "). Formato: <sensor> <temperatura> | "
                    + "rajada <n> <s> [intervaloMs] | rajada-quente <n> <s> [intervaloMs] | 'sair' para terminar");
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                if (line.equalsIgnoreCase("sair")) break;
                String[] p = line.split("\\s+");
                try {
                    if (p[0].equalsIgnoreCase("rajada") || p[0].equalsIgnoreCase("rajada-quente")) {
                        burst(p, p[0].equalsIgnoreCase("rajada-quente"), pubsub, part, rnd);
                    } else if (p.length == 2) {
                        // leitura manual: "<sensor> <temperatura>", ex.: S02 20.1
                        double temp = Double.parseDouble(p[1]);
                        int k = part.of(p[0]);
                        int retries = send(pubsub, k, p[0], temp, n);
                        System.out.println("Enviado: " + p[0] + " " + temp + " -> p" + k + " (repetições=" + retries + ")");
                    } else {
                        System.out.println("Formato inválido.");
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Número inválido: " + e.getMessage());
                } catch (IOException e) {
                    System.out.println("IO: " + e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // rajada <n> <s> [intervaloMs]: n leituras de s sensores (S01..Snn), temperaturas entre 15,0 e 30,0
    // rajada-quente: 80% das leituras vão para sensores da partição 0
    static void burst(String[] p, boolean hot, IpfsPubSub pubsub, Partitioner part, Random rnd)
            throws IOException, InterruptedException {
        if (p.length != 3 && p.length != 4) {
            System.out.println("Uso: " + p[0] + " <n> <s> [intervaloMs]");
            return;
        }
        int count = Integer.parseInt(p[1]);
        int sensors = Integer.parseInt(p[2]);
        long interval = p.length == 4 ? Long.parseLong(p[3]) : 0;
        if (count < 1 || sensors < 1 || interval < 0) {
            System.out.println("n e s têm de ser >= 1 e o intervalo >= 0");
            return;
        }
        int n = part.count();
        int[] perPartition = new int[n];
        int retries = 0;
        long first = 0, last = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < count; i++) {
            int id;
            if (hot && rnd.nextDouble() < 0.8) {
                id = 1 + rnd.nextInt(sensors);
                for (int tries = 0; tries < 50 && part.of(String.format("S%02d", id)) != 0; tries++) {
                    id = 1 + rnd.nextInt(sensors);
                }
            } else {
                id = 1 + rnd.nextInt(sensors);
            }
            String sensor = String.format("S%02d", id);
            double temp = Math.round((15.0 + rnd.nextDouble() * 15.0) * 10.0) / 10.0;   // 1 casa decimal
            int k = part.of(sensor);
            if (i == 0) first = System.currentTimeMillis();
            retries += send(pubsub, k, sensor, temp, n);
            perPartition[k]++;
            if (i == count - 1) last = System.currentTimeMillis();
            if (interval > 0 && i < count - 1) Thread.sleep(interval);
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < perPartition.length; k++) {
            sb.append(" p").append(k).append('=').append(perPartition[k]);
        }
        System.out.printf("Rajada de %d: primeiro envio=%d ms, último envio=%d ms, duração=%.1f ms, repetições=%d;%s%n",
                count, first, last, ms, retries, sb);
    }
}
