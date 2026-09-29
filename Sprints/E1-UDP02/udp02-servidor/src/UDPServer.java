import java.io.*;
import java.net.*;
import java.util.*;

public class UDPServer {

    // Lista das mensagens que já foram entregues pela ordem correta
    private static final List<String> listaRececao = new ArrayList<>();

    // Mensagens recebidas mas ainda não entregues
    // chave = número da mensagem
    // valor = conteúdo da mensagem
    private static final Map<Integer, String> temporaria = new HashMap<>();

    // Mensagens entregues durante o processamento do último datagrama
    private static final List<String> mensagensEntreguesNestePasso = new ArrayList<>();

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
                int virgula = mensagem.indexOf(',');
                String resposta;

                // Verificar se a mensagem tem o formato: N,mensagem
                if (virgula <= 0) {
                    resposta = "waitingfor," + (L + 1);
                    enviarResposta(aSocket, resposta, request);
                    System.out.println("Mensagem mal formada: " + mensagem
                            + " | L = " + L + " | temporaria = " + temporaria);
                    continue;
                }

                int N;
                try {
                    N = Integer.parseInt(mensagem.substring(0, virgula));
                } catch (NumberFormatException e) {
                    resposta = "waitingfor," + (L + 1);
                    enviarResposta(aSocket, resposta, request);
                    System.out.println("Número inválido: " + mensagem
                            + " | L = " + L + " | temporaria = " + temporaria);
                    continue;
                }

                String texto = mensagem.substring(virgula + 1);

                int LAntes = L;
                mensagensEntreguesNestePasso.clear();

                // Processar a mensagem
                L = processDeliveredMessages(L, N, texto);

                // Se L avançou, a mensagem foi entregue.
                // Caso contrário, ficou fora de ordem e o servidor continua à espera de L + 1.
                if (L > LAntes) {
                    resposta = mensagem;
                } else {
                    resposta = "waitingfor," + (L + 1);
                }

                enviarResposta(aSocket, resposta, request);

                System.out.println();
                System.out.println("Recebido: " + mensagem);
                System.out.println("L antes: " + LAntes);
                System.out.println("Mensagens entregues neste passo: " + mensagensEntreguesNestePasso);
                System.out.println("L depois: " + L);
                System.out.println("Lista de receção: " + listaRececao);
                System.out.println("Estrutura temporária: " + temporaria);
                System.out.println("Resposta: " + resposta);
                System.out.println("================================");
            }
        } catch (SocketException e) {
            System.out.println("Socket: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } finally {
            if (aSocket != null) {
                aSocket.close();
            }
        }
    }

    /**
     * Processes delivered messages
     *
     * @return the last message processed in order
     */
    public static int processDeliveredMessages(int nLastMessageInOrder, int nCurrentMessage, String currentMessage) {
        int L = nLastMessageInOrder;

        if (nCurrentMessage == L + 1) {
            // CASO 1: a mensagem está em ordem
            String mensagemCompleta = nCurrentMessage + "," + currentMessage;
            listaRececao.add(mensagemCompleta);
            mensagensEntreguesNestePasso.add(mensagemCompleta);
            L = nCurrentMessage;

            // Depois de entregar a mensagem atual, verificar se existem
            // mensagens seguintes guardadas na estrutura temporária.
            while (temporaria.containsKey(L + 1)) {
                int proximaMensagem = L + 1;
                String mensagemGuardada = temporaria.remove(proximaMensagem);
                String mensagemCompletaGuardada = proximaMensagem + "," + mensagemGuardada;

                listaRececao.add(mensagemCompletaGuardada);
                mensagensEntreguesNestePasso.add(mensagemCompletaGuardada);
                L = proximaMensagem;
            }
        } else {
            // CASO 2: a mensagem está fora de ordem.
            // Qualquer N que não seja L + 1 é colocado na estrutura temporária.
            temporaria.put(nCurrentMessage, currentMessage);
        }

        return L;
    }

    private static void enviarResposta(DatagramSocket socket, String resposta, DatagramPacket request)
            throws IOException {
        byte[] r = resposta.getBytes();
        DatagramPacket reply = new DatagramPacket(r, r.length, request.getAddress(), request.getPort());
        socket.send(reply);
    }
}
