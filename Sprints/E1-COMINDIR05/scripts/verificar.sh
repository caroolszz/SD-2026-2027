#!/usr/bin/env bash
# Corre o RaftCheck (com falhas.txt, se existir) e o CausalCheck sobre a pasta atual
. "$(dirname "$0")/comum.sh"
if [ -f falhas.txt ]; then java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$CP_NO" raft04.RaftCheck "$N" . falhas.txt
else java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$CP_NO" raft04.RaftCheck "$N" .; fi
echo
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$CP_NO" raft04.CausalCheck "$N" .
