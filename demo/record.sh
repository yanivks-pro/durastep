#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
for tool in asciinema agg docker java jq; do
  if ! command -v "$tool" >/dev/null; then
    echo "recording needs $tool; on a Mac: brew install asciinema agg jq" >&2
    exit 1
  fi
done

media=docs/media
mkdir -p "$media"
./mvnw -B -ntp -q package -pl durastep-cli -am -Dmaven.test.skip=true
docker compose up -d --build --wait
sleep 45

asciinema rec --overwrite --quiet --headless --window-size 100x30 --idle-time-limit 1 \
  --command demo/record/chaos-session.sh "$media/chaos.cast"
agg --font-size 18 --speed 1.5 --last-frame-duration 10 "$media/chaos.cast" "$media/chaos.gif"

asciinema rec --overwrite --quiet --headless --window-size 110x36 --idle-time-limit 2 \
  --command demo/record/cli-session.sh "$media/cli.cast"
agg --font-size 16 --last-frame-duration 8 "$media/cli.cast" "$media/cli.gif"

chrome=""
for candidate in "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" google-chrome chromium; do
  if command -v "$candidate" >/dev/null 2>&1 || [ -x "$candidate" ]; then
    chrome="$candidate"
    break
  fi
done
if [ -n "$chrome" ]; then
  trip=$(java -jar durastep-cli/target/durastep-cli-*[0-9T].jar list --type TripWorkflow --state failed --limit 1 --json \
    | jq -r '.[0].id')
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --window-size=1280,760 \
    --screenshot="$media/dashboard-overview.png" http://127.0.0.1:8099/ 2>/dev/null
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --window-size=1280,1000 \
    --screenshot="$media/dashboard-workflow.png" "http://127.0.0.1:8099/workflows/$trip" 2>/dev/null
else
  echo "no Chrome found; skipping the dashboard screenshots" >&2
fi

ls -la "$media"
