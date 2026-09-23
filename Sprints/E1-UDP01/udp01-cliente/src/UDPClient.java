import java.net.*;
import java.io.*;
import java.util.Scanner;                                   // (1) NOVO

public class UDPClient {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;
        Scanner teclado = new Scanner(System.in);               // (1) NOVO

        try {
            aSocket = new DatagramSocket();
            InetAddress aHost = InetAddress.getByName("localhost");
            int serverPort = 6789;
            byte[] buffer = new byte[1000];

            while (true) {                                        // (2) NOVO
                System.out.print("Mensagem (sair para terminar): ");
                String texto = teclado.nextLine();                  // (3) NOVO

                if (texto.equals("sair")) {                         // (4) NOVO
                    break;
                }

                byte[] m = texto.getBytes();
                DatagramPacket request = new DatagramPacket(m, m.length, aHost, serverPort);
                aSocket.send(request);

                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(reply);

                System.out.println("Reply: " + new String(reply.getData(), 0, reply.getLength()));  // (5) ALTERADO
            }

        } catch (SocketException e) { System.out.println("Socket: " + e.getMessage());
        } catch (IOException e)     { System.out.println("IO: " + e.getMessage());
        } finally { if (aSocket != null) aSocket.close(); }
    }
}