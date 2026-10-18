#!/usr/bin/env bash
# Checks out the pinned monorepo release (`tidefell` in gradle/libs.versions.toml) into <dir>,
# build/tidefell unless given, for the build to compile tidefell-ffi from (ADR 0014 as amended).
# A checkout from an earlier run is moved to the tag rather than cloned again, so its target/
# keeps cargo's incremental state.
#
# The monorepo is private: git needs an SSH key that can read tidefell/tidefell on Forgejo
# (GIT_SSH_COMMAND in CI).
set -euo pipefail

cd "$(dirname "$0")/.."
version=$(sed -n 's/^tidefell = "\(.*\)"/\1/p' gradle/libs.versions.toml)
dir=${1:-build/tidefell}
url=ssh://git@github.com/ShoalSync/shoal.git

if [ ! -d "$dir/.git" ]; then
  rm -rf "$dir"
  git init -q "$dir"
  git -C "$dir" remote add origin "$url"
fi
git -C "$dir" fetch -q --depth 1 origin "refs/tags/v$version:refs/tags/v$version"
git -C "$dir" -c advice.detachedHead=false checkout -q --force "v$version"
git -C "$dir" clean -q -fdx --exclude=/target
echo "$dir at v$version ($(git -C "$dir" rev-parse --short HEAD))"
