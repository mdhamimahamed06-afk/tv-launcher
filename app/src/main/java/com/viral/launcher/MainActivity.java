package com.viral.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Environment;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import java.io.FileOutputStream;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;

public class MainActivity extends Activity {

    static int RED = 0xFFE11D2E;
    private static final int[] ACCENTS = {0xFFE11D2E, 0xFF2F7BFF, 0xFF19B36B, 0xFF8B5CF6, 0xFFF59E0B};
    private static boolean booted = false;
    private static boolean startupDone = false;

    static class AppItem {
        String pkg, cls, label;
        Drawable icon;
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<AppItem> apps = new ArrayList<>();
    private SharedPreferences prefs;
    private ImageView wall;
    private View shade;
    private GridView grid;
    private BaseAdapter adapter;

    // start panel + taskbar
    private LinearLayout startPanel, pinBanner;
    private TextView pinText, startBtn;
    private final LinearLayout[] slots = new LinearLayout[3];
    private int pinSlot = -1;

    // clock
    private ClockView clockView;
    private TextView bigTime, bigDate, trayClock, trayWx;
    private TimeZone tz = TimeZone.getDefault();
    private final SimpleDateFormat fBig = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
    private final SimpleDateFormat fTray = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
    private final SimpleDateFormat fDate = new SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH);
    private final SimpleDateFormat fHm = new SimpleDateFormat("h:mm", Locale.ENGLISH);
    private final SimpleDateFormat fAp = new SimpleDateFormat("a", Locale.ENGLISH);
    private final SimpleDateFormat fH = new SimpleDateFormat("h", Locale.ENGLISH);
    private final SimpleDateFormat fMin = new SimpleDateFormat("mm", Locale.ENGLISH);
    private final SimpleDateFormat fDay = new SimpleDateFormat("d", Locale.ENGLISH);
    private final SimpleDateFormat fSub = new SimpleDateFormat("EEEE, MMM", Locale.ENGLISH);
    private long lastSec = -1;

    // volume
    private AudioManager am;
    private SpeakerView spk;
    private LinearLayout volPanel;
    private View volFill, volRest;
    private TextView volPct, volPlus;
    private int preMute = -1;

    // wallpaper
    private int wallIdx;
    private File customFile;
    private WallServer server;
    private String wallKey = "";
    private LinearLayout wallPanel;
    private QrView qrView;
    private TextView wallUrl, wallStatus, wallClose, wallHead, wallNext;
    private boolean profileMode = false;
    private File profileFile;

    // web windows (YouTube, Google, ... inside draggable windows on the desktop)
    private static final String SITE_URL = "https://viralquality.blogspot.com";
    private FrameLayout desk, fsHost;
    private View clockBlock;
    private LinearLayout webBox;

    private void refreshWebMenu() {
        if (webBox == null) return;
        webBox.removeAllViews();
        String all = prefs.getString("sites", "");
        for (String line : all.split("\n")) {
            int i = line.indexOf('\t');
            if (i <= 0) continue;
            final String n = line.substring(0, i), u = line.substring(i + 1);
            final String entry = line;
            TextView t = addMenu(webBox, "\u2605 " + n, new Runnable() { public void run() { openWeb(n, u); } });
            t.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    prefs.edit().putString("sites", prefs.getString("sites", "").replace(entry + "\n", "")).apply();
                    refreshWebMenu();
                    toast("Removed: " + n);
                    return true;
                }
            });
        }
    }
    private LinearLayout tabsBox;
    private final List<WebWindow> wins = new ArrayList<>();

    private boolean destroyed = false;

    private final int[][] PRESETS = {
            {0xFF7A0A1A, 0xFF08080C},
            {0xFF1B2A6B, 0xFF05060C},
            {0xFF4B1A7A, 0xFF07040E},
            {0xFF0F5B45, 0xFF04090A},
            {0xFF3A3A44, 0xFF0A0A0C}
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Date d = new Date();
            long sec = d.getTime() / 1000;
            if (sec != lastSec) {
                lastSec = sec;
                setBigTime(d);
                updateGreeting(d);
                updateWidgets(sec);
                bigDate.setText(fDate.format(d));
                trayClock.setText(fTray.format(d));
                if (locked) lockTime.setText(fBig.format(d) + "\n" + fDate.format(d));
            }
            if (slideOn) {
                long now = System.currentTimeMillis();
                if (lastSlide == 0) lastSlide = now;
                if (now - lastSlide > 300000L) {
                    lastSlide = now;
                    nextWall();
                }
            }
            boolean smooth = clockStyle == 1 && clockBlock.getVisibility() == View.VISIBLE && topWindow() == null;
            if (smooth) clockView.invalidate();
            extrasTick();
            extras2Tick();
            h.postDelayed(this, smooth ? 40 : 250);
        }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
        }
    };

    private final BroadcastReceiver volReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            refreshVol();
        }
    };

    private final Runnable volHide = new Runnable() {
        @Override
        public void run() {
            hideVol();
        }
    };

    // ---------- helpers ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable glass(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private StateListDrawable stateBg(boolean accent) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, glass(RED, 0xFFFFFFFF, 10, 2));
        sl.addState(new int[]{android.R.attr.state_pressed}, glass(0xFFB5121F, 0xFFFFFFFF, 10, 2));
        sl.addState(new int[]{android.R.attr.state_hovered}, accent ? glass(RED, 0xAAFFFFFF, 10, 1) : glass(0x55FFFFFF, 0xAAFFFFFF, 10, 1));
        sl.addState(new int[]{}, accent ? glass(RED, 0x55FFFFFF, 10, 1) : glass(0x22FFFFFF, 0x33FFFFFF, 10, 1));
        return sl;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView btn(String text, boolean accent) {
        TextView t = label(text, 15, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), 0, dp(18), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(stateBg(accent));
        return t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------- custom views ----------

    private class ClockView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        ClockView(Context c) {
            super(c);
        }

        private void hand(Canvas c, float cx, float cy, float deg, float len, float tail, int width, int color) {
            double a = Math.toRadians(deg);
            float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
            p.setColor(color);
            p.setStrokeWidth(width);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(cx - sx * tail, cy - sy * tail, cx + sx * len, cy + sy * len, p);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight();
            float cx = w / 2f, cy = hh / 2f;
            float r = Math.min(w, hh) / 2f - dp(4);

            p.setStyle(Paint.Style.FILL);
            p.setColor(0x00000000);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(0x66FFFFFF);
            c.drawCircle(cx, cy, r, p);

            Calendar cal = Calendar.getInstance(tz);
            cal.setTimeInMillis(System.currentTimeMillis());
            float sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f;
            float min = cal.get(Calendar.MINUTE) + sec / 60f;
            float hr = (cal.get(Calendar.HOUR) % 12) + min / 60f;

            // red ring that fills up once per minute
            float m = dp(10);
            RectF rf = new RectF(cx - r + m, cy - r + m, cx + r - m, cy + r - m);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(4));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor((RED & 0xFFFFFF) | 0x33000000);
            c.drawArc(rf, -90, 360, false, p);
            p.setColor(RED);
            c.drawArc(rf, -90, sec * 6f, false, p);

            // ticks
            for (int i = 0; i < 60; i++) {
                boolean big = i % 5 == 0;
                double a = Math.toRadians(i * 6);
                float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
                float r1 = r - dp(22);
                float r2 = r1 - dp(big ? 11 : 5);
                p.setStrokeWidth(big ? dp(3) : dp(1));
                p.setStrokeCap(Paint.Cap.BUTT);
                p.setColor(big ? 0xFFFFFFFF : 0x88FFFFFF);
                c.drawLine(cx + sx * r1, cy + sy * r1, cx + sx * r2, cy + sy * r2, p);
            }

            hand(c, cx, cy, hr * 30f, r * 0.45f, r * 0.08f, dp(6), Color.WHITE);
            hand(c, cx, cy, min * 6f, r * 0.68f, r * 0.08f, dp(4), Color.WHITE);
            hand(c, cx, cy, sec * 6f, r * 0.76f, r * 0.16f, dp(2), RED);
            p.setStyle(Paint.Style.FILL);
            p.setColor(RED);
            c.drawCircle(cx, cy, dp(7), p);
            p.setColor(Color.WHITE);
            c.drawCircle(cx, cy, dp(2), p);
        }
    }

    private class QrView extends View {
        Qr qr;
        final Paint qp = new Paint();

        QrView(Context c) {
            super(c);
            qp.setAntiAlias(false);
        }

        void set(Qr q) {
            qr = q;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            qp.setColor(Color.WHITE);
            c.drawRect(0, 0, getWidth(), getHeight(), qp);
            if (qr == null) return;
            int n = qr.size + 8;
            int cell = Math.min(getWidth(), getHeight()) / n;
            int offX = (getWidth() - cell * n) / 2, offY = (getHeight() - cell * n) / 2;
            qp.setColor(Color.BLACK);
            for (int y = 0; y < qr.size; y++) {
                for (int x = 0; x < qr.size; x++) {
                    if (qr.mod[y][x]) {
                        int l = offX + (x + 4) * cell, t = offY + (y + 4) * cell;
                        c.drawRect(l, t, l + cell, t + cell, qp);
                    }
                }
            }
        }
    }

    private class SpeakerView extends View {
        int level = 3;
        boolean muted = false;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        SpeakerView(Context c) {
            super(c);
            setFocusable(true);
            setClickable(true);
            setBackground(stateBg(false));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight();
            float s = Math.min(w, hh) * 0.5f;
            float cx = w / 2f - s * 0.15f, cy = hh / 2f;
            float x0 = cx - s * 0.5f;
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

    // ---------- onCreate / layout ----------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new ColorDrawable(0xFF000000));
        prefs = getSharedPreferences("vq", MODE_PRIVATE);
        RED = ACCENTS[Math.abs(prefs.getInt("accent", 0)) % ACCENTS.length];
        WebWindow.RED = RED;
        slideOn = prefs.getBoolean("slide", false);
        customFile = new File(getFilesDir(), "custom_wall.jpg");
        profileFile = new File(getFilesDir(), "profile.jpg");
        wallIdx = prefs.getInt("wall", 0);
        applyTz(prefs.getString("tz", ""));

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();

        FrameLayout root = new FrameLayout(this);
        FrameLayout.LayoutParams match = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wall, match);
        shade = new View(this);
        root.addView(shade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // desktop area: only the wallpaper and the animated clock
        desk = new FrameLayout(this);
        col.addView(desk, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        buildDeskIcons(dm);
        buildWidgets(dm);
        buildExtraWidgets(dm);
        buildExtraWidgets2(dm);
        clockBlock = buildClockBlock(dm);
        desk.addView(clockBlock, clockLp());
        clockBlock.setVisibility(prefs.getBoolean("clockoff", false) ? View.GONE : View.VISIBLE);

        View line = new View(this);
        line.setBackgroundColor(0x33FFFFFF);
        col.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        col.addView(buildTaskbar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

        // start panel (all apps + system shortcuts)
        startPanel = buildStartPanel();
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                (int) (dm.widthPixels * 0.82f), (int) (dm.heightPixels * 0.74f),
                Gravity.BOTTOM | Gravity.START);
        slp.bottomMargin = dp(72);
        slp.leftMargin = dp(12);
        root.addView(startPanel, slp);
        startPanel.setVisibility(View.GONE);

        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        volPanel = buildVolPanel();
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                dp(520), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        vlp.bottomMargin = dp(72);
        vlp.rightMargin = dp(16);
        root.addView(volPanel, vlp);
        volPanel.setVisibility(View.GONE);

        wallPanel = buildWallPanel();
        FrameLayout.LayoutParams wpl = new FrameLayout.LayoutParams(
                dp(640), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(wallPanel, wpl);
        wallPanel.setVisibility(View.GONE);

        fsHost = new FrameLayout(this);
        fsHost.setBackgroundColor(0xFF000000);
        root.addView(fsHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fsHost.setVisibility(View.GONE);

        buildPanels(root);
        buildExtras(root, dm);
        buildExtras2(root);

        clipMgr = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipL = new ClipboardManager.OnPrimaryClipChangedListener() {
            public void onPrimaryClipChanged() {
                try {
                    ClipData cd = clipMgr.getPrimaryClip();
                    if (cd != null && cd.getItemCount() > 0) {
                        CharSequence t = cd.getItemAt(0).coerceToText(MainActivity.this);
                        if (t != null) Tools.addClip(t.toString());
                    }
                } catch (Throwable x) {
                    // ignore
                }
            }
        };
        clipMgr.addPrimaryClipChangedListener(clipL);
        h.postDelayed(new Runnable() {
            public void run() { checkLatest(); }
        }, 4000);
        registerReceiver(dlReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        setContentView(root);
        if (!booted) {
            booted = true;
            if (prefs.getString("pin", "").length() > 0) lockNow(); else runStartup();
        } else if (prefs.getString("pin", "").length() == 0) {
            startupDone = true;
        }
        applyWall();
        refreshVol();
        registerReceiver(volReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"));

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        loadApps();
        loadWeather();
        startBtn.requestFocus();
    }

    private FrameLayout.LayoutParams clockLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        lp.topMargin = dp(24);
        return lp;
    }

    private int clockStyle = 0;
    private TextView greetView;

    private LinearLayout buildClockBlock(android.util.DisplayMetrics dm) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);

        greetView = label("", 22, 0xFFFFFFFF, false);
        greetView.setShadowLayer(dp(5), 0, dp(1), 0xAA000000);
        greetView.setGravity(Gravity.CENTER);
        c.addView(greetView);

        int sz = Math.min(dp(200), (int) (dm.heightPixels * 0.34f));
        clockView = new ClockView(this);
        LinearLayout.LayoutParams cvl = new LinearLayout.LayoutParams(sz, sz);
        cvl.topMargin = dp(8);
        c.addView(clockView, cvl);

        bigTime = label("", 112, Color.WHITE, false);
        bigTime.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        bigTime.setIncludeFontPadding(false);
        bigTime.setShadowLayer(dp(6), 0, dp(2), 0xAA000000);
        bigTime.setGravity(Gravity.CENTER);
        c.addView(bigTime);

        bigDate = label("", 18, 0xFFFFFFFF, false);
        bigDate.setShadowLayer(dp(5), 0, dp(1), 0xAA000000);
        bigDate.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = dp(4);
        c.addView(bigDate, dl);

        c.setClickable(true);
        c.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cycleClock(); }
        });
        clockStyle = Math.abs(prefs.getInt("cstyle", 0)) % 3;
        applyClockStyle();
        return c;
    }

    private void applyClockStyle() {
        clockView.setVisibility(clockStyle == 1 ? View.VISIBLE : View.GONE);
        bigTime.setVisibility(clockStyle == 1 ? View.GONE : View.VISIBLE);
        bigTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, clockStyle == 2 ? 90 : 112);
        bigTime.setLineSpacing(0, clockStyle == 2 ? 0.82f : 1f);
        lastSec = -1;
    }

    private void cycleClock() {
        clockStyle = (clockStyle + 1) % 3;
        prefs.edit().putInt("cstyle", clockStyle).apply();
        applyClockStyle();
    }

    private void setBigTime(Date d) {
        if (clockStyle == 1) return;
        if (clockStyle == 0) {
            String hm = fHm.format(d), ap = fAp.format(d);
            SpannableString sp = new SpannableString(hm + " " + ap);
            sp.setSpan(new RelativeSizeSpan(0.3f), hm.length(), sp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            bigTime.setText(sp);
        } else {
            String hh = fH.format(d), mm = fMin.format(d);
            SpannableString sp = new SpannableString(hh + "\n" + mm);
            sp.setSpan(new ForegroundColorSpan(RED), hh.length() + 1, sp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            bigTime.setText(sp);
        }
    }

    private void updateGreeting(Date d) {
        Calendar c = Calendar.getInstance(tz);
        c.setTime(d);
        int hr = c.get(Calendar.HOUR_OF_DAY);
        String g;
        int col;
        if (hr < 5) { g = "Good night"; col = 0xFF9DB7FF; }
        else if (hr < 12) { g = "Good morning"; col = 0xFFFFD27A; }
        else if (hr < 17) { g = "Good afternoon"; col = 0xFFFFFFFF; }
        else if (hr < 21) { g = "Good evening"; col = 0xFFFFA45C; }
        else { g = "Good night"; col = 0xFF9DB7FF; }
        greetView.setText(g + ", " + userName());
        greetView.setTextColor(col);
    }

    // ----- home widgets (draggable cards)

    private final List<View> widgetViews = new ArrayList<>();
    private final List<Integer> widgetIds = new ArrayList<>();
    private final String[] WIDGET_NAMES = {"Weather", "Today (date)", "Note", "System (RAM)", "World clock",
            "YouTube search", "To-do list", "Music", "Prayer time", "News & cricket"};

    private boolean widgetWanted(int idx) {
        return !prefs.getBoolean("widgetsoff", false) && !prefs.getBoolean("wh" + idx, false);
    }

    private void chooseWidgetsDialog() {
        if (prefs.getBoolean("widgetsoff", false)) {
            prefs.edit().putBoolean("widgetsoff", false).apply();
            for (int i = 0; i < widgetViews.size(); i++) {
                widgetViews.get(i).setVisibility(widgetWanted(widgetIds.get(i)) ? View.VISIBLE : View.GONE);
            }
        }
        final boolean[] on = new boolean[WIDGET_NAMES.length];
        for (int i = 0; i < on.length; i++) on[i] = !prefs.getBoolean("wh" + i, false);
        new AlertDialog.Builder(this)
                .setTitle("Choose widgets (tick = show)")
                .setMultiChoiceItems(WIDGET_NAMES, on, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int which, boolean checked) {
                        prefs.edit().putBoolean("wh" + which, !checked).apply();
                        for (int i = 0; i < widgetViews.size(); i++) {
                            if (widgetIds.get(i) == which) {
                                widgetViews.get(i).setVisibility(checked ? View.VISIBLE : View.GONE);
                            }
                        }
                    }
                })
                .setPositiveButton("Done", null)
                .show();
    }
    private TextView wxCard, dayNum, daySub, noteV, ramV;
    private long lastWidgetSec = -100;

    private LinearLayout cardBase(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(10), dp(14), dp(8));
        c.setBackground(glass(0x88101015, 0x55FFFFFF, 16, 1));
        c.setFocusable(true);
        c.setClickable(true);
        TextView t = label(title, 10, RED, true);
        t.setLetterSpacing(0.2f);
        c.addView(t);
        return c;
    }

    private void addCard(int idx, View card, int x, int y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(180), dp(96));
        lp.leftMargin = prefs.getInt("w" + idx + "x", x);
        lp.topMargin = prefs.getInt("w" + idx + "y", y);
        desk.addView(card, lp);
        makeDraggable(card, "w" + idx, null);
        card.setVisibility(widgetWanted(idx) ? View.VISIBLE : View.GONE);
        widgetViews.add(card);
        widgetIds.add(idx);
    }

    private void buildWidgets(android.util.DisplayMetrics dm) {
        int total = dp(180) * 4 + dp(14) * 3;
        int x0 = Math.max(dp(10), (dm.widthPixels - total) / 2);
        int y = dm.heightPixels - dp(63) - dp(96) - dp(24) - (prefs.getBoolean("dock", true) ? dp(76) : 0);

        LinearLayout w0 = cardBase("WEATHER");
        wxCard = label("--", 20, Color.WHITE, true);
        wxCard.setMaxLines(2);
        w0.addView(wxCard);
        addCard(0, w0, x0, y);

        LinearLayout w1 = cardBase("TODAY");
        dayNum = label("", 32, Color.WHITE, true);
        w1.addView(dayNum);
        daySub = label("", 13, 0xCCFFFFFF, false);
        w1.addView(daySub);
        w1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleCal(); }
        });
        addCard(1, w1, x0 + dp(194), y);

        LinearLayout w2 = cardBase("NOTE");
        noteV = label("", 13, Color.WHITE, false);
        noteV.setMaxLines(3);
        noteV.setEllipsize(android.text.TextUtils.TruncateAt.END);
        w2.addView(noteV);
        w2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openTool("Notepad"); }
        });
        addCard(2, w2, x0 + dp(388), y);

        LinearLayout w3 = cardBase("SYSTEM");
        ramV = label("RAM --", 20, Color.WHITE, true);
        w3.addView(ramV);
        w3.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openTool("System"); }
        });
        addCard(3, w3, x0 + dp(582), y);
    }

    private void updateWidgets(long sec) {
        if (wxCard == null || sec - lastWidgetSec < 5) return;
        lastWidgetSec = sec;
        Date d = new Date();
        updateExtraWidgets(d);
        CharSequence w = trayWx.getText();
        wxCard.setText(w.length() > 0 ? w : "Waiting for weather...");
        dayNum.setText(fDay.format(d));
        daySub.setText(fSub.format(d));
        String n = prefs.getString("note", "").trim();
        noteV.setText(n.length() == 0 ? "Click to write a note" : n);
        try {
            ActivityManager am2 = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am2.getMemoryInfo(mi);
            int pct = (int) (100L * (mi.totalMem - mi.availMem) / Math.max(1L, mi.totalMem));
            ramV.setText("RAM " + pct + "%");
        } catch (Throwable t) {
            // ignore
        }
    }

    private void makeDraggable(final View v, final String key, final String ctxName) {
        final float[] st = new float[4];
        final boolean[] f = new boolean[2];
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, MotionEvent e) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        f[0] = false;
                        f[1] = (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0;
                        if (f[1]) {
                            showCtx(e.getRawX(), e.getRawY(), ctxName);
                            return true;
                        }
                        st[0] = e.getRawX();
                        st[1] = e.getRawY();
                        st[2] = lp.leftMargin;
                        st[3] = lp.topMargin;
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (f[1]) return true;
                        float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1];
                        if (!f[0] && (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8))) f[0] = true;
                        if (f[0]) {
                            lp.leftMargin = (int) Math.max(0, Math.min(desk.getWidth() - v.getWidth(), st[2] + dx));
                            lp.topMargin = (int) Math.max(0, Math.min(desk.getHeight() - v.getHeight(), st[3] + dy));
                            v.setLayoutParams(lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if (f[1]) return true;
                        if (f[0]) {
                            prefs.edit().putInt(key + "x", lp.leftMargin).putInt(key + "y", lp.topMargin).apply();
                        } else {
                            v.performClick();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void tip(View v, String text) {
        if (Build.VERSION.SDK_INT >= 26) v.setTooltipText(text);
    }

    private LinearLayout buildTaskbar() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(14), 0, dp(36), 0);
        t.setBackgroundColor(prefs.getBoolean("glass", false) ? 0x990A0A0E : 0xE60A0A0E);
        taskbarView = t;

        tbLead = new View(this);
        t.addView(tbLead, new LinearLayout.LayoutParams(0, 1, prefs.getBoolean("tbleft", false) ? 0f : 1f));

        startBtn = btn("■  START", true);
        tip(startBtn, "START (Win key)");
        startBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleStart(); }
        });
        t.addView(startBtn, tbLp());

        TextView searchBtn = btn("Search", false);
        searchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSearch(); }
        });
        t.addView(searchBtn, tbLp());

        updBadge = btn("UPDATE", true);
        updBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        updBadge.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { updateLauncher(); }
        });
        updBadge.setVisibility(View.GONE);
        t.addView(updBadge, tbLp());
        badgeAnim = android.animation.ObjectAnimator.ofFloat(updBadge, "alpha", 1f, 0.35f);
        badgeAnim.setDuration(700);
        badgeAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        badgeAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        badgeAnim.start();

        for (int i = 0; i < 3; i++) {
            slots[i] = makeSlot(i);
            t.addView(slots[i], tbLp());
        }

        tabsBox = new LinearLayout(this);
        tabsBox.setOrientation(LinearLayout.HORIZONTAL);
        t.addView(tabsBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View sp = new View(this);
        t.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        trayWx = label("", 15, 0xCCFFFFFF, false);
        trayWx.setPadding(0, 0, dp(14), 0);
        trayWx.setSingleLine(true);
        trayWx.setMaxWidth(dp(150));
        t.addView(trayWx);
        spk = new SpeakerView(this);
        spk.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleVol(); }
        });
        LinearLayout.LayoutParams sk = new LinearLayout.LayoutParams(dp(52), dp(42));
        sk.setMargins(0, 0, dp(16), 0);
        t.addView(spk, sk);
        trayClock = label("", 18, Color.WHITE, true);
        trayClock.setFocusable(true);
        trayClock.setClickable(true);
        trayClock.setPadding(dp(10), 0, dp(10), 0);
        trayClock.setGravity(Gravity.CENTER_VERTICAL);
        trayClock.setBackground(stateBg(false));
        tip(trayClock, "Calendar");
        trayClock.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleCal(); }
        });
        t.addView(trayClock);
        return t;
    }

    private LinearLayout.LayoutParams tbLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        p.setMargins(0, 0, dp(10), 0);
        return p;
    }

    // ---------- web windows ----------

    private final WebWindow.Host winHost = new WebWindow.Host() {
        public void onWindowChanged() { refreshTabs(); }

        public void onWindowClosed(WebWindow w) {
            wins.remove(w);
            refreshTabs();
        }

        public void onFullscreenVideo(View v, WebChromeClient.CustomViewCallback cb) {
            fsView = v;
            fsCb = cb;
            fsHost.removeAllViews();
            fsHost.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fsHost.setVisibility(View.VISIBLE);
        }

        public void onDownload(String url, String ua, String cd, String mime) {
            startDownload(url, ua, URLUtil.guessFileName(url, cd, mime), mime, false);
        }

        public void onSaveSite(String name, String url) {
            String old = prefs.getString("sites", "");
            if (old.contains("\t" + url + "\n")) {
                toast("Already saved");
                return;
            }
            prefs.edit().putString("sites", old + name.replace("\t", " ").replace("\n", " ") + "\t" + url + "\n").apply();
            refreshWebMenu();
            toast("Saved to START menu: " + name);
        }

        public void onFullscreenVideoEnd() {
            fsHost.removeAllViews();
            fsHost.setVisibility(View.GONE);
            fsView = null;
            fsCb = null;
        }
    };

    private View fsView;
    private WebChromeClient.CustomViewCallback fsCb;

    private boolean showExisting(String name) {
        for (WebWindow w : wins) {
            if (w.name.equals(name)) {
                w.show();
                return true;
            }
        }
        return false;
    }

    private void openWeb(String name, String url) {
        hideStart();
        closeAllPanels();
        if (showExisting(name)) return;
        openWin(name, url, null, false);
    }

    private void openTool(String name) {
        hideStart();
        closeAllPanels();
        if (showExisting(name)) return;
        View v;
        if (name.equals("Files")) v = Tools.files(this, RED, fileOpener);
        else if (name.equals("Speed")) v = Tools.speedTest(this, RED);
        else if (name.equals("Downloads")) v = Tools.downloads(this, RED, fileOpener);
        else if (name.equals("Convert")) v = Tools.converter(this, RED);
        else if (name.equals("Photos")) v = Tools.imageViewer(this, RED, null);
        else if (name.equals("Notepad")) v = Tools.notepad(this, prefs);
        else if (name.equals("Calculator")) v = Tools.calculator(this, RED);
        else if (name.equals("Clipboard")) v = Tools.clipboard(this, RED);
        else if (name.equals("Timer")) v = Tools.timer(this, RED);
        else v = Tools.monitor(this, RED, winSrc);
        openWin(name, null, v, name.equals("Calculator") || name.equals("Timer") || name.equals("Convert"));
    }

    private void openWin(String name, String url, View content, boolean small) {
        if (wins.size() >= 3) {
            WebWindow old = wins.get(0);
            String n = old.name;
            old.close();
            toast("Maximum 3 windows: closed " + n);
        }
        int slot = 0;
        while (slot < 3) {
            boolean usedS = false;
            for (WebWindow w : wins) if (w.slot == slot) usedS = true;
            if (!usedS) break;
            slot++;
        }
        if (slot > 2) slot = 2;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int dw = desk.getWidth() > 0 ? desk.getWidth() : dm.widthPixels;
        int dh = desk.getHeight() > 0 ? desk.getHeight() : dm.heightPixels - dp(64);
        int pad = dp(10);
        int w = (dw - pad * 3) / 2, hgt = dh - pad * 2;
        if (small) {
            w = Math.min(w, dp(380));
            hgt = Math.min(hgt, dp(520));
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, hgt);
        lp.leftMargin = slot == 0 ? pad : dw - pad - w;
        lp.topMargin = pad;
        try {
            WebWindow win = new WebWindow(this, name, url, content, winHost, slot);
            win.small = small;
            if (url != null && (name.equals("WhatsApp") || name.equals("Telegram") || name.equals("TikTok"))) win.setDesktop(true);
            desk.addView(win, lp);
            wins.add(win);
            if (!small) retile();
            win.setAlpha(0f);
            win.setScaleX(0.93f);
            win.setScaleY(0.93f);
            win.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start();
        } catch (Throwable t) {
            toast("Could not open window");
        }
        refreshTabs();
    }

    private final Tools.WinSource winSrc = new Tools.WinSource() {
        public List<String> names() {
            List<String> l = new ArrayList<>();
            for (WebWindow w : wins) l.add(w.name);
            return l;
        }

        public void close(String name) {
            for (WebWindow w : new ArrayList<>(wins)) {
                if (w.name.equals(name)) {
                    w.close();
                    return;
                }
            }
        }
    };

    private void newBrowser() {
        String n = "Browser";
        for (int i = 2; i <= 3; i++) {
            boolean used = false;
            for (WebWindow w : wins) if (w.name.equals(n)) used = true;
            if (!used) break;
            n = "Browser " + i;
        }
        openWeb(n, "https://www.google.com");
    }

    private void closeTop() {
        WebWindow w = topWindow();
        if (w != null) w.close();
    }

    private void cycleWindows() {
        if (wins.isEmpty()) return;
        WebWindow top = topWindow();
        WebWindow next = null;
        for (WebWindow w : wins) {
            if (w != top) {
                next = w;
                break;
            }
        }
        if (next == null) next = wins.get(0);
        next.show();
        refreshTabs();
    }

    private void showDesktop() {
        boolean anyVisible = false;
        for (WebWindow w : wins) if (!w.minimized) anyVisible = true;
        for (WebWindow w : new ArrayList<>(wins)) {
            if (anyVisible) w.minimize(); else w.show();
        }
    }

    private void screenshot() {
        try {
            View root = getWindow().getDecorView();
            Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(b));
            String name = "VQ_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(new Date()) + ".png";
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots");
            if (!(dir.exists() || dir.mkdirs()) || !dir.canWrite()) dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            File out = new File(dir, name);
            FileOutputStream fo = new FileOutputStream(out);
            b.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            toast("Screenshot saved: " + out.getPath());
        } catch (Throwable t) {
            toast("Screenshot failed");
        }
    }

    // ----- custom shortcuts (added by the user)

    private String[] findCustom(char key) {
        for (String line : prefs.getString("shortcuts", "").split("\n")) {
            String[] p = line.split("\t");
            if (p.length == 3 && p[0].length() == 1 && Character.toUpperCase(p[0].charAt(0)) == key) return p;
        }
        return null;
    }

    private boolean runCustom(KeyEvent e) {
        char c = Character.toUpperCase((char) e.getDisplayLabel());
        if (!Character.isLetterOrDigit(c)) return false;
        String[] p = findCustom(c);
        if (p == null) return false;
        closeAllPanels();
        openTarget(p[1], p[2]);
        return true;
    }

    private void openTarget(String name, String addr) {
        String a = addr.trim();
        if (a.startsWith("http://") || a.startsWith("https://")) {
            openWeb(name, a);
        } else if (a.indexOf(' ') < 0 && a.indexOf('.') > 0) {
            openWeb(name, "https://" + a);
        } else {
            String ql = a.toLowerCase(Locale.ENGLISH);
            for (AppItem it : apps) {
                if (it.label != null && it.label.toLowerCase(Locale.ENGLISH).contains(ql)) {
                    launch(it);
                    return;
                }
            }
            toast("App not found: " + a);
        }
    }

    private void addShortcutDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        final EditText keyEt = new EditText(this);
        keyEt.setSingleLine(true);
        keyEt.setHint("Key: one letter or number (e.g. K)");
        box.addView(keyEt);
        final EditText nameEt = new EditText(this);
        nameEt.setSingleLine(true);
        nameEt.setHint("Name (e.g. Netflix)");
        box.addView(nameEt);
        final EditText addrEt = new EditText(this);
        addrEt.setSingleLine(true);
        addrEt.setHint("Website (netflix.com) or app name");
        addrEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(addrEt);
        new AlertDialog.Builder(this)
                .setTitle("New shortcut: Alt + key")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String k = keyEt.getText().toString().trim();
                        String n = nameEt.getText().toString().trim().replace("\t", " ").replace("\n", " ");
                        String ad = addrEt.getText().toString().trim().replace("\t", " ").replace("\n", " ");
                        if (k.length() != 1 || !Character.isLetterOrDigit(k.charAt(0)) || n.length() == 0 || ad.length() == 0) {
                            toast("Fill all 3 boxes (key = exactly 1 letter or number)");
                            return;
                        }
                        char kc = Character.toUpperCase(k.charAt(0));
                        StringBuilder sb = new StringBuilder();
                        for (String line : prefs.getString("shortcuts", "").split("\n")) {
                            String[] p = line.split("\t");
                            if (p.length == 3 && p[0].length() == 1 && Character.toUpperCase(p[0].charAt(0)) != kc) sb.append(line).append("\n");
                        }
                        sb.append(kc).append("\t").append(n).append("\t").append(ad).append("\n");
                        prefs.edit().putString("shortcuts", sb.toString()).apply();
                        toast("Saved: Alt+" + kc + " opens " + n);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void manageShortcutsDialog() {
        final List<String> lines = new ArrayList<>();
        final List<CharSequence> shown = new ArrayList<>();
        for (String line : prefs.getString("shortcuts", "").split("\n")) {
            String[] p = line.split("\t");
            if (p.length == 3) {
                lines.add(line);
                shown.add("Alt+" + p[0].toUpperCase(Locale.ENGLISH) + "  →  " + p[1] + "   (tap to remove)");
            }
        }
        if (shown.isEmpty()) {
            toast("No custom shortcuts yet. Use \"Add shortcut\".");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("My shortcuts")
                .setItems(shown.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String all = prefs.getString("shortcuts", "");
                        prefs.edit().putString("shortcuts", all.replace(lines.get(which) + "\n", "")).apply();
                        toast("Removed");
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private boolean handleShortcut(KeyEvent e) {
        if (runCustom(e)) return true;
        return handleBuiltinShortcut(e.getKeyCode());
    }

    private boolean handleBuiltinShortcut(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_Y: openWeb("YouTube", "https://m.youtube.com"); return true;
            case KeyEvent.KEYCODE_G: openWeb("Google", "https://www.google.com"); return true;
            case KeyEvent.KEYCODE_F: openWeb("Facebook", "https://m.facebook.com"); return true;
            case KeyEvent.KEYCODE_B: newBrowser(); return true;
            case KeyEvent.KEYCODE_E: openTool("Files"); return true;
            case KeyEvent.KEYCODE_N: openTool("Notepad"); return true;
            case KeyEvent.KEYCODE_C: openTool("Calculator"); return true;
            case KeyEvent.KEYCODE_M: openTool("System"); return true;
            case KeyEvent.KEYCODE_V: openTool("Clipboard"); return true;
            case KeyEvent.KEYCODE_T: openTool("Timer"); return true;
            case KeyEvent.KEYCODE_S: openSearch(); return true;
            case KeyEvent.KEYCODE_L: lockFromMenu(); return true;
            case KeyEvent.KEYCODE_D: showDesktop(); return true;
            case KeyEvent.KEYCODE_W: closeTop(); return true;
            case KeyEvent.KEYCODE_TAB: cycleWindows(); return true;
            case KeyEvent.KEYCODE_U: updateLauncher(); return true;
            case KeyEvent.KEYCODE_Q: toggleQuick(); return true;
            case KeyEvent.KEYCODE_P: openPhotos(null); return true;
            case KeyEvent.KEYCODE_X: openTool("Speed"); return true;
            case KeyEvent.KEYCODE_O: openTool("Downloads"); return true;
            case KeyEvent.KEYCODE_Z: openBuiltin("TikTok"); return true;
            case KeyEvent.KEYCODE_A: openBuiltin("WhatsApp"); return true;
            case KeyEvent.KEYCODE_H: openBuiltin("Telegram"); return true;
            case KeyEvent.KEYCODE_J: openTool("Convert"); return true;
            case KeyEvent.KEYCODE_K: toggleKb(); return true;
            case KeyEvent.KEYCODE_R: cycleSplit(); return true;
            default: return false;
        }
    }

    private String customList() {
        StringBuilder sb = new StringBuilder("Your shortcuts:");
        boolean any = false;
        for (String line : prefs.getString("shortcuts", "").split("\n")) {
            String[] p = line.split("\t");
            if (p.length == 3) {
                sb.append("\nAlt+").append(p[0].toUpperCase(Locale.ENGLISH)).append("  ").append(p[1]);
                any = true;
            }
        }
        if (!any) sb.append("\n(none yet: START > Add shortcut)");
        return sb.toString();
    }

    private void shortcutsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Keyboard shortcuts")
                .setMessage("Alt (or Win) +\n"
                        + "Y YouTube    G Google    F Facebook    B New browser\n"
                        + "E Files    N Notepad    C Calculator    M Task manager\n"
                        + "V Clipboard    T Timer    S Search    L Lock\n"
                        + "D Show desktop    W Close window    Tab Switch window\n"
                        + "U Update launcher    Q Quick settings\n"
                        + "P Photos    X Speed test    O Downloads\n"
                        + "J Converter    R Split size 50/70/30\n"
                        + "Z TikTok    A WhatsApp    H Telegram\n"
                        + "K Keyboard-mouse mode (arrows, Enter = click)\n\n"
                        + "Ctrl+T new browser, Ctrl+W close, Ctrl+Tab switch\n"
                        + "Ctrl+Shift+Esc Task manager\n"
                        + "Win key alone = START    Esc = close menus\n"
                        + "PrtSc = Screenshot\n"
                        + "Just start typing on the home screen to search.\n\n"
                        + customList())
                .setPositiveButton("OK", null)
                .show();
    }

    private void searchWeb(String q) {
        for (WebWindow w : wins) {
            if (w.name.equals("Browser")) {
                w.load(q);
                w.show();
                return;
            }
        }
        openWin("Browser", "https://www.google.com/search?q=" + Uri.encode(q), null, false);
    }

    private WebWindow topWindow() {
        WebWindow top = null;
        int best = -1;
        for (WebWindow w : wins) {
            if (w.minimized) continue;
            int i = desk.indexOfChild(w);
            if (i > best) {
                best = i;
                top = w;
            }
        }
        return top;
    }

    private void refreshTabs() {
        if (tabsBox == null) return;
        tabsBox.removeAllViews();
        WebWindow top = topWindow();
        for (final WebWindow w : wins) {
            TextView t = btn(w.name, w == top);
            t.setPadding(dp(12), 0, dp(12), 0);
            t.setSingleLine(true);
            t.setMaxWidth(dp(110));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (w.minimized) w.show();
                    else if (w == topWindow()) w.minimize();
                    else w.bringToFront();
                    refreshTabs();
                }
            });
            t.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    w.close();
                    return true;
                }
            });
            tabsBox.addView(t, tbLp());
        }
    }

    // ---------- desktop extras ----------

    private View tbLead;
    private LinearLayout iconsBox, ctxMenu, searchPanel, searchResults, calPanel, calGrid, taskbarView;
    private FrameLayout ctxLayer, lockView;
    private EditText searchEt;
    private TextView calTitle, lockMsg, lockName, lockAvatar, lockTime;
    private EditText lockEt;
    private final Calendar calShown = Calendar.getInstance();
    private boolean locked = false;
    private boolean slideOn = false;
    private long lastSlide = 0;
    private float lastX, lastY;

    private GradientDrawable panelBg() {
        return glass(0xF2101015, 0x44FFFFFF, 16, 1);
    }

    private final List<View> iconViews = new ArrayList<>();

    private void buildDeskIcons(android.util.DisplayMetrics dm) {
        final String[] names = {"YouTube", "Google", "Facebook", "Browser", "Files", "Notepad", "Calculator", "System", "Clipboard", "Timer", "TikTok", "WhatsApp", "Telegram"};
        final String[] marks = {"Y", "G", "f", "B", "F", "N", "=", "S", "C", "T", "Tk", "Wa", "Tg"};
        final int[] cols = {0xFFE11D2E, 0xFF2F7BFF, 0xFF1877F2, 0xFF4B5262, 0xFFF59E0B, 0xFF19B36B, 0xFF8B5CF6, 0xFF0EA5A5, 0xFFEC4899, 0xFF6366F1, 0xFF111111, 0xFF25D366, 0xFF229ED9};
        int rows = Math.max(3, (int) ((dm.heightPixels / dm.density - 62 - 40) / 98));
        boolean off = prefs.getBoolean("iconsoff", true);
        for (int i = 0; i < names.length; i++) {
            View v = deskIcon(names[i], marks[i], cols[i]);
            int defX = dp(14) + (i / rows) * dp(92);
            int defY = dp(14) + (i % rows) * dp(98);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(88), dp(92));
            lp.leftMargin = prefs.getInt("i" + i + "x", defX);
            lp.topMargin = prefs.getInt("i" + i + "y", defY);
            desk.addView(v, lp);
            makeDraggable(v, "i" + i, names[i]);
            v.setVisibility(off ? View.GONE : View.VISIBLE);
            iconViews.add(v);
        }

        desk.setLongClickable(true);
        desk.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                lastX = e.getRawX();
                lastY = e.getRawY();
                if ((e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) showCtx(lastX, lastY, null);
                    return true;
                }
                return false;
            }
        });
        desk.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showCtx(lastX, lastY, null);
                return true;
            }
        });
    }

    private View deskIcon(final String name, String mark, int color) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(0, dp(6), 0, dp(4));
        t.setFocusable(true);
        t.setClickable(true);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, glass(0x44FFFFFF, 0xFFFFFFFF, 12, 2));
        sl.addState(new int[]{android.R.attr.state_pressed}, glass(0x55FFFFFF, 0xFFFFFFFF, 12, 2));
        sl.addState(new int[]{android.R.attr.state_hovered}, glass(0x33FFFFFF, 0x88FFFFFF, 12, 1));
        sl.addState(new int[]{}, glass(0x00000000, 0, 12, 0));
        t.setBackground(sl);
        TextView badge = label(mark, 20, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(glass(color, 0x66FFFFFF, 14, 1));
        t.addView(badge, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView l = label(name.equals("System") ? "Task manager" : name, 11, Color.WHITE, false);
        l.setSingleLine(true);
        l.setGravity(Gravity.CENTER);
        l.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = dp(4);
        t.addView(l, ll);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openBuiltin(name); }
        });
        tip(t, name);
        return t;
    }

    private String siteUrl(String name) {
        if (name.equals("YouTube")) return "https://m.youtube.com";
        if (name.equals("Google") || name.equals("Browser")) return "https://www.google.com";
        if (name.equals("Facebook")) return "https://m.facebook.com";
        if (name.equals("TikTok")) return "https://www.tiktok.com";
        if (name.equals("WhatsApp")) return "https://web.whatsapp.com";
        if (name.equals("Telegram")) return "https://web.telegram.org/a/";
        if (name.equals("Viral Quality")) return SITE_URL;
        for (String line : prefs.getString("sites", "").split("\n")) {
            int i = line.indexOf('\t');
            if (i > 0 && line.substring(0, i).equals(name)) return line.substring(i + 1);
        }
        return null;
    }

    private void openBuiltin(String name) {
        if (name.equals("Files") || name.equals("Notepad") || name.equals("Calculator") || name.equals("System")
                || name.equals("Clipboard") || name.equals("Timer")) {
            openTool(name);
            return;
        }
        String u = siteUrl(name);
        if (u != null) openWeb(name, u);
    }

    private void toggleIcons() {
        boolean off = !iconViews.isEmpty() && iconViews.get(0).getVisibility() == View.VISIBLE;
        for (View v : iconViews) v.setVisibility(off ? View.GONE : View.VISIBLE);
        prefs.edit().putBoolean("iconsoff", off).apply();
    }

    private void toggleWidgets() {
        boolean off = !prefs.getBoolean("widgetsoff", false);
        prefs.edit().putBoolean("widgetsoff", off).apply();
        for (int i = 0; i < widgetViews.size(); i++) {
            widgetViews.get(i).setVisibility(widgetWanted(widgetIds.get(i)) ? View.VISIBLE : View.GONE);
        }
    }

    private void resetLayout() {
        SharedPreferences.Editor ed = prefs.edit();
        for (String k : new ArrayList<>(prefs.getAll().keySet())) {
            if (k.matches("^[iw]\\d+[xy]$")) ed.remove(k);
        }
        ed.apply();
        toast("Layout reset (windows will close)");
        recreate();
    }

    private void toggleClock() {
        boolean off = clockBlock.getVisibility() == View.VISIBLE;
        clockBlock.setVisibility(off ? View.GONE : View.VISIBLE);
        prefs.edit().putBoolean("clockoff", off).apply();
    }

    private void toggleSlide() {
        slideOn = !slideOn;
        lastSlide = 0;
        prefs.edit().putBoolean("slide", slideOn).apply();
        toast(slideOn ? "Wallpaper changes every 5 minutes" : "Slideshow off");
    }

    private void toggleAlign() {
        boolean left = !prefs.getBoolean("tbleft", false);
        prefs.edit().putBoolean("tbleft", left).apply();
        tbLead.setLayoutParams(new LinearLayout.LayoutParams(0, 1, left ? 0f : 1f));
    }

    private void toggleGlass() {
        boolean g = !prefs.getBoolean("glass", false);
        prefs.edit().putBoolean("glass", g).apply();
        taskbarView.setBackgroundColor(g ? 0x990A0A0E : 0xE60A0A0E);
    }

    private void nextAccent() {
        int i = (prefs.getInt("accent", 0) + 1) % ACCENTS.length;
        prefs.edit().putInt("accent", i).apply();
        toast("Accent color changed (windows will close)");
        recreate();
    }

    // ======================= EXTRAS (dock, quick settings, saver, folders, widgets) =======================

    private LinearLayout dock, qsPanel;
    private FrameLayout saver;
    private TextView saverClock, wcV, todoList;
    private boolean saverOn = false;
    private long lastInput = System.currentTimeMillis();
    private FrameLayout rootRef;
    private TextView qsWifi;
    private final List<View> folderViews = new ArrayList<>();
    private ImageView lockBgA, lockBgB;
    private boolean lockFront = true;
    private int lockIdx = 0;
    private final Random rnd = new Random();

    private final Tools.Opener fileOpener = new Tools.Opener() {
        public boolean open(File f) {
            String n = f.getName();
            if (Tools.isImage(n)) { openPhotos(f); return true; }
            if (Tools.isMedia(n)) { openPlayer(f); return true; }
            return false;
        }
    };

    private void closeWinByName(String name) {
        for (WebWindow w : new ArrayList<>(wins)) if (w.name.equals(name)) w.close();
    }

    private void openPhotos(File start) {
        hideStart();
        closeAllPanels();
        if (start == null) {
            if (showExisting("Photos")) return;
        } else {
            closeWinByName("Photos");
        }
        openWin("Photos", null, Tools.imageViewer(this, RED, start), false);
    }

    private void openPlayer(File f) {
        hideStart();
        closeAllPanels();
        closeWinByName("Player");
        openWin("Player", null, Tools.player(this, RED, f), false);
    }

    private void buildExtras(FrameLayout root, android.util.DisplayMetrics dm) {
        rootRef = root;
        // ---- dock ----
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(14), dp(8), dp(14), dp(8));
        dock.setBackground(glass(0x66101015, 0x55FFFFFF, 26, 1));
        final String[] dn = {"YouTube", "Google", "Files", "Photos", "Notepad", "Speed", "Quick"};
        final String[] dm2 = {"Y", "G", "F", "P", "N", "S", "Q"};
        final int[] dc = {0xFFE11D2E, 0xFF2F7BFF, 0xFFF59E0B, 0xFF19B36B, 0xFF8B5CF6, 0xFF0EA5A5, 0xFFEC4899};
        for (int i = 0; i < dn.length; i++) {
            final String nm = dn[i];
            TextView b = label(dm2[i], 20, Color.WHITE, true);
            b.setGravity(Gravity.CENTER);
            b.setBackground(glass(dc[i], 0x66FFFFFF, 14, 1));
            b.setFocusable(true);
            b.setClickable(true);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (nm.equals("Photos")) openPhotos(null);
                    else if (nm.equals("Speed")) openTool("Speed");
                    else if (nm.equals("Quick")) toggleQuick();
                    else openBuiltin(nm);
                }
            });
            b.setOnHoverListener(new View.OnHoverListener() {
                public boolean onHover(View v, MotionEvent e) {
                    int a = e.getActionMasked();
                    if (a == MotionEvent.ACTION_HOVER_ENTER) {
                        v.animate().scaleX(1.4f).scaleY(1.4f).translationY(-dp(10)).setDuration(120).start();
                    } else if (a == MotionEvent.ACTION_HOVER_EXIT) {
                        v.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(120).start();
                    }
                    return false;
                }
            });
            tip(b, nm.equals("Speed") ? "Speed test" : nm);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
            lp.leftMargin = dp(6);
            lp.rightMargin = dp(6);
            dock.addView(b, lp);
        }
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dl.bottomMargin = dp(70);
        root.addView(dock, dl);
        dock.setVisibility(prefs.getBoolean("dock", true) ? View.VISIBLE : View.GONE);

        // ---- quick settings ----
        qsPanel = new LinearLayout(this);
        qsPanel.setOrientation(LinearLayout.VERTICAL);
        qsPanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        qsPanel.setBackground(glass(0xF2101015, 0x55FFFFFF, 18, 1));
        TextView qt = label("QUICK SETTINGS", 11, RED, true);
        qt.setLetterSpacing(0.2f);
        qsPanel.addView(qt);
        LinearLayout row = null;
        final String[] qn = {"Vol -", "Vol +", "Mute", "Wi-Fi", "Lock", "Clean RAM", "Screenshot", "Settings",
                "Next wallpaper", "Update", "Speed test", "Photos"};
        for (int i = 0; i < qn.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
                rl.topMargin = dp(8);
                qsPanel.addView(row, rl);
            }
            final String nm = qn[i];
            TextView b = btn(nm, false);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            if (nm.equals("Wi-Fi")) qsWifi = b;
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { qsAction(nm); }
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            bl.leftMargin = dp(3);
            bl.rightMargin = dp(3);
            row.addView(b, bl);
        }
        FrameLayout.LayoutParams ql = new FrameLayout.LayoutParams(dp(520),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        ql.bottomMargin = dp(72);
        ql.rightMargin = dp(16);
        root.addView(qsPanel, ql);
        qsPanel.setVisibility(View.GONE);

        // ---- screen saver ----
        saver = new FrameLayout(this);
        saver.setBackgroundColor(0xFF000000);
        saver.setClickable(true);
        saverClock = label("", 54, 0xFFBBBBBB, false);
        saverClock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        saver.addView(saverClock, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(saver, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        saver.setVisibility(View.GONE);

        // ---- lock screen slideshow ----
        lockBgA = new ImageView(this);
        lockBgB = new ImageView(this);
        lockBgA.setScaleType(ImageView.ScaleType.CENTER_CROP);
        lockBgB.setScaleType(ImageView.ScaleType.CENTER_CROP);
        lockBgB.setAlpha(0f);
        lockView.addView(lockBgA, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lockView.addView(lockBgB, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View scrim = new View(this);
        scrim.setBackgroundColor(0x88000000);
        lockView.addView(scrim, 2, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildFolders();
    }

    private void toggleQuick() {
        if (qsPanel.getVisibility() == View.VISIBLE) {
            qsPanel.setVisibility(View.GONE);
            return;
        }
        closeAllPanels();
        hideStart();
        hideVol();
        refreshWifiBtn();
        qsPanel.bringToFront();
        qsPanel.setVisibility(View.VISIBLE);
    }

    private void refreshWifiBtn() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            qsWifi.setText(wm.isWifiEnabled() ? "Wi-Fi: ON" : "Wi-Fi: OFF");
        } catch (Throwable t) {
            qsWifi.setText("Wi-Fi");
        }
    }

    @SuppressWarnings("deprecation")
    private void qsAction(String nm) {
        if (nm.equals("Vol -")) { stepVol(-1); return; }
        if (nm.equals("Vol +")) { stepVol(1); return; }
        if (nm.equals("Mute")) { toggleMute(); return; }
        if (nm.equals("Wi-Fi")) {
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                boolean ok = wm.setWifiEnabled(!wm.isWifiEnabled());
                if (!ok) throw new RuntimeException("no");
                final TextView b = qsWifi;
                h.postDelayed(new Runnable() { public void run() { refreshWifiBtn(); } }, 1500);
                b.setText("Wi-Fi...");
            } catch (Throwable t) {
                toast("This box does not allow it. Opening Wi-Fi settings");
                openSettings(Settings.ACTION_WIFI_SETTINGS);
            }
            return;
        }
        qsPanel.setVisibility(View.GONE);
        if (nm.equals("Lock")) lockFromMenu();
        else if (nm.equals("Clean RAM")) cleanRam();
        else if (nm.equals("Screenshot")) h.postDelayed(new Runnable() { public void run() { screenshot(); } }, 250);
        else if (nm.equals("Settings")) openSettings(Settings.ACTION_SETTINGS);
        else if (nm.equals("Next wallpaper")) nextWall();
        else if (nm.equals("Update")) updateLauncher();
        else if (nm.equals("Speed test")) openTool("Speed");
        else if (nm.equals("Photos")) openPhotos(null);
    }

    private void toggleDock() {
        boolean on = dock.getVisibility() != View.VISIBLE;
        prefs.edit().putBoolean("dock", on).apply();
        dock.setVisibility(on ? View.VISIBLE : View.GONE);
        toast(on ? "Dock on" : "Dock off");
    }

    private void toggleBlur() {
        boolean on = !prefs.getBoolean("blur", true);
        prefs.edit().putBoolean("blur", on).apply();
        toast(on ? "Glass blur on" : "Glass blur off");
    }

    private void toggleSaver() {
        boolean on = !prefs.getBoolean("saver", true);
        prefs.edit().putBoolean("saver", on).apply();
        toast(on ? "Screen saver on (after 10 min idle)" : "Screen saver off");
    }

    // blurred wallpaper behind the START panel
    private static final class BlurDrawable extends Drawable {
        final Bitmap bmp;
        final float l, t, r, b;
        final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float rad;

        BlurDrawable(Bitmap bmp, float l, float t, float r, float b, float rad) {
            this.bmp = bmp;
            this.l = l;
            this.t = t;
            this.r = r;
            this.b = b;
            this.rad = rad;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(0x55FFFFFF);
            stroke.setStrokeWidth(2f);
        }

        @Override
        public void draw(Canvas c) {
            android.graphics.Rect bd = getBounds();
            RectF dst = new RectF(bd);
            Path pa = new Path();
            pa.addRoundRect(dst, rad, rad, Path.Direction.CW);
            int save = c.save();
            c.clipPath(pa);
            android.graphics.Rect src = new android.graphics.Rect(
                    (int) (l * bmp.getWidth()), (int) (t * bmp.getHeight()),
                    Math.max(1, (int) (r * bmp.getWidth())), Math.max(1, (int) (b * bmp.getHeight())));
            c.drawBitmap(bmp, src, bd, p);
            c.drawColor(0xCC0E0E14);
            c.restoreToCount(save);
            c.drawRoundRect(dst, rad, rad, stroke);
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private void applyBlur() {
        try {
            if (!prefs.getBoolean("blur", true) || wall.getWidth() == 0) {
                startPanel.setBackground(glass(0xF2101015, 0x55FFFFFF, 20, 1));
                return;
            }
            int W = wall.getWidth(), H = wall.getHeight();
            int sw = 48, sh = Math.max(8, 48 * H / W);
            Bitmap tiny = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(tiny);
            c.scale(sw / (float) W, sh / (float) H);
            wall.draw(c);
            shade.draw(c);
            int px = startPanel.getLeft(), py = startPanel.getTop();
            int pw = startPanel.getWidth(), ph = startPanel.getHeight();
            if (pw == 0) {
                pw = (int) (W * 0.82f);
                ph = (int) (H * 0.74f);
                px = dp(12);
                py = H - dp(72) - ph;
            }
            startPanel.setBackground(new BlurDrawable(tiny, px / (float) W, py / (float) H,
                    (px + pw) / (float) W, (py + ph) / (float) H, dp(20)));
        } catch (Throwable t) {
            startPanel.setBackground(glass(0xF2101015, 0x55FFFFFF, 20, 1));
        }
    }

    // ---- lock screen slideshow ----
    private final Runnable lockSlide = new Runnable() {
        public void run() {
            if (!locked) return;
            lockNextImage();
            h.postDelayed(this, 9000);
        }
    };

    private void lockNextImage() {
        Bitmap bm = null;
        try {
            List<File> pool = new ArrayList<>();
            if (customFile.exists()) pool.add(customFile);
            File pics = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            List<File> more = Tools.picturesIn(pics);
            for (int i = 0; i < more.size() && i < 12; i++) pool.add(more.get(i));
            if (!pool.isEmpty()) {
                lockIdx++;
                bm = Tools.decode(pool.get(lockIdx % pool.size()), 1280);
            }
        } catch (Throwable t) {
            bm = null;
        }
        ImageView in = lockFront ? lockBgB : lockBgA;
        ImageView out = lockFront ? lockBgA : lockBgB;
        if (bm != null) {
            in.setImageBitmap(bm);
        } else {
            lockIdx++;
            int[] cl = PRESETS[lockIdx % PRESETS.length];
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{cl[0], cl[1]});
            in.setImageDrawable(g);
        }
        in.animate().alpha(1f).setDuration(1500).start();
        out.animate().alpha(0f).setDuration(1500).start();
        lockFront = !lockFront;
    }

    private void startLockShow() {
        lockBgA.setAlpha(1f);
        lockBgB.setAlpha(0f);
        lockFront = true;
        lockBgA.setImageDrawable(null);
        lockNextImage();
        lockBgA.setAlpha(0f);
        h.removeCallbacks(lockSlide);
        h.postDelayed(lockSlide, 9000);
    }

    // ---- screen saver ----
    private void extrasTick() {
        if (dock != null && prefs.getBoolean("dock", true)) {
            boolean show = topWindow() == null && fsView == null && !locked;
            int vis = show ? View.VISIBLE : View.GONE;
            if (dock.getVisibility() != vis) dock.setVisibility(vis);
        }
        if (saver == null) return;
        long now = System.currentTimeMillis();
        if (saverOn) {
            if (now % 5000 < 300 || saverClock.getTag() == null) {
                saverClock.setTag("x");
                int W = saver.getWidth(), H = saver.getHeight();
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) saverClock.getLayoutParams();
                lp.leftMargin = rnd.nextInt(Math.max(1, W - dp(260)));
                lp.topMargin = rnd.nextInt(Math.max(1, H - dp(100)));
                saverClock.setLayoutParams(lp);
            }
            saverClock.setText(fTray.format(new Date()));
        } else if (prefs.getBoolean("saver", true) && now - lastInput > 600000L
                && topWindow() == null && fsView == null && !locked
                && startPanel.getVisibility() != View.VISIBLE) {
            saverOn = true;
            saverClock.setTag(null);
            saver.setVisibility(View.VISIBLE);
            saver.bringToFront();
        }
    }

    private boolean wakeSaver() {
        lastInput = System.currentTimeMillis();
        if (saverOn) {
            saverOn = false;
            saver.setVisibility(View.GONE);
            return true;
        }
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (wakeSaver()) return true;
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (wakeSaver()) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    // ---- extra widgets ----
    private void addCardSized(int idx, View card, int x, int y, int wdp, int hdp) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(wdp), dp(hdp));
        lp.leftMargin = prefs.getInt("w" + idx + "x", x);
        lp.topMargin = prefs.getInt("w" + idx + "y", y);
        desk.addView(card, lp);
        makeDraggable(card, "w" + idx, null);
        card.setVisibility(widgetWanted(idx) ? View.VISIBLE : View.GONE);
        widgetViews.add(card);
        widgetIds.add(idx);
    }

    private void buildExtraWidgets(android.util.DisplayMetrics dm) {
        int x = dm.widthPixels - dp(204);
        // world clock
        LinearLayout wc = cardBase("WORLD CLOCK");
        wcV = label("", 13, Color.WHITE, false);
        wc.addView(wcV);
        addCardSized(4, wc, x, dp(14), 190, 96);

        // youtube search
        LinearLayout yt = cardBase("YOUTUBE SEARCH");
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint("Search YouTube");
        et.setHintTextColor(0x88FFFFFF);
        et.setTextColor(Color.WHITE);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        et.setBackground(glass(0x33FFFFFF, 0x44FFFFFF, 8, 1));
        et.setPadding(dp(8), dp(4), dp(8), dp(4));
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int a, KeyEvent ev) {
                if (a == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && ev.getAction() == KeyEvent.ACTION_DOWN)) {
                    String q = et.getText().toString().trim();
                    if (q.length() > 0) {
                        ytSearch(q);
                        et.setText("");
                    }
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        el.topMargin = dp(8);
        yt.addView(et, el);
        addCardSized(5, yt, x, dp(120), 190, 90);

        // to-do
        LinearLayout td = cardBase("TO-DO  (tap an item to finish it)");
        todoList = label("", 13, Color.WHITE, false);
        td.addView(todoList);
        final EditText te = new EditText(this);
        te.setSingleLine(true);
        te.setHint("+ add task");
        te.setHintTextColor(0x88FFFFFF);
        te.setTextColor(Color.WHITE);
        te.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        te.setImeOptions(EditorInfo.IME_ACTION_DONE);
        te.setBackground(glass(0x33FFFFFF, 0x44FFFFFF, 8, 1));
        te.setPadding(dp(8), dp(2), dp(8), dp(2));
        te.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int a, KeyEvent ev) {
                String q = te.getText().toString().trim().replace("\n", " ");
                if (q.length() > 0) {
                    prefs.edit().putString("todo", prefs.getString("todo", "") + q + "\n").apply();
                    te.setText("");
                    refreshTodo();
                }
                return true;
            }
        });
        td.addView(te, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        addCardSized(6, td, x, dp(216), 190, 170);
        refreshTodo();
    }

    private void refreshTodo() {
        if (todoList == null) return;
        final String[] items = prefs.getString("todo", "").split("\n");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String s : items) {
            if (s.trim().length() == 0) continue;
            if (n < 5) sb.append("• ").append(s).append("\n");
            n++;
        }
        if (n == 0) sb.append("Nothing to do");
        todoList.setText(sb.toString().trim());
        todoList.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // finish the first item
                String all = prefs.getString("todo", "");
                int i = all.indexOf('\n');
                if (i >= 0) prefs.edit().putString("todo", all.substring(i + 1)).apply();
                refreshTodo();
            }
        });
    }

    private void ytSearch(String q) {
        String url = "https://m.youtube.com/results?search_query=" + Uri.encode(q);
        for (WebWindow w : wins) {
            if (w.name.equals("YouTube")) {
                w.load(url);
                w.show();
                return;
            }
        }
        openWin("YouTube", url, null, false);
    }

    private void updateExtraWidgets(Date d) {
        if (wcV == null) return;
        String[][] z = {{"Riyadh", "Asia/Riyadh"}, {"London", "Europe/London"}, {"New York", "America/New_York"}};
        StringBuilder sb = new StringBuilder();
        for (String[] a : z) {
            SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
            f.setTimeZone(java.util.TimeZone.getTimeZone(a[1]));
            sb.append(a[0]).append("   ").append(f.format(d)).append("\n");
        }
        wcV.setText(sb.toString().trim());
    }

    // ---- desktop folders ----
    private List<String[]> readFolders() {
        List<String[]> out = new ArrayList<>();
        for (String line : prefs.getString("folders", "").split("\n")) {
            if (line.trim().length() == 0) continue;
            out.add(line.split("\t"));
        }
        return out;
    }

    private void saveFolders(List<String[]> fs) {
        StringBuilder sb = new StringBuilder();
        for (String[] f : fs) {
            for (int i = 0; i < f.length; i++) sb.append(i == 0 ? "" : "\t").append(f[i]);
            sb.append("\n");
        }
        prefs.edit().putString("folders", sb.toString()).apply();
        buildFolders();
    }

    private void buildFolders() {
        for (View v : folderViews) {
            desk.removeView(v);
            iconViews.remove(v);
        }
        folderViews.clear();
        boolean off = prefs.getBoolean("iconsoff", true);
        final List<String[]> fs = readFolders();
        for (int i = 0; i < fs.size(); i++) {
            final int fi = i;
            View v = deskIcon(fs.get(i)[0], "▤", 0xFFB8860B);
            v.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) { openFolder(fi); }
            });
            int k = 20 + i;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(88), dp(92));
            lp.leftMargin = prefs.getInt("i" + k + "x", dp(14) + dp(92) * 3);
            lp.topMargin = prefs.getInt("i" + k + "y", dp(14) + dp(98) * i);
            desk.addView(v, lp);
            makeDraggable(v, "i" + k, null);
            v.setVisibility(off ? View.GONE : View.VISIBLE);
            iconViews.add(v);
            folderViews.add(v);
        }
    }

    private void newFolderDialog() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint("Folder name");
        new AlertDialog.Builder(this)
                .setTitle("New desktop folder")
                .setView(et)
                .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = et.getText().toString().trim().replace("\t", " ").replace("\n", " ");
                        if (n.length() == 0) return;
                        List<String[]> fs = readFolders();
                        fs.add(new String[]{n});
                        saveFolders(fs);
                        if (prefs.getBoolean("iconsoff", true)) toggleIcons();
                        openFolderAdd(fs.size() - 1);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String itemLabel(String it) {
        if (it.startsWith("A:")) {
            AppItem a = findApp(it.substring(2));
            return a != null ? a.label : it.substring(2);
        }
        return it;
    }

    private void openFolder(final int fi) {
        List<String[]> fs = readFolders();
        if (fi >= fs.size()) return;
        final String[] f = fs.get(fi);
        final List<CharSequence> shown = new ArrayList<>();
        for (int i = 1; i < f.length; i++) shown.add(itemLabel(f[i]));
        shown.add("+ Add / remove items");
        shown.add("Delete this folder");
        new AlertDialog.Builder(this)
                .setTitle(f[0])
                .setItems(shown.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        int n = f.length - 1;
                        if (which < n) {
                            String it = f[which + 1];
                            if (it.startsWith("A:")) {
                                AppItem a = findApp(it.substring(2));
                                if (a != null) launch(a);
                            } else {
                                openBuiltin(it);
                            }
                        } else if (which == n) {
                            openFolderAdd(fi);
                        } else {
                            List<String[]> all = readFolders();
                            if (fi < all.size()) all.remove(fi);
                            saveFolders(all);
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void openFolderAdd(final int fi) {
        List<String[]> fs = readFolders();
        if (fi >= fs.size()) return;
        final String[] f = fs.get(fi);
        final List<String> ids = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        String[] builtin = {"YouTube", "Google", "Facebook", "Browser", "Files", "Notepad", "Calculator", "System", "Clipboard", "Timer", "TikTok", "WhatsApp", "Telegram"};
        for (String b : builtin) { ids.add(b); names.add(b); }
        for (AppItem a : apps) {
            if (a.label == null) continue;
            ids.add("A:" + a.pkg);
            names.add(a.label);
        }
        final boolean[] on = new boolean[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            for (int j = 1; j < f.length; j++) if (f[j].equals(ids.get(i))) on[i] = true;
        }
        new AlertDialog.Builder(this)
                .setTitle("Items in \"" + f[0] + "\"")
                .setMultiChoiceItems(names.toArray(new CharSequence[0]), on,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            public void onClick(DialogInterface d, int which, boolean checked) {
                                on[which] = checked;
                            }
                        })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        List<String> nl = new ArrayList<>();
                        nl.add(f[0]);
                        for (int i = 0; i < on.length; i++) if (on[i]) nl.add(ids.get(i));
                        List<String[]> all = readFolders();
                        if (fi < all.size()) all.set(fi, nl.toArray(new String[0]));
                        saveFolders(all);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ======================= EXTRAS 2 =======================

    private TextView musicTitle, musicBtn, prayerV, newsTag, newsV;
    private android.media.MediaPlayer mp;
    private final List<File> songs = new ArrayList<>();
    private int songIdx = -1;
    private long lastEvCheck = 0, lastNewsFetch = 0, lastNewsShow = 0, lastPrayerCheck = 0;
    private final List<String[]> newsItems = new ArrayList<>();
    private int newsIdx = 0;
    private boolean newsFetching = false, prayerFetching = false;
    private String prayerDay = "", prayerKey = "", prayerFired = "";
    private final String[] pNames = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private final String[] pRaw = new String[5];
    private boolean kbMode = false, kbDown = false;
    private float kbX, kbY;
    private long kbDownTime;
    private View kbCursor;

    private void beep() {
        try {
            android.media.Ringtone r = android.media.RingtoneManager.getRingtone(this,
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION));
            if (r != null) r.play();
        } catch (Throwable t) {
            // ignore
        }
    }

    // ---- window tiling / split ----
    private void retile() {
        List<WebWindow> ts = new ArrayList<>();
        for (WebWindow w : wins) if (!w.small && !w.minimized && !w.maximized) ts.add(w);
        if (ts.isEmpty()) return;
        Collections.sort(ts, new java.util.Comparator<WebWindow>() {
            public int compare(WebWindow x, WebWindow y) { return x.slot - y.slot; }
        });
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int dw = desk.getWidth() > 0 ? desk.getWidth() : dm.widthPixels;
        int dh = desk.getHeight() > 0 ? desk.getHeight() : dm.heightPixels - dp(64);
        int pad = dp(10);
        int hgt = dh - pad * 2;
        int n = ts.size();
        float r = WebWindow.ratio;
        if (n == 1) {
            WebWindow w = ts.get(0);
            int wd = (int) ((dw - pad * 3) * (w.slot == 0 ? r : 1f - r));
            w.place(w.slot == 0 ? pad : dw - pad - wd, pad, wd, hgt);
        } else if (n == 2) {
            int wl = (int) ((dw - pad * 3) * r);
            int wr = dw - pad * 3 - wl;
            ts.get(0).place(pad, pad, wl, hgt);
            ts.get(1).place(pad * 2 + wl, pad, wr, hgt);
        } else {
            int wd = (dw - pad * 4) / 3;
            for (int i = 0; i < n; i++) ts.get(i).place(pad + i * (wd + pad), pad, wd, hgt);
        }
    }

    private void cycleSplit() {
        float r = WebWindow.ratio;
        WebWindow.ratio = r < 0.4f ? 0.5f : (r < 0.6f ? 0.7f : 0.3f);
        prefs.edit().putFloat("split", WebWindow.ratio).apply();
        toast("Split: " + Math.round(WebWindow.ratio * 100) + " / " + Math.round(100 - WebWindow.ratio * 100));
        retile();
    }

    // ---- file search inside the search box ----
    private int searchTok = 0;

    private void searchFiles(final String ql) {
        final int tok = ++searchTok;
        if (ql.length() < 2) return;
        new Thread(new Runnable() {
            public void run() {
                final List<File> out = new ArrayList<>();
                java.util.ArrayDeque<File> dq = new java.util.ArrayDeque<>();
                dq.add(Environment.getExternalStorageDirectory());
                int seen = 0;
                while (!dq.isEmpty() && out.size() < 6 && seen < 8000 && tok == searchTok) {
                    File d = dq.poll();
                    File[] fs = d.listFiles();
                    if (fs == null) continue;
                    for (File f : fs) {
                        seen++;
                        String n = f.getName();
                        if (n.startsWith(".")) continue;
                        if (n.toLowerCase(Locale.ENGLISH).contains(ql)) {
                            out.add(f);
                            if (out.size() >= 6) break;
                        }
                        if (f.isDirectory() && !n.equals("Android")) dq.add(f);
                    }
                }
                if (out.isEmpty()) return;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (tok != searchTok || searchPanel.getVisibility() != View.VISIBLE) return;
                        int at = Math.max(0, searchResults.getChildCount() - 1);
                        for (final File f : out) {
                            TextView t = btn((f.isDirectory() ? "[Folder] " : "[File] ") + f.getName()
                                    + "    " + f.getParentFile().getName(), false);
                            t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                            t.setSingleLine(true);
                            t.setOnClickListener(new View.OnClickListener() {
                                public void onClick(View v) {
                                    hideSearch();
                                    if (f.isDirectory()) openTool("Files");
                                    else Tools.openFile(MainActivity.this, f, fileOpener);
                                }
                            });
                            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
                            p.setMargins(0, 0, 0, dp(5));
                            searchResults.addView(t, Math.min(at++, searchResults.getChildCount()), p);
                        }
                    }
                });
            }
        }).start();
    }

    // ---- calendar events / reminders ----
    private String dayKey(int y, int m, int d) {
        return String.format(Locale.ENGLISH, "%04d-%02d-%02d", y, m + 1, d);
    }

    private boolean hasEvents(int y, int m, int d) {
        String k = dayKey(y, m, d) + " ";
        for (String line : prefs.getString("events", "").split("\n")) {
            if (line.startsWith(k)) return true;
        }
        return false;
    }

    private void dayDialog(final int y, final int m, final int d) {
        final String key = dayKey(y, m, d);
        final List<String> lines = new ArrayList<>();
        final List<CharSequence> shown = new ArrayList<>();
        for (String line : prefs.getString("events", "").split("\n")) {
            if (!line.startsWith(key + " ")) continue;
            String[] p = line.split("\t", -1);
            if (p.length < 2) continue;
            lines.add(line);
            shown.add(p[0].substring(11) + "   " + p[1] + "   (tap to delete)");
        }
        shown.add("+ Add reminder");
        new AlertDialog.Builder(this)
                .setTitle(key)
                .setItems(shown.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int which) {
                        if (which == lines.size()) {
                            addEventDialog(key);
                        } else {
                            String all = prefs.getString("events", "");
                            prefs.edit().putString("events", all.replace(lines.get(which) + "\n", "")).apply();
                            toast("Deleted");
                            renderCal();
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void addEventDialog(final String key) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        final EditText tm = new EditText(this);
        tm.setSingleLine(true);
        tm.setText("09:00");
        tm.setHint("Time (24h) like 14:30");
        final EditText tx = new EditText(this);
        tx.setSingleLine(true);
        tx.setHint("What to remember");
        box.addView(tm);
        box.addView(tx);
        new AlertDialog.Builder(this)
                .setTitle("Reminder on " + key)
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int w) {
                        String t = tm.getText().toString().trim();
                        String x = tx.getText().toString().trim().replace("\t", " ").replace("\n", " ");
                        if (!t.matches("^\\d{1,2}:\\d{2}$") || x.length() == 0) {
                            toast("Time must look like 14:30 and text can't be empty");
                            return;
                        }
                        String[] hm = t.split(":");
                        int hh = Integer.parseInt(hm[0]), mm = Integer.parseInt(hm[1]);
                        if (hh > 23 || mm > 59) {
                            toast("Invalid time");
                            return;
                        }
                        String line = key + String.format(Locale.ENGLISH, " %02d:%02d", hh, mm) + "\t" + x + "\t0\n";
                        prefs.edit().putString("events", prefs.getString("events", "") + line).apply();
                        toast("Reminder saved");
                        renderCal();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void checkEvents() {
        String all = prefs.getString("events", "");
        if (all.length() == 0) return;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH);
        f.setTimeZone(tz);
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        for (String line : all.split("\n")) {
            if (line.trim().length() == 0) continue;
            String[] p = line.split("\t", -1);
            if (p.length == 3) {
                try {
                    long t = f.parse(p[0]).getTime();
                    if (p[2].equals("0") && t <= now) {
                        if (now - t < 6 * 3600000L) fireReminder(p[1]);
                        line = p[0] + "\t" + p[1] + "\t1";
                        changed = true;
                    } else if (p[2].equals("1") && now - t > 7 * 86400000L) {
                        changed = true;
                        continue;
                    }
                } catch (Exception e) {
                    // keep line
                }
            }
            sb.append(line).append("\n");
        }
        if (changed) prefs.edit().putString("events", sb.toString()).apply();
    }

    private void fireReminder(String text) {
        wakeSaver();
        beep();
        try {
            new AlertDialog.Builder(this).setTitle("Reminder").setMessage(text)
                    .setPositiveButton("OK", null).show();
        } catch (Throwable t) {
            toast("Reminder: " + text);
        }
    }

    // ---- music widget ----
    private void loadSongs() {
        songs.clear();
        java.util.ArrayDeque<File> dq = new java.util.ArrayDeque<>();
        dq.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC));
        int depth = 0;
        while (!dq.isEmpty() && songs.size() < 300 && depth++ < 200) {
            File d = dq.poll();
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName().toLowerCase(Locale.ENGLISH);
                if (f.isDirectory()) dq.add(f);
                else if (n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".wav") || n.endsWith(".ogg")
                        || n.endsWith(".aac") || n.endsWith(".flac")) songs.add(f);
            }
        }
        Collections.sort(songs, new java.util.Comparator<File>() {
            public int compare(File x, File y) { return x.getName().compareToIgnoreCase(y.getName()); }
        });
    }

    private void musicPlay(int i) {
        if (songs.isEmpty()) loadSongs();
        if (songs.isEmpty()) {
            toast("No songs found in the Music folder");
            return;
        }
        songIdx = ((i % songs.size()) + songs.size()) % songs.size();
        try {
            if (mp != null) mp.release();
            mp = new android.media.MediaPlayer();
            mp.setDataSource(songs.get(songIdx).getPath());
            mp.prepare();
            mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                public void onCompletion(android.media.MediaPlayer x) { musicPlay(songIdx + 1); }
            });
            mp.start();
            String n = songs.get(songIdx).getName();
            int dot = n.lastIndexOf('.');
            musicTitle.setText(dot > 0 ? n.substring(0, dot) : n);
            musicBtn.setText("Pause");
        } catch (Throwable t) {
            toast("Cannot play this song");
            mp = null;
        }
    }

    private void musicToggle() {
        if (mp == null) {
            musicPlay(songIdx < 0 ? 0 : songIdx);
        } else if (mp.isPlaying()) {
            mp.pause();
            musicBtn.setText("Play");
        } else {
            mp.start();
            musicBtn.setText("Pause");
        }
    }

    private void musicPick() {
        if (songs.isEmpty()) loadSongs();
        if (songs.isEmpty()) {
            toast("No songs found in the Music folder");
            return;
        }
        final CharSequence[] names = new CharSequence[songs.size()];
        for (int i = 0; i < names.length; i++) names[i] = songs.get(i).getName();
        new AlertDialog.Builder(this).setTitle("Music")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { musicPlay(which); }
                }).show();
    }

    private void buildExtraWidgets2(android.util.DisplayMetrics dm) {
        int x = dm.widthPixels - dp(204) * 2;
        // music
        LinearLayout mu = cardBase("MUSIC");
        musicTitle = label("Tap to choose a song", 13, Color.WHITE, false);
        musicTitle.setSingleLine(true);
        musicTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        musicTitle.setClickable(true);
        musicTitle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { musicPick(); }
        });
        mu.addView(musicTitle);
        LinearLayout mr = new LinearLayout(this);
        mr.setOrientation(LinearLayout.HORIZONTAL);
        TextView pv = btn("<", false), nx = btn(">", false);
        musicBtn = btn("Play", true);
        pv.setGravity(Gravity.CENTER);
        nx.setGravity(Gravity.CENTER);
        musicBtn.setGravity(Gravity.CENTER);
        pv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { musicPlay(songIdx - 1); }
        });
        nx.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { musicPlay(songIdx + 1); }
        });
        musicBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { musicToggle(); }
        });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(dp(40), dp(34));
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, dp(34), 1f);
        b2.leftMargin = dp(6);
        b2.rightMargin = dp(6);
        mr.addView(pv, b1);
        mr.addView(musicBtn, b2);
        mr.addView(nx, new LinearLayout.LayoutParams(dp(40), dp(34)));
        LinearLayout.LayoutParams mrl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mrl.topMargin = dp(8);
        mu.addView(mr, mrl);
        addCardSized(7, mu, x, dp(14), 190, 96);

        // prayer times
        LinearLayout pr = cardBase("PRAYER TIME");
        prayerV = label("Loading...", 15, Color.WHITE, true);
        pr.addView(prayerV);
        pr.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 5; i++) {
                    if (pRaw[i] != null) sb.append(pNames[i]).append(" ").append(pRaw[i]).append("   ");
                }
                toast(sb.length() == 0 ? "Prayer times not loaded yet" : sb.toString());
            }
        });
        addCardSized(8, pr, x, dp(120), 190, 96);

        // news / cricket
        LinearLayout nw = cardBase("NEWS");
        newsTag = (TextView) nw.getChildAt(0);
        newsV = label("Loading...", 12, Color.WHITE, false);
        newsV.setMaxLines(4);
        newsV.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nw.addView(newsV);
        nw.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                newsIdx++;
                lastNewsShow = 0;
                if (newsItems.isEmpty()) fetchNews();
            }
        });
        addCardSized(9, nw, x, dp(226), 190, 118);
        fetchNews();
        fetchPrayer();
    }

    private static String xmlClean(String s) {
        s = s.replace("<![CDATA[", "").replace("]]>", "");
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").trim();
    }

    private List<String> rssTitles(String xml, int max) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<item>.*?<title>(.*?)</title>", java.util.regex.Pattern.DOTALL).matcher(xml);
        while (m.find() && out.size() < max) out.add(xmlClean(m.group(1)));
        return out;
    }

    private void fetchNews() {
        if (newsFetching) return;
        newsFetching = true;
        lastNewsFetch = System.currentTimeMillis();
        new Thread(new Runnable() {
            public void run() {
                final List<String[]> out = new ArrayList<>();
                try {
                    for (String t : rssTitles(http("https://static.espncricinfo.com/rss/livescores.xml"), 4)) {
                        out.add(new String[]{"CRICKET", t});
                    }
                } catch (Throwable e) {
                    // no cricket feed
                }
                try {
                    boolean bn = prefs.getBoolean("newsbn", true);
                    String u = bn ? "https://news.google.com/rss?hl=bn&gl=BD&ceid=BD:bn"
                            : "https://news.google.com/rss?hl=en-BD&gl=BD&ceid=BD:en";
                    for (String t : rssTitles(http(u), 10)) out.add(new String[]{"NEWS", t});
                } catch (Throwable e) {
                    // no news
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        newsFetching = false;
                        if (!out.isEmpty()) {
                            newsItems.clear();
                            newsItems.addAll(out);
                        }
                        lastNewsShow = 0;
                    }
                });
            }
        }).start();
    }

    private void fetchPrayer() {
        if (prayerFetching) return;
        prayerFetching = true;
        final String today = dayKey2();
        final String la = prefs.getString("lat", "23.8103"), lo = prefs.getString("lon", "90.4125");
        new Thread(new Runnable() {
            public void run() {
                String[] got = null;
                try {
                    String j = http("https://api.aladhan.com/v1/timings?latitude=" + la + "&longitude=" + lo + "&method=1");
                    JSONObject t = new JSONObject(j).getJSONObject("data").getJSONObject("timings");
                    got = new String[5];
                    for (int i = 0; i < 5; i++) got[i] = t.getString(pNames[i]).substring(0, 5);
                } catch (Throwable e) {
                    got = null;
                }
                final String[] fg = got;
                runOnUiThread(new Runnable() {
                    public void run() {
                        prayerFetching = false;
                        if (fg != null) {
                            System.arraycopy(fg, 0, pRaw, 0, 5);
                            prayerDay = today;
                            prayerKey = la;
                        }
                    }
                });
            }
        }).start();
    }

    private String dayKey2() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
        f.setTimeZone(tz);
        return f.format(new Date());
    }

    private long prayerMillis(String hhmm) {
        Calendar c = Calendar.getInstance(tz);
        String[] p = hhmm.split(":");
        c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0]));
        c.set(Calendar.MINUTE, Integer.parseInt(p[1]));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void extras2Tick() {
        long now = System.currentTimeMillis();
        if (now - lastEvCheck > 5000) {
            lastEvCheck = now;
            try {
                checkEvents();
            } catch (Throwable t) {
                // ignore
            }
            if (!prayerDay.equals(dayKey2()) || !prayerKey.equals(prefs.getString("lat", "23.8103"))) {
                if (now - lastPrayerCheck > 60000) {
                    lastPrayerCheck = now;
                    fetchPrayer();
                }
            }
            if (prayerV != null && prayerDay.length() > 0) {
                try {
                    int nxt = -1;
                    long nt = 0;
                    for (int i = 0; i < 5; i++) {
                        long t = prayerMillis(pRaw[i]);
                        if (now >= t && now - t < 90000 && prefs.getBoolean("azan", true)
                                && !prayerFired.equals(prayerDay + pNames[i])) {
                            prayerFired = prayerDay + pNames[i];
                            beep();
                            toast("Time for " + pNames[i] + " prayer");
                        }
                        if (nxt < 0 && t > now) {
                            nxt = i;
                            nt = t;
                        }
                    }
                    if (nxt < 0) {
                        prayerV.setText("Fajr  " + pRaw[0] + "\ntomorrow");
                    } else {
                        long mins = (nt - now) / 60000;
                        prayerV.setText(pNames[nxt] + "  " + pRaw[nxt] + "\nin " + (mins / 60) + "h " + (mins % 60) + "m");
                    }
                } catch (Throwable t) {
                    prayerV.setText("--");
                }
            } else if (prayerV != null) {
                prayerV.setText("Loading...");
            }
            if (now - lastNewsFetch > 600000L) fetchNews();
        }
        if (newsV != null && !newsItems.isEmpty() && now - lastNewsShow > 8000) {
            lastNewsShow = now;
            String[] it = newsItems.get(newsIdx++ % newsItems.size());
            newsTag.setText(it[0]);
            newsV.setText(it[1]);
        }
    }

    // ---- keyboard mouse mode ----
    private final class KbCursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        KbCursorView(Context c) {
            super(c);
            fill.setColor(Color.WHITE);
            line.setColor(Color.BLACK);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(2));
            float s = dp(22);
            path.moveTo(0, 0);
            path.lineTo(0, s);
            path.lineTo(s * 0.28f, s * 0.74f);
            path.lineTo(s * 0.5f, s * 1.12f);
            path.lineTo(s * 0.64f, s * 1.05f);
            path.lineTo(s * 0.42f, s * 0.68f);
            path.lineTo(s * 0.78f, s * 0.68f);
            path.close();
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawPath(path, fill);
            c.drawPath(path, line);
        }
    }

    private void toggleKb() {
        kbMode = !kbMode;
        kbDown = false;
        if (kbMode) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            kbX = dm.widthPixels / 2f;
            kbY = dm.heightPixels / 2f;
            moveKb(0, 0);
            kbCursor.setVisibility(View.VISIBLE);
            kbCursor.bringToFront();
            toast("Keyboard mouse ON: arrows move, Enter/Space click, R right-click, PgUp/PgDn scroll, Ctrl fast, Shift slow. Alt+K or Esc = off");
        } else {
            kbCursor.setVisibility(View.GONE);
            toast("Keyboard mouse off");
        }
    }

    private void moveKb(float dx, float dy) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        kbX = Math.max(0, Math.min(dm.widthPixels - 2, kbX + dx));
        kbY = Math.max(0, Math.min(dm.heightPixels - 2, kbY + dy));
        kbCursor.setTranslationX(kbX);
        kbCursor.setTranslationY(kbY);
    }

    private void kbInject(int action) {
        long t = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) kbDownTime = t;
        MotionEvent ev = MotionEvent.obtain(kbDownTime, t, action, kbX, kbY, 0);
        dispatchTouchEvent(ev);
        ev.recycle();
    }

    private MotionEvent kbMouseEvent(int action, int buttons, float vscroll) {
        long t = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = kbX;
        pc.y = kbY;
        pc.pressure = 1f;
        pc.size = 1f;
        if (vscroll != 0f) pc.setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        return MotionEvent.obtain(t, t, action, 1, new MotionEvent.PointerProperties[]{pp},
                new MotionEvent.PointerCoords[]{pc}, 0, buttons, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_MOUSE, 0);
    }

    private boolean kbKey(KeyEvent e) {
        if (!kbMode || locked || e.isAltPressed() || e.isMetaPressed()) return false;
        int kc = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        float step = e.isCtrlPressed() ? dp(90) : (e.isShiftPressed() ? dp(6) : dp(28));
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    float dx = kc == KeyEvent.KEYCODE_DPAD_LEFT ? -step : (kc == KeyEvent.KEYCODE_DPAD_RIGHT ? step : 0);
                    float dy = kc == KeyEvent.KEYCODE_DPAD_UP ? -step : (kc == KeyEvent.KEYCODE_DPAD_DOWN ? step : 0);
                    moveKb(dx, dy);
                    if (kbDown) kbInject(MotionEvent.ACTION_MOVE);
                }
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_SPACE:
                if (down && e.getRepeatCount() == 0) {
                    kbDown = true;
                    kbInject(MotionEvent.ACTION_DOWN);
                } else if (!down && kbDown) {
                    kbDown = false;
                    kbInject(MotionEvent.ACTION_UP);
                }
                return true;
            case KeyEvent.KEYCODE_R:
                if (down && e.getRepeatCount() == 0) {
                    MotionEvent dn = kbMouseEvent(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_SECONDARY, 0f);
                    dispatchTouchEvent(dn);
                    dn.recycle();
                    MotionEvent up = kbMouseEvent(MotionEvent.ACTION_UP, 0, 0f);
                    dispatchTouchEvent(up);
                    up.recycle();
                }
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (down) {
                    MotionEvent sc = kbMouseEvent(MotionEvent.ACTION_SCROLL, 0, kc == KeyEvent.KEYCODE_PAGE_UP ? 2f : -2f);
                    dispatchGenericMotionEvent(sc);
                    sc.recycle();
                }
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                if (down) toggleKb();
                return false;
            default:
                return false;
        }
    }

    private void buildExtras2(FrameLayout root) {
        kbCursor = new KbCursorView(this);
        root.addView(kbCursor, new FrameLayout.LayoutParams(dp(30), dp(34)));
        kbCursor.setVisibility(View.GONE);
        WebWindow.ratio = prefs.getFloat("split", 0.5f);
    }

    // ---- settings backup ----
    private void copyFile(File a, File b) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(a);
        FileOutputStream out = new FileOutputStream(b);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    private File backupDir() {
        File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        d.mkdirs();
        return d;
    }

    private void backupSettings() {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, ?> en : prefs.getAll().entrySet()) {
                Object v = en.getValue();
                JSONObject e = new JSONObject();
                String t = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l"
                        : v instanceof Float ? "f" : v instanceof String ? "s" : null;
                if (t == null) continue;
                e.put("t", t);
                e.put("v", v);
                o.put(en.getKey(), e);
            }
            File dir = backupDir();
            FileOutputStream fo = new FileOutputStream(new File(dir, "VQ-backup.json"));
            fo.write(o.toString().getBytes("UTF-8"));
            fo.close();
            if (customFile.exists()) copyFile(customFile, new File(dir, "VQ-wallpaper.jpg"));
            if (profileFile.exists()) copyFile(profileFile, new File(dir, "VQ-profile.jpg"));
            toast("Backup saved in Download folder (VQ-backup.json)");
        } catch (Throwable t) {
            toast("Backup failed. Allow storage permission (open Files once)");
        }
    }

    private void restoreSettings() {
        final File dir = backupDir();
        final File f = new File(dir, "VQ-backup.json");
        if (!f.exists()) {
            toast("No VQ-backup.json found in the Download folder");
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("Restore settings from the backup file? The launcher will restart.")
                .setPositiveButton("Restore", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = r.readLine()) != null) sb.append(line);
                            r.close();
                            JSONObject o = new JSONObject(sb.toString());
                            SharedPreferences.Editor ed = prefs.edit();
                            java.util.Iterator<String> it = o.keys();
                            while (it.hasNext()) {
                                String k = it.next();
                                JSONObject e = o.getJSONObject(k);
                                String t = e.getString("t");
                                if (t.equals("b")) ed.putBoolean(k, e.getBoolean("v"));
                                else if (t.equals("i")) ed.putInt(k, e.getInt("v"));
                                else if (t.equals("l")) ed.putLong(k, e.getLong("v"));
                                else if (t.equals("f")) ed.putFloat(k, (float) e.getDouble("v"));
                                else ed.putString(k, e.getString("v"));
                            }
                            ed.apply();
                            File bw = new File(dir, "VQ-wallpaper.jpg");
                            if (bw.exists()) copyFile(bw, customFile);
                            File bp = new File(dir, "VQ-profile.jpg");
                            if (bp.exists()) copyFile(bp, profileFile);
                            toast("Restored");
                            recreate();
                        } catch (Throwable t) {
                            toast("Restore failed");
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toggleAzan() {
        boolean on = !prefs.getBoolean("azan", true);
        prefs.edit().putBoolean("azan", on).apply();
        toast(on ? "Prayer alerts on" : "Prayer alerts off");
    }

    private void toggleNewsLang() {
        boolean bn = !prefs.getBoolean("newsbn", true);
        prefs.edit().putBoolean("newsbn", bn).apply();
        toast(bn ? "News in Bengali" : "News in English");
        newsItems.clear();
        fetchNews();
    }

    private void cleanRam() {
        toast("Cleaning RAM...");
        Tools.cleanRam(this, new Tools.Done() {
            public void done(long mb) { toast("Freed about " + mb + " MB"); }
        });
    }

    private void closeAllPanels() {
        if (ctxLayer != null) hideCtx();
        if (searchPanel != null) hideSearch();
        if (calPanel != null) calPanel.setVisibility(View.GONE);
        if (qsPanel != null) qsPanel.setVisibility(View.GONE);
    }

    // ----- panels: context menu, search, calendar, lock

    private void buildPanels(FrameLayout root) {
        // right-click menu
        ctxLayer = new FrameLayout(this);
        ctxLayer.setClickable(true);
        ctxLayer.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { hideCtx(); }
        });
        ctxMenu = new LinearLayout(this);
        ctxMenu.setOrientation(LinearLayout.VERTICAL);
        ctxMenu.setPadding(dp(8), dp(8), dp(8), dp(4));
        ctxMenu.setBackground(panelBg());
        ctxLayer.addView(ctxMenu, new FrameLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(ctxLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ctxLayer.setVisibility(View.GONE);

        // search
        searchPanel = new LinearLayout(this);
        searchPanel.setOrientation(LinearLayout.VERTICAL);
        searchPanel.setPadding(dp(14), dp(14), dp(14), dp(10));
        searchPanel.setBackground(panelBg());
        searchEt = new EditText(this);
        searchEt.setSingleLine(true);
        searchEt.setTextColor(Color.WHITE);
        searchEt.setHintTextColor(0x88FFFFFF);
        searchEt.setHint("Search apps or the web");
        searchEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        searchEt.setPadding(dp(14), 0, dp(14), 0);
        searchEt.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchEt.setBackground(glass(0xFF26262E, 0x55FFFFFF, 12, 1));
        searchEt.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { refreshSearch(s.toString()); }
        });
        searchEt.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                if (searchResults.getChildCount() > 0) searchResults.getChildAt(0).performClick();
                return true;
            }
        });
        searchPanel.addView(searchEt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        searchResults = new LinearLayout(this);
        searchResults.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srl.topMargin = dp(8);
        searchPanel.addView(searchResults, srl);
        FrameLayout.LayoutParams spl = new FrameLayout.LayoutParams(
                dp(560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        spl.topMargin = dp(60);
        root.addView(searchPanel, spl);
        searchPanel.setVisibility(View.GONE);

        // calendar
        calPanel = new LinearLayout(this);
        calPanel.setOrientation(LinearLayout.VERTICAL);
        calPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
        calPanel.setBackground(panelBg());
        LinearLayout hd = new LinearLayout(this);
        hd.setOrientation(LinearLayout.HORIZONTAL);
        hd.setGravity(Gravity.CENTER_VERTICAL);
        TextView prev = btn("\u2039", false);
        prev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { calShown.add(Calendar.MONTH, -1); renderCal(); }
        });
        hd.addView(prev, new LinearLayout.LayoutParams(dp(46), dp(38)));
        calTitle = label("", 16, Color.WHITE, true);
        calTitle.setGravity(Gravity.CENTER);
        hd.addView(calTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView next = btn("\u203A", false);
        next.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { calShown.add(Calendar.MONTH, 1); renderCal(); }
        });
        hd.addView(next, new LinearLayout.LayoutParams(dp(46), dp(38)));
        calPanel.addView(hd);
        LinearLayout wk = new LinearLayout(this);
        wk.setOrientation(LinearLayout.HORIZONTAL);
        String[] dn = {"S", "M", "T", "W", "T", "F", "S"};
        for (String d : dn) {
            TextView t = label(d, 12, RED, true);
            t.setGravity(Gravity.CENTER);
            wk.addView(t, new LinearLayout.LayoutParams(0, dp(28), 1f));
        }
        LinearLayout.LayoutParams wkl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wkl.topMargin = dp(6);
        calPanel.addView(wk, wkl);
        calGrid = new LinearLayout(this);
        calGrid.setOrientation(LinearLayout.VERTICAL);
        calPanel.addView(calGrid);
        FrameLayout.LayoutParams cpl = new FrameLayout.LayoutParams(
                dp(330), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        cpl.bottomMargin = dp(72);
        cpl.rightMargin = dp(16);
        root.addView(calPanel, cpl);
        calPanel.setVisibility(View.GONE);

        buildUpdPanel(root);

        // lock screen (always on top): profile + password, like Windows
        lockView = new FrameLayout(this);
        lockView.setBackgroundColor(0xFF000000);
        lockView.setClickable(true);

        lockTime = label("", 40, Color.WHITE, false);
        lockTime.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        lockTime.setGravity(Gravity.CENTER);
        lockTime.setShadowLayer(dp(4), 0, dp(1), 0xAA000000);
        FrameLayout.LayoutParams ltl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ltl.topMargin = dp(28);
        lockView.addView(lockTime, ltl);

        LinearLayout lb = new LinearLayout(this);
        lb.setOrientation(LinearLayout.VERTICAL);
        lb.setGravity(Gravity.CENTER_HORIZONTAL);

        lockAvatar = label("V", 44, Color.WHITE, true);
        lockAvatar.setGravity(Gravity.CENTER);
        GradientDrawable av = glass(RED, 0x88FFFFFF, 48, 2);
        lockAvatar.setBackground(av);
        lb.addView(lockAvatar, new LinearLayout.LayoutParams(dp(96), dp(96)));

        lockName = label("", 22, Color.WHITE, true);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.topMargin = dp(12);
        nl.bottomMargin = dp(14);
        lb.addView(lockName, nl);

        lockEt = new EditText(this);
        lockEt.setSingleLine(true);
        lockEt.setTextColor(Color.WHITE);
        lockEt.setHintTextColor(0x99FFFFFF);
        lockEt.setHint("Password");
        lockEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        lockEt.setPadding(dp(14), 0, dp(14), 0);
        lockEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        lockEt.setImeOptions(EditorInfo.IME_ACTION_GO);
        lockEt.setBackground(glass(0xFF26262E, 0x66FFFFFF, 10, 1));
        lockEt.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                tryUnlock();
                return true;
            }
        });
        lb.addView(lockEt, new LinearLayout.LayoutParams(dp(300), dp(48)));

        TextView go = btn("Sign in", true);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { tryUnlock(); }
        });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(300), dp(46));
        gl.topMargin = dp(10);
        lb.addView(go, gl);

        lockMsg = label(" ", 14, 0xFFFF8A8A, false);
        lockMsg.setPadding(0, dp(10), 0, 0);
        lb.addView(lockMsg);

        lockView.addView(lb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(lockView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lockView.setVisibility(View.GONE);
    }

    private void addCtx(String text, final Runnable r) {
        TextView t = btn(text, false);
        t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hideCtx();
                r.run();
            }
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        p.setMargins(0, 0, 0, dp(4));
        ctxMenu.addView(t, p);
    }

    private void showCtx(float x, float y, String extra) {
        if (locked) return;
        hideStart();
        ctxMenu.removeAllViews();
        if (extra != null) {
            final String ex = extra;
            addCtx("Open " + ex, () -> openBuiltin(ex));
        }
        addCtx("Search", () -> openSearch());
        addCtx("Next wallpaper", () -> nextWall());
        addCtx("Custom wallpaper (QR)", () -> openWallPanel());
        addCtx("Desktop icons show / hide", () -> toggleIcons());
        addCtx("Choose widgets", () -> chooseWidgetsDialog());
        addCtx("Widgets show / hide", () -> toggleWidgets());
        addCtx("Clock style (next)", () -> cycleClock());
        addCtx("Quick settings", () -> toggleQuick());
        addCtx("New folder", () -> newFolderDialog());
        addCtx("Keyboard mouse mode", () -> toggleKb());
        addCtx("Dock show / hide", () -> toggleDock());
        addCtx("Screenshot", () -> screenshot());
        addCtx("Reset layout", () -> resetLayout());
        addCtx("Lock screen", () -> lockFromMenu());
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int est = dp(16) + ctxMenu.getChildCount() * dp(42);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) ctxMenu.getLayoutParams();
        p.leftMargin = (int) Math.max(0, Math.min(x, dm.widthPixels - dp(250)));
        p.topMargin = (int) Math.max(0, Math.min(y, dm.heightPixels - est - dp(70)));
        ctxMenu.setLayoutParams(p);
        ctxLayer.setVisibility(View.VISIBLE);
        if (ctxMenu.getChildCount() > 0) ctxMenu.getChildAt(0).requestFocus();
    }

    private void showCtx(float x, float y) {
        showCtx(x, y, null);
    }

    private void hideCtx() {
        ctxLayer.setVisibility(View.GONE);
    }

    private void openSearch() {
        closeAllPanels();
        hideStart();
        searchEt.setText("");
        searchResults.removeAllViews();
        searchPanel.setVisibility(View.VISIBLE);
        searchEt.requestFocus();
        h.postDelayed(new Runnable() {
            public void run() {
                try {
                    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(searchEt, 0);
                } catch (Exception e) {
                    // ignore
                }
            }
        }, 200);
    }

    private void hideSearch() {
        if (searchPanel.getVisibility() != View.VISIBLE) return;
        searchPanel.setVisibility(View.GONE);
        try {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(searchEt.getWindowToken(), 0);
        } catch (Exception e) {
            // ignore
        }
    }

    private void refreshSearch(final String raw) {
        searchResults.removeAllViews();
        searchTok++;
        final String q = raw.trim();
        if (q.length() == 0) return;
        String ql = q.toLowerCase(Locale.ENGLISH);
        int n = 0;
        for (final AppItem a : apps) {
            if (n >= 6) break;
            if (a.label != null && a.label.toLowerCase(Locale.ENGLISH).contains(ql)) {
                n++;
                TextView t = btn(a.label, false);
                t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                t.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        hideSearch();
                        launch(a);
                    }
                });
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
                p.setMargins(0, 0, 0, dp(5));
                searchResults.addView(t, p);
            }
        }
        final String[][] bi = {{"YouTube", "YouTube"}, {"Google", "Google"}, {"Facebook", "Facebook"}, {"Browser", "Browser"},
                {"Files", "Files"}, {"Notepad", "Notepad"}, {"Calculator", "Calculator"}, {"System", "Task manager"},
                {"Clipboard", "Clipboard"}, {"Timer", "Timer / Stopwatch"},
                {"TikTok", "TikTok"}, {"WhatsApp", "WhatsApp"}, {"Telegram", "Telegram"}};
        for (final String[] b : bi) {
            if (n >= 9) break;
            if (b[1].toLowerCase(Locale.ENGLISH).contains(ql) || b[0].toLowerCase(Locale.ENGLISH).contains(ql)) {
                n++;
                TextView t = btn(b[1], false);
                t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                t.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        hideSearch();
                        openBuiltin(b[0]);
                    }
                });
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
                p.setMargins(0, 0, 0, dp(5));
                searchResults.addView(t, p);
            }
        }
        TextView w = btn("Search the web for \"" + q + "\"", true);
        w.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        w.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hideSearch();
                searchWeb(q);
            }
        });
        searchResults.addView(w, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        searchFiles(ql);
    }

    private void toggleCal() {
        if (calPanel.getVisibility() == View.VISIBLE) {
            calPanel.setVisibility(View.GONE);
            return;
        }
        closeAllPanels();
        hideStart();
        calShown.setTimeInMillis(System.currentTimeMillis());
        renderCal();
        calPanel.setVisibility(View.VISIBLE);
    }

    private void renderCal() {
        calGrid.removeAllViews();
        SimpleDateFormat f = new SimpleDateFormat("MMMM yyyy", Locale.ENGLISH);
        f.setTimeZone(tz);
        calTitle.setText(f.format(calShown.getTime()));
        Calendar c = (Calendar) calShown.clone();
        c.set(Calendar.DAY_OF_MONTH, 1);
        int first = c.get(Calendar.DAY_OF_WEEK) - 1;
        int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        Calendar today = Calendar.getInstance(tz);
        boolean thisMonth = today.get(Calendar.YEAR) == c.get(Calendar.YEAR)
                && today.get(Calendar.MONTH) == c.get(Calendar.MONTH);
        int d = 1;
        for (int r = 0; r < 6; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < 7; k++) {
                int idx = r * 7 + k;
                TextView t = label("", 14, Color.WHITE, false);
                t.setGravity(Gravity.CENTER);
                if (idx >= first && d <= days) {
                    t.setText("" + d);
                    if (thisMonth && d == today.get(Calendar.DAY_OF_MONTH)) {
                        t.setBackground(glass(RED, 0, 8, 0));
                        t.setTypeface(Typeface.DEFAULT_BOLD);
                    }
                    final int fy = c.get(Calendar.YEAR), fm = c.get(Calendar.MONTH), fd = d;
                    if (hasEvents(fy, fm, fd) && !(thisMonth && d == today.get(Calendar.DAY_OF_MONTH))) {
                        t.setBackground(glass(0x44FFFFFF, RED, 8, 1));
                    }
                    t.setClickable(true);
                    t.setFocusable(true);
                    t.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) { dayDialog(fy, fm, fd); }
                    });
                    d++;
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
                lp.setMargins(dp(1), dp(1), dp(1), dp(1));
                row.addView(t, lp);
            }
            calGrid.addView(row);
        }
    }

    // ----- lock screen, PIN, startup site

    private String userName() {
        String n = prefs.getString("uname", "User");
        return n.length() == 0 ? "User" : n;
    }

    private void lockFromMenu() {
        if (prefs.getString("pin", "").length() == 0) {
            toast("Set a password first (START > Account & password)");
            return;
        }
        lockNow();
    }

    private void applyAvatar() {
        String n = userName();
        lockName.setText(n);
        Bitmap src = null;
        if (profileFile.exists()) {
            try {
                src = BitmapFactory.decodeFile(profileFile.getPath());
            } catch (Throwable t) {
                src = null;
            }
        }
        if (src == null) {
            lockAvatar.setBackground(glass(RED, 0x88FFFFFF, 48, 2));
            lockAvatar.setText(n.substring(0, 1).toUpperCase(Locale.ENGLISH));
            return;
        }
        int sz = dp(96);
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        cv.drawCircle(sz / 2f, sz / 2f, sz / 2f - dp(1), p);
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        int side = Math.min(src.getWidth(), src.getHeight());
        android.graphics.Rect from = new android.graphics.Rect(
                (src.getWidth() - side) / 2, (src.getHeight() - side) / 2,
                (src.getWidth() + side) / 2, (src.getHeight() + side) / 2);
        cv.drawBitmap(src, from, new android.graphics.Rect(0, 0, sz, sz), p);
        lockAvatar.setText("");
        lockAvatar.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), out));
    }

    private void lockNow() {
        locked = true;
        applyAvatar();
        lockMsg.setText(" ");
        lockEt.setText("");
        Date d = new Date();
        lockTime.setText(fBig.format(d) + "\n" + fDate.format(d));
        closeAllPanels();
        hideStart();
        lockView.setVisibility(View.VISIBLE);
        lockView.bringToFront();
        startLockShow();
        lockEt.requestFocus();
        h.postDelayed(new Runnable() {
            public void run() {
                try {
                    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(lockEt, 0);
                } catch (Exception e) {
                    // ignore
                }
            }
        }, 300);
    }

    private void tryUnlock() {
        String pw = prefs.getString("pin", "");
        if (lockEt.getText().toString().equals(pw)) {
            locked = false;
            lockView.setVisibility(View.GONE);
            try {
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(lockEt.getWindowToken(), 0);
            } catch (Exception e) {
                // ignore
            }
            lockEt.setText("");
            runStartup();
        } else {
            lockMsg.setText("The password is incorrect. Try again.");
            lockEt.setText("");
        }
    }

    private void pinDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        final EditText nameEt = new EditText(this);
        nameEt.setSingleLine(true);
        nameEt.setHint("Account name");
        nameEt.setText(userName());
        box.addView(nameEt);
        final EditText pwEt = new EditText(this);
        pwEt.setSingleLine(true);
        pwEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwEt.setHint(prefs.getString("pin", "").length() > 0 ? "New password (empty = keep current)" : "Password (min 4 characters)");
        box.addView(pwEt);
        new AlertDialog.Builder(this)
                .setTitle("Account & password")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = nameEt.getText().toString().trim();
                        if (n.length() > 0) prefs.edit().putString("uname", n).apply();
                        String p = pwEt.getText().toString();
                        if (p.length() == 0) {
                            toast("Saved");
                        } else if (p.length() < 4) {
                            toast("Password needs at least 4 characters");
                        } else {
                            prefs.edit().putString("pin", p).apply();
                            toast("Password saved");
                        }
                    }
                })
                .setNeutralButton("Remove password", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit().remove("pin").apply();
                        toast("Password removed");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runStartup() {
        if (startupDone) return;
        startupDone = true;
        final String n = prefs.getString("startup", "");
        if (n.length() == 0) return;
        h.postDelayed(new Runnable() {
            public void run() {
                if (!destroyed) openBuiltin(n);
            }
        }, 1500);
    }

    private void startupDialog() {
        final List<String> names = new ArrayList<>();
        names.add("None");
        names.add("YouTube");
        names.add("Google");
        names.add("Facebook");
        names.add("Viral Quality");
        for (String line : prefs.getString("sites", "").split("\n")) {
            int i = line.indexOf('\t');
            if (i > 0) names.add(line.substring(0, i));
        }
        new AlertDialog.Builder(this)
                .setTitle("Open this when the box starts")
                .setItems(names.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            prefs.edit().remove("startup").apply();
                            toast("No startup site");
                        } else {
                            prefs.edit().putString("startup", names.get(which)).apply();
                            toast("Startup: " + names.get(which));
                        }
                    }
                })
                .show();
    }

    // ---------- pinned apps ----------

    private LinearLayout makeSlot(final int i) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.HORIZONTAL);
        s.setGravity(Gravity.CENTER_VERTICAL);
        s.setPadding(dp(12), 0, dp(16), 0);
        s.setFocusable(true);
        s.setClickable(true);
        s.setLongClickable(true);
        s.setBackground(stateBg(false));
        ImageView ic = new ImageView(this);
        s.addView(ic, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView tv = label("+ Add app", 15, Color.WHITE, true);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setMaxWidth(dp(100));
        tv.setPadding(dp(8), 0, 0, 0);
        s.addView(tv);
        s.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AppItem a = findApp(prefs.getString("pin" + i, ""));
                if (a != null) launch(a);
                else openStart(i);
            }
        });
        s.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                openStart(i);
                return true;
            }
        });
        return s;
    }

    private AppItem findApp(String pkg) {
        if (pkg == null || pkg.length() == 0) return null;
        for (AppItem a : apps) {
            if (a.pkg.equals(pkg)) return a;
        }
        return null;
    }

    private void refreshSlots() {
        for (int i = 0; i < 3; i++) {
            AppItem a = findApp(prefs.getString("pin" + i, ""));
            ImageView ic = (ImageView) slots[i].getChildAt(0);
            TextView tv = (TextView) slots[i].getChildAt(1);
            if (a != null) {
                ic.setVisibility(View.VISIBLE);
                ic.setImageDrawable(a.icon);
                tv.setText(a.label);
            } else {
                ic.setVisibility(View.GONE);
                tv.setText("+ Add app");
            }
        }
    }

    // ---------- start panel ----------

    private LinearLayout buildStartPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setPadding(dp(14), dp(14), dp(14), dp(14));
        p.setBackground(glass(0xF2101015, 0x55FFFFFF, 20, 1));

        // left: system shortcuts
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        sv.addView(left, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView head = label("VIRAL  LAUNCHER", 12, RED, true);
        head.setLetterSpacing(0.25f);
        head.setPadding(dp(8), dp(2), 0, dp(10));
        left.addView(head);
        updItem = addMenu(left, "Update launcher", () -> updateLauncher());
        refreshUpdateText();
        section(left, "WEB WINDOWS");
        addMenu(left, "YouTube", new Runnable() { public void run() { openWeb("YouTube", "https://m.youtube.com"); } });
        addMenu(left, "Google", new Runnable() { public void run() { openWeb("Google", "https://www.google.com"); } });
        addMenu(left, "Facebook", new Runnable() { public void run() { openWeb("Facebook", "https://m.facebook.com"); } });
        addMenu(left, "TikTok", () -> openBuiltin("TikTok"));
        addMenu(left, "WhatsApp (Alt+A)", () -> openBuiltin("WhatsApp"));
        addMenu(left, "Telegram (Alt+H)", () -> openBuiltin("Telegram"));
        addMenu(left, "Viral Quality", new Runnable() { public void run() { openWeb("Viral Quality", SITE_URL); } });
        addMenu(left, "+ New browser window", new Runnable() { public void run() { openWeb("Browser", "https://www.google.com"); } });
        webBox = new LinearLayout(this);
        webBox.setOrientation(LinearLayout.VERTICAL);
        left.addView(webBox);
        refreshWebMenu();
        section(left, "TOOLS");
        addMenu(left, "Files", () -> openTool("Files"));
        addMenu(left, "Notepad", () -> openTool("Notepad"));
        addMenu(left, "Calculator", () -> openTool("Calculator"));
        addMenu(left, "Task manager / System", () -> openTool("System"));
        addMenu(left, "Clipboard history", () -> openTool("Clipboard"));
        addMenu(left, "Photos", () -> openPhotos(null));
        addMenu(left, "Internet speed test", () -> openTool("Speed"));
        addMenu(left, "Downloads (Alt+O)", () -> openTool("Downloads"));
        addMenu(left, "Unit & currency converter (Alt+J)", () -> openTool("Convert"));
        addMenu(left, "Quick settings (Alt+Q)", () -> toggleQuick());
        addMenu(left, "Timer / Stopwatch", () -> openTool("Timer"));
        addMenu(left, "Clean RAM", () -> cleanRam());
        section(left, "DESKTOP");
        addMenu(left, "Search", () -> openSearch());
        addMenu(left, "Calendar", () -> toggleCal());
        addMenu(left, "Desktop icons show / hide", () -> toggleIcons());
        addMenu(left, "Clock show / hide", () -> toggleClock());
        addMenu(left, "Clock style (next)", () -> cycleClock());
        addMenu(left, "Choose widgets (pick each one)", () -> chooseWidgetsDialog());
        addMenu(left, "Widgets show / hide", () -> toggleWidgets());
        addMenu(left, "Screenshot (PrtSc)", () -> screenshot());
        addMenu(left, "Reset icon / widget layout", () -> resetLayout());
        addMenu(left, "Keyboard shortcuts", () -> shortcutsDialog());
        addMenu(left, "Add shortcut (Alt + key)", () -> addShortcutDialog());
        addMenu(left, "My shortcuts (remove)", () -> manageShortcutsDialog());
        addMenu(left, "Wallpaper slideshow on / off", () -> toggleSlide());
        addMenu(left, "Accent color (next)", () -> nextAccent());
        addMenu(left, "Dock show / hide", () -> toggleDock());
        addMenu(left, "Glass blur on / off", () -> toggleBlur());
        addMenu(left, "Screen saver on / off", () -> toggleSaver());
        addMenu(left, "New desktop folder", () -> newFolderDialog());
        addMenu(left, "Window split size (Alt+R)", () -> cycleSplit());
        addMenu(left, "Keyboard mouse mode (Alt+K)", () -> toggleKb());
        addMenu(left, "Prayer alerts on / off", () -> toggleAzan());
        addMenu(left, "News language (Bengali / English)", () -> toggleNewsLang());
        addMenu(left, "Backup settings", () -> backupSettings());
        addMenu(left, "Restore settings", () -> restoreSettings());
        addMenu(left, "Taskbar glass / solid", () -> toggleGlass());
        addMenu(left, "Taskbar center / left", () -> toggleAlign());
        addMenu(left, "Startup site...", () -> startupDialog());
        addMenu(left, "Lock screen now", () -> lockFromMenu());
        addMenu(left, "Account & password", () -> pinDialog());
        addMenu(left, "Profile picture (QR)", () -> openWallPanel(true));
        section(left, "SYSTEM");
        addMenu(left, "Settings", new Runnable() { public void run() { openSettings(Settings.ACTION_SETTINGS); } });
        addMenu(left, "Wi-Fi / Network", new Runnable() { public void run() { openSettings(Settings.ACTION_WIFI_SETTINGS); } });
        addMenu(left, "Display", new Runnable() { public void run() { openSettings(Settings.ACTION_DISPLAY_SETTINGS); } });
        addMenu(left, "Apps manager", new Runnable() { public void run() { openSettings(Settings.ACTION_APPLICATION_SETTINGS); } });
        addMenu(left, "Custom wallpaper (QR)", new Runnable() { public void run() { openWallPanel(); } });
        addMenu(left, "Next wallpaper", new Runnable() { public void run() { nextWall(); } });
        addMenu(left, "Floating volume button", new Runnable() { public void run() { ensureOverlay(true); } });
        addMenu(left, "Refresh apps", new Runnable() { public void run() { loadApps(); toast("Apps refreshed"); } });
        LinearLayout leftWrap = new LinearLayout(this);
        leftWrap.setOrientation(LinearLayout.VERTICAL);
        leftWrap.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        verText = label("VQ Launcher  \u2022  Build " + curBuild(), 12, 0xCCFFFFFF, true);
        verText.setPadding(dp(8), dp(8), 0, dp(2));
        leftWrap.addView(verText);
        p.addView(leftWrap, new LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.MATCH_PARENT));

        View div = new View(this);
        div.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT);
        dl.setMargins(dp(10), 0, dp(10), 0);
        p.addView(div, dl);

        // right: all apps
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        p.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        pinBanner = new LinearLayout(this);
        pinBanner.setOrientation(LinearLayout.HORIZONTAL);
        pinBanner.setGravity(Gravity.CENTER_VERTICAL);
        pinText = label("", 15, Color.WHITE, true);
        pinBanner.addView(pinText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = btn("Clear this slot", false);
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int s = pinSlot;
                if (s >= 0) prefs.edit().remove("pin" + s).apply();
                hideStart();
                refreshSlots();
            }
        });
        pinBanner.addView(clear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        LinearLayout.LayoutParams pbl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbl.bottomMargin = dp(8);
        right.addView(pinBanner, pbl);
        pinBanner.setVisibility(View.GONE);

        TextView all = label("ALL APPS", 12, RED, true);
        all.setLetterSpacing(0.25f);
        all.setPadding(dp(8), dp(2), 0, dp(6));
        right.addView(all);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(110));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(8));
        grid.setHorizontalSpacing(dp(8));
        grid.setPadding(dp(4), dp(4), dp(4), dp(4));
        grid.setClipToPadding(false);
        grid.setSelector(glass((RED & 0xFFFFFF) | 0x33000000, RED, 14, 2));
        grid.setFocusable(true);
        grid.setVerticalScrollBarEnabled(false);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> par, View v, int pos, long id) {
                AppItem a = apps.get(pos);
                if (pinSlot >= 0) {
                    int s = pinSlot;
                    prefs.edit().putString("pin" + s, a.pkg).apply();
                    hideStart();
                    refreshSlots();
                    toast("Pinned " + a.label + " to slot " + (s + 1));
                } else {
                    hideStart();
                    launch(a);
                }
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> par, View v, int pos, long id) {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + apps.get(pos).pkg)));
                } catch (Exception e) {
                    toast("App info not available");
                }
                return true;
            }
        });
        right.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return p;
    }

    private void section(LinearLayout m, String text) {
        TextView t = label(text, 11, RED, true);
        t.setLetterSpacing(0.2f);
        t.setPadding(dp(8), dp(8), 0, dp(6));
        m.addView(t);
    }

    private TextView addMenu(LinearLayout m, String text, final Runnable r) {
        TextView t = btn(text, false);
        t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hideStart();
                r.run();
            }
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        p.setMargins(0, 0, 0, dp(5));
        m.addView(t, p);
        return t;
    }

    private void toggleStart() {
        if (startPanel.getVisibility() == View.VISIBLE) hideStart();
        else openStart(-1);
    }

    private void openStart(int slot) {
        checkLatest();
        closeWallPanel();
        hideVol();
        pinSlot = slot;
        if (slot >= 0) {
            pinText.setText("Choose an app for taskbar slot " + (slot + 1));
            pinBanner.setVisibility(View.VISIBLE);
        } else {
            pinBanner.setVisibility(View.GONE);
        }
        applyBlur();
        startPanel.setAlpha(0f);
        startPanel.setTranslationY(dp(40));
        startPanel.setVisibility(View.VISIBLE);
        startPanel.animate().alpha(1f).translationY(0f).setDuration(200).start();
        grid.setSelection(0);
        grid.requestFocus();
    }

    private void hideStart() {
        pinSlot = -1;
        if (startPanel.getVisibility() == View.VISIBLE) {
            startPanel.animate().cancel();
            startPanel.setVisibility(View.GONE);
            startBtn.requestFocus();
        }
    }

    private void openSettings(String action) {
        try {
            Intent i = new Intent(action);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("Not available on this box");
        }
    }

    // ---------- custom wallpaper panel ----------

    private LinearLayout buildWallPanel() {
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        m.setPadding(dp(22), dp(18), dp(22), dp(20));
        m.setBackground(glass(0xF2101015, 0x66FFFFFF, 20, 1));

        TextView head = label("CUSTOM WALLPAPER", 13, RED, true);
        wallHead = head;
        head.setLetterSpacing(0.25f);
        head.setPadding(0, 0, 0, dp(12));
        m.addView(head);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        m.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        qrView = new QrView(this);
        row.addView(qrView, new LinearLayout.LayoutParams(dp(250), dp(250)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), 0, 0, 0);
        row.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        col.addView(label("1. Phone and TV on the same Wi-Fi", 15, Color.WHITE, false));
        TextView s2 = label("2. Scan the QR code with your phone camera, or open this address:", 15, Color.WHITE, false);
        s2.setPadding(0, dp(6), 0, dp(6));
        col.addView(s2);
        wallUrl = label("", 17, RED, true);
        col.addView(wallUrl);
        TextView s3 = label("3. Choose image, move/zoom to crop, press Upload", 15, Color.WHITE, false);
        s3.setPadding(0, dp(6), 0, dp(10));
        col.addView(s3);
        wallStatus = label("", 16, 0xFFFFFFFF, true);
        col.addView(wallStatus);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(16);
        m.addView(btns, bl);

        TextView next = btn("Next wallpaper", false);
        next.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { nextWall(); }
        });
        wallNext = next;
        btns.addView(next, tbLp());

        TextView rm = btn("Remove custom", false);
        rm.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (profileMode) {
                    profileFile.delete();
                    applyAvatar();
                    wallStatus.setText("Profile picture removed");
                    return;
                }
                customFile.delete();
                wallIdx = 0;
                applyWall();
                wallStatus.setText("Custom wallpaper removed");
            }
        });
        btns.addView(rm, tbLp());

        wallClose = btn("Close", true);
        wallClose.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeWallPanel(); }
        });
        btns.addView(wallClose, tbLp());
        return m;
    }

    private String localIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private void openWallPanel() {
        openWallPanel(false);
    }

    private void openWallPanel(boolean profile) {
        hideStart();
        hideVol();
        if (server != null) {
            server.stop();
            server = null;
        }
        profileMode = profile;
        wallHead.setText(profile ? "PROFILE PICTURE" : "CUSTOM WALLPAPER");
        wallNext.setVisibility(profile ? View.GONE : View.VISIBLE);
        if (server == null) {
            wallKey = String.valueOf(1000 + new Random().nextInt(9000));
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            server = new WallServer(profile ? profileFile : customFile, wallKey,
                    profile ? 512 : dm.widthPixels, profile ? 512 : dm.heightPixels,
                    new WallServer.Listener() {
                        public void onUploaded() {
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    if (profileMode) {
                                        applyAvatar();
                                        wallStatus.setText("Profile picture updated!");
                                        return;
                                    }
                                    wallIdx = 0;
                                    prefs.edit().putInt("wall", 0).apply();
                                    applyWall();
                                    wallStatus.setText("Wallpaper updated!");
                                }
                            });
                        }
                    });
            if (!server.start()) {
                server = null;
                toast("Cannot start the upload server");
                return;
            }
        }
        String ip = localIp();
        if (ip == null) {
            wallUrl.setText("No network found. Connect Wi-Fi first.");
            qrView.set(null);
        } else {
            String url = "http://" + ip + ":" + server.port + "/?k=" + wallKey;
            wallUrl.setText(url);
            qrView.set(Qr.encode(url));
        }
        wallStatus.setText("Waiting for your phone...");
        wallPanel.setVisibility(View.VISIBLE);
        wallClose.requestFocus();
    }

    private void closeWallPanel() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (wallPanel.getVisibility() == View.VISIBLE) {
            wallPanel.setVisibility(View.GONE);
            startBtn.requestFocus();
        }
    }

    // ---------- volume panel ----------

    private LinearLayout buildVolPanel() {
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        m.setPadding(dp(16), dp(14), dp(16), dp(16));
        m.setBackground(glass(0xF2101015, 0x55FFFFFF, 18, 1));
        TextView head = label("VOLUME", 12, RED, true);
        head.setLetterSpacing(0.25f);
        head.setPadding(dp(4), 0, 0, dp(10));
        m.addView(head);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        m.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView minus = btn("  −  ", false);
        minus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stepVol(-1); }
        });
        row.addView(minus, new LinearLayout.LayoutParams(dp(60), dp(46)));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(glass(0x33FFFFFF, 0, 6, 0));
        volFill = new View(this);
        volFill.setBackground(glass(RED, 0, 6, 0));
        volRest = new View(this);
        bar.addView(volFill, new LinearLayout.LayoutParams(0, dp(12), 50f));
        bar.addView(volRest, new LinearLayout.LayoutParams(0, dp(12), 50f));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(12), 1f);
        bp.setMargins(dp(12), 0, dp(12), 0);
        row.addView(bar, bp);

        volPlus = btn("  +  ", false);
        volPlus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        volPlus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stepVol(1); }
        });
        row.addView(volPlus, new LinearLayout.LayoutParams(dp(60), dp(46)));

        volPct = label("0%", 16, Color.WHITE, true);
        volPct.setGravity(Gravity.CENTER);
        row.addView(volPct, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView mute = btn("Mute", false);
        mute.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleMute(); }
        });
        row.addView(mute, new LinearLayout.LayoutParams(dp(84), dp(46)));
        return m;
    }

    private void toggleVol() {
        if (volPanel.getVisibility() == View.VISIBLE) {
            hideVol();
        } else {
            hideStart();
            closeWallPanel();
            refreshVol();
            volPanel.setVisibility(View.VISIBLE);
            volPlus.requestFocus();
            h.removeCallbacks(volHide);
            h.postDelayed(volHide, 6000);
        }
    }

    private void hideVol() {
        h.removeCallbacks(volHide);
        if (volPanel.getVisibility() == View.VISIBLE) {
            volPanel.setVisibility(View.GONE);
            startBtn.requestFocus();
        }
    }

    private void stepVol(int dir) {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int step = Math.max(1, Math.round(max / 15f));
        setVol(cur + dir * step);
    }

    private void setVol(int v) {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        v = Math.max(0, Math.min(max, v));
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
        } catch (Exception e) {
            toast("Cannot change volume");
        }
        refreshVol();
        h.removeCallbacks(volHide);
        h.postDelayed(volHide, 6000);
    }

    private void toggleMute() {
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (cur > 0) {
            preMute = cur;
            setVol(0);
        } else {
            setVol(preMute > 0 ? preMute : Math.max(1, max / 2));
        }
    }

    private void refreshVol() {
        if (am == null || spk == null) return;
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int pct = cur * 100 / max;
        spk.muted = cur == 0;
        spk.level = pct == 0 ? 0 : (pct < 34 ? 1 : (pct < 67 ? 2 : 3));
        spk.invalidate();
        if (volPct != null) {
            volPct.setText(pct + "%");
            volFill.setLayoutParams(new LinearLayout.LayoutParams(0, dp(12), pct));
            volRest.setLayoutParams(new LinearLayout.LayoutParams(0, dp(12), 100 - pct));
        }
    }

    // ---------- floating volume permission ----------

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void ensureOverlay(boolean force) {
        if (canOverlay()) {
            try {
                Intent i = new Intent(this, VolumeService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (Exception e) {
                toast("Floating volume could not start");
            }
            if (force) toast("Floating volume is on (shows inside other apps)");
        } else if (force || !prefs.getBoolean("askedOverlay", false)) {
            prefs.edit().putBoolean("askedOverlay", true).apply();
            toast("Please allow 'Display over other apps' for VQ Launcher");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                toast("Open Settings > Apps > Special access > Display over other apps");
            }
        }
    }

    // ---------- wallpaper ----------

    private int imageWallId() {
        return getResources().getIdentifier("wallpaper", "drawable", getPackageName());
    }

    private void nextWall() {
        wallIdx++;
        prefs.edit().putInt("wall", wallIdx).apply();
        applyWall();
    }

    private boolean showCustom() {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(customFile.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 2000) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(customFile.getPath(), o2);
            if (bmp == null) return false;
            wall.setImageBitmap(bmp);
            shade.setBackgroundColor(0x00000000);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void applyWall() {
        boolean hasCustom = customFile.exists();
        int img = imageWallId();
        boolean hasImg = img != 0;
        int n = PRESETS.length + (hasCustom ? 1 : 0) + (hasImg ? 1 : 0);
        int k = ((wallIdx % n) + n) % n;
        if (hasCustom) {
            if (k == 0) {
                if (showCustom()) return;
            } else {
                k--;
            }
        }
        if (hasImg) {
            if (k == 0) {
                try {
                    wall.setImageDrawable(getResources().getDrawable(img, getTheme()));
                    shade.setBackgroundColor(0x00000000);
                    return;
                } catch (Exception e) {
                    // fall through to gradient
                }
            } else {
                k--;
            }
        }
        if (k < 0 || k >= PRESETS.length) k = 0;
        int[] c = PRESETS[k];
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(Math.max(dm.widthPixels, dm.heightPixels) * 0.9f);
        g.setGradientCenter(0.85f, 0.1f);
        g.setColors(new int[]{c[0], c[1]});
        wall.setImageDrawable(g);
        shade.setBackgroundColor(0x00000000);
    }

    // ---------- apps ----------

    private void loadApps() {
        new Thread(new Runnable() {
            public void run() {
                PackageManager pm = getPackageManager();
                Map<String, AppItem> m = new HashMap<>();
                String[] cats = {"android.intent.category.LEANBACK_LAUNCHER", Intent.CATEGORY_LAUNCHER};
                for (String c : cats) {
                    Intent i = new Intent(Intent.ACTION_MAIN);
                    i.addCategory(c);
                    List<ResolveInfo> l = pm.queryIntentActivities(i, 0);
                    for (ResolveInfo r : l) {
                        String p = r.activityInfo.packageName;
                        if (p.equals(getPackageName()) || m.containsKey(p)) continue;
                        AppItem a = new AppItem();
                        a.pkg = p;
                        a.cls = r.activityInfo.name;
                        a.label = String.valueOf(r.loadLabel(pm));
                        try {
                            a.icon = r.loadIcon(pm);
                        } catch (Exception e) {
                            a.icon = pm.getDefaultActivityIcon();
                        }
                        m.put(p, a);
                    }
                }
                final List<AppItem> out = new ArrayList<>(m.values());
                Collections.sort(out, new java.util.Comparator<AppItem>() {
                    public int compare(AppItem x, AppItem y) {
                        return x.label.compareToIgnoreCase(y.label);
                    }
                });
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (destroyed) return;
                        apps.clear();
                        apps.addAll(out);
                        adapter.notifyDataSetChanged();
                        refreshSlots();
                    }
                });
            }
        }).start();
    }

    private void launch(AppItem a) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setClassName(a.pkg, a.cls);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(a.pkg);
                if (i != null) startActivity(i);
            } catch (Exception e2) {
                toast("Cannot open " + a.label);
            }
        }
    }

    private class AppAdapter extends BaseAdapter {
        public int getCount() { return apps.size(); }
        public Object getItem(int p) { return apps.get(p); }
        public long getItemId(int p) { return p; }

        public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout v;
            ImageView icon;
            TextView name;
            if (cv == null) {
                v = new LinearLayout(MainActivity.this);
                v.setOrientation(LinearLayout.VERTICAL);
                v.setGravity(Gravity.CENTER);
                v.setPadding(dp(4), dp(6), dp(4), dp(6));
                v.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(104)));
                icon = new ImageView(MainActivity.this);
                v.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
                name = label("", 13, Color.WHITE, false);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                np.topMargin = dp(6);
                v.addView(name, np);
            } else {
                v = (LinearLayout) cv;
            }
            icon = (ImageView) v.getChildAt(0);
            name = (TextView) v.getChildAt(1);
            AppItem a = apps.get(pos);
            icon.setImageDrawable(a.icon);
            name.setText(a.label);
            return v;
        }
    }

    // ---------- weather + time zone ----------

    private void applyTz(String id) {
        if (id != null && id.length() > 0) {
            TimeZone t = TimeZone.getTimeZone(id);
            if (t.getID().equals(id)) tz = t;
        }
        fBig.setTimeZone(tz);
        fTray.setTimeZone(tz);
        fDate.setTimeZone(tz);
        fHm.setTimeZone(tz);
        fAp.setTimeZone(tz);
        fH.setTimeZone(tz);
        fMin.setTimeZone(tz);
        fDay.setTimeZone(tz);
        fSub.setTimeZone(tz);
        lastSec = -1;
    }

    private String http(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        c.disconnect();
        return sb.toString();
    }

    private String wxText(int code) {
        if (code == 0) return "Clear sky";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Rain showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "Weather";
    }

    private void loadWeather() {
        new Thread(new Runnable() {
            public void run() {
                double lat = 23.8103, lon = 90.4125;
                try {
                    JSONObject o = new JSONObject(http("https://ipwho.is/"));
                    if (o.optBoolean("success", false)) {
                        lat = o.getDouble("latitude");
                        lon = o.getDouble("longitude");
                        prefs.edit().putString("lat", "" + lat).putString("lon", "" + lon).apply();
                        JSONObject z = o.optJSONObject("timezone");
                        final String zid = z == null ? "" : z.optString("id", "");
                        if (zid.length() > 0) {
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    applyTz(zid);
                                    prefs.edit().putString("tz", zid).apply();
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    // use default location
                }
                long next = 60 * 1000;
                try {
                    String j = http("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                            + "&longitude=" + lon + "&current=temperature_2m,weather_code&timezone=auto");
                    JSONObject c = new JSONObject(j).getJSONObject("current");
                    final int t = (int) Math.round(c.getDouble("temperature_2m"));
                    final String d = wxText(c.getInt("weather_code"));
                    runOnUiThread(new Runnable() {
                        public void run() {
                            trayWx.setText(t + "°C  " + d);
                        }
                    });
                    next = 30 * 60 * 1000;
                } catch (Exception e) {
                    // retry soon
                }
                final long delay = next;
                h.postDelayed(new Runnable() {
                    public void run() {
                        if (!destroyed) loadWeather();
                    }
                }, delay);
            }
        }).start();
    }

    // ---------- lifecycle / keys ----------

    @Override
    protected void onResume() {
        super.onResume();
        VolumeService.setHome(true);
        ensureOverlay(false);
        if (pendingApk != null && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) {
            installApk(pendingApk);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        for (WebWindow w : wins) w.resumeWeb();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onStop() {
        for (WebWindow w : wins) w.pauseWeb();
        h.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    protected void onPause() {
        VolumeService.setHome(false);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideStart();
        closeAllPanels();
        if (volPanel != null) hideVol();
        if (wallPanel != null) closeWallPanel();
        if (startBtn != null) startBtn.requestFocus();
    }

    private boolean metaCombo = false;

    // ----- version info

    private TextView updItem, verText;
    private int latestBuild = -1;
    private long lastCheck = 0;

    private int curBuild() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void refreshUpdateText() {
        if (updItem == null) return;
        int cur = curBuild();
        if (updBadge != null) updBadge.setVisibility(latestBuild > cur ? View.VISIBLE : View.GONE);
        if (latestBuild < 0) {
            updItem.setText("Update launcher  (build " + cur + ")");
        } else if (latestBuild > cur) {
            updItem.setText("NEW: update to build " + latestBuild + "  (now " + cur + ")");
        } else {
            updItem.setText("Up to date: build " + cur);
        }
    }

    private void checkLatest() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 60000) return;
        lastCheck = now;
        new Thread(new Runnable() {
            public void run() {
                try {
                    String j = http("https://api.github.com/repos/mdhamimahamed06-afk/tv-launcher/releases/latest");
                    String tag = new JSONObject(j).optString("tag_name", "");
                    final int n = Integer.parseInt(tag.replaceAll("[^0-9]", ""));
                    runOnUiThread(new Runnable() {
                        public void run() {
                            latestBuild = n;
                            refreshUpdateText();
                        }
                    });
                } catch (Throwable t) {
                    // offline or rate limited: keep the old text
                }
            }
        }).start();
    }

    // ----- downloads and one-click launcher update

    private static final String UPDATE_URL =
            "https://github.com/mdhamimahamed06-afk/tv-launcher/releases/latest/download/VQLauncher.apk";
    private DownloadManager dlm;
    private final Map<Long, String> dlNames = new HashMap<>();
    private Uri pendingApk;

    private final BroadcastReceiver dlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            String name = dlNames.remove(id);
            if (name == null || dlm == null) return;
            boolean ok = false;
            android.database.Cursor cu = dlm.query(new DownloadManager.Query().setFilterById(id));
            if (cu != null) {
                if (cu.moveToFirst()) {
                    ok = cu.getInt(cu.getColumnIndex(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL;
                }
                cu.close();
            }
            if (!ok) {
                toast("Download failed: " + name);
                return;
            }
            Uri u = dlm.getUriForDownloadedFile(id);
            if (name.toLowerCase(Locale.ENGLISH).endsWith(".apk") && u != null) installApk(u);
            else toast("Downloaded: " + name + " (see Files > Download)");
        }
    };

    private long startDownload(String url, String ua, String name, String mime, boolean priv) {
        try {
            if (dlm == null) dlm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) r.addRequestHeader("Cookie", ck);
            if (mime != null && mime.length() > 0) r.setMimeType(mime);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            boolean pub = !priv && (Build.VERSION.SDK_INT < 23
                    || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
            if (pub) {
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            } else {
                new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name).delete();
                r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            }
            long id = dlm.enqueue(r);
            dlNames.put(id, name);
            toast("Downloading " + name + " ...");
            return id;
        } catch (Throwable t) {
            toast("Download failed");
            return -1;
        }
    }

    private void updateLauncher() {
        long id = startDownload(UPDATE_URL, null, "VQLauncher.apk", "application/vnd.android.package-archive", true);
        if (id >= 0) showUpdPanel(id);
    }

    // ----- animated update panel

    private LinearLayout updPanel;
    private SpinnerView updSpin;
    private TextView updTitle, updStatus, updBadge;
    private View updFill, updRest;
    private long updDlId = -1;
    private android.animation.ObjectAnimator badgeAnim;

    private class SpinnerView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        boolean spinning = true;

        SpinnerView(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight();
            float sw = dp(5);
            p.setStrokeWidth(sw);
            r.set(sw, sw, w - sw, hh - sw);
            p.setColor(0x33FFFFFF);
            c.drawArc(r, 0, 360, false, p);
            if (spinning) {
                p.setColor(RED);
                float a = (System.currentTimeMillis() % 1000) / 1000f * 360f;
                c.drawArc(r, a, 100, false, p);
                if (isShown()) postInvalidateOnAnimation();
            }
        }
    }

    private void buildUpdPanel(FrameLayout root) {
        updPanel = new LinearLayout(this);
        updPanel.setOrientation(LinearLayout.VERTICAL);
        updPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        updPanel.setPadding(dp(28), dp(22), dp(28), dp(20));
        updPanel.setBackground(panelBg());
        updSpin = new SpinnerView(this);
        updPanel.addView(updSpin, new LinearLayout.LayoutParams(dp(64), dp(64)));
        updTitle = label("Updating VQ Launcher", 20, Color.WHITE, true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(14);
        updPanel.addView(updTitle, tl);
        updStatus = label("", 14, 0xCCFFFFFF, false);
        updStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(6);
        updPanel.addView(updStatus, sl);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(glass(0x33FFFFFF, 0, 6, 0));
        updFill = new View(this);
        updFill.setBackground(glass(RED, 0, 6, 0));
        bar.addView(updFill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.02f));
        updRest = new View(this);
        bar.addView(updRest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.98f));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(360), dp(10));
        bl.topMargin = dp(16);
        updPanel.addView(bar, bl);
        TextView hide = btn("Hide", false);
        hide.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { hideUpd(); }
        });
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(100), dp(40));
        hl.topMargin = dp(16);
        updPanel.addView(hide, hl);
        root.addView(updPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        updPanel.setVisibility(View.GONE);
    }

    private void setUpdBar(float f) {
        f = Math.max(0.02f, Math.min(1f, f));
        updFill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, f));
        updRest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - f));
    }

    private void showUpdPanel(long id) {
        updDlId = id;
        updTitle.setText(latestBuild > curBuild() ? "Updating to build " + latestBuild : "Updating VQ Launcher");
        updStatus.setText("Starting download...");
        updSpin.spinning = true;
        setUpdBar(0.02f);
        updPanel.setAlpha(0f);
        updPanel.setScaleX(0.92f);
        updPanel.setScaleY(0.92f);
        updPanel.setVisibility(View.VISIBLE);
        updPanel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).start();
        updSpin.invalidate();
        h.removeCallbacks(updPoll);
        h.post(updPoll);
    }

    private void hideUpd() {
        h.removeCallbacks(updPoll);
        if (updPanel != null && updPanel.getVisibility() == View.VISIBLE) {
            updPanel.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(200)
                    .withEndAction(new Runnable() {
                        public void run() { updPanel.setVisibility(View.GONE); }
                    }).start();
        }
    }

    private final Runnable updPoll = new Runnable() {
        @Override
        public void run() {
            if (dlm == null || updDlId < 0) return;
            android.database.Cursor cu = dlm.query(new DownloadManager.Query().setFilterById(updDlId));
            if (cu == null || !cu.moveToFirst()) {
                if (cu != null) cu.close();
                hideUpd();
                return;
            }
            int st = cu.getInt(cu.getColumnIndex(DownloadManager.COLUMN_STATUS));
            long got = cu.getLong(cu.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long tot = cu.getLong(cu.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            cu.close();
            if (st == DownloadManager.STATUS_SUCCESSFUL) {
                updSpin.spinning = false;
                setUpdBar(1f);
                updStatus.setText("Download complete. Opening the installer...");
                h.postDelayed(new Runnable() {
                    public void run() { hideUpd(); }
                }, 4000);
                return;
            }
            if (st == DownloadManager.STATUS_FAILED) {
                updSpin.spinning = false;
                updStatus.setText("Download failed. Check the internet and try again.");
                return;
            }
            if (tot > 0) {
                int pct = (int) (100L * got / tot);
                updStatus.setText("Downloading...  " + pct + "%");
                setUpdBar(got / (float) tot);
            } else {
                updStatus.setText("Downloading...");
            }
            h.postDelayed(this, 400);
        }
    };

    private void installApk(Uri u) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            pendingApk = u;
            toast("Turn ON \"Allow from this source\" for VQ Launcher, then press Back");
            try {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(s);
            } catch (Throwable t) {
                toast("Open Settings > Apps > Special access > Install unknown apps");
            }
            return;
        }
        pendingApk = null;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable t) {
            toast("Cannot open the installer");
        }
    }
    private ClipboardManager clipMgr;
    private ClipboardManager.OnPrimaryClipChangedListener clipL;

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int kc = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (wakeSaver()) return true;
        if (kbKey(e)) return true;
        if (kc == KeyEvent.KEYCODE_META_LEFT || kc == KeyEvent.KEYCODE_META_RIGHT) {
            if (down) metaCombo = false;
            else if (!metaCombo && !locked) toggleStart();
            return true;
        }
        if (locked) return super.dispatchKeyEvent(e);
        if (down && e.getRepeatCount() == 0) {
            boolean ctrl = e.isCtrlPressed();
            if (kc == KeyEvent.KEYCODE_SYSRQ) {
                screenshot();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_ESCAPE) {
                if (ctrl && e.isShiftPressed()) {
                    openTool("System");
                    return true;
                }
                if (fsView != null) {
                    if (fsCb != null) fsCb.onCustomViewHidden();
                    winHost.onFullscreenVideoEnd();
                }
                closeAllPanels();
                hideStart();
                hideVol();
                return true;
            }
            if (e.isMetaPressed()) metaCombo = true;
            if ((e.isAltPressed() || e.isMetaPressed()) && handleShortcut(e)) return true;
            if (ctrl) {
                if (kc == KeyEvent.KEYCODE_T) { newBrowser(); return true; }
                if (kc == KeyEvent.KEYCODE_W) { closeTop(); return true; }
                if (kc == KeyEvent.KEYCODE_TAB) { cycleWindows(); return true; }
            }
        }
        // typing anywhere on the home screen opens search
        if (down && !e.isCtrlPressed() && !e.isAltPressed() && !e.isMetaPressed()) {
            int u = e.getUnicodeChar();
            boolean busy = wallPanel.getVisibility() == View.VISIBLE || fsView != null || topWindow() != null
                    || ctxLayer.getVisibility() == View.VISIBLE || calPanel.getVisibility() == View.VISIBLE;
            if (u > 32 && u != 127 && !busy && !(getCurrentFocus() instanceof EditText)) {
                openSearch();
                searchEt.setText(String.valueOf((char) u));
                searchEt.setSelection(searchEt.getText().length());
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (locked) {
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK) return true;
            return super.onKeyDown(keyCode, e);
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            toggleStart();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public void onBackPressed() {
        if (locked) return;
        if (ctxLayer.getVisibility() == View.VISIBLE) { hideCtx(); return; }
        if (searchPanel.getVisibility() == View.VISIBLE) { hideSearch(); return; }
        if (calPanel.getVisibility() == View.VISIBLE) { calPanel.setVisibility(View.GONE); return; }
        if (updPanel.getVisibility() == View.VISIBLE) { hideUpd(); return; }
        if (fsView != null) {
            if (fsCb != null) fsCb.onCustomViewHidden();
            winHost.onFullscreenVideoEnd();
            return;
        }
        WebWindow top = topWindow();
        if (startPanel.getVisibility() != View.VISIBLE && wallPanel.getVisibility() != View.VISIBLE
                && volPanel.getVisibility() != View.VISIBLE && top != null && top.canGoBack()) {
            top.goBack();
            return;
        }
        if (wallPanel.getVisibility() == View.VISIBLE) closeWallPanel();
        else if (volPanel.getVisibility() == View.VISIBLE) hideVol();
        else hideStart(); // a launcher never closes
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        try {
            if (mp != null) mp.release();
        } catch (Throwable x) {
            // ignore
        }
        if (badgeAnim != null) badgeAnim.cancel();
        try {
            unregisterReceiver(dlReceiver);
        } catch (Throwable x) {
            // ignore
        }
        try {
            clipMgr.removePrimaryClipChangedListener(clipL);
        } catch (Throwable x) {
            // ignore
        }
        h.removeCallbacksAndMessages(null);
        if (server != null) server.stop();
        try {
            unregisterReceiver(pkgReceiver);
            unregisterReceiver(volReceiver);
        } catch (Exception e) {
            // ignore
        }
        super.onDestroy();
    }
}
