import java.net.*;
import java.io.*;
import java.util.Scanner;

public class UDPClient {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;
        Scanner teclado = new Scanner(System.in);

        try {
            aSocket = new DatagramSocket();
            InetAddress aHost = InetAddress.getByName("localhost");
            int serverPort = 6789;
            byte[] buffer = new byte[1000];

            System.out.print("Modo de numeração (a = automático, m = manual): ");
            String modo = teclado.nextLine();
            int proximo = 1;

            while (true) {
                String numero = "";
                if (modo.equals("m")) {
                    System.out.print("Número de sequência (sair para terminar): ");
                    numero = teclado.nextLine();
                    if (numero.equals("sair")) {
                        break;
                    }
                }

                System.out.print("Mensagem (sair para terminar): ");
                String texto = teclado.nextLine();

                if (texto.equals("sair")) {
                    break;
                }

                if (!modo.equals("m")) {
                    numero = String.valueOf(proximo);
                    proximo++;
                }

                String mensagem = numero + "," + texto;
                byte[] m = mensagem.getBytes();
                DatagramPacket request = new DatagramPacket(m, m.length, aHost, serverPort);
                aSocket.send(request);

                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(reply);

                String resposta = new String(reply.getData(), 0, reply.getLength());
                if (resposta.startsWith("waitingfor,")) {
                    System.out.println("FORA DE ORDEM: o servidor espera a mensagem "
                            + resposta.substring(resposta.indexOf(',') + 1));
                } else {
                    System.out.println("Echo: " + resposta);
                }
            }

        } catch (SocketException e) { System.out.println("Socket: " + e.getMessage());
        } catch (IOException e)     { System.out.println("IO: " + e.getMessage());
        } finally { if (aSocket != null) aSocket.close(); }
    }
}