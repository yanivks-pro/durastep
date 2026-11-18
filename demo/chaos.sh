#!/usr/bin/env bash
set -euo pipefail

rounds=20
gap=4
freeze=8
while [ $# -gt 0 ]; do
  case "$1" in
    --rounds) rounds="$2"; shift 2 ;;
    --gap) gap="$2"; shift 2 ;;
    *) echo "usage: demo/chaos.sh [--rounds N] [--gap SECONDS]" >&2; exit 2 ;;
  esac
done
case "$rounds$gap" in
  *[!0-9]*|"") echo "rounds and gap must be whole numbers" >&2; exit 2 ;;
esac

cd "$(dirname "$0")/.."

sql() {
  docker compose exec -T postgres psql -U durastep -d durastep -Atq -c "$1"
}

say() {
  printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"
}

engines=()
while IFS= read -r id; do
  engines+=("$id")
done < <(docker compose ps -q engine)
if [ "${#engines[@]}" -lt 2 ]; then
  echo "start the demo first: docker compose up -d --wait" >&2
  exit 1
fi

since=$(sql "SELECT now()")
since_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
began=$(date +%s)
kills=0
freezes=0
say "chaos: $rounds rounds against ${#engines[@]} engines while the load keeps starting workflows"

for round in $(seq 1 "$rounds"); do
  victim=${engines[$((RANDOM % ${#engines[@]}))]}
  name=$(docker inspect --format '{{.Name}}' "$victim" | tr -d /)
  if [ $((RANDOM % 3)) -eq 0 ]; then
    say "round $round: freezing $name for ${freeze}s"
    docker pause "$victim" >/dev/null
    freezes=$((freezes + 1))
    sleep "$freeze"
    docker unpause "$victim" >/dev/null
  else
    say "round $round: kill -9 $name"
    docker kill --signal KILL "$victim" >/dev/null
    kills=$((kills + 1))
    sleep "$gap"
    docker start "$victim" >/dev/null
  fi
  sleep "$gap"
done

say "stopping the load and waiting for the started workflows to finish"
docker compose stop load >/dev/null 2>&1
waited=0
while [ "$(sql "SELECT count(*) FROM durastep.workflows WHERE created_at >= '$since' AND state = 'RUNNING' AND NOT blocked")" != 0 ]; do
  if [ "$waited" -ge 180 ]; then
    break
  fi
  sleep 2
  waited=$((waited + 2))
done
seconds=$(( $(date +%s) - began ))

IFS='|' read -r started completed failed cancelled unfinished blocked <<<"$(sql "
  SELECT count(*),
         count(*) FILTER (WHERE state = 'COMPLETED'),
         count(*) FILTER (WHERE state = 'FAILED'),
         count(*) FILTER (WHERE state = 'CANCELLED'),
         count(*) FILTER (WHERE state = 'RUNNING' AND NOT blocked),
         count(*) FILTER (WHERE blocked)
  FROM durastep.workflows WHERE created_at >= '$since'")"
duplicates=$(sql "
  SELECT count(*) FROM (
    SELECT workflow_id, effect FROM demo.effects WHERE applied_at >= '$since'
    GROUP BY workflow_id, effect HAVING count(*) > 1) AS twice")
repeats=$(sql "SELECT COALESCE(sum(repeats), 0) FROM demo.effects WHERE applied_at >= '$since'")
zombies=$(docker compose logs engine --no-log-prefix --since "$since_utc" 2>/dev/null | grep -c 'discarded' || true)
docker compose start load >/dev/null 2>&1

lost=$((unfinished + blocked))
printf '\nDurastep chaos report\n'
printf '  %-40s %s\n' \
  "workflows started" "$started" \
  "completed" "$completed" \
  "failed as designed (no car left)" "$failed" \
  "cancelled" "$cancelled" \
  "lost (unfinished or blocked)" "$lost" \
  "duplicate effects" "$duplicates" \
  "kills (kill -9)" "$kills" \
  "freezes" "$freezes" \
  "zombie results rejected" "$zombies" \
  "repeats absorbed by idempotency keys" "$repeats" \
  "duration" "${seconds}s"

if [ "$lost" -ne 0 ] || [ "$duplicates" -ne 0 ]; then
  exit 1
fi
