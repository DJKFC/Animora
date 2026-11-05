#!/bin/bash
# Смоук-тест: запуск, создание проекта, мазок, сбор logcat и скриншотов
set -x
mkdir -p out
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -n com.animora.studio/.MainActivity
sleep 10
shot() { adb exec-out screencap -p > "out/$1.png"; }
tap() {
  for i in $(seq 1 15); do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb shell cat /sdcard/ui.xml > out/ui.xml
    xy=$(TXT="$1" python3 - <<'PY'
import os, re
s = open('out/ui.xml', encoding='utf-8', errors='ignore').read()
t = re.escape(os.environ['TXT'])
m = re.search(r'text="' + t + r'"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
print(f"{(int(m[1])+int(m[3]))//2} {(int(m[2])+int(m[4]))//2}" if m else "")
PY
)
    if [ -n "$xy" ]; then adb shell input tap $xy; return 0; fi
    sleep 2
  done
  echo "NOT FOUND: $1"; return 1
}
shot 1_home
tap "Новый проект"; sleep 5; shot 2_new
tap "Создать"; sleep 10; shot 3_editor
adb shell input swipe 600 900 800 1100 400; sleep 3; shot 4_stroke
adb logcat -d > out/logcat.txt
echo "===== CRASH ====="
grep -A60 "FATAL EXCEPTION" out/logcat.txt || echo "NO CRASH FOUND"
grep -E "AndroidRuntime|animora" out/logcat.txt | tail -80
exit 0
