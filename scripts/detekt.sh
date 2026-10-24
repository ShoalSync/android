#!/usr/bin/env bash
# Runs detekt with its formatting rules on the app's Kotlin (ADR 0031). The CLI is used rather
# than the Gradle plugin, which is built against an older Kotlin than the app's. Pass
# --create-baseline to record the current findings in detekt-baseline.xml.
set -euo pipefail

cd "$(dirname "$0")/.."
version=1.23.8
cache="${XDG_CACHE_HOME:-$HOME/.cache}/shoal-detekt/$version"
mkdir -p "$cache"
fetch() {
  local name=$1 sha=$2
  if ! echo "$sha  $cache/$name" | sha256sum --check --status 2>/dev/null; then
    curl -sfL -o "$cache/$name" "https://repo1.maven.org/maven2/io/gitlab/arturbosch/detekt/${name%-"$version"*}/$version/$name"
    echo "$sha  $cache/$name" | sha256sum --check --quiet
  fi
}
fetch "detekt-cli-$version-all.jar" 3afe89a11120303c73c9bdda3d8fe558dd9070a6937d27819ddc04b275381245
fetch "detekt-formatting-$version.jar" a18a3f680d232ad746ccec3d5a251776ce39131b395c455b92e1bb0a9e8fb6ba

"${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$cache/detekt-cli-$version-all.jar" \
  --input app/src \
  --plugins "$cache/detekt-formatting-$version.jar" \
  --config detekt.yml --build-upon-default-config \
  --baseline detekt-baseline.xml "$@"
