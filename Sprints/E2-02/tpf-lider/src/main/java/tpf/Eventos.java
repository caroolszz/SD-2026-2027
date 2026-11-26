package tpf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O ficheiro {@code eventos-lider.txt}: uma linha {@code instante;evento;detalhe} por evento.
 *
 * <p>É um registo de diagnóstico, não de estado: não leva {@code fsync} e uma falha a escrever
 * só gera um aviso na consola. O detalhe nunca contém o texto dos registos.
 */
final class Eventos {

    private static final Logger log = LoggerFactory.getLogger(Eventos.class);

    private final Path ficheiro;

    Eventos(Path ficheiro) {
        this.ficheiro = ficheiro;
    }

    synchronized void registar(String evento, String detalhe) {
        // ';' separa os três campos, por isso não pode aparecer no detalhe
        String limpo = detalhe.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
        String linha = Instant.now().truncatedTo(ChronoUnit.MILLIS) + ";" + evento + ";" + limpo + "\n";
        try {
            Files.writeString(ficheiro, linha, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Não foi possível escrever em {}: {}", ficheiro, e.getMessage());
        }
        log.info("[{}] {}", evento, limpo);
    }
}
