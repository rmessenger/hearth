#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
fixture_dir=$(mktemp -d)
trap 'rm -rf "$fixture_dir"' EXIT
ffmpeg -v error -f lavfi -i 'sine=frequency=220:duration=125' -af 'volume=0.05' "$fixture_dir/audio.mp3"
adb shell mkdir -p /sdcard/Audiobooks
adb push "$fixture_dir/audio.mp3" /sdcard/hearth-test.mp3
ffmpeg -v error -i "$repo_dir/demo/lantern-keeper-cover.png" -q:v 2 "$fixture_dir/cover.jpg"
adb push "$fixture_dir/cover.jpg" /sdcard/hearth-test-cover.jpg
