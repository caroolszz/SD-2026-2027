package rep01;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.Scanner;

public class MulticastLeader {
    static final String GROUP = "230.0.0.1";
    static final int PORT = 6789;

    public static void main(String[] args) {
        try (MulticastSocket socket = new MulticastSocket();
             Scanner sc = new Scanner(System.in)) {
            InetAddress group = InetAddress.getByName(GROUP);
            socket.setTimeToLive(1);                                // o datagrama não sai da rede local
            RecordFile file = new RecordFile("lider.txt");
            long seq = file.lastSeq();                              // continua a numeração após reinício
            Random rnd = new Random();
            System.out.println("Líder pronto. Formato: <sensor> <temperatura> | rajada <n> | desordem | 'sair' para terminar");
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
                        send(socket, group, r);
                        if (i == n - 1) last = System.currentTimeMillis();
                    }
                    double ms = (System.nanoTime() - t0) / 1e6;
                    System.out.printf("Rajada de %d: primeiro envio=%d ms, último envio=%d ms, duração=%.1f ms, débito=%.0f reg/s%n",
                            n, first, last, ms, n / (ms / 1000.0));
                    continue;
                }

                if (p[0].equalsIgnoreCase("desordem")) {            // teste: envia seq+1, seq+2 e só depois seq
                    SensorRecord a = SensorRecord.now(++seq, "DES", 20.0);
                    SensorRecord b = SensorRecord.now(++seq, "DES", 21.0);
                    SensorRecord c = SensorRecord.now(++seq, "DES", 22.0);
                    file.append(a);
                    file.append(b);
                    file.append(c);
                    send(socket, group, b);
                    send(socket, group, c);
                    send(socket, group, a);
                    System.out.println("Enviados fora de ordem: " + b.getSeq() + ", " + c.getSeq() + ", " + a.getSeq());
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
                send(socket, group, r);                             // 2) propaga para o grupo
                System.out.println("Registado e enviado: " + r.toLine());
            }
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        }
    }

    private static void send(MulticastSocket socket, InetAddress group, SensorRecord r) throws IOException {
        byte[] m = r.toLine().getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(m, m.length, group, PORT));
    }
}
