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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.VideoView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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

    interface Opener { boolean open(File f); }

    private static File fileClip;
    private static boolean fileClipMove;

    static boolean isImage(String n) {
        n = n.toLowerCase(Locale.ENGLISH);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".gif")
                || n.endsWith(".webp") || n.endsWith(".bmp");
    }

    static boolean isMedia(String n) {
        n = n.toLowerCase(Locale.ENGLISH);
        String[] ex = {".mp4", ".mkv", ".avi", ".webm", ".3gp", ".mov", ".flv", ".m4v", ".ts",
                ".mp3", ".wav", ".ogg", ".m4a", ".aac", ".flac"};
        for (String e : ex) if (n.endsWith(e)) return true;
        return false;
    }

    private static void copyRec(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyRec(k, new File(dst, k.getName()));
        } else {
            InputStream in = new FileInputStream(src);
            OutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
        }
    }

    private static void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        f.delete();
    }

    private static final class FileBrowser extends LinearLayout {
        final Activity a;
        final int accent;
        final Opener opener;
        File cwd;
        final List<File> items = new ArrayList<>();
        final TextView path, msg, paste;
        final BaseAdapter ad;

        FileBrowser(final Activity act, int accentColor, Opener op) {
            super(act);
            a = act;
            accent = accentColor;
            opener = op;
            cwd = Environment.getExternalStorageDirectory();
            setOrientation(VERTICAL);
            setBackgroundColor(0xFF14141A);

            LinearLayout top = new LinearLayout(a);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setPadding(dp(a, 8), dp(a, 6), dp(a, 8), dp(a, 2));
            addView(top, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 46)));

            TextView up = button(a, "↑ Up", accent, 12, false);
            up.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    File p = cwd.getParentFile();
                    if (p != null) { cwd = p; reload(); }
                }
            });
            top.addView(up, new LayoutParams(dp(a, 62), dp(a, 34)));
            top.addView(small("Home", new OnClickListener() {
                public void onClick(View v) { cwd = Environment.getExternalStorageDirectory(); reload(); }
            }, 58));
            top.addView(small("Refresh", new OnClickListener() {
                public void onClick(View v) { reload(); }
            }, 66));
            top.addView(small("+ Folder", new OnClickListener() {
                public void onClick(View v) { newFolder(); }
            }, 72));
            paste = small("Paste here", new OnClickListener() {
                public void onClick(View v) { doPaste(); }
            }, 86);
            top.addView(paste);

            path = txt(a, "", 12, 0xCCFFFFFF, false);
            path.setSingleLine(true);
            path.setEllipsize(TextUtils.TruncateAt.START);
            path.setPadding(dp(a, 12), dp(a, 2), dp(a, 12), dp(a, 2));
            addView(path);

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
                    actions(items.get(i));
                    return true;
                }
            });
            addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            reload();
        }

        TextView small(String label, OnClickListener l, int w) {
            TextView b = button(a, label, accent, 12, false);
            b.setOnClickListener(l);
            LayoutParams lp = new LayoutParams(dp(a, w), dp(a, 34));
            lp.leftMargin = dp(a, 5);
            b.setLayoutParams(lp);
            return b;
        }

        void toast(String m) { Toast.makeText(a, m, Toast.LENGTH_SHORT).show(); }

        void reload() {
            path.setText(cwd.getPath());
            paste.setVisibility(fileClip != null ? View.VISIBLE : View.GONE);
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
            if (opener != null && opener.open(f)) return;
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
                toast("No app can open this file");
            }
        }

        void actions(final File f) {
            final String[] names = {"Open", "Rename", "Copy", "Move", "Delete"};
            new AlertDialog.Builder(a)
                    .setTitle(f.getName())
                    .setItems(names, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) {
                            if (which == 0) {
                                if (f.isDirectory()) { cwd = f; reload(); } else open(f);
                            } else if (which == 1) {
                                rename(f);
                            } else if (which == 2 || which == 3) {
                                fileClip = f;
                                fileClipMove = which == 3;
                                paste.setVisibility(View.VISIBLE);
                                toast("Open the target folder and press \"Paste here\"");
                            } else {
                                confirmDelete(f);
                            }
                        }
                    })
                    .show();
        }

        void rename(final File f) {
            final EditText et = new EditText(a);
            et.setSingleLine(true);
            et.setText(f.getName());
            new AlertDialog.Builder(a)
                    .setTitle("Rename")
                    .setView(et)
                    .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            String n = et.getText().toString().trim();
                            if (n.length() == 0 || n.contains("/")) { toast("Invalid name"); return; }
                            boolean ok = f.renameTo(new File(f.getParentFile(), n));
                            toast(ok ? "Renamed" : "Could not rename");
                            reload();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }

        void newFolder() {
            final EditText et = new EditText(a);
            et.setSingleLine(true);
            et.setHint("Folder name");
            new AlertDialog.Builder(a)
                    .setTitle("New folder")
                    .setView(et)
                    .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            String n = et.getText().toString().trim();
                            if (n.length() == 0 || n.contains("/")) { toast("Invalid name"); return; }
                            boolean ok = new File(cwd, n).mkdirs();
                            toast(ok ? "Folder created" : "Could not create");
                            reload();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }

        void doPaste() {
            final File src = fileClip;
            if (src == null) return;
            if (!src.exists()) { toast("Source file is gone"); fileClip = null; reload(); return; }
            String name = src.getName();
            File t = new File(cwd, name);
            int k = 1;
            while (t.exists()) {
                t = new File(cwd, "copy" + (k == 1 ? "" : k) + "_" + name);
                k++;
            }
            if (src.isDirectory() && t.getPath().startsWith(src.getPath() + "/")) {
                toast("Cannot paste a folder into itself");
                return;
            }
            final File dst = t;
            final boolean move = fileClipMove;
            toast(move ? "Moving..." : "Copying...");
            new Thread(new Runnable() {
                public void run() {
                    boolean ok = true;
                    try {
                        if (move && src.renameTo(dst)) {
                            // moved
                        } else {
                            copyRec(src, dst);
                            if (move) deleteRec(src);
                        }
                    } catch (Throwable e) {
                        ok = false;
                    }
                    final boolean fok = ok;
                    a.runOnUiThread(new Runnable() {
                        public void run() {
                            if (move && fok) fileClip = null;
                            toast(fok ? "Done" : "Failed");
                            reload();
                        }
                    });
                }
            }).start();
        }

        void confirmDelete(final File f) {
            new AlertDialog.Builder(a)
                    .setMessage("Delete \"" + f.getName() + "\"" + (f.isDirectory() ? " and everything inside?" : "?"))
                    .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            deleteRec(f);
                            toast(f.exists() ? "Could not delete" : "Deleted");
                            reload();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    static View files(Activity a, int accent, Opener op) {
        return new FileBrowser(a, accent, op);
    }

    // ------------------------------------------------------------ Photo viewer

    static Bitmap decode(File f, int max) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), o2);
        } catch (Throwable t) {
            return null;
        }
    }

    static List<File> picturesIn(File dir) {
        List<File> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) if (f.isFile() && isImage(f.getName())) out.add(f);
        }
        Collections.sort(out, new java.util.Comparator<File>() {
            public int compare(File x, File y) { return x.getName().compareToIgnoreCase(y.getName()); }
        });
        return out;
    }

    static View imageViewer(final Activity a, final int accent, File start) {
        final List<File> imgs = new ArrayList<>();
        if (start != null) {
            imgs.addAll(picturesIn(start.getParentFile()));
        } else {
            File pics = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            imgs.addAll(picturesIn(pics));
            imgs.addAll(picturesIn(new File(pics, "Screenshots")));
            imgs.addAll(picturesIn(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)));
            imgs.addAll(picturesIn(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")));
        }
        if (imgs.isEmpty()) {
            TextView t = txt(a, "No pictures found.\nPut pictures in the Pictures folder, or open one from the Files tool.", 15, 0xCCFFFFFF, false);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(0xFF14141A);
            return t;
        }
        final LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF000000);
        final ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        col.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF14141A);
        bar.setPadding(dp(a, 8), dp(a, 6), dp(a, 8), dp(a, 6));
        final TextView prev = button(a, "‹", accent, 20, false);
        final TextView next = button(a, "›", accent, 20, false);
        final TextView nameT = txt(a, "", 13, Color.WHITE, false);
        nameT.setSingleLine(true);
        nameT.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        nameT.setGravity(Gravity.CENTER);
        final TextView show = button(a, "Slideshow", accent, 12, false);
        bar.addView(prev, new LinearLayout.LayoutParams(dp(a, 46), dp(a, 36)));
        bar.addView(nameT, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(next, new LinearLayout.LayoutParams(dp(a, 46), dp(a, 36)));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(a, 90), dp(a, 36));
        sl.leftMargin = dp(a, 6);
        bar.addView(show, sl);
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 48)));

        final int[] idx = {0};
        if (start != null) {
            for (int i = 0; i < imgs.size(); i++) if (imgs.get(i).equals(start)) idx[0] = i;
        }
        final Runnable showCur = new Runnable() {
            public void run() {
                File f = imgs.get(idx[0]);
                Bitmap b = decode(f, 1600);
                if (b != null) iv.setImageBitmap(b);
                nameT.setText((idx[0] + 1) + "/" + imgs.size() + "   " + f.getName());
            }
        };
        prev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { idx[0] = (idx[0] - 1 + imgs.size()) % imgs.size(); showCur.run(); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { idx[0] = (idx[0] + 1) % imgs.size(); showCur.run(); }
        });
        iv.setFocusable(true);
        iv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { idx[0] = (idx[0] + 1) % imgs.size(); showCur.run(); }
        });
        iv.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int kc, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (kc == KeyEvent.KEYCODE_DPAD_RIGHT || kc == KeyEvent.KEYCODE_PAGE_DOWN) {
                    idx[0] = (idx[0] + 1) % imgs.size(); showCur.run(); return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_PAGE_UP) {
                    idx[0] = (idx[0] - 1 + imgs.size()) % imgs.size(); showCur.run(); return true;
                }
                return false;
            }
        });
        final boolean[] playing = {false};
        final Runnable[] loop = new Runnable[1];
        loop[0] = new Runnable() {
            public void run() {
                if (!playing[0] || !col.isAttachedToWindow()) return;
                idx[0] = (idx[0] + 1) % imgs.size();
                showCur.run();
                col.postDelayed(loop[0], 4000);
            }
        };
        show.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                playing[0] = !playing[0];
                show.setText(playing[0] ? "Stop" : "Slideshow");
                if (playing[0]) col.postDelayed(loop[0], 4000);
            }
        });
        col.post(showCur);
        return col;
    }

    // ------------------------------------------------------------ Player

    static View player(final Activity a, final int accent, File f) {
        final LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF000000);
        FrameLayout box = new FrameLayout(a);
        final VideoView vv = new VideoView(a);
        box.addView(vv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        col.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF14141A);
        bar.setPadding(dp(a, 8), dp(a, 6), dp(a, 8), dp(a, 6));
        final TextView play = button(a, "Pause", accent, 12, true);
        TextView back = button(a, "-10s", accent, 12, false);
        TextView fwd = button(a, "+10s", accent, 12, false);
        final TextView tm = txt(a, "0:00", 12, Color.WHITE, false);
        final SeekBar seek = new SeekBar(a);
        bar.addView(play, new LinearLayout.LayoutParams(dp(a, 64), dp(a, 34)));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(a, 52), dp(a, 34));
        bl.leftMargin = dp(a, 5);
        bar.addView(back, bl);
        bar.addView(fwd, bl);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sl.leftMargin = dp(a, 8);
        bar.addView(seek, sl);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = dp(a, 8);
        bar.addView(tm, tl);
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 48)));

        vv.setVideoPath(f.getPath());
        vv.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
            public void onPrepared(android.media.MediaPlayer mp) {
                seek.setMax(Math.max(1, vv.getDuration()));
                vv.start();
            }
        });
        vv.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
            public boolean onError(android.media.MediaPlayer mp, int w, int x) {
                Toast.makeText(a, "This file cannot be played here", Toast.LENGTH_LONG).show();
                return true;
            }
        });
        vv.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
            public void onCompletion(android.media.MediaPlayer mp) { play.setText("Play"); }
        });
        View.OnClickListener toggle = new View.OnClickListener() {
            public void onClick(View v) {
                if (vv.isPlaying()) { vv.pause(); play.setText("Play"); }
                else { vv.start(); play.setText("Pause"); }
            }
        };
        play.setOnClickListener(toggle);
        vv.setOnClickListener(toggle);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { vv.seekTo(Math.max(0, vv.getCurrentPosition() - 10000)); }
        });
        fwd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { vv.seekTo(Math.min(vv.getDuration(), vv.getCurrentPosition() + 10000)); }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) vv.seekTo(p);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        final Runnable[] loop = new Runnable[1];
        loop[0] = new Runnable() {
            public void run() {
                try {
                    int p = vv.getCurrentPosition(), d = vv.getDuration();
                    seek.setProgress(p);
                    tm.setText(clock(p, false) + " / " + clock(Math.max(0, d), false));
                } catch (Throwable t) {
                    // ignore
                }
                if (col.isAttachedToWindow()) col.postDelayed(loop[0], 500);
            }
        };
        col.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {}
            public void onViewDetachedFromWindow(View v) {
                try { vv.stopPlayback(); } catch (Throwable t) { /* ignore */ }
            }
        });
        col.postDelayed(loop[0], 500);
        return col;
    }

    // ------------------------------------------------------------ Speed test

    private static boolean speedRunning = false;

    static View speedTest(final Activity a, final int accent) {
        final LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackgroundColor(0xFF14141A);
        col.setPadding(dp(a, 20), dp(a, 24), dp(a, 20), dp(a, 20));
        col.addView(head(a, "INTERNET SPEED (DOWNLOAD)", accent));
        final TextView big = txt(a, "-- Mbps", 44, Color.WHITE, true);
        col.addView(big);
        final TextView st = txt(a, "Press Start to test", 14, 0xCCFFFFFF, false);
        col.addView(st);
        final LinearLayout b = bar(a, accent);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(a, 300), dp(a, 10));
        bl.topMargin = dp(a, 14);
        col.addView(b, bl);
        final TextView go = button(a, "Start", accent, 16, true);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(a, 160), dp(a, 46));
        gl.topMargin = dp(a, 18);
        col.addView(go, gl);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (speedRunning) return;
                speedRunning = true;
                go.setText("Testing...");
                st.setText("Connecting...");
                new Thread(new Runnable() {
                    public void run() {
                        double mbps = 0;
                        long ttfb = -1;
                        boolean ok = false;
                        try {
                            long t0 = System.currentTimeMillis();
                            HttpURLConnection c = (HttpURLConnection) new URL("https://speed.cloudflare.com/__down?bytes=60000000").openConnection();
                            c.setConnectTimeout(8000);
                            c.setReadTimeout(8000);
                            InputStream in = c.getInputStream();
                            ttfb = System.currentTimeMillis() - t0;
                            byte[] buf = new byte[65536];
                            long total = 0, start = System.currentTimeMillis(), lastUi = 0;
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                total += n;
                                long now = System.currentTimeMillis();
                                if (now - start > 9000 || !col.isAttachedToWindow()) break;
                                if (now - lastUi > 300 && now - start > 400) {
                                    lastUi = now;
                                    final double m = total * 8.0 / 1e6 / ((now - start) / 1000.0);
                                    col.post(new Runnable() {
                                        public void run() {
                                            big.setText(String.format(Locale.ENGLISH, "%.1f Mbps", m));
                                            setBar(b, m / 100.0);
                                            st.setText("Testing...");
                                        }
                                    });
                                }
                            }
                            long el = Math.max(1, System.currentTimeMillis() - start);
                            mbps = total * 8.0 / 1e6 / (el / 1000.0);
                            in.close();
                            c.disconnect();
                            ok = total > 0;
                        } catch (Throwable t) {
                            ok = false;
                        }
                        final boolean fok = ok;
                        final double fm = mbps;
                        final long ft = ttfb;
                        col.post(new Runnable() {
                            public void run() {
                                speedRunning = false;
                                go.setText("Start again");
                                if (fok) {
                                    big.setText(String.format(Locale.ENGLISH, "%.1f Mbps", fm));
                                    setBar(b, fm / 100.0);
                                    st.setText("Response time: " + ft + " ms");
                                } else {
                                    st.setText("Test failed. Check the internet.");
                                }
                            }
                        });
                    }
                }).start();
            }
        });
        return col;
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
