#!/usr/bin/env bash
# Runs the Phase 4 exit criterion (ExitCriterionTest) on a running emulator against the pinned
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
cleanup() {
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  echo "--- relay log ---"; cat "$work/relay.log" 2>/dev/null || true
  echo "--- daemon log ---"; cat "$work/daemon.log" 2>/dev/null || true
  echo "--- approver log ---"; cat "$work/approver.log" 2>/dev/null || true
}
trap cleanup EXIT

ip addr show lo | grep -q "10.0.2.2/32" || sudo ip addr add 10.0.2.2/32 dev lo

# A development relay over plain HTTP. Metrics are off: they bind an IPv6 port some hosts lack.
printf 'enable_metrics = false\nhttp_bind_addr = "0.0.0.0:3340"\n' > "$work/relay.toml"
iroh-relay --dev --config-path "$work/relay.toml" > "$work/relay.log" 2>&1 &
pids+=($!)

# The laptop: a daemon holding the user key, with one folder and one file.
tidefelld init --data-dir "$work/laptop" --name laptop
user=$(tidefelld id --data-dir "$work/laptop" | sed -n 's/^user: //p')
folder=$(tidefelld new-folder-id)
mkdir -p "$work/Photos"
printf 'sand and sea' > "$work/Photos/beach.jpg"
cat > "$work/tidefelld.toml" <<TOML
data_dir = "$work/laptop"

[network]
relay = { mode = "custom", urls = ["$relay_url"] }
address_lookup = false
bind = "0.0.0.0:7777"

[[folders]]
id = "$folder"
path = "$work/Photos"
members = ["$user"]
retention_days = 30
scan_interval_secs = 1
TOML
RUST_LOG=info,tidefell_net=debug tidefelld run --config "$work/tidefelld.toml" > "$work/daemon.log" 2>&1 &
pids+=($!)

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
  -Pandroid.testInstrumentationRunnerArguments.tidefellRelay="$relay_url"

# The file saved on the emulator reached the daemon.
for _ in $(seq 1 60); do
  [ "$(cat "$work/Photos/from-android.txt" 2>/dev/null)" = "hello from android" ] && {
    echo "exit criterion met: from-android.txt synced to the daemon"
    exit 0
  }
  sleep 1
done
echo "from-android.txt did not reach the daemon" >&2
exit 1
