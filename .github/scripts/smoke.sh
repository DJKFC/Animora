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
m = re.search(r'(?:text|content-desc)="' + t + r'"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
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
tap_once() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > out/ui.xml
  xy=$(TXT="$1" python3 - <<'PY'
import os, re
s = open('out/ui.xml', encoding='utf-8', errors='ignore').read()
t = re.escape(os.environ['TXT'])
m = re.search(r'(?:text|content-desc)="' + t + r'"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
print(f"{(int(m[1])+int(m[3]))//2} {(int(m[2])+int(m[4]))//2}" if m else "")
PY
)
  [ -n "$xy" ] && adb shell input tap $xy && return 0
  return 1
}
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
# длинный проект (30 минут) и прокрутка таймлайна
tap "Новый проект"; sleep 4
found=0
for i in 1 2 3 4 5 6 7 8; do
  if tap_once "30 мин"; then found=1; break; fi
  adb shell input swipe $((W/2)) $((H*7/10)) $((W/2)) $((H*4/10)) 300; sleep 1
done
echo "LONG chip found=$found"
sleep 1; tap "Создать"; sleep 15; shot long_project
echo "SCREEN[long]: $(screen_texts)"
for i in 1 2 3 4 5; do adb shell input swipe $((W*8/10)) $((H*93/100)) $((W*2/10)) $((H*93/100)) 120; sleep 0.5; done; shot long_scrolled
crashcheck long
adb shell input keyevent 4; sleep 5
crashcheck long_back
# новые окна: настройка интерфейса и редактор кривых (на шаблоне с твином)
tap "Новый проект"; sleep 4; scroll_down; tap "Анимация движения"; sleep 1; tap "Создать"; sleep 10
tap "Ещё"; sleep 2; tap "Настроить интерфейс…"; sleep 3; shot ui_dialog
tap "Для левши"; sleep 2; tap "Крупные кнопки"; sleep 2; shot ui_big
tap "Закрыть"; sleep 3; shot ui_after
crashcheck ui_settings
tap "Ещё"; sleep 2; tap "Кривые анимации…"; sleep 3; shot curves
tap "Отскок"; sleep 2; tap "Отдельные кривые для свойств"; sleep 2; tap "Поворот"; sleep 1; tap "Замах (антиципация)"; sleep 2
adb shell input swipe $((W/2)) $((H*4/10)) $((W*6/10)) $((H*3/10)) 300; sleep 2; shot curves_edit
tap "Закрыть"; sleep 2
adb shell input keyevent 4; sleep 2
crashcheck curves
tap "Ещё"; sleep 2; tap "Настроить интерфейс…"; sleep 3; tap "Стандарт"; sleep 2; tap "Закрыть"; sleep 2
crashcheck ui_reset
# камера и lip-sync (этап 1)
tap "Новый проект"; sleep 4; tap "Создать"; sleep 10
tap "Камера"; sleep 3
adb shell input swipe $((W/2)) $((H/2)) $((W*7/10)) $((H*6/10)) 400; sleep 2
tap "+ Ключ"; sleep 1
for i in 1 2 3; do adb shell input swipe $((W*8/10)) $((H*93/100)) $((W*3/10)) $((H*93/100)) 150; sleep 0.5; done
adb shell input swipe $((W/2)) $((H/2)) $((W*4/10)) $((H*4/10)) 400; sleep 2; shot camera
crashcheck camera
tap "Ещё"; sleep 2; tap "Lip-sync по звуку…"; sleep 3; shot lipsync; tap "Отмена"; sleep 2
crashcheck lipsync
tap "Воспроизвести"; sleep 4; tap "Воспроизвести"; sleep 1
crashcheck camera_play
adb shell input keyevent 4; sleep 4
# символы (этап 2)
tap "Новый проект"; sleep 4; tap "Создать"; sleep 10
adb shell input swipe $((W*4/10)) $((H*4/10)) $((W*6/10)) $((H*5/10)) 400; sleep 2
tap "Лассо"; sleep 1
adb shell input tap $((W/2)) $((H*45/100)); sleep 2; shot sym_selected
tap "В символ"; sleep 3; shot sym_converted
tap "✎ Изменить символ"; sleep 3
adb shell input swipe $((W*4/10)) $((H*5/10)) $((W*6/10)) $((H*4/10)) 400; sleep 2; shot sym_inside
tap "← Назад"; sleep 3
tap "Библиотека (1)"; sleep 2; shot library
tap "На холст"; sleep 2; tap "Свойства…"; sleep 2; shot instance_props; tap "Готово"; sleep 1
tap "Воспроизвести"; sleep 3; tap "Воспроизвести"; sleep 1
crashcheck symbols
adb shell input keyevent 4; sleep 4
crashcheck symbols_back
# этап 3: текст, папки, выделение кадров, траектория
tap "Новый проект"; sleep 4; tap "Создать"; sleep 10
tap "Текст"; sleep 1
adb shell input tap $((W/2)) $((H*4/10)); sleep 2
tap "Текст (можно в несколько строк)"; sleep 1; adb shell input text "Animora"; sleep 1
tap "Готово"; sleep 2; shot text
tap "Папка"; sleep 2
tap "⧉ Выделить"; sleep 1
adb shell input swipe $((W*5/10)) $((H*92/100)) $((W*7/10)) $((H*92/100)) 500; sleep 2
tap "×2"; sleep 1; tap "1▶"; sleep 1; shot frames_sel
tap "⧉ Выделение: вкл"; sleep 1
tap "Трансформация ключа"; sleep 1; tap "〰 Траектория"; sleep 1
adb shell input swipe $((W*3/10)) $((H*5/10)) $((W*7/10)) $((H*3/10)) 600; sleep 2
tap "Воспроизвести"; sleep 3; tap "Воспроизвести"; sleep 1; shot guide
crashcheck stage3
adb shell input keyevent 4; sleep 4
crashcheck stage3_back
adb logcat -d > out/logcat.txt
exit 0
