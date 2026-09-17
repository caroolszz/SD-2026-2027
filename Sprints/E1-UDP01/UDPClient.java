import java.net.*;
import java.io.*;

public class UDPClient {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        try {
            aSocket = new DatagramSocket();
            InetAddress aHost = InetAddress.getByName("localhost");
            int serverPort = 6789;

            BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in));

            while (true) {
                System.out.print("Mensagem a enviar (ou 'sair' para terminar): ");
                String texto = teclado.readLine();

                if (texto == null || texto.equalsIgnoreCase("sair")) {
                    break;
                }

                byte[] m = texto.getBytes();
                DatagramPacket request = new DatagramPacket(m, m.length, aHost, serverPort);
                aSocket.send(request);

                byte[] buffer = new byte[1000];
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(reply);

                System.out.println("Reply: " + new String(reply.getData(), 0, reply.getLength()));
            }

        } catch (SocketException e) { System.out.println("Socket: " + e.getMessage());
        } catch (IOException e)     { System.out.println("IO: " + e.getMessage());
        } finally { if (aSocket != null) aSocket.close(); }
    }
}