package rep01;

import java.io.*;
import java.util.Random;
import java.util.Scanner;

public class PubSubLeader {
    static final String TOPIC = "rep01-temperaturas";

    public static void main(String[] args) {
        String api = args.length > 0 ? args[0] : "http://127.0.0.1:5001/api/v0";
        IpfsPubSub pubsub = new IpfsPubSub(api);
        try (Scanner sc = new Scanner(System.in)) {
            RecordFile file = new RecordFile("lider.txt");
            long seq = file.lastSeq();                              // continua a numeração após reinício
            Random rnd = new Random();
            System.out.println("Líder pronto (" + api + "). Formato: <sensor> <temperatura> | rajada <n> | desordem | 'sair' para terminar");
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break;                       // bloqueia à espera do utilizador
                String line = sc.nextLine().trim();
                if (line.equalsIgnoreCase("sair")) break;
                String[] p = line.split("\\s+");

                if (p[0].equalsIgnoreCase("rajada") && p.length == 2) {
                    int n;
                    try {
                        n = Integer.parseInt(p[1]);
                    } catch (NumberFormatException e) {
                        System.out.println("Número inválido: " + p[1]);
                        continue;
                    }
                    long first = 0, last = 0;
                    long t0 = System.nanoTime();
                    for (int i = 0; i < n; i++) {
                        double temp = Math.round((15 + rnd.nextDouble() * 15) * 10) / 10.0;
                        SensorRecord r = SensorRecord.now(++seq, "SIM", temp);
                        file.append(r);
                        if (i == 0) first = System.currentTimeMillis();
                        pubsub.publish(TOPIC, r.toLine());          // bloqueia até o daemon responder
                        if (i == n - 1) last = System.currentTimeMillis();
                    }
                    double ms = (System.nanoTime() - t0) / 1e6;
                    System.out.printf("Rajada de %d: primeiro envio=%d ms, último envio=%d ms, duração=%.1f ms, débito=%.0f reg/s%n",
                            n, first, last, ms, n / (ms / 1000.0));
                    continue;
                }

                if (p[0].equalsIgnoreCase("desordem")) {            // teste: publica seq+1, seq+2 e só depois seq
                    SensorRecord a = SensorRecord.now(++seq, "DES", 20.0);
                    SensorRecord b = SensorRecord.now(++seq, "DES", 21.0);
                    SensorRecord c = SensorRecord.now(++seq, "DES", 22.0);
                    file.append(a);
                    file.append(b);
                    file.append(c);
                    pubsub.publish(TOPIC, b.toLine());
                    pubsub.publish(TOPIC, c.toLine());
                    pubsub.publish(TOPIC, a.toLine());
                    System.out.println("Publicados fora de ordem: " + b.getSeq() + ", " + c.getSeq() + ", " + a.getSeq());
                    continue;
                }

                if (p.length != 2) {
                    System.out.println("Formato inválido.");
                    continue;
                }
                double temp;
                try {
                    temp = Double.parseDouble(p[1]);
                } catch (NumberFormatException e) {
                    System.out.println("Temperatura inválida: " + p[1]);
                    continue;
                }
                SensorRecord r = SensorRecord.now(++seq, p[0], temp);
                file.append(r);                                     // 1) escreve localmente
                pubsub.publish(TOPIC, r.toLine());                  // 2) publica no tópico (HTTP para o daemon local)
                System.out.println("Registado e publicado: " + r.toLine());
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
