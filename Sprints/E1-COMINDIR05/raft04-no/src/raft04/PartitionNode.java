package raft04;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// NOVO na COMINDIR05. Nó i (1..3) do grupo que serve a partição de dados k.
// Executado como: PartitionNode <k> <i> [N]          (N = número de partições, por omissão 3)
// Substitui o PartitionLeader e a PartitionReplica da COMINDIR04: o mesmo processo pode ser
// SEGUIDOR (aplica os lotes, como a réplica), CANDIDATO (pede votos) ou LÍDER (atribui seq e
// publica lotes, como o líder fixo). As regras da eleição estão todas em RaftState.
//
// Threads:
//  - entrada  : subscreve raft04-in-p<k>; só o líder atribui seq, os outros assinalam IGNORADO
//  - dados    : subscreve raft04-p<k>; aplica os LOTE do líder do termo atual
//  - eleicao  : subscreve raft04-eleicao-p<k>; responde a PEDIDO_VOTO e conta os VOTO
//  - verificacao: a cada VERIFICACAO_MS chama tick(); se o prazo expirou, publica PEDIDO_VOTO
//  - heartbeat: só enquanto é líder, envia o LOTE de imediato e depois a cada Δ
//  - principal: servidor TCP de recuperação (qualquer nó serve o seu ficheiro, seja ou não líder)
//
// Sincronização: LOCK (o monitor do nó) protege o registo em memória, o ficheiro, seq, L, V, hb e
// pendentes. Todas as chamadas a RaftState que podem mudar o termo/papel são feitas DENTRO de LOCK
// (ordem de aquisição: LOCK -> RaftState; nenhuma operação adquire pela ordem inversa, porque
// RaftState nunca chama código do nó). Assim, um IN que obtém termIfLeader() dentro de LOCK não
// pode ver o nó demitir-se a meio: a demissão espera que o IN termine, ou o IN vê já -1.
// O servidor TCP NÃO adquire LOCK (lê só do RecordFile): um seguidor que faz uma recuperação TCP
// dentro de LOCK nunca fica à espera do LOCK de outro nó, por isso não há bloqueio entre nós.
public class PartitionNode {
    private static final Object LOCK = new Object();

    private static int k;                   // partição de dados
    private static int self;                // identificador deste nó (1..3)
    private static int n;                   // N partições (dimensão dos vetores)
    private static final String ADDRESS = "127.0.0.1";   // todos os processos na mesma máquina
    private static int port;
    private static IpfsPubSub pubsub;
    private static RaftState raft;
    private static RecordFile file;                       // no-p<k>-n<i>.txt
    // cópia em memória do ficheiro: log.get(s - 1) é o registo com seq s (o ficheiro não tem lacunas)
    private static final List<SensorRecord> log = new ArrayList<>();

    // estado do papel de LÍDER (reinicializado em cada liderança, em becomeLeader)
    private static long seq;                              // último seq atribuído
    private static long lamport;                          // L
    private static VectorClock vector;                    // V
    private static long hb;                               // último heartbeat emitido neste termo
    private static List<SensorRecord> pendentes = new ArrayList<>();   // registos com seq, por publicar
    private static ScheduledExecutorService scheduler;    // uma só thread: nunca há dois lotes em paralelo
    private static ScheduledFuture<?> heartbeatTask;
    private static long firstIn, lastIn, burstCount;      // estatísticas da rajada (log RAJADA)

    // estado do papel de SEGUIDOR: onde pedir registos por TCP (vem do cabeçalho de cada LOTE)
    private static String leaderHost = ADDRESS;
    private static int leaderPort;

    private static Path eventsFile, hbFile, truncFile;
    private static final Object EVLOCK = new Object();    // só para não misturar linhas no ficheiro de eventos

    // FALHA 5 do passo 10: novo líder inicializa L e V a zero (as outras estão em RaftState)
    private static final int FALHA = Integer.getInteger("falha", 0);

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: PartitionNode <k> <i> [N]   (ex.: PartitionNode 1 2)");
            return;
        }
        try {
            k = Integer.parseInt(args[0]);
            self = Integer.parseInt(args[1]);
            n = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        } catch (NumberFormatException e) {
            System.out.println("k, i e N têm de ser inteiros");
            return;
        }
        if (k < 0 || k >= n || self < 1 || self > Wire.GRUPO) {
            System.out.println("É preciso 0 <= k < N e 1 <= i <= " + Wire.GRUPO);
            return;
        }
        String api = System.getProperty("raft04.api", "http://127.0.0.1:5001/api/v0");
        pubsub = new IpfsPubSub(api);
        port = Wire.nodePort(k, self);
        String suffix = "p" + k + "-n" + self;
        eventsFile = Paths.get("eventos-" + suffix + ".txt");
        hbFile = Paths.get("hb-" + suffix + ".txt");
        truncFile = Paths.get("truncados-" + suffix + ".txt");
        file = new RecordFile("no-" + suffix + ".txt");
        // PID num ficheiro: os scripts de ensaio usam-no para terminar o nó "sem lhe dar oportunidade de reagir"
        Files.writeString(Paths.get("pid-" + suffix + ".txt"), ProcessHandle.current().pid() + "\n");

        // ---- arranque: 1) registos do ficheiro -> ultimoSeq e ultimoTermo locais ----
        synchronized (LOCK) {
            List<SensorRecord> all = file.all();
            for (SensorRecord r : all) {
                if (r.getSeq() != log.size() + 1) {
                    System.out.println("Ficheiro " + "no-" + suffix + ".txt com lacuna ou fora de ordem no seq "
                            + r.getSeq() + " (esperado " + (log.size() + 1) + ")");
                    return;
                }
                log.add(r);
            }
        }
        // ---- 2) RaftState: começa SEMPRE como seguidor, com o termo e o voto gravados ----
        raft = new RaftState(k, self, Wire.GRUPO, Wire.ELEICAO_MIN_MS, Wire.ELEICAO_MAX_MS,
                Paths.get("estado-" + suffix + ".txt"), PartitionNode::event);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-" + suffix);
            t.setDaemon(true);
            return t;
        });
        event("ARRANQUE;p" + k + ";termo=" + raft.term() + ";votouEm=" + raft.votedFor()
                + ";ultimoSeq=" + lastSeq() + ";ultimoTermo=" + lastTerm()
                + ";eleicao=" + Wire.ELEICAO_MIN_MS + ".." + Wire.ELEICAO_MAX_MS + ";periodo=" + Wire.PERIODO_HB_MS
                + (FALHA != 0 ? ";FALHA=" + FALHA : ""));

        // ---- 3) servidor TCP de recuperação ----
        ServerSocket server;
        try {
            server = new ServerSocket(port);
        } catch (IOException e) {
            System.out.println("Não foi possível escutar no porto " + port + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        // ---- 4) subscrições (uma thread cada, porque subscribe() bloqueia) ----
        subscribe(Wire.topicIn(k), "entrada", PartitionNode::onInput);
        subscribe(Wire.topicData(k), "dados", PartitionNode::onData);
        subscribe(Wire.topicElection(k), "eleicao", PartitionNode::onElection);

        // ---- 5) thread de verificação: o prazo de eleição ----
        Thread checker = new Thread(PartitionNode::checkLoop, "verificacao-" + suffix);
        checker.setDaemon(true);
        checker.start();
        startIdleWatcher();

        System.out.println("Nó n" + self + " de p" + k + " pronto (N=" + n + ", termo=" + raft.term()
                + ", ultimoSeq=" + lastSeq() + ", ultimoTermo=" + lastTerm() + ", Δ=" + Wire.PERIODO_HB_MS
                + " ms, eleição em [" + Wire.ELEICAO_MIN_MS + ", " + Wire.ELEICAO_MAX_MS + "] ms"
                + (FALHA != 0 ? ", FALHA=" + FALHA : "") + "), TCP " + ADDRESS + ":" + port);

        // ---- thread principal: servidor TCP, um pedido de cada vez ----
        while (true) {
            try (Socket s = server.accept()) {
                if (Wire.isolated(k, self)) {
                    continue;                              // partição de rede: fecha sem responder
                }
                s.setSoTimeout(Wire.T_TCP_MS);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                serve(in.readLine(), s, out);
            } catch (IOException | RuntimeException e) {
                System.out.println("IO (servidor): " + e.getMessage());
            }
        }
    }

    // ===================================================================================
    // Eventos: "instante;evento;p<k>;detalhe" na consola e em eventos-p<k>-n<i>.txt
    // ===================================================================================
    static void event(String e) {
        String line = System.currentTimeMillis() + ";" + e;
        synchronized (EVLOCK) {
            System.out.println("EVENTO " + line);
            try {
                Files.writeString(eventsFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                System.out.println("IO (eventos): " + ex.getMessage());
            }
        }
    }

    // subscreve um tópico numa thread própria; ignora as mensagens que este nó publicou
    // (o filtro do "próprio" é feito em cada handler, pelo identificador que vai na mensagem)
    private static void subscribe(String topic, String name, java.util.function.Consumer<String> handler) {
        Thread t = new Thread(() -> {
            try {
                pubsub.subscribe(topic, msg -> {
                    try {
                        handler.accept(msg);
                    } catch (RuntimeException e) {          // uma mensagem estranha não pode matar a subscrição
                        System.out.println("ERRO (" + name + "): " + e);
                    }
                });
                System.out.println("Subscrição " + topic + " terminada.");
            } catch (IOException e) {
                System.out.println("IO (" + name + "): " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, name + "-p" + k + "-n" + self);
        t.start();
    }

    // o daemon pode recusar uma publicação sem subscritores ("validation ignored"): repete até 8 vezes
    static void publicar(String topic, String message) throws IOException, InterruptedException {
        int attempt = 1;
        while (true) {
            try {
                pubsub.publish(topic, message);
                return;
            } catch (IOException e) {
                if (attempt >= 8 || !String.valueOf(e.getMessage()).contains("validation ignored")) throw e;
                Thread.sleep(2);
                attempt++;
            }
        }
    }

    // publicação "melhor esforço" das mensagens de eleição: um erro só é registado na consola
    private static void publicarEleicao(String message) {
        if (Wire.isolated(k, self)) return;               // partição de rede: não publica
        try {
            publicar(Wire.topicElection(k), message);
        } catch (IOException e) {
            System.out.println("IO (eleição): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- consultas ao registo local (chamar com LOCK, ou no arranque) ----
    private static long lastSeq() {
        synchronized (LOCK) { return log.size(); }
    }

    private static long lastTerm() {
        synchronized (LOCK) { return log.isEmpty() ? 0 : log.get(log.size() - 1).getTerm(); }
    }

    private static long termAt(long s) {                  // termo do registo com seq s (0 se não existir)
        return (s >= 1 && s <= log.size()) ? log.get((int) (s - 1)).getTerm() : 0;
    }

    // ===================================================================================
    // Eleição
    // ===================================================================================

    // thread de verificação: a cada VERIFICACAO_MS, se o prazo de eleição expirou, candidata-se
    private static void checkLoop() {
        while (true) {
            try {
                Thread.sleep(Wire.VERIFICACAO_MS);
            } catch (InterruptedException e) {
                return;
            }
            long t, ls, lt;
            synchronized (LOCK) {
                t = raft.tick();                           // incrementa o termo, vota em si e GRAVA
                if (t == -1) continue;
                ls = lastSeq();
                lt = lastTerm();
                if (raft.termIfLeader() == t) {            // só acontece com a FALHA 1 (maioria de 1)
                    becomeLeader(t);
                    continue;
                }
            }
            // fora do lock: publicar é lento. O estado já está gravado: se o nó falhar aqui, no
            // arranque seguinte retoma o termo t com votedFor = ele próprio e não vota noutro em t.
            publicarEleicao(new VoteRequest(t, self, ls, lt).encode());
        }
    }

    // tópico de eleição: PEDIDO_VOTO de outros nós e VOTO dirigido a este nó
    private static void onElection(String msg) {
        if (Wire.isolated(k, self)) return;               // partição de rede: não processa
        String type = Wire.type(msg);
        if (type.equals("PEDIDO_VOTO")) {
            VoteRequest r = VoteRequest.decode(msg);
            if (r.candidate == self) return;               // o próprio pedido
            RaftState.Vote v;
            synchronized (LOCK) {
                // o último registo local é lido no mesmo lock em que se decide: coerente com o ficheiro
                v = raft.onVoteRequest(r.term, r.candidate, r.lastSeq, r.lastTerm, lastSeq(), lastTerm());
            }
            // responde SEMPRE (mesmo a recusar): o termo da resposta pode demitir um candidato atrasado.
            // O voto já foi gravado por onVoteRequest antes de chegarmos aqui.
            publicarEleicao(new VoteReply(v.term, self, r.candidate, v.granted).encode());
        } else if (type.equals("VOTO")) {
            VoteReply v = VoteReply.decode(msg);
            if (v.candidate != self || v.voter == self) return;   // só os votos dirigidos a este nó
            synchronized (LOCK) {
                if (raft.onVote(v.term, v.voter, v.candidate, v.granted)) {
                    becomeLeader(v.term);                  // maioria: passa a líder no mesmo lock
                }
            }
        }
    }

    // ===================================================================================
    // Papel de LÍDER (reutiliza o PartitionLeader da COMINDIR04)
    // ===================================================================================

    // chamado com LOCK: inicializa seq, L e V a partir do ÚLTIMO REGISTO do ficheiro, hb = 0,
    // pendentes vazia, e agenda enviarLote() de imediato (afirma a liderança) e depois a cada Δ.
    // L e V vêm do último registo (e não de zero) para que V[k] = seq e L crescente se mantenham
    // através das mudanças de líder: o novo líder continua a história do anterior.
    private static void becomeLeader(long term) {
        SensorRecord last = log.isEmpty() ? null : log.get(log.size() - 1);
        seq = log.size();
        if (last == null || FALHA == 5) {                  // FALHA 5: L e V a zero
            lamport = 0;
            vector = new VectorClock(n);
        } else {
            lamport = last.getLamport();
            vector = VectorClock.parse(last.getVector().toString(), n);
        }
        hb = 0;
        pendentes = new ArrayList<>();
        if (heartbeatTask != null) heartbeatTask.cancel(false);   // tarefa de uma liderança anterior
        final long myTerm = term;
        // scheduleAtFixedRate numa única thread: nunca há dois lotes em paralelo.
        // Quando enviarLote() devolve false (deixou de ser líder deste termo), lança-se
        // CancellationException: o executor deixa de agendar esta tarefa ("cancela o agendamento").
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            boolean continuar;
            try {
                continuar = enviarLote(myTerm);
            } catch (RuntimeException e) {
                System.out.println("ERRO no heartbeat: " + e);
                continuar = true;                          // um erro pontual não termina a liderança
            }
            if (!continuar) throw new CancellationException("liderança do termo " + myTerm + " terminou");
        }, 0, Wire.PERIODO_HB_MS, TimeUnit.MILLISECONDS);
        System.out.println("LÍDER de p" + k + " no termo " + term + ": seq=" + seq + " L=" + lamport + " V=" + vector);
    }

    // uma mensagem IN = um evento, mas só se este nó for líder (igual à COMINDIR04 + termo no registo)
    private static void onInput(String msg) {
        Wire.In in;
        try {
            in = Wire.parseIn(msg, n);
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO: " + msg + " (" + e.getMessage() + ")");
            return;
        }
        if (new Partitioner(n).of(in.sensor) != k) {
            System.out.println("REJEITADO: " + in.sensor + " não pertence a p" + k);
            return;
        }
        synchronized (LOCK) {
            // o termo é obtido DENTRO do LOCK: como a demissão também acontece dentro do LOCK,
            // ou o nó ainda é líder do termo t durante toda a atribuição, ou já vê -1.
            long t = raft.termIfLeader();
            if (t == -1) {
                event("IGNORADO;p" + k + ";IN;sensor=" + in.sensor + ";temperatura=" + in.temperature
                        + ";papel=" + raft.role() + ";termo=" + raft.term());
                return;                                    // não atribui seq
            }
            long newL = Math.max(lamport, in.lamport) + 1;  // regra de Lamport
            VectorClock newV = vector.copy();
            newV.merge(in.vector);                          // regra vetorial: máximo...
            newV.tick(k);                                   // ...e incremento da componente k
            long newSeq = seq + 1;
            if (newV.get(k) != newSeq) {
                // invariante V[k] = seq: só falha se o líder foi mal inicializado (FALHA 5).
                // Ao contrário da COMINDIR04 o registo é escrito na mesma, para o CausalCheck o ver.
                event("ERRO;p" + k + ";V[k]=" + newV.get(k) + " != seq=" + newSeq);
            }
            SensorRecord r = SensorRecord.now(newSeq, t, in.sensor, in.temperature, newL, newV.copy());
            try {
                file.append(r);
            } catch (IOException e) {
                System.out.println("IO: " + e.getMessage());
                return;                                    // nada confirmado: o estado fica como estava
            }
            log.add(r);
            seq = newSeq;
            lamport = newL;
            vector = newV;
            pendentes.add(r);                              // dentro do lock: nenhum seq fica "entre lotes"
            long now = System.currentTimeMillis();
            if (burstCount == 0) firstIn = now;
            lastIn = now;
            burstCount++;
            System.out.println("ATRIBUIDO: seq=" + newSeq + " termo=" + t + " " + in.sensor + " " + in.temperature
                    + " L=" + newL + " V=" + newV + " (pendente, " + pendentes.size() + " no lote)");
        }
    }

    // fecha o lote do intervalo e publica-o (é também o heartbeat). Corre na thread do scheduler.
    // Devolve false quando este nó deixou de ser líder do termo myTerm (fim da tarefa).
    private static boolean enviarLote(long myTerm) {
        List<SensorRecord> lote;
        long ultimoSeq, ultimoTermo, meuHb;
        synchronized (LOCK) {
            long t = raft.termIfLeader();
            if (t == -1 || t != myTerm) return false;     // demitiu-se: cancela o agendamento
            lote = pendentes;
            pendentes = new ArrayList<>();                 // troca, não clear: a lista antiga é publicada fora do lock
            ultimoSeq = seq;
            ultimoTermo = termAt(seq);
            hb++;
            meuHb = hb;
        }
        // fora do lock: codificar e publicar é lento (HTTP ao daemon)
        Batch b = new Batch(myTerm, self, ADDRESS, port, meuHb, ultimoSeq, ultimoTermo, lote);
        String msg = b.encode();
        int bytes = Batch.bytes(msg);
        if (Wire.isolated(k, self)) {
            // partição de rede: o líder isolado continua a julgar-se líder, mas ninguém o ouve
            System.out.println("ISOLADO: lote hb=" + meuHb + " termo=" + myTerm + " n=" + lote.size() + " não publicado");
            return true;
        }
        try {
            publicar(Wire.topicData(k), msg);
        } catch (IOException e) {
            System.out.println("ERRO: lote hb=" + meuHb + " não publicado (" + e.getMessage() + ")");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        // termo;hb;instante;ultimoSeq;ultimoTermo;n;bytes
        String line = myTerm + ";" + meuHb + ";" + System.currentTimeMillis() + ";" + ultimoSeq + ";" + ultimoTermo
                + ";" + lote.size() + ";" + bytes;
        try {
            Files.writeString(hbFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("IO (hb): " + e.getMessage());
        }
        System.out.println("HEARTBEAT: termo=" + myTerm + " hb=" + meuHb + " n=" + lote.size() + " ultimoSeq="
                + ultimoSeq + " ultimoTermo=" + ultimoTermo + " seq=" + b.seqRange() + " bytes=" + bytes);
        return true;
    }

    // ===================================================================================
    // Papel de SEGUIDOR (reutiliza a PartitionReplica da COMINDIR04)
    // ===================================================================================

    // tópico de dados: LOTE de outro nó
    private static void onData(String msg) {
        if (Wire.isolated(k, self)) return;               // partição de rede: não processa
        Batch b;
        try {
            b = Batch.decode(msg, 0);
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO (lote): " + e.getMessage());
            return;
        }
        if (b.leader == self) return;                      // o próprio lote
        synchronized (LOCK) {
            // onBatch: termo inferior => false (líder obsoleto); termo superior => adota-o (e, se
            // este nó era líder, demite-se); mesmo termo => reconhece o líder e renova o prazo
            if (!raft.onBatch(b.term, b.leader)) {
                event("IGNORADO;p" + k + ";LOTE;termo=" + b.term + ";lider=n" + b.leader + ";hb=" + b.hb
                        + ";meuTermo=" + raft.term());
                return;
            }
            event("RECEBIDO;p" + k + ";termo=" + b.term + ";lider=n" + b.leader + ";hb=" + b.hb
                    + ";ultimoSeq=" + b.lastSeq + ";ultimoTermo=" + b.lastTerm + ";n=" + b.records.size()
                    + ";seq=" + b.seqRange());
            leaderHost = b.address;
            leaderPort = b.port;
            applyBatch(b);
        }
    }

    // aplica os registos de um lote pela ordem (chamar com LOCK)
    private static void applyBatch(Batch b) {
        for (SensorRecord r : b.records) {
            long last = lastSeq();
            if (r.getSeq() <= last) {
                if (termAt(r.getSeq()) == r.getTerm()) {
                    continue;                              // mesmo seq e mesmo termo: duplicado
                }
                // mesmo seq, termo diferente: divergência (prevalece o do líder)
                resolveDivergence(b.lastSeq, "seq " + r.getSeq() + " local termo " + termAt(r.getSeq())
                        + " != " + r.getTerm());
                return;                                    // a resolução já trouxe tudo até ultimoSeq
            } else if (r.getSeq() == last + 1) {
                if (!append(r)) return;
            } else {
                // lacuna (perderam-se lotes): recupera por TCP ao líder até ao ultimoSeq do lote
                recover(last + 1, b.lastSeq);
                break;                                     // os restantes do lote já vieram (ou falhou)
            }
        }
        // fim do lote: compara o último registo local com ultimoSeq e ultimoTermo
        if (lastSeq() < b.lastSeq) {
            recover(lastSeq() + 1, b.lastSeq);             // tem menos registos: recupera os que faltam
        }
        if (lastSeq() > b.lastSeq) {
            resolveDivergence(b.lastSeq, "registos para além de ultimoSeq=" + b.lastSeq + " (local " + lastSeq() + ")");
        } else if (b.lastSeq > 0 && lastSeq() == b.lastSeq && termAt(b.lastSeq) != b.lastTerm) {
            resolveDivergence(b.lastSeq, "seq " + b.lastSeq + " local termo " + termAt(b.lastSeq)
                    + " != ultimoTermo " + b.lastTerm);
        }
    }

    // acrescenta um registo ao ficheiro e à memória (chamar com LOCK)
    private static boolean append(SensorRecord r) {
        try {
            file.append(r);
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
            return false;
        }
        log.add(r);
        System.out.println("APLICADO p" + k + ": " + r.toLine());
        return true;
    }

    // recupera from..to por TCP e aplica-os com as mesmas regras dos lotes (chamar com LOCK)
    private static void recover(long from, long to) {
        if (from > to) return;
        event("RECUPERACAO;p" + k + ";de=" + from + ";ate=" + to);
        List<SensorRecord> recs;
        try {
            recs = fetch(from, to);
        } catch (IOException | RuntimeException e) {
            // o próximo LOTE (ultimoSeq) volta a provocar o pedido
            System.out.println("Recuperação falhou (" + e.getClass().getSimpleName() + "): " + e.getMessage());
            return;
        }
        for (SensorRecord r : recs) {
            long last = lastSeq();
            if (r.getSeq() <= last) {
                if (termAt(r.getSeq()) == r.getTerm()) continue;
                resolveDivergence(to, "seq " + r.getSeq() + " (recuperação) local termo " + termAt(r.getSeq())
                        + " != " + r.getTerm());
                return;
            }
            if (r.getSeq() != last + 1 || !append(r)) return;
        }
    }

    // Resolução de divergência (passo 5): pede ao líder os registos a partir do primeiro seq do ÚLTIMO
    // BLOCO DE TERMO local, recua um bloco de cada vez enquanto o primeiro registo recebido não
    // coincidir com o local, trunca a partir do primeiro seq diferente e aplica os do líder.
    // Chamar com LOCK. leaderLast = ultimoSeq do líder.
    private static void resolveDivergence(long leaderLast, String motivo) {
        System.out.println("DIVERGÊNCIA p" + k + ": " + motivo);
        long last = lastSeq();
        if (last == 0) {                                   // registo vazio: não há nada a truncar
            recover(1, leaderLast);
            return;
        }
        long s = blockStart(last);
        List<SensorRecord> recs;
        try {
            while (true) {
                recs = s <= leaderLast ? fetch(s, leaderLast) : new ArrayList<>();
                SensorRecord first = recs.isEmpty() ? null : recs.get(0);
                if (first != null && first.getSeq() == s && first.getTerm() == termAt(s)) {
                    break;                                 // coincide: o prefixo até s é comum
                }
                if (s == 1) break;                         // não há bloco anterior: compara desde o início
                s = blockStart(s - 1);                     // recua um bloco de termo
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("Resolução de divergência falhou (" + e.getClass().getSimpleName() + "): "
                    + e.getMessage() + " (tenta de novo no próximo lote)");
            return;
        }
        // primeiro seq divergente: o primeiro em que o registo local não existe no líder ou tem outro termo
        Map<Long, SensorRecord> leader = new HashMap<>();
        for (SensorRecord r : recs) leader.put(r.getSeq(), r);
        long d = -1;
        for (long q = s; q <= last; q++) {
            SensorRecord lr = leader.get(q);
            if (lr == null || lr.getTerm() != termAt(q)) {
                d = q;
                break;
            }
        }
        if (d != -1 && !truncate(d)) return;
        // aplica os registos do líder que faltam (a partir do novo fim do registo local)
        for (SensorRecord r : recs) {
            if (r.getSeq() == lastSeq() + 1) {
                if (!append(r)) return;
            }
        }
        if (d == -1) event("RECUPERACAO;p" + k + ";de=" + s + ";ate=" + leaderLast + ";sem divergencia");
    }

    // primeiro seq do bloco de termo que contém o seq s (sequência máxima de registos com o mesmo termo)
    private static long blockStart(long s) {
        long t = termAt(s);
        while (s > 1 && termAt(s - 1) == t) s--;
        return s;
    }

    // remove os registos com seq >= d: reescreve o ficheiro de forma atómica (chamar com LOCK)
    // Os registos descartados ficam em truncados-p<k>-n<i>.txt (instante;linha), para o RaftCheck.
    private static boolean truncate(long d) {
        List<SensorRecord> keep = new ArrayList<>(log.subList(0, (int) (d - 1)));
        List<SensorRecord> gone = new ArrayList<>(log.subList((int) (d - 1), log.size()));
        try {
            file.rewrite(keep);
            StringBuilder sb = new StringBuilder();
            long now = System.currentTimeMillis();
            for (SensorRecord r : gone) sb.append(now).append(';').append(r.toLine()).append(System.lineSeparator());
            Files.writeString(truncFile, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("IO (truncagem): " + e.getMessage());
            return false;
        }
        log.clear();
        log.addAll(keep);
        TreeSet<Long> terms = new TreeSet<>();
        for (SensorRecord r : gone) terms.add(r.getTerm());
        event("TRUNCADO;p" + k + ";de=" + d + ";descartados=" + gone.size() + ";termos=" + terms);
        return true;
    }

    // cliente TCP de recuperação: PEDIDO <de> <ate> -> linhas de registo e FIM (protocolo da COMINDIR02)
    private static List<SensorRecord> fetch(long from, long to) throws IOException {
        if (Wire.isolated(k, self)) throw new IOException("nó isolado (partição de rede simulada)");
        List<SensorRecord> out = new ArrayList<>();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(leaderHost, leaderPort), Wire.T_TCP_MS);
            s.setSoTimeout(Wire.T_TCP_MS);
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            pw.println("PEDIDO " + from + " " + to);
            while (true) {
                String line = br.readLine();
                if (line == null) throw new IOException("ligação fechada antes de FIM");
                if (line.equals("FIM")) break;
                if (line.startsWith("ERRO")) throw new IOException(line);
                out.add(SensorRecord.fromLine(line));
            }
        }
        System.out.println("TCP p" + k + ": PEDIDO " + from + " " + to + " a " + leaderHost + ":" + leaderPort
                + " -> " + out.size() + " registos");
        return out;
    }

    // servidor TCP: responde a PEDIDO <de> <ate> com as linhas do ficheiro (qualquer nó, líder ou não)
    // Lê só do RecordFile (sem LOCK): o ficheiro só contém registos completos (append atómico por linha
    // e truncagem por ATOMIC_MOVE), por isso não precisa do estado do nó.
    private static void serve(String request, Socket s, PrintWriter out) throws IOException {
        if (request == null) return;
        String[] p = request.trim().split("\\s+");
        long from, to;
        try {
            if (p.length != 3 || !p[0].equals("PEDIDO")) throw new NumberFormatException("formato");
            from = Long.parseLong(p[1]);
            to = Long.parseLong(p[2]);
        } catch (NumberFormatException e) {
            out.println("ERRO pedido mal formado: " + request);
            return;
        }
        if (from > to || from < 1) {
            out.println("ERRO intervalo inválido: " + from + ".." + to);
            return;
        }
        List<SensorRecord> records = file.range(from, to);
        for (SensorRecord r : records) out.println(r.toLine());
        out.println("FIM");
        System.out.println("Recuperação servida: " + from + ".." + to + " (" + records.size() + " registos) a "
                + s.getRemoteSocketAddress());
    }

    // thread auxiliar: 2 s sem receber IN (como líder) => escreve o resumo da rajada na consola
    private static void startIdleWatcher() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (LOCK) {
                    if (burstCount > 0 && System.currentTimeMillis() - lastIn >= 2000) {
                        long ms = Math.max(1, lastIn - firstIn);
                        System.out.printf("RAJADA p%d: primeiro=%d ms, último=%d ms, atribuídos=%d, débito=%.1f reg/s, último seq=%d%n",
                                k, firstIn, lastIn, burstCount, burstCount * 1000.0 / ms, seq);
                        burstCount = 0;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
