package io.github.songlomin.skytalk;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 기내톡 채팅 서버. Windows(PowerShell)·Python 서버와 같은 HTTP API를 씁니다. */
final class ChatServer {
    interface Listener { void onMessage(JSONObject msg); }

    static final String TAG = "SkyTalk";
    static final long ONLINE_MS = 45000;
    static final long POLL_WAIT_MS = 20000;
    static final int MAX_TEXT = 2000;
    static final int MAX_UPLOAD = 6 * 1024 * 1024;
    static final int RESP_CAP = 300;
    static final Pattern ID_RE = Pattern.compile("^[A-Za-z0-9_-]{4,40}$");
    static final Pattern IMG_RE = Pattern.compile("^[a-z0-9]{8,40}\\.(jpg|png)$");
    static final String CSP = "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
            + "script-src 'self' 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    static final class Member { String name; String seat; long seen; long read; }

    static final class Resp {
        final int code; final String type; final byte[] body;
        String cache = "no-store"; boolean html;
        Resp(int code, String type, byte[] body) { this.code = code; this.type = type; this.body = body; }
    }

    private final File dir, imgDir, msgFile, membersFile, roomFile;
    private final byte[] html;
    private final String version;
    private final Object lock = new Object();
    private final ArrayList<Long> ids = new ArrayList<>();
    private final ArrayList<String> msgJson = new ArrayList<>();
    private final HashMap<String, Long> byCid = new HashMap<>();
    private final LinkedHashMap<String, Member> members = new LinkedHashMap<>();
    private final SecureRandom rnd = new SecureRandom();
    private final ExecutorService events = Executors.newSingleThreadExecutor();
    private long rev = 0;
    private boolean dirty = false;
    private long dirtyAt = 0;
    private boolean membersChanged = false;
    private long lastMemberSave = 0;
    private String sid = "";
    private volatile String wifiSsid = "", wifiPass = "";
    private volatile Listener listener;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private ScheduledExecutorService ticker;
    private volatile boolean running;
    private volatile int port;

    ChatServer(File dir, byte[] html, String version) {
        this.dir = dir;
        this.imgDir = new File(dir, "img");
        //noinspection ResultOfMethodCallIgnored
        imgDir.mkdirs();
        this.msgFile = new File(dir, "messages.jsonl");
        this.membersFile = new File(dir, "members.json");
        this.roomFile = new File(dir, "room.json");
        this.html = html;
        this.version = version;
        synchronized (lock) { load(); }
    }

    void setListener(Listener l) { listener = l; }
    void setWifi(String ssid, String pass) { wifiSsid = ssid == null ? "" : ssid; wifiPass = pass == null ? "" : pass; }
    int port() { return port; }
    boolean isRunning() { return running; }

    int onlineCount() {
        synchronized (lock) {
            long t = System.currentTimeMillis();
            int n = 0;
            for (Member m : members.values()) if (t - m.seen < ONLINE_MS) n++;
            return n;
        }
    }

    synchronized int start(int basePort) throws IOException {
        IOException last = null;
        for (int p = basePort; p < basePort + 10 && serverSocket == null; p++) {
            ServerSocket ss = new ServerSocket();
            try {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(p), 64);
                serverSocket = ss;
                port = p;
            } catch (IOException e) {
                last = e;
                try { ss.close(); } catch (IOException ignored) { }
            }
        }
        if (serverSocket == null) throw last != null ? last : new IOException("no free port");
        running = true;
        pool = Executors.newCachedThreadPool();
        ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleWithFixedDelay(this::tick, 250, 250, TimeUnit.MILLISECONDS);
        Thread t = new Thread(this::acceptLoop, "skytalk-accept");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "chat server listening on port " + port);
        return port;
    }

    void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        if (pool != null) pool.shutdownNow();
        if (ticker != null) ticker.shutdownNow();
        events.shutdown();
        synchronized (lock) {
            lock.notifyAll();
            saveMembers();
        }
    }

    // ------------------------------------------------------------------ HTTP

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = serverSocket.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (!running) break;
            } catch (RejectedExecutionException e) {
                break;
            }
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(65000);
            sock.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 16384);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 16384);
            while (running) {
                String line = readLine(in);
                if (line == null) return;
                if (line.isEmpty()) continue;
                String[] parts = line.split(" ");
                if (parts.length < 2) return;
                String method = parts[0];
                String target = parts[1];
                Map<String, String> headers = new HashMap<>();
                for (int count = 0; ; count++) {
                    String h = readLine(in);
                    if (h == null || count > 100) return;
                    if (h.isEmpty()) break;
                    int i = h.indexOf(':');
                    if (i > 0) headers.put(h.substring(0, i).trim().toLowerCase(Locale.ROOT), h.substring(i + 1).trim());
                }
                boolean keep = !"close".equalsIgnoreCase(headers.get("connection")) && !"HTTP/1.0".equals(parts[parts.length - 1]);
                long len;
                try {
                    String cl = headers.get("content-length");
                    len = cl == null ? 0 : Long.parseLong(cl);
                } catch (NumberFormatException e) {
                    len = -1;
                }
                boolean head = "HEAD".equals(method);
                if (len < 0 || len > MAX_UPLOAD) {
                    write(out, json(413, err("사진이 너무 커요")), false, head);
                    return;
                }
                byte[] body = readBody(in, (int) len);
                if (body == null) return;
                Resp r;
                try {
                    r = route(method, target, body);
                } catch (Exception e) {
                    Log.w(TAG, "request failed", e);
                    r = json(500, err("server error"));
                }
                write(out, r, keep, head);
                if (!keep) return;
            }
        } catch (IOException ignored) {
            // 연결 끊김
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readBody(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) return null;
            off += n;
        }
        return buf;
    }

    private static void write(OutputStream out, Resp r, boolean keep, boolean head) throws IOException {
        StringBuilder h = new StringBuilder(256);
        h.append("HTTP/1.1 ").append(r.code).append(' ').append(reason(r.code)).append("\r\n");
        h.append("Content-Type: ").append(r.type).append("\r\n");
        h.append("Content-Length: ").append(r.body.length).append("\r\n");
        h.append("Cache-Control: ").append(r.cache).append("\r\n");
        h.append("X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nServer: SkyTalk-Android\r\n");
        if (r.html) h.append("Content-Security-Policy: ").append(CSP).append("\r\n");
        h.append("Connection: ").append(keep ? "keep-alive" : "close").append("\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        if (!head) out.write(r.body);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            default: return "Error";
        }
    }

    private static Resp json(int code, String body) {
        return new Resp(code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static String err(String msg) { return "{\"ok\":false,\"error\":" + JSONObject.quote(msg) + "}"; }

    private Resp route(String method, String target, byte[] body) {
        String path = target, query = "";
        int q = target.indexOf('?');
        if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
        if (method.equals("GET") || method.equals("HEAD")) {
            if (path.equals("/") || path.equals("/index.html")) {
                Resp r = new Resp(200, "text/html; charset=utf-8", html);
                r.cache = "no-cache";
                r.html = true;
                return r;
            }
            if (path.equals("/api/poll")) {
                Map<String, String> qs = parseQuery(query);
                String uid = val(qs, "uid");
                if (!ID_RE.matcher(uid).matches()) uid = "";
                long since = Math.max(0, toLong(qs.get("since"), 0));
                long clientRev = toLong(qs.get("rev"), -1);
                boolean wait = !"0".equals(qs.get("wait"));
                return json(200, poll(since, clientRev, uid, cleanName(val(qs, "name")), cleanSeat(val(qs, "seat")),
                        toLong(qs.get("read"), 0), wait));
            }
            if (path.equals("/api/info")) return json(200, info());
            if (path.startsWith("/img/")) {
                String name = path.substring(5);
                File f = new File(imgDir, name);
                if (IMG_RE.matcher(name).matches() && f.isFile()) {
                    byte[] data = readFile(f);
                    if (data != null) {
                        Resp r = new Resp(200, name.endsWith(".jpg") ? "image/jpeg" : "image/png", data);
                        r.cache = "public, max-age=31536000, immutable";
                        return r;
                    }
                }
                return json(404, err("사진을 찾을 수 없어요"));
            }
            if (path.equals("/favicon.ico")) {
                Resp r = new Resp(204, "image/x-icon", new byte[0]);
                r.cache = "max-age=86400";
                return r;
            }
            return json(404, err("not found"));
        }
        if (method.equals("POST")) {
            if (path.equals("/api/send")) return send(body);
            if (path.equals("/api/upload")) return upload(body);
            return json(404, err("not found"));
        }
        return json(405, err("method not allowed"));
    }

    // ------------------------------------------------------------------ chat logic

    private String poll(long since, long clientRev, String uid, String name, String seat, long read, boolean wait) {
        synchronized (lock) {
            touch(uid, name, seat, read);
            long deadline = now() + POLL_WAIT_MS;
            while (wait && running && rev == clientRev) {
                long left = deadline - now();
                if (left <= 0) break;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            Member m = members.get(uid);
            if (m != null) m.seen = System.currentTimeMillis();
            return snapshot(since);
        }
    }

    private Resp send(byte[] body) {
        JSONObject d;
        try {
            d = new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            return json(400, err("잘못된 요청이에요"));
        }
        String uid = str(d, "uid"), cid = str(d, "cid");
        String name = cleanName(str(d, "name")), seat = cleanSeat(str(d, "seat"));
        String text = cleanText(str(d, "text")), img = str(d, "img");
        if (!ID_RE.matcher(uid).matches() || !ID_RE.matcher(cid).matches()) return json(400, err("잘못된 요청이에요"));
        if (name.isEmpty()) return json(400, err("이름을 먼저 정해 주세요"));
        if (text.codePointCount(0, text.length()) > MAX_TEXT) return json(400, err("메시지가 너무 길어요 (최대 2000자)"));
        if (!img.isEmpty() && (!IMG_RE.matcher(img).matches() || !new File(imgDir, img).isFile())) {
            return json(400, err("사진을 찾을 수 없어요. 다시 보내 주세요"));
        }
        if (text.isEmpty() && img.isEmpty()) return json(400, err("빈 메시지예요"));
        long id;
        synchronized (lock) {
            Long existing = byCid.get(cid);
            if (existing != null) {
                id = existing;
            } else {
                touch(uid, name, seat, 0);
                id = addLocked("msg", text, uid, name, seat, img, cid);
            }
        }
        return json(200, "{\"ok\":true,\"id\":" + id + "}");
    }

    private Resp upload(byte[] body) {
        String ext = null;
        if (body.length >= 3 && (body[0] & 0xff) == 0xFF && (body[1] & 0xff) == 0xD8 && (body[2] & 0xff) == 0xFF) ext = "jpg";
        else if (body.length >= 8 && (body[0] & 0xff) == 0x89 && body[1] == 'P' && body[2] == 'N' && body[3] == 'G') ext = "png";
        if (ext == null) return json(400, err("JPEG·PNG 사진만 보낼 수 있어요"));
        byte[] r = new byte[10];
        rnd.nextBytes(r);
        String name = hex(r) + "." + ext;
        try (FileOutputStream fo = new FileOutputStream(new File(imgDir, name))) {
            fo.write(body);
        } catch (IOException e) {
            return json(500, err("사진을 저장하지 못했어요"));
        }
        return json(200, "{\"ok\":true,\"id\":" + JSONObject.quote(name) + "}");
    }

    String info() {
        StringBuilder urls = new StringBuilder();
        for (String ip : Net.localIPv4()) {
            if (urls.length() > 0) urls.append(',');
            urls.append(JSONObject.quote("http://" + ip + ":" + port));
        }
        String ssid = wifiSsid, pass = wifiPass;
        String wifi = ssid.isEmpty() ? "null" : "{\"ssid\":" + JSONObject.quote(ssid) + ",\"pass\":" + JSONObject.quote(pass) + "}";
        String s;
        synchronized (lock) { s = sid; }
        return "{\"ok\":true,\"app\":\"skytalk\",\"ver\":" + JSONObject.quote(version) + ",\"host\":\"android\",\"sid\":"
                + JSONObject.quote(s) + ",\"port\":" + port + ",\"urls\":[" + urls + "],\"wifi\":" + wifi + "}";
    }

    // 아래 함수들은 lock 안에서 호출합니다.
    private long lastId() { return ids.isEmpty() ? 0 : ids.get(ids.size() - 1); }

    private long addLocked(String kind, String text, String uid, String name, String seat, String img, String cid) {
        long id = lastId() + 1;
        final JSONObject m = new JSONObject();
        try {
            m.put("id", id).put("ts", System.currentTimeMillis()).put("kind", kind).put("uid", uid).put("name", name)
                    .put("seat", seat).put("text", text).put("img", img).put("cid", cid);
        } catch (JSONException ignored) { }
        String js = m.toString();
        ids.add(id);
        msgJson.add(js);
        if (!cid.isEmpty()) byCid.put(cid, id);
        appendLine(msgFile, js);
        rev++;
        dirty = false;
        lock.notifyAll();
        final Listener l = listener;
        if (l != null) {
            try { events.execute(() -> l.onMessage(m)); } catch (RejectedExecutionException ignored) { }
        }
        return id;
    }

    private void markDirty() {
        if (!dirty) { dirty = true; dirtyAt = now(); }
    }

    private void touch(String uid, String name, String seat, long read) {
        if (uid.isEmpty() || name.isEmpty()) return;
        long t = System.currentTimeMillis();
        long last = lastId();
        if (read > last) read = last;
        if (read < 0) read = 0;
        Member m = members.get(uid);
        if (m == null) {
            m = new Member();
            m.name = name; m.seat = seat; m.seen = t; m.read = read;
            members.put(uid, m);
            addLocked("sys", name + "님이 들어왔어요" + (seat.isEmpty() ? "" : " (" + seat + ")"), "", "", "", "", "");
            saveMembers();
            return;
        }
        if (!m.name.equals(name)) {
            String old = m.name;
            m.name = name;
            addLocked("sys", "이름 변경: " + old + " → " + name, "", "", "", "", "");
            saveMembers();
        }
        if (!m.seat.equals(seat)) { m.seat = seat; membersChanged = true; markDirty(); }
        if (t - m.seen > ONLINE_MS) markDirty();
        m.seen = t;
        if (read > m.read) { m.read = read; membersChanged = true; markDirty(); }
    }

    private String snapshot(long since) {
        int n = ids.size();
        int start = Collections.binarySearch(ids, since);
        start = start >= 0 ? start + 1 : -start - 1;
        boolean gap = false;
        if (n - start > RESP_CAP) { start = n - RESP_CAP; gap = since > 0; }
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"sid\":").append(JSONObject.quote(sid)).append(",\"rev\":").append(rev)
                .append(",\"now\":").append(System.currentTimeMillis()).append(",\"last\":").append(lastId())
                .append(",\"gap\":").append(gap).append(",\"msgs\":[");
        for (int i = start; i < n; i++) {
            if (i > start) sb.append(',');
            sb.append(msgJson.get(i));
        }
        sb.append("],\"members\":[");
        boolean first = true;
        for (Map.Entry<String, Member> e : members.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            Member m = e.getValue();
            sb.append("{\"uid\":").append(JSONObject.quote(e.getKey())).append(",\"name\":").append(JSONObject.quote(m.name))
                    .append(",\"seat\":").append(JSONObject.quote(m.seat)).append(",\"seen\":").append(m.seen)
                    .append(",\"read\":").append(m.read).append('}');
        }
        return sb.append("]}").toString();
    }

    private void tick() {
        synchronized (lock) {
            long n = now();
            if (dirty && n - dirtyAt >= 1000) {
                dirty = false;
                rev++;
                lock.notifyAll();
            }
            if (membersChanged && n - lastMemberSave > 20000) saveMembers();
        }
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        try {
            sid = new JSONObject(readText(roomFile)).optString("sid", "");
        } catch (Exception e) {
            sid = "";
        }
        if (!ID_RE.matcher(sid).matches()) {
            byte[] b = new byte[6];
            rnd.nextBytes(b);
            sid = "r" + hex(b);
            writeText(roomFile, "{\"sid\":" + JSONObject.quote(sid) + ",\"created\":" + System.currentTimeMillis() + "}");
        }
        if (msgFile.isFile()) {
            for (String line : readText(msgFile).split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JSONObject m = new JSONObject(line);
                    long id = m.getLong("id");
                    if (!ids.isEmpty() && id <= ids.get(ids.size() - 1)) continue;
                    ids.add(id);
                    msgJson.add(line);
                    String cid = str(m, "cid");
                    if (!cid.isEmpty()) byCid.put(cid, id);
                } catch (JSONException ignored) { }
            }
        }
        if (membersFile.isFile()) {
            try {
                JSONObject o = new JSONObject(readText(membersFile));
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String uid = it.next();
                    JSONObject v = o.optJSONObject(uid);
                    if (v == null || !ID_RE.matcher(uid).matches()) continue;
                    String nm = cleanName(str(v, "name"));
                    if (nm.isEmpty()) continue;
                    Member m = new Member();
                    m.name = nm;
                    m.seat = cleanSeat(str(v, "seat"));
                    m.seen = v.optLong("seen", 0);
                    m.read = v.optLong("read", 0);
                    members.put(uid, m);
                }
            } catch (Exception ignored) { }
        }
    }

    private void saveMembers() {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Member> e : members.entrySet()) {
                Member m = e.getValue();
                o.put(e.getKey(), new JSONObject().put("name", m.name).put("seat", m.seat).put("seen", m.seen).put("read", m.read));
            }
            File tmp = new File(dir, "members.json.tmp");
            writeText(tmp, o.toString());
            if (!tmp.renameTo(membersFile)) writeText(membersFile, o.toString());
        } catch (Exception e) {
            Log.w(TAG, "save members", e);
        }
        membersChanged = false;
        lastMemberSave = now();
    }

    // ------------------------------------------------------------------ helpers

    private static long now() { return System.nanoTime() / 1_000_000L; }

    static String str(JSONObject o, String key) { return o.isNull(key) ? "" : o.optString(key, ""); }

    private static String val(Map<String, String> m, String k) { String v = m.get(k); return v == null ? "" : v; }

    static long toLong(String s, long def) {
        if (s == null) return def;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            try {
                m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception ignored) { }
        }
        return m;
    }

    static String cleanName(String s) {
        if (s == null) return "";
        s = s.replaceAll("[\\x00-\\x1f\\x7f]", " ").replaceAll("\\s+", " ").trim();
        if (s.codePointCount(0, s.length()) > 20) s = s.substring(0, s.offsetByCodePoints(0, 20));
        return s;
    }

    static String cleanSeat(String s) {
        if (s == null) return "";
        s = s.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", "");
        return s.length() > 6 ? s.substring(0, 6) : s;
    }

    static String cleanText(String s) {
        if (s == null) return "";
        s = s.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]", "");
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        int start = 0;
        while (start < end && s.charAt(start) == '\n') start++;
        return s.substring(start, end);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    private static byte[] readFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream((int) Math.max(16, f.length()));
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static String readText(File f) {
        byte[] b = readFile(f);
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }

    private static void writeText(File f, String s) {
        try (FileOutputStream fo = new FileOutputStream(f)) {
            fo.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "write " + f, e);
        }
    }

    private static void appendLine(File f, String s) {
        try (FileOutputStream fo = new FileOutputStream(f, true)) {
            fo.write((s + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "append", e);
        }
    }
}
