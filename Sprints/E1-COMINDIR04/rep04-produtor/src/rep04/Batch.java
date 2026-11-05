package rep04;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// NOVO na COMINDIR04. Mensagem LOTE: heartbeat + registos acumulados no intervalo Δ.
// Formato (várias linhas):
//   LOTE;<endereço>;<porto>;<inc>;<hb>;<ultimoSeq>;<n>
//   <seq>;<sensor>;<temperatura>;<instante>;<L>;<V>      (n linhas, formato dos ficheiros)
// É imutável: o líder cria-o fora da secção sincronizada e pode publicá-lo sem lock.
// Um lote com n = 0 é um heartbeat puro; continua a levar ultimoSeq (é isso que permite
// detetar a perda do último registo de uma partição pouco ativa).
public final class Batch {
    public final String address;            // onde pedir, por TCP, os registos em falta
    public final int port;
    public final long inc;                  // incarnação do líder (instante do arranque, em ms)
    public final long hb;                   // nº do heartbeat dentro da incarnação (1, 2, 3, ...)
    public final long lastSeq;              // ultimoSeq: último seq atribuído quando o lote foi fechado
    public final List<SensorRecord> records;   // registos do lote, por ordem de seq (pode ser vazia)

    public Batch(String address, int port, long inc, long hb, long lastSeq, List<SensorRecord> records) {
        this.address = address;
        this.port = port;
        this.inc = inc;
        this.hb = hb;
        this.lastSeq = lastSeq;
        this.records = Collections.unmodifiableList(new ArrayList<>(records));
    }

    // texto da mensagem: cabeçalho + uma linha por registo, separadas por '\n'
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append("LOTE;").append(address).append(';').append(port).append(';')
          .append(inc).append(';').append(hb).append(';').append(lastSeq).append(';').append(records.size());
        for (SensorRecord r : records) {
            sb.append('\n').append(r.toLine());
        }
        return sb.toString();
    }

    // tamanho da mensagem em bytes (é o que se escreve na coluna "bytes" de hb-p<k>.txt)
    public static int bytes(String encoded) {
        return encoded.getBytes(StandardCharsets.UTF_8).length;
    }

    // interpreta só a primeira linha (o produtor só precisa disto):
    // devolve {endereço, porto, inc, hb, ultimoSeq, n} como texto; lança IllegalArgumentException se mal formado
    public static String[] header(String msg) {
        String first = lines(msg)[0];
        String[] f = first.split(";");
        if (f.length != 7 || !f[0].equals("LOTE")) {
            throw new IllegalArgumentException("cabeçalho LOTE mal formado: " + first);
        }
        for (int i = 2; i <= 6; i++) {
            Long.parseLong(f[i]);                   // valida que porto, inc, hb, ultimoSeq e n são números
        }
        return new String[] { f[1], f[2], f[3], f[4], f[5], f[6] };
    }

    // interpreta a mensagem inteira; n é a dimensão dos vetores (N partições)
    // n <= 0: aceita a dimensão com que o vetor vier (a réplica não conhece N; guarda os carimbos sem os usar)
    // valida que o número de linhas de registo coincide com o n do cabeçalho
    public static Batch decode(String msg, int n) {
        String[] h = header(msg);
        String[] all = lines(msg);
        int count = Integer.parseInt(h[5]);
        if (count < 0 || all.length - 1 != count) {
            throw new IllegalArgumentException("LOTE com " + (all.length - 1) + " linhas de registo, cabeçalho diz " + count);
        }
        List<SensorRecord> records = new ArrayList<>(count);
        for (int i = 1; i < all.length; i++) {
            records.add(n > 0 ? SensorRecord.fromLine(all[i], n) : SensorRecord.fromLine(all[i]));
        }
        return new Batch(h[0], Integer.parseInt(h[1]), Long.parseLong(h[2]), Long.parseLong(h[3]),
                Long.parseLong(h[4]), records);
    }

    // intervalo de seq do lote, para os logs: "a..b" ou "-" se estiver vazio
    public String seqRange() {
        if (records.isEmpty()) return "-";
        return records.get(0).getSeq() + ".." + records.get(records.size() - 1).getSeq();
    }

    // linhas não vazias (aceita "\n" ou "\r\n": o daemon não altera o texto, mas assim é indiferente)
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
