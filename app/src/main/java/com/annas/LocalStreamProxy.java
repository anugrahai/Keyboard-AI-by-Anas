package com.annas;

import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
 * Mini HTTP Proxy Loopback (127.0.0.1) untuk streaming pratinjau cepat (< 3 detik) di MediaPlayer / VideoView.
 * - Pre-buffer 256 KB pertama saat registrasi agar pemutar langsung menyala tanpa jeda.
 * - Memecah permintaan ke GoogleVideo / CDN menjadi potongan Range 512 KB agar tidak terkena throttle 32 KB/s
 *   maupun blokir HTTP 403 (no-range) saat diputar di Android NuPlayer.
 */
public final class LocalStreamProxy {

    private static final String TAG = "LocalStreamProxy";
    private static final int PREFETCH_BYTES = 256 * 1024; // 256 KB
    private static final int CHUNK_BYTES = 512 * 1024;    // 512 KB per request ke CDN

    private static final class Entry {
        final String targetUrl;
        final Map<String, String> headers;
        long totalLen = -1L;
        String contentType = "";
        boolean supportsRange = false;
        byte[] headCache = null;

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
            serverSocket = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
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

    /**
     * Registrasi stream sekaligus pre-buffer 256 KB pertama & deteksi Range support.
     * Menjamin pemutaran MediaPlayer/VideoView instan dan kebal throttle.
     */
    public String registerStreamWithPrefetch(String targetUrl, Map<String, String> headers) {
        ensureStarted();
        if (port <= 0 || TextUtils.isEmpty(targetUrl)) return targetUrl;
        if (entries.size() > 30) entries.clear();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, String> copy = new HashMap<>();
        if (headers != null) copy.putAll(headers);
        Entry entry = new Entry(targetUrl, copy);

        HttpURLConnection conn = null;
        try {
            conn = openFollowingRedirects(targetUrl, copy, "bytes=0-" + (PREFETCH_BYTES - 1));
            int code = conn.getResponseCode();
            entry.contentType = conn.getContentType();
            if (code == 206) {
                entry.supportsRange = true;
                String cr = conn.getHeaderField("Content-Range");
                if (cr != null && cr.contains("/")) {
                    entry.totalLen = parseLongSafe(cr.substring(cr.lastIndexOf('/') + 1).trim(), -1L);
                }
                entry.headCache = readUpTo(conn.getInputStream(), PREFETCH_BYTES);
            } else if (code >= 200 && code < 300) {
                long cl = conn.getContentLengthLong();
                if (cl > 0) entry.totalLen = cl;
                String ar = conn.getHeaderField("Accept-Ranges");
                entry.supportsRange = "bytes".equalsIgnoreCase(ar);
                // Simpan max 256 KB awal hanya kalau totalLen kecil atau supportsRange
                if (entry.supportsRange || (entry.totalLen > 0 && entry.totalLen <= PREFETCH_BYTES)) {
                    entry.headCache = readUpTo(conn.getInputStream(), PREFETCH_BYTES);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Prefetch warning: " + t.getMessage());
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }

        entries.put(id, entry);
        return "http://127.0.0.1:" + port + "/stream?id=" + id;
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(30000);
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
                out.flush();
                return;
            }

            boolean isHead = reqLine.startsWith("HEAD ");

            // Jalur cepat: server mendukung Range & kita tahu totalLen (memecah chunk 512 KB agar ngebut)
            if (entry.supportsRange && entry.totalLen > 0) {
                serveChunkedRanged(entry, rangeHeader, isHead, out);
                return;
            }

            // Fallback: teruskan langsung jika server tidak memberi Content-Range
            serveDirectUpstream(entry, rangeHeader, isHead, out);
        } catch (Throwable ignored) {
        }
    }

    private static void serveChunkedRanged(Entry entry, String rangeHeader, boolean isHead, OutputStream out) throws Exception {
        long total = entry.totalLen;
        long start = 0L;
        long end = total - 1L;
        boolean clientRequestedRange = false;

        if (!TextUtils.isEmpty(rangeHeader) && rangeHeader.toLowerCase(java.util.Locale.ROOT).startsWith("bytes=")) {
            clientRequestedRange = true;
            String spec = rangeHeader.substring(6).trim();
            int comma = spec.indexOf(',');
            if (comma > 0) spec = spec.substring(0, comma).trim();
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                String a = spec.substring(0, dash).trim();
                String b = spec.substring(dash + 1).trim();
                if (a.isEmpty() && !b.isEmpty()) {
                    long suffix = parseLongSafe(b, 0L);
                    start = Math.max(0L, total - suffix);
                    end = total - 1L;
                } else {
                    start = Math.max(0L, parseLongSafe(a, 0L));
                    end = b.isEmpty() ? (total - 1L) : Math.min(total - 1L, parseLongSafe(b, total - 1L));
                }
            }
        }

        if (start >= total || end < start) {
            out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + total + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return;
        }

        long contentLen = end - start + 1L;
        StringBuilder hdr = new StringBuilder();
        if (clientRequestedRange) {
            hdr.append("HTTP/1.1 206 Partial Content\r\n");
            hdr.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n");
        } else {
            hdr.append("HTTP/1.1 200 OK\r\n");
        }
        if (!TextUtils.isEmpty(entry.contentType)) {
            hdr.append("Content-Type: ").append(entry.contentType).append("\r\n");
        }
        hdr.append("Content-Length: ").append(contentLen).append("\r\n");
        hdr.append("Accept-Ranges: bytes\r\n");
        hdr.append("Connection: close\r\n\r\n");
        out.write(hdr.toString().getBytes(StandardCharsets.US_ASCII));

        if (isHead) {
            out.flush();
            return;
        }

        long pos = start;
        // 1. Layani dari headCache di RAM kalau overlap dengan byte 0..headCache.length (0 ms latency!)
        if (entry.headCache != null && pos < entry.headCache.length) {
            int cacheAvail = (int) Math.min(end - pos + 1L, entry.headCache.length - pos);
            if (cacheAvail > 0) {
                out.write(entry.headCache, (int) pos, cacheAvail);
                out.flush();
                pos += cacheAvail;
            }
        }

        // 2. Tarik sisa stream per 512 KB chunk untuk menghindari throttling / 403 CDN
        byte[] buf = new byte[32768];
        while (pos <= end) {
            long reqEnd = Math.min(end, pos + CHUNK_BYTES - 1L);
            HttpURLConnection conn = null;
            try {
                conn = openFollowingRedirects(entry.targetUrl, entry.headers, "bytes=" + pos + "-" + reqEnd);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    break;
                }
                long expected = reqEnd - pos + 1L;
                long gotThisChunk = 0L;
                try (InputStream is = new BufferedInputStream(conn.getInputStream(), 32768)) {
                    int n;
                    while (gotThisChunk < expected && (n = is.read(buf, 0, (int) Math.min(buf.length, expected - gotThisChunk))) > 0) {
                        out.write(buf, 0, n);
                        pos += n;
                        gotThisChunk += n;
                    }
                }
                out.flush();
                if (gotThisChunk == 0L) break;
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    private static void serveDirectUpstream(Entry entry, String rangeHeader, boolean isHead, OutputStream out) throws Exception {
        HttpURLConnection conn = null;
        try {
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

            if (isHead || code >= 400) {
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
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private static byte[] readUpTo(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(maxBytes, 65536));
        byte[] buf = new byte[16384];
        int total = 0;
        while (total < maxBytes) {
            int toRead = Math.min(buf.length, maxBytes - total);
            int n = in.read(buf, 0, toRead);
            if (n <= 0) break;
            bos.write(buf, 0, n);
            total += n;
        }
        return bos.toByteArray();
    }

    private static HttpURLConnection openFollowingRedirects(String rawUrl, Map<String, String> headers,
                                                          String rangeHeader) throws Exception {
        String cur = rawUrl;
        for (int hops = 0; hops < 5; hops++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(cur).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(12000);
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

    private static long parseLongSafe(String s, long fallback) {
        try { return TextUtils.isEmpty(s) ? fallback : Long.parseLong(s.trim()); }
        catch (Throwable t) { return fallback; }
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
