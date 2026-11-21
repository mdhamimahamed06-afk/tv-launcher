package com.viral.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;

/** Keeps the Phone Inbox server alive in the background (also while the TV box screen is off). */
public class InboxService extends Service {

    private InboxServer server;
    private PowerManager.WakeLock wl;
    private WifiManager.WifiLock wfl;
    private final Handler h = new Handler(Looper.getMainLooper());
    private File dir;

    private final Runnable sweep = new Runnable() {
        public void run() {
            try {
                InboxServer.purge(dir);
            } catch (Throwable t) {
                // ignore
            }
            h.postDelayed(this, 60000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("vqinbox", "VQ Inbox",
                        NotificationManager.IMPORTANCE_MIN);
                nm.createNotificationChannel(ch);
                b = new Notification.Builder(this, "vqinbox");
            } else {
                b = new Notification.Builder(this);
            }
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("VQ Launcher")
                    .setContentText("Phone Inbox is ready");
            startForeground(2, b.build());
        } catch (Throwable t) {
            // keep running even without a notification
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vq:inbox");
            wl.setReferenceCounted(false);
            wl.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wfl = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "vq:inbox");
            wfl.setReferenceCounted(false);
            wfl.acquire();
        } catch (Throwable t) {
            // ignore
        }
        dir = InboxServer.dir(this);
        h.post(sweep);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (server == null || InboxServer.livePort == 0) {
            SharedPreferences p = getSharedPreferences("vq", MODE_PRIVATE);
            String key = p.getString("inboxkey", null);
            if (key == null) {
                key = String.valueOf(1000 + new java.util.Random().nextInt(9000));
                p.edit().putString("inboxkey", key).apply();
            }
            if (server != null) server.stop();
            server = new InboxServer(dir, key);
            if (!server.start()) server = null;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (server != null) server.stop();
        try {
            if (wl != null && wl.isHeld()) wl.release();
            if (wfl != null && wfl.isHeld()) wfl.release();
        } catch (Throwable t) {
            // ignore
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
