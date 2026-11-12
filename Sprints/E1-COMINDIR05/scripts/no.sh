#!/usr/bin/env bash
# Arranca UM nó em primeiro plano (para ver a consola): no.sh <k> <i> [-Dopção=valor ...]
# ex.: no.sh 1 2          no.sh 1 2 -Dfalha=3
. "$(dirname "$0")/comum.sh"
k=$1; i=$2; shift 2
exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "$@" -cp "$CP_NO" raft04.PartitionNode "$k" "$i" "$N"
