package rep02;

import java.io.IOException;
import java.util.Random;
import java.util.Scanner;

public class Producer {

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
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: Producer <N> [api]");
            return;
        }
        int n;
        try {
            n = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("N inválido: " + args[0]);
            return;
        }
        String api = args.length > 1 ? args[1] : "http://127.0.0.1:5001/api/v0";
        Partitioner partitioner = new Partitioner(n);
        IpfsPubSub ps = new IpfsPubSub(api);
        Random rnd = new Random();
        try (Scanner sc = new Scanner(System.in)) {
            System.out.println("Produtor pronto (N=" + n + ", " + api + "). Formato: <sensor> <temperatura> | rajada <n> <s> | rajada-quente <n> <s> | 'sair'");
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break;                       // bloqueia à espera do utilizador
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                if (line.equalsIgnoreCase("sair")) break;
                String[] p = line.split("\s+");
                try {
                    if (p[0].equalsIgnoreCase("rajada") || p[0].equalsIgnoreCase("rajada-quente")) {
                        burst(p, p[0].equalsIgnoreCase("rajada-quente"), ps, partitioner, rnd);
                    } else if (p.length == 2) {
                        double temp = Double.parseDouble(p[1]);
                        int k = partitioner.of(p[0]);
                        publicarComRepeticao(ps, Wire.topicIn(k), Wire.in(p[0], temp));
                        System.out.println("Publicado " + p[0] + " " + temp + " → p" + k);
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

    // rajada <n> <s> / rajada-quente <n> <s>
    static void burst(String[] p, boolean hot, IpfsPubSub ps, Partitioner part, Random rnd)
            throws IOException, InterruptedException {
        if (p.length != 3) {
            System.out.println("Formato: " + p[0] + " <n> <s>");
            return;
        }
        int count = Integer.parseInt(p[1]);
        int sensors = Integer.parseInt(p[2]);
        if (count < 1 || sensors < 1) {
            System.out.println("n e s têm de ser >= 1");
            return;
        }
        int[] perPartition = new int[part.count()];
        int repetidas = 0;
        long first = 0, last = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < count; i++) {
            int s;
            if (hot && rnd.nextDouble() < 0.8) {
                s = 1;                                              // chave quente: S01
            } else if (hot) {
                s = sensors > 1 ? 2 + rnd.nextInt(sensors - 1) : 1; // restantes: S02..S<s>
            } else {
                s = 1 + rnd.nextInt(sensors);
            }
            String sensor = String.format("S%02d", s);
            double temp = Math.round((15 + rnd.nextDouble() * 15) * 10) / 10.0;
            int k = part.of(sensor);
            if (i == 0) first = System.currentTimeMillis();
            repetidas += publicarComRepeticao(ps, Wire.topicIn(k), Wire.in(sensor, temp)); // bloqueia até o daemon responder
            if (i == count - 1) last = System.currentTimeMillis();
            perPartition[k]++;
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < perPartition.length; k++) {
            sb.append(" p").append(k).append('=').append(perPartition[k]);
        }
        System.out.printf("Rajada de %d: primeiro envio=%d ms, último envio=%d ms, duração=%.1f ms, repetições=%d;%s%n",
                count, first, last, ms, repetidas, sb);
    }
}
