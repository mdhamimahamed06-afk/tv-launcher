package com.viral.launcher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Comparator;

/** The "Phone Inbox" window: QR code + list of files/text received from the phone. */
final class InboxView {

    interface Info { String url(); }

    private static class QrBox extends View {
        Qr qr;
        final Paint p = new Paint();

        QrBox(Context c) {
            super(c);
            p.setAntiAlias(false);
        }

        void set(Qr q) {
            qr = q;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            p.setColor(Color.WHITE);
            c.drawRect(0, 0, getWidth(), getHeight(), p);
            if (qr == null) return;
            int n = qr.size + 8;
            int cell = Math.min(getWidth(), getHeight()) / n;
            int offX = (getWidth() - cell * n) / 2, offY = (getHeight() - cell * n) / 2;
            p.setColor(Color.BLACK);
            for (int y = 0; y < qr.size; y++) {
                for (int x = 0; x < qr.size; x++) {
                    if (qr.mod[y][x]) {
                        int l = offX + (x + 4) * cell, t = offY + (y + 4) * cell;
                        c.drawRect(l, t, l + cell, t + cell, p);
                    }
                }
            }
        }
    }

    private static boolean isText(String n) {
        String s = n.toLowerCase();
        return s.endsWith(".txt");
    }

    private static String readText(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[(int) Math.min(f.length(), 200 * 1024)];
                int got = 0, n;
                while (got < b.length && (n = in.read(b, got, b.length - got)) > 0) got += n;
                return new String(b, 0, got, "UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    static View create(final Activity a, final int accent, final Tools.Opener op, final Info info) {
        final File dir = InboxServer.dir(a);
        final LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF14141A);

        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Tools.dp(a, 10), Tools.dp(a, 10), Tools.dp(a, 10), Tools.dp(a, 6));
        final QrBox qr = new QrBox(a);
        top.addView(qr, new LinearLayout.LayoutParams(Tools.dp(a, 130), Tools.dp(a, 130)));
        LinearLayout info1 = new LinearLayout(a);
        info1.setOrientation(LinearLayout.VERTICAL);
        info1.setPadding(Tools.dp(a, 12), 0, 0, 0);
        TextView h1 = Tools.txt(a, "PHONE INBOX", 15, accent, true);
        final TextView urlT = Tools.txt(a, "", 13, Color.WHITE, true);
        urlT.setTextIsSelectable(false);
        TextView h2 = Tools.txt(a, "Scan the QR with your phone (same Wi-Fi), or open the address. "
                + "Send any file or text. Works even when the TV screen is off. "
                + "Everything is deleted after 1 hour.", 12, 0xAAFFFFFF, false);
        info1.addView(h1);
        info1.addView(urlT);
        info1.addView(h2);
        top.addView(info1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(top);

        ScrollView sv = new ScrollView(a);
        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Tools.dp(a, 8), 0, Tools.dp(a, 8), Tools.dp(a, 8));
        sv.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final String[] lastSig = {null};
        final String[] lastUrl = {null};
        final Runnable[] loop = new Runnable[1];
        loop[0] = new Runnable() {
            public void run() {
                String u = info.url();
                if (u == null ? lastUrl[0] != null : !u.equals(lastUrl[0])) {
                    lastUrl[0] = u;
                    urlT.setText(u == null ? "No network. Connect Wi-Fi first." : u);
                    qr.set(u == null ? null : Qr.encode(u));
                }
                InboxServer.purge(dir);
                File[] fs = dir.listFiles();
                if (fs == null) fs = new File[0];
                Arrays.sort(fs, new Comparator<File>() {
                    public int compare(File x, File y) {
                        return Long.compare(y.lastModified(), x.lastModified());
                    }
                });
                StringBuilder sig = new StringBuilder();
                long now = System.currentTimeMillis();
                for (File f : fs) {
                    if (f.getName().startsWith(".") || f.getName().endsWith(".part")) continue;
                    long left = (InboxServer.KEEP_MS - (now - f.lastModified())) / 60000L;
                    sig.append(f.getName()).append('|').append(f.length()).append('|').append(left).append(';');
                }
                String sg = sig.toString();
                if (!sg.equals(lastSig[0])) {
                    lastSig[0] = sg;
                    rebuild(a, accent, op, list, fs);
                }
                if (col.isAttachedToWindow()) col.postDelayed(loop[0], 2000);
            }
        };
        col.post(loop[0]);
        return col;
    }

    private static void rebuild(final Activity a, final int accent, final Tools.Opener op,
                                final LinearLayout list, File[] fs) {
        list.removeAllViews();
        int shown = 0;
        long now = System.currentTimeMillis();
        for (final File f : fs) {
            if (f.getName().startsWith(".") || f.getName().endsWith(".part")) continue;
            shown++;
            long left = (InboxServer.KEEP_MS - (now - f.lastModified())) / 60000L;
            if (left < 0) left = 0;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(Tools.box(a, 0x22FFFFFF, 0x22FFFFFF, 10, 1));
            row.setPadding(Tools.dp(a, 12), Tools.dp(a, 8), Tools.dp(a, 12), Tools.dp(a, 8));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.topMargin = Tools.dp(a, 6);

            final boolean text = isText(f.getName());
            TextView nm = Tools.txt(a, f.getName(), 14, Color.WHITE, true);
            nm.setSingleLine(true);
            row.addView(nm);
            TextView sub = Tools.txt(a, Tools.human(f.length()) + "  -  deletes in " + left + " min", 11, 0x99FFFFFF, false);
            row.addView(sub);
            if (text) {
                String t = readText(f);
                if (t.length() > 160) t = t.substring(0, 160) + "...";
                TextView pv = Tools.txt(a, t, 13, 0xFFCCE7FF, false);
                pv.setMaxLines(3);
                pv.setPadding(0, Tools.dp(a, 4), 0, 0);
                row.addView(pv);
            }

            LinearLayout btns = new LinearLayout(a);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setPadding(0, Tools.dp(a, 6), 0, 0);
            TextView main = Tools.button(a, text ? "Copy text" : "Open", accent, 13, true);
            main.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (text) {
                        String t = readText(f);
                        try {
                            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("inbox", t));
                            Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show();
                        } catch (Throwable e) {
                            // ignore
                        }
                    } else {
                        Tools.openFile(a, f, op);
                    }
                }
            });
            TextView del = Tools.button(a, "Delete", accent, 13, false);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    f.delete();
                    list.removeView((View) v.getParent().getParent());
                }
            });
            btns.addView(main, new LinearLayout.LayoutParams(Tools.dp(a, 110), Tools.dp(a, 34)));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Tools.dp(a, 90), Tools.dp(a, 34));
            dl.leftMargin = Tools.dp(a, 8);
            btns.addView(del, dl);
            row.addView(btns);
            list.addView(row, rl);
        }
        if (shown == 0) {
            TextView e = Tools.txt(a, "Nothing yet. Send something from your phone.", 13, 0x88FFFFFF, false);
            e.setPadding(Tools.dp(a, 8), Tools.dp(a, 16), 0, 0);
            list.addView(e);
        }
    }
}
