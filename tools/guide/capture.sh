#!/usr/bin/env sh
# Regenerates the README's guide screenshots (docs/guide/) on a connected
# emulator: runs the GuideScreenshots test, pulls its PNG + JSON pairs and
# draws the numbered arrows. Needs adb on PATH and Python with Pillow.
set -e
cd "$(dirname "$0")/../.."

# Without all files access, so the "allow all files access" steps show up.
adb shell appops set com.tune.music MANAGE_EXTERNAL_STORAGE default || true
# A tidy status bar.
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" > /dev/null; }
demo enter
demo clock -e hhmm 0930
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4
demo notifications -e visible false

adb shell rm -rf /data/local/tmp/tune-guide
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.guide=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.tune.music.GuideScreenshots

demo exit
out=$(mktemp -d)
adb pull /data/local/tmp/tune-guide "$out"
python tools/guide/annotate.py "$out/tune-guide"
