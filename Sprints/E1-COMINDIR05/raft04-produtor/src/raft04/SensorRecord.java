package raft04;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

// Um registo de uma partição: o que o líder escreve no ficheiro, publica dentro de um LOTE e envia na
// recuperação TCP. É imutável, por isso pode ser partilhado entre threads.
// ALTERADO na COMINDIR05: cada registo passa a levar o TERMO em que foi criado.
// Dois registos com o mesmo seq e o mesmo termo são o mesmo registo (só há um líder por termo);
// com o mesmo seq e termos diferentes, há divergência e prevalece o do líder atual.
// Formato da linha: seq;termo;sensor;temperatura;instante;L;V
public class SensorRecord {
    private final long seq;                 // número de sequência na partição (1, 2, 3, ...)
    private final long term;                // NOVO: termo do líder que atribuiu este seq
    private final String sensorId;          // ex.: S02 ou ALARM-S02
    private final double temperature;
    private final String timestamp;         // instante (relógio físico do líder), hora local com milissegundos
    private final long lamport;             // L: relógio de Lamport do líder neste evento
    private final VectorClock vector;       // V: relógio vetorial do líder neste evento (V[k] = seq)

    public SensorRecord(long seq, long term, String sensorId, double temperature, String timestamp,
                        long lamport, VectorClock vector) {
        this.seq = seq;
        this.term = term;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;
        this.lamport = lamport;
        this.vector = vector;
    }

    private static final DateTimeFormatter INSTANTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    // cria um registo com o instante atual (usado só pelo líder); o vetor deve ser uma cópia
    public static SensorRecord now(long seq, long term, String sensorId, double temperature,
                                   long lamport, VectorClock vector) {
        return new SensorRecord(seq, term, sensorId, temperature, LocalDateTime.now().format(INSTANTE),
                lamport, vector);
    }

    // instante do registo em ms desde a época, no fuso desta máquina
    // (só faz sentido com todos os processos na mesma máquina, como pede a ficha)
    public long epochMillis() {
        return LocalDateTime.parse(timestamp).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    // representação textual: é o que se guarda no ficheiro e o que se envia pela rede
    public String toLine() {
        return seq + ";" + term + ";" + sensorId + ";" + temperature + ";" + timestamp + ";" + lamport + ";" + vector;
    }

    // lê uma linha cujo vetor tem exatamente n componentes (processador, CausalCheck)
    public static SensorRecord fromLine(String line, int n) {
        String[] f = split(line);
        return new SensorRecord(Long.parseLong(f[0]), Long.parseLong(f[1]), f[2], Double.parseDouble(f[3]), f[4],
                Long.parseLong(f[5]), VectorClock.parse(f[6], n));
    }

    // lê uma linha sem exigir uma dimensão (a dimensão é deduzida do número de componentes)
    public static SensorRecord fromLine(String line) {
        String[] f = split(line);
        int n = f[6].trim().split(",").length;
        return new SensorRecord(Long.parseLong(f[0]), Long.parseLong(f[1]), f[2], Double.parseDouble(f[3]), f[4],
                Long.parseLong(f[5]), VectorClock.parse(f[6], n));
    }

    // parte a linha nos 7 campos; o vetor usa ',' e não ';', por isso fica inteiro em f[6]
    private static String[] split(String line) {
        String[] f = line.trim().split(";");
        if (f.length != 7) {
            throw new IllegalArgumentException("Registo mal formado: " + line);
        }
        return f;
    }

    public long getSeq() { return seq; }
    public long getTerm() { return term; }
    public String getSensorId() { return sensorId; }
    public double getTemperature() { return temperature; }
    public String getTimestamp() { return timestamp; }
    public long getLamport() { return lamport; }
    public VectorClock getVector() { return vector; }
}
