package raft04;

// NOVO na COMINDIR05. Mensagem PEDIDO_VOTO, publicada por um candidato no tópico raft04-eleicao-p<k>.
// Formato: PEDIDO_VOTO;<termo>;<candidato>;<ultimoSeq>;<ultimoTermo>
// ultimoSeq e ultimoTermo descrevem o último registo do candidato: é com eles que cada eleitor
// aplica a restrição de voto (só vota em quem tem o registo pelo menos tão atualizado como o seu).
public final class VoteRequest {
    public final long term;
    public final int candidate;
    public final long lastSeq;
    public final long lastTerm;

    public VoteRequest(long term, int candidate, long lastSeq, long lastTerm) {
        this.term = term;
        this.candidate = candidate;
        this.lastSeq = lastSeq;
        this.lastTerm = lastTerm;
    }

    public String encode() {
        return "PEDIDO_VOTO;" + term + ";" + candidate + ";" + lastSeq + ";" + lastTerm;
    }

    // lança IllegalArgumentException (ou NumberFormatException) se a mensagem estiver mal formada
    public static VoteRequest decode(String msg) {
        String[] f = msg.trim().split(";");
        if (f.length != 5 || !f[0].equals("PEDIDO_VOTO")) {
            throw new IllegalArgumentException("PEDIDO_VOTO mal formado: " + msg);
        }
        return new VoteRequest(Long.parseLong(f[1]), Integer.parseInt(f[2]), Long.parseLong(f[3]), Long.parseLong(f[4]));
    }
}
