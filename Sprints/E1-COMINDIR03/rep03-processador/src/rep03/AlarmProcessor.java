package rep03;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

// Observa todas as partições e publica um alarme derivado (ALARM-<sensor>) quando a temperatura >= LIMIAR_ALARME.
// Não gera eventos: apenas transporta, nos IN, o conhecimento causal (Lp, Vp) que acumulou.
// Executado como: AlarmProcessor <N> [api]
// É este processo que cria dependências entre partições: lê os REG de uma partição e escreve
// um IN noutra; o líder dessa outra partição faz merge com Vp e o alarme fica "depois" da causa.
public class AlarmProcessor {
    private static final Object LOCK = new Object();        // protege Lp, Vp e o conjunto de registos vistos
    private static long lp;                                 // Lp: máximo dos L de todos os registos recebidos
    private static VectorClock vp;                          // Vp: máximo, componente a componente, dos V recebidos
    private static final Set<String> seen = new HashSet<>();   // registos já tratados, chave "k#seq"

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: AlarmProcessor <N> [api]");
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
        lp = 0;
        vp = new VectorClock(n);                            // ainda não observou nada

        // uma thread por partição, porque subscribe() bloqueia; todas partilham Lp e Vp
        for (int k = 0; k < n; k++) {
            final int fk = k;
            new Thread(() -> {
                try {
                    pubsub.subscribe(Wire.topicData(fk), msg -> {
                        try {
                            onRecord(fk, n, msg, part, pubsub);
                        } catch (IOException | RuntimeException e) {
                            // um REG mal formado ou uma publicação falhada não pode matar a subscrição
                            System.out.println("IO p" + fk + ": " + e.getMessage());
                        }
                    });
                    System.out.println("Subscrição p" + fk + " terminada.");
                } catch (IOException e) {
                    System.out.println("IO p" + fk + ": " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "processador-p" + k).start();
        }
        System.out.println("Processador pronto (N=" + n + ", " + api + "), limiar=" + Wire.LIMIAR_ALARME);
    }

    // trata um REG recebido no tópico de dados da partição k
    private static void onRecord(int k, int n, String msg, Partitioner part, IpfsPubSub pubsub)
            throws IOException {
        SensorRecord r;
        try {
            r = SensorRecord.fromLine(Wire.regParts(msg)[2], n);    // rejeita vetores de dimensão != N
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO p" + k + ": " + e.getMessage());
            return;
        }
        long lpSnapshot;
        VectorClock vpSnapshot;
        synchronized (LOCK) {
            // o PubSub pode entregar o mesmo REG mais do que uma vez: não se repete o alarme
            if (!seen.add(k + "#" + r.getSeq())) {
                return;                                             // duplicado (mesma partição, mesmo seq)
            }
            // acumula o conhecimento causal: o próximo alarme fica depois de TUDO o que já foi observado
            // (inclui os registos ALARM-: também foram recebidos, só não geram novo alarme)
            lp = Math.max(lp, r.getLamport());                      // máximo de TODOS os registos recebidos
            vp.merge(r.getVector());
            // instantâneo tirado ainda dentro do lock: depois de sair, as outras threads continuam
            // a alterar vp; sem copy() o IN podia levar um Vp de registos posteriores com um Lp antigo
            lpSnapshot = lp;
            vpSnapshot = vp.copy();                                 // cópia: outras threads continuam a alterar Vp
        }
        System.out.println("OBSERVADO: p" + k + "#" + r.getSeq() + " " + r.getSensorId() + " " + r.getTemperature()
                + " -> Lp=" + lpSnapshot + " Vp=" + vpSnapshot);
        if (r.getSensorId().startsWith("ALARM-")) {
            return;                                                 // sem alarmes sobre alarmes
        }
        if (r.getTemperature() >= Wire.LIMIAR_ALARME) {
            String alarm = "ALARM-" + r.getSensorId();
            int j = part.of(alarm);                                 // partição do alarme (pode ser != k)
            try {
                // IN;ALARM-<sensor>;<temperatura>;<Lp>;<Vp>  ->  rep03-in-p<j>
                // a publicação é feita fora do lock (é lenta) com os valores do instantâneo
                publicar(pubsub, Wire.topicIn(j), Wire.in(alarm, r.getTemperature(), lpSnapshot, vpSnapshot));
                System.out.println("ALARME: " + r.getSensorId() + " p" + k + "#" + r.getSeq() + " → p" + j);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // publicação com repetição (o daemon pode recusar enquanto não há subscritores)
    private static void publicar(IpfsPubSub pubsub, String topic, String message)
            throws IOException, InterruptedException {
        int attempt = 1;
        while (true) {
            try {
                pubsub.publish(topic, message);
                return;
            } catch (IOException e) {
                if (attempt >= 8 || !String.valueOf(e.getMessage()).contains("validation ignored")) {
                    throw e;
                }
                Thread.sleep(2);
                attempt++;
            }
        }
    }
}
