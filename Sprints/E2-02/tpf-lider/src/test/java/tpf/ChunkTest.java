package tpf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class ChunkTest {

    private static Registo reg(long lsn, String texto) {
        return new Registo(lsn, Instant.parse("2026-11-23T20:15:30.123Z"), "rodrigo", "arquivo", texto);
    }

    @Test
    void serializarEDesserializarReconstroiOsRegistos() {
        List<Registo> regs = List.of(reg(1, "olá"), reg(2, "acentuação e emoji 📦"), reg(3, ""));
        byte[] dados = Chunk.serializar(regs, 4096);
        assertEquals(regs, Chunk.desserializar(dados));
    }

    @Test
    void paddingAteMultiploDePadBytes() {
        byte[] dados = Chunk.serializar(List.of(reg(1, "x")), 4096);
        assertEquals(4096, dados.length);
        for (int i = 100; i < dados.length; i++) assertEquals(0, dados[i]);
    }

    @Test
    void semPaddingQuandoJaEMultiplo() {
        // cabeçalho 9 + registo 24 + autor 7 + canal 7 + texto = 64  →  texto de 17 bytes
        Registo r = reg(1, "x".repeat(17));
        assertEquals(64, Chunk.CABECALHO_BYTES + Chunk.tamanhoRegisto(r));
        assertEquals(64, Chunk.serializar(List.of(r), 64).length);
        assertEquals(128, Chunk.serializar(List.of(reg(1, "x".repeat(18))), 64).length);
    }

    @Test
    void tamanhoContadoCoincideComOSerializado() {
        Chunk c = new Chunk();
        for (int i = 1; i <= 50; i++) c.adicionar(reg(i, "texto " + i + " ç"));
        assertEquals(c.tamanho(), Chunk.serializar(c.registos(), 1).length);
    }

    @Test
    void depoisDeSeladoEImutavel() {
        Chunk c = new Chunk();
        c.adicionar(reg(1, "a"));
        byte[] a = c.selar(4096);
        assertTrue(c.selado());
        assertThrows(IllegalStateException.class, () -> c.adicionar(reg(2, "b")));
        a[0] = 0;   // alterar a cópia devolvida não altera o chunk
        assertEquals('T', c.selar(4096)[0]);
    }

    @Test
    void dadosTruncadosOuInvalidosSaoRejeitados() {
        byte[] dados = Chunk.serializar(List.of(reg(1, "abcdef")), 1);
        assertThrows(IllegalArgumentException.class,
                () -> Chunk.desserializar(java.util.Arrays.copyOf(dados, dados.length - 2)));
        dados[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> Chunk.desserializar(dados));
    }
}
