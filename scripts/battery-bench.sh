#!/usr/bin/env bash
# The battery benchmark (ADR 0039): measures what the app costs on a running emulator or phone
# against the pinned release's shoald on this host, and prints a table for the release notes.
#
# Scenarios, each measured on its own from a reset of the phone's battery statistics:
#   idle_service  the peer running in its foreground service, nothing to sync, for BENCH_MINUTES
#   receive       BENCH_MIB of files from the host (1 GiB by default), downloaded into a two-way
#                 phone folder
#   backup        BENCH_MIB of photos added on the phone, backed up to the host
#
# Once opened, the app keeps its service running until Android stops it, which the shell cannot
# do without also cancelling the app's scheduled work, so the cost of that work alone is not
# measured here.
#
# For each: the app's CPU time, its wake-up alarms, jobs and partial wakelocks, and its network
# bytes, from `dumpsys batterystats`. Idle figures are per hour, transfers per GiB. On an emulator
# the battery's energy figures come from a placeholder power profile and are left out: only a
# phone's are worth reporting.
#
# Needs what scripts/ci-exit-criterion.sh needs: shoald and shoal on PATH, iroh-relay on
# PATH, adb connected to one device, and sudo to add 10.0.2.2 to the loopback interface. Run it
# only while CI is not using the emulator. Arguments are passed on to Gradle, such as
# -PshoalFfiLocalPath=<monorepo>.
set -euo pipefail

cd "$(dirname "$0")/.."
minutes=${BENCH_MINUTES:-10}
# Which scenarios to run, for looking into one: idle_service, receive, backup.
scenarios=" ${BENCH_SCENARIOS:-idle_service receive backup} "
mib=${BENCH_MIB:-1024}
package=org.shoalsync.android
work=$(mktemp -d)
relay_url="http://10.0.2.2:3340"
pids=()
# shellcheck disable=SC2329 # Called by the EXIT trap.
cleanup() {
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
  adb shell dumpsys battery reset > /dev/null 2>&1 || true
  adb shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
  adb shell am force-stop "$package" > /dev/null 2>&1 || true
  # BENCH_KEEP_LOGS names a directory to keep the logs of the run in.
  if [ -n "${BENCH_KEEP_LOGS:-}" ]; then cp "$work"/*.log "$work"/logcat.txt "$BENCH_KEEP_LOGS/" 2>/dev/null || true; fi
  rm -rf "$work"
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

# The host: a full replica of the two folders the phone joins.
shoald init --data-dir "$work/host" --name host > /dev/null
user=$(shoald id --data-dir "$work/host" | awk '$1 == "user:" { print $2 }')
mkdir -p "$work/In" "$work/Camera"
{
  printf 'data_dir = "%s"\n\n[network]\n' "$work/host"
  printf 'relay = { mode = "custom", urls = ["%s"] }\n' "$relay_url"
  printf 'address_lookup = false\nlocal_discovery = false\nbind = "0.0.0.0:7777"\n'
  for name in In Camera; do
    printf '\n[[folders]]\nid = "%s"\npath = "%s"\nmembers = ["%s"]\n' \
      "$(shoald new-folder-id)" "$work/$name" "$user"
    printf 'retention_days = 30\nscan_interval_secs = 5\n'
  done
} > "$work/shoald.toml"
# BENCH_DAEMON_LOG sets the host's log filter, such as "info,shoal_engine=debug".
RUST_LOG="${BENCH_DAEMON_LOG:-info}" shoald run --config "$work/shoald.toml" > "$work/daemon.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 30); do
  [ -S "$work/host/control.sock" ] && break
  sleep 1
done

adb logcat -c
(
  until code=$(adb logcat -d -s BatteryBench:I | grep -o 'TFP1-[A-Z0-9]*' | tail -1) && [ -n "$code" ]; do
    sleep 1
  done
  shoal --data-dir "$work/host" pair --yes --name "Benchmark" "$code"
) > "$work/approver.log" 2>&1 &
pids+=($!)

# Pair and join, leaving the app installed for the measurements.
./gradlew --no-daemon -q :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.shoalsync.android.BatterySetup \
  -Pandroid.testInstrumentationRunnerArguments.shoalRelay="$relay_url" \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true "$@"

uid=$(adb shell pm list packages -U "$package" | tr -d '\r' | awk -v p="package:$package" '$1 == p { sub("uid:", "", $2); print $2 }')
# The app asks for this when it opens; granted here, nothing waits on the dialog.
adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS
adb logcat -c
adb logcat -v time > "$work/logcat.txt" 2>&1 &
pids+=($!)
adb shell dumpsys battery unplug

# The app's figures since the last reset of the battery statistics, as "cpu_ms alarms jobs
# wakelocks".
figures() {
  adb shell dumpsys batterystats --checkin | python3 -c '
import sys
uid = sys.argv[1]
cpu = alarms = jobs = wakelocks = 0
for line in sys.stdin:
    f = line.strip().split(",")
    if len(f) < 5 or f[1] != uid or f[2] != "l":
        continue
    kind, rest = f[3], f[4:]
    if kind == "cpu":
        cpu += int(rest[0]) + int(rest[1])
    elif kind == "wua":
        alarms += int(rest[-1])
    elif kind == "jb":
        jobs += int(rest[-1]) if len(rest) > 2 else 1
    elif kind == "wl":
        # name, full ms, "f", count, partial ms, "p", count, ...
        wakelocks += int(rest[6]) if len(rest) > 6 else 0
print(cpu, alarms, jobs, wakelocks)
' "$uid"
}

# Bytes the app has sent and received, by the network statistics, which keep no reset of their own.
net_bytes() {
  adb shell dumpsys netstats --poll > /dev/null 2>&1 || true
  adb shell dumpsys netstats detail | tr -d '\r' | awk -v uid="$uid" '
    /^ *ident=/ { counting = index($0, " uid=" uid " ") > 0 && index($0, "tag=0x0") > 0; next }
    counting && / st=/ {
      for (i = 1; i <= NF; i++) { split($i, kv, "="); if (kv[1] == "rb" || kv[1] == "tb") total += kv[2] }
    }
    END { print total + 0 }'
}

# Budgets (ADR 0039), from the first emulator results with room to spare: CPU seconds per hour
# idle, CPU seconds per GiB moved, and network bytes per byte of file content moved. A run over
# any of them fails.
budget_idle_cpu=15
budget_gib_cpu_receive=120
budget_gib_cpu_backup=180
budget_net_overhead=1.25
over=()

rows=()
begin() { adb shell dumpsys batterystats --reset > /dev/null; net_before=$(net_bytes); started=$(date +%s); }
# Records scenario $1, scaled to per hour (per = hour) or per GiB (per = gib).
finish() {
  local name=$1 per=$2 seconds scale cpu alarms jobs wakelocks net
  seconds=$(( $(date +%s) - started ))
  read -r cpu alarms jobs wakelocks < <(figures)
  net=$(( $(net_bytes) - net_before ))
  if [ "$per" = hour ]; then scale=$(python3 -c "print(3600 / $seconds)"); else scale=$(python3 -c "print(1024 / $mib)"); fi
  local budget=""
  case $name in
    idle_service) budget=$budget_idle_cpu ;;
    receive) budget=$budget_gib_cpu_receive ;;
    backup) budget=$budget_gib_cpu_backup ;;
  esac
  if python3 -c "import sys; sys.exit(0 if $cpu / 1000 * $scale > $budget else 1)"; then
    over+=("$name: CPU over its budget of $budget s per $per")
  fi
  if [ "$per" = gib ] && python3 -c "import sys; sys.exit(0 if $net > $budget_net_overhead * $mib * 1048576 else 1)"; then
    over+=("$name: network over $budget_net_overhead times the content moved")
  fi
  rows+=("$(python3 -c "
s = $scale
print('| $name | $seconds s | %.1f s | %.0f | %.0f | %.0f | %.1f MB | per $per |' % (
    $cpu / 1000 * s, $alarms * s, $jobs * s, $wakelocks * s, $net / 1e6 * s))")")
  echo "measured $name"
}

# Waits until the files under directory $1 (on the host, or on the phone if $2 is "phone") add
# up to at least $3 bytes.
wait_for_bytes() {
  local dir=$1 where=$2 want=$3 have
  for _ in $(seq 1 1800); do
    if [ "$where" = phone ]; then
      # Files the app writes come and go while it does, which find may complain of.
      have=$( (adb shell "find $dir -type f -exec stat -c %s {} + 2>/dev/null" || true) | tr -d '\r' | awk '{ s += $1 } END { print s + 0 }')
    else
      have=$( (find "$dir" -type f -printf '%s\n' 2>/dev/null || true) | awk '{ s += $1 } END { print s + 0 }')
    fi
    [ "$have" -ge "$want" ] && return 0
    sleep 1
  done
  echo "timed out waiting for $want bytes in $dir; the app's warnings:" >&2
  grep -iE "shoal.*(warn|error)" "$work/logcat.txt" | tail -40 >&2 || true
  echo "--- daemon log ---" >&2
  tail -40 "$work/daemon.log" >&2
  echo "--- device idle state: $(adb shell dumpsys deviceidle get deep | tr -d '\r') ---" >&2
  return 1
}

bytes=$(( mib * 1024 * 1024 ))
# Photo-sized files, about 5 MiB each.
files=$(( (mib + 4) / 5 ))
size=$(( bytes / files ))

# 1. Idle with the foreground service, once the peer has connected: the app opened, then left,
# and the screen turned off.
# A fresh start, on an unlocked screen: an activity the setup left would only be brought to the
# front, and none starts while the screen is off.
adb shell am force-stop "$package"
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am start -W --activity-clear-task -n "$package/.ui.MainActivity" > /dev/null
sleep 10
adb shell input keyevent KEYCODE_HOME
adb shell input keyevent KEYCODE_SLEEP
sleep 60
if [[ $scenarios == *" idle_service "* ]]; then
  begin
  sleep $(( minutes * 60 ))
  finish idle_service hour
fi

# 2. Receiving: the host adds files to the two-way folder.
if [[ $scenarios == *" receive "* ]]; then
  begin
  for i in $(seq 1 "$files"); do head -c "$size" /dev/urandom > "$work/In/file-$i.bin"; done
  wait_for_bytes /sdcard/ShoalBench/In phone $(( size * files ))
  finish receive gib
fi

if [[ $scenarios == *" backup "* ]]; then
  # 3. Backing up: photos added to the phone folder while the peer runs, which scans it on its own.
  # They are copied to the phone first and moved in at once, so copying them is not measured.
  adb shell mkdir -p /sdcard/ShoalBench/staging /sdcard/DCIM/ShoalBench
  for i in $(seq 1 "$files"); do
    head -c "$size" /dev/urandom > "$work/photo.jpg"
    adb push -q "$work/photo.jpg" "/sdcard/ShoalBench/staging/IMG_$i.jpg" > /dev/null
  done
  begin
  adb shell 'mv /sdcard/ShoalBench/staging/* /sdcard/DCIM/ShoalBench/'
  # A mark in the phone's log, to time what follows by the phone's own clock.
  adb shell log -t BatteryBench "photos moved in"
  wait_for_bytes "$work/Camera" host $(( size * files ))
  finish backup gib
fi

device=$(adb shell getprop ro.product.model | tr -d '\r')
kind=$(adb shell getprop ro.kernel.qemu | tr -d '\r')
echo
echo "Battery benchmark on $device$([ "$kind" = 1 ] && echo ' (emulator: no energy figures)'), $(date -u +%Y-%m-%d)"
echo
echo "| Scenario | Ran | CPU | Wake-up alarms | Jobs | Wakelocks | Network | |"
echo "|---|---|---|---|---|---|---|---|"
printf '%s\n' "${rows[@]}"
if [ ${#over[@]} -gt 0 ]; then
  echo
  printf 'over budget: %s\n' "${over[@]}" >&2
  exit 1
fi
