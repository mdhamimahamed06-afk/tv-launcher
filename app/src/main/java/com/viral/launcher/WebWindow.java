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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
    }

    static final int RED = 0xFFE11D2E;

    final String name;
    final int slot;
    boolean minimized = false;
    boolean maximized = false;

    private final Host host;
    private final WebView web;
    private final TextView titleView;
    private int rl, rt, rw, rh;

    WebWindow(Context ctx, String name, String url, Host host, int slot) {
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

        // ---- web page
        web = new WebView(ctx);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        try {
            s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        } catch (Exception e) {
            // keep default
        }
        web.setWebViewClient(new WebViewClient() {
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
        col.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        web.loadUrl(url);

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
        web.requestFocus();
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
        return web.canGoBack();
    }

    void goBack() {
        web.goBack();
    }

    void pauseWeb() {
        web.onPause();
    }

    void resumeWeb() {
        web.onResume();
    }
}
