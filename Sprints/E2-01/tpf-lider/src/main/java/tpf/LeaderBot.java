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
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
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
     *
     * Os tipos e limites são validados pelo PRÓPRIO DISCORD, na caixa de escrita:
     * - OptionType.INTEGER: se o utilizador escrever "abc", pode escrever mas o Discord
     *   não deixa enviar (o pedido nem chega ao bot → sem spam, sem conversões no código).
     * - setMinValue(x): números abaixo de x também são bloqueados na inserção.
     * - setMaxLength(x): textos maiores que x caracteres são bloqueados na inserção.
     */
    private static void registarComandos(Guild guild) {
        guild.updateCommands().addCommands(
                // /registar texto:<texto>   — o "true" = obrigatório; máx. 1500 caracteres
                Commands.slash("registar", "Regista um texto no arquivo")
                        .addOptions(new OptionData(OptionType.STRING, "texto", "Texto a registar", true)
                                .setMaxLength(1500)),

                // /estado   — sem argumentos
                Commands.slash("estado", "Mostra o estado do arquivo"),

                // /config r n:<inteiro >= 1>   — "r" é um SUBCOMANDO (comando dentro do comando).
                // O argumento "n" pertence ao subcomando, não ao /config.
                Commands.slash("config", "Configuração do arquivo (admin)")
                        .addSubcommands(new SubcommandData("r", "Define o número de réplicas")
                                .addOptions(new OptionData(OptionType.INTEGER, "n", "Número de réplicas (>= 1)", true)
                                        .setMinValue(1))),

                // /recuperar de:<inteiro >= 0> ate:<inteiro >= 0>
                // (a regra de <= ate envolve DOIS campos, o Discord não a consegue verificar: fica no código)
                Commands.slash("recuperar", "Recupera os registos num intervalo")
                        .addOptions(
                                new OptionData(OptionType.INTEGER, "de", "Primeiro registo (>= 0)", true)
                                        .setMinValue(0),
                                new OptionData(OptionType.INTEGER, "ate", "Último registo (>= de)", true)
                                        .setMinValue(0)),

                Commands.slash("replica-token", "Gera um token para uma nova réplica (admin)"),

                // /replica-remover id:<texto>   — máx. 64 caracteres
                Commands.slash("replica-remover", "Remove uma réplica (admin)")
                        .addOptions(new OptionData(OptionType.STRING, "id", "Identificador da réplica", true)
                                .setMaxLength(64)),

                // /rajada n:<inteiro >= 1> intervalo_ms:<inteiro >= 0>
                // Os nomes das opções no Discord têm de ser minúsculos: intervaloMs -> intervalo_ms
                Commands.slash("rajada", "Envia uma rajada de registos de teste (admin)")
                        .addOptions(
                                new OptionData(OptionType.INTEGER, "n", "Número de registos (>= 1)", true)
                                        .setMinValue(1),
                                new OptionData(OptionType.INTEGER, "intervalo_ms", "Intervalo entre registos em ms (>= 0)", true)
                                        .setMinValue(0))
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
     * Passos: (0) só aceitar comandos no #arquivo → (1) verificar permissões
     * → (2) ler os argumentos (já validados pelo Discord) e montar a resposta
     * → (3) responder. Os erros que sobram (ex.: de > ate) são lançados como
     * IllegalArgumentException e tratados num único catch no fim.
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

        // (0) Comandos só no #arquivo. Noutros canais o bot não executa nada.
        //     O Discord OBRIGA a responder a todas as interações (senão o utilizador vê
        //     "A aplicação não respondeu"), por isso damos um aviso efémero: só o autor
        //     o vê, o canal não fica com mensagens do bot.
        if (!CANAL_ARQUIVO.equals(event.getChannel().getName())) {
            log.info("Comando /{} ignorado: canal #{}", event.getFullCommandName(), event.getChannel().getName());
            event.reply("⛔ Os comandos só podem ser usados no #" + CANAL_ARQUIVO + ".")
                    .setEphemeral(true).queue();
            return;
        }

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
                    long n = numero(event, "n");   // o Discord já garantiu: inteiro >= 1
                    yield "/config r recebido: n=" + n + NAO_IMPLEMENTADO;
                }
                case "recuperar" -> {
                    long de = numero(event, "de");     // já garantido: inteiro >= 0
                    long ate = numero(event, "ate");
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
                    long n = numero(event, "n");                    // já garantido: >= 1
                    long intervalo = numero(event, "intervalo_ms"); // já garantido: >= 0 (0 = sem pausa)
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
     * Lê uma opção INTEGER. Não há conversão nem validação de formato aqui:
     * o Discord só deixa enviar o comando se o valor for um inteiro dentro do
     * setMinValue definido em registarComandos(), e entrega-o já como número.
     */
    private static long numero(SlashCommandInteractionEvent event, String nome) {
        OptionMapping opt = event.getOption(nome);
        if (opt == null) {   // não deve acontecer (opção obrigatória), mas fica a proteção
            throw new IllegalArgumentException("O argumento '" + nome + "' é obrigatório.");
        }
        return opt.getAsLong();
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
