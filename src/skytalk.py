#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
기내톡 (SkyTalk) 서버 — 인터넷 없이 같은 Wi-Fi 안에서 쓰는 팀 채팅

  실행:  python3 skytalk.py            (Windows는 python skytalk.py)
  옵션:  --port 8080   --data 폴더   --wifi-name 이름 --wifi-pass 비밀번호

Python 3.7 이상, 표준 라이브러리만 씁니다. 설치할 것은 없습니다.
같은 Wi-Fi(핫스팟)에 연결된 휴대폰 브라우저에서 화면에 나오는 주소를 열면 됩니다.
"""
import argparse
import base64
import bisect
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

VERSION = "1.0.0"
HTML_B64 = ""  # build.py가 client.html을 여기에 넣습니다.

ONLINE_MS = 45000
POLL_WAIT = 20.0
MAX_TEXT = 2000
MAX_UPLOAD = 6 * 1024 * 1024
RESP_CAP = 300

ID_RE = re.compile(r"^[A-Za-z0-9_-]{4,40}$")
IMG_RE = re.compile(r"^[a-z0-9]{8,40}\.(?:jpg|png)$")
CTRL_RE = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")


def now_ms():
    return int(time.time() * 1000)


def clean_name(s):
    s = CTRL_RE.sub(" ", str(s or "")).replace("\n", " ")
    return re.sub(r"\s+", " ", s).strip()[:20]


def clean_seat(s):
    return re.sub(r"[^0-9A-Z]", "", str(s or "").upper())[:6]


def clean_text(s):
    s = str(s or "").replace("\r\n", "\n").replace("\r", "\n")
    s = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", "", s)
    return s.rstrip().lstrip("\n")


def to_int(v, default):
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


def log(line):
    try:
        print(line, flush=True)
    except Exception:
        pass


class Room:
    """대화방 상태. 모든 변경은 self.cond 잠금 안에서 이뤄집니다."""

    def __init__(self, data_dir):
        self.dir = data_dir
        self.img_dir = os.path.join(data_dir, "img")
        os.makedirs(self.img_dir, exist_ok=True)
        self.cond = threading.Condition()
        self.msgs, self.ids, self.by_cid = [], [], {}
        self.members = {}
        self.rev = 0
        self.dirty_at = 0.0
        self.members_changed = False
        self.sid = None
        self._load()
        threading.Thread(target=self._ticker, name="skytalk-ticker", daemon=True).start()

    # ---- 저장/불러오기 ----
    def _path(self, name):
        return os.path.join(self.dir, name)

    def _write_json(self, name, obj):
        tmp = self._path(name + ".tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False)
        os.replace(tmp, self._path(name))

    def _load(self):
        try:
            with open(self._path("room.json"), encoding="utf-8") as f:
                self.sid = json.load(f).get("sid")
        except Exception:
            self.sid = None
        if not isinstance(self.sid, str) or not ID_RE.match(self.sid):
            self.sid = "r" + secrets.token_hex(6)
            self._write_json("room.json", {"sid": self.sid, "created": now_ms()})
        try:
            with open(self._path("messages.jsonl"), encoding="utf-8") as f:
                for line in f:
                    try:
                        m = json.loads(line)
                    except ValueError:
                        continue
                    if isinstance(m, dict) and isinstance(m.get("id"), int) and (not self.ids or m["id"] > self.ids[-1]):
                        self.msgs.append(m)
                        self.ids.append(m["id"])
                        if m.get("cid"):
                            self.by_cid[m["cid"]] = m
        except FileNotFoundError:
            pass
        try:
            with open(self._path("members.json"), encoding="utf-8") as f:
                data = json.load(f)
            for uid, v in data.items():
                if ID_RE.match(uid) and isinstance(v, dict) and clean_name(v.get("name")):
                    self.members[uid] = {
                        "name": clean_name(v.get("name")), "seat": clean_seat(v.get("seat")),
                        "seen": to_int(v.get("seen"), 0), "read": to_int(v.get("read"), 0),
                    }
        except Exception:
            pass

    def _save_members(self):
        try:
            self._write_json("members.json", self.members)
            self.members_changed = False
        except OSError:
            pass

    # ---- 내부 동작 (잠금 안에서 호출) ----
    def _last_id(self):
        return self.ids[-1] if self.ids else 0

    def _add(self, kind, text, uid="", name="", seat="", img="", cid=""):
        m = {"id": self._last_id() + 1, "ts": now_ms(), "kind": kind, "uid": uid, "name": name,
             "seat": seat, "text": text, "img": img, "cid": cid}
        self.msgs.append(m)
        self.ids.append(m["id"])
        if cid:
            self.by_cid[cid] = m
        try:
            with open(self._path("messages.jsonl"), "a", encoding="utf-8") as f:
                f.write(json.dumps(m, ensure_ascii=False) + "\n")
        except OSError as e:
            log("! 대화 저장 실패: %s" % e)
        self.rev += 1
        self.dirty_at = 0.0
        self.cond.notify_all()
        stamp = time.strftime("%H:%M")
        if kind == "sys":
            log("[%s] · %s" % (stamp, text))
        else:
            body = ("[사진] " if img else "") + text.replace("\n", " ")
            log("[%s] %s%s: %s" % (stamp, name, "(%s)" % seat if seat else "", body[:70]))
        return m

    def _mark_dirty(self):
        if not self.dirty_at:
            self.dirty_at = time.monotonic()

    def _touch(self, uid, name, seat, read):
        if not uid or not name:
            return
        t = now_ms()
        read = max(0, min(read, self._last_id()))
        mem = self.members.get(uid)
        if mem is None:
            self.members[uid] = {"name": name, "seat": seat, "seen": t, "read": read}
            self._add("sys", "%s님이 들어왔어요%s" % (name, " (%s)" % seat if seat else ""))
            self._save_members()
            return
        if mem["name"] != name:
            old, mem["name"] = mem["name"], name
            self._add("sys", "이름 변경: %s → %s" % (old, name))
            self._save_members()
        if mem["seat"] != seat:
            mem["seat"] = seat
            self.members_changed = True
            self._mark_dirty()
        if t - mem["seen"] > ONLINE_MS:
            self._mark_dirty()
        mem["seen"] = t
        if read > mem["read"]:
            mem["read"] = read
            self.members_changed = True
            self._mark_dirty()

    def _snapshot(self, since):
        start = bisect.bisect_right(self.ids, since)
        msgs = self.msgs[start:]
        gap = False
        if len(msgs) > RESP_CAP:
            msgs = msgs[-RESP_CAP:]
            gap = since > 0
        members = [{"uid": u, "name": m["name"], "seat": m["seat"], "seen": m["seen"], "read": m["read"]}
                   for u, m in self.members.items()]
        return {"sid": self.sid, "rev": self.rev, "now": now_ms(), "last": self._last_id(),
                "gap": gap, "msgs": msgs, "members": members}

    def _ticker(self):
        last_save = time.monotonic()
        while True:
            time.sleep(0.25)
            with self.cond:
                if self.dirty_at and time.monotonic() - self.dirty_at >= 1.0:
                    self.dirty_at = 0.0
                    self.rev += 1
                    self.cond.notify_all()
                if self.members_changed and time.monotonic() - last_save > 20:
                    self._save_members()
                    last_save = time.monotonic()

    # ---- 외부 API ----
    def poll(self, since, rev, uid, name, seat, read, wait=True):
        deadline = time.monotonic() + POLL_WAIT
        with self.cond:
            self._touch(uid, name, seat, read)
            while wait and self.rev == rev:
                left = deadline - time.monotonic()
                if left <= 0:
                    break
                self.cond.wait(left)
            if uid in self.members:
                self.members[uid]["seen"] = now_ms()
            return self._snapshot(since)

    def send(self, uid, name, seat, cid, text, img):
        with self.cond:
            if cid in self.by_cid:
                return self.by_cid[cid]
            self._touch(uid, name, seat, 0)
            return self._add("msg", text, uid, name, seat, img, cid)

    def save_image(self, data):
        if data[:3] == b"\xff\xd8\xff":
            ext = "jpg"
        elif data[:8] == b"\x89PNG\r\n\x1a\n":
            ext = "png"
        else:
            raise ValueError("JPEG·PNG 사진만 보낼 수 있어요")
        name = secrets.token_hex(10) + "." + ext
        with open(os.path.join(self.img_dir, name), "wb") as f:
            f.write(data)
        return name

    def close(self):
        with self.cond:
            self._save_members()


# ---------- 네트워크 정보 ----------
_ip_cache = {"t": 0.0, "ips": []}


def local_ips():
    if time.monotonic() - _ip_cache["t"] < 10:
        return _ip_cache["ips"]
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    for target in ("10.255.255.255", "192.168.255.255", "172.31.255.255"):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect((target, 9))
            ips.add(s.getsockname()[0])
            s.close()
        except OSError:
            pass
    for cmd in (["ip", "-4", "-o", "addr"], ["ifconfig"]):
        if shutil.which(cmd[0]):
            try:
                out = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                     universal_newlines=True, timeout=3).stdout
                ips.update(re.findall(r"inet (?:addr:)?(\d+\.\d+\.\d+\.\d+)", out))
            except Exception:
                pass
    good = sorted(ip for ip in ips if not ip.startswith(("127.", "169.254.", "0.")))
    _ip_cache.update(t=time.monotonic(), ips=good)
    return good


def keep_awake():
    """서버가 도는 동안 컴퓨터가 절전으로 들어가지 않게 합니다(가능한 경우)."""
    try:
        if sys.platform == "darwin" and shutil.which("caffeinate"):
            subprocess.Popen(["caffeinate", "-i", "-w", str(os.getpid())])
            return "caffeinate"
        if os.name == "nt":
            import ctypes
            ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)
            k = ctypes.windll.kernel32
            h = k.GetStdHandle(-10)
            mode = ctypes.c_uint32()
            if k.GetConsoleMode(h, ctypes.byref(mode)):
                k.SetConsoleMode(h, (mode.value & ~0x0040) | 0x0080)  # 빠른 편집 모드 끄기(클릭하면 서버가 멈추는 문제 방지)
            return "windows"
        if shutil.which("termux-wake-lock"):
            subprocess.run(["termux-wake-lock"], timeout=5)
            return "termux"
    except Exception:
        pass
    return None


# ---------- HTTP ----------
def load_html():
    if HTML_B64:
        return base64.b64decode(HTML_B64)
    here = os.path.dirname(os.path.abspath(__file__))
    for p in (os.path.join(here, "client.html"), os.path.join(here, "..", "src", "client.html")):
        if os.path.exists(p):
            with open(p, "rb") as f:
                return f.read()
    return "<h1>client.html을 찾을 수 없어요</h1>".encode("utf-8")


CSP = ("default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
       "script-src 'self' 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")


class Handler(BaseHTTPRequestHandler):
    server_version = "SkyTalk/" + VERSION
    sys_version = ""
    protocol_version = "HTTP/1.1"
    timeout = 90

    def log_message(self, *args):
        pass

    def _send(self, code, body, ctype, cache="no-store", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache)
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        if self.close_connection:
            self.send_header("Connection", "close")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self._send(code, body, "application/json; charset=utf-8")

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        app = self.server.app
        u = urlsplit(self.path)
        path = u.path
        if path in ("/", "/index.html"):
            return self._send(200, app.html, "text/html; charset=utf-8", "no-cache",
                              {"Content-Security-Policy": CSP})
        if path == "/api/poll":
            q = parse_qs(u.query)
            g = lambda k, d="": (q.get(k) or [d])[0]
            uid = g("uid")
            uid = uid if ID_RE.match(uid) else ""
            snap = app.room.poll(max(0, to_int(g("since"), 0)), to_int(g("rev"), -1), uid,
                                 clean_name(g("name")), clean_seat(g("seat")), to_int(g("read"), 0),
                                 g("wait", "1") != "0")
            return self._json(snap)
        if path == "/api/info":
            return self._json(app.info())
        if path.startswith("/img/"):
            name = path[5:]
            fp = os.path.join(app.room.img_dir, name)
            if IMG_RE.match(name) and os.path.isfile(fp):
                with open(fp, "rb") as f:
                    data = f.read()
                return self._send(200, data, "image/jpeg" if name.endswith(".jpg") else "image/png",
                                  "public, max-age=31536000, immutable")
            return self._json({"ok": False, "error": "사진을 찾을 수 없어요"}, 404)
        if path == "/favicon.ico":
            return self._send(204, b"", "image/x-icon", "max-age=86400")
        return self._json({"ok": False, "error": "not found"}, 404)

    def do_POST(self):
        app = self.server.app
        path = urlsplit(self.path).path
        length = to_int(self.headers.get("Content-Length"), -1)
        if length < 0 or length > MAX_UPLOAD:
            self.close_connection = True
            return self._json({"ok": False, "error": "사진이 너무 커요"}, 413)
        body = self.rfile.read(length) if length else b""
        if path == "/api/send":
            try:
                d = json.loads(body.decode("utf-8"))
                if not isinstance(d, dict):
                    raise ValueError
            except ValueError:
                return self._json({"ok": False, "error": "잘못된 요청이에요"}, 400)
            uid, cid = str(d.get("uid") or ""), str(d.get("cid") or "")
            name, seat = clean_name(d.get("name")), clean_seat(d.get("seat"))
            text, img = clean_text(d.get("text")), str(d.get("img") or "")
            if not ID_RE.match(uid) or not ID_RE.match(cid):
                return self._json({"ok": False, "error": "잘못된 요청이에요"}, 400)
            if not name:
                return self._json({"ok": False, "error": "이름을 먼저 정해 주세요"}, 400)
            if len(text) > MAX_TEXT:
                return self._json({"ok": False, "error": "메시지가 너무 길어요 (최대 2000자)"}, 400)
            if img and (not IMG_RE.match(img) or not os.path.isfile(os.path.join(app.room.img_dir, img))):
                return self._json({"ok": False, "error": "사진을 찾을 수 없어요. 다시 보내 주세요"}, 400)
            if not text and not img:
                return self._json({"ok": False, "error": "빈 메시지예요"}, 400)
            m = app.room.send(uid, name, seat, cid, text, img)
            return self._json({"ok": True, "id": m["id"]})
        if path == "/api/upload":
            try:
                name = app.room.save_image(body)
            except ValueError as e:
                return self._json({"ok": False, "error": str(e)}, 400)
            return self._json({"ok": True, "id": name})
        return self._json({"ok": False, "error": "not found"}, 404)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = os.name != "nt"

    def handle_error(self, request, client_address):
        if isinstance(sys.exc_info()[1], (ConnectionError, TimeoutError, socket.timeout)):
            return
        super().handle_error(request, client_address)


class App:
    def __init__(self, args):
        self.args = args
        self.room = Room(args.data)
        self.html = load_html()
        self.port = None

    def info(self):
        wifi = {"ssid": self.args.wifi_name, "pass": self.args.wifi_pass} if self.args.wifi_name else None
        return {"ok": True, "app": "skytalk", "ver": VERSION, "host": "python", "sid": self.room.sid,
                "port": self.port, "urls": ["http://%s:%d" % (ip, self.port) for ip in local_ips()], "wifi": wifi}


def main():
    ap = argparse.ArgumentParser(description="기내톡 서버 — 인터넷 없이 같은 Wi-Fi 안에서 쓰는 팀 채팅")
    ap.add_argument("--port", type=int, default=8080, help="포트 (기본 8080, 사용 중이면 다음 번호)")
    ap.add_argument("--data", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "SkyTalk-data"),
                    help="대화·사진을 저장할 폴더")
    ap.add_argument("--wifi-name", default="", help="(선택) 초대 화면에 보여 줄 Wi-Fi 이름")
    ap.add_argument("--wifi-pass", default="", help="(선택) 초대 화면에 보여 줄 Wi-Fi 비밀번호")
    ap.add_argument("--host", default="0.0.0.0", help=argparse.SUPPRESS)
    args = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(errors="replace")
        except Exception:
            pass

    app = App(args)
    httpd = None
    for port in range(args.port, args.port + 10):
        try:
            httpd = Server((args.host, port), Handler)
            break
        except OSError:
            continue
    if httpd is None:
        log("! %d~%d 포트를 모두 쓸 수 없어요. --port 로 다른 번호를 지정해 주세요." % (args.port, args.port + 9))
        return 1
    httpd.app = app
    app.port = httpd.server_address[1]
    awake = keep_awake()

    line = "=" * 58
    log(line)
    log("  >> 기내톡 서버가 켜졌어요   v%s" % VERSION)
    log(line)
    ips = local_ips()
    if ips:
        log("  같은 Wi-Fi에 연결된 휴대폰 브라우저에서 아래 주소를 여세요:")
        for ip in ips:
            log("     →  http://%s:%d" % (ip, app.port))
    else:
        log("  아직 연결된 Wi-Fi가 없어요. 핫스팟/Wi-Fi에 연결하면 주소가 생깁니다.")
    log("  (이 기기에서는 http://localhost:%d)" % app.port)
    log("  주소를 모르겠으면: 휴대폰 Wi-Fi 설정의 '라우터' 주소 + :%d" % app.port)
    if args.wifi_name:
        log("  Wi-Fi 안내: %s / 비밀번호 %s" % (args.wifi_name, args.wifi_pass or "(없음)"))
    log("  대화 저장 위치: %s" % os.path.abspath(args.data))
    if awake:
        log("  절전 방지: 켜짐 (%s)" % awake)
    log("  끄려면 Ctrl+C")
    log(line)

    def watch_ips(known):
        while True:
            time.sleep(10)
            _ip_cache["t"] = 0.0
            for ip in local_ips():
                if ip not in known:
                    known.add(ip)
                    log("  + 새 접속 주소가 생겼어요: http://%s:%d" % (ip, app.port))

    threading.Thread(target=watch_ips, args=(set(ips),), name="skytalk-ips", daemon=True).start()

    try:
        httpd.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        app.room.close()
        httpd.server_close()
        log("기내톡 서버를 껐어요. 대화는 저장돼 있어요.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
