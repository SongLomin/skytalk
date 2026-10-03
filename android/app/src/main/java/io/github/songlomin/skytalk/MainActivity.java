package io.github.songlomin.skytalk;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** 화면 하나: 처음 화면(start.html)과 채팅 화면(호스트 주소)을 WebView로 보여 줍니다. */
public class MainActivity extends Activity {
    static volatile boolean visible = false;
    static final String START = "file:///android_asset/start.html";
    private static final int REQ_FILE = 41, REQ_PERM = 42, REQ_SAVE = 43;

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private volatile String currentUrl = "";
    private Runnable afterPerm;
    private String pendingExport;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Window w = getWindow();
        w.setStatusBarColor(0xFF1A1D23);
        w.setNavigationBarColor(0xFF1A1D23);
        Notifier.ensureChannels(this);

        web = new WebView(this);
        web.setBackgroundColor(0xFF121418);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "SkyTalkApp");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) { currentUrl = url == null ? "" : url; }

            @Override public void onPageFinished(WebView v, String url) {
                currentUrl = url == null ? "" : url;
                if (isStartPage()) pushStatus();
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("http".equals(u.getScheme()) || u.toString().startsWith("file:///android_asset/")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && "http".equals(r.getUrl().getScheme())) {
                    v.loadUrl(START + "#error=" + Uri.encode("채팅방에 연결하지 못했어요 (" + e.getDescription() + "). Wi-Fi 연결을 확인해 주세요."));
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "보낼 사진 고르기"), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
            }

            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(ChatServer.TAG, "js: " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
        });
        HostService.listener = this::pushStatus;
        ClientService.listener = () -> runOnUiThread(this::pushStatus);
        if (!handleIntent(getIntent())) web.loadUrl(homeUrl());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private boolean handleIntent(Intent i) {
        if (i == null) return false;
        if ("host".equals(i.getStringExtra("autostart"))) {   // 자동 테스트용: 권한 확인 없이 바로 방 열기
            i.removeExtra("autostart");
            startHostService(false);
            web.loadUrl(START);
            return true;
        }
        if ("chat".equals(i.getStringExtra("open"))) {        // 자동 테스트용: 채팅 화면 바로 열기
            i.removeExtra("open");
            String u = chatUrl();
            web.loadUrl(u.isEmpty() ? START : u);
            return true;
        }
        return false;
    }

    private boolean isStartPage() { return currentUrl.startsWith("file:///android_asset/"); }

    private String chatUrl() {
        if (HostService.instance != null) {
            try {
                String u = new JSONObject(HostService.status).optString("url", "");
                if (!u.isEmpty()) return u;
            } catch (Exception ignored) { }
        }
        if (ClientService.running && !ClientService.url.isEmpty()) return ClientService.url + "/";
        return "";
    }

    private String homeUrl() {
        String u = chatUrl();
        return u.isEmpty() ? START : u;
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        Notifier.clearMessages(this);
        pushStatus();
    }

    @Override protected void onPause() {
        visible = false;
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (isStartPage() && web.canGoBack()) { web.goBack(); return; }
        moveTaskToBack(true);   // 뒤로 가기를 눌러도 방·알림은 계속 유지
    }

    @Override protected void onDestroy() {
        visible = false;
        HostService.listener = null;
        ClientService.listener = null;
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ native → JS

    private void emit(final String json) {
        runOnUiThread(() -> {
            if (web != null && isStartPage()) web.evaluateJavascript("window.onNative&&window.onNative(" + json + ")", null);
        });
    }

    private void pushStatus() { emit(statusJson()); }

    private String statusJson() {
        try {
            SharedPreferences sp = Notifier.prefs(this);
            JSONObject o = new JSONObject();
            o.put("type", "status");
            o.put("hostRunning", HostService.instance != null);
            o.put("host", new JSONObject(HostService.status));
            o.put("client", new JSONObject().put("running", ClientService.running).put("url", ClientService.url));
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            o.put("wifiOn", wm == null || wm.isWifiEnabled());
            o.put("ssid", sp.getString("ssid", "SkyTalk"));
            o.put("pass", sp.getString("pass", "skytalk1234"));
            o.put("lastJoin", sp.getString("joinUrl", ""));
            o.put("version", BuildConfig.VERSION_NAME);
            o.put("sdk", Build.VERSION.SDK_INT);
            return o.toString();
        } catch (Exception e) {
            return "{\"type\":\"status\"}";
        }
    }

    // ------------------------------------------------------------------ 동작

    private void requestHost(String suffix, String pass) {
        suffix = suffix == null ? "" : suffix.replaceAll("[^A-Za-z0-9_-]", "");
        if (suffix.isEmpty()) suffix = "SkyTalk";
        if (suffix.length() > 20) suffix = suffix.substring(0, 20);
        if (pass == null || !pass.matches("[\\x20-\\x7e]{8,63}")) {
            emit("{\"type\":\"error\",\"error\":\"비밀번호는 영문·숫자 8~63자로 정해 주세요\"}");
            return;
        }
        Notifier.prefs(this).edit().putString("ssid", suffix).putString("pass", pass).apply();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null && !wm.isWifiEnabled()) {
            emit("{\"type\":\"wifiOff\"}");
            return;
        }
        withPermissions(true, () -> startHostService(true));
    }

    private void startHostService(boolean askBattery) {
        stopService(new Intent(this, ClientService.class));
        unbindNetwork();
        startForegroundService(new Intent(this, HostService.class));
        if (askBattery) askBatteryExemptionOnce();
        pushStatus();
    }

    private void requestJoin(String raw) {
        final String u = Net.normalize(raw);
        if (u.isEmpty()) return;
        emit("{\"type\":\"joining\",\"url\":" + JSONObject.quote(u) + "}");
        withPermissions(false, () -> new Thread(() -> {
            bindWifi();
            final JSONObject info = Net.fetchInfo(u);
            runOnUiThread(() -> {
                if (info == null) {
                    emit("{\"type\":\"error\",\"error\":" + JSONObject.quote(u + " 에 기내톡 방이 없어요. 방장 Wi-Fi에 연결돼 있는지, 주소가 맞는지 확인해 주세요.") + "}");
                    return;
                }
                stopService(new Intent(this, HostService.class));
                startForegroundService(new Intent(this, ClientService.class).putExtra("url", u));
                web.loadUrl(u + "/");
            });
        }).start());
    }

    private void findRooms() {
        new Thread(() -> {
            bindWifi();
            JSONArray rooms = Net.findRooms(this);
            emit("{\"type\":\"rooms\",\"rooms\":" + rooms + "}");
        }).start();
    }

    private void bindWifi() {
        if (HostService.instance != null) return;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = Net.wifiNetwork(this);
        if (cm != null && n != null) { cm.bindProcessToNetwork(n); Net.boundNetwork = n; }
    }

    private void unbindNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.bindProcessToNetwork(null);
        Net.boundNetwork = null;
    }

    private void openWifiSettings() {
        try {
            startActivity(new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    private void askBatteryExemptionOnce() {
        SharedPreferences sp = Notifier.prefs(this);
        if (sp.getBoolean("askedBattery", false)) return;
        sp.edit().putBoolean("askedBattery", true).apply();
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            }
        } catch (Exception ignored) { }
    }

    private void saveText(String name, String text) {
        pendingExport = text;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, REQ_SAVE);
        } catch (Exception e) {
            pendingExport = null;
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
            try { startActivity(Intent.createChooser(share, "대화 내용 보내기")); } catch (Exception ignored) { }
        }
    }

    private void withPermissions(boolean host, Runnable then) {
        ArrayList<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (host && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        } else if (host && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (need.isEmpty()) { then.run(); return; }
        afterPerm = then;
        requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_PERM && afterPerm != null) {
            Runnable r = afterPerm;
            afterPerm = null;
            r.run();
            return;
        }
        super.onRequestPermissionsResult(req, perms, results);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            if (fileCb != null) {
                fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
                fileCb = null;
            }
            return;
        }
        if (req == REQ_SAVE) {
            String text = pendingExport;
            pendingExport = null;
            if (res == RESULT_OK && data != null && data.getData() != null && text != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out != null) out.write(("﻿" + text).getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "대화 내용을 저장했어요", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "저장하지 못했어요", Toast.LENGTH_SHORT).show();
                }
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    // ------------------------------------------------------------------ JS bridge (window.SkyTalkApp)

    private final class Bridge {
        /** 앱 안의 처음 화면에서만 방 열기·참여 같은 동작을 허용합니다. */
        private boolean trusted() { return isStartPage(); }

        @JavascriptInterface public String status() { return statusJson(); }

        @JavascriptInterface public void startHost(final String suffix, final String pass) {
            if (trusted()) runOnUiThread(() -> requestHost(suffix, pass));
        }

        @JavascriptInterface public void stopHost() {
            if (trusted()) runOnUiThread(() -> stopService(new Intent(MainActivity.this, HostService.class)));
        }

        @JavascriptInterface public void findRooms() {
            if (trusted()) MainActivity.this.findRooms();
        }

        @JavascriptInterface public void join(final String url) {
            if (trusted()) runOnUiThread(() -> requestJoin(url));
        }

        @JavascriptInterface public void leave() {
            if (trusted()) runOnUiThread(() -> { stopService(new Intent(MainActivity.this, ClientService.class)); unbindNetwork(); });
        }

        @JavascriptInterface public void openChat() {
            if (trusted()) runOnUiThread(() -> { String u = chatUrl(); if (!u.isEmpty()) web.loadUrl(u); });
        }

        @JavascriptInterface public void openWifiSettings() { runOnUiThread(MainActivity.this::openWifiSettings); }

        @JavascriptInterface public void goHome() { runOnUiThread(() -> web.loadUrl(START)); }

        @JavascriptInterface public void onProfile(String uid, String name, String seat, String origin) {
            Notifier.prefs(MainActivity.this).edit().putString("uid", uid == null ? "" : uid)
                    .putString("name", name == null ? "" : name).putString("seat", seat == null ? "" : seat).apply();
        }

        @JavascriptInterface public void saveText(final String name, final String text) {
            runOnUiThread(() -> MainActivity.this.saveText(name, text));
        }

        @JavascriptInterface public void toast(final String text) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show());
        }
    }
}
