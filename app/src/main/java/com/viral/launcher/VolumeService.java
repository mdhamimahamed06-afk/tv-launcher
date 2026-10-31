package com.viral.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small round volume button that floats over every app (YouTube, Netflix, ...). */
public class VolumeService extends Service {

    static final int RED = 0xFFE11D2E;
    static VolumeService inst;
    static boolean onHome = true;

    private WindowManager wm;
    private AudioManager am;
    private LinearLayout box;
    private Circle circle;
    private TextView minus, plus, pct;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean added = false;

    private final Runnable collapse = new Runnable() {
        public void run() { showPanel(false); }
    };

    private final BroadcastReceiver volReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { refresh(); }
    };

    static void setHome(boolean home) {
        onHome = home;
        if (inst != null) inst.apply();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable oval(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        g.setStroke(dp(2), stroke);
        return g;
    }

    private class Circle extends View {
        int level = 3;
        boolean muted = false;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Circle(Context c) {
            super(c);
            setBackground(oval(0xD9111116, RED));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight();
            float s = Math.min(w, hh) * 0.34f;
            float cy = hh / 2f;
            float x0 = w / 2f - s * 0.785f;
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.FILL);
            Path path = new Path();
            path.moveTo(x0, cy - s * 0.22f);
            path.lineTo(x0 + s * 0.25f, cy - s * 0.22f);
            path.lineTo(x0 + s * 0.7f, cy - s * 0.55f);
            path.lineTo(x0 + s * 0.7f, cy + s * 0.55f);
            path.lineTo(x0 + s * 0.25f, cy + s * 0.22f);
            path.lineTo(x0, cy + s * 0.22f);
            path.close();
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setStrokeCap(Paint.Cap.ROUND);
            if (muted) {
                p.setColor(RED);
                c.drawLine(x0 + s * 0.95f, cy - s * 0.3f, x0 + s * 1.45f, cy + s * 0.3f, p);
                c.drawLine(x0 + s * 1.45f, cy - s * 0.3f, x0 + s * 0.95f, cy + s * 0.3f, p);
            } else {
                for (int i = 1; i <= level; i++) {
                    float r = s * (0.3f + 0.28f * i);
                    RectF rf = new RectF(x0 + s * 0.7f - r, cy - r, x0 + s * 0.7f + r, cy + r);
                    c.drawArc(rf, -40, 80, false, p);
                }
            }
        }
    }

    private TextView round(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setGravity(Gravity.CENTER);
        t.setBackground(oval(0xD9111116, 0x66FFFFFF));
        return t;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        startAsForeground();

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);

        minus = round("\u2212");
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { step(-1); }
        });
        pct = new TextView(this);
        pct.setTextColor(Color.WHITE);
        pct.setTypeface(Typeface.DEFAULT_BOLD);
        pct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        pct.setGravity(Gravity.CENTER);
        pct.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleMute(); }
        });
        plus = round("+");
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { step(1); }
        });
        circle = new Circle(this);
        circle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showPanel(minus.getVisibility() != View.VISIBLE);
            }
        });

        int d = dp(44);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(d, d);
        lp1.setMargins(0, 0, dp(6), 0);
        box.addView(minus, lp1);
        LinearLayout.LayoutParams lpp = new LinearLayout.LayoutParams(dp(56), d);
        box.addView(pct, lpp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(d, d);
        lp2.setMargins(0, 0, dp(6), 0);
        box.addView(plus, lp2);
        box.addView(circle, new LinearLayout.LayoutParams(d, d));
        showPanel(false);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(16);
        lp.y = dp(16);
        try {
            wm.addView(box, lp);
            added = true;
        } catch (Exception e) {
            stopSelf();
            return;
        }
        registerReceiver(volReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
        refresh();
        apply();
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("vq", "VQ Launcher",
                    NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, "vq");
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("VQ Launcher")
                .setContentText("Floating volume button is active");
        startForeground(1, b.build());
    }

    private void apply() {
        if (box != null) box.setVisibility(onHome ? View.GONE : View.VISIBLE);
    }

    private void showPanel(boolean show) {
        int v = show ? View.VISIBLE : View.GONE;
        minus.setVisibility(v);
        pct.setVisibility(v);
        plus.setVisibility(v);
        h.removeCallbacks(collapse);
        if (show) h.postDelayed(collapse, 4000);
    }

    private void step(int dir) {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int st = Math.max(1, Math.round(max / 15f));
        set(cur + dir * st);
    }

    private int preMute = -1;

    private void toggleMute() {
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (cur > 0) {
            preMute = cur;
            set(0);
        } else {
            set(preMute > 0 ? preMute : Math.max(1, max / 2));
        }
    }

    private void set(int v) {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        v = Math.max(0, Math.min(max, v));
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
        } catch (Exception e) {
            // ignore
        }
        refresh();
        h.removeCallbacks(collapse);
        h.postDelayed(collapse, 4000);
    }

    private void refresh() {
        if (am == null || circle == null) return;
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int p = cur * 100 / max;
        circle.muted = cur == 0;
        circle.level = p == 0 ? 0 : (p < 34 ? 1 : (p < 67 ? 2 : 3));
        circle.invalidate();
        pct.setText(p + "%");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        inst = null;
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(volReceiver); } catch (Exception e) { /* ignore */ }
        try { if (added) wm.removeView(box); } catch (Exception e) { /* ignore */ }
        super.onDestroy();
    }
}
