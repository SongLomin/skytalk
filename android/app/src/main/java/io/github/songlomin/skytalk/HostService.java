package io.github.songlomin.skytalk;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/** 이 폰을 방장(호스트)으로: Wi-Fi Direct 그룹(일반 Wi-Fi처럼 보임) + 채팅 서버. */
public class HostService extends Service {
    static final String ACTION_STOP = "stop";
    static final String SSID_PREFIX = "DIRECT-ST-";

    static volatile HostService instance;
    static volatile String status = "{\"running\":false}";
    static volatile Runnable listener;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ChatServer server;
    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;
    private String wantSsid = "", wantPass = "", ssid = "", pass = "";
    private String p2pState = "starting", p2pError = "", serverError = "";
    private boolean stopping;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopping = true;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        SharedPreferences sp = Notifier.prefs(this);
        wantSsid = SSID_PREFIX + sp.getString("ssid", "SkyTalk");
        wantPass = sp.getString("pass", "skytalk1234");
        startFg("기내톡 방 준비 중…", "Wi-Fi와 채팅 서버를 켜고 있어요");
        if (instance == null) {
            instance = this;
            acquireLocks();
            new Thread(this::startServer, "skytalk-start").start();
        }
        return START_STICKY;
    }

    private void startFg(String title, String text) {
        Notification n = Notifier.service(this, HostService.class, title, text);
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notifier.ID_HOST, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(Notifier.ID_HOST, n);
    }

    private void startServer() {
        try {
            ChatServer s = new ChatServer(new File(getFilesDir(), "skytalk-data"), readAsset("client.html"), BuildConfig.VERSION_NAME);
            s.start(8080);
            s.setListener(m -> Notifier.message(this, m));
            server = s;
        } catch (Exception e) {
            Log.e(ChatServer.TAG, "server start failed", e);
            serverError = "채팅 서버를 켜지 못했어요: " + e.getMessage();
        }
        main.post(() -> {
            publish();
            if (server != null) startGroup();
        });
    }

    private byte[] readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    // ------------------------------------------------------------------ Wi-Fi Direct

    private void startGroup() {
        p2p = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        if (p2p == null) { p2pFail("이 폰은 Wi-Fi Direct를 지원하지 않아요"); return; }
        channel = p2p.initialize(this, getMainLooper(), () -> {
            if (!stopping) { p2pState = "lost"; p2pError = "Wi-Fi Direct 연결이 끊겼어요"; publish(); }
        });
        if (channel == null) { p2pFail("Wi-Fi Direct를 시작할 수 없어요"); return; }
        try {
            p2p.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { main.postDelayed(HostService.this::createGroup, 800); }
                @Override public void onFailure(int reason) { createGroup(); }
            });
        } catch (SecurityException e) {
            createGroup();
        }
    }

    @SuppressLint("MissingPermission")
    private void createGroup() {
        if (stopping) return;
        WifiP2pManager.ActionListener l = new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { main.postDelayed(() -> fetchGroup(0), 800); }
            @Override public void onFailure(int reason) { p2pFail(reasonText(reason)); }
        };
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                WifiP2pConfig cfg = new WifiP2pConfig.Builder()
                        .setNetworkName(wantSsid)
                        .setPassphrase(wantPass)
                        .enablePersistentMode(false)
                        .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                        .build();
                p2p.createGroup(channel, cfg, l);
            } else {
                p2p.createGroup(channel, l);
            }
        } catch (SecurityException e) {
            p2pFail("권한이 없어 Wi-Fi를 만들 수 없어요 (‘주변 기기’ 또는 ‘위치’ 권한을 허용해 주세요)");
        } catch (IllegalArgumentException e) {
            p2pFail("Wi-Fi 이름·비밀번호 형식이 맞지 않아요 (" + e.getMessage() + ")");
        }
    }

    @SuppressLint("MissingPermission")
    private void fetchGroup(final int attempt) {
        if (stopping || p2p == null) return;
        try {
            p2p.requestGroupInfo(channel, g -> {
                if (g == null) {
                    if (attempt < 12) main.postDelayed(() -> fetchGroup(attempt + 1), 700);
                    else p2pFail("Wi-Fi를 만들었지만 정보를 받지 못했어요");
                    return;
                }
                ssid = g.getNetworkName();
                pass = g.getPassphrase();
                p2pState = "on";
                p2pError = "";
                if (server != null) server.setWifi(ssid, pass);
                publish();
            });
        } catch (SecurityException e) {
            p2pFail("권한이 없어 Wi-Fi 정보를 읽을 수 없어요");
        }
    }

    private void p2pFail(String why) {
        Log.w(ChatServer.TAG, "wifi direct: " + why);
        p2pState = "failed";
        p2pError = why;
        publish();
    }

    private static String reasonText(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED: return "이 폰은 Wi-Fi Direct를 지원하지 않아요";
            case WifiP2pManager.BUSY: return "Wi-Fi Direct를 다른 기능(Quick Share·스마트 뷰 등)이 쓰고 있어요. 잠시 뒤 다시 시도해 주세요";
            case WifiP2pManager.ERROR: return "Wi-Fi Direct를 만들지 못했어요. Wi-Fi가 켜져 있는지 확인해 주세요";
            default: return "Wi-Fi Direct 오류 (" + reason + ")";
        }
    }

    // ------------------------------------------------------------------ 상태 알림

    private void publish() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", server != null);
            o.put("port", server != null ? server.port() : 0);
            o.put("url", server != null ? "http://127.0.0.1:" + server.port() + "/" : "");
            o.put("p2p", p2pState);
            o.put("ssid", ssid);
            o.put("pass", pass);
            o.put("error", serverError.isEmpty() ? p2pError : serverError);
            JSONArray urls = new JSONArray();
            if (server != null) for (String ip : Net.localIPv4()) urls.put("http://" + ip + ":" + server.port());
            o.put("urls", urls);
            status = o.toString();
        } catch (Exception ignored) { }
        String title, text;
        if (server == null) {
            title = "기내톡 방을 열지 못했어요";
            text = serverError;
        } else if ("on".equals(p2pState)) {
            String ip = "192.168.49.1";
            for (String a : Net.localIPv4()) { if (a.startsWith("192.168.49.")) { ip = a; break; } }
            title = "기내톡 방이 열려 있어요";
            text = "Wi-Fi " + ssid + " · 비밀번호 " + pass + "\n주소 http://" + ip + ":" + server.port();
        } else if ("starting".equals(p2pState)) {
            title = "기내톡 방 준비 중…";
            text = "Wi-Fi를 만드는 중이에요";
        } else {
            title = "기내톡 방이 열려 있어요 (Wi-Fi Direct 없음)";
            text = p2pError;
        }
        if (!stopping) Notifier.update(this, Notifier.ID_HOST, Notifier.service(this, HostService.class, title, text));
        Runnable l = listener;
        if (l != null) main.post(l);
    }

    // ------------------------------------------------------------------ 수명

    private void acquireLocks() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "skytalk:host");
            wake.setReferenceCounted(false);
            wake.acquire(14L * 3600 * 1000);
        } catch (Exception ignored) { }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            @SuppressWarnings("deprecation") int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "skytalk:host");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        stopping = true;
        if (server != null) server.stop();
        server = null;
        if (p2p != null && channel != null) {
            try { p2p.removeGroup(channel, null); } catch (Exception ignored) { }
            if (Build.VERSION.SDK_INT >= 27) { try { channel.close(); } catch (Exception ignored) { } }
        }
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        instance = null;
        status = "{\"running\":false}";
        Runnable l = listener;
        if (l != null) main.post(l);
        super.onDestroy();
    }
}
