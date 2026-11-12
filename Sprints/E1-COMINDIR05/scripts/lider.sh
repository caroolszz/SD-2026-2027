#!/usr/bin/env bash
# Mostra, para cada nó de p<k>, o último LIDER que registou; o de maior termo é o líder mais recente.
#   lider.sh <k>          (para obter só o número do nó: lider.sh <k> -q)
k=$1
best=""; bt=-1
for f in eventos-p$k-n*.txt; do
  [ -f "$f" ] || continue
  nd=${f##*-}; nd=${nd%.txt}
  l=$(grep ";LIDER;p$k;" "$f" | tail -n1)
  [ -z "$l" ] && continue
  t=$(echo "$l" | sed 's/.*termo=\([0-9]*\).*/\1/')
  [ "$2" = "-q" ] || echo "$nd: $l"
  if [ "$t" -gt "$bt" ]; then bt=$t; best=$nd; fi
done
if [ "$2" = "-q" ]; then echo "${best#n}"; else echo "=> líder mais recente de p$k: $best (termo $bt)"; fi
