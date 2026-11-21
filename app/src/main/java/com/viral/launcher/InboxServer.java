package com.viral.launcher;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Phone Inbox: the phone opens a page on the same Wi-Fi and sends files or text to the TV box.
 * Everything is kept for one hour and then deleted automatically.
 */
final class InboxServer {

    static final int PORT = 8765;
    static final long KEEP_MS = 60L * 60L * 1000L;
    static final long MAX_BYTES = 400L * 1024L * 1024L;

    /** Port the server is really listening on (0 = not running). */
    static volatile int livePort = 0;

    private final File dir;
    private final String key;
    private ServerSocket ss;
    private volatile boolean running = false;

    InboxServer(File dir, String key) {
        this.dir = dir;
        this.key = key;
    }

    static File dir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File d = new File(base, "inbox");
        d.mkdirs();
        return d;
    }

    /** Delete everything older than one hour (and broken partial uploads). */
    static void purge(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        long now = System.currentTimeMillis();
        for (File f : fs) {
            long age = now - f.lastModified();
            boolean part = f.getName().endsWith(".part");
            if (age > KEEP_MS || (part && age > 10L * 60L * 1000L)) f.delete();
        }
    }

    boolean start() {
        for (int p = PORT; p < PORT + 10; p++) {
            try {
                ss = new ServerSocket(p);
                livePort = p;
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
        livePort = 0;
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

    private static String param(String query, String name) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(name)) {
                try {
                    return URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    static String safeName(String n) {
        if (n == null) n = "";
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.length() > 120) n = n.substring(n.length() - 120);
        if (n.length() == 0) n = "file";
        return n;
    }

    private File uniqueFile(String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, System.currentTimeMillis() + "-" + name);
    }

    private void handle(Socket s) throws IOException {
        s.setSoTimeout(30000);
        InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
        OutputStream os = s.getOutputStream();
        String req = readLine(in);
        if (req == null) return;
        String[] parts = req.split(" ");
        if (parts.length < 2) return;
        String method = parts[0], target = parts[1];
        long len = 0;
        String line;
        while ((line = readLine(in)) != null && line.length() > 0) {
            if (line.toLowerCase(Locale.ENGLISH).startsWith("content-length:")) {
                try {
                    len = Long.parseLong(line.substring(15).trim());
                } catch (NumberFormatException e) {
                    len = 0;
                }
            }
        }
        String route = target, query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            route = target.substring(0, q);
            query = target.substring(q + 1);
        }
        if (!key.equals(param(query, "k"))) {
            reply(os, 403, "text/plain", "Forbidden");
            return;
        }
        purge(dir);

        if (method.equals("POST") && route.equals("/f")) {
            if (len <= 0 || len > MAX_BYTES) {
                reply(os, 400, "text/plain", "Bad size");
                return;
            }
            saveBody(in, os, len, safeName(param(query, "n")));
            return;
        }
        if (method.equals("POST") && route.equals("/t")) {
            if (len <= 0 || len > 1024 * 1024) {
                reply(os, 400, "text/plain", "Bad size");
                return;
            }
            String nm = "text-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ENGLISH).format(new Date()) + ".txt";
            saveBody(in, os, len, nm);
            return;
        }
        if (route.equals("/list")) {
            StringBuilder sb = new StringBuilder();
            File[] fs = dir.listFiles();
            long now = System.currentTimeMillis();
            if (fs != null) {
                for (File f : fs) {
                    if (f.getName().startsWith(".") || f.getName().endsWith(".part")) continue;
                    long left = (KEEP_MS - (now - f.lastModified())) / 60000L;
                    if (left < 0) left = 0;
                    sb.append(URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20")).append('\t')
                            .append(f.length()).append('\t').append(left).append('\n');
                }
            }
            reply(os, 200, "text/plain; charset=utf-8", sb.toString());
            return;
        }
        if (route.equals("/d")) {
            String n = param(query, "n");
            File f = n == null ? null : new File(dir, safeName(n));
            if (f == null || !f.isFile()) {
                reply(os, 404, "text/plain", "Not found");
                return;
            }
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Content-Length: " + f.length() + "\r\n"
                    + "Content-Disposition: attachment; filename*=UTF-8''"
                    + URLEncoder.encode(f.getName(), "UTF-8").replace("+", "%20") + "\r\n"
                    + "Connection: close\r\n\r\n";
            os.write(head.getBytes("UTF-8"));
            FileInputStream fi = new FileInputStream(f);
            try {
                byte[] buf = new byte[16384];
                int n2;
                while ((n2 = fi.read(buf)) > 0) os.write(buf, 0, n2);
            } finally {
                fi.close();
            }
            os.flush();
            return;
        }
        String page = PAGE.replace("__K__", key);
        reply(os, 200, "text/html; charset=utf-8", page);
    }

    private void saveBody(InputStream in, OutputStream os, long len, String name) throws IOException {
        File tmp = new File(dir, "." + System.currentTimeMillis() + ".part");
        FileOutputStream fo = new FileOutputStream(tmp);
        byte[] buf = new byte[32768];
        long left = len;
        try {
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
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
        File out;
        synchronized (InboxServer.class) {
            out = uniqueFile(name);
            if (!tmp.renameTo(out)) {
                tmp.delete();
                reply(os, 500, "text/plain", "Save failed");
                return;
            }
        }
        out.setLastModified(System.currentTimeMillis());
        reply(os, 200, "text/plain", "OK");
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
            + "<meta name=viewport content=\"width=device-width,initial-scale=1\"><title>VQ Inbox</title>"
            + "<style>body{margin:0;background:#0b0b10;color:#fff;font-family:sans-serif;padding:18px;max-width:640px;margin:auto}"
            + "h2{color:#e11d2e;letter-spacing:3px;margin:8px 0;text-align:center}p{color:#bbb}"
            + ".b{background:#e11d2e;color:#fff;border:0;border-radius:12px;padding:14px 22px;font-size:16px;font-weight:bold;display:inline-block;margin:6px 0}"
            + "textarea{width:100%;box-sizing:border-box;height:110px;border-radius:10px;border:1px solid #444;background:#15151c;color:#fff;font-size:16px;padding:10px}"
            + ".r{background:#15151c;border-radius:10px;padding:10px 12px;margin:6px 0;font-size:14px;word-break:break-all}"
            + ".r small{color:#999;display:block}a{color:#7cc4ff}#m{font-weight:bold;min-height:22px}</style></head><body>"
            + "<h2>VQ INBOX</h2><p style=\"text-align:center\">Send files or text to your TV. Kept for 1 hour, then deleted.</p>"
            + "<label class=b>Choose files<input id=f type=file multiple hidden></label>"
            + "<p id=m></p>"
            + "<textarea id=t placeholder=\"Or type / paste text here (name, password...)\"></textarea><br>"
            + "<button class=b id=ts>Send text</button>"
            + "<h3>On the TV now</h3><div id=l></div>"
            + "<script>"
            + "var K='__K__',m=document.getElementById('m');"
            + "function say(s){m.textContent=s;}"
            + "function up(files,i){if(i>=files.length){say('Done! Look at your TV.');load();return;}"
            + "var f=files[i],x=new XMLHttpRequest();"
            + "x.open('POST','/f?k='+K+'&n='+encodeURIComponent(f.name));"
            + "x.upload.onprogress=function(e){if(e.lengthComputable)say('Sending '+(i+1)+'/'+files.length+': '+Math.round(e.loaded*100/e.total)+'%');};"
            + "x.onload=function(){if(x.responseText=='OK')up(files,i+1);else say('Failed: '+x.responseText);};"
            + "x.onerror=function(){say('Upload failed. Same Wi-Fi?');};x.send(f);}"
            + "document.getElementById('f').onchange=function(e){if(e.target.files.length)up(e.target.files,0);};"
            + "document.getElementById('ts').onclick=function(){var v=document.getElementById('t').value;if(!v)return;"
            + "fetch('/t?k='+K,{method:'POST',body:v}).then(function(r){return r.text();}).then(function(t){"
            + "if(t=='OK'){document.getElementById('t').value='';say('Text sent! Look at your TV.');load();}else say('Failed: '+t);})"
            + ".catch(function(){say('Failed. Same Wi-Fi?');});};"
            + "function load(){fetch('/list?k='+K).then(function(r){return r.text();}).then(function(t){"
            + "var l=document.getElementById('l');l.innerHTML='';t.split('\\n').forEach(function(ln){if(!ln)return;"
            + "var p=ln.split('\\t'),n=decodeURIComponent(p[0]);var d=document.createElement('div');d.className='r';"
            + "var a=document.createElement('a');a.textContent=n;a.href='/d?k='+K+'&n='+encodeURIComponent(n);d.appendChild(a);"
            + "var s=document.createElement('small');s.textContent=Math.round(p[1]/1024)+' KB - deletes in '+p[2]+' min';d.appendChild(s);l.appendChild(d);});});}"
            + "load();setInterval(load,10000);"
            + "</script></body></html>";
}
