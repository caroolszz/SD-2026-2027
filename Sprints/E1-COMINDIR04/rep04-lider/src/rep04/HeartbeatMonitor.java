package rep04;

import java.util.function.Consumer;

// NOVO na COMINDIR04. Código dado no enunciado; só foram acrescentados comentários.
// Detetor de falhas de UMA partição, num participante (réplica, processador ou produtor).
// Duas threads mexem no estado: a de receção (onHeartbeat) e a de verificação (check);
// por isso todos os métodos são synchronized no próprio monitor.
// O estado é SUSPEITO, nunca "morto": num sistema assíncrono um líder lento é indistinguível
// de um líder que falhou, por isso o detetor só pode suspeitar.
public final class HeartbeatMonitor {
    public enum State { DESCONHECIDO, ATIVO, SUSPEITO }

    private final int partition;
    private final long timeoutNanos;        // TIMEOUT = periodMs × factor, em nanossegundos
    private final Consumer<String> log;     // quem cria o monitor decide onde escrever (consola + monitor-<id>.txt)

    private State state = State.DESCONHECIDO;
    private long lastNanos;   // instante local da última receção
    private long inc = -1;    // incarnação observada
    private long hb = 0;      // último heartbeat observado

    public HeartbeatMonitor(int partition, long periodMs, int factor, Consumer<String> log) {
        this.partition = partition;
        this.timeoutNanos = periodMs * factor * 1_000_000L;
        this.log = log;
        // começa a contar no arranque: se nunca chegar um LOTE, também acaba por suspeitar
        this.lastNanos = System.nanoTime();
    }

    // thread de receção: chegou um LOTE da partição
    public synchronized void onHeartbeat(long inc, long hb) {
        if (inc < this.inc) return;                    // incarnação antiga
        if (inc == this.inc && hb <= this.hb) return;  // duplicado ou atrasado
        if (this.inc != -1 && inc != this.inc) {
            log.accept("REINICIO;p" + partition + ";inc=" + this.inc + "->" + inc);
        } else if (inc == this.inc && hb != this.hb + 1) {
            log.accept("HB_PERDIDO;p" + partition + ";esperado=" + (this.hb + 1) + ";recebido=" + hb);
        }
        if (state == State.SUSPEITO) {
            log.accept("RETOMA;p" + partition + ";inc=" + inc + ";hb=" + hb);
        }
        this.inc = inc;
        this.hb = hb;
        this.lastNanos = System.nanoTime();            // relógio LOCAL e monótono (não depende do líder)
        this.state = State.ATIVO;
    }

    // thread de verificação: chamada a cada VERIFICACAO_MS
    public synchronized void check() {
        long elapsed = System.nanoTime() - lastNanos;
        if (state != State.SUSPEITO && elapsed > timeoutNanos) {
            state = State.SUSPEITO;                    // só assinala uma vez por período de silêncio
            log.accept("SUSPEITA;p" + partition + ";silencio_ms=" + elapsed / 1_000_000);
        }
    }

    public synchronized State state() {
        return state;
    }
}
