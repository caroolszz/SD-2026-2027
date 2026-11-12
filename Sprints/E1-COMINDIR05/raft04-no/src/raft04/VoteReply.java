package raft04;

// NOVO na COMINDIR05. Mensagem VOTO, resposta de um eleitor a um PEDIDO_VOTO.
// Formato: VOTO;<termo>;<eleitor>;<candidato>;<S|N>
// É publicada no tópico de eleição (todos a recebem), mas só o candidato indicado a trata.
// O eleitor responde SEMPRE, mesmo quando recusa: o termo da resposta permite ao candidato
// descobrir que está atrasado (termo superior) e demitir-se.
public final class VoteReply {
    public final long term;
    public final int voter;
    public final int candidate;
    public final boolean granted;

    public VoteReply(long term, int voter, int candidate, boolean granted) {
        this.term = term;
        this.voter = voter;
        this.candidate = candidate;
        this.granted = granted;
    }

    public String encode() {
        return "VOTO;" + term + ";" + voter + ";" + candidate + ";" + (granted ? "S" : "N");
    }

    public static VoteReply decode(String msg) {
        String[] f = msg.trim().split(";");
        if (f.length != 5 || !f[0].equals("VOTO") || !(f[4].equals("S") || f[4].equals("N"))) {
            throw new IllegalArgumentException("VOTO mal formado: " + msg);
        }
        return new VoteReply(Long.parseLong(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), f[4].equals("S"));
    }
}
