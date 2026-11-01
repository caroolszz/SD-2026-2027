package rep03;

// Constantes e formatos das mensagens trocadas entre processos (um único sítio para o "protocolo").
// Novidades da COMINDIR03: prefixo rep03, LIMIAR_ALARME e os carimbos L e V em IN e REG.
public final class Wire {
    public static final int LIMITE_RETIDOS = 10;       // registos retidos na réplica antes de recorrer ao TCP
    public static final int T_TCP_MS = 5000;           // tempo limite da ligação de recuperação
    public static final int PORTO_BASE = 7000;         // o líder da partição k escuta em PORTO_BASE + k
    public static final double LIMIAR_ALARME = 28.0;   // temperatura a partir da qual há alarme

    private Wire() {}                                  // só métodos estáticos: não se instancia

    // tópico de entrada da partição k: produtor e processador publicam IN, o líder k subscreve
    public static String topicIn(int k) {
        return "rep03-in-p" + k;
    }

    // tópico de dados da partição k: o líder k publica REG, réplicas e processador subscrevem
    public static String topicData(int k) {
        return "rep03-p" + k;
    }

    // IN;<sensor>;<temperatura>;<L>;<V>
    // L e V são o conhecimento causal de quem envia (zeros no produtor, Lp/Vp no processador)
    public static String in(String sensor, double temperature, long lamport, VectorClock vector) {
        return "IN;" + sensor + ";" + temperature + ";" + lamport + ";" + vector;
    }

    // REG;<endereço>;<porto>;<seq>;<sensor>;<temperatura>;<instante>;<L>;<V>
    // endereço e porto dizem às réplicas onde pedir, por TCP, os registos em falta
    public static String reg(String address, int port, SensorRecord r) {
        return "REG;" + address + ";" + port + ";" + r.toLine();
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

    // tipo da mensagem: o texto antes do primeiro ';' (IN, REG, ...)
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

    // devolve {endereço, porto, linha do registo (seq;sensor;temperatura;instante;L;V)}
    // split com limite 4: a linha do registo fica inteira no último elemento
    public static String[] regParts(String message) {
        String[] f = message.trim().split(";", 4);
        if (f.length != 4 || !f[0].equals("REG")) {
            throw new IllegalArgumentException("REG mal formado: " + message);
        }
        return new String[] { f[1], f[2], f[3] };
    }
}
