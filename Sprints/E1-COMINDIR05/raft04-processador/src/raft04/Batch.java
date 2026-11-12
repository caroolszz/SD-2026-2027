package raft04;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Mensagem LOTE: heartbeat + registos acumulados no intervalo Δ.
// ALTERADO na COMINDIR05: o cabeçalho identifica a liderança pelo TERMO e pelo nó LÍDER
// (em vez da incarnação "inc") e acrescenta ultimoTermo, o termo do registo ultimoSeq.
// Formato (várias linhas):
//   LOTE;<termo>;<lider>;<endereço>;<porto>;<hb>;<ultimoSeq>;<ultimoTermo>;<n>
//   <seq>;<termo>;<sensor>;<temperatura>;<instante>;<L>;<V>      (n linhas, formato dos ficheiros)
// É imutável: o líder cria-o fora da secção sincronizada e pode publicá-lo sem lock.
public final class Batch {
    public final long term;                 // termo do líder que publicou o lote
    public final int leader;                // identificador do líder (1..3)
    public final String address;            // onde pedir, por TCP, os registos em falta
    public final int port;
    public final long hb;                   // nº do heartbeat dentro deste termo (1, 2, 3, ...)
    public final long lastSeq;              // ultimoSeq: último seq do líder quando o lote foi fechado
    public final long lastTerm;             // ultimoTermo: termo do registo ultimoSeq (0 se não houver)
    public final List<SensorRecord> records;   // registos do lote, por ordem de seq (pode ser vazia)

    public Batch(long term, int leader, String address, int port, long hb, long lastSeq, long lastTerm,
                 List<SensorRecord> records) {
        this.term = term;
        this.leader = leader;
        this.address = address;
        this.port = port;
        this.hb = hb;
        this.lastSeq = lastSeq;
        this.lastTerm = lastTerm;
        this.records = Collections.unmodifiableList(new ArrayList<>(records));
    }

    // texto da mensagem: cabeçalho + uma linha por registo, separadas por '\n'
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append("LOTE;").append(term).append(';').append(leader).append(';').append(address).append(';')
          .append(port).append(';').append(hb).append(';').append(lastSeq).append(';').append(lastTerm)
          .append(';').append(records.size());
        for (SensorRecord r : records) {
            sb.append('\n').append(r.toLine());
        }
        return sb.toString();
    }

    // tamanho da mensagem em bytes (coluna "bytes" de hb-p<k>-n<i>.txt)
    public static int bytes(String encoded) {
        return encoded.getBytes(StandardCharsets.UTF_8).length;
    }

    // interpreta só a primeira linha (o produtor só precisa disto). Devolve, como texto,
    // {termo, lider, endereço, porto, hb, ultimoSeq, ultimoTermo, n}; lança IllegalArgumentException se mal formado
    public static String[] header(String msg) {
        String first = lines(msg)[0];
        String[] f = first.split(";");
        if (f.length != 9 || !f[0].equals("LOTE")) {
            throw new IllegalArgumentException("cabeçalho LOTE mal formado: " + first);
        }
        for (int i = 1; i <= 8; i++) {
            if (i != 3) Long.parseLong(f[i]);       // todos numéricos menos o endereço
        }
        return new String[] { f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8] };
    }

    // interpreta a mensagem inteira; n é a dimensão dos vetores (N partições)
    // n <= 0: aceita a dimensão com que o vetor vier
    // valida que o número de linhas de registo coincide com o n do cabeçalho
    public static Batch decode(String msg, int n) {
        String[] h = header(msg);
        String[] all = lines(msg);
        int count = Integer.parseInt(h[7]);
        if (count < 0 || all.length - 1 != count) {
            throw new IllegalArgumentException("LOTE com " + (all.length - 1) + " linhas de registo, cabeçalho diz " + count);
        }
        List<SensorRecord> records = new ArrayList<>(count);
        for (int i = 1; i < all.length; i++) {
            records.add(n > 0 ? SensorRecord.fromLine(all[i], n) : SensorRecord.fromLine(all[i]));
        }
        return new Batch(Long.parseLong(h[0]), Integer.parseInt(h[1]), h[2], Integer.parseInt(h[3]),
                Long.parseLong(h[4]), Long.parseLong(h[5]), Long.parseLong(h[6]), records);
    }

    // intervalo de seq do lote, para os logs: "a..b" ou "-" se estiver vazio
    public String seqRange() {
        if (records.isEmpty()) return "-";
        return records.get(0).getSeq() + ".." + records.get(records.size() - 1).getSeq();
    }

    // linhas não vazias (aceita "\n" ou "\r\n")
    private static String[] lines(String msg) {
        List<String> out = new ArrayList<>();
        for (String l : msg.split("\\r?\\n")) {
            if (!l.isBlank()) out.add(l.trim());
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("LOTE vazio");
        }
        return out.toArray(new String[0]);
    }
}
