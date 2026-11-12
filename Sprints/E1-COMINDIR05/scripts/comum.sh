# Variáveis comuns aos scripts (incluído pelos outros com ". comum.sh").
# BASE = pasta E1-COMINDIR05; os scripts correm DENTRO da pasta de trabalho do ensaio (ex.: ensaios/9a).
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CP_NO="$BASE/raft04-no/out"
CP_PROD="$BASE/raft04-produtor/out"
CP_PROC="$BASE/raft04-processador/out"
N=3
# termina um processo sem lhe dar oportunidade de reagir (equivalente a kill -9)
matar() {
  if command -v taskkill >/dev/null 2>&1; then taskkill //F //PID "$1" >/dev/null 2>&1 || taskkill /F /PID "$1" >/dev/null 2>&1
  else kill -9 "$1" 2>/dev/null; fi
}
agora() { date +%s%3N; }
