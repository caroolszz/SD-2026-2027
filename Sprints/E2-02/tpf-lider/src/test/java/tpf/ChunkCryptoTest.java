package tpf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.AEADBadTagException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Testes de ChunkCrypto pedidos no objetivo 5 do sprint. */
class ChunkCryptoTest {

    private static final HexFormat HEX = HexFormat.of();

    private byte[] mk;
    private ChunkCrypto crypto;
    private byte[] texto;

    @BeforeEach
    void preparar() {
        mk = aleatorios(32);
        crypto = new ChunkCrypto(mk);
        texto = new byte[8192];   // como um chunk com padding: 2 × 4096
        byte[] util = "registo secreto: o tesouro está no ponto X".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(util, 0, texto, 0, util.length);
    }

    // ---------- cifrar e decifrar ----------

    @Test
    void cifrarEDecifrarDevolveOTextoOriginal() throws Exception {
        Sealed s = crypto.seal(texto);
        assertArrayEquals(texto, crypto.open(s));
    }

    @Test
    void textoCifradoTemOTamanhoDoTextoMaisTag() throws Exception {
        Sealed s = crypto.seal(texto);
        assertEquals(texto.length + 16, s.ciphertext().length);   // tag GCM de 128 bits
        assertEquals(32, s.salt().length);
        assertEquals(12, s.nonce().length);
        assertEquals(1, s.keyVersion());
    }

    @Test
    void idEOSha256DoTextoCifrado() throws Exception {
        Sealed s = crypto.seal(texto);
        assertEquals(ChunkCrypto.sha256Hex(s.ciphertext()), s.id());
        assertTrue(s.id().matches("[0-9a-f]{64}"));
    }

    @Test
    void cadaChunkTemSaltENonceNovos() throws Exception {
        Sealed a = crypto.seal(texto), b = crypto.seal(texto);
        assertFalse(java.util.Arrays.equals(a.salt(), b.salt()));
        assertFalse(java.util.Arrays.equals(a.nonce(), b.nonce()));
        assertFalse(a.id().equals(b.id()));   // mesmo texto, chunks (e ids) diferentes
    }

    @Test
    void textoCifradoNaoContemOTextoEmClaro() throws Exception {
        Sealed s = crypto.seal(texto);
        assertFalse(VerificarSelados.contem(s.ciphertext(), "tesouro".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void cabecalhoHdrIdaEVolta() throws Exception {
        Sealed s = crypto.seal(texto);
        Sealed lido = Sealed.deCabecalho(s.id(), s.cabecalho(), s.ciphertext());
        assertArrayEquals(texto, crypto.open(lido));
    }

    // ---------- byte alterado no texto cifrado ----------

    @Test
    void byteAlteradoNoTextoCifradoFalhaNaVerificacaoDoHash() throws Exception {
        Sealed s = crypto.seal(texto);
        byte[] ct = s.ciphertext().clone();
        ct[100] ^= 0x01;
        Sealed adulterado = new Sealed(s.id(), s.keyVersion(), s.salt(), s.nonce(), ct);
        GeneralSecurityException e = assertThrows(GeneralSecurityException.class, () -> crypto.open(adulterado));
        assertTrue(e.getMessage().contains("SHA-256"));
    }

    @Test
    void byteAlteradoComIdRecalculadoFalhaNaAutenticacaoGcm() throws Exception {
        // Um atacante que também troque o nome do ficheiro passa a verificação (1), mas não a GCM (3)
        Sealed s = crypto.seal(texto);
        byte[] ct = s.ciphertext().clone();
        ct[100] ^= 0x01;
        Sealed adulterado = new Sealed(ChunkCrypto.sha256Hex(ct), s.keyVersion(), s.salt(), s.nonce(), ct);
        assertThrows(AEADBadTagException.class, () -> crypto.open(adulterado));
    }

    @Test
    void tagAlteradaFalha() throws Exception {
        Sealed s = crypto.seal(texto);
        byte[] ct = s.ciphertext().clone();
        ct[ct.length - 1] ^= (byte) 0x80;   // último byte = tag
        Sealed adulterado = new Sealed(ChunkCrypto.sha256Hex(ct), s.keyVersion(), s.salt(), s.nonce(), ct);
        assertThrows(AEADBadTagException.class, () -> crypto.open(adulterado));
    }

    @Test
    void idDeOutroChunkFalha() throws Exception {
        Sealed a = crypto.seal(texto), b = crypto.seal(texto);
        Sealed trocado = new Sealed(b.id(), a.keyVersion(), a.salt(), a.nonce(), a.ciphertext());
        assertThrows(GeneralSecurityException.class, () -> crypto.open(trocado));
    }

    // ---------- salt / nonce / keyVersion diferentes ----------

    @Test
    void saltDiferenteFalha() throws Exception {
        Sealed s = crypto.seal(texto);
        byte[] salt = s.salt().clone();
        salt[0] ^= 0x01;   // outra DEK e outro aad
        Sealed outro = new Sealed(s.id(), s.keyVersion(), salt, s.nonce(), s.ciphertext());
        assertThrows(AEADBadTagException.class, () -> crypto.open(outro));
    }

    @Test
    void nonceDiferenteFalha() throws Exception {
        Sealed s = crypto.seal(texto);
        byte[] nonce = s.nonce().clone();
        nonce[11] ^= 0x01;
        Sealed outro = new Sealed(s.id(), s.keyVersion(), s.salt(), nonce, s.ciphertext());
        assertThrows(AEADBadTagException.class, () -> crypto.open(outro));
    }

    @Test
    void keyVersionDiferenteFalhaNaVerificacaoDaVersao() throws Exception {
        Sealed s = crypto.seal(texto);
        Sealed outro = new Sealed(s.id(), 2, s.salt(), s.nonce(), s.ciphertext());
        GeneralSecurityException e = assertThrows(GeneralSecurityException.class, () -> crypto.open(outro));
        assertTrue(e.getMessage().contains("Versão de chave"));
    }

    @Test
    void keyVersionFazParteDoAad() throws Exception {
        // Mesmo que quem abre aceite a versão 2, a tag foi calculada com aad de versão 1
        Sealed s = crypto.seal(texto);
        ChunkCrypto v2 = new ChunkCrypto(mk, 2);
        Sealed outro = new Sealed(s.id(), 2, s.salt(), s.nonce(), s.ciphertext());
        assertThrows(AEADBadTagException.class, () -> v2.open(outro));
    }

    // ---------- master.key diferente ----------

    @Test
    void masterKeyDiferenteFalha() throws Exception {
        Sealed s = crypto.seal(texto);
        ChunkCrypto outraChave = new ChunkCrypto(aleatorios(32));
        assertThrows(AEADBadTagException.class, () -> outraChave.open(s));
    }

    @Test
    void masterKeyComTamanhoErradoERecusada() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkCrypto(new byte[31]));
    }

    // ---------- primitivas ----------

    @Test
    void hkdfCorrespondeAoVetorDeTesteDoRfc5869() throws Exception {
        // RFC 5869, Test Case 1 (os primeiros 32 bytes do OKM = um único bloco)
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");
        byte[] okm = ChunkCrypto.hkdfSha256(ikm, salt, info);
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf", HEX.formatHex(okm));
    }

    @Test
    void aadTemOFormatoPedido() {
        byte[] salt = new byte[32], nonce = new byte[12];
        java.util.Arrays.fill(salt, (byte) 0xAA);
        java.util.Arrays.fill(nonce, (byte) 0xBB);
        byte[] aad = ChunkCrypto.aad(1, salt, nonce);
        assertEquals(6 + 4 + 32 + 12, aad.length);
        assertEquals("tpf-v1", new String(aad, 0, 6, StandardCharsets.US_ASCII));
        assertArrayEquals(new byte[]{0, 0, 0, 1}, java.util.Arrays.copyOfRange(aad, 6, 10));
        assertEquals((byte) 0xAA, aad[10]);
        assertEquals((byte) 0xBB, aad[42]);
    }

    private static byte[] aleatorios(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }
}
