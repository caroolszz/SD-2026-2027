package rep03;

import java.util.Arrays;

// Relógio vetorial de dimensão fixa N (uma componente por líder de partição).
// Código dado no enunciado; só foram acrescentados comentários.
// ATENÇÃO: a classe NÃO é sincronizada. Quem a partilha entre threads tem de a proteger com
// o seu próprio monitor (o LOCK do líder ou do processador) e publicar sempre uma copy().
public final class VectorClock {
    // resultado de this.compare(other):
    // BEFORE = this -> other; AFTER = other -> this; EQUAL = mesmo vetor; CONCURRENT = this || other
    public enum Order { BEFORE, AFTER, EQUAL, CONCURRENT }

    private final long[] v;                 // v[i] = nº de eventos do líder i conhecidos por este vetor

    public VectorClock(int n) {
        this.v = new long[n];               // começa a (0, ..., 0)
    }

    private VectorClock(long[] v) {         // usado por copy() e parse(): adota o array recebido
        this.v = v;
    }

    // máximo componente a componente com outro vetor
    // (incorpora tudo o que o outro vetor conhece: é a regra de receção)
    public void merge(VectorClock other) {
        for (int i = 0; i < v.length; i++) {
            v[i] = Math.max(v[i], other.v[i]);
        }
    }

    // evento local no processo k (só o líder k incrementa a componente k, por isso V[k] = seq)
    public void tick(int k) {
        v[k]++;
    }

    public long get(int k) {
        return v[k];
    }

    // cópia independente: alterações posteriores ao original não afetam a cópia
    public VectorClock copy() {
        return new VectorClock(v.clone());
    }

    // relação de this com other
    // menor = há uma componente em que this está atrás; maior = há uma em que está à frente
    // atrás numa e à frente noutra => nenhum conhece o outro => concorrentes
    public Order compare(VectorClock other) {
        boolean menor = false, maior = false;
        for (int i = 0; i < v.length; i++) {
            if (v[i] < other.v[i]) menor = true;
            if (v[i] > other.v[i]) maior = true;
        }
        if (menor && maior) return Order.CONCURRENT;
        if (menor) return Order.BEFORE;     // this <= other em tudo e diferente => this -> other
        if (maior) return Order.AFTER;      // other -> this
        return Order.EQUAL;
    }

    // representação "v0,v1,...,vN-1"
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.toString();
    }

    // lê "v0,v1,...,vN-1"; recusa vetores com dimensão diferente de n
    // (é isto que impede misturar processos arrancados com N diferentes)
    public static VectorClock parse(String s, int n) {
        String[] f = s.trim().split(",");
        if (f.length != n) {
            throw new IllegalArgumentException("Vetor com dimensão " + f.length + ", esperado " + n + ": " + s);
        }
        long[] v = new long[n];
        for (int i = 0; i < n; i++) {
            v[i] = Long.parseLong(f[i]);
        }
        return new VectorClock(v);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VectorClock && Arrays.equals(v, ((VectorClock) o).v);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(v);
    }
}
