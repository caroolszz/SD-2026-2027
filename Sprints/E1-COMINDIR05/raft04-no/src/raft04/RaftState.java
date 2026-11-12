package raft04;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

// NOVO na COMINDIR05. Estado RAFT de UM nó de UMA partição de dados (passo 1 da ficha).
// Código dado no enunciado; foram acrescentados comentários e os interruptores -Dfalha=1..4 do
// passo 10 (por omissão desligados, ou seja, código correto).
//
// Papel: guarda o termo, o voto, o papel (SEGUIDOR/CANDIDATO/LIDER), o líder conhecido, os votos
// recebidos e o prazo de eleição, e aplica TODAS as regras da eleição:
//   - termo superior observado => adota-o e passa a seguidor (adoptTerm);
//   - termo inferior => mensagem de um líder/candidato obsoleto, ignorada;
//   - um voto por termo, só a quem tem o registo pelo menos tão atualizado (onVoteRequest);
//   - maioria de G/2+1 votos => líder (onVote).
// Todos os métodos públicos são synchronized no próprio objeto: são chamados pelas threads de
// eleição, de dados, de entrada e de verificação. No PartitionNode são SEMPRE chamados já dentro
// do monitor do nó (ordem de locks: nó -> RaftState; esta classe nunca chama código do nó).
public final class RaftState {
    public enum Role { SEGUIDOR, CANDIDATO, LIDER }

    // resposta a um PEDIDO_VOTO: termo em que é dada e se o voto foi concedido
    public static final class Vote {
        public final long term;
        public final boolean granted;
        Vote(long term, boolean granted) { this.term = term; this.granted = granted; }
    }

    // Falhas deliberadas do passo 10 (só para os ensaios; 0 = código correto):
    //  1 maioria calculada como votes.size() >= groupSize / 2
    //  2 termo e votedFor mantidos só em memória (nem se lê nem se grava estado-p<k>-n<i>.txt)
    //  3 voto concedido sem a verificação upToDate
    //  4 seguidor que aplica lotes de termo inferior ao seu
    //  (a 5, L e V a zero no novo líder, está em PartitionNode)
    private static final int FALHA = Integer.getInteger("falha", 0);

    private final int partition;
    private final int self;
    private final int groupSize;
    private final long minNanos;
    private final long maxNanos;
    private final Path stateFile;
    private final Consumer<String> log;    // eventos: o nó escreve-os na consola e em eventos-p<k>-n<i>.txt

    // estado persistente: gravado antes de qualquer mensagem que dele dependa
    private long term = 0;
    private int votedFor = 0;              // 0 = ainda não votou neste termo

    // estado volátil (perde-se numa falha: o nó volta sempre como seguidor)
    private Role role = Role.SEGUIDOR;
    private int leader = 0;                // 0 = desconhecido
    private final Set<Integer> votes = new HashSet<>();
    private long deadline;                 // System.nanoTime() a partir do qual há eleição

    public RaftState(int partition, int self, int groupSize, long minMs, long maxMs,
                     Path stateFile, Consumer<String> log) throws IOException {
        this.partition = partition;
        this.self = self;
        this.groupSize = groupSize;
        this.minNanos = minMs * 1_000_000L;
        this.maxNanos = maxMs * 1_000_000L;
        this.stateFile = stateFile;
        this.log = log;
        // num reinício, retoma o termo e o voto gravados: assim não volta a votar no mesmo termo
        if (FALHA != 2 && Files.exists(stateFile)) {
            String[] f = Files.readString(stateFile, StandardCharsets.UTF_8).trim().split(";");
            term = Long.parseLong(f[0]);
            votedFor = Integer.parseInt(f[1]);
        }
        resetTimer();
    }

    // sorteia um novo prazo de eleição em [min, max] (com min = max, o prazo é fixo)
    // É sorteado de novo a cada eleição e a cada voto concedido: se o sorteio fosse só no arranque,
    // dois nós que empatassem uma vez voltariam a expirar juntos em todas as eleições seguintes.
    private void resetTimer() {
        long t = (maxNanos > minNanos)
                ? ThreadLocalRandom.current().nextLong(minNanos, maxNanos + 1)
                : minNanos;
        deadline = System.nanoTime() + t;
    }

    // grava "termo;votouEm" de forma atómica (ficheiro temporário + ATOMIC_MOVE).
    // Nota: falta forçar a escrita no disco (fsync/FileChannel.force): numa falha de energia o
    // sistema operativo pode ainda não ter escrito o conteúdo, apesar de o move ser atómico.
    private void persist() {
        if (FALHA == 2) return;                            // FALHA 2: estado só em memória
        try {
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(tmp, term + ";" + votedFor + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, stateFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // termo superior observado: adota-o e passa a seguidor.
    // votedFor volta a 0 porque o voto é POR TERMO: no termo novo ainda não votou em ninguém.
    // (se não voltasse a 0, o nó ficava preso ao voto do termo anterior e recusava todos os candidatos)
    private void adoptTerm(long newTerm) {
        if (role == Role.LIDER) log.accept("DEMISSAO;p" + partition + ";termo=" + term);
        log.accept("TERMO;p" + partition + ";" + term + "->" + newTerm);
        term = newTerm;
        votedFor = 0;
        role = Role.SEGUIDOR;
        leader = 0;
        persist();
    }

    // maioria: mais de metade do grupo (2 de 3). FALHA 1: ">=" metade (1 de 3 basta)
    private boolean hasMajority() {
        return FALHA == 1 ? votes.size() >= groupSize / 2 : votes.size() > groupSize / 2;
    }

    // thread de verificação, a cada VERIFICACAO_MS: devolve o termo da candidatura ou -1
    // Ordem: incrementa o termo, vota em si, GRAVA, e só depois o nó publica o PEDIDO_VOTO.
    public synchronized long tick() {
        if (role == Role.LIDER || System.nanoTime() < deadline) return -1;
        term++;
        votedFor = self;
        persist();
        role = Role.CANDIDATO;
        leader = 0;
        votes.clear();
        votes.add(self);
        resetTimer();
        log.accept("CANDIDATO;p" + partition + ";termo=" + term);
        // Acrescentado: verificar a maioria logo após o voto em si próprio (como no RAFT).
        // Com o código correto e G = 3 nunca é verdade (1 voto não é maioria); só serve para a
        // FALHA 1 se manifestar: com ">= G/2", o candidato elege-se sozinho.
        if (hasMajority()) {
            role = Role.LIDER;
            leader = self;
            log.accept("LIDER;p" + partition + ";termo=" + term + ";votos=" + votes);
        }
        return term;
    }

    // PEDIDO_VOTO de outro nó: decide e devolve o voto (o nó publica-o depois)
    public synchronized Vote onVoteRequest(long cTerm, int candidate, long cLastSeq, long cLastTerm,
                                           long myLastSeq, long myLastTerm) {
        if (cTerm > term) adoptTerm(cTerm);
        // restrição de voto: o registo do candidato é pelo menos tão atualizado como o meu?
        // compara primeiro o termo do último registo e, em caso de empate, o seq
        boolean upToDate = FALHA == 3                      // FALHA 3: sem restrição de voto
                || cLastTerm > myLastTerm
                || (cLastTerm == myLastTerm && cLastSeq >= myLastSeq);
        // as três condições: termo atual, um voto por termo (ou repetição ao mesmo), registo atualizado
        // (o segundo pedido do mesmo candidato chega, por ex., se o PubSub entregar a mensagem duas vezes)
        boolean granted = cTerm == term
                && (votedFor == 0 || votedFor == candidate)
                && upToDate;
        if (granted) {
            votedFor = candidate;
            persist();                                     // gravado ANTES de o nó publicar o VOTO
            resetTimer();                                  // dá tempo ao candidato para ganhar
        }
        log.accept("VOTO;p" + partition + ";termo=" + term + ";candidato=n" + candidate
                + ";" + (granted ? "concedido" : "recusado"));
        return new Vote(term, granted);
    }

    // VOTO recebido: devolve true apenas no instante em que este nó se torna líder
    public synchronized boolean onVote(long vTerm, int voter, int candidate, boolean granted) {
        if (vTerm > term) { adoptTerm(vTerm); return false; }
        if (role != Role.CANDIDATO || vTerm != term || candidate != self || !granted) return false;
        votes.add(voter);                                  // Set: um voto repetido não conta duas vezes
        if (!hasMajority()) return false;
        role = Role.LIDER;
        leader = self;
        log.accept("LIDER;p" + partition + ";termo=" + term + ";votos=" + votes);
        return true;
    }

    // LOTE de outro nó: devolve true se o lote deve ser aplicado
    public synchronized boolean onBatch(long bTerm, int bLeader) {
        if (bTerm < term) return FALHA == 4;               // líder obsoleto (FALHA 4: aplica-o na mesma)
        if (bTerm > term) adoptTerm(bTerm);
        if (role == Role.LIDER) {                          // nunca deveria acontecer
            log.accept("ERRO;p" + partition + ";dois lideres no termo " + term);
            return false;
        }
        role = Role.SEGUIDOR;                              // um candidato reconhece o líder
        if (leader != bLeader) {
            leader = bLeader;
            log.accept("NOVO_LIDER;p" + partition + ";termo=" + term + ";lider=n" + bLeader);
        }
        resetTimer();                                      // o líder está vivo: adia a eleição
        return true;
    }

    public synchronized long termIfLeader() { return role == Role.LIDER ? term : -1; }
    public synchronized long term()         { return term; }
    public synchronized int votedFor()      { return votedFor; }
    public synchronized Role role()         { return role; }
    public synchronized int leader()        { return leader; }
}
