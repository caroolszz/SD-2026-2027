#!/usr/bin/env bash
# Partição de rede simulada: isola o nó i de p<k> (cria isolar-p<k>-n<i>) e anota o instante em particoes.txt
. "$(dirname "$0")/comum.sh"
k=$1; i=$2
touch "isolar-p$k-n$i"; echo "p$k;n$i;isolado;$(agora)" >> particoes.txt; echo "p$k n$i isolado"
