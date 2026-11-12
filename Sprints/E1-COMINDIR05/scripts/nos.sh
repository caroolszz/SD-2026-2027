#!/usr/bin/env bash
# Arranca vários nós em SEGUNDO PLANO (consola em consola-p<k>-n<i>.log):
#   nos.sh "<lista k:i>" [-Dopção=valor ...]
#   nos.sh todos                       -> os 9 nós, por ordem p0n1 p0n2 ... p2n3
#   nos.sh aleatorio                   -> os 9 nós por ordem aleatória, com 0..2 s entre cada um
#   nos.sh "1:1 1:2 1:3"               -> só os três nós de p1, no mesmo instante
#   nos.sh todos -Draft04.eleicaoMin=6000 -Draft04.eleicaoMax=6000   (9(b) com prazo fixo)
. "$(dirname "$0")/comum.sh"
lista=$1; shift
todos="0:1 0:2 0:3 1:1 1:2 1:3 2:1 2:2 2:3"
pausa=0
case "$lista" in
  todos) lista=$todos ;;
  aleatorio) lista=$(echo $todos | tr ' ' '\n' | shuf | tr '\n' ' '); pausa=1 ;;
esac
echo "ordem de arranque: $lista"
for ki in $lista; do
  k=${ki%%:*}; i=${ki##*:}
  java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "$@" -cp "$CP_NO" raft04.PartitionNode "$k" "$i" "$N" > "consola-p$k-n$i.log" 2>&1 &
  if [ $pausa = 1 ]; then sleep 0.$((RANDOM % 10)); sleep $((RANDOM % 2)); fi
done
echo "arrancados; PIDs em pid-p<k>-n<i>.txt"
