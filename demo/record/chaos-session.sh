#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."
command=(demo/chaos.sh --rounds 10 --gap 3 --seed 6)
printf '\033[1;32m$\033[0m %s\n' "${command[*]}"
"${command[@]}"
sleep 2
