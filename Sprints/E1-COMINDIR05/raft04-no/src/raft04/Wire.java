package raft04;

import java.nio.file.Files;
import java.nio.file.Paths;

// Constantes e formatos das mensagens trocadas entre processos (um único sítio para o "protocolo").
// ALTERADO na COMINDIR05 (eleição do líder):
//  - prefixo dos tópicos passa a raft04;
//  - sai tudo o que se referia à incarnação "inc" do líder fixo;
//  - Δ passa a 2000 ms por omissão e a verificação a 200 ms;
//  - entram os prazos de eleição, o tamanho do grupo (G = 3), o tópico de eleição,
//    o porto TCP de cada nó e a simulação de partições de rede (isolated).
// É IGUAL nos três projetos (raft04-no, raft04-produtor, raft04-processador).
public final class Wire {
    public static final int T_TCP_MS = 5000;           // tempo limite da ligação de recuperação
    public static final int PORTO_BASE = 7000;         // ver nodePort(k, i)
    public static final double LIMIAR_ALARME = 28.0;   // temperatura a partir da qual há alarme

    // Δ: período do heartbeat (= período do lote). -Draft04.periodo=<ms>, por omissão 2000.
    // Tem de ser IGUAL em todos os processos: o líder usa-o para emitir, os outros para o TIMEOUT.
    public static final long PERIODO_HB_MS = Long.getLong("raft04.periodo", 2000L);
    // TIMEOUT dos detetores do produtor e do processador = FATOR_TIMEOUT × Δ. -Draft04.fator=<f>
    public static final int FATOR_TIMEOUT = Integer.getInteger("raft04.fator", 3);
    // de quanto em quanto tempo a thread de verificação corre (tick() nos nós, check() nos monitores)
    public static final long VERIFICACAO_MS = 200;

    // NOVO: prazo de eleição sorteado uniformemente em [ELEICAO_MIN_MS, ELEICAO_MAX_MS].
    // Por omissão 3Δ e 6Δ (6000 e 12000 ms). Com -Draft04.eleicaoMin=-Draft04.eleicaoMax=6000
    // o prazo deixa de ser aleatório (ensaio 9(b) com prazo fixo).
    public static final long ELEICAO_MIN_MS = Long.getLong("raft04.eleicaoMin", 3 * PERIODO_HB_MS);
    public static final long ELEICAO_MAX_MS = Long.getLong("raft04.eleicaoMax", 6 * PERIODO_HB_MS);
    // NOVO: número de nós de cada grupo (cada partição de dados é servida por n1, n2 e n3)
    public static final int GRUPO = 3;

    private Wire() {}                                  // só métodos estáticos: não se instancia

    // tópico de entrada da partição k: produtor e processador publicam IN; os três nós subscrevem
    // (só o líder atribui seq; os outros assinalam IGNORADO)
    public static String topicIn(int k) {
        return "raft04-in-p" + k;
    }

    // tópico de dados da partição k: o líder publica LOTE; os outros nós, o processador e o produtor subscrevem
    public static String topicData(int k) {
        return "raft04-p" + k;
    }

    // NOVO: tópico de eleição da partição k (PEDIDO_VOTO e VOTO). Cada partição de dados tem o seu:
    // as eleições de p0, p1 e p2 são independentes.
    public static String topicElection(int k) {
        return "raft04-eleicao-p" + k;
    }

    // NOVO: porto TCP de recuperação do nó i (1..3) da partição k: 7000 + 10k + i
    // ex.: p0 -> 7001, 7002, 7003; p1 -> 7011, 7012, 7013; p2 -> 7021, 7022, 7023
    public static int nodePort(int k, int i) {
        return PORTO_BASE + 10 * k + i;
    }

    // NOVO: simulação de uma partição de REDE. Enquanto existir, na pasta de trabalho, o ficheiro
    // isolar-p<k>-n<i>, o nó i de p<k> não publica nem processa mensagens de raft04-p<k> e
    // raft04-eleicao-p<k>, e as suas ligações TCP de recuperação (cliente ou servidor) falham.
    // O tópico raft04-in-p<k> NÃO é afetado: o produtor continua a alcançar os dois lados.
    // É consultado a cada mensagem (não fica em cache), para que criar/apagar o ficheiro tenha efeito imediato.
    public static boolean isolated(int k, int i) {
        return Files.exists(Paths.get("isolar-p" + k + "-n" + i));
    }

    // IN;<sensor>;<temperatura>;<L>;<V>   (inalterado)
    // L e V são o conhecimento causal de quem envia (zeros no produtor, Lp/Vp no processador)
    public static String in(String sensor, double temperature, long lamport, VectorClock vector) {
        return "IN;" + sensor + ";" + temperature + ";" + lamport + ";" + vector;
    }

    // conteúdo de um IN depois de validado
    public static final class In {
        public final String sensor;
        public final double temperature;
        public final long lamport;                     // Lmsg
        public final VectorClock vector;               // Vmsg

        In(String sensor, double temperature, long lamport, VectorClock vector) {
            this.sensor = sensor;
            this.temperature = temperature;
            this.lamport = lamport;
            this.vector = vector;
        }
    }

    // valida um IN com um vetor de n componentes; lança IllegalArgumentException se estiver mal formado
    public static In parseIn(String message, int n) {
        if (!type(message).equals("IN")) {
            throw new IllegalArgumentException("tipo desconhecido");
        }
        String[] f = body(message).split(";");        // sensor; temperatura; L; V
        if (f.length != 4 || f[0].isBlank()) {
            throw new IllegalArgumentException("campos");
        }
        return new In(f[0].trim(), Double.parseDouble(f[1]), Long.parseLong(f[2]), VectorClock.parse(f[3], n));
    }

    // tipo da mensagem: o texto antes do primeiro ';' (IN, LOTE, PEDIDO_VOTO, VOTO)
    public static String type(String message) {
        String m = message.trim();
        int i = m.indexOf(';');
        if (i <= 0) {
            throw new IllegalArgumentException("Mensagem sem tipo: " + message);
        }
        return m.substring(0, i);
    }

    // tudo o que vem depois do tipo
    public static String body(String message) {
        String m = message.trim();
        return m.substring(m.indexOf(';') + 1);
    }
}
