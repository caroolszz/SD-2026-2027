# E1-UDP02 · Relatório

## 1. Custo da solução anterior (ponto 1)

Na sequência `1, 3, 4, 5, 2`, na UDP01 as mensagens `3`, `4` e `5` chegam antes da mensagem esperada `2`. Como a UDP01 descarta essas mensagens, depois de a mensagem `2` chegar é necessário retransmitir as mensagens `3`, `4` e `5`. Assim, existem 3 retransmissões causadas pela desordenação.

Na UDP02, as mensagens `3`, `4` e `5` são guardadas na estrutura temporária. Quando a mensagem `2` chega, o servidor entrega `2`, `3`, `4` e `5` em cascata. Assim, não são necessárias retransmissões das mensagens `3`, `4` e `5`.

## 2. Estruturas de dados (ponto 2)

Para a lista de receção é utilizada uma `ArrayList<String>`. Esta lista guarda as mensagens que já foram entregues pela ordem correta. A operação mais frequente é adicionar uma nova mensagem no final da lista, por isso uma `ArrayList` é adequada.

Para a estrutura temporária é utilizado um `HashMap<Integer, String>`. A chave é o número da mensagem e o valor é o conteúdo da mensagem. Esta estrutura permite verificar se uma determinada mensagem, como `L + 1`, já foi recebida e está guardada temporariamente. Quando essa mensagem pode ser entregue, é possível obtê-la e removê-la da estrutura.

As duas estruturas são necessárias porque uma mensagem pode já ter sido recebida mas ainda não ter sido entregue. A lista de receção representa as mensagens entregues, enquanto a estrutura temporária representa as mensagens recebidas fora de ordem.

## 3. Verificação

### 3.1 Cenário normal (ponto 8)

Ao enviar as mensagens `1`, `2` e `3` pela ordem correta, cada mensagem é entregue imediatamente. A variável `L` avança de `0` para `1`, depois para `2` e finalmente para `3`.

A estrutura temporária permanece sempre vazia (`{}`), porque nenhuma mensagem chega antes da mensagem que o servidor está à espera. As mensagens são diretamente adicionadas à lista de receção pela ordem `1`, `2`, `3`.

### 3.2 Desordenação múltipla (pontos 9 e 10)

| Mensagem enviada | Resposta recebida | L após processamento | Estrutura temporária | Mensagens entregues neste passo | Justificação |
|---|---|---:|---|---|---|
| 1, olá | `1,olá` | 1 | `{}` | [1, olá] | A mensagem 1 é a próxima esperada, pois L=0. É entregue diretamente e L passa para 1. |
| 3, mundo | `waitingfor,2` | 1 | `{3=mundo}` | [] | A mensagem 3 chegou antes da 2. Como não é a próxima esperada, fica guardada temporariamente. O servidor continua à espera da 2. |
| 4, tudo bem | `waitingfor,2` | 1 | `{3=mundo, 4=tudo bem}` | [] | A mensagem 4 também chegou adiantada. É guardada na estrutura temporária e L não é alterado. |
| 2, cruel | `2,cruel` | 4 | `{}` | [2, cruel; 3, mundo; 4, tudo bem] | A mensagem 2 é a próxima esperada. Depois de a entregar, o servidor verifica a estrutura temporária e encontra 3 e 4, entregando-as em cascata. |
| 3, mundo | `waitingfor,5` | 4 | `{3=mundo}` | [] | A mensagem 3 já tinha sido entregue durante a cascata. Como chega novamente, não é a próxima esperada relativamente a L=4 e volta a ficar na estrutura temporária. |

### 3.3 Estado final (ponto 11)

No final, a lista de receção contém as mensagens pela ordem correta:

`[1, olá, 2, cruel, 3, mundo, 4, tudo bem]`

A estrutura temporária contém:

`{3=mundo}`

A mensagem 3 que ficou na estrutura temporária é um duplicado, porque a mensagem 3 original já tinha sido entregue durante a cascata iniciada pela chegada da mensagem 2.

Esta mensagem não volta a sair da estrutura temporária com o algoritmo atual. O `while` procura sempre a próxima mensagem esperada, ou seja, `L + 1`. Como no final L=4, procura a mensagem 5 e não a mensagem 3.

Seria necessária uma verificação para detetar que uma mensagem recebida já foi entregue antes de a colocar na estrutura temporária.

## 4. Reflexão crítica (ponto 12)

### 4.1 Retransmissões na sequência 1, 3, 4, 5, 6, 2

Na UDP01, as mensagens 3, 4, 5 e 6 chegam enquanto o servidor espera a mensagem 2. Como são descartadas, depois da chegada da 2 é necessário retransmitir as mensagens 3, 4, 5 e 6. Assim, existem 4 retransmissões causadas pela desordenação.

Na UDP02, as mensagens 3, 4, 5 e 6 são guardadas na estrutura temporária. Quando a mensagem 2 chega, o servidor entrega 2, 3, 4, 5 e 6 em cascata. Assim, não são necessárias retransmissões das mensagens que chegaram fora de ordem.

Se a própria mensagem 2 se perder, será necessária uma retransmissão da 2. Nesse caso, considerando também essa retransmissão, seriam 5 retransmissões na UDP01 e 1 na UDP02.

### 4.2 Mensagem em falta que nunca chega

Se a mensagem 2 se perder e nunca chegar, o cliente recebe respostas `waitingfor,2` quando envia mensagens posteriores, como 3, 4, 5 e 6.

O cliente pode usar essa resposta para saber qual é a mensagem que o servidor está à espera e retransmitir a mensagem 2.

Um mecanismo concreto para resolver este problema seria utilizar um timeout e retransmissão: se o cliente não receber a confirmação esperada dentro de determinado período, volta a enviar a mensagem em falta.

Se a mensagem 2 continuar sem chegar, as mensagens seguintes permanecem na estrutura temporária e não são entregues.

### 4.3 Mensagem número 1 000 000

Uma mensagem muito adiantada, como a número 1 000 000, pode ser recebida e colocada na estrutura temporária mesmo que L ainda seja muito inferior.

Para limitar as mensagens aceites, poderia ser definida uma janela em função de L. Por exemplo, aceitar apenas mensagens que satisfaçam:

`L < N <= L + W`

onde W representa o tamanho máximo da janela.

Assim, se L=10 e W=100, seriam aceites mensagens de 11 até 110. Uma mensagem como 1 000 000 ficaria fora da janela e não seria colocada na estrutura temporária.

### 4.4 Duplicados

Existem dois casos diferentes de duplicados.

O primeiro acontece quando a mensagem já foi entregue. No teste `1, 3, 4, 2, 3`, a segunda mensagem `3` chega quando L já é 4. A mensagem 3 já tinha sido entregue durante a cascata e, por isso, o algoritmo atual volta a colocá-la na estrutura temporária. Falta uma verificação que detete que `N <= L`.

O segundo caso acontece quando uma mensagem chega duas vezes enquanto ainda está na estrutura temporária.

Por exemplo, considerando `L=1`:

- chega `3,mundo` → fica guardada como `{3=mundo}`;
- chega novamente `3,mundo` antes de chegar a mensagem 2.

Nesse caso, a mesma mensagem já existe na estrutura temporária. O algoritmo deveria verificar se a chave 3 já existe antes de a adicionar novamente. Caso contrário, a segunda chegada pode substituir a primeira ou ser tratada como um duplicado sem necessidade de criar uma nova entrada.

Assim, são necessárias verificações tanto para mensagens já entregues como para mensagens que já estão na estrutura temporária.

### 4.5 Características do UDP resolvidas / por resolver

A solução da UDP02 resolve, ao nível da aplicação, o problema da desordenação das mensagens. As mensagens que chegam adiantadas deixam de ser imediatamente descartadas e ficam temporariamente armazenadas até ser possível entregá-las pela ordem correta.

A duplicação, a perda e a corrupção continuam a ser características do UDP que devem ser consideradas:

- **Perda:** continua possível uma mensagem nunca chegar. Nesse caso, as mensagens seguintes podem permanecer retidas à espera dela.
- **Duplicação:** o UDP pode entregar o mesmo datagrama mais do que uma vez. O algoritmo atual ainda precisa de verificações para evitar duplicados já entregues ou que já estejam na estrutura temporária.
- **Corrupção:** o UDP utiliza um checksum para detetar erros nos dados do datagrama. A deteção de corrupção é uma característica do próprio UDP, mas a solução apresentada não implementa uma recuperação ao nível da aplicação caso um datagrama seja considerado inválido.

Assim, a UDP02 trata a desordenação ao nível da aplicação, mas não elimina as características de perda, duplicação e possível corrupção associadas à comunicação através de UDP.