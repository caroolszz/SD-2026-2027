package rep03;

import java.time.LocalDateTime;

// Um registo de uma partição: o que o líder escreve no ficheiro, publica no REG e envia na
// recuperação TCP. É imutável (todos os campos final), por isso pode ser partilhado entre threads.
// Em relação à COMINDIR02 ganha dois campos: o relógio de Lamport (L) e o relógio vetorial (V)
// atribuídos pelo líder no momento em que deu o seq a este registo.
public class SensorRecord {
    private final long seq;                 // número de sequência na partição (1, 2, 3, ...)
    private final String sensorId;          // ex.: S02 ou ALARM-S02
    private final double temperature;
    private final String timestamp;         // instante (relógio físico do líder) - só informativo
    private final long lamport;             // L: relógio de Lamport do líder neste evento
    private final VectorClock vector;       // V: relógio vetorial do líder neste evento (V[k] = seq)

    public SensorRecord(long seq, String sensorId, double temperature, String timestamp,
                        long lamport, VectorClock vector) {
        this.seq = seq;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;
        this.lamport = lamport;
        this.vector = vector;
    }

    // cria um registo com o instante atual (usado só pelo líder); o vetor deve ser uma cópia
    // (se fosse o próprio V do líder, o registo "mudaria" quando o líder tratasse o evento seguinte)
    public static SensorRecord now(long seq, String sensorId, double temperature,
                                   long lamport, VectorClock vector) {
        return new SensorRecord(seq, sensorId, temperature, LocalDateTime.now().withNano(0).toString(),
                lamport, vector);
    }

    // representação textual: é o que se guarda no ficheiro e o que se envia pela rede
    // formato: seq;sensor;temperatura;instante;L;V   (V = "v0,v1,...,vN-1", via VectorClock.toString)
    public String toLine() {
        return seq + ";" + sensorId + ";" + temperature + ";" + timestamp + ";" + lamport + ";" + vector;
    }

    // lê uma linha cujo vetor tem exatamente n componentes
    // (usado pelo processador e pelo CausalCheck: um vetor com outra dimensão é rejeitado)
    public static SensorRecord fromLine(String line, int n) {
        String[] f = split(line);
        return new SensorRecord(Long.parseLong(f[0]), f[1], Double.parseDouble(f[2]), f[3],
                Long.parseLong(f[4]), VectorClock.parse(f[5], n));
    }

    // lê uma linha sem exigir uma dimensão (as réplicas guardam os carimbos, mas não os usam)
    // a dimensão é deduzida do número de componentes escritas
    public static SensorRecord fromLine(String line) {
        String[] f = split(line);
        int n = f[5].trim().split(",").length;
        return new SensorRecord(Long.parseLong(f[0]), f[1], Double.parseDouble(f[2]), f[3],
                Long.parseLong(f[4]), VectorClock.parse(f[5], n));
    }

    // parte a linha nos 6 campos; o vetor usa ',' e não ';', por isso fica inteiro em f[5]
    private static String[] split(String line) {
        String[] f = line.trim().split(";");
        if (f.length != 6) {
            throw new IllegalArgumentException("Registo mal formado: " + line);
        }
        return f;
    }

    public long getSeq() { return seq; }
    public String getSensorId() { return sensorId; }
    public double getTemperature() { return temperature; }
    public String getTimestamp() { return timestamp; }
    public long getLamport() { return lamport; }
    public VectorClock getVector() { return vector; }
}
