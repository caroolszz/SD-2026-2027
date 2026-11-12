package raft04;

import java.util.function.Consumer;

// Detetor de falhas de UMA partição, no produtor e no processador.
// ALTERADO na COMINDIR05: a liderança identifica-se pelo TERMO (e pelo nó líder), não pela incarnação.
//  - lote de termo inferior ao maior já visto: ignorado (vem de um líder obsoleto);
//  - termo superior: NOVO_LIDER (o grupo elegeu outro líder, ou o mesmo nó num termo novo);
//  - salto de hb dentro do mesmo termo: HB_PERDIDO.
// Duas threads mexem no estado: a de receção (onHeartbeat) e a de verificação (check),
// por isso todos os métodos são synchronized no próprio monitor.
// O estado é SUSPEITO, nunca "morto": aqui SUSPEITO quer dizer "partição sem líder conhecido".
public final class HeartbeatMonitor {
    public enum State { DESCONHECIDO, ATIVO, SUSPEITO }

    private final int partition;
    private final long timeoutNanos;        // TIMEOUT = periodMs × factor, em nanossegundos
    private final Consumer<String> log;

    private State state = State.DESCONHECIDO;
    private long lastNanos;   // instante local da última receção
    private long term = -1;   // maior termo observado (-1 = nenhum)
    private int leader = 0;   // líder desse termo
    private long hb = 0;      // último heartbeat observado nesse termo

    public HeartbeatMonitor(int partition, long periodMs, int factor, Consumer<String> log) {
        this.partition = partition;
        this.timeoutNanos = periodMs * factor * 1_000_000L;
        this.log = log;
        this.lastNanos = System.nanoTime();            // se nunca chegar um LOTE, também acaba por suspeitar
    }

    // thread de receção: chegou um LOTE da partição
    public synchronized void onHeartbeat(long term, int leader, long hb) {
        if (term < this.term) return;                  // líder obsoleto
        if (term == this.term && hb <= this.hb) return;   // duplicado ou atrasado
        if (term > this.term) {
            log.accept("NOVO_LIDER;p" + partition + ";termo=" + (this.term < 0 ? "nenhum" : String.valueOf(this.term))
                    + "->" + term + ";lider=n" + leader);
        } else if (hb != this.hb + 1) {
            log.accept("HB_PERDIDO;p" + partition + ";termo=" + term + ";esperado=" + (this.hb + 1) + ";recebido=" + hb);
        }
        if (state == State.SUSPEITO) {
            log.accept("RETOMA;p" + partition + ";termo=" + term + ";lider=n" + leader + ";hb=" + hb);
        }
        this.term = term;
        this.leader = leader;
        this.hb = hb;
        this.lastNanos = System.nanoTime();            // relógio LOCAL e monótono
        this.state = State.ATIVO;
    }

    // thread de verificação: chamada a cada VERIFICACAO_MS
    public synchronized void check() {
        long elapsed = System.nanoTime() - lastNanos;
        if (state != State.SUSPEITO && elapsed > timeoutNanos) {
            state = State.SUSPEITO;                    // só assinala uma vez por período de silêncio
            log.accept("SUSPEITA;p" + partition + ";silencio_ms=" + elapsed / 1_000_000
                    + ";ultimoTermo=" + (term < 0 ? "nenhum" : String.valueOf(term))
                    + ";lider=" + (leader == 0 ? "desconhecido" : "n" + leader));
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized long term() {
        return term;
    }
}
