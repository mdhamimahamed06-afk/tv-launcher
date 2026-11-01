package com.viral.launcher;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/** Tiny web server: the phone opens the page, picks + crops an image and uploads it. */
final class WallServer {

    interface Listener {
        void onUploaded();
    }

    private static final int MAX_BYTES = 20 * 1024 * 1024;

    private final File out;
    private final String key;
    private final int sw, sh;
    private final Listener listener;
    private ServerSocket ss;
    private volatile boolean running = false;
    int port = 0;

    WallServer(File out, String key, int sw, int sh, Listener l) {
        this.out = out;
        this.key = key;
        this.sw = sw;
        this.sh = sh;
        this.listener = l;
    }

    boolean start() {
        for (int p = 8080; p < 8100; p++) {
            try {
                ss = new ServerSocket(p);
                port = p;
                break;
            } catch (IOException e) {
                ss = null;
            }
        }
        if (ss == null) return false;
        running = true;
        new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        final Socket s = ss.accept();
                        new Thread(new Runnable() {
                            public void run() {
                                try {
                                    handle(s);
                                } catch (Exception e) {
                                    // ignore broken connections
                                } finally {
                                    try { s.close(); } catch (IOException e) { /* ignore */ }
                                }
                            }
                        }).start();
                    } catch (IOException e) {
                        if (!running) return;
                    }
                }
            }
        }).start();
        return true;
    }

    void stop() {
        running = false;
        try {
            if (ss != null) ss.close();
        } catch (IOException e) {
            // ignore
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private void handle(Socket s) throws IOException {
        s.setSoTimeout(20000);
        InputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream os = s.getOutputStream();
        String req = readLine(in);
        if (req == null) return;
        String[] parts = req.split(" ");
        if (parts.length < 2) return;
        String method = parts[0], path = parts[1];
        int len = 0;
        String line;
        while ((line = readLine(in)) != null && line.length() > 0) {
            if (line.toLowerCase().startsWith("content-length:")) {
                try {
                    len = Integer.parseInt(line.substring(15).trim());
                } catch (NumberFormatException e) {
                    len = 0;
                }
            }
        }
        boolean keyOk = path.contains("k=" + key);
        if (!keyOk) {
            reply(os, 403, "text/plain", "Forbidden");
            return;
        }
        if (method.equals("POST") && path.startsWith("/u")) {
            if (len <= 0 || len > MAX_BYTES) {
                reply(os, 400, "text/plain", "Bad size");
                return;
            }
            File tmp = new File(out.getPath() + ".tmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int left = len;
            try {
                while (left > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, left));
                    if (n < 0) break;
                    fo.write(buf, 0, n);
                    left -= n;
                }
            } finally {
                fo.close();
            }
            if (left != 0) {
                tmp.delete();
                reply(os, 400, "text/plain", "Incomplete");
                return;
            }
            out.delete();
            if (!tmp.renameTo(out)) {
                reply(os, 500, "text/plain", "Save failed");
                return;
            }
            reply(os, 200, "text/plain", "OK");
            if (listener != null) listener.onUploaded();
            return;
        }
        String page = PAGE.replace("__K__", key)
                .replace("__W__", String.valueOf(sw))
                .replace("__H__", String.valueOf(sh));
        reply(os, 200, "text/html; charset=utf-8", page);
    }

    private void reply(OutputStream os, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        os.write(head.getBytes("UTF-8"));
        os.write(b);
        os.flush();
    }

    private static final String PAGE = "<!doctype html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content=\"width=device-width,initial-scale=1\"><title>VQ Wallpaper</title>"
            + "<style>body{margin:0;background:#0b0b10;color:#fff;font-family:sans-serif;text-align:center;padding:18px}"
            + "h2{color:#e11d2e;letter-spacing:3px;margin:8px 0}p{color:#bbb}"
            + ".b{background:#e11d2e;color:#fff;border:0;border-radius:12px;padding:14px 24px;font-size:17px;font-weight:bold;display:inline-block;margin:8px}"
            + "canvas{width:100%;max-width:560px;border:2px solid #e11d2e;border-radius:12px;touch-action:none;background:#000}"
            + "input[type=range]{width:90%;max-width:520px}#m{font-size:18px;font-weight:bold}</style></head><body>"
            + "<h2>VQ LAUNCHER</h2><p>Custom wallpaper for your TV</p>"
            + "<label class=b>1. Choose image<input id=f type=file accept=\"image/*\" hidden></label>"
            + "<div id=ed style=\"display:none\"><canvas id=c></canvas>"
            + "<p>Drag the picture to move it. Use the slider to zoom.</p>"
            + "<input id=z type=range min=100 max=400 value=100><br>"
            + "<button class=b id=u>2. Upload to TV</button></div><p id=m></p>"
            + "<script>"
            + "var K='__K__',W=__W__,H=__H__,img=null,zoom=1,ox=0,oy=0;"
            + "var cv=document.getElementById('c');cv.width=960;cv.height=Math.round(960*H/W);var cx=cv.getContext('2d');"
            + "function draw(ctx,w,h){ctx.fillStyle='#000';ctx.fillRect(0,0,w,h);if(!img)return;"
            + "var s=Math.max(w/img.width,h/img.height)*zoom,iw=img.width*s,ih=img.height*s;"
            + "var mx=Math.max(0,(iw-w)/2/w),my=Math.max(0,(ih-h)/2/h);"
            + "ox=Math.max(-mx,Math.min(mx,ox));oy=Math.max(-my,Math.min(my,oy));"
            + "ctx.drawImage(img,(w-iw)/2+ox*w,(h-ih)/2+oy*h,iw,ih);}"
            + "function show(){draw(cx,cv.width,cv.height);}"
            + "document.getElementById('f').onchange=function(e){var fl=e.target.files[0];if(!fl)return;"
            + "var im=new Image();im.onload=function(){img=im;zoom=1;ox=0;oy=0;"
            + "document.getElementById('z').value=100;document.getElementById('ed').style.display='block';"
            + "document.getElementById('m').textContent='';show();};im.src=URL.createObjectURL(fl);};"
            + "document.getElementById('z').oninput=function(e){zoom=e.target.value/100;show();};"
            + "var dr=false,lx=0,ly=0;"
            + "cv.onpointerdown=function(e){dr=true;lx=e.clientX;ly=e.clientY;cv.setPointerCapture(e.pointerId);};"
            + "cv.onpointermove=function(e){if(!dr)return;var r=cv.getBoundingClientRect();"
            + "ox+=(e.clientX-lx)/r.width;oy+=(e.clientY-ly)/r.height;lx=e.clientX;ly=e.clientY;show();};"
            + "cv.onpointerup=function(){dr=false;};"
            + "document.getElementById('u').onclick=function(){var m=document.getElementById('m');"
            + "var ow=Math.min(1920,W),oh=Math.round(ow*H/W),o=document.createElement('canvas');o.width=ow;o.height=oh;"
            + "draw(o.getContext('2d'),ow,oh);m.textContent='Uploading...';"
            + "o.toBlob(function(b){fetch('/u?k='+K,{method:'POST',body:b}).then(function(r){return r.text();})"
            + ".then(function(t){m.textContent=t=='OK'?'Done! Look at your TV.':'Failed: '+t;})"
            + ".catch(function(){m.textContent='Upload failed. Same Wi-Fi?';});},'image/jpeg',0.9);};"
            + "</script></body></html>";
}
