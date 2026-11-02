package com.viral.launcher;

import android.app.Activity;
import android.app.AlertDialog;
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
    private TextView wallUrl, wallStatus, wallClose;

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
                bigTime.setText(fBig.format(d));
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
            clockView.invalidate();
            h.postDelayed(this, 40);
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
                Gravity.END | Gravity.TOP);
        lp.rightMargin = dp(28);
        lp.topMargin = dp(20);
        return lp;
    }

    private LinearLayout buildClockBlock(android.util.DisplayMetrics dm) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);

        int sz = Math.min(dp(130), (int) (dm.heightPixels * 0.22f));
        clockView = new ClockView(this);
        c.addView(clockView, new LinearLayout.LayoutParams(sz, sz));

        bigTime = label("", 26, Color.WHITE, false);
        bigTime.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        bigTime.setShadowLayer(dp(4), 0, dp(1), 0xAA000000);
        bigTime.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(10);
        c.addView(bigTime, tp);

        bigDate = label("", 13, 0xFFFFFFFF, false);
        bigDate.setShadowLayer(dp(4), 0, dp(1), 0xAA000000);
        bigDate.setGravity(Gravity.CENTER);
        c.addView(bigDate);
        return c;
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
        startBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleStart(); }
        });
        t.addView(startBtn, tbLp());

        TextView searchBtn = btn("Search", false);
        searchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSearch(); }
        });
        t.addView(searchBtn, tbLp());

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
        if (showExisting(name)) return;
        openWin(name, url, null, false);
    }

    private void openTool(String name) {
        hideStart();
        if (showExisting(name)) return;
        View v;
        if (name.equals("Files")) v = Tools.files(this, RED);
        else if (name.equals("Notepad")) v = Tools.notepad(this, prefs);
        else if (name.equals("Calculator")) v = Tools.calculator(this, RED);
        else v = Tools.monitor(this, RED);
        openWin(name, null, v, name.equals("Calculator"));
    }

    private void openWin(String name, String url, View content, boolean small) {
        if (wins.size() >= 2) {
            WebWindow old = wins.get(0);
            String n = old.name;
            old.close();
            toast("Maximum 2 windows: closed " + n);
        }
        boolean used0 = false;
        for (WebWindow w : wins) if (w.slot == 0) used0 = true;
        int slot = used0 ? 1 : 0;
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
            desk.addView(win, lp);
            wins.add(win);
            win.setAlpha(0f);
            win.setScaleX(0.93f);
            win.setScaleY(0.93f);
            win.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start();
        } catch (Throwable t) {
            toast("Could not open window");
        }
        refreshTabs();
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

    private void buildDeskIcons(android.util.DisplayMetrics dm) {
        iconsBox = new LinearLayout(this);
        iconsBox.setOrientation(LinearLayout.HORIZONTAL);
        final String[] names = {"YouTube", "Google", "Facebook", "Viral Quality", "Browser", "Files", "Notepad", "Calculator", "System"};
        final String[] marks = {"Y", "G", "f", "VQ", "B", "F", "N", "=", "S"};
        final int[] cols = {0xFFE11D2E, 0xFF2F7BFF, 0xFF1877F2, 0xFFB5121F, 0xFF4B5262, 0xFFF59E0B, 0xFF19B36B, 0xFF8B5CF6, 0xFF0EA5A5};
        int rows = Math.max(3, (int) ((dm.heightPixels / dm.density - 62 - 40) / 94));
        LinearLayout colBox = null;
        for (int i = 0; i < names.length; i++) {
            if (i % rows == 0) {
                colBox = new LinearLayout(this);
                colBox.setOrientation(LinearLayout.VERTICAL);
                iconsBox.addView(colBox, new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            colBox.addView(deskIcon(names[i], marks[i], cols[i]), new LinearLayout.LayoutParams(dp(88), dp(92)));
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        lp.leftMargin = dp(14);
        lp.topMargin = dp(14);
        desk.addView(iconsBox, lp);
        iconsBox.setVisibility(prefs.getBoolean("iconsoff", false) ? View.GONE : View.VISIBLE);

        desk.setLongClickable(true);
        desk.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                lastX = e.getRawX();
                lastY = e.getRawY();
                if ((e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) showCtx(lastX, lastY);
                    return true;
                }
                return false;
            }
        });
        desk.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showCtx(lastX, lastY);
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
        sl.addState(new int[]{}, glass(0x00000000, 0, 12, 0));
        t.setBackground(sl);
        TextView badge = label(mark, 20, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(glass(color, 0x66FFFFFF, 14, 1));
        t.addView(badge, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView l = label(name, 11, Color.WHITE, false);
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
        return t;
    }

    private String siteUrl(String name) {
        if (name.equals("YouTube")) return "https://m.youtube.com";
        if (name.equals("Google") || name.equals("Browser")) return "https://www.google.com";
        if (name.equals("Facebook")) return "https://m.facebook.com";
        if (name.equals("Viral Quality")) return SITE_URL;
        for (String line : prefs.getString("sites", "").split("\n")) {
            int i = line.indexOf('\t');
            if (i > 0 && line.substring(0, i).equals(name)) return line.substring(i + 1);
        }
        return null;
    }

    private void openBuiltin(String name) {
        if (name.equals("Files") || name.equals("Notepad") || name.equals("Calculator") || name.equals("System")) {
            openTool(name);
            return;
        }
        String u = siteUrl(name);
        if (u != null) openWeb(name, u);
    }

    private void toggleIcons() {
        boolean off = iconsBox.getVisibility() == View.VISIBLE;
        iconsBox.setVisibility(off ? View.GONE : View.VISIBLE);
        prefs.edit().putBoolean("iconsoff", off).apply();
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

        // lock screen (always on top): profile + password, like Windows
        lockView = new FrameLayout(this);
        lockView.setBackgroundColor(0xE6000000);
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        p.setMargins(0, 0, 0, dp(5));
        ctxMenu.addView(t, p);
    }

    private void showCtx(float x, float y) {
        if (locked) return;
        hideStart();
        ctxMenu.removeAllViews();
        addCtx("Search", () -> openSearch());
        addCtx("Calendar", () -> toggleCal());
        addCtx("Next wallpaper", () -> nextWall());
        addCtx("Custom wallpaper (QR)", () -> openWallPanel());
        addCtx("Desktop icons show / hide", () -> toggleIcons());
        addCtx("Clock show / hide", () -> toggleClock());
        addCtx("Refresh apps", () -> { loadApps(); toast("Apps refreshed"); });
        addCtx("Lock screen", () -> lockFromMenu());
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int est = dp(16) + 8 * dp(47);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) ctxMenu.getLayoutParams();
        p.leftMargin = (int) Math.max(0, Math.min(x, dm.widthPixels - dp(250)));
        p.topMargin = (int) Math.max(0, Math.min(y, dm.heightPixels - est - dp(70)));
        ctxMenu.setLayoutParams(p);
        ctxLayer.setVisibility(View.VISIBLE);
        if (ctxMenu.getChildCount() > 0) ctxMenu.getChildAt(0).requestFocus();
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
        String n = prefs.getString("uname", "Viral Quality");
        return n.length() == 0 ? "Viral Quality" : n;
    }

    private void lockFromMenu() {
        if (prefs.getString("pin", "").length() == 0) {
            toast("Set a password first (START > Account & password)");
            return;
        }
        lockNow();
    }

    private void lockNow() {
        locked = true;
        String n = userName();
        lockName.setText(n);
        lockAvatar.setText(n.substring(0, 1).toUpperCase(Locale.ENGLISH));
        lockMsg.setText(" ");
        lockEt.setText("");
        Date d = new Date();
        lockTime.setText(fBig.format(d) + "\n" + fDate.format(d));
        closeAllPanels();
        hideStart();
        lockView.setVisibility(View.VISIBLE);
        lockView.bringToFront();
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
        section(left, "WEB WINDOWS");
        addMenu(left, "YouTube", new Runnable() { public void run() { openWeb("YouTube", "https://m.youtube.com"); } });
        addMenu(left, "Google", new Runnable() { public void run() { openWeb("Google", "https://www.google.com"); } });
        addMenu(left, "Facebook", new Runnable() { public void run() { openWeb("Facebook", "https://m.facebook.com"); } });
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
        addMenu(left, "System monitor", () -> openTool("System"));
        addMenu(left, "Clean RAM", () -> cleanRam());
        section(left, "DESKTOP");
        addMenu(left, "Search", () -> openSearch());
        addMenu(left, "Calendar", () -> toggleCal());
        addMenu(left, "Desktop icons show / hide", () -> toggleIcons());
        addMenu(left, "Clock show / hide", () -> toggleClock());
        addMenu(left, "Wallpaper slideshow on / off", () -> toggleSlide());
        addMenu(left, "Accent color (next)", () -> nextAccent());
        addMenu(left, "Taskbar glass / solid", () -> toggleGlass());
        addMenu(left, "Taskbar center / left", () -> toggleAlign());
        addMenu(left, "Startup site...", () -> startupDialog());
        addMenu(left, "Lock screen now", () -> lockFromMenu());
        addMenu(left, "Account & password", () -> pinDialog());
        section(left, "SYSTEM");
        addMenu(left, "Settings", new Runnable() { public void run() { openSettings(Settings.ACTION_SETTINGS); } });
        addMenu(left, "Wi-Fi / Network", new Runnable() { public void run() { openSettings(Settings.ACTION_WIFI_SETTINGS); } });
        addMenu(left, "Display", new Runnable() { public void run() { openSettings(Settings.ACTION_DISPLAY_SETTINGS); } });
        addMenu(left, "Apps manager", new Runnable() { public void run() { openSettings(Settings.ACTION_APPLICATION_SETTINGS); } });
        addMenu(left, "Custom wallpaper (QR)", new Runnable() { public void run() { openWallPanel(); } });
        addMenu(left, "Next wallpaper", new Runnable() { public void run() { nextWall(); } });
        addMenu(left, "Floating volume button", new Runnable() { public void run() { ensureOverlay(true); } });
        addMenu(left, "Refresh apps", new Runnable() { public void run() { loadApps(); toast("Apps refreshed"); } });
        p.addView(sv, new LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.MATCH_PARENT));

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
        closeWallPanel();
        hideVol();
        pinSlot = slot;
        if (slot >= 0) {
            pinText.setText("Choose an app for taskbar slot " + (slot + 1));
            pinBanner.setVisibility(View.VISIBLE);
        } else {
            pinBanner.setVisibility(View.GONE);
        }
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
        btns.addView(next, tbLp());

        TextView rm = btn("Remove custom", false);
        rm.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
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
        hideStart();
        hideVol();
        if (server == null) {
            wallKey = String.valueOf(1000 + new Random().nextInt(9000));
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            server = new WallServer(customFile, wallKey, dm.widthPixels, dm.heightPixels,
                    new WallServer.Listener() {
                        public void onUploaded() {
                            runOnUiThread(new Runnable() {
                                public void run() {
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
