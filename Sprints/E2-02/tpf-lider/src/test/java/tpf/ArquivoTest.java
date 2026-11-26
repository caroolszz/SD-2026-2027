package tpf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Critérios de aceitação do sprint, sem Discord: o LeaderBot só chama Arquivo.registar(). */
class ArquivoTest {

    private static final int MAX = 65536, PAD = 4096;

    @TempDir
    Path pasta;

    private Arquivo abrir(long selagemMs) throws Exception {
        return new Arquivo(pasta, MAX, PAD, selagemMs);
    }

    private List<String> linhas(String ficheiro) throws Exception {
        return Files.readAllLines(pasta.resolve(ficheiro), StandardCharsets.UTF_8);
    }

    private Path selados() {
        return pasta.resolve(Wire.PASTA_SELADOS);
    }

    // ---------- master.key ----------

    @Test
    void primeiroArranqueGeraMasterKeyDe32Bytes() throws Exception {
        try (Arquivo a = abrir(5000)) {
            assertEquals(32, Files.size(pasta.resolve(Wire.FICHEIRO_MASTER_KEY)));
        }
        byte[] antes = Files.readAllBytes(pasta.resolve(Wire.FICHEIRO_MASTER_KEY));
        try (Arquivo a = abrir(5000)) {   // 2.º arranque reutiliza a mesma chave
            assertArrayEquals(antes, Files.readAllBytes(pasta.resolve(Wire.FICHEIRO_MASTER_KEY)));
        }
    }

    @Test
    void masterKeyComTamanhoErradoRecusaOArranque() throws Exception {
        Files.write(pasta.resolve(Wire.FICHEIRO_MASTER_KEY), new byte[31]);
        assertThrows(IllegalStateException.class, () -> abrir(5000));
    }

    // ---------- registo durável ----------

    @Test
    void registarDevolveLsnELinhaJaEstaNoLog() throws Exception {
        try (Arquivo a = abrir(60_000)) {
            Registo r1 = a.registar("ana", "arquivo", "primeiro");
            Registo r2 = a.registar("rui", "arquivo", "segundo; com ponto e vírgula\ne linha nova");
            assertEquals(1, r1.lsn());
            assertEquals(2, r2.lsn());
            // logo depois de registar() voltar, sem fechar nada, a linha já está no ficheiro
            List<String> l = linhas(Wire.FICHEIRO_REGISTOS);
            assertEquals(2, l.size());
            assertEquals(r1, Registo.deLinha(l.get(0)));
            assertEquals(r2, Registo.deLinha(l.get(1)));
            assertTrue(l.get(0).startsWith("1;"));
            assertTrue(l.get(0).endsWith(";ana;arquivo;cHJpbWVpcm8="));   // Base64("primeiro")
        }
    }

    @Test
    void depoisDeReiniciarOsLsnContinuamSemRepeticoesNemSaltos() throws Exception {
        try (Arquivo a = abrir(60_000)) {
            for (int i = 0; i < 7; i++) a.registar("ana", "arquivo", "r" + i);
        }
        try (Arquivo a = abrir(60_000)) {
            assertEquals(7, a.ultimoLsn());
            assertEquals(8, a.registar("ana", "arquivo", "depois").lsn());
        }
        List<String> l = linhas(Wire.FICHEIRO_REGISTOS);
        for (int i = 0; i < l.size(); i++) assertEquals(i + 1, Registo.deLinha(l.get(i)).lsn());
    }

    @Test
    void ultimaLinhaIncompletaECortadaNoArranque() throws Exception {
        try (Arquivo a = abrir(60_000)) {
            a.registar("ana", "arquivo", "ok");
        }
        Files.writeString(pasta.resolve(Wire.FICHEIRO_REGISTOS), "2;2026-11-23T10:00:00Z;an",
                StandardOpenOption.APPEND);   // queda a meio de uma escrita
        try (Arquivo a = abrir(60_000)) {
            assertEquals(1, a.ultimoLsn());
            assertEquals(2, a.registar("ana", "arquivo", "seguinte").lsn());
        }
        assertEquals(2, linhas(Wire.FICHEIRO_REGISTOS).size());
    }

    @Test
    void logComSaltoDeLsnRecusaOArranque() throws Exception {
        Files.writeString(pasta.resolve(Wire.FICHEIRO_REGISTOS),
                "1;2026-11-23T10:00:00Z;a;arquivo;eA==\n3;2026-11-23T10:00:01Z;a;arquivo;eA==\n");
        assertThrows(IllegalStateException.class, () -> abrir(5000));
    }

    @Test
    void eventosRegistoESeladoAparecemNoFicheiro() throws Exception {
        try (Arquivo a = abrir(60_000)) {
            a.registar("ana", "arquivo", "texto que não pode aparecer nos eventos");
        }   // close() sela o chunk aberto
        List<String> ev = linhas(Wire.FICHEIRO_EVENTOS);
        assertTrue(ev.stream().anyMatch(l -> l.matches("[^;]+;REGISTO;lsn=1 autor=ana canal=arquivo bytes=\\d+")));
        assertTrue(ev.stream().anyMatch(l -> l.matches("[^;]+;SELADO;chunkId=[0-9a-f]{64} lsn=1-1 .*")));
        assertTrue(ev.stream().noneMatch(l -> l.contains("não pode aparecer")));
        for (String l : ev) assertEquals(3, l.split(";", -1).length, l);   // instante;evento;detalhe
    }

    // ---------- selagem ----------

    @Test
    void umUnicoRegistoESeladoPorTempo() throws Exception {
        try (Arquivo a = abrir(300)) {
            a.registar("ana", "arquivo", "sozinho");
            assertTrue(a.chunksSelados().isEmpty());
            long limite = System.currentTimeMillis() + 5000;
            while (a.chunksSelados().isEmpty() && System.currentTimeMillis() < limite) Thread.sleep(20);
            assertEquals(1, a.chunksSelados().size());
            String id = a.chunksSelados().get(0);
            assertEquals(1, a.abrirChunk(id).size());
            assertTrue(linhas(Wire.FICHEIRO_EVENTOS).stream().anyMatch(l -> l.contains("SELADO") && l.contains("motivo=tempo")));
        }
    }

    @Test
    void rajadaDe500ProduzVariosChunksSeladosPorTamanho() throws Exception {
        List<Registo> enviados = new ArrayList<>();
        try (Arquivo a = abrir(60_000)) {
            for (int i = 1; i <= 500; i++) {
                enviados.add(a.registar("ana", "arquivo", LeaderBot.textoSintetico("rajada-teste", i, 500)));
            }
            List<String> ids = a.chunksSelados();
            assertTrue(ids.size() >= 3, "chunks selados por tamanho: " + ids.size());

            List<Registo> abertos = new ArrayList<>();
            for (String id : ids) {
                byte[] bin = Files.readAllBytes(selados().resolve(id + ".bin"));
                // nome = SHA-256 do conteúdo
                assertEquals(id, ChunkCrypto.sha256Hex(bin));
                // texto com padding múltiplo de PAD_BYTES (o .bin tem mais 16 bytes de tag)
                assertEquals(0, (bin.length - 16) % PAD);
                // sem texto em claro (equivalente ao grep)
                assertFalse(VerificarSelados.contem(bin, "rajada-teste".getBytes(StandardCharsets.UTF_8)));
                assertFalse(VerificarSelados.contem(bin, "lorem".getBytes(StandardCharsets.UTF_8)));
                abertos.addAll(a.abrirChunk(id));
            }
            // chunks por tamanho: todos os chunks selados ultrapassaram o limite
            assertTrue(linhas(Wire.FICHEIRO_EVENTOS).stream()
                    .filter(l -> l.contains(";SELADO;")).allMatch(l -> l.contains("motivo=tamanho")));

            abertos.sort((x, y) -> Long.compare(x.lsn(), y.lsn()));
            assertEquals(enviados.subList(0, abertos.size()), abertos);   // exatamente os mesmos registos
        }
    }

    @Test
    void cadaChunkContemExatamenteOIntervaloDoEventoSelado() throws Exception {
        try (Arquivo a = abrir(60_000)) {
            for (int i = 1; i <= 300; i++) a.registar("ana", "arquivo", LeaderBot.textoSintetico("x", i, 300));
        }
        List<String> ev = linhas(Wire.FICHEIRO_EVENTOS);
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(";SELADO;chunkId=([0-9a-f]{64}) lsn=(\\d+)-(\\d+) ");
        List<Registo> log = linhas(Wire.FICHEIRO_REGISTOS).stream().map(Registo::deLinha).toList();
        ChunkCrypto crypto = new ChunkCrypto(Files.readAllBytes(pasta.resolve(Wire.FICHEIRO_MASTER_KEY)));
        long proximo = 1;
        for (String l : ev) {
            java.util.regex.Matcher m = p.matcher(l);
            if (!m.find()) continue;
            int de = Integer.parseInt(m.group(2)), ate = Integer.parseInt(m.group(3));
            assertEquals(proximo, de);   // chunks consecutivos, sem buracos
            List<Registo> regs = Arquivo.abrirChunk(crypto, selados(), m.group(1));
            assertEquals(log.subList(de - 1, ate), regs);
            proximo = ate + 1;
        }
        assertEquals(301, proximo);
    }

    @Test
    void registoQueExcedeOLimiteSelaOChunkDeImediato() throws Exception {
        try (Arquivo a = new Arquivo(pasta, 1000, 256, 60_000)) {
            a.registar("ana", "arquivo", "x".repeat(500));
            assertTrue(a.chunksSelados().isEmpty());
            a.registar("ana", "arquivo", "x".repeat(500));   // este passa dos 1000 bytes
            assertEquals(1, a.chunksSelados().size());
            assertEquals(2, a.abrirChunk(a.chunksSelados().get(0)).size());
            a.registar("ana", "arquivo", "seguinte vai para um chunk novo");
            assertEquals(1, a.chunksSelados().size());
        }
    }

    @Test
    void registosNaoSeladosAntesDeUmaQuedaSaoSeladosDepoisDoArranque() throws Exception {
        Arquivo a = abrir(60_000);
        a.registar("ana", "arquivo", "um");
        a.registar("ana", "arquivo", "dois");
        a.abortar();   // queda: o chunk aberto (em memória) perde-se, o registos.log não
        try (var ficheiros = Files.list(selados())) {
            assertTrue(ficheiros.findAny().isEmpty());
        }

        try (Arquivo b = abrir(60_000)) {
            assertEquals(3, b.registar("ana", "arquivo", "três").lsn());
        }
        List<Registo> todos = new ArrayList<>();
        try (Arquivo c = abrir(60_000)) {
            for (String id : c.chunksSelados()) todos.addAll(c.abrirChunk(id));
        }
        assertEquals(List.of(1L, 2L, 3L), todos.stream().map(Registo::lsn).sorted().toList());
    }

    @Test
    void chunkAdulteradoNaoAbre() throws Exception {
        try (Arquivo a = abrir(60_000)) {
            a.registar("ana", "arquivo", "alvo");
        }
        try (Arquivo a = abrir(60_000)) {
            String id = a.chunksSelados().get(0);
            Path bin = selados().resolve(id + ".bin");
            byte[] b = Files.readAllBytes(bin);
            b[0] ^= 1;
            Files.write(bin, b);
            assertThrows(java.security.GeneralSecurityException.class, () -> a.abrirChunk(id));
        }
    }
}
