package tpf;

/**
 * Parâmetros do sistema e nomes de ficheiros partilhados pelo líder.
 *
 * Os valores numéricos podem ser alterados no arranque com propriedades de sistema
 * {@code -Dtpf.*}, por exemplo:
 * <pre>
 *   mvn compile exec:java -Dtpf.selagemMs=2000 -Dtpf.chunkMaxBytes=16384
 * </pre>
 */
public final class Wire {

    private Wire() {}

    /** Tamanho (sem padding) a partir do qual o chunk aberto é selado. {@code -Dtpf.chunkMaxBytes} */
    public static final int CHUNK_MAX_BYTES = Integer.getInteger("tpf.chunkMaxBytes", 65536);

    /** O chunk serializado é completado com padding até um múltiplo deste valor. {@code -Dtpf.padBytes} */
    public static final int PAD_BYTES = Integer.getInteger("tpf.padBytes", 4096);

    /** Tempo máximo, desde o primeiro registo, até o chunk aberto ser selado. {@code -Dtpf.selagemMs} */
    public static final long SELAGEM_MS = Long.getLong("tpf.selagemMs", 5000L);

    /** Pasta do líder (master.key, registos.log, eventos-lider.txt, selados/). {@code -Dtpf.dir} */
    public static final String PASTA = System.getProperty("tpf.dir", ".");

    /** Tamanho do texto de cada registo sintético de /rajada. {@code -Dtpf.rajadaBytes} */
    public static final int RAJADA_TEXTO_BYTES = Integer.getInteger("tpf.rajadaBytes", 512);

    // Nomes dos ficheiros dentro da pasta do líder
    public static final String FICHEIRO_MASTER_KEY = "master.key";
    public static final String FICHEIRO_REGISTOS = "registos.log";
    public static final String FICHEIRO_EVENTOS = "eventos-lider.txt";
    public static final String PASTA_SELADOS = "selados";
}
