#!/usr/bin/env bash
# 안드로이드 에뮬레이터에서 기내톡 앱을 설치·실행하고, 앱 안의 채팅 서버를 API 테스트로 검사합니다.
# (GitHub Actions의 android-emulator-runner 안에서 실행)
set -uo pipefail
OUT=emu-out
PKG=io.github.songlomin.skytalk
mkdir -p "$OUT"
shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }

adb install -r -g out/SkyTalk.apk || { echo "install failed"; exit 1; }
adb shell dumpsys package "$PKG" | grep -E "versionName|targetSdk" | head -3

# 1) 처음 화면
adb shell am start -W -n "$PKG/.MainActivity"
sleep 6
shot 1-start

# 2) 이 폰으로 방 만들기 (테스트용 자동 시작)
adb shell am start -n "$PKG/.MainActivity" --es autostart host
ok=0
for i in $(seq 1 45); do
  adb forward tcp:18080 tcp:8080 >/dev/null 2>&1
  if curl -fsS --max-time 3 http://127.0.0.1:18080/api/info > "$OUT/info.json" 2>/dev/null; then ok=1; break; fi
  sleep 2
done
echo "info: $(cat "$OUT/info.json" 2>/dev/null)"
sleep 4
shot 2-host-start
if [ "$ok" != 1 ]; then
  echo "chat server did not start"
  adb logcat -d > "$OUT/logcat.txt"
  exit 1
fi

# 3) 노트북 서버와 같은 API 테스트를 안드로이드 서버에 실행
python3 tests/api_test.py url http://127.0.0.1:18080 | tee "$OUT/api-test.txt"
rc=${PIPESTATUS[0]}

# 4) 보기 좋은 대화 몇 줄을 넣고 앱 안 채팅 화면 확인
post() {
  curl -fsS -X POST -H 'Content-Type: application/json' --data "$1" http://127.0.0.1:18080/api/send > /dev/null || echo "post failed"
}
post '{"uid":"udemo0001","name":"지영","seat":"41C","cid":"cdemo0001","text":"다들 자리 어디예요? 저 41C!","img":""}'
post '{"uid":"udemo0002","name":"현우","seat":"18F","cid":"cdemo0002","text":"18F요. 앞쪽이라 기내식 먼저 나오는 듯","img":""}'
post '{"uid":"udemo0001","name":"지영","seat":"41C","cid":"cdemo0003","text":"착륙하면 3번 벨트 앞에서 모여요","img":""}'
adb shell am start -n "$PKG/.MainActivity" --es open chat
sleep 6
shot 3-chat-profile
adb shell input text "Host"
adb shell input keyevent 66
sleep 1
adb shell input text "12A"
adb shell input keyevent 66
sleep 4
shot 4-chat

# 5) 앱을 내려 둔 상태에서 새 메시지 → 알림이 뜨는지
adb shell input keyevent 3
sleep 2
post '{"uid":"udemo0002","name":"현우","seat":"18F","cid":"cdemo0004","text":"알림 테스트: 화장실 줄 길어요","img":""}'
sleep 4
adb shell dumpsys notification --noredact > "$OUT/notifications.txt" 2>/dev/null || true
if grep -q "알림 테스트" "$OUT/notifications.txt"; then
  echo "  ok   message notification shown while app is in background"
else
  echo "  FAIL message notification not found"
  rc=1
fi
adb shell cmd statusbar expand-notifications || true
sleep 2
shot 5-notification
adb shell cmd statusbar collapse || true

# 6) 서비스 상태와 로그
adb shell dumpsys activity services "$PKG" > "$OUT/services.txt" 2>/dev/null || true
grep -E "HostService|isForeground|foregroundServiceType" "$OUT/services.txt" | head -5
adb logcat -d -v time > "$OUT/logcat.txt" 2>/dev/null || true
grep -E "SkyTalk|AndroidRuntime" "$OUT/logcat.txt" | tail -40

exit "$rc"
