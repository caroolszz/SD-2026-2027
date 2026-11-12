#!/usr/bin/env bash
# Arranca o produtor em primeiro plano (comandos pelo teclado): produtor.sh [-Dopção=valor ...]
. "$(dirname "$0")/comum.sh"
exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "$@" -cp "$CP_PROD" raft04.Producer "$N"
