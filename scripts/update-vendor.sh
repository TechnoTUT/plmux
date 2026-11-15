#!/usr/bin/env bash
# vendor/ の submodule (beat-link, crate-digger) を各リポジトリの main 最新に更新し、
# ルート pom.xml の beatlink.version を beat-link の実バージョンに合わせる。
set -euo pipefail
cd "$(dirname "$0")/.."

git submodule update --init --remote vendor/beat-link vendor/crate-digger

bl_version=$(sed -n '0,/<version>/s:.*<version>\(.*\)</version>.*:\1:p' vendor/beat-link/pom.xml)
sed -i "s:<beatlink.version>.*</beatlink.version>:<beatlink.version>${bl_version}</beatlink.version>:" pom.xml

echo "beat-link    : $(git -C vendor/beat-link log --oneline -1) (version ${bl_version})"
echo "crate-digger : $(git -C vendor/crate-digger log --oneline -1)"
echo
echo "ビルド確認: ./mvnw -q package && git add vendor pom.xml && git commit"
