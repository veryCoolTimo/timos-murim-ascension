#!/usr/bin/env bash
# Генерация исходников звуков через ~/bin/murim-sfx (ElevenLabs, бюджет считает сам).
# Использование: gen.sh [имя …] — без аргументов генерирует всё из prompts.tsv, чего ещё нет.
# Варианты: <имя>_a.mp3, <имя>_b.mp3 (VARIANTS=a или VARIANTS="a b").
set -u
cd "$(dirname "$0")"
VARIANTS=${VARIANTS:-"a b"}
while IFS=$'\t' read -r name sec infl loop prompt; do
  [ -z "$name" ] && continue
  if [ $# -gt 0 ] && ! printf '%s\n' "$@" | grep -qx "$name"; then continue; fi
  for v in $VARIANTS; do
    out="${name}_${v}.mp3"
    [ -s "$out" ] && continue
    args=(--seconds "$sec" --influence "$infl")
    [ "$loop" = "loop" ] && args+=(--loop)
    ~/bin/murim-sfx "$prompt" "$out" "${args[@]}" || echo "FAIL $out"
  done
done < prompts.tsv
