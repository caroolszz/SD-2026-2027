#!/usr/bin/env bash
# Compila os três projetos para <projeto>/out
. "$(dirname "$0")/comum.sh"
for p in raft04-no raft04-produtor raft04-processador; do
  echo "== $p"; (cd "$BASE/$p" && rm -rf out && javac -encoding UTF-8 -d out src/raft04/*.java) || exit 1
done
echo "OK"
