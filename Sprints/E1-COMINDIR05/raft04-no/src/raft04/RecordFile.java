package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

// Ficheiro de registos de um nó (no-p<k>-n<i>.txt), uma linha por registo no formato
// seq;termo;sensor;temperatura;instante;L;V.
// Todos os métodos são synchronized no próprio objeto: o append de uma thread não se mistura
// com a leitura de outra (ex.: o nó a escrever enquanto o servidor TCP serve uma recuperação).
// O servidor TCP lê daqui SEM adquirir o monitor do nó (ver PartitionNode.serve).
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

    // todos os registos, pela ordem do ficheiro (lista vazia se o ficheiro não existir)
    public synchronized List<SensorRecord> all() throws IOException {
        List<SensorRecord> out = new ArrayList<>();
        if (!Files.exists(path)) return out;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) out.add(SensorRecord.fromLine(line));
        }
        return out;
    }

    // registos com from <= seq <= to (resposta a um PEDIDO de recuperação)
    public synchronized List<SensorRecord> range(long from, long to) throws IOException {
        List<SensorRecord> out = new ArrayList<>();
        for (SensorRecord r : all()) {
            if (r.getSeq() >= from && r.getSeq() <= to) out.add(r);
        }
        return out;
    }

    // NOVO na COMINDIR05: reescreve o ficheiro inteiro de forma ATÓMICA (truncagem).
    // Escreve primeiro um ficheiro temporário e depois substitui o original com ATOMIC_MOVE:
    // se o nó falhar a meio, fica ou o ficheiro antigo ou o novo, nunca um ficheiro meio escrito.
    public synchronized void rewrite(List<SensorRecord> records) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        StringBuilder sb = new StringBuilder();
        for (SensorRecord r : records) sb.append(r.toLine()).append(System.lineSeparator());
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
