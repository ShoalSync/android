#!/usr/bin/env bash
# Runs FileNamesTest (ADR 0033) on a running emulator against tidefelld on this host: names that
# fold together in shared storage, and a name a FAT volume refuses.
#
# Needs what scripts/ci-exit-criterion.sh needs, and an emulator image that allows `adb root`
# (google_apis), to add a virtual SD card formatted as FAT. Arguments are passed on to Gradle,
# such as -PtidefellFfiLocalPath=<monorepo>/bindings/android to test a monorepo checkout.
set -euo pipefail

cd "$(dirname "$0")/.."
work=$(mktemp -d)
relay_url="http://10.0.2.2:3340"
pids=()
# shellcheck disable=SC2329 # Called by the EXIT trap.
cleanup() {
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  # Gone before the next run binds the same ports.
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
  adb shell sm set-virtual-disk false >/dev/null 2>&1 || true
  echo "--- daemon log ---"; cat "$work/daemon.log" 2>/dev/null || true
  echo "--- approver log ---"; cat "$work/approver.log" 2>/dev/null || true
  echo "--- test log ---"; adb logcat -d -s FileNames:I TestRunner:* AndroidRuntime:E 2>/dev/null || true
}
trap cleanup EXIT

ip addr show lo | grep -q "10.0.2.2/32" || sudo ip addr add 10.0.2.2/32 dev lo

printf 'enable_metrics = false\nhttp_bind_addr = "0.0.0.0:3340"\n' > "$work/relay.toml"
iroh-relay --dev --config-path "$work/relay.toml" > "$work/relay.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 30); do
  (exec 3<>/dev/tcp/127.0.0.1/3340) 2>/dev/null && break
  sleep 1
done
(exec 3<>/dev/tcp/127.0.0.1/3340) 2>/dev/null || { echo "iroh-relay did not start" >&2; exit 1; }

# A virtual SD card, formatted as FAT by Android, mounted at /storage/<id>.
adb root >/dev/null
adb wait-for-device
adb shell sm set-virtual-disk true
disk=""
for _ in $(seq 1 30); do
  disk=$(adb shell sm list-disks | tr -d '\r' | head -1)
  [ -n "$disk" ] && break
  sleep 1
done
[ -n "$disk" ] || { echo "no virtual disk appeared" >&2; exit 1; }
adb shell sm partition "$disk" public
volume=""
for _ in $(seq 1 30); do
  volume=$(adb shell sm list-volumes public | tr -d '\r' | awk '$2 == "mounted" { print $3 }' | head -1)
  [ -n "$volume" ] && break
  sleep 1
done
[ -n "$volume" ] || { echo "the virtual disk did not mount" >&2; exit 1; }
adb shell mount | grep -q "/mnt/media_rw/$volume type vfat" || { echo "/storage/$volume is not FAT" >&2; exit 1; }

# The laptop: Names holds two photos whose names differ only in case, Card a name FAT refuses.
tidefelld init --data-dir "$work/laptop" --name laptop
user=$(tidefelld id --data-dir "$work/laptop" | awk '$1 == "user:" { print $2 }')
names=$(tidefelld new-folder-id)
card=$(tidefelld new-folder-id)
mkdir -p "$work/Names" "$work/Card"
printf 'upper' > "$work/Names/Photo.jpg"
printf 'lower' > "$work/Names/photo.jpg"
printf 'colon' > "$work/Card/a:b.txt"
printf 'fine' > "$work/Card/ok.txt"
cat > "$work/tidefelld.toml" <<TOML
data_dir = "$work/laptop"

[network]
relay = { mode = "custom", urls = ["$relay_url"] }
address_lookup = false
local_discovery = false
bind = "0.0.0.0:7777"

[[folders]]
id = "$names"
path = "$work/Names"
members = ["$user"]
retention_days = 30
scan_interval_secs = 1

[[folders]]
id = "$card"
path = "$work/Card"
members = ["$user"]
retention_days = 30
scan_interval_secs = 1
TOML
RUST_LOG=info tidefelld run --config "$work/tidefelld.toml" > "$work/daemon.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 30); do
  [ -S "$work/laptop/control.sock" ] && break
  sleep 1
done
[ -S "$work/laptop/control.sock" ] || { echo "tidefelld did not start" >&2; exit 1; }

adb logcat -c
(
  until code=$(adb logcat -d -s FileNames:I | grep -o 'TFP1-[A-Z0-9]*' | tail -1) && [ -n "$code" ]; do
    sleep 1
  done
  tidefell --data-dir "$work/laptop" pair --yes --name "Emulator" "$code"
) > "$work/approver.log" 2>&1 &
pids+=($!)

status=0
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.tidefell.android.FileNamesTest \
  -Pandroid.testInstrumentationRunnerArguments.tidefellRelay="$relay_url" \
  -Pandroid.testInstrumentationRunnerArguments.tidefellFatVolume="/storage/$volume" "$@" || status=$?

# The daemon keeps both photos, as they were.
echo "daemon's Names: $(cd "$work/Names" && for f in *; do printf '%s=%s ' "$f" "$(cat "$f")"; done)"
if [ "$(cat "$work/Names/Photo.jpg" 2>/dev/null)" != "upper" ] || [ "$(cat "$work/Names/photo.jpg" 2>/dev/null)" != "lower" ]; then
  echo "the daemon's photos changed" >&2
  status=1
fi
# The rename made on the phone reached the daemon.
renamed=false
for _ in $(seq 1 30); do
  [ "$(cat "$work/Card/a-b.txt" 2>/dev/null)" = "colon" ] && [ ! -e "$work/Card/a:b.txt" ] && renamed=true && break
  sleep 1
done
if ! $renamed; then
  echo "the daemon did not get the rename of a:b.txt" >&2
  status=1
fi
[ "$status" = 0 ] && echo "file names: both photos survived, a:b.txt was a problem, and its rename reached the daemon"
exit "$status"
