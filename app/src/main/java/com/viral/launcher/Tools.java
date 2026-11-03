package com.viral.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.StrictMode;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Small built-in tools that live inside launcher windows. */
final class Tools {
    private Tools() {}

    interface Done { void done(long mb); }

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable box(Context c, int fill, int stroke, int r, int sw) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, r));
        if (sw > 0) g.setStroke(dp(c, sw), stroke);
        return g;
    }

    static TextView txt(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView button(Context c, String text, int accent, int sp, boolean strong) {
        TextView t = txt(c, text, sp, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, box(c, accent, 0xFFFFFFFF, 10, 2));
        sl.addState(new int[]{android.R.attr.state_pressed}, box(c, 0xFF8A0F18, 0xFFFFFFFF, 10, 2));
        sl.addState(new int[]{android.R.attr.state_hovered}, box(c, strong ? accent : 0x55FFFFFF, 0xAAFFFFFF, 10, 1));
        sl.addState(new int[]{}, box(c, strong ? ((accent & 0xFFFFFF) | 0x99000000) : 0x2AFFFFFF, 0x33FFFFFF, 10, 1));
        t.setBackground(sl);
        return t;
    }

    // ---------------------------------------------------------------- Notepad

    static View notepad(Context c, final SharedPreferences prefs) {
        EditText e = new EditText(c);
        e.setBackgroundColor(0xFF14141A);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0x77FFFFFF);
        e.setHint("Write notes here. Saved automatically.");
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setText(prefs.getString("note", ""));
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            public void onTextChanged(CharSequence s, int a, int b, int d) {}
            public void afterTextChanged(Editable s) {
                prefs.edit().putString("note", s.toString()).apply();
            }
        });
        return e;
    }

    // ------------------------------------------------------------- Calculator

    private static final class Calc {
        StringBuilder cur = new StringBuilder("0");
        double acc = 0;
        char op = 0;
        boolean fresh = true;
        final TextView disp;

        Calc(TextView d) { disp = d; }

        static String fmt(double d) {
            if (Double.isNaN(d) || Double.isInfinite(d)) return "Error";
            String s = java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
            if (s.length() > 14) s = Double.toString(d);
            return s;
        }

        double val() {
            try { return Double.parseDouble(cur.toString()); } catch (Exception e) { return 0; }
        }

        double apply(double a, char o, double b) {
            switch (o) {
                case '+': return a + b;
                case '-': return a - b;
                case '*': return a * b;
                case '/': return b == 0 ? Double.NaN : a / b;
                default: return b;
            }
        }

        void show() { disp.setText(cur.toString()); }

        void press(String k) {
            if (cur.toString().equals("Error") && !k.equals("C")) return;
            char ch = k.charAt(0);
            if (k.length() == 1 && ch >= '0' && ch <= '9') {
                if (fresh) { cur.setLength(0); fresh = false; }
                if (cur.toString().equals("0")) cur.setLength(0);
                if (cur.length() < 14) cur.append(ch);
            } else if (k.equals(".")) {
                if (fresh) { cur.setLength(0); cur.append("0"); fresh = false; }
                if (cur.indexOf(".") < 0) cur.append(".");
            } else if (k.equals("C")) {
                cur.setLength(0); cur.append("0"); acc = 0; op = 0; fresh = true;
            } else if (k.equals("BK")) {
                if (!fresh && cur.length() > 1) cur.setLength(cur.length() - 1);
                else { cur.setLength(0); cur.append("0"); }
                if (cur.toString().equals("-")) { cur.setLength(0); cur.append("0"); }
            } else if (k.equals("+/-")) {
                if (!cur.toString().equals("0")) {
                    if (cur.charAt(0) == '-') cur.deleteCharAt(0); else cur.insert(0, '-');
                }
            } else if (k.equals("%")) {
                String s = fmt(val() / 100.0);
                cur.setLength(0); cur.append(s); fresh = true;
            } else if (k.equals("=")) {
                if (op != 0) {
                    acc = apply(acc, op, val());
                    String s = fmt(acc);
                    cur.setLength(0); cur.append(s);
                    op = 0; fresh = true;
                }
            } else { // + - * /
                if (op != 0 && !fresh) acc = apply(acc, op, val());
                else if (op == 0) acc = val();
                String s = fmt(acc);
                cur.setLength(0); cur.append(s);
                op = ch; fresh = true;
            }
            show();
        }
    }

    static View calculator(final Context c, final int accent) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF14141A);
        col.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 10));

        TextView disp = txt(c, "0", 34, Color.WHITE, false);
        disp.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        disp.setSingleLine(true);
        disp.setPadding(dp(c, 10), 0, dp(c, 10), 0);
        col.addView(disp, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.1f));

        final Calc calc = new Calc(disp);
        String[][] rows = {
                {"C", "BK", "%", "/"},
                {"7", "8", "9", "*"},
                {"4", "5", "6", "-"},
                {"1", "2", "3", "+"},
                {"+/-", "0", ".", "="}};
        for (String[] r : rows) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (final String k : r) {
                boolean op = k.equals("/") || k.equals("*") || k.equals("-") || k.equals("+") || k.equals("=");
                String label = k.equals("/") ? "÷" : k.equals("*") ? "×" : k.equals("-") ? "−"
                        : k.equals("BK") ? "⌫" : k;
                TextView b = button(c, label, accent, 20, op);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { calc.press(k); }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                lp.setMargins(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3));
                row.addView(b, lp);
            }
            col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        return col;
    }

    // ---------------------------------------------------------------- System

    private static LinearLayout bar(Context c, int accent) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setBackground(box(c, 0x33FFFFFF, 0, 5, 0));
        View fill = new View(c);
        fill.setBackground(box(c, accent, 0, 5, 0));
        b.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.01f));
        b.addView(new View(c), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.99f));
        return b;
    }

    private static void setBar(LinearLayout b, double f) {
        f = Math.max(0.01, Math.min(1.0, f));
        b.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) f));
        b.getChildAt(1).setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (float) (1 - f)));
    }

    static String human(long b) {
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        if (v < 1024) return String.format(Locale.ENGLISH, "%.0f KB", v);
        v /= 1024;
        if (v < 1024) return String.format(Locale.ENGLISH, "%.1f MB", v);
        v /= 1024;
        return String.format(Locale.ENGLISH, "%.1f GB", v);
    }

    static void cleanRam(final Activity a, final Done d) {
        new Thread(new Runnable() {
            public void run() {
                long freed = 0;
                try {
                    ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
                    ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(m);
                    long before = m.availMem;
                    for (ApplicationInfo ai : a.getPackageManager().getInstalledApplications(0)) {
                        if (ai.packageName.equals(a.getPackageName())) continue;
                        try {
                            am.killBackgroundProcesses(ai.packageName);
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                    Thread.sleep(900);
                    am.getMemoryInfo(m);
                    freed = Math.max(0, (m.availMem - before) / (1024 * 1024));
                } catch (Throwable t) {
                    // ignore
                }
                final long f = freed;
                a.runOnUiThread(new Runnable() {
                    public void run() { d.done(f); }
                });
            }
        }).start();
    }

    interface WinSource {
        List<String> names();
        void close(String name);
    }

    static View monitor(final Activity a, final int accent, final WinSource ws) {
        final LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF14141A);
        col.setPadding(dp(a, 18), dp(a, 14), dp(a, 18), dp(a, 14));

        final TextView ramT = txt(a, "", 15, Color.WHITE, false);
        final TextView stoT = txt(a, "", 15, Color.WHITE, false);
        final TextView netT = txt(a, "", 15, Color.WHITE, false);
        final TextView upT = txt(a, "", 15, Color.WHITE, false);
        final LinearLayout ramB = bar(a, accent);
        final LinearLayout stoB = bar(a, accent);

        col.addView(head(a, "MEMORY (RAM)", accent));
        col.addView(ramT);
        col.addView(ramB, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 10)));
        col.addView(head(a, "STORAGE", accent));
        col.addView(stoT);
        col.addView(stoB, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 10)));
        col.addView(head(a, "NETWORK", accent));
        col.addView(netT);
        col.addView(head(a, "UPTIME", accent));
        col.addView(upT);
        col.addView(head(a, "OPEN WINDOWS", accent));
        final LinearLayout winBox = new LinearLayout(a);
        winBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(winBox);

        final Runnable[] loop = new Runnable[1];
        final Runnable update = new Runnable() {
            public void run() {
                try {
                    ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
                    ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(m);
                    long used = m.totalMem - m.availMem;
                    ramT.setText(human(used) + " used of " + human(m.totalMem));
                    setBar(ramB, (double) used / m.totalMem);

                    StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
                    long tot = sf.getBlockCountLong() * sf.getBlockSizeLong();
                    long fr = sf.getAvailableBlocksLong() * sf.getBlockSizeLong();
                    stoT.setText(human(tot - fr) + " used of " + human(tot));
                    setBar(stoB, (double) (tot - fr) / tot);

                    ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
                    NetworkInfo ni = cm.getActiveNetworkInfo();
                    netT.setText(ni != null && ni.isConnected() ? "Online via " + ni.getTypeName() : "Offline");

                    long s = SystemClock.elapsedRealtime() / 1000;
                    upT.setText((s / 3600) + " h " + ((s / 60) % 60) + " min");

                    winBox.removeAllViews();
                    List<String> names = ws.names();
                    if (names.isEmpty()) {
                        winBox.addView(txt(a, "No open windows", 14, 0x99FFFFFF, false));
                    }
                    for (final String nm : names) {
                        LinearLayout row = new LinearLayout(a);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        TextView nt = txt(a, nm, 15, Color.WHITE, false);
                        row.addView(nt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                        TextView cb = button(a, "Close", accent, 12, false);
                        cb.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) { ws.close(nm); }
                        });
                        row.addView(cb, new LinearLayout.LayoutParams(dp(a, 70), dp(a, 34)));
                        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        rl.topMargin = dp(a, 4);
                        winBox.addView(row, rl);
                    }
                } catch (Throwable t) {
                    // ignore
                }
                if (col.isAttachedToWindow()) col.postDelayed(loop[0], 2000);
            }
        };
        loop[0] = update;

        final TextView clean = button(a, "Clean RAM", accent, 16, true);
        clean.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(a, "Cleaning RAM...", Toast.LENGTH_SHORT).show();
                cleanRam(a, new Done() {
                    public void done(long mb) {
                        Toast.makeText(a, "Freed about " + mb + " MB", Toast.LENGTH_SHORT).show();
                        update.run();
                    }
                });
            }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(a, 180), dp(a, 46));
        cl.topMargin = dp(a, 18);
        col.addView(clean, cl);

        col.post(update);
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(0xFF14141A);
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private static TextView head(Context c, String s, int accent) {
        TextView t = txt(c, s, 11, accent, true);
        t.setLetterSpacing(0.2f);
        t.setPadding(0, dp(c, 14), 0, dp(c, 4));
        return t;
    }

    // ------------------------------------------------------------ File manager

    private static final class FileBrowser extends LinearLayout {
        final Activity a;
        final int accent;
        File cwd;
        final List<File> items = new ArrayList<>();
        final TextView path, msg;
        final BaseAdapter ad;

        FileBrowser(final Activity act, int accentColor) {
            super(act);
            a = act;
            accent = accentColor;
            cwd = Environment.getExternalStorageDirectory();
            setOrientation(VERTICAL);
            setBackgroundColor(0xFF14141A);

            LinearLayout top = new LinearLayout(a);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setPadding(dp(a, 8), dp(a, 6), dp(a, 8), dp(a, 6));
            addView(top, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 50)));

            TextView up = button(a, "↑ Up", accent, 13, false);
            up.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    File p = cwd.getParentFile();
                    if (p != null) { cwd = p; reload(); }
                }
            });
            top.addView(up, new LayoutParams(dp(a, 70), dp(a, 36)));

            TextView home = button(a, "Home", accent, 13, false);
            home.setOnClickListener(new OnClickListener() {
                public void onClick(View v) { cwd = Environment.getExternalStorageDirectory(); reload(); }
            });
            LayoutParams hl = new LayoutParams(dp(a, 64), dp(a, 36));
            hl.leftMargin = dp(a, 6);
            top.addView(home, hl);

            TextView ref = button(a, "Refresh", accent, 13, false);
            ref.setOnClickListener(new OnClickListener() {
                public void onClick(View v) { reload(); }
            });
            LayoutParams rl = new LayoutParams(dp(a, 74), dp(a, 36));
            rl.leftMargin = dp(a, 6);
            top.addView(ref, rl);

            path = txt(a, "", 12, 0xCCFFFFFF, false);
            path.setSingleLine(true);
            path.setEllipsize(TextUtils.TruncateAt.START);
            LayoutParams pl = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            pl.leftMargin = dp(a, 10);
            top.addView(path, pl);

            msg = txt(a, "", 13, 0xFFFFB4B4, false);
            msg.setPadding(dp(a, 14), dp(a, 6), dp(a, 14), dp(a, 6));
            addView(msg);

            ListView list = new ListView(a);
            list.setDivider(null);
            list.setSelector(box(a, (accent & 0xFFFFFF) | 0x55000000, accent, 8, 2));
            ad = new BaseAdapter() {
                public int getCount() { return items.size(); }
                public Object getItem(int i) { return items.get(i); }
                public long getItemId(int i) { return i; }
                public View getView(int i, View cv, ViewGroup parent) {
                    TextView t = cv instanceof TextView ? (TextView) cv : new TextView(a);
                    File f = items.get(i);
                    t.setTextColor(f.isDirectory() ? 0xFFFFFFFF : 0xDDFFFFFF);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    t.setSingleLine(true);
                    t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    t.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
                    t.setText(f.isDirectory() ? "▸ " + f.getName()
                            : "• " + f.getName() + "   (" + human(f.length()) + ")");
                    return t;
                }
            };
            list.setAdapter(ad);
            list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> p, View v, int i, long id) {
                    File f = items.get(i);
                    if (f.isDirectory()) { cwd = f; reload(); } else open(f);
                }
            });
            list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                public boolean onItemLongClick(AdapterView<?> p, View v, int i, long id) {
                    confirmDelete(items.get(i));
                    return true;
                }
            });
            addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            reload();
        }

        void reload() {
            path.setText(cwd.getPath());
            items.clear();
            if (Build.VERSION.SDK_INT >= 23
                    && a.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                a.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, 11);
                msg.setText("Allow storage permission, then press Refresh.");
                msg.setVisibility(View.VISIBLE);
                ad.notifyDataSetChanged();
                return;
            }
            File[] fs = cwd.listFiles();
            if (fs == null) {
                msg.setText("Cannot read this folder.");
                msg.setVisibility(View.VISIBLE);
            } else {
                Collections.addAll(items, fs);
                Collections.sort(items, new java.util.Comparator<File>() {
                    public int compare(File x, File y) {
                        if (x.isDirectory() != y.isDirectory()) return x.isDirectory() ? -1 : 1;
                        return x.getName().compareToIgnoreCase(y.getName());
                    }
                });
                if (items.isEmpty()) {
                    msg.setText("Empty folder.");
                    msg.setVisibility(View.VISIBLE);
                } else {
                    msg.setVisibility(View.GONE);
                }
            }
            ad.notifyDataSetChanged();
        }

        void open(File f) {
            try {
                try {
                    StrictMode.class.getMethod("disableDeathOnFileUriExposure").invoke(null);
                } catch (Throwable t) {
                    // ignore
                }
                String name = f.getName();
                int dot = name.lastIndexOf('.');
                String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ENGLISH) : "";
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (ext.equals("apk")) mime = "application/vnd.android.package-archive";
                if (mime == null) mime = "*/*";
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.fromFile(f), mime);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
            } catch (Throwable t) {
                Toast.makeText(a, "No app can open this file", Toast.LENGTH_SHORT).show();
            }
        }

        void confirmDelete(final File f) {
            new AlertDialog.Builder(a)
                    .setMessage("Delete \"" + f.getName() + "\"?")
                    .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            boolean ok = f.delete();
                            Toast.makeText(a, ok ? "Deleted" : "Could not delete (folder not empty?)",
                                    Toast.LENGTH_SHORT).show();
                            reload();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    static View files(Activity a, int accent) {
        return new FileBrowser(a, accent);
    }

    // -------------------------------------------------------------- Clipboard

    static final List<String> clips = new ArrayList<>();

    static void addClip(String t) {
        if (t == null) return;
        t = t.trim();
        if (t.length() == 0) return;
        clips.remove(t);
        clips.add(0, t);
        while (clips.size() > 15) clips.remove(clips.size() - 1);
    }

    static View clipboard(final Activity a, final int accent) {
        final LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF14141A);

        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 8));
        TextView hint = txt(a, "Copied text appears here. Click to copy it again.", 12, 0xAAFFFFFF, false);
        top.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = button(a, "Clear", accent, 13, false);
        top.addView(clear, new LinearLayout.LayoutParams(dp(a, 70), dp(a, 34)));
        col.addView(top);

        ListView list = new ListView(a);
        list.setDivider(null);
        list.setSelector(box(a, (accent & 0xFFFFFF) | 0x55000000, accent, 8, 2));
        final BaseAdapter ad = new BaseAdapter() {
            public int getCount() { return clips.size(); }
            public Object getItem(int i) { return clips.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View cv, ViewGroup p) {
                TextView t = cv instanceof TextView ? (TextView) cv : new TextView(a);
                t.setTextColor(Color.WHITE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                t.setMaxLines(3);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
                t.setText(clips.get(i));
                return t;
            }
        };
        list.setAdapter(ad);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int i, long id) {
                try {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("clip", clips.get(i)));
                    Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    // ignore
                }
            }
        });
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                clips.clear();
                ad.notifyDataSetChanged();
            }
        });
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final Runnable[] loop = new Runnable[1];
        loop[0] = new Runnable() {
            public void run() {
                ad.notifyDataSetChanged();
                if (col.isAttachedToWindow()) col.postDelayed(loop[0], 1500);
            }
        };
        col.postDelayed(loop[0], 1500);
        return col;
    }

    // ------------------------------------------------------- Timer / stopwatch

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static boolean swRun = false;
    private static long swStart = 0, swAcc = 0;
    private static boolean cdRun = false;
    private static long cdEnd = 0;
    private static Context appCtx;
    private static Ringtone ring;

    private static final Runnable CD_CHECK = new Runnable() {
        public void run() {
            if (!cdRun) return;
            if (System.currentTimeMillis() + 80 < cdEnd) {
                H.postDelayed(this, 200);
                return;
            }
            cdRun = false;
            try {
                if (appCtx != null) {
                    Toast.makeText(appCtx, "Timer finished", Toast.LENGTH_LONG).show();
                    ring = RingtoneManager.getRingtone(appCtx,
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
                    if (ring != null) {
                        ring.play();
                        H.postDelayed(new Runnable() {
                            public void run() { stopRing(); }
                        }, 15000);
                    }
                }
            } catch (Throwable t) {
                // ignore
            }
        }
    };

    private static void stopRing() {
        try {
            if (ring != null && ring.isPlaying()) ring.stop();
        } catch (Throwable t) {
            // ignore
        }
    }

    private static String clock(long ms, boolean tenths) {
        long t = Math.max(0, ms);
        long m = t / 60000, sec = (t / 1000) % 60;
        String r = String.format(Locale.ENGLISH, "%02d:%02d", m, sec);
        if (tenths) r += "." + ((t / 100) % 10);
        return r;
    }

    private static void startCountdown(Context c, long ms) {
        appCtx = c.getApplicationContext();
        stopRing();
        cdEnd = System.currentTimeMillis() + ms;
        cdRun = true;
        H.removeCallbacks(CD_CHECK);
        H.postDelayed(CD_CHECK, 200);
    }

    static View timer(final Activity a, final int accent) {
        final LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF14141A);
        col.setPadding(dp(a, 18), dp(a, 10), dp(a, 18), dp(a, 14));

        col.addView(head(a, "STOPWATCH", accent));
        final TextView swT = txt(a, "00:00.0", 36, Color.WHITE, false);
        col.addView(swT);
        LinearLayout r1 = new LinearLayout(a);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        final TextView swGo = button(a, swRun ? "Stop" : "Start", accent, 15, true);
        swGo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (swRun) {
                    swAcc += System.currentTimeMillis() - swStart;
                    swRun = false;
                    swGo.setText("Start");
                } else {
                    swStart = System.currentTimeMillis();
                    swRun = true;
                    swGo.setText("Stop");
                }
            }
        });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(dp(a, 100), dp(a, 42));
        r1.addView(swGo, b1);
        TextView swReset = button(a, "Reset", accent, 15, false);
        swReset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                swRun = false;
                swAcc = 0;
                swGo.setText("Start");
            }
        });
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(dp(a, 100), dp(a, 42));
        b2.leftMargin = dp(a, 10);
        r1.addView(swReset, b2);
        LinearLayout.LayoutParams r1l = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1l.topMargin = dp(a, 8);
        col.addView(r1, r1l);

        col.addView(head(a, "COUNTDOWN TIMER", accent));
        final TextView cdT = txt(a, "00:00", 36, Color.WHITE, false);
        col.addView(cdT);
        LinearLayout r2 = new LinearLayout(a);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        final int[] presets = {1, 5, 10};
        for (final int pm : presets) {
            TextView pb = button(a, pm + " min", accent, 14, false);
            pb.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { startCountdown(a, pm * 60000L); }
            });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(a, 84), dp(a, 40));
            pl.rightMargin = dp(a, 8);
            r2.addView(pb, pl);
        }
        LinearLayout.LayoutParams r2l = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2l.topMargin = dp(a, 8);
        col.addView(r2, r2l);

        LinearLayout r3 = new LinearLayout(a);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        r3.setGravity(Gravity.CENTER_VERTICAL);
        final EditText mins = new EditText(a);
        mins.setSingleLine(true);
        mins.setInputType(InputType.TYPE_CLASS_NUMBER);
        mins.setHint("minutes");
        mins.setTextColor(Color.WHITE);
        mins.setHintTextColor(0x88FFFFFF);
        mins.setBackground(box(a, 0xFF26262E, 0x55FFFFFF, 10, 1));
        mins.setPadding(dp(a, 12), 0, dp(a, 12), 0);
        r3.addView(mins, new LinearLayout.LayoutParams(dp(a, 110), dp(a, 40)));
        TextView go = button(a, "Start", accent, 14, true);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    int m = Integer.parseInt(mins.getText().toString().trim());
                    if (m >= 1 && m <= 600) startCountdown(a, m * 60000L);
                } catch (Exception e) {
                    Toast.makeText(a, "Type minutes (1-600)", Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(a, 80), dp(a, 40));
        gl.leftMargin = dp(a, 8);
        r3.addView(go, gl);
        TextView cancel = button(a, "Cancel", accent, 14, false);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                cdRun = false;
                stopRing();
            }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(a, 80), dp(a, 40));
        cl.leftMargin = dp(a, 8);
        r3.addView(cancel, cl);
        LinearLayout.LayoutParams r3l = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r3l.topMargin = dp(a, 8);
        col.addView(r3, r3l);

        final Runnable[] loop = new Runnable[1];
        loop[0] = new Runnable() {
            public void run() {
                long sw = swAcc + (swRun ? System.currentTimeMillis() - swStart : 0);
                swT.setText(clock(sw, true));
                cdT.setText(cdRun ? clock(cdEnd - System.currentTimeMillis(), false) : "00:00");
                if (col.isAttachedToWindow()) col.postDelayed(loop[0], 100);
            }
        };
        col.post(loop[0]);
        return col;
    }
}
