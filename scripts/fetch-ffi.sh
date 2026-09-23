#!/usr/bin/env bash
# Downloads the tidefell-ffi AAR of the pinned monorepo release into app/libs/ and checks it.
#
# The monorepo is private, so `gh` needs access to tidefell/tidefell (GH_TOKEN in CI).
set -euo pipefail

cd "$(dirname "$0")/.."
version=$(sed -n 's/^tidefell = "\(.*\)"/\1/p' gradle/libs.versions.toml)
name="tidefell-ffi-$version.aar"
mkdir -p app/libs
if [ -f "app/libs/$name" ]; then
  echo "app/libs/$name already present"
  exit 0
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
gh release download "v$version" --repo tidefell/tidefell --dir "$work" \
  --pattern "$name" --pattern "$name.sha256"
(cd "$work" && sha256sum --check "$name.sha256")
mv "$work/$name" app/libs/
echo "app/libs/$name"
