# tpf-lider

Líder do arquivo distribuído (SD 2026/2027): bot Discord em Java 17+ com JDA.
No Sprint E2-01 recebe os comandos e responde com mensagens provisórias.

## Requisitos

- Java 17 ou superior
- Maven 3.9+
- Um bot criado no [Discord Developer Portal](https://discord.com/developers/applications) com o **Message Content Intent** ativo, já adicionado ao servidor de teste (scopes `bot` e `applications.commands`)

## Configurar o token

O token **nunca** vai para o repositório. Há duas formas de o configurar.

**Opção A: ficheiro `bot.properties`** (já está no `.gitignore`)

```powershell
copy bot.properties.example bot.properties
```

Depois edita `bot.properties`:

```properties
token=O_TOKEN_DO_BOT
guild.id=ID_DO_SERVIDOR_DE_TESTE
```

**Opção B: variáveis de ambiente** (têm prioridade sobre o ficheiro)

```powershell
$env:DISCORD_TOKEN="O_TOKEN_DO_BOT"
$env:DISCORD_GUILD_ID="ID_DO_SERVIDOR_DE_TESTE"
```

O ID do servidor obtém-se no Discord com o Modo desenvolvedor ativo: clique direito no servidor → *Copiar ID do servidor*.

## Arrancar o líder

Na pasta do projeto:

```powershell
mvn compile exec:java
```

Quando aparecer `LeaderBot online como ...`, o bot está online e os comandos estão registados.

## Comandos

| Comando | Acesso |
|---|---|
| `/registar <texto>` | todos |
| `/estado` | todos |
| `/config r <n>` | `admin-arquivo` |
| `/recuperar <de> <ate>` | todos |
| `/replica-token` | `admin-arquivo` |
| `/replica-remover <id>` | `admin-arquivo` |
| `/rajada <n> <intervalo_ms>` | `admin-arquivo` |

As mensagens escritas no canal `#arquivo` são detetadas pelo bot. As dos outros canais e as de bots são ignoradas.
