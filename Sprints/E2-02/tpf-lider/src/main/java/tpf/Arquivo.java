package tpf;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caminho de escrita local do líder, independente do Discord:
 *
 * <pre>
 *   registar(autor, canal, texto)
 *     1. LSN seguinte
 *     2. linha em registos.log + fsync          ← a partir daqui o registo é durável
 *     3. evento REGISTO
 *     4. entra no chunk aberto
 *          - 1.º registo do chunk → agenda selagem daqui a SELAGEM_MS
 *          - tamanho >= CHUNK_MAX_BYTES → sela já
 *     5. devolve o Registo (com o LSN) → o bot responde
 *
 *   selar(motivo)
 *     serializar + padding → ChunkCrypto.seal → selados/&lt;id&gt;.hdr e .bin → evento SELADO
 * </pre>
 *
 * Todos os métodos que mexem no estado são {@code synchronized}: as threads da JDA
 * (/registar e mensagens), a thread da rajada e a thread do temporizador nunca se misturam.
 */
public final class Arquivo implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Arquivo.class);

    private final int chunkMaxBytes;
    private final int padBytes;
    private final long selagemMs;

    private final Path selados;
    private final RegistoLog registos;
    private final Eventos eventos;
    private final ChunkCrypto crypto;
    private final ScheduledExecutorService relogio;

    private long ultimoLsn;
    private Chunk aberto = new Chunk();
    private ScheduledFuture<?> temporizador;
    private boolean fechado;

    /** Abre a pasta do líder com os parâmetros de {@link Wire}. */
    public static Arquivo abrir(Path pasta) throws IOException {
        return new Arquivo(pasta, Wire.CHUNK_MAX_BYTES, Wire.PAD_BYTES, Wire.SELAGEM_MS);
    }

    /**
     * Arranque: master.key → registos.log → chunks já selados → recuperação do chunk aberto.
     *
     * @throws IllegalStateException se a master.key não tiver 32 bytes ou o registos.log estiver corrompido
     */
    public Arquivo(Path pasta, int chunkMaxBytes, int padBytes, long selagemMs) throws IOException {
        if (chunkMaxBytes < 1 || padBytes < 1 || selagemMs < 1) {
            throw new IllegalArgumentException("CHUNK_MAX_BYTES, PAD_BYTES e SELAGEM_MS têm de ser positivos");
        }
        this.chunkMaxBytes = chunkMaxBytes;
        this.padBytes = padBytes;
        this.selagemMs = selagemMs;

        Files.createDirectories(pasta);
        this.eventos = new Eventos(pasta.resolve(Wire.FICHEIRO_EVENTOS));

        // 1) Chave mestra: gera no 1.º arranque, recusa se tiver tamanho errado
        MasterKey.Carregada mk = MasterKey.carregarOuGerar(pasta.resolve(Wire.FICHEIRO_MASTER_KEY));
        this.crypto = new ChunkCrypto(mk.chave());

        // 2) registos.log: lê, valida a sequência de LSN e corta uma última linha incompleta
        this.registos = new RegistoLog(pasta.resolve(Wire.FICHEIRO_REGISTOS));
        if (registos.bytesCortados() > 0) {
            eventos.registar("LOG_TRUNCADO", "removidos " + registos.bytesCortados()
                    + " bytes de uma última linha incompleta (registo nunca confirmado)");
        }

        // 3) Chunks já selados: quais os LSN que já estão protegidos em selados/
        this.selados = pasta.resolve(Wire.PASTA_SELADOS);
        Files.createDirectories(selados);
        Set<Long> lsnSelados = lerLsnSelados();
        long maxSelado = lsnSelados.stream().mapToLong(Long::longValue).max().orElse(0);

        // Um registos.log apagado ou truncado à mão faria repetir LSN já usados em chunks: recusa.
        if (maxSelado > registos.ultimoLsn()) {
            registos.close();
            throw new IllegalStateException("Há chunks selados até ao LSN " + maxSelado + " mas registos.log só vai até "
                    + registos.ultimoLsn() + ". Arranque recusado para não repetir LSN.");
        }
        // Continua a numeração a partir do último LSN de registos.log
        this.ultimoLsn = registos.ultimoLsn();

        this.relogio = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "selagem");
            t.setDaemon(true);
            return t;
        });

        // 4) Registos que estavam no chunk aberto quando o líder parou (em memória, perdidos)
        //    voltam a entrar no chunk aberto a partir do registos.log
        int pendentes = 0;
        synchronized (this) {
            for (Registo r : registos.existentes()) {
                if (!lsnSelados.contains(r.lsn())) {
                    adicionarAoChunk(r);
                    pendentes++;
                }
            }
        }
        eventos.registar("ARRANQUE", "masterKey=" + (mk.gerada() ? "gerada" : "existente")
                + " ultimoLsn=" + ultimoLsn + " lsnSelados=" + lsnSelados.size()
                + " pendentes=" + pendentes + " chunkMaxBytes=" + chunkMaxBytes
                + " padBytes=" + padBytes + " selagemMs=" + selagemMs);
    }

    // ------------------------------------------------------------------
    // Escrita
    // ------------------------------------------------------------------

    /**
     * Ponto único de entrada de /registar, das mensagens em #arquivo e de /rajada.
     * Quando volta, a linha do registo já está em registos.log e no disco (fsync).
     */
    public synchronized Registo registar(String autor, String canal, String texto) throws IOException {
        if (fechado) throw new IllegalStateException("Arquivo fechado");
        Registo r = new Registo(ultimoLsn + 1, Instant.now(), autor, canal, texto);
        registos.acrescentar(r);   // write + force(true); em caso de erro o LSN não é consumido
        ultimoLsn = r.lsn();
        eventos.registar("REGISTO", "lsn=" + r.lsn() + " autor=" + r.autor() + " canal=" + r.canal()
                + " bytes=" + Chunk.tamanhoRegisto(r));
        adicionarAoChunk(r);
        return r;
    }

    public synchronized long ultimoLsn() {
        return ultimoLsn;
    }

    private void adicionarAoChunk(Registo r) {
        aberto.adicionar(r);
        if (aberto.numRegistos() == 1) {
            // 1.º registo do chunk: começa a contar SELAGEM_MS
            final Chunk alvo = aberto;
            temporizador = relogio.schedule(() -> selarPorTempo(alvo), selagemMs, TimeUnit.MILLISECONDS);
        }
        if (aberto.tamanho() >= chunkMaxBytes) {
            selar("tamanho");   // o registo que atinge/excede o limite sela o chunk de imediato
        }
    }

    /** Chamado pela thread "selagem". Só sela se o chunk ainda for o mesmo (pode já ter sido selado por tamanho). */
    private synchronized void selarPorTempo(Chunk alvo) {
        if (aberto == alvo && !alvo.vazio() && !fechado) {
            selar("tempo");
        }
    }

    private void selar(String motivo) {
        Chunk c = aberto;
        if (c.vazio()) return;
        if (temporizador != null) {
            temporizador.cancel(false);
            temporizador = null;
        }
        aberto = new Chunk();   // o registo seguinte abre um chunk novo

        try {
            byte[] texto = c.selar(padBytes);   // a partir daqui o chunk é imutável
            Sealed s = crypto.seal(texto);
            s.gravar(selados);
            eventos.registar("SELADO", "chunkId=" + s.id() + " lsn=" + c.primeiroLsn() + "-" + c.ultimoLsn()
                    + " registos=" + c.numRegistos() + " bytes=" + texto.length
                    + " bin=" + s.ciphertext().length + " motivo=" + motivo);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            // Os registos continuam em registos.log (fsync); no próximo arranque voltam ao chunk aberto.
            log.error("Falha a selar o chunk {}-{}", c.primeiroLsn(), c.ultimoLsn(), e);
            eventos.registar("ERRO_SELAGEM", "lsn=" + c.primeiroLsn() + "-" + c.ultimoLsn() + " " + e);
        }
    }

    // ------------------------------------------------------------------
    // Leitura dos chunks selados
    // ------------------------------------------------------------------

    /** Ids dos chunks em selados/ (só os que têm .bin e .hdr). */
    public List<String> chunksSelados() throws IOException {
        return listarSelados(selados);
    }

    /** Abre um chunk selado: lê, verifica (hash, versão, GCM), decifra e reconstrói os registos. */
    public List<Registo> abrirChunk(String id) throws IOException, GeneralSecurityException {
        return abrirChunk(crypto, selados, id);
    }

    static List<Registo> abrirChunk(ChunkCrypto crypto, Path selados, String id)
            throws IOException, GeneralSecurityException {
        return Chunk.desserializar(crypto.open(Sealed.ler(selados, id)));
    }

    static List<String> listarSelados(Path selados) throws IOException {
        List<String> ids = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(selados, "*" + Sealed.EXT_BIN)) {
            for (Path p : ds) {
                String nome = p.getFileName().toString();
                String id = nome.substring(0, nome.length() - Sealed.EXT_BIN.length());
                if (Files.exists(selados.resolve(id + Sealed.EXT_HDR))) ids.add(id);
            }
        }
        ids.sort(null);
        return ids;
    }

    /** No arranque abre todos os chunks selados e devolve os LSN que contêm. */
    private Set<Long> lerLsnSelados() throws IOException {
        try (DirectoryStream<Path> tmp = Files.newDirectoryStream(selados, "*.tmp")) {
            for (Path p : tmp) Files.deleteIfExists(p);   // restos de uma gravação interrompida
        }
        Set<Long> lsn = new HashSet<>();
        for (String id : listarSelados(selados)) {
            try {
                for (Registo r : abrirChunk(id)) lsn.add(r.lsn());
            } catch (GeneralSecurityException | RuntimeException e) {
                // Chunk inválido: os seus registos são tratados como não selados e voltam a ser selados
                eventos.registar("CHUNK_INVALIDO", "chunkId=" + id + " " + e.getMessage());
            }
        }
        return lsn;
    }

    // ------------------------------------------------------------------

    /** Paragem ordenada: sela o chunk aberto (se tiver registos) e fecha o registos.log. */
    @Override
    public synchronized void close() {
        if (fechado) return;
        selar("paragem");
        fechado = true;
        relogio.shutdownNow();
        try {
            registos.close();
        } catch (IOException e) {
            log.warn("Erro a fechar registos.log: {}", e.getMessage());
        }
        eventos.registar("PARAGEM", "ultimoLsn=" + ultimoLsn);
    }

    /** Só para testes: simula uma queda (sem selar o chunk aberto). */
    synchronized void abortar() throws IOException {
        fechado = true;
        relogio.shutdownNow();
        registos.close();
    }
}
