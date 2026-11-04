package com.viral.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A draggable, resizable window with a web page inside (like a browser window on a desktop). */
final class WebWindow extends FrameLayout {

    interface Host {
        void onWindowChanged();
        void onWindowClosed(WebWindow w);
        void onFullscreenVideo(View v, WebChromeClient.CustomViewCallback cb);
        void onFullscreenVideoEnd();
        void onSaveSite(String name, String url);
        void onDownload(String url, String ua, String cd, String mime);
    }

    static int RED = 0xFFE11D2E;

    final String name;
    final int slot;
    boolean minimized = false;
    boolean maximized = false;
    boolean small = false;
    static float ratio = 0.5f;

    private final Host host;
    private WebView web;
    private TextView titleView;
    private EditText addr;
    private TextView bDesk;
    private boolean desktop = false;
    private String mobileUa = "";
    static final String H264_JS = "(function(){try{var re=/vp9|vp09|av01|av1/i;"
            + "if(window.MediaSource&&MediaSource.isTypeSupported){var o=MediaSource.isTypeSupported.bind(MediaSource);"
            + "MediaSource.isTypeSupported=function(t){return re.test(t)?false:o(t);};}"
            + "var c=HTMLMediaElement.prototype.canPlayType;"
            + "HTMLMediaElement.prototype.canPlayType=function(t){return re.test(t)?'':c.call(this,t);};}catch(e){}})();";
    static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private int rl, rt, rw, rh;

    WebWindow(Context ctx, String name, String url, Host host, int slot) {
        this(ctx, name, url, null, host, slot);
    }

    WebWindow(Context ctx, String name, String url, View content, Host host, int slot) {
        super(ctx);
        this.name = name;
        this.slot = slot;
        this.host = host;

        setBackground(box(0xFF101015, 0x88FFFFFF, 10, 1));
        setPadding(dp(1), dp(1), dp(1), dp(1));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- title bar
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1B1B22);
        bar.setPadding(dp(12), 0, dp(4), 0);
        col.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        titleView = new TextView(ctx);
        titleView.setText(name);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView bL = barBtn("\u25E7");
        bL.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { snap(true); }
        });
        bar.addView(bL, new LinearLayout.LayoutParams(dp(46), dp(32)));

        TextView bR = barBtn("\u25E8");
        bR.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { snap(false); }
        });
        bar.addView(bR, new LinearLayout.LayoutParams(dp(46), dp(32)));

        TextView bMin = barBtn("–");
        bMin.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { minimize(); }
        });
        bar.addView(bMin, new LinearLayout.LayoutParams(dp(46), dp(32)));

        TextView bMax = barBtn("□");
        bMax.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleMax(); }
        });
        bar.addView(bMax, new LinearLayout.LayoutParams(dp(46), dp(32)));

        TextView bClose = barBtn("×");
        bClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        bClose.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { close(); }
        });
        bar.addView(bClose, new LinearLayout.LayoutParams(dp(46), dp(32)));

        // drag the window by its title bar (mouse or touch)
        bar.setOnTouchListener(new View.OnTouchListener() {
            float lx, ly;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        bringToFront();
                        lx = e.getRawX();
                        ly = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        moveBy((int) (e.getRawX() - lx), (int) (e.getRawY() - ly));
                        lx = e.getRawX();
                        ly = e.getRawY();
                        return true;
                    default:
                        return true;
                }
            }
        });
        bar.setOnLongClickListener(null);

        if (content != null) {
            col.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
        // ---- address bar
        LinearLayout tb = new LinearLayout(ctx);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        tb.setBackgroundColor(0xFF15151B);
        tb.setPadding(dp(6), dp(3), dp(6), dp(3));
        col.addView(tb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        TextView bBack = barBtn("\u2039");
        bBack.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        bBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (web.canGoBack()) web.goBack(); }
        });
        tb.addView(bBack, new LinearLayout.LayoutParams(dp(40), dp(34)));

        TextView bRef = barBtn("\u27F3");
        bRef.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { web.reload(); }
        });
        tb.addView(bRef, new LinearLayout.LayoutParams(dp(40), dp(34)));

        addr = new EditText(ctx);
        addr.setSingleLine(true);
        addr.setTextColor(Color.WHITE);
        addr.setHintTextColor(0x88FFFFFF);
        addr.setHint("Type address or search");
        addr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        addr.setPadding(dp(10), 0, dp(10), 0);
        addr.setImeOptions(EditorInfo.IME_ACTION_GO);
        addr.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        addr.setBackground(box(0xFF26262E, 0x44FFFFFF, 17, 1));
        addr.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE
                        || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    load(addr.getText().toString());
                    web.requestFocus();
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        alp.setMargins(dp(4), 0, dp(4), 0);
        tb.addView(addr, alp);

        bDesk = barBtn("PC");
        bDesk.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bDesk.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setDesktop(!desktop); }
        });
        tb.addView(bDesk, new LinearLayout.LayoutParams(dp(44), dp(34)));

        TextView bStar = barBtn("\u2605");
        bStar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String u = web.getUrl();
                if (u == null || u.startsWith("about:")) return;
                String n = titleView.getText().toString();
                if (n.length() > 18) n = n.substring(0, 18);
                WebWindow.this.host.onSaveSite(n, u);
            }
        });
        tb.addView(bStar, new LinearLayout.LayoutParams(dp(40), dp(34)));

        // ---- web page
        web = new WebView(ctx);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        try {
            mobileUa = s.getUserAgentString().replace("; wv", "");
            s.setUserAgentString(mobileUa);
        } catch (Exception e) {
            // keep default
        }
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String u, android.graphics.Bitmap b) {
                if (u != null && !addr.hasFocus() && !u.startsWith("about:")) addr.setText(u);
                // ask YouTube for H.264 only: cheap boxes decode it in hardware, VP9/AV1 would be software (laggy)
                if (u != null && (u.contains("youtube.com") || u.contains("youtu.be"))) {
                    try {
                        v.evaluateJavascript(H264_JS, null);
                    } catch (Throwable t) {
                        // ignore
                    }
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String u) {
                return !(u.startsWith("http://") || u.startsWith("https://"));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView v, String t) {
                if (t != null && t.length() > 0) titleView.setText(t);
            }

            @Override
            public void onShowCustomView(View view, WebChromeClient.CustomViewCallback cb) {
                WebWindow.this.host.onFullscreenVideo(view, cb);
            }

            @Override
            public void onHideCustomView() {
                WebWindow.this.host.onFullscreenVideoEnd();
            }
        });
        web.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) bringToFront();
                return false;
            }
        });
        web.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String u, String ua, String cd, String mime, long len) {
                WebWindow.this.host.onDownload(u, ua, cd, mime);
            }
        });
        col.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        web.loadUrl(url);
        }

        // ---- resize grip (bottom-right corner)
        View grip = new View(ctx) {
            final Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas c) {
                gp.setColor(0xCCFFFFFF);
                gp.setStrokeWidth(dp(2));
                float w = getWidth(), h = getHeight();
                c.drawLine(w - dp(4), h - dp(14), w - dp(14), h - dp(4), gp);
                c.drawLine(w - dp(4), h - dp(8), w - dp(8), h - dp(4), gp);
            }
        };
        grip.setOnTouchListener(new View.OnTouchListener() {
            float lx, ly;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        bringToFront();
                        lx = e.getRawX();
                        ly = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        resizeBy((int) (e.getRawX() - lx), (int) (e.getRawY() - ly));
                        lx = e.getRawX();
                        ly = e.getRawY();
                        return true;
                    default:
                        return true;
                }
            }
        });
        addView(grip, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM | Gravity.END));
    }

    // ---------- helpers ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable box(int fill, int stroke, int r, int sw) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        g.setStroke(dp(sw), stroke);
        return g;
    }

    private TextView barBtn(String text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, box(RED, 0xFFFFFFFF, 8, 2));
        sl.addState(new int[]{android.R.attr.state_pressed}, box(0xFFB5121F, 0xFFFFFFFF, 8, 2));
        sl.addState(new int[]{android.R.attr.state_hovered}, box(0x44FFFFFF, 0x88FFFFFF, 8, 1));
        sl.addState(new int[]{}, box(0x00000000, 0x00000000, 8, 0));
        t.setBackground(sl);
        return t;
    }

    private FrameLayout.LayoutParams lp() {
        return (FrameLayout.LayoutParams) getLayoutParams();
    }

    private void moveBy(int dx, int dy) {
        if (maximized || !(getParent() instanceof View)) return;
        View p = (View) getParent();
        FrameLayout.LayoutParams l = lp();
        int w = getWidth(), h = getHeight();
        l.leftMargin = Math.max(-w + dp(120), Math.min(p.getWidth() - dp(120), l.leftMargin + dx));
        l.topMargin = Math.max(0, Math.min(p.getHeight() - dp(40), l.topMargin + dy));
        setLayoutParams(l);
    }

    private void resizeBy(int dx, int dy) {
        if (maximized || !(getParent() instanceof View)) return;
        View p = (View) getParent();
        FrameLayout.LayoutParams l = lp();
        int w = getWidth() + dx, h = getHeight() + dy;
        l.width = Math.max(dp(320), Math.min(p.getWidth() - l.leftMargin, w));
        l.height = Math.max(dp(220), Math.min(p.getHeight() - l.topMargin, h));
        setLayoutParams(l);
    }

    // ---------- address / desktop mode ----------

    void load(String input) {
        String t = input.trim();
        if (t.length() == 0) return;
        String u;
        if (t.startsWith("http://") || t.startsWith("https://")) u = t;
        else if (t.indexOf(' ') < 0 && t.indexOf('.') > 0) u = "https://" + t;
        else u = "https://www.google.com/search?q=" + android.net.Uri.encode(t);
        web.loadUrl(u);
    }

    void setDesktop(boolean on) {
        desktop = on;
        WebSettings s = web.getSettings();
        s.setUserAgentString(on ? DESKTOP_UA : mobileUa);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        bDesk.setText(on ? "MOB" : "PC");
        web.reload();
    }

    void place(int left, int top, int w, int h) {
        FrameLayout.LayoutParams l = lp();
        maximized = false;
        l.leftMargin = left;
        l.topMargin = top;
        l.width = w;
        l.height = h;
        setLayoutParams(l);
    }

    void snap(boolean left) {
        if (!(getParent() instanceof View)) return;
        View p = (View) getParent();
        int pad = dp(10);
        int avail = p.getWidth() - pad * 3;
        int wl = (int) (avail * ratio);
        int w = left ? wl : avail - wl;
        FrameLayout.LayoutParams l = lp();
        maximized = false;
        l.width = w;
        l.height = p.getHeight() - pad * 2;
        l.topMargin = pad;
        l.leftMargin = left ? pad : pad * 2 + wl;
        setLayoutParams(l);
        bringToFront();
        host.onWindowChanged();
    }

    // ---------- window actions ----------

    void toggleMax() {
        FrameLayout.LayoutParams l = lp();
        if (!maximized) {
            rl = l.leftMargin;
            rt = l.topMargin;
            rw = getWidth();
            rh = getHeight();
            l.leftMargin = 0;
            l.topMargin = 0;
            l.width = ViewGroup.LayoutParams.MATCH_PARENT;
            l.height = ViewGroup.LayoutParams.MATCH_PARENT;
            maximized = true;
        } else {
            l.leftMargin = rl;
            l.topMargin = rt;
            l.width = rw > 0 ? rw : dp(500);
            l.height = rh > 0 ? rh : dp(360);
            maximized = false;
        }
        setLayoutParams(l);
        bringToFront();
        host.onWindowChanged();
    }

    void minimize() {
        minimized = true;
        setVisibility(View.GONE);
        host.onWindowChanged();
    }

    void show() {
        minimized = false;
        setVisibility(View.VISIBLE);
        bringToFront();
        if (web != null) web.requestFocus();
        host.onWindowChanged();
    }

    void close() {
        try {
            web.stopLoading();
            web.loadUrl("about:blank");
        } catch (Exception e) {
            // ignore
        }
        ViewGroup p = (ViewGroup) getParent();
        if (p != null) p.removeView(this);
        try {
            web.destroy();
        } catch (Exception e) {
            // ignore
        }
        host.onWindowClosed(this);
    }

    boolean canGoBack() {
        return web != null && web.canGoBack();
    }

    void goBack() {
        if (web != null) web.goBack();
    }

    void pauseWeb() {
        if (web != null) web.onPause();
    }

    void resumeWeb() {
        if (web != null) web.onResume();
    }
}
