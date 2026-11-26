# Sprint E2-02 · Registo durável, chunks e cifra no líder

Relatório de estudo e guião para a apresentação. Explica **o que foi feito**, **porquê**, **onde está no código** (com ligações para as linhas), **como se verifica cada critério de aceitação** e as **perguntas prováveis com resposta**.

> Ficha: <https://github.com/cunhaestgv/SD-2026-2027/issues/21> · Base: Sprint E2-01 (bot Discord com respostas provisórias).

---

## Índice

0. [Entregáveis e como correr](#0-entregáveis-e-como-correr)
1. [Pontos ambíguos e decisões tomadas](#1-pontos-ambíguos-e-decisões-tomadas)
2. [O que é, em poucas palavras](#2-o-que-é-em-poucas-palavras)
3. [Conceitos](#3-conceitos)
4. [Arquitetura e classes](#4-arquitetura-e-classes)
5. [Formatos dos ficheiros (com exemplos reais)](#5-formatos-dos-ficheiros-com-exemplos-reais)
6. [Fluxo completo, passo a passo](#6-fluxo-completo-passo-a-passo)
7. [Mapa do código](#7-mapa-do-código)
8. [Concorrência](#8-concorrência)
9. [Arranque e recuperação depois de uma queda](#9-arranque-e-recuperação-depois-de-uma-queda)
10. [Critérios de aceitação: como se verifica cada um](#10-critérios-de-aceitação-como-se-verifica-cada-um)
11. [Testes](#11-testes)
12. [Perguntas prováveis na apresentação](#12-perguntas-prováveis-na-apresentação)
13. [Limitações que se mantêm](#13-limitações-que-se-mantêm)
14. [Resumo de uma página](#14-resumo-de-uma-página)

---

## 0. Entregáveis e como correr

Projeto Maven em [tpf-lider/](tpf-lider/), copiado do E2-01 e evoluído. Pacote `tpf`.

| Ficheiro | Estado | Resumo |
|---|---|---|
| [Wire.java](tpf-lider/src/main/java/tpf/Wire.java) | **novo** | `CHUNK_MAX_BYTES`, `PAD_BYTES`, `SELAGEM_MS` (alteráveis com `-Dtpf.*`) e nomes de ficheiros |
| [MasterKey.java](tpf-lider/src/main/java/tpf/MasterKey.java) | **novo** | Gera/lê a `master.key` (32 bytes, só o dono lê); recusa tamanho errado |
| [Registo.java](tpf-lider/src/main/java/tpf/Registo.java) | **novo** | `record` com `lsn`, `instante`, `autor`, `canal`, `texto`; linha do `registos.log` |
| [RegistoLog.java](tpf-lider/src/main/java/tpf/RegistoLog.java) | **novo** | `registos.log` append-only com `force(true)`; leitura e validação no arranque |
| [Eventos.java](tpf-lider/src/main/java/tpf/Eventos.java) | **novo** | `eventos-lider.txt` (`instante;evento;detalhe`) |
| [Chunk.java](tpf-lider/src/main/java/tpf/Chunk.java) | **novo** | Chunk aberto, selagem (imutável), serialização com padding e leitura inversa |
| [ChunkCrypto.java](tpf-lider/src/main/java/tpf/ChunkCrypto.java) | **novo** | HKDF-SHA256, AES-256-GCM, `seal`/`open`, `chunkId` = SHA-256 |
| [Sealed.java](tpf-lider/src/main/java/tpf/Sealed.java) | **novo** | Chunk selado; grava/lê `selados/<id>.bin` e `.hdr` |
| [Arquivo.java](tpf-lider/src/main/java/tpf/Arquivo.java) | **novo** | Junta tudo: `registar(autor, canal, texto)`, temporizador, `selar()`, arranque |
| [VerificarSelados.java](tpf-lider/src/main/java/tpf/VerificarSelados.java) | **novo** (extra) | Ferramenta que abre todos os chunks e confere-os com o log e os eventos |
| [LeaderBot.java](tpf-lider/src/main/java/tpf/LeaderBot.java) | alterado | `/registar`, `#arquivo` e `/rajada` passam a usar `Arquivo.registar` |
| [pom.xml](tpf-lider/pom.xml) | alterado | JUnit 5 + surefire; `exec.mainClass` passa a propriedade (pode ser trocada) |
| [README.md](tpf-lider/README.md) | alterado | Parâmetros, pasta do líder, **formato binário do chunk e do `.hdr`** |
| `src/test/java/tpf/*Test.java` | **novo** | 37 testes: [ChunkCryptoTest](tpf-lider/src/test/java/tpf/ChunkCryptoTest.java) (18), [ChunkTest](tpf-lider/src/test/java/tpf/ChunkTest.java) (6), [ArquivoTest](tpf-lider/src/test/java/tpf/ArquivoTest.java) (13) |

**Correr** (na pasta `tpf-lider`):

```powershell
mvn test                                                          # 37 testes, sem Discord
mvn compile exec:java                                             # líder (precisa do token)
mvn compile exec:java "-Dtpf.selagemMs=2000"                      # com outro SELAGEM_MS
mvn -q compile exec:java "-Dexec.mainClass=tpf.VerificarSelados"  # verifica selados/
```

---

## 1. Pontos ambíguos e decisões tomadas

Convém **declarar estas escolhas** na apresentação.

| # | O que a ficha deixa em aberto | O que foi feito | Porquê |
|---|---|---|---|
| D1 | "Selar quando chegar a `CHUNK_MAX_BYTES`": tamanho com ou sem padding? `>` ou `>=`? | Compara-se o tamanho serializado **sem padding** com `>=` (chegar = atingir). | O padding só existe depois de selar. "Chegar a" inclui o valor exato. |
| D2 | "O registo que faz o chunk exceder o limite sela-o de imediato": esse registo entra no chunk? | **Entra**, e o chunk é selado logo a seguir. O seguinte abre chunk novo. | É a leitura literal ("sela-o", não "vai para o seguinte"). Consequência: um chunk pode passar o limite em, no máximo, um registo (por isso os chunks da rajada têm 69632 B = 17 × 4096 e não 65536). |
| D3 | CA "todos com tamanho múltiplo de `PAD_BYTES`", mas o `.bin` tem a tag GCM de 16 bytes. | O **texto com padding** é múltiplo de `PAD_BYTES`; o `.bin` tem `k × PAD_BYTES + 16`. O evento `SELADO` mostra os dois (`bytes=` e `bin=`). | O nome tem de ser o SHA-256 do texto cifrado **incluindo a tag**, e o `.bin` tem de ter esse conteúdo (CA5). Não dá para ter as duas coisas sem alterar o padding pedido. |
| D4 | `.hdr` = "`keyVersion;salt;nonce`, em Base64": também o `keyVersion`? | `keyVersion` em **decimal**, `salt` e `nonce` em Base64: `1;8roVM…;6LqH…` | `keyVersion` é um inteiro pequeno; em decimal lê-se à vista. Os bytes aleatórios precisam de Base64. |
| D5 | `registos.log`: só o texto em Base64? E se `autor`/`canal` tiverem `;`? | Só o texto em Base64 (como pedido). `;` ou mudança de linha em `autor`/`canal` são trocados por `_`. | Os nomes do Discord não têm esses caracteres, mas a troca garante que o formato nunca parte. |
| D6 | Precisão do `instante`. | Milissegundos, UTC, ISO-8601 (`2026-11-23T19:55:18.044Z`). No chunk vai como `int64` em ms. | Ida e volta exata entre log, chunk e verificação. |
| D7 | `/rajada 500 0` tem de dar **vários** chunks por tamanho. Com textos curtos, 500 registos cabem num só chunk. | Cada registo sintético tem **512 caracteres** (`-Dtpf.rajadaBytes`). 500 × ~550 B ≈ 275 KB → 4 chunks por tamanho + o resto. | Sem isto o critério não se cumpre com os valores por omissão. |
| D8 | `/rajada` com intervalo pode demorar mais do que os 3 s que o Discord dá para responder. | Responde logo "Rajada iniciada", corre numa thread própria e, no fim, publica "Rajada concluída: n registos, LSN a–b". | Uma rajada de 500 × 1000 ms dura mais de 8 minutos. |
| D9 | Mensagens em `#arquivo` só com anexo (texto vazio). | Ignoradas. | Não há texto para arquivar. |
| D10 | O que acontece aos registos do chunk aberto se o líder morrer antes da selagem? (a ficha não fala nisto) | No arranque, os registos de `registos.log` que não estão em nenhum chunk selado **voltam ao chunk aberto**. Num Ctrl+C o chunk aberto é selado antes de sair (`motivo=paragem`). | Sem isto, esses registos ficariam para sempre só no log (respondemos "registado" mas nunca seriam selados). |
| D11 | `registos.log` com a última linha incompleta (queda a meio de uma escrita). | É **cortada** no arranque e regista-se o evento `LOG_TRUNCADO`. | Esse registo nunca foi confirmado: a resposta só sai depois do `fsync`. |
| D12 | `registos.log` com LSN repetido/saltado, ou chunks com LSN maior do que o último do log. | O líder **recusa arrancar**. | Continuar criaria LSN repetidos (CA3). É preferível parar e o operador ver o problema. |
| D13 | Permissões "só o dono lê" no Windows (não há `chmod`). | POSIX: `r--------` (0400). Windows: ACL com uma só entrada, o dono, com leitura e `DELETE`. | Confirmado com `icacls`: `DESKTOP-…\admin:(R,D)`. `DELETE` para se poder apagar a pasta de testes. |
| D14 | Formato binário "à escolha do grupo". | Ver [§5.4](#54-chunk-serializado-antes-da-cifra): magic `TPFC`, versão, nº de registos, registos com comprimentos explícitos, padding a zeros. | Comprimentos explícitos → não há separadores a escapar; magic e versão → deteta lixo e permite evoluir. |

---

## 2. O que é, em poucas palavras

O líder passa a **guardar** o que recebe. Cada registo:

1. recebe um número de sequência (**LSN**) 1, 2, 3, …;
2. é escrito em `registos.log` e **forçado para o disco** (`fsync`); só depois o utilizador vê "Registado com LSN n";
3. junta-se a outros num **chunk**;
4. quando o chunk chega a 64 KB, ou passam 5 s desde o primeiro registo, é **selado**: serializado, completado com padding até um múltiplo de 4 KB, **cifrado** com uma chave derivada da `master.key` e gravado em `selados/<sha256>.bin`.

O resultado: uma pasta com ficheiros cifrados, cujo nome é o hash do conteúdo, que só o líder abre. É a preparação para os sprints seguintes, em que estes chunks vão ser enviados para réplicas que **não** devem conseguir lê-los.

---

## 3. Conceitos

### 3.1 Write-ahead log e `fsync`

`write()` só copia os bytes para a cache do sistema operativo. Se a máquina perder energia, perdem-se. `FileChannel.force(true)` (equivale a `fsync`) só volta quando os dados **e os metadados** (por exemplo, o tamanho do ficheiro) estão no disco. Por isso a ordem é:

```
escrever linha → force(true) → só então responder com o LSN
```

Se respondêssemos antes, um "Registado com LSN 42" podia desaparecer numa queda. É o princípio do *write-ahead log*: primeiro o registo durável, depois o resto (chunk, cifra, réplicas).

### 3.2 LSN (Log Sequence Number)

Identificador único e crescente, sem buracos. É atribuído dentro de um método `synchronized`, por isso dois pedidos simultâneos nunca recebem o mesmo número. No arranque lê-se o último LSN do `registos.log` e continua-se a partir daí.

### 3.3 Chunk e padding

Juntar registos em chunks reduz o número de ficheiros e operações de cifra. O **padding** até um múltiplo de 4096 esconde o tamanho exato do conteúdo: quem vê um chunk de 4096 bytes não sabe se tem 10 ou 3000 bytes de registos. Com padding fixo, os tamanhos ficam "em degraus".

### 3.4 HKDF (RFC 5869)

Não se cifra com a `master.key` diretamente. Para cada chunk deriva-se uma **DEK** (*data encryption key*):

```
PRK = HMAC-SHA256(chave = salt, mensagem = MK)              extração: "concentra" a aleatoriedade
DEK = HMAC-SHA256(chave = PRK,  mensagem = info || 0x01)    expansão: 1 bloco = 32 bytes = AES-256
```

Como o `salt` é aleatório e novo em cada chunk, **cada chunk tem uma chave diferente**. O `info = "tpf-chunk-v1"` separa domínios: se um dia a MK for usada para outra coisa (por exemplo, `tpf-manifesto-v1`), as chaves derivadas nunca coincidem. A implementação foi validada contra o vetor de teste 1 da RFC 5869.

### 3.5 AES-256-GCM e AAD

GCM é cifra **autenticada** (AEAD): além de cifrar, calcula uma **tag** de 128 bits sobre o texto cifrado e sobre dados adicionais (**AAD**) que não são cifrados mas ficam protegidos. Na decifra, se um único bit do texto cifrado, da tag, do AAD, do nonce ou da chave estiver diferente, `doFinal` lança `AEADBadTagException` e **não devolve nada**.

O AAD aqui é `"tpf-v1" || keyVersion || salt || nonce`. Assim o cabeçalho (`.hdr`) fica ligado ao texto cifrado: não se pode trocar o `keyVersion` ou o `salt` de um chunk sem a autenticação falhar.

**Nonce**: 12 bytes aleatórios por chunk. Em GCM, repetir um par (chave, nonce) é catastrófico; aqui a chave já muda em cada chunk (salt novo), por isso o risco é duplamente afastado.

### 3.6 Endereçamento por conteúdo

`chunkId = SHA-256(texto cifrado + tag)`. O nome do ficheiro **é** a verificação de integridade: quem recebe um chunk (nos próximos sprints, uma réplica) confirma que tem o ficheiro certo calculando o hash, **sem precisar da chave**. A réplica pode verificar integridade, mas não pode ler o conteúdo.

### 3.7 Por que é que `open` verifica por esta ordem

1. **Hash vs id**: barato e não precisa de chave. Apanha corrupção/troca de ficheiro.
2. **Versão da chave**: se não for a nossa, nem vale a pena tentar decifrar (prepara a rotação de chaves).
3. **GCM**: a prova criptográfica final. Apanha tudo o resto (salt/nonce trocados, chave errada, adulteração com hash recalculado).

---

## 4. Arquitetura e classes

```
                         Discord
     /registar   mensagem em #arquivo   /rajada n ms (thread "rajada")
          \               |                  /
           \              |                 /
            +---------> LeaderBot <--------+
                          |
                          v
         Arquivo.registar(autor, canal, texto)        synchronized
           |   1. LSN = último + 1
           |   2. RegistoLog.acrescentar  ── registos.log  (write + force(true))
           |   3. Eventos "REGISTO"       ── eventos-lider.txt
           |   4. Chunk.adicionar
           |        ├─ 1.º registo → agenda selarPorTempo (+SELAGEM_MS)   thread "selagem"
           |        └─ tamanho >= CHUNK_MAX_BYTES → selar("tamanho")
           v
       selar(motivo)
           Chunk.selar(PAD_BYTES)    → bytes com padding (chunk imutável)
           ChunkCrypto.seal          → salt, nonce, DEK=HKDF(MK,salt), AES-GCM, id=SHA-256
           Sealed.gravar             → selados/<id>.hdr, selados/<id>.bin   (tmp + fsync + rename)
           Eventos "SELADO"
```

Separação deliberada: **`Arquivo` não sabe nada de Discord**. O `LeaderBot` só traduz eventos do Discord em chamadas a `arquivo.registar(...)`. Por isso todos os critérios se testam com JUnit, sem bot.

---

## 5. Formatos dos ficheiros (com exemplos reais)

Os exemplos vêm de um ensaio real: 1 registo, espera de 5,6 s, rajada de 500 registos, paragem.

### 5.1 `registos.log`

```
lsn;instante;autor;canal;textoBase64
1;2026-11-23T19:55:18.044Z;rodrigo;arquivo;cGFsYXZyYS1zZWNyZXRhLXh5eg==
```

`cGFsYXZyYS1zZWNyZXRhLXh5eg==` = Base64 de `palavra-secreta-xyz`. Base64 **não é cifra**: serve para o texto (que pode ter `;` e mudanças de linha) caber numa linha sem partir o formato.

### 5.2 `eventos-lider.txt`

```
2026-11-23T19:55:18.040Z;ARRANQUE;masterKey=gerada ultimoLsn=0 lsnSelados=0 pendentes=0 chunkMaxBytes=65536 padBytes=4096 selagemMs=5000
2026-11-23T19:55:18.054Z;REGISTO;lsn=1 autor=rodrigo canal=arquivo bytes=57
2026-11-23T19:55:23.086Z;SELADO;chunkId=9927373183865618…bffc0 lsn=1-1 registos=1 bytes=4096 bin=4112 motivo=tempo
2026-11-23T19:55:23.782Z;SELADO;chunkId=ae97389fcbebe5e5…06ce3 lsn=2-121 registos=120 bytes=69632 bin=69648 motivo=tamanho
2026-11-23T19:55:23.884Z;SELADO;chunkId=8637955fbb7e8263…8de2d lsn=122-241 registos=120 bytes=69632 bin=69648 motivo=tamanho
2026-11-23T19:55:23.980Z;SELADO;chunkId=8c9f4faa8929e640…f8f65 lsn=242-361 registos=120 bytes=69632 bin=69648 motivo=tamanho
2026-11-23T19:55:24.071Z;SELADO;chunkId=bc2fb72562337baf…c507f lsn=362-481 registos=120 bytes=69632 bin=69648 motivo=tamanho
2026-11-23T19:55:24.093Z;SELADO;chunkId=b3b3ced47b93de76…12f0 lsn=482-501 registos=20 bytes=12288 bin=12304 motivo=paragem
2026-11-23T19:55:24.093Z;PARAGEM;ultimoLsn=501
```

Notas:
- O registo 1 foi selado **5,03 s** depois (`motivo=tempo`).
- Cada registo da rajada ocupa 550 B serializado (24 fixos + `rodrigo` 7 + `arquivo` 7 + 512 de texto). 119 registos = 65 459 B (< 65 536); o 120.º faz passar o limite e sela o chunk → 66 009 B → padding até 69 632 = 17 × 4096.
- Os eventos **nunca** contêm o texto dos registos (senão o `eventos-lider.txt` seria uma fuga).
- Outros eventos: `LOG_TRUNCADO`, `CHUNK_INVALIDO`, `ERRO_SELAGEM`.

### 5.3 `selados/<chunkId>.hdr`

```
1;8roVM7gKcwVDM1fMHLNsmFe8aLz0oNdw/AdLAoZ1NtE=;6LqHa3atAvF7Iwdz
```
`keyVersion` (decimal) ; `salt` (32 B em Base64 = 44 caracteres) ; `nonce` (12 B em Base64 = 16 caracteres).

### 5.4 Chunk serializado (antes da cifra)

Big-endian, textos em UTF-8 ([Chunk.java:21-36](tpf-lider/src/main/java/tpf/Chunk.java#L21-L36)):

```
Cabeçalho (9 B)   magic "TPFC" (4) | versão 0x01 (1) | nRegistos int32 (4)
Registo (24 B +)  lsn int64 | instante int64 (ms) | autorLen uint16 + autor
                  | canalLen uint16 + canal | textoLen int32 + texto
Padding           0x00 até múltiplo de PAD_BYTES
```

A leitura lê `nRegistos` registos e ignora o resto, por isso o padding não precisa de marcador.

### 5.5 `selados/<chunkId>.bin`

`texto cifrado || tag (16 B)`. Tamanho = texto com padding + 16. O nome é o SHA-256 deste conteúdo:

```
$ sha256sum selados/*.bin   → coincide com o nome em todos os 6 ficheiros
```

---

## 6. Fluxo completo, passo a passo

**`/registar olá`** (ou uma mensagem `olá` em `#arquivo`)

1. JDA chama `onSlashCommandInteraction` (ou `onMessageReceived`). Filtros: canal `#arquivo`, não-bot.
2. `arquivo.registar("rodrigo", "arquivo", "olá")` – entra no monitor do `Arquivo`.
3. Cria `Registo(lsn = ultimo + 1, agora, …)`.
4. `RegistoLog.acrescentar`: escreve `lsn;instante;autor;canal;b64\n`, chama `force(true)`. Se falhar, corta o que escreveu e lança exceção → o LSN **não** é consumido e o utilizador recebe "❌ Erro interno".
5. `ultimoLsn = lsn`; evento `REGISTO`.
6. `adicionarAoChunk`: se é o 1.º registo do chunk, agenda `selarPorTempo(este chunk)` para daqui a 5 s; se o tamanho chegou a 64 KB, `selar("tamanho")`.
7. Volta o `Registo`; o bot responde `✅ Registado com LSN n`.

**Selagem** (`selar(motivo)`)

1. Cancela o temporizador; guarda o chunk atual e põe um **chunk novo** como aberto.
2. `Chunk.selar(4096)` → bytes com padding; o chunk fica imutável.
3. `ChunkCrypto.seal`: `salt`, `nonce` aleatórios → `DEK = HKDF(MK, salt)` → `AES-GCM(DEK, nonce, aad)` → `id = SHA-256(ct)`.
4. `Sealed.gravar`: `.hdr` e depois `.bin`, cada um em `.tmp` + `fsync` + `rename` atómico.
5. Evento `SELADO` com `chunkId`, intervalo de LSN, nº de registos, tamanhos e motivo.

**`/rajada 500 0`** (só `admin-arquivo`)

1. Resposta imediata "Rajada iniciada…".
2. Thread `rajada`: 500 × `arquivo.registar(autor, "arquivo", textoSintetico(...))` – **o mesmo método** que `/registar`.
3. No fim, mensagem no canal com o intervalo de LSN.

---

## 7. Mapa do código

| O quê | Onde |
|---|---|
| Constantes `-Dtpf.*` | [Wire.java:17-23](tpf-lider/src/main/java/tpf/Wire.java#L17-L23) |
| Gerar/ler `master.key`, recusar ≠ 32 B | [MasterKey.java:39](tpf-lider/src/main/java/tpf/MasterKey.java#L39), [:47](tpf-lider/src/main/java/tpf/MasterKey.java#L47), [:60](tpf-lider/src/main/java/tpf/MasterKey.java#L60) |
| Permissões só para o dono (POSIX / ACL) | [MasterKey.java:89-104](tpf-lider/src/main/java/tpf/MasterKey.java#L89-L104) |
| Linha do log (Base64) | [Registo.java:39](tpf-lider/src/main/java/tpf/Registo.java#L39), [:45](tpf-lider/src/main/java/tpf/Registo.java#L45) |
| Append + `force(true)` | [RegistoLog.java:59-67](tpf-lider/src/main/java/tpf/RegistoLog.java#L59-L67) |
| Ler log, validar LSN, cortar linha incompleta | [RegistoLog.java:93](tpf-lider/src/main/java/tpf/RegistoLog.java#L93), [:40](tpf-lider/src/main/java/tpf/RegistoLog.java#L40) |
| **Método único `registar`** | [Arquivo.java:138](tpf-lider/src/main/java/tpf/Arquivo.java#L138) |
| Agendar selagem / selar por tamanho | [Arquivo.java:153-161](tpf-lider/src/main/java/tpf/Arquivo.java#L153-L161) |
| Selar por tempo (só se for o mesmo chunk) | [Arquivo.java:166](tpf-lider/src/main/java/tpf/Arquivo.java#L166) |
| `selar()` | [Arquivo.java:172](tpf-lider/src/main/java/tpf/Arquivo.java#L172) |
| Arranque: master.key → log → selados → pendentes | [Arquivo.java:70-128](tpf-lider/src/main/java/tpf/Arquivo.java#L70-L128) |
| Serializar com padding / leitura inversa | [Chunk.java:92](tpf-lider/src/main/java/tpf/Chunk.java#L92), [:114](tpf-lider/src/main/java/tpf/Chunk.java#L114), [:119](tpf-lider/src/main/java/tpf/Chunk.java#L119) |
| Imutabilidade depois de selado | [Chunk.java:50](tpf-lider/src/main/java/tpf/Chunk.java#L50), [:76](tpf-lider/src/main/java/tpf/Chunk.java#L76) |
| `seal` | [ChunkCrypto.java:65](tpf-lider/src/main/java/tpf/ChunkCrypto.java#L65) |
| `open` (1) hash (2) versão (3) GCM | [ChunkCrypto.java:81-107](tpf-lider/src/main/java/tpf/ChunkCrypto.java#L81-L107) |
| HKDF / AAD / SHA-256 | [ChunkCrypto.java:122](tpf-lider/src/main/java/tpf/ChunkCrypto.java#L122), [:137](tpf-lider/src/main/java/tpf/ChunkCrypto.java#L137), [:143](tpf-lider/src/main/java/tpf/ChunkCrypto.java#L143) |
| Gravar `.hdr`/`.bin` atómicos | [Sealed.java:52-73](tpf-lider/src/main/java/tpf/Sealed.java#L52-L73) |
| Abrir arquivo no arranque do bot; Ctrl+C sela | [LeaderBot.java:101](tpf-lider/src/main/java/tpf/LeaderBot.java#L101), [:108](tpf-lider/src/main/java/tpf/LeaderBot.java#L108) |
| `/registar` | [LeaderBot.java:248-253](tpf-lider/src/main/java/tpf/LeaderBot.java#L248-L253) |
| Mensagens em `#arquivo` | [LeaderBot.java:315-334](tpf-lider/src/main/java/tpf/LeaderBot.java#L315-L334) |
| `/rajada` | [LeaderBot.java:281](tpf-lider/src/main/java/tpf/LeaderBot.java#L281), [:346](tpf-lider/src/main/java/tpf/LeaderBot.java#L346), [:369](tpf-lider/src/main/java/tpf/LeaderBot.java#L369) |

---

## 8. Concorrência

Há quatro origens de chamadas ao `Arquivo`:

| Thread | Chama |
|---|---|
| JDA (eventos) | `registar` (de `/registar` e de `#arquivo`) |
| `rajada` | `registar` em ciclo |
| `selagem` (`ScheduledExecutorService`) | `selarPorTempo` |
| `paragem` (shutdown hook) | `close` → `selar("paragem")` |

Todas passam por métodos **`synchronized`** do mesmo objeto, por isso:

- dois registos nunca recebem o mesmo LSN e entram no log pela ordem do LSN;
- um registo nunca é adicionado a um chunk que está a meio de ser selado.

**Corrida subtil (temporizador vs. tamanho):** o chunk A é selado por tamanho às 2 s, abre-se o chunk B, e às 5 s dispara o temporizador agendado para A. Se o temporizador selasse "o chunk aberto", selaria B antes do tempo. Por isso o temporizador recebe **a referência do chunk para que foi agendado** e só sela se `aberto == alvo` ([Arquivo.java:166-170](tpf-lider/src/main/java/tpf/Arquivo.java#L166-L170)); além disso, `selar` cancela o temporizador.

**Custo:** o `fsync` e a cifra correm dentro do lock. Com 500 registos seguidos a rajada demorou ~0,7 s no ensaio (≈1,4 ms por `fsync`), o que chega para este sprint. Ver [§13](#13-limitações-que-se-mantêm).

---

## 9. Arranque e recuperação depois de uma queda

[Arquivo.java:70-128](tpf-lider/src/main/java/tpf/Arquivo.java#L70-L128), por esta ordem:

1. **`master.key`**: não existe → gera 32 B de `SecureRandom`, só o dono lê. Existe com ≠ 32 B → `IllegalStateException` → "Arranque recusado" e `exit(1)` **antes** de ligar ao Discord. (Gerar outra deixaria todos os chunks ilegíveis.)
2. **`registos.log`**: lê as linhas completas, exige LSN 1, 2, 3, …; corta uma última linha incompleta.
3. **`selados/`**: apaga `.tmp` órfãos; abre cada chunk (hash, versão, GCM) e recolhe os LSN que já estão selados. Chunk inválido → evento `CHUNK_INVALIDO` e os seus registos são tratados como não selados.
4. Se algum chunk tiver LSN maior do que o último do log → recusa (evita repetir LSN).
5. `ultimoLsn` = último LSN do log → o próximo registo continua a sequência.
6. Registos do log que **não** estão em nenhum chunk voltam ao chunk aberto (e o temporizador começa a contar).

| Momento da queda | O que se perde | Depois do arranque |
|---|---|---|
| Antes do `force(true)` | O registo (nunca foi confirmado ao utilizador) | Linha incompleta cortada; o LSN é reutilizado (nunca foi confirmado) |
| Depois do `force`, antes da selagem | Nada (está no log) | Volta ao chunk aberto e é selado |
| A meio de `Sealed.gravar` | Só um `.tmp` | `.tmp` apagado; registos voltam ao chunk aberto |
| Ctrl+C | Nada | O shutdown hook selou o chunk aberto |

---

## 10. Critérios de aceitação: como se verifica cada um

| # | Critério | Como se demonstra | Teste automático |
|---|---|---|---|
| CA1 | `/registar` responde com o LSN e a linha já está em `registos.log` | `/registar olá` → "Registado com LSN n"; `Get-Content registos.log -Tail 1` mostra `n;…` | `registarDevolveLsnELinhaJaEstaNoLog` (lê o ficheiro **sem** fechar o arquivo) |
| CA2 | `#arquivo` pelo mesmo caminho; bots e outros canais ignorados | Escrever em `#arquivo` → "📥 Registado com LSN n"; escrever em `#geral` → nada. Código: os três `return` no início de `onMessageReceived` e a chamada a `arquivo.registar` | — (precisa do Discord) |
| CA3 | Depois de reiniciar, LSN continuam sem repetições nem saltos | Registar 3, Ctrl+C, arrancar, registar → LSN 4. Evento `ARRANQUE ultimoLsn=3` | `depoisDeReiniciarOsLsnContinuam…`, `ultimaLinhaIncompletaECortada…`, `logComSaltoDeLsnRecusaOArranque` |
| CA4 | 1 registo → chunk selado ~5 s depois; `/rajada 500 0` → vários chunks por tamanho, múltiplos de `PAD_BYTES` | Eventos: `SELADO … motivo=tempo` 5,03 s depois; 4 × `motivo=tamanho` com `bytes=69632` (= 17 × 4096) | `umUnicoRegistoESeladoPorTempo`, `rajadaDe500ProduzVariosChunks…`, `registoQueExcedeOLimite…` |
| CA5 | Nome = SHA-256 do conteúdo e sem texto em claro | `sha256sum selados/*.bin`; `Select-String -Path selados\*.bin -Pattern "palavra-secreta"` não devolve nada (no ensaio: `grep` saiu com código 1, nenhuma correspondência) | `rajadaDe500…` (hash e `contem`), `textoCifradoNaoContemOTextoEmClaro` |
| CA6 | O líder abre cada chunk e obtém exatamente os registos do intervalo do `SELADO` | `mvn -q compile exec:java "-Dexec.mainClass=tpf.VerificarSelados"` → `6/6 chunks válidos, 501 registos selados` | `cadaChunkContemExatamenteOIntervaloDoEventoSelado` |
| CA7 | Byte alterado, outro salt/nonce/keyVersion ou outra `master.key` → exceção | Alterar um byte de um `.bin` e correr `VerificarSelados` → `FALHA … SHA-256 …` | 8 testes em `ChunkCryptoTest` (ver §11) |
| CA8 | Eventos `REGISTO` e `SELADO`; README descreve os formatos | `eventos-lider.txt`; [README.md](tpf-lider/README.md), secções "Formato binário do chunk" e "Cifra e ficheiros selados" | `eventosRegistoESeladoAparecemNoFicheiro` |

Saída real do `VerificarSelados` no ensaio:

```
501 registos em registos.log, 6 chunks em ...\demo\selados

OK    8637955fbb7e8263...  lsn=122-241  registos=120  texto=69632 B  bin=69648 B
OK    8c9f4faa8929e640...  lsn=242-361  registos=120  texto=69632 B  bin=69648 B
OK    9927373183865618...  lsn=1-1  registos=1  texto=4096 B  bin=4112 B
OK    ae97389fcbebe5e5...  lsn=2-121  registos=120  texto=69632 B  bin=69648 B
OK    b3b3ced47b93de76...  lsn=482-501  registos=20  texto=12288 B  bin=12304 B
OK    bc2fb72562337baf...  lsn=362-481  registos=120  texto=69632 B  bin=69648 B

6/6 chunks válidos, 501 registos selados.
```

---

## 11. Testes

`mvn test` → **37 testes, 0 falhas** (corridos com Maven 3.9.9 e Java 25).

**[ChunkCryptoTest](tpf-lider/src/test/java/tpf/ChunkCryptoTest.java)** (os pedidos no objetivo 5 e mais alguns):

| Teste | Falha em |
|---|---|
| `cifrarEDecifrarDevolveOTextoOriginal` | — (ida e volta) |
| `byteAlteradoNoTextoCifradoFalhaNaVerificacaoDoHash` | (1) hash |
| `byteAlteradoComIdRecalculadoFalhaNaAutenticacaoGcm` | (3) GCM – um atacante que também mude o nome não engana o GCM |
| `tagAlteradaFalha` | (3) GCM |
| `saltDiferenteFalha` | (3) GCM – outra DEK e outro AAD |
| `nonceDiferenteFalha` | (3) GCM |
| `keyVersionDiferenteFalhaNaVerificacaoDaVersao` | (2) versão |
| `keyVersionFazParteDoAad` | (3) GCM – mesmo aceitando a versão 2, o AAD da cifra tinha 1 |
| `masterKeyDiferenteFalha` | (3) GCM |
| `hkdfCorrespondeAoVetorDeTesteDoRfc5869` | valida a implementação do HKDF |
| `aadTemOFormatoPedido`, `idEOSha256DoTextoCifrado`, `cadaChunkTemSaltENonceNovos`, … | formato e aleatoriedade |

**[ChunkTest](tpf-lider/src/test/java/tpf/ChunkTest.java)**: ida e volta com acentos e emoji, padding, caso já múltiplo, imutabilidade, dados truncados.

**[ArquivoTest](tpf-lider/src/test/java/tpf/ArquivoTest.java)**: critérios CA1, CA3–CA8 de ponta a ponta numa pasta temporária, mais recuperação depois de uma queda (`abortar()` simula `kill -9`).

---

## 12. Perguntas prováveis na apresentação

**Porque é que se faz `fsync` antes de responder e não depois?**
Porque a resposta é uma promessa: "o teu registo tem o LSN 42 e não se perde". Se respondêssemos antes e a máquina caísse, o LSN 42 desaparecia e seria reutilizado por outro registo.

**`force(true)` vs `force(false)`?**
`true` também sincroniza os metadados (tamanho do ficheiro). Num append o tamanho muda; sem os metadados, depois de uma queda o ficheiro podia aparecer com o tamanho antigo.

**Porque é que `registar` é `synchronized`?**
Atribuir o LSN e escrever no log têm de ser atómicos e na mesma ordem. Sem lock, duas threads podiam ler o mesmo `ultimoLsn` ou escrever as linhas pela ordem errada.

**Porquê derivar uma DEK por chunk em vez de usar a MK diretamente?**
(1) Cada chunk tem a sua chave: comprometer uma DEK não expõe os outros chunks. (2) Com chaves diferentes, a repetição acidental de nonce não tem consequências. (3) A MK nunca é usada diretamente numa cifra. (4) `info` separa usos futuros da mesma MK.

**O que é que o AAD protege?**
Liga o `.hdr` ao `.bin`. Se alguém trocar o `keyVersion`, o `salt` ou o `nonce` no `.hdr`, a tag GCM deixa de bater certo.

**Se o GCM já deteta adulteração, para que serve o SHA-256 do id?**
Para quem **não tem a chave** (as réplicas, nos próximos sprints) conseguir verificar integridade, e para identificar o chunk de forma única e determinística (endereçamento por conteúdo). No líder é também uma verificação barata antes de decifrar.

**E se alguém alterar um byte e recalcular o SHA-256 e mudar o nome?**
Passa o passo (1) mas falha no (3): sem a DEK não consegue produzir uma tag válida. Ver o teste `byteAlteradoComIdRecalculadoFalhaNaAutenticacaoGcm`.

**Porque é que o `.bin` não é múltiplo de 4096?**
Porque tem a tag GCM de 16 bytes no fim. O que é múltiplo de 4096 é o texto com padding (que é o que o evento `SELADO` mostra em `bytes=`). Ver D3.

**Porquê padding?**
Para não revelar o tamanho exato dos registos a quem vê os ficheiros cifrados.

**O que acontece se apagarem a `master.key`?**
O líder gera uma nova no arranque e os chunks antigos deixam de abrir (GCM falha → `CHUNK_INVALIDO`). Os registos ainda estão em `registos.log` e são selados de novo com a chave nova. Se a `master.key` tiver tamanho errado, o líder recusa arrancar.

**O `registos.log` não é uma fuga de informação?**
É, em claro (Base64 não cifra). Neste sprint é o registo durável local do líder, que é de confiança. O que sai do líder (nos próximos sprints) são os chunks cifrados.

**Porque é que o temporizador recebe o chunk como argumento?**
Para não selar um chunk novo antes do tempo, se o chunk para o qual foi agendado já tiver sido selado por tamanho (§8).

**Como é que a rajada usa "o mesmo método de `/registar`"?**
Chama `arquivo.registar(autor, canal, texto)`, exatamente o mesmo que `/registar` e as mensagens. Passa pelo mesmo lock, log, `fsync`, chunk e eventos.

**Porque é que os chunks da rajada têm 120 registos?**
Cada registo tem 550 B serializado. 9 + 119 × 550 = 65 459 < 65 536; o 120.º faz 66 009 ≥ 65 536 e sela o chunk.

---

## 13. Limitações que se mantêm

- **Débito limitado pelo `fsync`:** um `fsync` por registo, dentro do lock. Para mais débito usar-se-ia *group commit* (juntar vários registos num só `fsync`), sem mudar a garantia.
- **Cifra dentro do lock:** a selagem de um chunk de 64 KB bloqueia novos registos durante alguns milissegundos. Podia passar para outra thread, mas complicava a ordem e a recuperação.
- **`registos.log` cresce sem limite** e está em claro. Compactação (apagar o que já está selado e replicado) fica para quando houver réplicas.
- **Uma única versão de chave (1):** a rotação de chaves não está implementada; a verificação da versão e o `keyVersion` no AAD já a preparam.
- **Falha na selagem:** os registos ficam no log e são selados no próximo arranque, mas não há nova tentativa enquanto o líder está a correr.
- **Arranque abre todos os chunks** para saber o que está selado. Com milhares de chunks seria lento; o catálogo/manifesto dos próximos sprints resolve isto.
- `/estado`, `/config`, `/recuperar`, `/replica-token` e `/replica-remover` continuam provisórios, como pede a ficha.

---

## 14. Resumo de uma página

- **Registo durável:** `Arquivo.registar` (sincronizado) → LSN seguinte → `registos.log` + `force(true)` → evento `REGISTO` → chunk aberto → resposta com o LSN. `/registar`, `#arquivo` e `/rajada` usam todos este método.
- **Arranque:** gera ou valida a `master.key` (32 B, só o dono lê); lê o log e continua o LSN; volta a pôr no chunk aberto o que não estava selado.
- **Chunk:** selado quando o tamanho sem padding chega a `CHUNK_MAX_BYTES` (o registo que passa o limite entra e sela) ou `SELAGEM_MS` depois do 1.º registo. Formato `TPFC | versão | n | registos`, padding a zeros até múltiplo de `PAD_BYTES`. Imutável depois de selado.
- **Cifra:** `salt`(32) e `nonce`(12) aleatórios; `DEK = HKDF-SHA256(MK, salt, "tpf-chunk-v1")`; AES-256-GCM com `aad = "tpf-v1"‖keyVersion‖salt‖nonce`; `chunkId = SHA-256(ct‖tag)`.
- **`open`:** hash → versão → GCM; qualquer falha lança exceção.
- **Disco:** `selados/<id>.bin` (ct‖tag) e `selados/<id>.hdr` (`1;salt;nonce`), escritos com tmp + `fsync` + rename atómico; evento `SELADO` com id, intervalo de LSN e tamanho.
- **Prova:** 37 testes JUnit, `VerificarSelados` (6/6 chunks, 501 registos) e `grep` sem resultados nos `.bin`.

**Glossário:** LSN – número de sequência do registo · MK – chave mestra · DEK – chave de cifra de dados (por chunk) · HKDF – função de derivação de chaves baseada em HMAC · GCM – modo de cifra autenticada · AAD – dados autenticados mas não cifrados · tag – código de autenticação de 16 B do GCM · nonce – valor único por cifra · padding – enchimento até um tamanho fixo.
