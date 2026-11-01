package rep03;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

// Ficheiro de registos (lider-p<k>.txt ou replica-<id>-p<k>.txt), uma linha por registo
// no formato seq;sensor;temperatura;instante;L;V.
// Todos os métodos são synchronized no próprio objeto: o append de uma thread não se mistura
// com a leitura de outra (ex.: o líder a escrever enquanto serve uma recuperação TCP).
public class RecordFile {
    private final Path path;

    public RecordFile(String fileName) {
        this.path = Paths.get(fileName);    // relativo à pasta onde o processo foi arrancado
    }

    // acrescenta uma linha ao fim do ficheiro (cria-o se não existir)
    public synchronized void append(SensorRecord r) throws IOException {
        Files.writeString(path, r.toLine() + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // número de sequência do último registo guardado (0 se o ficheiro não existir)
    // usado pela réplica no arranque para saber a partir de que seq continua
    public synchronized long lastSeq() throws IOException {
        SensorRecord r = last();
        return r == null ? 0 : r.getSeq();
    }

    // NOVO na COMINDIR03.
    // último registo guardado, com os respetivos carimbos (null se o ficheiro não existir ou estiver vazio)
    // O líder usa-o no arranque: como L e V só mudam quando há um evento e cada evento é escrito
    // aqui, a última linha contém o estado completo do líder (seq, L e V).
    public synchronized SensorRecord last() throws IOException {
        if (!Files.exists(path)) {
            return null;
        }
        SensorRecord last = null;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                last = SensorRecord.fromLine(line);     // fica com a última linha não vazia
            }
        }
        return last;
    }

    // registos com from <= seq <= to (usado pelo líder para responder a um PEDIDO de recuperação)
    // como as linhas já trazem L e V, a recuperação transfere os carimbos sem alterações ao protocolo
    public synchronized List<SensorRecord> range(long from, long to) throws IOException {
        List<SensorRecord> out = new ArrayList<>();
        if (!Files.exists(path)) {
            return out;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            SensorRecord r = SensorRecord.fromLine(line);
            if (r.getSeq() >= from && r.getSeq() <= to) {
                out.add(r);
            }
        }
        return out;
    }
}
