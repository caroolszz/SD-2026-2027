# tpf-lider

Líder do arquivo distribuído (SD 2026/2027): bot Discord em Java 17+ com JDA.

- **Sprint E2-01:** recebe os comandos e responde com mensagens provisórias.
- **Sprint E2-02:** caminho de escrita local. Cada `/registar` e cada mensagem em `#arquivo` recebe um LSN e é gravado em `registos.log` com `fsync` antes da resposta. Depois entra no chunk aberto, que é selado por tamanho ou por tempo, serializado com padding, cifrado (HKDF-SHA256 + AES-256-GCM) e gravado em `selados/<sha256>.bin`.

`/estado`, `/config`, `/recuperar`, `/replica-token` e `/replica-remover` mantêm a resposta provisória.

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

### Parâmetros (`-Dtpf.*`)

| Propriedade | Constante em `Wire` | Por omissão | Significado |
|---|---|---|---|
| `tpf.chunkMaxBytes` | `CHUNK_MAX_BYTES` | 65536 | Tamanho (sem padding) a partir do qual o chunk é selado |
| `tpf.padBytes` | `PAD_BYTES` | 4096 | O chunk serializado é completado até um múltiplo deste valor |
| `tpf.selagemMs` | `SELAGEM_MS` | 5000 | Tempo máximo desde o 1.º registo do chunk até à selagem |
| `tpf.dir` | `PASTA` | `.` | Pasta do líder |
| `tpf.rajadaBytes` | `RAJADA_TEXTO_BYTES` | 512 | Tamanho do texto de cada registo sintético de `/rajada` |

Exemplo: `mvn compile exec:java "-Dtpf.selagemMs=2000" "-Dtpf.dir=dados-lider"` (no PowerShell as aspas são necessárias).

### Pasta do líder

```
master.key          32 bytes aleatórios, legível só pelo dono (nunca vai para o Git)
registos.log        lsn;instante;autor;canal;textoBase64   (uma linha por registo, fsync)
eventos-lider.txt   instante;evento;detalhe
selados/
  <chunkId>.bin     texto cifrado + tag GCM
  <chunkId>.hdr     keyVersion;Base64(salt);Base64(nonce)
```

- No primeiro arranque a `master.key` é gerada. Se existir com tamanho diferente de 32 bytes, o líder **recusa arrancar**.
- No arranque o líder lê `registos.log` e continua a numeração a partir do último LSN. Os registos que estavam no chunk aberto quando o líder parou voltam ao chunk aberto e são selados.
- No Windows a `master.key` fica com uma ACL só para o dono (leitura e apagar). Para a apagar usa `Remove-Item master.key` **sem** `-Force`.

## Comandos

| Comando | Acesso | E2-02 |
|---|---|---|
| `/registar <texto>` | todos | responde `Registado com LSN n` depois do fsync |
| `/estado` | todos | provisório |
| `/config r <n>` | `admin-arquivo` | provisório |
| `/recuperar <de> <ate>` | todos | provisório |
| `/replica-token` | `admin-arquivo` | provisório |
| `/replica-remover <id>` | `admin-arquivo` | provisório |
| `/rajada <n> <intervalo_ms>` | `admin-arquivo` | injeta n registos sintéticos pelo mesmo método de `/registar` |

As mensagens escritas no canal `#arquivo` são registadas pelo mesmo método que `/registar` (resposta `📥 Registado com LSN n`). As dos outros canais, as de bots e as que só têm anexos são ignoradas.

## Formato binário do chunk (antes da cifra)

Todos os inteiros em **big-endian** (o formato de `DataOutputStream`/`ByteBuffer`). Textos em UTF-8.

```
Cabeçalho (9 bytes)
  offset 0   magic       4 bytes   "TPFC" = 54 50 46 43
  offset 4   versão      1 byte    01
  offset 5   nRegistos   int32

Registo (repetido nRegistos vezes; 24 bytes fixos + textos)
  lsn         int64
  instante    int64    milissegundos desde 1970-01-01T00:00:00Z
  autorLen    uint16   seguido de autorLen bytes UTF-8
  canalLen    uint16   seguido de canalLen bytes UTF-8
  textoLen    int32    seguido de textoLen bytes UTF-8

Padding
  bytes 00 até o total ser múltiplo de PAD_BYTES (nenhum se já for)
```

- O tamanho comparado com `CHUNK_MAX_BYTES` é o tamanho **sem padding** (9 + soma dos registos).
- O registo que faz o tamanho atingir ou passar `CHUNK_MAX_BYTES` entra no chunk e sela-o de imediato; o registo seguinte abre um chunk novo.
- A leitura (`Chunk.desserializar`) lê `nRegistos` registos e ignora o resto (padding).

## Cifra e ficheiros selados

```
salt  = 32 bytes aleatórios        nonce = 12 bytes aleatórios         (novos em cada chunk)
DEK   = HKDF-SHA256(MK, salt, info = "tpf-chunk-v1")
          PRK = HMAC-SHA256(chave = salt, msg = MK)                     extração
          DEK = HMAC-SHA256(chave = PRK,  msg = "tpf-chunk-v1" || 01)   expansão, 1 bloco = 32 bytes
aad   = "tpf-v1" (6 B) || keyVersion (int32 BE, = 1) || salt (32 B) || nonce (12 B)   = 54 bytes
ct    = AES-256-GCM(DEK, nonce, aad, chunk com padding)               inclui tag de 16 bytes no fim
chunkId = hex minúsculo de SHA-256(ct)
```

**`<chunkId>.bin`**: exatamente `ct` (texto cifrado + tag). Tem `k × PAD_BYTES + 16` bytes, e o nome é o SHA-256 do conteúdo (`sha256sum selados/*.bin` confirma).

**`<chunkId>.hdr`**: uma linha de texto ASCII

```
keyVersion;Base64(salt);Base64(nonce)
1;8roVM7gKcwVDM1fMHLNsmFe8aLz0oNdw/AdLAoZ1NtE=;6LqHa3atAvF7Iwdz
```

`open` verifica por esta ordem: (1) `SHA-256(.bin)` = `chunkId`, (2) `keyVersion` = 1, (3) tag GCM. Qualquer falha lança `GeneralSecurityException`.

## Testes e verificação

```powershell
mvn test                                                     # 37 testes (ChunkCrypto, Chunk, Arquivo)
mvn -q compile exec:java "-Dexec.mainClass=tpf.VerificarSelados"   # abre todos os chunks de selados/
```

`VerificarSelados` confirma, para cada chunk, que o nome é o SHA-256 do conteúdo, que abre (hash, versão, GCM), que o tamanho é múltiplo de `PAD_BYTES`, que os registos são exatamente os de `registos.log`, que o intervalo coincide com o evento `SELADO` e que nenhum texto aparece em claro.

Procurar texto em claro à mão:

```powershell
Select-String -Path selados\*.bin -Pattern "texto registado"   # não devolve nada
```
