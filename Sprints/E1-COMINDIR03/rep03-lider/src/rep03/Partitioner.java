package rep03;

// Função de partição da COMINDIR02: decide em que partição (0..N-1) fica cada sensor.
// Na COMINDIR03 é usada também pelo processador para escolher a partição do alarme,
// aplicada a "ALARM-<sensor>" (por isso o alarme pode cair noutra partição que não a da causa).
public final class Partitioner {
    private final int n;                    // número de partições (N)

    public Partitioner(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("Número de partições inválido: " + n);
        }
        this.n = n;
    }

    // partição do sensor: igual para todos os processos (depende só do identificador e de N)
    // floorMod em vez de % porque hashCode() pode ser negativo
    // ex. (N=3): S01, S04 -> p0; S02 -> p1; ALARM-S02 -> p2 (como na Figura 1)
    public int of(String sensorId) {
        return Math.floorMod(sensorId.hashCode(), n);
    }

    public int count() {
        return n;
    }
}
