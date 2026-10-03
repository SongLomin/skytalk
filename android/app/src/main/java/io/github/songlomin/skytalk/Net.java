package io.github.songlomin.skytalk;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 네트워크 도우미: 내 주소 목록, 같은 Wi-Fi의 기내톡 방 찾기, HTTP 요청. */
final class Net {
    /** 참여 모드에서 앱 전체 통신을 Wi-Fi로 고정했을 때의 네트워크 (없으면 null). */
    static volatile Network boundNetwork;

    private Net() { }

    static List<String> localIPv4() {
        ArrayList<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String s = a.getHostAddress();
                    if (out.contains(s)) continue;
                    if (ni.getName().startsWith("p2p")) out.add(0, s); else out.add(s);
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    static Network wifiNetwork(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 방이 있을 만한 주소: 연결된 Wi-Fi의 공유기(게이트웨이) 주소 + 자주 쓰는 핫스팟 주소. */
    static List<String> candidates(Context c) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        try {
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp == null) continue;
                    for (RouteInfo r : lp.getRoutes()) {
                        InetAddress g = r.getGateway();
                        if (g instanceof Inet4Address && !g.isAnyLocalAddress()) set.add(g.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) { }
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            @SuppressWarnings("deprecation") int gw = wm.getDhcpInfo().gateway;
            if (gw != 0) {
                set.add(String.format(Locale.ROOT, "%d.%d.%d.%d", gw & 0xff, (gw >> 8) & 0xff, (gw >> 16) & 0xff, (gw >> 24) & 0xff));
            }
        } catch (Exception ignored) { }
        set.add("192.168.49.1");   // 안드로이드 Wi-Fi Direct 방장
        set.add("192.168.137.1");  // Windows 핫스팟
        set.add("192.168.43.1");   // 예전 안드로이드 핫스팟
        set.removeAll(localIPv4()); // 내 주소는 빼기
        return new ArrayList<>(set);
    }

    /** 후보 주소에서 기내톡 방을 찾습니다(병렬, 몇 초 안에 끝남). */
    static JSONArray findRooms(Context c) {
        List<String> ips = candidates(c);
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<JSONObject>> fs = new ArrayList<>();
        for (String ip : ips) {
            for (int port : new int[]{8080, 8081, 8082}) {
                final String base = "http://" + ip + ":" + port;
                fs.add(ex.submit(() -> fetchInfo(base)));
            }
        }
        JSONArray out = new JSONArray();
        HashSet<String> seen = new HashSet<>();
        for (Future<JSONObject> f : fs) {
            try {
                JSONObject o = f.get(6, TimeUnit.SECONDS);
                if (o != null && seen.add(o.optString("sid") + "|" + o.optInt("port"))) out.put(o);
            } catch (Exception ignored) { }
        }
        ex.shutdownNow();
        return out;
    }

    static JSONObject fetchInfo(String base) {
        try {
            JSONObject o = new JSONObject(get(base + "/api/info", 1500, 2500));
            if (!"skytalk".equals(o.optString("app"))) return null;
            o.put("url", base);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    static String get(String url, int connectMs, int readMs) throws IOException {
        Network n = boundNetwork;
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection) (n != null ? n.openConnection(u) : u.openConnection());
        try {
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setUseCaches(false);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    static String readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** "192.168.49.1", "192.168.49.1:8080", "http://x/" 같은 입력을 http://주소:포트 로 정리합니다. */
    static String normalize(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        String rest = s.substring(s.indexOf("//") + 2);
        if (rest.contains("/")) rest = rest.substring(0, rest.indexOf('/'));
        if (!rest.contains(":")) rest = rest + ":8080";
        return "http://" + rest;
    }
}
