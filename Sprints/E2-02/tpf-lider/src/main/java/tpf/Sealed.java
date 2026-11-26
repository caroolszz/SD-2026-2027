package tpf;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

/**
 * Um chunk selado, tal como fica em disco.
 *
 * @param id         chunkId: SHA-256 (hexadecimal, minúsculas) do texto cifrado, incluindo a tag GCM
 * @param keyVersion versão da chave mestra usada (1 neste sprint)
 * @param salt       32 bytes aleatórios, entrada do HKDF
 * @param nonce      12 bytes aleatórios, IV do AES-GCM
 * @param ciphertext texto cifrado seguido da tag de 16 bytes (conteúdo de {@code <id>.bin})
 *
 * <p>Ficheiros em {@code selados/}:
 * <ul>
 *   <li>{@code <id>.bin} = {@code ciphertext}, byte a byte (por isso o nome é o SHA-256 do conteúdo)</li>
 *   <li>{@code <id>.hdr} = uma linha de texto {@code keyVersion;Base64(salt);Base64(nonce)}</li>
 * </ul>
 */
public record Sealed(String id, int keyVersion, byte[] salt, byte[] nonce, byte[] ciphertext) {

    public static final String EXT_BIN = ".bin";
    public static final String EXT_HDR = ".hdr";

    /** Conteúdo do ficheiro {@code .hdr}. */
    public String cabecalho() {
        Base64.Encoder b64 = Base64.getEncoder();
        return keyVersion + ";" + b64.encodeToString(salt) + ";" + b64.encodeToString(nonce);
    }

    /** Reconstrói um {@code Sealed} a partir do id, da linha do {@code .hdr} e do conteúdo do {@code .bin}. */
    public static Sealed deCabecalho(String id, String linhaHdr, byte[] ciphertext) {
        String[] p = linhaHdr.strip().split(";", -1);
        if (p.length != 3) throw new IllegalArgumentException("cabeçalho .hdr inválido: " + linhaHdr);
        Base64.Decoder b64 = Base64.getDecoder();
        return new Sealed(id, Integer.parseInt(p[0]), b64.decode(p[1]), b64.decode(p[2]), ciphertext);
    }

    /**
     * Grava {@code <id>.hdr} e depois {@code <id>.bin}. Cada ficheiro é escrito num temporário,
     * sincronizado com fsync e só depois renomeado (rename atómico), para nunca existir um
     * {@code .bin} meio escrito. O {@code .hdr} vai primeiro: um {@code .bin} sem {@code .hdr}
     * nunca existe; um {@code .hdr} órfão é ignorado.
     */
    public void gravar(Path pasta) throws IOException {
        Files.createDirectories(pasta);
        gravarAtomico(pasta.resolve(id + EXT_HDR), (cabecalho() + "\n").getBytes(StandardCharsets.US_ASCII));
        gravarAtomico(pasta.resolve(id + EXT_BIN), ciphertext);
    }

    /** Lê {@code <id>.bin} e {@code <id>.hdr} da pasta. Não verifica nada: isso é feito por {@code ChunkCrypto.open}. */
    public static Sealed ler(Path pasta, String id) throws IOException {
        byte[] ct = Files.readAllBytes(pasta.resolve(id + EXT_BIN));
        String hdr = Files.readString(pasta.resolve(id + EXT_HDR), StandardCharsets.US_ASCII);
        return deCabecalho(id, hdr, ct);
    }

    private static void gravarAtomico(Path destino, byte[] dados) throws IOException {
        Path tmp = destino.resolveSibling(destino.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(dados);
            while (b.hasRemaining()) ch.write(b);
            ch.force(true);
        }
        Files.move(tmp, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
