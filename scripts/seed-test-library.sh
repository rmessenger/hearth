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

cat > "$fixture_dir/chapters.txt" <<'CHAPTERS'
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=40000
title=The Beginning
[CHAPTER]
TIMEBASE=1/1000
START=40000
END=80000
title=The Journey
[CHAPTER]
TIMEBASE=1/1000
START=80000
END=125000
title=The Homecoming
CHAPTERS
ffmpeg -v error -i "$fixture_dir/audio.mp3" -i "$fixture_dir/chapters.txt" -map_metadata 1 -map_chapters 1 -c:a aac -b:a 48k "$fixture_dir/chapters.m4b"
adb push "$fixture_dir/chapters.m4b" /sdcard/hearth-test-chapters.m4b
