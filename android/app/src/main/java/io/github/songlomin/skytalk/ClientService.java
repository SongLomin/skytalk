package io.github.songlomin.skytalk;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

/** 다른 사람의 방에 들어가 있을 때: 화면이 꺼져 있어도 새 메시지를 알림으로 알려 줍니다. */
public class ClientService extends Service {
    static final String ACTION_STOP = "stop";
    static volatile String url = "";
    static volatile boolean running = false;
    static volatile Runnable listener;

    private volatile boolean stop;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private ConnectivityManager.NetworkCallback netCb;
    private boolean connected = true;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stop = true;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        String u = intent != null ? intent.getStringExtra("url") : null;
        if (u == null || u.isEmpty()) u = Notifier.prefs(this).getString("joinUrl", "");
        if (u.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        url = u;
        Notifier.prefs(this).edit().putString("joinUrl", u).apply();
        startFg("기내톡 방에 들어가 있어요", u + "\n새 메시지가 오면 알려 드려요");
        if (worker == null) {
            stop = false;
            running = true;
            bindWifi();
            try {
                PowerManager pm = getSystemService(PowerManager.class);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "skytalk:client");
                wake.setReferenceCounted(false);
                wake.acquire(14L * 3600 * 1000);
            } catch (Exception ignored) { }
            worker = new Thread(this::loop, "skytalk-client");
            worker.start();
        }
        Runnable l = listener;
        if (l != null) l.run();
        return START_STICKY;
    }

    private void startFg(String title, String text) {
        Notification n = Notifier.service(this, ClientService.class, title, text);
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notifier.ID_CLIENT, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(Notifier.ID_CLIENT, n);
    }

    /** 앱 전체 통신을 Wi-Fi로 고정합니다. (휴대폰 데이터가 켜져 있어도 방 주소로 가도록) */
    private void bindWifi() {
        final ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        Network n = Net.wifiNetwork(this);
        if (n != null) { cm.bindProcessToNetwork(n); Net.boundNetwork = n; }
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (stop) return;
                cm.bindProcessToNetwork(network);
                Net.boundNetwork = network;
            }
            @Override public void onLost(Network network) {
                if (network.equals(Net.boundNetwork)) { cm.bindProcessToNetwork(null); Net.boundNetwork = null; }
            }
        };
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(req, netCb);
        } catch (Exception ignored) { netCb = null; }
    }

    private void unbindWifi() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        try { if (netCb != null) cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
        cm.bindProcessToNetwork(null);
        Net.boundNetwork = null;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    private void loop() {
        String sid = null;
        long since = 0, rev = -1;
        boolean first = true;
        int fails = 0;
        while (!stop) {
            String base = url;
            try {
                SharedPreferences sp = Notifier.prefs(this);
                String uid = sp.getString("uid", ""), name = sp.getString("name", ""), seat = sp.getString("seat", "");
                StringBuilder q = new StringBuilder(base).append("/api/poll?since=").append(first ? 999999999L : since)
                        .append("&rev=").append(rev).append("&read=0");
                if (!uid.isEmpty() && !name.isEmpty()) {
                    q.append("&uid=").append(enc(uid)).append("&name=").append(enc(name)).append("&seat=").append(enc(seat));
                }
                JSONObject d = new JSONObject(Net.get(q.toString(), 5000, 35000));
                String nsid = d.getString("sid");
                if (sid != null && !sid.equals(nsid)) first = true;
                sid = nsid;
                rev = d.getLong("rev");
                long last = d.optLong("last", since);
                JSONArray msgs = d.getJSONArray("msgs");
                if (first) {
                    since = last;
                    first = false;
                } else {
                    for (int i = 0; i < msgs.length(); i++) {
                        JSONObject m = msgs.getJSONObject(i);
                        long id = m.optLong("id");
                        if (id <= since) continue;
                        since = id;
                        Notifier.message(this, m);
                    }
                    if (last < since) { first = true; }
                }
                if (fails > 0 || !connected) {
                    fails = 0;
                    connected = true;
                    Notifier.update(this, Notifier.ID_CLIENT, Notifier.service(this, ClientService.class,
                            "기내톡 방에 들어가 있어요", base + "\n새 메시지가 오면 알려 드려요"));
                }
            } catch (Exception e) {
                fails++;
                if (fails >= 2 && connected) {
                    connected = false;
                    Notifier.update(this, Notifier.ID_CLIENT, Notifier.service(this, ClientService.class,
                            "기내톡 연결 끊김 · 다시 연결하는 중", "방장 Wi-Fi에 다시 연결되면 자동으로 이어져요"));
                }
                try { Thread.sleep(Math.min(2000L * fails, 15000L)); } catch (InterruptedException ie) { return; }
            }
        }
    }

    @Override
    public void onDestroy() {
        stop = true;
        running = false;
        if (worker != null) worker.interrupt();
        unbindWifi();
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        Runnable l = listener;
        if (l != null) l.run();
        super.onDestroy();
    }
}
