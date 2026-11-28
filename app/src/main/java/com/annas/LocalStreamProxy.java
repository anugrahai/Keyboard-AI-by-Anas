package com.annas;

import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mini HTTP Proxy Loopback (127.0.0.1) untuk streaming pratinjau cepat di MediaPlayer / VideoView.
 * - Meneruskan User-Agent, Referer, Cookie & Range header melalui HttpURLConnection Java sehingga
 *   MediaPlayer Android tidak terkena blokir HTTP 403 dari YouTube, Facebook, TikTok, SoundCloud, dll.
 */
public final class LocalStreamProxy {

    private static final String TAG = "LocalStreamProxy";

    private static final class Entry {
        final String targetUrl;
        final Map<String, String> headers;
        Entry(String targetUrl, Map<String, String> headers) {
            this.targetUrl = targetUrl;
            this.headers = headers;
        }
    }

    private static LocalStreamProxy instance;
    private ServerSocket serverSocket;
    private int port = -1;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ExecutorService clientPool = Executors.newCachedThreadPool();

    private LocalStreamProxy() {}

    public static synchronized LocalStreamProxy getInstance() {
        if (instance == null) {
            instance = new LocalStreamProxy();
        }
        instance.ensureStarted();
        return instance;
    }

    private synchronized void ensureStarted() {
        if (serverSocket != null && !serverSocket.isClosed() && port > 0) return;
        try {
            serverSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            port = serverSocket.getLocalPort();
            Thread acceptThread = new Thread(() -> {
                while (serverSocket != null && !serverSocket.isClosed()) {
                    try {
                        final Socket client = serverSocket.accept();
                        clientPool.submit(() -> handleClient(client));
                    } catch (Throwable t) {
                        if (serverSocket == null || serverSocket.isClosed()) break;
                    }
                }
            }, "local-stream-proxy-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.d(TAG, "Proxy started on 127.0.0.1:" + port);
        } catch (Throwable t) {
            Log.e(TAG, "Failed starting proxy: " + t.getMessage());
        }
    }

    public String registerStream(String targetUrl, Map<String, String> headers) {
        ensureStarted();
        if (port <= 0 || TextUtils.isEmpty(targetUrl)) return targetUrl;
        if (entries.size() > 30) entries.clear();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, String> copy = new HashMap<>();
        if (headers != null) copy.putAll(headers);
        entries.put(id, new Entry(targetUrl, copy));
        return "http://127.0.0.1:" + port + "/stream?id=" + id;
    }

    private void handleClient(Socket socket) {
        HttpURLConnection conn = null;
        try (Socket s = socket) {
            s.setSoTimeout(25000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            String reqLine = reader.readLine();
            if (reqLine == null || reqLine.isEmpty()) return;

            String rangeHeader = null;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0 && "range".equalsIgnoreCase(line.substring(0, idx).trim())) {
                    rangeHeader = line.substring(idx + 1).trim();
                }
            }

            String id = extractIdFromRequestLine(reqLine);
            Entry entry = id != null ? entries.get(id) : null;
            OutputStream out = s.getOutputStream();
            if (entry == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                return;
            }

            conn = openFollowingRedirects(entry.targetUrl, entry.headers, rangeHeader);
            int code = conn.getResponseCode();
            String statusMsg = conn.getResponseMessage();
            if (statusMsg == null || statusMsg.isEmpty()) statusMsg = (code == 206) ? "Partial Content" : "OK";

            StringBuilder hdr = new StringBuilder();
            hdr.append("HTTP/1.1 ").append(code).append(" ").append(statusMsg).append("\r\n");
            String ct = conn.getContentType();
            if (ct != null && !ct.isEmpty()) hdr.append("Content-Type: ").append(ct).append("\r\n");
            long len = conn.getContentLengthLong();
            if (len >= 0) hdr.append("Content-Length: ").append(len).append("\r\n");
            String cr = conn.getHeaderField("Content-Range");
            if (cr != null && !cr.isEmpty()) hdr.append("Content-Range: ").append(cr).append("\r\n");
            hdr.append("Accept-Ranges: bytes\r\n");
            hdr.append("Connection: close\r\n\r\n");
            out.write(hdr.toString().getBytes(StandardCharsets.US_ASCII));

            if (reqLine.startsWith("HEAD ")) {
                out.flush();
                return;
            }

            try (InputStream is = new BufferedInputStream(conn.getInputStream(), 32768)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = is.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private static HttpURLConnection openFollowingRedirects(String rawUrl, Map<String, String> headers,
                                                          String rangeHeader) throws Exception {
        String cur = rawUrl;
        for (int hops = 0; hops < 5; hops++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(cur).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (!TextUtils.isEmpty(e.getKey()) && e.getValue() != null) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            if (!TextUtils.isEmpty(rangeHeader)) {
                conn.setRequestProperty("Range", rangeHeader);
            }
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (TextUtils.isEmpty(loc)) return conn;
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            return conn;
        }
        throw new IllegalStateException("Terlalu banyak redirect stream.");
    }

    private static String extractIdFromRequestLine(String reqLine) {
        int sp1 = reqLine.indexOf(' ');
        int sp2 = reqLine.indexOf(' ', sp1 + 1);
        if (sp1 < 0 || sp2 <= sp1) return null;
        String path = reqLine.substring(sp1 + 1, sp2);
        int idx = path.indexOf("id=");
        if (idx < 0) return null;
        String sub = path.substring(idx + 3);
        int amp = sub.indexOf('&');
        return amp >= 0 ? sub.substring(0, amp) : sub;
    }
}
