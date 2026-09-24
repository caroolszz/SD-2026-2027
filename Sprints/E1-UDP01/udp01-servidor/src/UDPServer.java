import java.net.*;
import java.io.*;

public class UDPServer {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;

        try {
            aSocket = new DatagramSocket(6789);
            byte[] buffer = new byte[1000];
            int L = 0;

            while (true) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                aSocket.receive(request);

                String mensagem = new String(request.getData(), 0, request.getLength());

                int N = -1;
                int virgula = mensagem.indexOf(',');
                if (virgula > 0) {
                    try {
                        N = Integer.parseInt(mensagem.substring(0, virgula));
                    } catch (NumberFormatException e) {
                        N = -1;
                    }
                }

                String resposta;
                if (N == L + 1) {
                    resposta = mensagem;
                    L = N;
                } else {
                    resposta = "waitingfor," + (L + 1);
                }

                byte[] r = resposta.getBytes();
                DatagramPacket reply = new DatagramPacket(r, r.length,
                        request.getAddress(), request.getPort());

                aSocket.send(reply);
                System.out.println("Recebido de " + request.getAddress() + ":" + request.getPort()
                        + " -> " + mensagem + " | resposta: " + resposta + " | L = " + L);
            }
        } catch (SocketException e) { System.out.println("Socket: " + e.getMessage());
        } catch (IOException e)     { System.out.println("IO: " + e.getMessage());
        } finally { if (aSocket != null) aSocket.close(); }
    }
}