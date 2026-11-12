# COMINDIR05 · Guião dos ensaios (passos 9, 10 e 11)

Guião prático para executar os ensaios 9(a) a 9(e), as falhas deliberadas do passo 10 e preencher as tabelas do passo 11. O relatório está em [RELATORIO.md](RELATORIO.md).

> **Os valores medidos são teus.** As tabelas abaixo estão vazias de propósito. A secção [V](#v-validação-feita-com-ipfs-simulado-não-são-medições) mostra o que obtive ao validar o código com um IPFS **simulado**; serve para saberes o que esperar, não para copiar.

---

## I. Preparação

### I.1 Compilar (uma vez, e depois de cada alteração)

Em Git Bash, a partir de `Sprints/E1-COMINDIR05`:

```bash
scripts/compilar.sh
```

O script equivale a correr, em cada projeto:

```bash
cd raft04-no          && javac -encoding UTF-8 -d out src/raft04/*.java && cd ..
cd raft04-produtor    && javac -encoding UTF-8 -d out src/raft04/*.java && cd ..
cd raft04-processador && javac -encoding UTF-8 -d out src/raft04/*.java && cd ..
```

### I.2 Daemon IPFS (Kubo) com PubSub

```bash
ipfs daemon --enable-pubsub-experiment
# (em versões recentes do Kubo: ipfs config --json Pubsub.Enabled true ; ipfs daemon)
```

### I.3 Uma pasta de trabalho POR ENSAIO

Todos os processos escrevem os ficheiros **na pasta de onde são arrancados**. Usa uma pasta nova por ensaio (e por repetição): assim não é preciso apagar nada e o `RaftCheck` não mistura execuções.

```bash
mkdir -p ensaios/9a && cd ensaios/9a
S=../../scripts          # atalho para os scripts (a partir de ensaios/<x>)
```

### I.4 Arrancar os processos (ordem: nós → processador → produtor)

Cada processo num terminal, todos **na mesma pasta de trabalho**:

```bash
# os 9 nós (3 por partição), em primeiro plano, um por terminal:
$S/no.sh 0 1    $S/no.sh 0 2    $S/no.sh 0 3
$S/no.sh 1 1    $S/no.sh 1 2    $S/no.sh 1 3
$S/no.sh 2 1    $S/no.sh 2 2    $S/no.sh 2 3
# ...ou os 9 de uma vez, em segundo plano (consola em consola-p<k>-n<i>.log):
$S/nos.sh todos
# processador e produtor, cada um no seu terminal:
$S/processador.sh
$S/produtor.sh
```

Comandos Java equivalentes (sem scripts, a partir da pasta de trabalho `ensaios/<x>`):

```bash
java -cp ../../raft04-no/out          raft04.PartitionNode 1 2 3      # PartitionNode <k> <i> [N]
java -cp ../../raft04-processador/out raft04.AlarmProcessor 3
java -cp ../../raft04-produtor/out    raft04.Producer 3
```

As opções `-D` vão **antes** de `-cp`: `-Draft04.periodo=2000`, `-Draft04.eleicaoMin=6000`, `-Draft04.eleicaoMax=12000`, `-Draft04.fator=3`, `-Dfalha=1..5`. Os scripts aceitam-nas no fim: `$S/no.sh 1 2 -Dfalha=3`, `$S/nos.sh todos -Draft04.eleicaoMin=6000 -Draft04.eleicaoMax=6000`.

### I.5 Scripts de apoio (correm na pasta de trabalho)

| Script | O que faz | Equivalente em PowerShell |
|---|---|---|
| `$S/lider.sh 1` | mostra o último `LIDER` de cada nó de p1 e o líder mais recente (`-q` só dá o número do nó) | `Select-String ";LIDER;p1;" eventos-p1-n*.txt` |
| `$S/falhar.sh 1 2` | anota `p1;n2;<instante>` em `falhas.txt` e termina o nó à força (`kill -9`) | `Add-Content falhas.txt ("p1;n2;"+[DateTimeOffset]::Now.ToUnixTimeMilliseconds()); Stop-Process -Id (Get-Content pid-p1-n2.txt) -Force` |
| `$S/isolar.sh 1 2` | cria `isolar-p1-n2` (partição de rede) e anota em `particoes.txt` | `New-Item isolar-p1-n2` |
| `$S/reparar.sh 1 2` | apaga `isolar-p1-n2` e anota em `particoes.txt` | `Remove-Item isolar-p1-n2` |
| `$S/parar.sh` | termina todos os nós da pasta (lê `pid-*.txt`) | `Get-Content pid-*.txt \| % { Stop-Process -Id $_ -Force }` |
| `$S/verificar.sh` | `RaftCheck 3 . [falhas.txt]` + `CausalCheck 3 .` | ver abaixo |

```bash
# no fim de CADA ensaio (depois de parar os processos):
java -cp ../../raft04-no/out raft04.RaftCheck 3 . falhas.txt     # relatorio-raft.txt
java -cp ../../raft04-no/out raft04.CausalCheck 3 .              # relatorio-causal.txt
```

### I.6 Sensores e partições (N = 3)

| Partição | Sensores | Alarmes que lá caem |
|---|---|---|
| p0 | S01, S04, S07, S10 | ALARM-S03, ALARM-S06, ALARM-S09 |
| **p1** | **S02, S05, S08** | ALARM-S01, ALARM-S04, ALARM-S07, ALARM-S10 |
| p2 | S03, S06, S09 | ALARM-S02, ALARM-S05, ALARM-S08 |

O comando `rajada <n> <s> [intervaloMs]` aceita um 3.º argumento opcional, porque a rajada da ficha anterior envia tudo em menos de 1 s. Nos ensaios usa `rajada 300 10 200`: cerca de 60 s e uns 90 envios para p1.

### I.7 O que aparece na consola dos nós

| Linha | Significado |
|---|---|
| `EVENTO …;CANDIDATO;p1;termo=2` | o prazo expirou: termo +1, voto em si, `PEDIDO_VOTO` |
| `EVENTO …;VOTO;p1;termo=2;candidato=n3;concedido` | este nó votou (ou `recusado`) |
| `EVENTO …;LIDER;p1;termo=2;votos=[1, 3]` | maioria obtida |
| `LÍDER de p1 no termo 2: seq=2 L=… V=…` | inicialização de seq, L e V a partir do último registo |
| `HEARTBEAT: termo=2 hb=5 n=0 ultimoSeq=3 ultimoTermo=2` | lote publicado (linha em `hb-p1-n3.txt`) |
| `EVENTO …;NOVO_LIDER;p1;termo=2;lider=n3` | este nó reconheceu um líder |
| `EVENTO …;TERMO;p1;1->2` / `DEMISSAO;p1;termo=1` | adotou um termo superior / deixou de ser líder |
| `EVENTO …;TRUNCADO;p1;de=3;descartados=1;termos=[1]` | divergência resolvida por truncagem |
| `EVENTO …;IGNORADO;p1;IN;…` | um `IN` chegou a um nó que não é líder |
| `EVENTO …;IGNORADO;p1;LOTE;termo=1;…;meuTermo=2` | lote de um líder obsoleto |
| `ISOLADO: lote hb=… não publicado` | líder isolado: continua a julgar-se líder, mas ninguém o ouve |

---

## II. Ensaios do passo 9

Em todos: pasta nova, arranque pela ordem de I.4, `$S/parar.sh` no fim, e depois `$S/verificar.sh` (ou os dois comandos `java` de I.5). Guarda `relatorio-raft.txt` e `relatorio-causal.txt` de cada ensaio.

### 9(a) · Reproduzir a Figura 1 em p1

| # | Ação | O que observar |
|---|---|---|
| 1 | Arranca os 9 nós, o processador e o produtor | nos 6–12 s seguintes, uma linha `CANDIDATO` e uma `LIDER` por partição; nos outros nós, `VOTO … concedido` e `NOVO_LIDER` |
| 2 | `$S/lider.sh 1` → anota o líder (ex.: n2) | |
| 3 | No produtor: `S02 20.1` e depois `S05 23.7` | na consola do líder, `ATRIBUIDO: seq=1 termo=1` e `seq=2` |
| 4 | Espera pelo próximo `HEARTBEAT` do líder com `n=` > 0 (ou outro qualquer) | |
| 5 | **Logo a seguir** a um `HEARTBEAT`: `S02 20.4` e, **menos de 1 s depois**, `$S/falhar.sh 1 <líder>` | `ATRIBUIDO: seq=3` no líder, que morre antes do lote seguinte (seq 3 fica **pendente**) |
| 6 | Durante a eleição: `S05 23.9` | o produtor escreve `AVISO: partição p1 sem líder conhecido…` (se já estiver SUSPEITO); os seguidores assinalam `IGNORADO;p1;IN` |
| 7 | Espera por `LIDER` num dos sobreviventes; depois `S02 20.6` | novo líder com `seq=2` na inicialização; `ATRIBUIDO: seq=3 termo=2` |
| 8 | Volta a arrancar o antigo líder (`$S/no.sh 1 <líder>`) | `ARRANQUE;…;termo=1;…;ultimoSeq=3;ultimoTermo=1`, `TERMO;p1;1->2`, `NOVO_LIDER`, `TRUNCADO;p1;de=3;descartados=1;termos=[1]` |
| 9 | Espera ~5 s, para tudo, verifica | `RaftCheck` §8: `seq divergentes=0` |

**Registar:** tempo até ao primeiro líder (§3), indisponibilidade (§5), leituras perdidas e de que tipo (§6: `S05 23.9` durante a eleição e `S02 20.4` por truncagem), `TRUNCADO` (§7), coincidência final (§8), `CausalCheck` OK.

> Se o seq 3 aparecer nos três ficheiros sem truncagem, o lote saiu antes da falha. Repete o passo 5 mais depressa a seguir a um `HEARTBEAT`.

### 9(b) · Eleição no arranque, 10× aleatório e 5× prazo fixo

Prazo aleatório, 10 execuções, cada uma na sua pasta (`ensaios/9b-a01` … `9b-a10`):

```bash
$S/nos.sh aleatorio        # os 9 nós por ordem aleatória, com pausas de 0 a 2 s
# espera ~25 s
$S/parar.sh; $S/verificar.sh | grep RESUMO
```

Prazo fixo, 5 execuções (`ensaios/9b-f01` … `9b-f05`). Os três nós de cada partição arrancam no mesmo instante:

```bash
$S/nos.sh todos -Draft04.eleicaoMin=6000 -Draft04.eleicaoMax=6000
# espera ~40 s (com voto dividido pode demorar vários termos)
$S/parar.sh; $S/verificar.sh | grep RESUMO
```

**O que observar:** a linha `RESUMO;p<k>;primeiroLider_s=…;termoPrimeiroLider=…;termosAtePrimeiroLider=…;votosDivididosAntes=…` de cada partição. Em §1, um **voto dividido** aparece como `termo t: candidatos n1=1 voto(s) [1] n2=1 voto(s) [2] … -> sem líder: VOTO DIVIDIDO`. Nos `eventos-*`, um voto dividido nota-se por dois ou mais `CANDIDATO` com o mesmo termo, `VOTO … recusado` (cada nó já votou em si) e nenhum `LIDER` nesse termo.

| Execução | Ordem de arranque | p0: 1.º líder (s) / termos | p1: 1.º líder (s) / termos | p2: 1.º líder (s) / termos | Votos divididos |
|---|---|---|---|---|---|
| a01 | | | | | |
| a02 | | | | | |
| a03 | | | | | |
| a04 | | | | | |
| a05 | | | | | |
| a06 | | | | | |
| a07 | | | | | |
| a08 | | | | | |
| a09 | | | | | |
| a10 | | | | | |
| f01 (fixo) | simultâneo | | | | |
| f02 (fixo) | simultâneo | | | | |
| f03 (fixo) | simultâneo | | | | |
| f04 (fixo) | simultâneo | | | | |
| f05 (fixo) | simultâneo | | | | |

### 9(c) · Falha do líder de p1 durante uma rajada (×3)

Cada repetição na sua pasta (`ensaios/9c-1`, `9c-2`, `9c-3`):

| # | Ação | O que observar |
|---|---|---|
| 1 | Arranca tudo e espera pelos três líderes | |
| 2 | No produtor: `rajada 300 10 200` | |
| 3 | ~10 s depois: `$S/falhar.sh 1 $($S/lider.sh 1 -q)` | seguidores de p1: `CANDIDATO` 4–12 s depois, `LIDER`; processador e produtor: `SUSPEITA;p1` e depois `NOVO_LIDER;p1;termo=1->2` |
| 4 | 30 s depois: `$S/no.sh 1 <nó morto>` | antigo líder: `TERMO`, `NOVO_LIDER` e, se tinha registos pendentes, `TRUNCADO` |
| 5 | Espera pelo fim da rajada + 10 s; para e verifica | |

**Registar (RaftCheck):** §5 `indisponibilidade = … s` e `leituras enviadas nesse intervalo=…, das quais perdidas=…`; §6 perdidas por tipo; §7 descartados.

| Repetição | Indisponibilidade (s) | Leituras enviadas a p1 na indisponibilidade | Perdidas durante a eleição | Perdidas por truncagem | Registos truncados | Cabe em ]4 s; 12,2 s]? |
|---|---|---|---|---|---|---|
| 1 | | | | | | |
| 2 | | | | | | |
| 3 | | | | | | |
| **média** | | | | | | |

### 9(d) · Partição de rede: isolar o líder e depois um seguidor

Parte 1 (com rajada), pasta `ensaios/9d`:

| # | Ação | O que observar |
|---|---|---|
| 1 | Arranca tudo; `rajada 300 10 200` | |
| 2 | ~8 s depois: `L=$($S/lider.sh 1 -q); $S/isolar.sh 1 $L` | líder isolado: `ISOLADO: lote … não publicado` e **continua a atribuir seq** (`ATRIBUIDO … termo=1`), porque o tópico de entrada não é afetado |
| 3 | | outros dois: `CANDIDATO`/`LIDER` num termo novo: **há dois nós que se julgam líderes**, em termos diferentes |
| 4 | 30 s depois do isolamento: `$S/reparar.sh 1 $L` | antigo líder: `TERMO;p1;1->2`, `DEMISSAO;p1;termo=1`, `NOVO_LIDER`, `TRUNCADO;…;descartados=<muitos>` |
| 5 | Espera pelo fim da rajada | processador: em `monitor-processador.txt`, `NOVO_LIDER;p1;termo=1->2` (nunca viu os lotes do líder isolado) |

Parte 2 (sem rajada), na mesma pasta ou numa nova:

| # | Ação | O que observar |
|---|---|---|
| 6 | Escolhe um **seguidor** F de p1; `$S/isolar.sh 1 F` | F: `CANDIDATO` a cada 6–12 s, termo sempre a subir, sem votos |
| 7 | 60 s depois: `$S/reparar.sh 1 F` | líder: `TERMO;p1;t->t'` e `DEMISSAO` (provocada pelo `PEDIDO_VOTO` de F, com termo mais alto); depois, nova eleição. F não é eleito, porque o registo dele está atrasado |

**Registar:** RaftCheck §4 `LÍDERES SIMULTÂNEOS: n… (termo …) e n… (termo …) de … a …` e `registos atribuídos nesse intervalo e depois truncados`; §7 descartados e quantos existem com outro seq no líder final; §6 perdidas por truncagem; §1 o termo máximo atingido pelo seguidor isolado; §4 o `DEMISSAO` do líder saudável; §9 o que viu o processador.

| Grandeza (9(d)) | Valor medido |
|---|---|
| Líderes simultâneos (nós e termos) | |
| Duração da liderança simultânea (s) | |
| Leituras atribuídas pelo antigo líder durante o isolamento | |
| Registos truncados após a reparação | |
| … dos quais existem com outro seq no novo líder | |
| Leituras perdidas por truncagem | |
| O que o processador observou (`monitor-processador.txt`) | |
| Termo atingido pelo seguidor isolado (60 s) | |
| Efeito do regresso do seguidor no líder (termo antes → depois, `DEMISSAO`?) | |

### 9(e) · Falha de dois dos três nós de p1

| # | Ação | O que observar |
|---|---|---|
| 1 | Arranca tudo e espera pelos líderes | |
| 2 | `$S/falhar.sh 1 <a>; $S/falhar.sh 1 <b>` (dois nós de p1, o líder incluído) | |
| 3 | Durante 30 s, envia leituras a p1: `S02 20.0`, `S05 21.0`, `S08 22.0`, … (ou `rajada 90 10 333`) | sobrevivente: `CANDIDATO;termo=t+1`, `t+2`, … a cada 6–12 s, **sem `LIDER`** (1 voto não é maioria); `IGNORADO;p1;IN` para cada leitura; produtor: `AVISO: partição p1 sem líder conhecido` |
| 4 | Volta a arrancar um dos nós terminados | o nó que regressa adota o termo do sobrevivente; há maioria (2/3) e elege-se líder |
| 5 | Espera 10 s; para e verifica | |

**Registar:** §1 os termos e candidaturas do sobrevivente (`sem líder: candidato sem maioria`); §4b `ARRANQUE … : líder n… (termo …) X s depois`; §6 todas as leituras dos 30 s perdidas.

| Grandeza (9(e)) | Valor medido |
|---|---|
| Termos percorridos pelo sobrevivente (de → até) | |
| Número de candidaturas sem maioria | |
| Leituras enviadas a p1 nos 30 s / perdidas | |
| Tempo desde o arranque do nó até haver líder (s) | |
| Quem foi eleito e em que termo | |

---

## III. Falhas deliberadas do passo 10

Cada falha liga-se com `-Dfalha=<n>` **em todos os nós da partição ensaiada**. Sem a opção corre o código correto, por isso não é preciso repor nada entre falhas. Usa uma pasta nova para cada uma.

| # | Falha | Como provocar | O que procurar |
|---|---|---|---|
| 1 | maioria `>= groupSize/2` | `$S/nos.sh "1:1 1:2 1:3" -Dfalha=1 -Draft04.eleicaoMin=6000 -Draft04.eleicaoMax=6000` | cada candidato elege-se sozinho: RaftCheck §1 `VIOLAÇÃO: 2 líderes no mesmo termo`, §2 `termos com mais de um LIDER ≥ 1`, eventos `ERRO;…;dois lideres` |
| 2 | estado só em memória | 3 nós com `-Dfalha=2`; mata o líder; quando um seguidor registar `VOTO … concedido` no termo novo, mata-o e arranca-o logo (`$S/falhar.sh 1 X; $S/no.sh 1 X -Dfalha=2`) | §2 `diminuições do termo num nó` (o nó volta com `ARRANQUE;…;termo=0`); com azar, dois `LIDER` no mesmo termo |
| 3 | sem `upToDate` | 3 nós de p1 com `-Dfalha=3`. Isola um **seguidor** S (`$S/isolar.sh 1 S`); envia as leituras de 9(a) (`S02 20.1`, `S05 23.7`, `S02 20.4`), que o líder e o outro seguidor guardam; espera ~20 s (o termo de S sobe) e repara S | o `PEDIDO_VOTO` de S (termo alto, `ultimo=(0,0)`) demite o líder e recebe `VOTO … concedido` apesar do registo vazio; S fica líder e publica `ultimoSeq=0`; os outros dois assinalam `TRUNCADO;…;de=1` e perdem registos que **todos** os que não estavam isolados tinham: §7 `descartados` > 0, §6 perdidas por truncagem, §1 líder com votos de nós mais atualizados. Sem a falha, S recebe `recusado` |
| 4 | aplica lotes de termo inferior | repete 9(d) com `-Dfalha=4` nos três nós de p1. **Ao reparar o antigo líder, isola o novo líder durante ~5 s** (`$S/isolar.sh 1 <novo>; $S/reparar.sh 1 <antigo>; sleep 5; $S/reparar.sh 1 <novo>`). Se só reparares o antigo, ele costuma receber o lote do novo líder e demitir-se antes de publicar, e a falha não se vê (3 em 3 vezes no IPFS simulado) | o seguidor aplica lotes do termo antigo: `RECEBIDO;p1;termo=1` num nó com termo 2, seguido de `TRUNCADO` dos registos **bons** do termo 2. RaftCheck §2 `VIOLAÇÃO: termo de n… diminuiu 2 -> 1 (RECEBIDO …)`; §7 truncagens a mais. A coincidência final pode até ficar certa, porque o novo líder repõe tudo quando volta |
| 5 | L e V a zero no novo líder | `-Dfalha=5`; mata o líder depois de algumas leituras e envia mais leituras ao novo líder | `CausalCheck` §1 `V[k]!=seq` e `L não crescente` > 0; eventos `ERRO;…;V[k]=… != seq=…` |

| Falha introduzida | Lado onde surgiu | Detetada por `RaftCheck` / `CausalCheck`? | O que permitiu concluir |
|---|---|---|---|
| Maioria calculada como `votes.size() >= groupSize / 2` | | | |
| Termo e `votedFor` mantidos só em memória; um seguidor é reiniciado logo após conceder um voto, durante uma eleição | | | |
| Voto concedido sem a verificação `upToDate`, com o ensaio 9(a) repetido de modo a que o nó atrasado seja candidato | | | |
| Seguidor que aplica lotes de termo inferior ao seu, com o ensaio 9(d) repetido | | | |
| Novo líder que inicializa *L* e *V* a zero em vez de a partir do último registo | | | |

---

## IV. Tabelas do passo 11 (preencher com valores medidos)

A primeira tabela usa 9(a) a 9(c) (média das execuções) e a segunda usa 9(c) a 9(e).

| Métrica | Valor | De onde vem |
|---|---|---|
| Tempo até ao primeiro líder no arranque (mínimo / médio / máximo, s) | | `RESUMO … primeiroLider_s` de 9(a) e 9(b) aleatório |
| Termos até ao primeiro líder: prazo aleatório / prazo fixo | | `termosAtePrimeiroLider` de 9(b) |
| Votos divididos: prazo aleatório / prazo fixo | | `votosDivididosAntes` / §1 de 9(b) |
| Indisponibilidade após falha do líder (mínimo / médio / máximo, s) | | §5 de 9(a) e 9(c) |
| Leituras perdidas por falha: durante a eleição / por truncagem | | §6 de 9(a) e 9(c) |
| Registos truncados ao regressar o antigo líder | | §7 de 9(a) e 9(c) |
| Termos com mais de um líder | | §2 de todos |
| `seq` divergentes na coincidência final | | §8 de todos |

| Cenário (ensaio) | Lado maioritário aceita escritas? | Lado minoritário aceita escritas? | Escritas do lado minoritário após a reparação | Termos envolvidos | Comportamento: C ou A? |
|---|---|---|---|---|---|
| Falha do líder (c) | | | | | |
| Isolamento do líder (d) | | | | | |
| Isolamento de um seguidor (d) | | | | | |
| Falha de dois nós (e) | | | | | |

---

## V. Validação feita com IPFS simulado (não são medições)

Para confirmar que o código funciona de ponta a ponta, corri os processos contra o `MockIpfs` da pasta `E1-COMINDIR02/teste`, que implementa `pubsub/pub` e `pubsub/sub` no formato do Kubo, com os parâmetros da ficha (Δ = 2 s, prazo em [6 s, 12 s]). **Estes valores não substituem os teus ensaios com o daemon real.**

| Cenário | O que se observou |
|---|---|
| Figura 1 (9(a)) | líder n1 no termo 1; seq 3 pendente na falha; n3 eleito no termo 2 com indisponibilidade de 6,97 s; `S05 23.9` perdida durante a eleição e `S02 20.4` perdida por truncagem; n1 regressou com `TERMO 1->2`, `NOVO_LIDER n3`, `TRUNCADO de=3 descartados=1 termos=[1]`; 0 seq divergentes; `CausalCheck` OK |
| Isolamento do líder com `rajada 300 10 200` (9(d)) | dois líderes simultâneos (n1 no termo 1 e n2 no termo 2) durante 26,0 s; n1 truncou 50 registos, 41 dos quais existem com outro seq no novo líder; 8 leituras perdidas por truncagem; 0 seq divergentes |
| Isolamento de um seguidor 45 s (9(d)) | o seguidor foi do termo 2 ao 8 sem votos; ao regressar fez o líder demitir-se (`DEMISSAO termo=2`); novo líder no termo 9 |
| Dois nós mortos (9(e)) | o nó que voltou fez maioria com o sobrevivente; líder 2,8 s depois do arranque |
| `-Dfalha=1` com prazo fixo | dois líderes no termo 1, detetados pelo RaftCheck (§1 e §2) |
| `-Dfalha=3` (seguidor isolado com registo vazio) | o nó vazio foi eleito no termo 4 com 3 votos; os outros dois truncaram `de=1 descartados=3`; as 3 leituras perderam-se |
| `-Dfalha=4` | só repará-lo: não se manifestou em 3 execuções. Com o novo líder isolado 5 s: 2 `RECEBIDO` de termo 1 num nó de termo 2 (RaftCheck §2) e 19 registos bons truncados |
| `-Dfalha=5` | `CausalCheck`: 4 violações (`V[k]!=seq` e `L` não crescente) no seq 3 do termo 2 |
