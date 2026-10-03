#!/usr/bin/env python3
"""기내톡 서버 API 블랙박스 테스트 (표준 라이브러리만 사용).

  python tests/api_test.py python      # dist/skytalk.py 를 띄워서 검사
  python tests/api_test.py windows     # dist/SkyTalk-Windows.bat 의 PowerShell 본문을 띄워서 검사
  python tests/api_test.py url http://host:port   # 이미 떠 있는 서버 검사(재시작 검사 제외)
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(ROOT, "dist")
PNG = bytes.fromhex("89504e470d0a1a0a0000000d4948445200000001000000010806000000"
                    "1f15c4890000000d49444154789c6360000002000154a24f5d0000000049454e44ae426082")

FAILS = []


def check(cond, what):
    print(("  ok   " if cond else "  FAIL ") + what, flush=True)
    if not cond:
        FAILS.append(what)


class Client:
    def __init__(self, base, uid, name, seat=""):
        self.base, self.uid, self.name, self.seat = base, uid, name, seat
        self.since, self.rev, self.read = 0, -1, 0

    def req(self, method, path, body=None, ctype="application/json", timeout=40):
        data = None
        if body is not None:
            data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        r = urllib.request.Request(self.base + path, data=data, method=method)
        if data is not None:
            r.add_header("Content-Type", ctype)
        try:
            with urllib.request.urlopen(r, timeout=timeout) as resp:
                return resp.status, resp.read(), resp.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers

    def poll(self, wait=True, read=None, timeout=40):
        q = {"since": self.since, "rev": self.rev, "uid": self.uid, "name": self.name, "seat": self.seat,
             "read": self.read if read is None else read, "wait": "1" if wait else "0"}
        st, body, _ = self.req("GET", "/api/poll?" + urllib.parse.urlencode(q), timeout=timeout)
        assert st == 200, (st, body)
        d = json.loads(body.decode("utf-8"))
        self.rev = d["rev"]
        if d["msgs"]:
            self.since = d["msgs"][-1]["id"]
        return d

    def send(self, text="", img="", cid=None):
        cid = cid or "c" + os.urandom(6).hex()
        st, body, _ = self.req("POST", "/api/send", {"uid": self.uid, "name": self.name, "seat": self.seat,
                                                     "cid": cid, "text": text, "img": img})
        return st, json.loads(body.decode("utf-8")), cid


def wait_up(base, secs=40):
    end = time.time() + secs
    while time.time() < end:
        try:
            with urllib.request.urlopen(base + "/api/info", timeout=2) as r:
                if r.status == 200:
                    return True
        except Exception:
            time.sleep(0.3)
    return False


def start_server(kind, port, data):
    log = open(os.path.join(data, "..", "server-%s-%d.log" % (kind, port)), "ab")
    if kind == "python":
        cmd = [sys.executable, os.path.join(DIST, "skytalk.py"), "--port", str(port), "--data", data]
        base = "http://127.0.0.1:%d" % port
    else:
        bat = os.path.join(DIST, "SkyTalk-Windows.bat").replace("'", "''")
        data_q = data.replace("'", "''")
        script = ("$s=[IO.File]::ReadAllText('%s',[Text.Encoding]::UTF8); & ([ScriptBlock]::Create($s)) "
                  "-Port %d -DataDir '%s' -NoHotspot -NoFirewall -NoBrowser" % (bat, port, data_q))
        cmd = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script]
        base = "http://localhost:%d" % port
    p = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    if not wait_up(base):
        p.kill()
        raise SystemExit("server did not start: " + " ".join(cmd))
    return p, base


def run_suite(base):
    a = Client(base, "ua" + os.urandom(4).hex(), "철수", "32A")
    b = Client(base, "ub" + os.urandom(4).hex(), "영희 Kim", "40c")

    st, body, hdr = a.req("GET", "/")
    check(st == 200 and "기내톡".encode() in body, "GET / serves the chat page")
    check("text/html" in (hdr.get("Content-Type") or ""), "page content type is HTML")

    st, body, _ = a.req("GET", "/api/info")
    info = json.loads(body)
    check(st == 200 and info.get("ok") and info.get("app") == "skytalk", "/api/info answers")
    check(isinstance(info.get("urls"), list), "/api/info lists urls")

    d = a.poll()
    sid = d["sid"]
    check(isinstance(sid, str) and len(sid) >= 4, "poll returns room id")
    check(any(m["kind"] == "sys" and "철수님이 들어왔어요 (32A)" in m["text"] for m in d["msgs"]), "join notice for A")
    check(any(x["uid"] == a.uid and x["name"] == "철수" for x in d["members"]), "A listed as member")

    d = b.poll()
    check(any(x["uid"] == b.uid and x["seat"] == "40C" for x in d["members"]), "seat is cleaned to upper case")
    a.poll(wait=False)

    # long-poll wake-up
    got = {}

    def waiter():
        t0 = time.time()
        got["d"] = b.poll()
        got["dt"] = time.time() - t0

    th = threading.Thread(target=waiter)
    th.start()
    time.sleep(1.0)
    text = '안녕하세요 "따옴표" \\ <script>x</script>\n둘째 줄 🚀'
    st, j, cid = a.send(text)
    check(st == 200 and j.get("ok") and isinstance(j.get("id"), int), "A sends a message")
    th.join(10)
    check("d" in got and got["dt"] < 3.5, "waiting poll of B wakes up quickly (%.2fs)" % got.get("dt", -1))
    msgs = got.get("d", {}).get("msgs", [])
    mine = [m for m in msgs if m.get("cid") == cid]
    check(len(mine) == 1 and mine[0]["text"] == text, "B receives the exact text (quotes, newline, emoji)")
    check(mine and mine[0]["name"] == "철수" and mine[0]["seat"] == "32A", "message carries name and seat")

    # duplicate send with same cid
    st, j2, _ = a.send(text, cid=cid)
    check(st == 200 and j2.get("id") == j.get("id"), "resend with the same cid is deduplicated")
    d = b.poll(wait=False)
    check(not any(m.get("cid") == cid for m in d["msgs"]), "no duplicate message delivered")

    # validation
    st, j3, _ = Client(base, "uz" + os.urandom(4).hex(), "").send("hi")
    check(st == 400, "message without a name is rejected")
    st, j3, _ = a.send("x" * 2001)
    check(st == 400, "too long message is rejected")
    st, j3, _ = a.send("   \n  ")
    check(st == 400, "empty message is rejected")
    st, j3, _ = Client(base, "bad uid!", "x").send("hi")
    check(st == 400, "bad uid is rejected")
    st, j3, _ = a.send("", img="../../etc/passwd")
    check(st == 400, "bad image id is rejected")
    st, body, _ = a.req("POST", "/api/send", b"{not json", "application/json")
    check(st == 400, "broken JSON is rejected")

    # upload
    st, body, _ = a.req("POST", "/api/upload", PNG, "image/png")
    up = json.loads(body)
    check(st == 200 and up.get("ok") and up["id"].endswith(".png"), "PNG upload accepted")
    st, img, hdr = a.req("GET", "/img/" + up["id"])
    check(st == 200 and img == PNG and hdr.get("Content-Type") == "image/png", "uploaded image served back")
    st, body, _ = a.req("POST", "/api/upload", b"hello world", "image/png")
    check(st == 400, "non-image upload rejected")
    st, body, _ = a.req("GET", "/img/nothere12345.jpg")
    check(st == 404, "missing image is 404")
    st, j4, cid4 = a.send("사진이에요", img=up["id"])
    check(st == 200 and j4.get("ok"), "message with image accepted")
    d = b.poll(wait=False)
    check(any(m.get("img") == up["id"] and m["text"] == "사진이에요" for m in d["msgs"]), "image message delivered")

    # read receipts: A waits, B reads -> A sees B.read move within the debounce window
    a.poll(wait=False)
    last = b.since
    got = {}

    def a_wait():
        t0 = time.time()
        got["d"] = a.poll()
        got["dt"] = time.time() - t0

    th = threading.Thread(target=a_wait)
    th.start()
    time.sleep(0.5)
    b.read = last
    b.poll(wait=False)
    th.join(10)
    rb = [x for x in got.get("d", {}).get("members", []) if x["uid"] == b.uid]
    check(rb and rb[0]["read"] == last, "A learns B's read position")
    check(got.get("dt", 99) < 4.0, "read update wakes waiting pollers (%.2fs)" % got.get("dt", -1))

    # rename
    a.name = "철수2"
    d = a.poll(wait=False)
    check(any(m["kind"] == "sys" and "철수 → 철수2" in m["text"] for m in d["msgs"]), "rename notice")

    # many messages + cap/gap
    for i in range(310):
        st, _, _ = a.send("bulk %d" % i)
        if st != 200:
            check(False, "bulk send %d" % i)
            break
    c = Client(base, "uc" + os.urandom(4).hex(), "새사람")
    d = c.poll(wait=False)
    check(len(d["msgs"]) == 300 and d["gap"] is False, "first load is capped to 300 messages")
    c2 = Client(base, "ud" + os.urandom(4).hex(), "늦은사람")
    c2.since = 1
    d = c2.poll(wait=False)
    check(len(d["msgs"]) == 300 and d["gap"] is True, "gap flag set when history is cut")

    # concurrency: 6 waiters all wake on one message
    waiters = [Client(base, "uw%d" % i + os.urandom(3).hex(), "대기%d" % i) for i in range(6)]
    for w in waiters:
        w.poll(wait=False)
    time.sleep(1.3)
    for w in waiters:
        w.poll(wait=False)
    results = {}

    def w_run(w):
        results[w.uid] = w.poll()

    ths = [threading.Thread(target=w_run, args=(w,)) for w in waiters]
    [t.start() for t in ths]
    time.sleep(1.5)
    st, jj, cidx = a.send("모두 깨어나세요")
    [t.join(10) for t in ths]
    check(len(results) == 6 and all(any(m.get("cid") == cidx for m in r["msgs"]) for r in results.values()),
          "6 parallel waiters all receive the message")
    return sid


def run_timeout(base):
    a = Client(base, "ut" + os.urandom(4).hex(), "타임아웃")
    a.poll(wait=False)
    time.sleep(1.5)  # let debounce settle
    a.poll(wait=False)
    t0 = time.time()
    d = a.poll()
    dt = time.time() - t0
    check(17 <= dt <= 24 and d["msgs"] == [], "idle poll returns after about 20s (%.1fs)" % dt)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "python"
    if mode == "url":
        run_suite(sys.argv[2].rstrip("/"))
    else:
        tmp = tempfile.mkdtemp(prefix="skytalk-test-")
        data = os.path.join(tmp, "data")
        os.makedirs(data)
        port = 18080 if mode == "python" else 18180
        p, base = start_server(mode, port, data)
        try:
            print("== %s server at %s" % (mode, base))
            sid = run_suite(base)
            run_timeout(base)
        finally:
            p.kill()
            p.wait()
        time.sleep(1.0)
        p, base = start_server(mode, port, data)
        try:
            print("== restart with the same data folder")
            a = Client(base, "ur" + os.urandom(4).hex(), "다시")
            d = a.poll(wait=False)
            check(d["sid"] == sid, "room id survives restart")
            check(any(m["text"] == "모두 깨어나세요" for m in d["msgs"]), "messages survive restart")
            check(any(x["name"] == "철수2" for x in d["members"]), "members survive restart")
            last = d["last"]
            st, j, _ = a.send("재시작 후 메시지")
            check(st == 200 and j["id"] == last + 1, "message ids continue after restart")
        finally:
            p.kill()
            p.wait()
        shutil.rmtree(tmp, ignore_errors=True)
    print("\nRESULT: %s (%d failures)" % ("PASS" if not FAILS else "FAIL", len(FAILS)))
    for f in FAILS:
        print("  - " + f)
    sys.exit(1 if FAILS else 0)


if __name__ == "__main__":
    main()
