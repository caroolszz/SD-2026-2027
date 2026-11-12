#!/usr/bin/env bash
# Repara a partição de rede do nó i de p<k> (apaga isolar-p<k>-n<i>) e anota o instante em particoes.txt
. "$(dirname "$0")/comum.sh"
k=$1; i=$2
rm -f "isolar-p$k-n$i"; echo "p$k;n$i;reparado;$(agora)" >> particoes.txt; echo "p$k n$i reparado"
