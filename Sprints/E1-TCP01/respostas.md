# TCP01 · Respostas

Projetos: `tcp01-servidor` e `tcp01-cliente` (pacote `tcp01`, porto 7896). Estado final = etapa 4.3 (cliente envia `Person` com `Place`, servidor devolve a localidade). Resultado: `Received: Viseu`.

Compilar e correr (em cada projeto, dois terminais, servidor primeiro):
`javac -d out src/tcp01/*.java` · `java -cp out tcp01.TCPServer` / `java -cp out tcp01.TCPClient`

## 2.2 Diagnóstico
(a) O TCP garante entrega íntegra (checksums), ordenada (numeração) e com retransmissão se não houver confirmação. O UDP não garante nenhuma destas: datagramas podem perder-se, duplicar-se, chegar fora de ordem ou corrompidos sem a aplicação ser avisada.
(b) Ficam bloqueados (a thread pára) até haver uma ligação / dados completos, ou até lançarem exceção se a ligação fechar (`EOFException`).
(c) A stream só transporta bytes, por isso o objeto tem de ser codificado (serialização). Quem lê precisa da classe (mesmo nome completo e mesmo `serialVersionUID`) para saber reconstruir o objeto e os seus campos.

## 4.1 Linhas que bloqueiam
| Ficheiro | Linha | Espera por |
|---|---|---|
| TCPServer | `listenSocket.accept()` | que um cliente se ligue |
| Connection (construtor) | `new ObjectInputStream(...)` | o cabeçalho que o `ObjectOutputStream` do cliente escreve ao ser criado |
| Connection.run | `in.readObject()` (era `readUTF()` em 4.1) | o objeto/mensagem completo |
| TCPClient | `new Socket(...)` | conclusão do handshake (falha logo se não há servidor) |
| TCPClient | `in.readUTF()` | a resposta do servidor |

Servidor parado + cliente: falha em `new Socket("localhost", 7896)` com `java.net.ConnectException: Connection refused`.

## 4.2 / 4.3 Decisões
- `Person` e `Place` existem **nos dois projetos**, no pacote `tcp01`, ambas `Serializable` com `serialVersionUID = 1L`: o cliente precisa delas para criar e escrever, o servidor para reconstruir e fazer o cast.
- Cliente: `ObjectOutputStream` (saída) + `DataInputStream` (entrada). Servidor: `ObjectInputStream` (entrada) + `DataOutputStream` (saída). Cada sentido usa um só tipo de stream; só um sentido transporta objetos, logo não há bloqueio mútuo nos cabeçalhos.
- Cast `(Person) readObject()` com `catch (ClassNotFoundException)`.
- O `Place` viaja porque `writeObject` percorre o grafo alcançável, desde que `Place` seja `Serializable`.

### Duas variantes (4.3), testadas
| Variante | Alteração | Resultado |
|---|---|---|
| Continua a funcionar | método novo (`extra()`) só na `Person` do cliente, ou atributo novo, com o mesmo `serialVersionUID` | `Received: Viseu`. Com UID explícito, métodos novos não afetam; o atributo novo é ignorado pelo servidor |
| Deixa de funcionar | `serialVersionUID` 2 num lado, 1 no outro | `InvalidClassException: tcp01.Person; local class incompatible: stream classdesc serialVersionUID = 1, local class serialVersionUID = 2`, surge no **servidor**, em `readObject` (se o UID alterado for o do servidor; se for o do cliente, o servidor também é quem rejeita) |

Sem UID declarado, o atributo/método novo mudaria o UID calculado e daria também `InvalidClassException`.

## 4.4 Falhas introduzidas (executadas)
| Falha | Lado | Exceção | Momento | O que permitiu concluir |
|---|---|---|---|---|
| Cliente sem servidor | cliente | `ConnectException: Connection refused` | ligação (`new Socket`) | ninguém à escuta nesse porto/host; nada sobre dados |
| Sem `Serializable` no `Place` | cliente (causa) e servidor (consequência) | cliente: `NotSerializableException: tcp01.Place`; servidor: `WriteAbortedException: writing aborted; java.io.NotSerializableException: tcp01.Place` | escrita (cliente), leitura (servidor) | o servidor, aqui, mostra a causa na mensagem, mas é o cliente que tem de corrigir |
| `serialVersionUID` diferente | servidor | `InvalidClassException ... serialVersionUID = 1, local ... = 2` | leitura (`readObject`). O cliente vê só `SocketException`/`EOFException` | as versões são incompatíveis; o cliente, sozinho, não diz porquê |
| `Person` noutro pacote no servidor | servidor | `ClassNotFoundException: tcp01.Person` | leitura | o nome completo (pacote incluído) faz parte da identidade; o cliente vê só `EOFException: null` |

## 4.4 Construções
| Construção | Onde | Garantido | Não garantido |
|---|---|---|---|
| `ServerSocket`/`accept()` | `TCPServer` | uma ligação dedicada (`Socket`) por cliente | que o cliente envie algo; que o serviço escale |
| `Connection extends Thread` | `Connection` | clientes atendidos em paralelo; `accept()` nunca fica preso a um pedido | limite de threads, nem sincronização de dados partilhados |
| `implements Serializable` | `Person`, `Place` | a classe pode ser codificada em bytes | que o outro lado a tenha ou a compreenda; que dados sensíveis não sejam enviados |
| `ObjectOutputStream`/`ObjectInputStream` | cliente / servidor | transporte do grafo de objetos completo | tipo certo do objeto recebido (cast pode falhar) |
| `serialVersionUID` | `Person`, `Place` | rejeição explícita de versões incompatíveis | compatibilidade automática; é manual |
| Referência para `Place` | `Person` | o `Place` chega junto, sem escrita explícita | controlo sobre o volume nem sobre o que vai no grafo |

## 4.5 Reflexão crítica
1. Problemas que não eram de transmissão: `NotSerializableException`, `InvalidClassException`, `ClassNotFoundException`, cast inválido. Os bytes chegavam intactos; a aplicação (quem define as classes e as versões) tinha de os resolver.
2. Clientes antigos com a `Person` antiga enviariam objetos que o servidor novo rejeita (UID diferente) — o `serialVersionUID` torna a incompatibilidade explícita em vez de silenciosa, mas obriga a atualizar os clientes ou a gerir versões à mão.
3. Riscos: volume (objetos grandes ou ligados a muitos outros viajam inteiros) e fuga de informação (todos os campos alcançáveis vão, incluindo os sensíveis). Com os mecanismos desta ficha não há como excluir um campo.
4. Uma thread por ligação: o servidor ganha paralelismo e deixa de ficar bloqueado por um cliente lento; gasta memória (stack por thread), mudanças de contexto e tem limite prático de threads. Um cliente-de-cada-vez é simples e barato, mas serializa os pedidos.
5. Datagramas continuam preferíveis para: jogos online (posição atual importa mais que a antiga), pedidos DNS (curtos, retransmitidos pela aplicação), sensores a enviar leituras frequentes, onde uma leitura perdida é substituída pela seguinte.

## CA5 · Limitações
- Atributo que não deve ser enviado (palavra-passe): sem `transient` (fora do âmbito), não se consegue. Teria de se enviar um objeto diferente sem esse campo.
- Servidor noutra linguagem: não lê a serialização nativa Java; é um formato específico da JVM, logo o alcance é limitado a Java. Seria necessário um formato neutro (fora do âmbito desta ficha).
- Dez mil clientes: dez mil threads, com risco de esgotar memória; a solução (pool, NIO) está fora do âmbito.
- Validação do `year`: o servidor aceita qualquer valor; a verificação tem de ser feita no servidor, depois de ler o objeto (nunca confiar no cliente). Pode também ser feita no construtor/`set`, mas a desserialização não passa pelo construtor.
