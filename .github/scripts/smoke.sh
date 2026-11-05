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
crashcheck() {
  adb logcat -d > out/logcat_$1.txt
  if grep -q "FATAL EXCEPTION" out/logcat_$1.txt; then
    echo "===== CRASH in $1 ====="; grep -A60 "FATAL EXCEPTION" out/logcat_$1.txt | head -70
    adb logcat -c; adb shell am start -n com.animora.studio/.MainActivity; sleep 8
  else echo "OK: $1"; fi
}
screen_texts() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]\+"' | head -12 | tr '\n' ' '; echo; }
read W H < <(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | tr 'x' ' ')
echo "SCREEN SIZE $W x $H"
scroll_down() { for i in 1 2 3 4 5 6; do adb shell input swipe $((W/2)) $((H*8/10)) $((W/2)) $((H*2/10)) 150; sleep 0.5; done; }
shot 1_home
run_case() { # $1 имя, $2 шаблон, $3 пресет
  tap "Новый проект"; sleep 4
  [ -n "$3" ] && tap "$3"
  if [ -n "$2" ]; then scroll_down; tap "$2"; fi
  sleep 1; tap "Создать"; sleep 12; shot "case_$1"
  echo "SCREEN[$1]: $(screen_texts)"
  adb shell input swipe $((W/2)) $((H/2)) $((W*6/10)) $((H*6/10)) 400; sleep 3
  crashcheck "$1"
  adb shell input keyevent 4; sleep 5
  crashcheck "$1_back"
}
run_case fbf "" ""
run_case cutout "Перекладка + кости" ""
run_case tween "Анимация движения" ""
run_case empty "Пустой" ""
run_case k4 "" "4K UHD"
run_case reels "" "Reels / TikTok / Shorts"
adb logcat -d > out/logcat.txt
exit 0
