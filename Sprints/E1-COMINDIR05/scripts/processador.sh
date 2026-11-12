#!/usr/bin/env bash
# Arranca o processador em primeiro plano: processador.sh [-Dopção=valor ...]
. "$(dirname "$0")/comum.sh"
exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "$@" -cp "$CP_PROC" raft04.AlarmProcessor "$N"
