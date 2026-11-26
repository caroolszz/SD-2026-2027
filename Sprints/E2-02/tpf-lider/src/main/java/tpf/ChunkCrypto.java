package tpf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifra e decifra chunks.
 *
 * <pre>
 *   salt  = 32 bytes aleatórios (por chunk)
 *   nonce = 12 bytes aleatórios (por chunk)
 *   DEK   = HKDF-SHA256(MK, salt, info = "tpf-chunk-v1")            32 bytes
 *             PRK = HMAC-SHA256(chave = salt, mensagem = MK)          (extração)
 *             DEK = HMAC-SHA256(chave = PRK, mensagem = info || 0x01) (expansão, 1 bloco)
 *   aad   = "tpf-v1" || keyVersion (int32 big-endian) || salt || nonce  (54 bytes)
 *   ct    = AES-256-GCM(DEK, nonce, aad, texto com padding)          inclui tag de 128 bits
 *   id    = hex(SHA-256(ct))
 * </pre>
 *
 * {@link #open} verifica, por esta ordem: (1) SHA-256 do texto cifrado contra o id,
 * (2) versão da chave, (3) autenticação GCM. Qualquer falha lança {@link GeneralSecurityException}.
 */
public final class ChunkCrypto {

    public static final int VERSAO_CHAVE = 1;
    public static final int SALT_BYTES = 32;
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BITS = 128;
    public static final int DEK_BYTES = 32;
    static final byte[] INFO = "tpf-chunk-v1".getBytes(StandardCharsets.US_ASCII);
    static final byte[] AAD_PREFIXO = "tpf-v1".getBytes(StandardCharsets.US_ASCII);

    private static final String HMAC = "HmacSHA256";
    private static final String AES_GCM = "AES/GCM/NoPadding";

    private final byte[] masterKey;
    private final int keyVersion;
    private final SecureRandom aleatorio = new SecureRandom();

    public ChunkCrypto(byte[] masterKey) {
        this(masterKey, VERSAO_CHAVE);
    }

    public ChunkCrypto(byte[] masterKey, int keyVersion) {
        if (masterKey == null || masterKey.length != MasterKey.TAMANHO) {
            throw new IllegalArgumentException("A master key tem de ter " + MasterKey.TAMANHO + " bytes.");
        }
        this.masterKey = masterKey.clone();
        this.keyVersion = keyVersion;
    }

    public int keyVersion() { return keyVersion; }

    /** Cifra o texto (já com padding) com salt e nonce novos e devolve o chunk selado. */
    public Sealed seal(byte[] texto) throws GeneralSecurityException {
        byte[] salt = aleatorios(SALT_BYTES);
        byte[] nonce = aleatorios(NONCE_BYTES);
        byte[] dek = derivarDek(masterKey, salt);
        try {
            Cipher c = Cipher.getInstance(AES_GCM);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad(keyVersion, salt, nonce));
            byte[] ct = c.doFinal(texto);   // texto cifrado || tag (16 bytes)
            return new Sealed(sha256Hex(ct), keyVersion, salt, nonce, ct);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** Verifica e decifra. Devolve o texto com padding (para {@link Chunk#desserializar}). */
    public byte[] open(Sealed s) throws GeneralSecurityException {
        // (1) Integridade/identidade: o nome tem de ser o SHA-256 do conteúdo
        byte[] calculado = sha256Hex(s.ciphertext()).getBytes(StandardCharsets.US_ASCII);
        byte[] esperado = String.valueOf(s.id()).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(calculado, esperado)) {
            throw new GeneralSecurityException("SHA-256 do texto cifrado não corresponde ao id " + s.id());
        }
        // (2) Versão da chave
        if (s.keyVersion() != keyVersion) {
            throw new GeneralSecurityException("Versão de chave " + s.keyVersion() + " não suportada (esperada "
                    + keyVersion + ")");
        }
        if (s.salt() == null || s.salt().length != SALT_BYTES || s.nonce() == null || s.nonce().length != NONCE_BYTES) {
            throw new GeneralSecurityException("salt ou nonce com tamanho inválido");
        }
        // (3) Autenticação GCM: falha (AEADBadTagException) se o texto, a tag, o aad
        //     (keyVersion, salt, nonce) ou a chave não forem exatamente os da cifra
        byte[] dek = derivarDek(masterKey, s.salt());
        try {
            Cipher c = Cipher.getInstance(AES_GCM);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, s.nonce()));
            c.updateAAD(aad(s.keyVersion(), s.salt(), s.nonce()));
            return c.doFinal(s.ciphertext());
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    // ------------------------------------------------------------------
    // Primitivas (package-private para os testes)
    // ------------------------------------------------------------------

    /** DEK = HKDF-SHA256(MK, salt, "tpf-chunk-v1"), 32 bytes. */
    static byte[] derivarDek(byte[] mk, byte[] salt) throws GeneralSecurityException {
        return hkdfSha256(mk, salt, INFO);
    }

    /**
     * HKDF-SHA256 (RFC 5869) com um único bloco de expansão: devolve os primeiros 32 bytes do OKM.
     * Extração: PRK = HMAC(salt, ikm). Expansão: T(1) = HMAC(PRK, info || 0x01).
     */
    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(salt, HMAC));
        byte[] prk = mac.doFinal(ikm);
        try {
            mac.init(new SecretKeySpec(prk, HMAC));
            mac.update(info);
            mac.update((byte) 0x01);
            return mac.doFinal();   // 32 bytes = DEK_BYTES
        } finally {
            Arrays.fill(prk, (byte) 0);
        }
    }

    /** aad = "tpf-v1" || keyVersion (4 bytes) || salt || nonce. */
    static byte[] aad(int keyVersion, byte[] salt, byte[] nonce) {
        return ByteBuffer.allocate(AAD_PREFIXO.length + 4 + salt.length + nonce.length)
                .put(AAD_PREFIXO).putInt(keyVersion).put(salt).put(nonce)
                .array();
    }

    public static String sha256Hex(byte[] dados) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dados));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);   // obrigatório em qualquer JVM
        }
    }

    private byte[] aleatorios(int n) {
        byte[] b = new byte[n];
        aleatorio.nextBytes(b);
        return b;
    }
}
