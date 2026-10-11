#!/usr/bin/env bash
# Runs the Phase 4 and 5d exit criteria (ExitCriterionTest) on a running emulator against the pinned
# release's tidefelld on this host.
#
# Needs: tidefelld and tidefell on PATH, iroh-relay on PATH, adb connected to the emulator, and
# the ability to add 10.0.2.2 to the loopback interface (sudo). The emulator reaches the host's
# loopback as 10.0.2.2; the alias lets the host use the same relay URL.
set -euo pipefail

cd "$(dirname "$0")/.."
work=$(mktemp -d)
relay_url="http://10.0.2.2:3340"
pids=()
# shellcheck disable=SC2329 # Called by the EXIT trap.
cleanup() {
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  echo "--- relay log ---"; cat "$work/relay.log" 2>/dev/null || true
  echo "--- daemon log ---"; cat "$work/daemon.log" 2>/dev/null || true
  echo "--- approver log ---"; cat "$work/approver.log" 2>/dev/null || true
  # The test's progress and, on failure, the assertion and its stack trace.
  echo "--- test log ---"; adb logcat -d -s ExitCriterion:I TestRunner:* AndroidRuntime:E 2>/dev/null || true
}
trap cleanup EXIT

ip addr show lo | grep -q "10.0.2.2/32" || sudo ip addr add 10.0.2.2/32 dev lo

# A development relay over plain HTTP. Metrics are off: they bind an IPv6 port some hosts lack.
printf 'enable_metrics = false\nhttp_bind_addr = "0.0.0.0:3340"\n' > "$work/relay.toml"
iroh-relay --dev --config-path "$work/relay.toml" > "$work/relay.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 30); do
  (exec 3<>/dev/tcp/127.0.0.1/3340) 2>/dev/null && break
  sleep 1
done
(exec 3<>/dev/tcp/127.0.0.1/3340) 2>/dev/null || { echo "iroh-relay did not start" >&2; exit 1; }

# The laptop: a daemon holding the user key, a full replica of two folders with one file each.
tidefelld init --data-dir "$work/laptop" --name laptop
user=$(tidefelld id --data-dir "$work/laptop" | awk '$1 == "user:" { print $2 }')
folder=$(tidefelld new-folder-id)
camera=$(tidefelld new-folder-id)
mkdir -p "$work/Photos" "$work/Camera"
printf 'sand and sea' > "$work/Photos/beach.jpg"
printf 'made on the laptop' > "$work/Camera/desktop.txt"
cat > "$work/tidefelld.toml" <<TOML
data_dir = "$work/laptop"

[network]
relay = { mode = "custom", urls = ["$relay_url"] }
address_lookup = false
local_discovery = false
bind = "0.0.0.0:7777"

[[folders]]
id = "$folder"
path = "$work/Photos"
members = ["$user"]
retention_days = 30
scan_interval_secs = 1

[[folders]]
id = "$camera"
path = "$work/Camera"
members = ["$user"]
retention_days = 30
scan_interval_secs = 1
TOML
RUST_LOG=info,tidefell_net=debug tidefelld run --config "$work/tidefelld.toml" > "$work/daemon.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 30); do
  [ -S "$work/laptop/control.sock" ] && break
  sleep 1
done
[ -S "$work/laptop/control.sock" ] || { echo "tidefelld did not start" >&2; exit 1; }

# Approve the emulator's pairing code as soon as it appears in logcat.
adb logcat -c
(
  until code=$(adb logcat -d -s ExitCriterion:I | grep -o 'TFP1-[A-Z0-9]*' | tail -1) && [ -n "$code" ]; do
    sleep 1
  done
  tidefell --data-dir "$work/laptop" pair --yes --name "Emulator" "$code"
) > "$work/approver.log" 2>&1 &
pids+=($!)

./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.tidefell.android.ExitCriterionTest \
  -Pandroid.testInstrumentationRunnerArguments.tidefellRelay="$relay_url"

# The file saved on the emulator and the photo taken there reached the daemon, and the file
# deleted on the phone is still on the daemon.
saved=false
photo=false
for _ in $(seq 1 60); do
  [ "$(cat "$work/Photos/from-android.txt" 2>/dev/null)" = "hello from android" ] && saved=true
  [ -f "$work/Camera/IMG_0001.jpg" ] && photo=true
  if $saved && $photo; then break; fi
  sleep 1
done
kept=false
[ "$(cat "$work/Camera/desktop.txt" 2>/dev/null)" = "made on the laptop" ] && kept=true
if $saved && $photo && $kept; then
  echo "exit criteria met: the saved file and the photo reached the daemon, and the phone's delete stayed on the phone"
  exit 0
fi
$saved || echo "from-android.txt did not reach the daemon" >&2
$photo || echo "Camera/IMG_0001.jpg did not reach the daemon" >&2
$kept || echo "Camera/desktop.txt was deleted on the daemon" >&2
exit 1
