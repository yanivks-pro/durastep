#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."
jar=$(ls durastep-cli/target/durastep-cli-*.jar | grep -v original | head -1)

durastep() {
  java -jar "$jar" "$@"
}

typed() {
  printf '\033[1;32m$\033[0m '
  local text="$*"
  for ((i = 0; i < ${#text}; i++)); do
    printf '%s' "${text:i:1}"
    sleep 0.03
  done
  printf '\n'
}

run() {
  typed "durastep $*"
  durastep "$@"
  sleep 2.5
  echo
}

trip=$(durastep list --type TripWorkflow --state failed --limit 1 --json | jq -r '.[0].id')
report=$(durastep list --type ReportWorkflow --state completed --limit 1 --json | jq -r '.[0].id')

run stats
run list --limit 6
run show "$trip"
run show "$report"
typed "durastep history $trip --json | jq -c '.[] | {seq, type}' | head -6"
durastep history "$trip" --json | jq -c '.[] | {seq, type}' | head -6
sleep 3
