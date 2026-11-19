package tpf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

public class LeaderBot extends ListenerAdapter {

    /** Logger (SLF4J). A saída aparece na consola graças ao slf4j-simple do pom.xml. */
    private static final Logger log = LoggerFactory.getLogger(LeaderBot.class);

    /** Nome do canal monitorizado. */
    public static final String CANAL_ARQUIVO = "arquivo";

    /** Papel necessário para os comandos de administração. */
    public static final String PAPEL_ADMIN = "admin-arquivo";

    /**
     * Nomes dos comandos restritos a membros com o papel admin-arquivo.
     * Set.of(...) cria um conjunto imutável; .contains() é uma pesquisa rápida.
     * (Os comandos que não estão aqui, como /registar, são abertos a todos.)
     */
    private static final Set<String> COMANDOS_ADMIN =
            Set.of("config", "replica-token", "replica-remover", "rajada");

    /** Sufixo das respostas provisórias, para deixar claro que ainda é um esqueleto. */
    private static final String NAO_IMPLEMENTADO = " — ainda não implementado";

    // ------------------------------------------------------------------
    // Arranque
    // ------------------------------------------------------------------

    /**
     * Ponto de entrada. Corresponde ao mainClass configurado no exec-maven-plugin,
     * por isso "mvn compile exec:java" chama este método.
     *
     * "throws InterruptedException" existe por causa do jda.awaitReady(), que
     * bloqueia a thread atual e pode ser interrompido.
     */
    public static void main(String[] args) throws InterruptedException {
        // 1) Configuração: tenta ler o bot.properties da pasta onde o programa corre.
        Properties ficheiro = lerFicheiroConfig(Path.of("bot.properties"));

        String token = lerConfig("DISCORD_TOKEN", "token", ficheiro);
        String guildId = lerConfig("DISCORD_GUILD_ID", "guild.id", ficheiro);

        // Sem token ou sem ID do servidor não vale a pena continuar: avisa e termina
        if (token == null || guildId == null) {
            System.err.println("""
                    Falta configuração.
                    Defina as variáveis de ambiente DISCORD_TOKEN e DISCORD_GUILD_ID,
                    ou crie o ficheiro bot.properties (ver bot.properties.example).""");
            System.exit(1);
        }

        // 2) Ligação ao Discord.
        JDA jda = JDABuilder.createLight(token,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT) // privileged intent ativado no Developer Portal
                .addEventListeners(new LeaderBot())    // regista esta classe como ouvinte de eventos
                .build();                              // inicia a ligação (assíncrona: devolve logo)

        // build() não espera pela ligação; awaitReady() bloqueia até a JDA estar
        // totalmente ligada e com os servidores carregados.
        jda.awaitReady();

        // 3) Encontra o servidor de teste. Dá null se o bot não tiver sido convidado
        //    para esse servidor ou se o ID estiver errado.
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            log.error("O bot não está no servidor com ID {}. Verifique o guild.id e o convite.", guildId);
            jda.shutdown();
            System.exit(1);
        }

        // 4) Publica os slash commands nesse servidor.
        registarComandos(guild);
        log.info("LeaderBot online como {} no servidor '{}'.", jda.getSelfUser().getName(), guild.getName());

        // Nota: o main() acaba aqui, mas o processo NÃO termina. As threads da JDA
        // (websocket) continuam vivas e ficam a despachar eventos para os métodos on...().
    }

    /**
     * Regista os 7 comandos como guild commands (aparecem logo, ao contrário dos globais).
     *
     * updateCommands() SUBSTITUI toda a lista de comandos do bot neste servidor pela
     * lista dada: comandos que já não estejam aqui desaparecem do Discord.
     * Todos os parâmetros são STRING e a conversão para número é feita à mão
     * (ver inteiro()), assim controlamos as mensagens de erro.
     */
    private static void registarComandos(Guild guild) {
        guild.updateCommands().addCommands(
                // /registar texto:<string>   — o último argumento "true" = obrigatório
                Commands.slash("registar", "Regista um texto no arquivo")
                        .addOption(OptionType.STRING, "texto", "Texto a registar", true),

                // /estado   — sem argumentos
                Commands.slash("estado", "Mostra o estado do arquivo"),

                // /config r n:<string>   — "r" é um SUBCOMANDO (comando dentro do comando).
                // O argumento "n" pertence ao subcomando, não ao /config.
                Commands.slash("config", "Configuração do arquivo (admin)")
                        .addSubcommands(new SubcommandData("r", "Define o número de réplicas")
                                .addOption(OptionType.STRING, "n", "Número de réplicas (inteiro >= 1)", true)),

                // /recuperar de:<string> ate:<string>
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
        // queue(sucesso, erro): envia o pedido HTTP de forma assíncrona (não bloqueia)
        // e chama um dos dois callbacks quando o Discord responder.
        ).queue(
                cmds -> log.info("{} comandos registados no servidor.", cmds.size()),
                erro -> log.error("Falha a registar os comandos", erro)
        );
    }

    // ------------------------------------------------------------------
    // Slash commands
    // ------------------------------------------------------------------

    /**
     * Chamado pela JDA sempre que alguém executa um slash command neste servidor.
     *
     * Passos: (1) verificar permissões → (2) validar argumentos e montar a resposta
     * → (3) responder. Qualquer erro de validação é lançado como
     * IllegalArgumentException e tratado num único catch no fim.
     *
     * IMPORTANTE: o Discord exige uma resposta (event.reply) em menos de 3 segundos,
     * senão o utilizador vê "A aplicação não respondeu". Por agora tudo é instantâneo;
     * quando houver trabalho lento será preciso event.deferReply() primeiro.
     */
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String comando = event.getName();            // só o comando base, ex.: "config"
        String autor = event.getUser().getName();
        // getFullCommandName inclui o subcomando, ex.: "config r"
        log.info("Comando /{} de {}", event.getFullCommandName(), autor);

        // (1) Controlo de acesso: se o comando é de admin e o autor não tem o papel,
        //     responde de forma "efémera" (só o autor vê) e sai com return.
        if (COMANDOS_ADMIN.contains(comando) && !temPapelAdmin(event.getMember())) {
            event.reply("⛔ Não tens permissão para usar /" + comando
                            + ". É necessário o papel **" + PAPEL_ADMIN + "**.")
                    .setEphemeral(true).queue();
            return;
        }

        try {
            // (2) switch expression (Java 14+): cada ramo produz o texto da resposta.
            //     - "->" com expressão simples: o valor é a própria expressão.
            //     - "-> { ... yield valor; }": bloco com várias instruções; "yield" devolve o valor.
            //     - throw dentro de um ramo salta para o catch lá em baixo.
            String resposta = switch (comando) {
                case "registar" -> {
                    String texto = textoObrigatorio(event, "texto");
                    yield "/registar recebido: texto=\"" + texto + "\"" + NAO_IMPLEMENTADO;
                }
                case "estado" -> "/estado recebido" + NAO_IMPLEMENTADO;
                case "config" -> {
                    // Só existe o subcomando "r"; qualquer outro é rejeitado por segurança.
                    if (!"r".equals(event.getSubcommandName())) {
                        throw new IllegalArgumentException("Subcomando desconhecido. Use /config r <n>.");
                    }
                    long n = inteiro(event, "n", 1);   // nº de réplicas: mínimo 1
                    yield "/config r recebido: n=" + n + NAO_IMPLEMENTADO;
                }
                case "recuperar" -> {
                    long de = inteiro(event, "de", 0);   // índices começam em 0
                    long ate = inteiro(event, "ate", 0);
                    // Validação cruzada entre dois argumentos: o intervalo tem de fazer sentido.
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
                    long n = inteiro(event, "n", 1);               // pelo menos 1 registo
                    long intervalo = inteiro(event, "intervalo_ms", 0); // 0 = sem pausa
                    yield "/rajada recebido: n=" + n + ", intervaloMs=" + intervalo + NAO_IMPLEMENTADO;
                }
                // Rede de segurança: um comando registado mas sem ramo aqui.
                default -> throw new IllegalArgumentException("Comando desconhecido: /" + comando);
            };

            // (3a) Sucesso: resposta pública, visível a todos no canal.
            event.reply("✅ " + resposta).queue();

        } catch (IllegalArgumentException e) {
            // (3b) Erro do utilizador: resposta efémera (só ele a vê), não polui o canal.
            log.info("Argumentos inválidos em /{}: {}", event.getFullCommandName(), e.getMessage());
            event.reply("❌ " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    // ------------------------------------------------------------------
    // Mensagens no canal #arquivo
    // ------------------------------------------------------------------

    /**
     * Chamado pela JDA para cada mensagem recebida (precisa do intent GUILD_MESSAGES).
     * Funciona como um funil de filtros: cada "return" descarta o que não interessa.
     */
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;                 // ignora bots (incluindo o próprio) — evita ciclos infinitos
        if (!event.isFromGuild()) return;                      // ignora mensagens privadas
        if (!CANAL_ARQUIVO.equals(event.getChannel().getName())) return; // ignora outros canais

        // getContentRaw = texto exato como foi escrito (sem formatação resolvida).
        // Só vem preenchido porque ativámos o intent MESSAGE_CONTENT.
        String conteudo = event.getMessage().getContentRaw();
        log.info("Mensagem em #{} de {}: {}", CANAL_ARQUIVO, event.getAuthor().getName(), conteudo);

        // Responde "em resposta" (reply) à mensagem original. Por agora só confirma a receção.
        event.getMessage()
                .reply("📥 Mensagem detetada em #" + CANAL_ARQUIVO + " (" + conteudo.length()
                        + " caracteres)" + NAO_IMPLEMENTADO)
                .queue();
    }

    // ------------------------------------------------------------------
    // Auxiliares
    // ------------------------------------------------------------------

    /**
     * Verifica se o membro tem o papel admin-arquivo (comparação pelo NOME do papel).
     * O membro pode ser null (ex.: comando fora de um servidor), e nesse caso nega.
     */
    private static boolean temPapelAdmin(Member membro) {
        return membro != null && membro.getRoles().stream()
                .anyMatch(r -> r.getName().equals(PAPEL_ADMIN));
    }

    /**
     * Lê uma opção de texto obrigatória e devolve-a sem espaços nas pontas.
     * Rejeita opção ausente ou só com espaços (isBlank).
     */
    private static String textoObrigatorio(SlashCommandInteractionEvent event, String nome) {
        OptionMapping opt = event.getOption(nome);
        if (opt == null || opt.getAsString().isBlank()) {
            throw new IllegalArgumentException("O argumento '" + nome + "' é obrigatório.");
        }
        return opt.getAsString().trim();
    }

    /**
     * Lê um argumento e valida que é um inteiro >= min.
     * Como as opções foram registadas como STRING, o utilizador pode escrever
     * "abc" ou "-3"; aqui convertemos e damos mensagens de erro claras para cada caso.
     */
    private static long inteiro(SlashCommandInteractionEvent event, String nome, long min) {
        String valor = textoObrigatorio(event, nome);
        long n;
        try {
            n = Long.parseLong(valor);   // falha com NumberFormatException se não for número
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + nome + "' tem de ser um número inteiro (recebido: \"" + valor + "\").");
        }
        if (n < min) {
            throw new IllegalArgumentException("'" + nome + "' tem de ser >= " + min + " (recebido: " + n + ").");
        }
        return n;
    }

    /**
     * Variável de ambiente tem prioridade; senão usa o ficheiro bot.properties.
     * Valores vazios ou só com espaços contam como "não definido" (devolve null).
     *
     * @param env     nome da variável de ambiente (ex.: DISCORD_TOKEN)
     * @param chave   chave equivalente no ficheiro (ex.: token)
     * @param ficheiro propriedades já carregadas do bot.properties
     */
    private static String lerConfig(String env, String chave, Properties ficheiro) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) v = ficheiro.getProperty(chave);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * Carrega um ficheiro .properties (formato chave=valor).
     * Se não existir devolve vazio; se existir mas falhar a leitura, avisa e
     * devolve o que houver — o erro real aparecerá depois, como "Falta configuração".
     * O try-with-resources fecha o InputStream automaticamente.
     */
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
