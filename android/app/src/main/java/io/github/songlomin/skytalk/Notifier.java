package io.github.songlomin.skytalk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;

import org.json.JSONObject;

import java.util.ArrayList;

/** 알림: 서비스 상태(조용히) + 새 메시지(소리·진동). */
final class Notifier {
    static final String CH_CHAT = "chat";
    static final String CH_SERVICE = "service";
    static final int ID_HOST = 1;
    static final int ID_MSG = 2;
    static final int ID_CLIENT = 3;

    private static final ArrayList<String> lines = new ArrayList<>();
    private static int count = 0;

    private Notifier() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("skytalk", Context.MODE_PRIVATE); }

    static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH_CHAT) == null) {
            NotificationChannel ch = new NotificationChannel(CH_CHAT, "새 메시지", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("팀원이 보낸 메시지 알림");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 140, 90, 140});
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_SERVICE) == null) {
            NotificationChannel ch = new NotificationChannel(CH_SERVICE, "연결 상태", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("방을 열었거나 방에 들어가 있는 동안 표시돼요");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static Notification service(Context c, Class<?> svc, String title, String text) {
        ensureChannels(c);
        Intent stop = new Intent(c, svc).setAction("stop");
        PendingIntent ps = PendingIntent.getService(c, svc == HostService.class ? 11 : 12, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action off = new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_stat_plane), "끄기", ps).build();
        return new Notification.Builder(c, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_plane)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp(c))
                .addAction(off)
                .build();
    }

    static void update(Context c, int id, Notification n) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        try { if (nm != null) nm.notify(id, n); } catch (SecurityException ignored) { }
    }

    /** 앱 화면을 보고 있지 않을 때, 내가 보낸 게 아닌 새 메시지를 알립니다. */
    static void message(Context c, JSONObject m) {
        if (!"msg".equals(m.optString("kind"))) return;
        String myUid = prefs(c).getString("uid", "");
        if (!myUid.isEmpty() && myUid.equals(m.optString("uid"))) return;
        if (MainActivity.visible) return;
        String who = ChatServer.str(m, "name");
        String seat = ChatServer.str(m, "seat");
        if (!seat.isEmpty()) who = who + " · " + seat;
        String text = ChatServer.str(m, "text");
        if (!ChatServer.str(m, "img").isEmpty()) text = text.isEmpty() ? "[사진]" : "[사진] " + text;
        String myName = prefs(c).getString("name", "");
        boolean mention = !myName.isEmpty() && text.contains("@" + myName);
        post(c, who, text.replace('\n', ' '), mention);
    }

    private static synchronized void post(Context c, String who, String text, boolean mention) {
        ensureChannels(c);
        count++;
        lines.add(who + ": " + text);
        while (lines.size() > 6) lines.remove(0);
        Notification.InboxStyle st = new Notification.InboxStyle();
        for (String l : lines) st.addLine(l);
        st.setSummaryText("새 메시지 " + count + "개");
        Notification.Builder b = new Notification.Builder(c, CH_CHAT)
                .setSmallIcon(R.drawable.ic_stat_plane)
                .setContentTitle(count == 1 ? who : "기내톡 · 새 메시지 " + count + "개")
                .setContentText(count == 1 ? text : who + ": " + text)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(openApp(c));
        if (count > 1) b.setStyle(st); else b.setStyle(new Notification.BigTextStyle().bigText(text));
        if (mention) b.setSubText("나를 불렀어요");
        update(c, ID_MSG, b.build());
    }

    static synchronized void clearMessages(Context c) {
        count = 0;
        lines.clear();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID_MSG);
    }
}
