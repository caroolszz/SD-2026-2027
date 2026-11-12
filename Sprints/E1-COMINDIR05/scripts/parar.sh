#!/usr/bin/env bash
# Termina todos os nós arrancados nesta pasta (lê pid-*.txt)
. "$(dirname "$0")/comum.sh"
for f in pid-*.txt; do [ -f "$f" ] && matar "$(tr -d '\r\n ' < "$f")"; done
echo "nós terminados"
