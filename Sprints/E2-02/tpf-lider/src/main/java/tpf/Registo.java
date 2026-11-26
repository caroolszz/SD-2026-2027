package tpf;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * Um registo do arquivo.
 *
 * @param lsn      Log Sequence Number: 1, 2, 3, ... sem repetições nem saltos
 * @param instante quando o líder o aceitou (precisão de milissegundos)
 * @param autor    nome do utilizador Discord
 * @param canal    nome do canal de origem (ex.: "arquivo")
 * @param texto    conteúdo, em claro (só neste objeto e em registos.log, em Base64)
 */
public record Registo(long lsn, Instant instante, String autor, String canal, String texto) {

    public Registo {
        if (lsn < 1) throw new IllegalArgumentException("LSN inválido: " + lsn);
        // Os milissegundos chegam e garantem que a ida e volta (log e chunk) é exata.
        instante = instante.truncatedTo(ChronoUnit.MILLIS);
        autor = limparCampo(autor);
        canal = limparCampo(canal);
        if (texto == null) texto = "";
    }

    /**
     * autor e canal vão em claro na linha de registos.log, separada por ';'.
     * Os nomes do Discord não têm ';' nem mudanças de linha, mas se tiverem são trocados por '_'
     * para não partir o formato.
     */
    static String limparCampo(String s) {
        if (s == null || s.isEmpty()) return "?";
        return s.replace(';', '_').replace('\n', '_').replace('\r', '_');
    }

    /** Linha de registos.log (sem '\n'): {@code lsn;instante;autor;canal;textoBase64}. */
    public String paraLinha() {
        String b64 = Base64.getEncoder().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
        return lsn + ";" + instante + ";" + autor + ";" + canal + ";" + b64;
    }

    /** Operação inversa de {@link #paraLinha()}. */
    public static Registo deLinha(String linha) {
        String[] p = linha.split(";", -1);
        if (p.length != 5) {
            throw new IllegalArgumentException("Linha de registo com " + p.length + " campos (esperados 5): " + linha);
        }
        String texto = new String(Base64.getDecoder().decode(p[4]), StandardCharsets.UTF_8);
        return new Registo(Long.parseLong(p[0]), Instant.parse(p[1]), p[2], p[3], texto);
    }
}
