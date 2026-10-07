package tcp01;

import java.io.*;
import java.net.*;

public class TCPClient {
    public static void main(String[] args) {
        Socket s = null;
        try {
            int serverPort = 7896;                              // porto do servidor
            s = new Socket("localhost", serverPort);            // falha (ConnectException) se não houver servidor
            ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            Person p = new Person("Ana", new Place("3500-606", "Viseu"), 2000);
            out.writeObject(p);                                 // serializa Person e Place
            out.flush();
            String data = in.readUTF();                         // BLOQUEIA: espera a resposta
            System.out.println("Received: " + data);
        } catch (UnknownHostException e) {
            System.out.println("Sock: " + e.getMessage());
        } catch (EOFException e) {
            System.out.println("EOF: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e);
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    System.out.println("close: " + e.getMessage());
                }
            }
        }
    }
}
