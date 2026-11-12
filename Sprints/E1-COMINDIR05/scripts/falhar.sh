#!/usr/bin/env bash
# Provoca a falha do nó i de p<k>: anota o instante em falhas.txt e termina-o à força (kill -9).
#   falhar.sh <k> <i>        ex.: falhar.sh 1 2
. "$(dirname "$0")/comum.sh"
k=$1; i=$2
pid=$(tr -d '\r\n ' < "pid-p$k-n$i.txt")
echo "p$k;n$i;$(agora)" >> falhas.txt
matar "$pid"
echo "falha: p$k n$i (pid $pid) anotada em falhas.txt"
