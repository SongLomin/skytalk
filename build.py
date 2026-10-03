#!/usr/bin/env python3
"""client.html을 각 서버에 넣어 배포 파일(dist/)을 만듭니다.

  python build.py

  dist/SkyTalk-Windows.bat   Windows 노트북: 더블클릭 (PowerShell 서버 + 핫스팟)
  dist/skytalk.py            Mac·Linux·Termux: python3 skytalk.py
  dist/client.html           채팅 화면 (참고용)
"""
import base64
import pathlib
import shutil
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent
SRC = ROOT / "src"
DIST = ROOT / "dist"
ANDROID_ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"

BAT_HEADER = r"""<# : SkyTalk Windows launcher - double-click to start
@echo off
setlocal EnableExtensions DisableDelayedExpansion
title SkyTalk server
rem ====================== settings ======================
rem  Wi-Fi name, Wi-Fi password - 8 or more characters, port.
rem  You can change them with Notepad before starting.
set "SKYTALK_SSID=SkyTalk"
set "SKYTALK_PASS=skytalk1234"
set "SKYTALK_PORT=8080"
rem ======================================================
set "SKYTALK_BAT=%~f0"
set "SKYTALK_DATA=%~dp0SkyTalk-data"
fltmc >nul 2>&1 || (
  echo.
  echo   [SkyTalk] Administrator permission is needed so that phones can connect.
  echo   [SkyTalk] Please click "Yes" in the next window.
  powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Start-Process -FilePath $env:SKYTALK_BAT -Verb RunAs -ErrorAction Stop } catch { exit 1 }"
  if errorlevel 1 ( echo   [SkyTalk] Permission was not granted. Run this file again and click "Yes". & pause )
  exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s=[IO.File]::ReadAllText($env:SKYTALK_BAT,[Text.Encoding]::UTF8); & ([ScriptBlock]::Create($s)) -Port $env:SKYTALK_PORT -Ssid $env:SKYTALK_SSID -Pass $env:SKYTALK_PASS -DataDir $env:SKYTALK_DATA"
if errorlevel 1 pause
exit /b
#>
"""


def chunk(s, n):
    return [s[i:i + n] for i in range(0, len(s), n)]


def main():
    html = (SRC / "client.html").read_bytes()
    b64 = base64.b64encode(html).decode("ascii")
    DIST.mkdir(exist_ok=True)

    # Python 서버
    py = (SRC / "skytalk.py").read_text(encoding="utf-8")
    marker = 'HTML_B64 = ""  # build.py가 client.html을 여기에 넣습니다.'
    assert py.count(marker) == 1, "skytalk.py marker"
    lines = "\n".join('    "%s"' % c for c in chunk(b64, 100))
    py = py.replace(marker, "HTML_B64 = (\n" + lines + "\n)")
    (DIST / "skytalk.py").write_text(py, encoding="utf-8", newline="\n")

    # Windows (.bat = 배치 머리말 + PowerShell 본문)
    ps = (SRC / "server.ps1").read_text(encoding="utf-8")
    assert ps.count("$HtmlB64 = ''") == 1, "server.ps1 marker"
    assert "#>" not in ps, "server.ps1 must not contain '#>' (it would end the launcher comment)"
    ps = ps.replace("$HtmlB64 = ''", "$HtmlB64 = '" + b64 + "'")
    bat = (BAT_HEADER + ps).replace("\r\n", "\n")
    assert all(ord(c) < 128 for c in BAT_HEADER), "batch header must be ASCII"
    (DIST / "SkyTalk-Windows.bat").write_bytes(bat.replace("\n", "\r\n").encode("utf-8"))

    # 브라우저의 .bat 다운로드 경고를 피하려고 zip도 만듭니다.
    readme = (
        "기내톡 (SkyTalk) - Windows 방장용\r\n\r\n"
        "1. 비행기 모드를 켜고 Wi-Fi만 다시 켜세요. (아무 Wi-Fi에도 연결하지 않아도 돼요)\r\n"
        "2. SkyTalk-Windows.bat 을 더블클릭하세요.\r\n"
        "   - \"Windows의 PC 보호\" 창 → [추가 정보] → [실행]\r\n"
        "   - 관리자 권한을 물으면 → [예]\r\n"
        "3. 검은 창에 나온 Wi-Fi 이름·비밀번호·주소를 팀원에게 알려 주세요.\r\n"
        "   팀원: 그 Wi-Fi에 연결 → Safari/Chrome 주소창에 주소 입력 → 이름 입력\r\n"
        "4. 노트북 덮개는 닫지 말고 화면만 어둡게 해 두세요. (덮으면 절전으로 꺼질 수 있어요)\r\n\r\n"
        "Wi-Fi 이름·비밀번호 바꾸기: SkyTalk-Windows.bat 을 메모장으로 열어 맨 위\r\n"
        "SKYTALK_SSID, SKYTALK_PASS 를 고치세요. (비밀번호 8자 이상)\r\n\r\n"
        "자세한 안내: https://songlomin.github.io/skytalk/\r\n"
    )
    def entry(name):  # 같은 내용이면 zip도 똑같이 나오도록 시각을 고정
        info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        return info

    with zipfile.ZipFile(DIST / "SkyTalk-Windows.zip", "w") as z:
        z.writestr(entry("SkyTalk/SkyTalk-Windows.bat"), (DIST / "SkyTalk-Windows.bat").read_bytes())
        z.writestr(entry("SkyTalk/README-KO.txt"), ("﻿" + readme).encode("utf-8"))

    shutil.copyfile(SRC / "client.html", DIST / "client.html")
    if ANDROID_ASSETS.parent.exists():
        ANDROID_ASSETS.mkdir(exist_ok=True)
        shutil.copyfile(SRC / "client.html", ANDROID_ASSETS / "client.html")

    for f in sorted(DIST.iterdir()):
        print("%-24s %8d bytes" % (f.name, f.stat().st_size))


if __name__ == "__main__":
    main()
