package tpf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Líder do arquivo (Sprint E2-01).
 *
 * Regista os slash commands no servidor de teste, recebe-os, valida os argumentos
 * e responde com uma mensagem provisória. Deteta também as mensagens no canal #arquivo.
 * Nenhuma funcionalidade do arquivo (registo, cifra, réplicas, recuperação) está implementada.
 */
public class LeaderBot extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(LeaderBot.class);

    /** Nome do canal monitorizado. */
    public static final String CANAL_ARQUIVO = "arquivo";

    /** Papel necessário para os comandos de administração. */
    public static final String PAPEL_ADMIN = "admin-arquivo";

    /** Comandos restritos a membros com o papel admin-arquivo. */
    private static final Set<String> COMANDOS_ADMIN =
            Set.of("config", "replica-token", "replica-remover", "rajada");

    private static final String NAO_IMPLEMENTADO = " — ainda não implementado";

    // ------------------------------------------------------------------
    // Arranque
    // ------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {
        Properties ficheiro = lerFicheiroConfig(Path.of("bot.properties"));

        String token = lerConfig("DISCORD_TOKEN", "token", ficheiro);
        String guildId = lerConfig("DISCORD_GUILD_ID", "guild.id", ficheiro);

        if (token == null || guildId == null) {
            System.err.println("""
                    Falta configuração.
                    Defina as variáveis de ambiente DISCORD_TOKEN e DISCORD_GUILD_ID,
                    ou crie o ficheiro bot.properties (ver bot.properties.example).""");
            System.exit(1);
        }

        JDA jda = JDABuilder.createLight(token,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT) // privileged intent ativado no Developer Portal
                .addEventListeners(new LeaderBot())
                .build();

        jda.awaitReady();

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            log.error("O bot não está no servidor com ID {}. Verifique o guild.id e o convite.", guildId);
            jda.shutdown();
            System.exit(1);
        }

        registarComandos(guild);
        log.info("LeaderBot online como {} no servidor '{}'.", jda.getSelfUser().getName(), guild.getName());
    }

    /** Regista os 7 comandos como guild commands (aparecem logo, ao contrário dos globais). */
    private static void registarComandos(Guild guild) {
        guild.updateCommands().addCommands(
                Commands.slash("registar", "Regista um texto no arquivo")
                        .addOption(OptionType.STRING, "texto", "Texto a registar", true),

                Commands.slash("estado", "Mostra o estado do arquivo"),

                Commands.slash("config", "Configuração do arquivo (admin)")
                        .addSubcommands(new SubcommandData("r", "Define o número de réplicas")
                                .addOption(OptionType.STRING, "n", "Número de réplicas (inteiro >= 1)", true)),

                Commands.slash("recuperar", "Recupera os registos num intervalo")
                        .addOption(OptionType.STRING, "de", "Primeiro registo (inteiro >= 0)", true)
                        .addOption(OptionType.STRING, "ate", "Último registo (inteiro >= de)", true),

                Commands.slash("replica-token", "Gera um token para uma nova réplica (admin)"),

                Commands.slash("replica-remover", "Remove uma réplica (admin)")
                        .addOption(OptionType.STRING, "id", "Identificador da réplica", true),

                Commands.slash("rajada", "Envia uma rajada de registos de teste (admin)")
                        .addOption(OptionType.STRING, "n", "Número de registos (inteiro >= 1)", true)
                        // Os nomes das opções no Discord têm de ser minúsculos: intervaloMs -> intervalo_ms
                        .addOption(OptionType.STRING, "intervalo_ms", "Intervalo entre registos em ms (inteiro >= 0)", true)
        ).queue(
                cmds -> log.info("{} comandos registados no servidor.", cmds.size()),
                erro -> log.error("Falha a registar os comandos", erro)
        );
    }

    // ------------------------------------------------------------------
    // Slash commands
    // ------------------------------------------------------------------

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String comando = event.getName();
        String autor = event.getUser().getName();
        log.info("Comando /{} de {}", event.getFullCommandName(), autor);

        if (COMANDOS_ADMIN.contains(comando) && !temPapelAdmin(event.getMember())) {
            event.reply("⛔ Não tens permissão para usar /" + comando
                            + ". É necessário o papel **" + PAPEL_ADMIN + "**.")
                    .setEphemeral(true).queue();
            return;
        }

        try {
            String resposta = switch (comando) {
                case "registar" -> {
                    String texto = textoObrigatorio(event, "texto");
                    yield "/registar recebido: texto=\"" + texto + "\"" + NAO_IMPLEMENTADO;
                }
                case "estado" -> "/estado recebido" + NAO_IMPLEMENTADO;
                case "config" -> {
                    if (!"r".equals(event.getSubcommandName())) {
                        throw new IllegalArgumentException("Subcomando desconhecido. Use /config r <n>.");
                    }
                    long n = inteiro(event, "n", 1);
                    yield "/config r recebido: n=" + n + NAO_IMPLEMENTADO;
                }
                case "recuperar" -> {
                    long de = inteiro(event, "de", 0);
                    long ate = inteiro(event, "ate", 0);
                    if (de > ate) {
                        throw new IllegalArgumentException(
                                "Intervalo inválido: 'de' (" + de + ") tem de ser <= 'ate' (" + ate + ").");
                    }
                    yield "/recuperar recebido: de=" + de + ", ate=" + ate + NAO_IMPLEMENTADO;
                }
                case "replica-token" -> "/replica-token recebido" + NAO_IMPLEMENTADO;
                case "replica-remover" -> {
                    String id = textoObrigatorio(event, "id");
                    if (id.contains(" ")) {
                        throw new IllegalArgumentException("O id da réplica não pode conter espaços.");
                    }
                    yield "/replica-remover recebido: id=" + id + NAO_IMPLEMENTADO;
                }
                case "rajada" -> {
                    long n = inteiro(event, "n", 1);
                    long intervalo = inteiro(event, "intervalo_ms", 0);
                    yield "/rajada recebido: n=" + n + ", intervaloMs=" + intervalo + NAO_IMPLEMENTADO;
                }
                default -> throw new IllegalArgumentException("Comando desconhecido: /" + comando);
            };

            event.reply("✅ " + resposta).queue();

        } catch (IllegalArgumentException e) {
            log.info("Argumentos inválidos em /{}: {}", event.getFullCommandName(), e.getMessage());
            event.reply("❌ " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    // ------------------------------------------------------------------
    // Mensagens no canal #arquivo
    // ------------------------------------------------------------------

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;                 // ignora bots (incluindo o próprio)
        if (!event.isFromGuild()) return;                      // ignora mensagens privadas
        if (!CANAL_ARQUIVO.equals(event.getChannel().getName())) return; // ignora outros canais

        String conteudo = event.getMessage().getContentRaw();
        log.info("Mensagem em #{} de {}: {}", CANAL_ARQUIVO, event.getAuthor().getName(), conteudo);

        event.getMessage()
                .reply("📥 Mensagem detetada em #" + CANAL_ARQUIVO + " (" + conteudo.length()
                        + " caracteres)" + NAO_IMPLEMENTADO)
                .queue();
    }

    // ------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------

    private static boolean temPapelAdmin(Member membro) {
        return membro != null && membro.getRoles().stream()
                .anyMatch(r -> r.getName().equals(PAPEL_ADMIN));
    }

    private static String textoObrigatorio(SlashCommandInteractionEvent event, String nome) {
        OptionMapping opt = event.getOption(nome);
        if (opt == null || opt.getAsString().isBlank()) {
            throw new IllegalArgumentException("O argumento '" + nome + "' é obrigatório.");
        }
        return opt.getAsString().trim();
    }

    /** Lê um argumento e valida que é um inteiro >= min. */
    private static long inteiro(SlashCommandInteractionEvent event, String nome, long min) {
        String valor = textoObrigatorio(event, nome);
        long n;
        try {
            n = Long.parseLong(valor);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + nome + "' tem de ser um número inteiro (recebido: \"" + valor + "\").");
        }
        if (n < min) {
            throw new IllegalArgumentException("'" + nome + "' tem de ser >= " + min + " (recebido: " + n + ").");
        }
        return n;
    }

    /** Variável de ambiente tem prioridade; senão usa o ficheiro bot.properties. */
    private static String lerConfig(String env, String chave, Properties ficheiro) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) v = ficheiro.getProperty(chave);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static Properties lerFicheiroConfig(Path caminho) {
        Properties p = new Properties();
        if (Files.exists(caminho)) {
            try (InputStream in = Files.newInputStream(caminho)) {
                p.load(in);
            } catch (IOException e) {
                System.err.println("Não foi possível ler " + caminho + ": " + e.getMessage());
            }
        }
        return p;
    }
}
