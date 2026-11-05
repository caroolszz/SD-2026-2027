package rep04;

// Constantes e formatos das mensagens trocadas entre processos (um único sítio para o "protocolo").
// Novidades da COMINDIR04: prefixo rep04, deixa de existir o REG (substituído pelo LOTE, ver Batch)
// e passam a existir as constantes do heartbeat e do detetor de falhas.
public final class Wire {
    public static final int LIMITE_RETIDOS = 10;       // registos retidos na réplica antes de recorrer ao TCP
    public static final int T_TCP_MS = 5000;           // tempo limite da ligação de recuperação
    public static final int PORTO_BASE = 7000;         // o líder da partição k escuta em PORTO_BASE + k
    public static final double LIMIAR_ALARME = 28.0;   // temperatura a partir da qual há alarme

    // NOVO na COMINDIR04.
    // Δ: período do heartbeat (= período do lote). -Drep04.periodo=<ms>, por omissão 5000.
    // Tem de ser IGUAL em todos os processos: o líder usa-o para emitir, os participantes para o TIMEOUT.
    public static final long PERIODO_HB_MS = Long.getLong("rep04.periodo", 5000L);
    // TIMEOUT = FATOR_TIMEOUT × Δ. -Drep04.fator=<f>, por omissão 3.
    public static final int FATOR_TIMEOUT = Integer.getInteger("rep04.fator", 3);
    // de quanto em quanto tempo cada participante chama check() nos seus monitores
    public static final long VERIFICACAO_MS = 1000;

    private Wire() {}                                  // só métodos estáticos: não se instancia

    // tópico de entrada da partição k: produtor e processador publicam IN, o líder k subscreve
    public static String topicIn(int k) {
        return "rep04-in-p" + k;
    }

    // tópico de dados da partição k: o líder k publica LOTE; réplicas, processador e produtor subscrevem
    public static String topicData(int k) {
        return "rep04-p" + k;
    }

    // IN;<sensor>;<temperatura>;<L>;<V>   (inalterado em relação à COMINDIR03)
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
    // (NumberFormatException, lançada pelos parse, também é uma IllegalArgumentException)
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

    // tipo da mensagem: o texto antes do primeiro ';' (IN, LOTE, ...)
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
