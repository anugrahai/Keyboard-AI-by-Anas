package com.annas;

import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Mesin YouTube Native Java Murni (Innertube + Multi-Range Downloader).
 * - Scan single video, playlist ratusan lagu, channel, & pencarian YouTube selesai dalam 0.5-3 detik
 *   tanpa overhead menjalankan proses Python yt-dlp.
 * - Pengambilan direct stream via ANDROID_VR / IOS Innertube + multi-range GET paralel (4 thread/file)
 *   agar unduhan lagu 5 menit selesai dalam hitungan detik.
 * - Jika suatu video dibatasi atau platform non-YouTube, MediaDownloaderEngine otomatis fallback ke yt-dlp.
 */
public final class YouTubeFastBridge {

    private static final String TAG = "YouTubeFastBridge";

    private static final String UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
    private static final String UA_ANDROID = "com.google.android.youtube/20.10.38 (Linux; U; Android 14; Pixel 8) gzip";
    private static final String UA_VR = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
    private static final String UA_IOS = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X)";

    private static final int CLIENT_ANDROID = 0;
    private static final int CLIENT_IOS = 1;
    private static final int CLIENT_VR = 2;

    private static final Pattern PAT_YT_ID = Pattern.compile("(?:v=|youtu\\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})");
    private static final Pattern PAT_LIST_ID = Pattern.compile("[?&]list=([A-Za-z0-9_-]+)");

    private YouTubeFastBridge() {}

    public static final class StreamSpec {
        public String videoUrl = "";
        public String audioUrl = "";
        public String userAgent = UA_VR;
        public String audioExt = "m4a";
        public long videoBytes = 0L;
        public long audioBytes = 0L;
        public boolean progressiveSingleFile = false;
    }

    public interface ByteProgressCallback {
        void onBytes(long downloadedBytes, long totalBytes);
        boolean isCancelled();
    }

    public static boolean isYouTubeUrlOrSearch(String input) {
        if (input == null) return false;
        String t = input.trim();
        if (!MediaDownloaderEngine.isLikelyUrl(t)) return true; // Kata kunci pencarian YouTube
        String low = t.toLowerCase(Locale.ROOT);
        return low.contains("youtube.com") || low.contains("youtu.be");
    }

    public static String extractYouTubeVideoId(String url) {
        if (url == null) return "";
        Matcher m = PAT_YT_ID.matcher(url);
        return m.find() ? m.group(1) : "";
    }

    public static String extractYouTubePlaylistId(String url) {
        if (url == null) return "";
        Matcher m = PAT_LIST_ID.matcher(url);
        return m.find() ? m.group(1) : "";
    }

    // =========================================================================
    // 1. SCAN CEPAT YOUTUBE (SEARCH / SINGLE / PLAYLIST / CHANNEL) 0.5-3 DETIK
    // =========================================================================

    public static MediaDownloaderEngine.ScanResult scanFast(String rawInput, int maxItems) throws Exception {
        long t0 = System.currentTimeMillis();
        String input = rawInput == null ? "" : rawInput.trim();
        int limit = Math.max(1, Math.min(maxItems <= 0 ? 200 : maxItems, 500));

        MediaDownloaderEngine.ScanResult res = new MediaDownloaderEngine.ScanResult();
        res.sourceKind = "YouTube";

        if (!MediaDownloaderEngine.isLikelyUrl(input)) {
            res.containerKind = "Pencarian";
            res.containerTitle = "Hasil: " + input;
            searchYouTubeInnertube(input, Math.min(limit, 30), res.items);
            Log.d(TAG, "Fast search done in " + (System.currentTimeMillis() - t0) + "ms, items=" + res.items.size());
            if (res.items.isEmpty()) throw new IllegalStateException("Pencarian kosong.");
            return res;
        }

        String listId = extractYouTubePlaylistId(input);
        String videoId = extractYouTubeVideoId(input);
        String low = input.toLowerCase(Locale.ROOT);

        boolean isChannel = low.contains("/@") || low.contains("/channel/") || low.contains("/c/")
                || low.contains("/user/") || low.endsWith("/videos") || low.endsWith("/shorts");

        if (!listId.isEmpty() && !listId.startsWith("RD")) {
            res.containerKind = "Playlist";
            fetchPlaylistInnertube(listId, limit, res);
            Log.d(TAG, "Fast playlist done in " + (System.currentTimeMillis() - t0) + "ms, items=" + res.items.size());
            if (!res.items.isEmpty()) return res;
        }

        if (isChannel) {
            res.containerKind = "Channel";
            fetchChannelInnertube(input, limit, res);
            Log.d(TAG, "Fast channel done in " + (System.currentTimeMillis() - t0) + "ms, items=" + res.items.size());
            if (!res.items.isEmpty()) return res;
        }

        if (!videoId.isEmpty()) {
            res.containerKind = "Single";
            MediaDownloaderEngine.MediaItem single = fetchSingleMetadataFast(videoId, input);
            res.containerTitle = single.title;
            res.items.add(single);
            Log.d(TAG, "Fast single done in " + (System.currentTimeMillis() - t0) + "ms: " + single.title);
            return res;
        }

        throw new IllegalStateException("Bukan tautan YouTube yang dikenali untuk fast scan.");
    }

    private static MediaDownloaderEngine.MediaItem fetchSingleMetadataFast(String videoId, String origUrl) throws Exception {
        // 1. Coba oEmbed super ringan (< 500ms)
        try {
            String oeUrl = "https://www.youtube.com/oembed?url="
                    + URLEncoder.encode("https://www.youtube.com/watch?v=" + videoId, "UTF-8")
                    + "&format=json";
            String raw = httpGetString(oeUrl, UA_WEB, 7000);
            JSONObject o = new JSONObject(raw);
            String title = o.optString("title", "").trim();
            if (!title.isEmpty()) {
                MediaDownloaderEngine.MediaItem it = new MediaDownloaderEngine.MediaItem();
                it.id = videoId;
                it.title = title;
                it.uploader = o.optString("author_name", "").trim();
                it.webpageUrl = "https://www.youtube.com/watch?v=" + videoId;
                it.sourceKind = "YouTube";
                it.containerKind = "Single";
                // Ambil durasi ringan jika tersedia dari player cepat? Skip agar tetap < 1 detik!
                return it;
            }
        } catch (Throwable ignored) {}

        // 2. Cadangan: Innertube ANDROID player metadata
        JSONObject player = callInnertubePlayerByClient(videoId, CLIENT_ANDROID);
        JSONObject vd = player.optJSONObject("videoDetails");
        if (vd != null) {
            MediaDownloaderEngine.MediaItem it = new MediaDownloaderEngine.MediaItem();
            it.id = videoId;
            it.title = vd.optString("title", "Video_" + videoId);
            it.uploader = vd.optString("author", "");
            long dur = vd.optLong("lengthSeconds", 0L);
            it.durationSec = dur;
            it.durationText = MediaDownloaderEngine.formatDuration(dur);
            it.webpageUrl = "https://www.youtube.com/watch?v=" + videoId;
            it.sourceKind = "YouTube";
            it.containerKind = "Single";
            return it;
        }
        throw new IllegalStateException("Metadata video tidak ditemukan.");
    }

    private static void searchYouTubeInnertube(String query, int limit, List<MediaDownloaderEngine.MediaItem> out) throws Exception {
        JSONObject body = buildWebContext();
        body.put("query", query);
        String resp = httpPostJson("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", body.toString(), UA_WEB, 10000);
        JSONObject root = new JSONObject(resp);
        Set<String> seen = new HashSet<>();
        extractItemsRecursive(root, out, seen, limit, "Pencarian", "Hasil: " + query, "");
    }

    private static void fetchPlaylistInnertube(String listId, int limit, MediaDownloaderEngine.ScanResult res) throws Exception {
        JSONObject body = buildWebContext();
        body.put("browseId", listId.startsWith("VL") ? listId : ("VL" + listId));
        String resp = httpPostJson("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body.toString(), UA_WEB, 12000);
        JSONObject root = new JSONObject(resp);

        String plTitle = extractPlaylistTitle(root);
        if (!plTitle.isEmpty()) res.containerTitle = plTitle;
        else res.containerTitle = "Playlist_" + listId;

        Set<String> seen = new HashSet<>();
        List<String> contTokens = new ArrayList<>();
        extractItemsRecursive(root, res.items, seen, limit, "Playlist", res.containerTitle, "");
        extractContinuationTokens(root, contTokens);

        int page = 0;
        while (res.items.size() < limit && !contTokens.isEmpty() && page < 6) {
            page++;
            String token = contTokens.remove(0);
            JSONObject nextBody = buildWebContext();
            nextBody.put("continuation", token);
            String nextResp = httpPostJson("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", nextBody.toString(), UA_WEB, 10000);
            JSONObject nextRoot = new JSONObject(nextResp);
            int before = res.items.size();
            extractItemsRecursive(nextRoot, res.items, seen, limit, "Playlist", res.containerTitle, "");
            extractContinuationTokens(nextRoot, contTokens);
            if (res.items.size() == before) break;
        }
    }

    private static void fetchChannelInnertube(String channelUrl, int limit, MediaDownloaderEngine.ScanResult res) throws Exception {
        JSONObject resBody = buildWebContext();
        resBody.put("url", channelUrl);
        String rStr = httpPostJson("https://www.youtube.com/youtubei/v1/navigation/resolve_url?prettyPrint=false", resBody.toString(), UA_WEB, 10000);
        JSONObject rJson = new JSONObject(rStr);
        JSONObject endpoint = rJson.optJSONObject("endpoint");
        JSONObject browseEp = endpoint != null ? endpoint.optJSONObject("browseEndpoint") : null;
        String browseId = browseEp != null ? browseEp.optString("browseId", "") : "";
        if (browseId.isEmpty()) throw new IllegalStateException("Channel ID tidak ditemukan.");

        JSONObject body = buildWebContext();
        body.put("browseId", browseId);
        body.put("params", "EgZ2aWRlb3PyBgQKAjoA"); // Tab Videos
        String resp = httpPostJson("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", body.toString(), UA_WEB, 12000);
        JSONObject root = new JSONObject(resp);

        String chTitle = extractChannelTitle(root);
        res.containerTitle = chTitle.isEmpty() ? browseId : chTitle;

        Set<String> seen = new HashSet<>();
        List<String> contTokens = new ArrayList<>();
        extractItemsRecursive(root, res.items, seen, limit, "Channel", res.containerTitle, res.containerTitle);
        extractContinuationTokens(root, contTokens);

        int page = 0;
        while (res.items.size() < limit && !contTokens.isEmpty() && page < 6) {
            page++;
            String token = contTokens.remove(0);
            JSONObject nextBody = buildWebContext();
            nextBody.put("continuation", token);
            String nextResp = httpPostJson("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", nextBody.toString(), UA_WEB, 10000);
            JSONObject nextRoot = new JSONObject(nextResp);
            int before = res.items.size();
            extractItemsRecursive(nextRoot, res.items, seen, limit, "Channel", res.containerTitle, res.containerTitle);
            extractContinuationTokens(nextRoot, contTokens);
            if (res.items.size() == before) break;
        }
    }

    // =========================================================================
    // 2. PENGAMBILAN STREAM URL CEPAT + MULTI-RANGE DOWNLOADER (JAVA MURNI)
    // =========================================================================

    public static StreamSpec fetchFastStreamSpec(String youtubeUrl, boolean wantVideo, int maxHeight,
                                                 String targetAudioMode, int targetBitrateKbps) throws Exception {
        String videoId = extractYouTubeVideoId(youtubeUrl);
        if (videoId.isEmpty()) throw new IllegalArgumentException("Bukan URL video YouTube.");

        // Urutan client: ANDROID (mendukung itag=18 progresif + adaptive), IOS, ANDROID_VR.
        // Cek juga probe > 1 MB agar stream yang dibatasi PO-token di 1 MB langsung terdeteksi & dialihkan ke itag=18.
        int[] clients = {CLIENT_ANDROID, CLIENT_IOS, CLIENT_VR};
        Throwable lastErr = null;
        for (int clientType : clients) {
            try {
                JSONObject player = callInnertubePlayerByClient(videoId, clientType);
                JSONObject ps = player.optJSONObject("playabilityStatus");
                String status = ps != null ? ps.optString("status", "") : "";
                if (!"OK".equalsIgnoreCase(status)) {
                    throw new IllegalStateException("Playability: " + status);
                }
                JSONObject sd = player.optJSONObject("streamingData");
                if (sd == null) throw new IllegalStateException("streamingData kosong");

                StreamSpec spec = new StreamSpec();
                spec.userAgent = uaForClient(clientType);

                if (!wantVideo) {
                    pickBestAudioFormat(sd.optJSONArray("adaptiveFormats"), targetAudioMode, targetBitrateKbps, spec);
                    if (!spec.audioUrl.isEmpty() && probeStreamPlayable(spec.audioUrl, spec.userAgent, spec.audioBytes)) {
                        return spec;
                    }
                    // Jika adaptive 140 diblokir di >1MB (VEVO/musik), pakai progresif MP4 (itag=18) yang kebal blokir 1MB
                    spec.audioUrl = "";
                    spec.audioBytes = 0L;
                    pickProgressiveVideo(sd.optJSONArray("formats"), 480, spec);
                    if (!spec.videoUrl.isEmpty() && probeStreamPlayable(spec.videoUrl, spec.userAgent, spec.videoBytes)) {
                        spec.audioUrl = spec.videoUrl;
                        spec.audioBytes = spec.videoBytes;
                        spec.audioExt = "mp4";
                        spec.progressiveSingleFile = true;
                        return spec;
                    }
                    throw new IllegalStateException("Audio URL ditolak (403)");
                } else {
                    if (maxHeight <= 720) {
                        pickProgressiveVideo(sd.optJSONArray("formats"), maxHeight, spec);
                        if (!spec.videoUrl.isEmpty() && probeStreamPlayable(spec.videoUrl, spec.userAgent, spec.videoBytes)) {
                            return spec;
                        }
                        spec.videoUrl = "";
                        spec.progressiveSingleFile = false;
                    }
                    pickBestVideoAndAudio(sd.optJSONArray("adaptiveFormats"), maxHeight, spec);
                    if (!spec.videoUrl.isEmpty() && !spec.audioUrl.isEmpty()
                            && probeStreamPlayable(spec.videoUrl, spec.userAgent, spec.videoBytes)
                            && probeStreamPlayable(spec.audioUrl, spec.userAgent, spec.audioBytes)) {
                        return spec;
                    }
                    throw new IllegalStateException("Video URL ditolak (403)");
                }
            } catch (Throwable t) {
                lastErr = t;
                Log.w(TAG, "Innertube client " + clientType + " failed: " + t.getMessage());
            }
        }
        throw new IllegalStateException("Fast stream gagal: " + (lastErr != null ? lastErr.getMessage() : "unknown"));
    }

    private static boolean probeStreamPlayable(String urlStr, String ua, long knownBytes) {
        // Jika file > 1.2 MB, langsung probe byte 1.1 MB dalam 1 roundtrip (jika 206, awal file pasti OK & bebas blokir 1 MB!)
        if (knownBytes <= 0 || knownBytes > 1200000L) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", ua);
                conn.setRequestProperty("Range", "bytes=1100000-1100001");
                int code = conn.getResponseCode();
                if (code == 200 || code == 206) return true;
                if (code != 416) return false;
            } catch (Throwable t) {
                return false;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return probeRangeByte(urlStr, ua, "bytes=0-1");
    }

    private static boolean probeRangeByte(String urlStr, String ua, String rangeVal) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Range", rangeVal);
            int code = conn.getResponseCode();
            return code == 200 || code == 206 || code == 416;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void pickBestAudioFormat(JSONArray adaptive, String mode, int targetKbps, StreamSpec spec) {
        if (adaptive == null) return;
        boolean preferOpus = MediaDownloaderEngine.MODE_AUDIO_OPUS.equals(mode);
        JSONObject bestMatch = null;
        int bestScore = -1;

        for (int i = 0; i < adaptive.length(); i++) {
            JSONObject f = adaptive.optJSONObject(i);
            if (f == null) continue;
            String url = f.optString("url", "");
            String mime = f.optString("mimeType", "").toLowerCase(Locale.ROOT);
            if (url.isEmpty() || !mime.startsWith("audio/")) continue;

            int bitrate = f.optInt("averageBitrate", f.optInt("bitrate", 128000)) / 1000;
            boolean isM4a = mime.contains("audio/mp4") || mime.contains("mp4a");
            boolean isOpus = mime.contains("opus");

            int score = 0;
            if (preferOpus) {
                score += isOpus ? 500 : 100;
            } else {
                score += isM4a ? 500 : 150;
            }
            // Pilih bitrate yang cukup tinggi untuk target, tapi tidak kegedean
            if (targetKbps <= 128 && bitrate <= 150) score += 200;
            else if (targetKbps > 128 && bitrate >= 120) score += 200;
            score += Math.min(bitrate, 256);

            if (score > bestScore) {
                bestScore = score;
                bestMatch = f;
            }
        }
        if (bestMatch != null) {
            spec.audioUrl = bestMatch.optString("url", "");
            String mime = bestMatch.optString("mimeType", "").toLowerCase(Locale.ROOT);
            spec.audioExt = mime.contains("mp4") ? "m4a" : "webm";
            spec.audioBytes = parseLongSafe(bestMatch.optString("contentLength", "0"));
        }
    }

    private static void pickProgressiveVideo(JSONArray formats, int maxHeight, StreamSpec spec) {
        if (formats == null) return;
        JSONObject best = null;
        int bestH = -1;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (f == null) continue;
            String url = f.optString("url", "");
            String mime = f.optString("mimeType", "").toLowerCase(Locale.ROOT);
            int h = f.optInt("height", 0);
            if (url.isEmpty() || !mime.contains("video/mp4") || h <= 0 || h > maxHeight) continue;
            if (h > bestH) {
                bestH = h;
                best = f;
            }
        }
        if (best != null) {
            spec.videoUrl = best.optString("url", "");
            spec.videoBytes = parseLongSafe(best.optString("contentLength", "0"));
            spec.progressiveSingleFile = true;
        }
    }

    private static void pickBestVideoAndAudio(JSONArray adaptive, int maxHeight, StreamSpec spec) {
        if (adaptive == null) return;
        JSONObject bestVid = null;
        int bestVidScore = -1;
        for (int i = 0; i < adaptive.length(); i++) {
            JSONObject f = adaptive.optJSONObject(i);
            if (f == null) continue;
            String url = f.optString("url", "");
            String mime = f.optString("mimeType", "").toLowerCase(Locale.ROOT);
            int h = f.optInt("height", 0);
            if (url.isEmpty() || !mime.startsWith("video/") || h <= 0 || h > maxHeight) continue;
            int score = h * 10 + (mime.contains("video/mp4") ? 5 : 0) + f.optInt("fps", 30);
            if (score > bestVidScore) {
                bestVidScore = score;
                bestVid = f;
            }
        }
        if (bestVid != null) {
            spec.videoUrl = bestVid.optString("url", "");
            spec.videoBytes = parseLongSafe(bestVid.optString("contentLength", "0"));
        }
        pickBestAudioFormat(adaptive, MediaDownloaderEngine.MODE_AUDIO_M4A, 128, spec);
    }

    /**
     * Mengunduh URL direct stream secara cepat memakai 4 koneksi Range paralel (jika contentLength > 512 KB),
     * atau fallback streaming tunggal jika ukuran belum diketahui.
     */
    public static void downloadUrlFast(String urlStr, String userAgent, long knownLength, File outFile,
                                       ByteProgressCallback cb) throws Exception {
        long totalLen = knownLength;
        if (totalLen <= 0) {
            // Probe byte 0-0 untuk mengetahui Content-Range total length tanpa ditolak HEAD
            totalLen = probeTotalLength(urlStr, userAgent);
        }

        // Gunakan 4 koneksi Range paralel untuk file > 600 KB (tembus throttle per-koneksi YouTube!)
        if (totalLen > 600 * 1024L) {
            try {
                downloadParallelRanges(urlStr, userAgent, totalLen, outFile, cb);
                return;
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Throwable parallelFail) {
                Log.w(TAG, "Parallel range fallback to single GET: " + parallelFail.getMessage());
                if (cb != null && cb.isCancelled()) throw new InterruptedException("Dibatalkan.");
            }
        }
        downloadSingleGet(urlStr, userAgent, totalLen, outFile, cb);
    }

    private static void downloadParallelRanges(final String urlStr, final String userAgent,
                                               final long totalLen, File outFile,
                                               final ByteProgressCallback cb) throws Exception {
        final int parts = 6;
        final long chunk = totalLen / parts;
        final AtomicLong downloaded = new AtomicLong(0L);
        final AtomicBoolean aborted = new AtomicBoolean(false);

        try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {
            raf.setLength(totalLen);
        }

        ExecutorService pool = Executors.newFixedThreadPool(parts);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < parts; i++) {
                final long start = i * chunk;
                final long end = (i == parts - 1) ? (totalLen - 1) : ((i + 1) * chunk - 1);
                futures.add(pool.submit((Callable<Void>) () -> {
                    downloadRangeSlice(urlStr, userAgent, start, end, outFile, totalLen, downloaded, aborted, cb);
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (Throwable t) {
            aborted.set(true);
            throw (t instanceof Exception) ? (Exception) t : new RuntimeException(t);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void downloadRangeSlice(String urlStr, String userAgent, long start, long end,
                                           File outFile, long totalLen, AtomicLong downloaded,
                                           AtomicBoolean aborted, ByteProgressCallback cb) throws Exception {
        // Pecah lagi ke sub-request 512 KB agar googlevideo selalu ngasih speed penuh tanpa throttle
        long pos = start;
        final long subSize = 512 * 1024L;
        byte[] buf = new byte[32768];

        try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {
            while (pos <= end) {
                if (aborted.get() || (cb != null && cb.isCancelled())) {
                    aborted.set(true);
                    throw new InterruptedException("Dibatalkan.");
                }
                long subEnd = Math.min(end, pos + subSize - 1);
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("Range", "bytes=" + pos + "-" + subEnd);
                int code = conn.getResponseCode();
                if (code != 206 && code != 200) {
                    conn.disconnect();
                    throw new IllegalStateException("HTTP " + code);
                }
                raf.seek(pos);
                try (InputStream is = new BufferedInputStream(conn.getInputStream(), 32768)) {
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        if (aborted.get() || (cb != null && cb.isCancelled())) {
                            aborted.set(true);
                            throw new InterruptedException("Dibatalkan.");
                        }
                        raf.write(buf, 0, n);
                        pos += n;
                        long cur = downloaded.addAndGet(n);
                        if (cb != null) cb.onBytes(cur, totalLen);
                    }
                } finally {
                    conn.disconnect();
                }
                if (code == 200) break; // Server mengirim full stream bukan partial
            }
        }
    }

    private static void downloadSingleGet(String urlStr, String userAgent, long knownLen, File outFile,
                                          ByteProgressCallback cb) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(25000);
        conn.setRequestProperty("User-Agent", userAgent);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }
        long total = knownLen > 0 ? knownLen : conn.getContentLengthLong();
        byte[] buf = new byte[32768];
        long readSum = 0L;
        try (InputStream is = new BufferedInputStream(conn.getInputStream(), 32768);
             OutputStream os = new FileOutputStream(outFile, false)) {
            int n;
            while ((n = is.read(buf)) > 0) {
                if (cb != null && cb.isCancelled()) throw new InterruptedException("Dibatalkan.");
                os.write(buf, 0, n);
                readSum += n;
                if (cb != null) cb.onBytes(readSum, total);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static long probeTotalLength(String urlStr, String userAgent) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Range", "bytes=0-0");
            int code = conn.getResponseCode();
            if (code == 206) {
                String cr = conn.getHeaderField("Content-Range");
                if (cr != null && cr.contains("/")) {
                    return parseLongSafe(cr.substring(cr.lastIndexOf('/') + 1).trim());
                }
            }
            return conn.getContentLengthLong();
        } catch (Throwable ignored) {
            return -1L;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================================
    // HELPERS INNERTUBE & JSON PARSING
    // =========================================================================

    private static String uaForClient(int clientType) {
        if (clientType == CLIENT_IOS) return UA_IOS;
        if (clientType == CLIENT_VR) return UA_VR;
        return UA_ANDROID;
    }

    private static JSONObject callInnertubePlayer(String videoId, boolean useVr) throws Exception {
        return callInnertubePlayerByClient(videoId, useVr ? CLIENT_VR : CLIENT_IOS);
    }

    private static JSONObject callInnertubePlayerByClient(String videoId, int clientType) throws Exception {
        JSONObject root = new JSONObject();
        JSONObject ctx = new JSONObject();
        JSONObject client = new JSONObject();
        if (clientType == CLIENT_VR) {
            client.put("clientName", "ANDROID_VR");
            client.put("clientVersion", "1.65.10");
            client.put("deviceModel", "Quest 3");
            client.put("androidSdkVersion", 32);
        } else if (clientType == CLIENT_IOS) {
            client.put("clientName", "IOS");
            client.put("clientVersion", "20.10.4");
            client.put("deviceModel", "iPhone16,2");
            client.put("osName", "iPhone");
            client.put("osVersion", "18.3.1.22D72");
        } else {
            client.put("clientName", "ANDROID");
            client.put("clientVersion", "20.10.38");
            client.put("deviceModel", "Pixel 8");
            client.put("osName", "Android");
            client.put("osVersion", "14");
            client.put("androidSdkVersion", 34);
        }
        client.put("hl", "id");
        client.put("gl", "ID");
        ctx.put("client", client);
        root.put("context", ctx);
        root.put("videoId", videoId);
        root.put("contentCheckOk", true);
        root.put("racyCheckOk", true);

        String resp = httpPostJson("https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                root.toString(), uaForClient(clientType), 8000);
        return new JSONObject(resp);
    }

    private static JSONObject buildWebContext() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject ctx = new JSONObject();
        JSONObject client = new JSONObject();
        client.put("clientName", "WEB");
        client.put("clientVersion", "2.20251101.00.00");
        client.put("hl", "id");
        client.put("gl", "ID");
        ctx.put("client", client);
        root.put("context", ctx);
        return root;
    }

    private static String httpGetString(String urlStr, String ua, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", ua);
        conn.setRequestProperty("Accept-Encoding", "gzip");
        try {
            return readStreamAsString(conn);
        } finally {
            conn.disconnect();
        }
    }

    private static String httpPostJson(String urlStr, String jsonBody, String ua, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", ua);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        try {
            return readStreamAsString(conn);
        } finally {
            conn.disconnect();
        }
    }

    private static String readStreamAsString(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream raw = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (raw == null) throw new IllegalStateException("HTTP " + code);
        String enc = conn.getContentEncoding();
        InputStream is = ("gzip".equalsIgnoreCase(enc)) ? new GZIPInputStream(raw) : raw;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        String str = bos.toString("UTF-8");
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        return str;
    }

    private static void extractItemsRecursive(Object node, List<MediaDownloaderEngine.MediaItem> out,
                                              Set<String> seenIds, int limit,
                                              String containerKind, String containerName, String fallbackUploader) {
        if (node == null || out.size() >= limit) return;
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length() && out.size() < limit; i++) {
                extractItemsRecursive(arr.opt(i), out, seenIds, limit, containerKind, containerName, fallbackUploader);
            }
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject obj = (JSONObject) node;

        // Cek apakah node ini merupakan renderer video/playlist (memiliki videoId & title)
        String vid = obj.optString("videoId", "");
        if (vid.length() == 11 && (obj.has("title") || obj.has("headline"))) {
            if (!seenIds.contains(vid)) {
                String title = readTextField(obj.opt("title"));
                if (title.isEmpty()) title = readTextField(obj.opt("headline"));
                if (!title.isEmpty() && !"[Private video]".equalsIgnoreCase(title) && !"[Deleted video]".equalsIgnoreCase(title)) {
                    seenIds.add(vid);
                    MediaDownloaderEngine.MediaItem it = new MediaDownloaderEngine.MediaItem();
                    it.id = vid;
                    it.title = title;
                    String up = readTextField(obj.opt("ownerText"));
                    if (up.isEmpty()) up = readTextField(obj.opt("shortBylineText"));
                    if (up.isEmpty()) up = readTextField(obj.opt("longBylineText"));
                    it.uploader = up.isEmpty() ? fallbackUploader : up;
                    String dur = readTextField(obj.opt("lengthText"));
                    it.durationText = dur;
                    it.webpageUrl = "https://www.youtube.com/watch?v=" + vid;
                    it.sourceKind = "YouTube";
                    it.containerKind = containerKind;
                    it.containerName = containerName;
                    out.add(it);
                }
            }
        }

        // Juga cek format lockupViewModel terbaru (2025/2026) untuk playlist/channel modern
        if (obj.has("lockupViewModel") && out.size() < limit) {
            JSONObject lvm = obj.optJSONObject("lockupViewModel");
            String cid = lvm != null ? lvm.optString("contentId", "") : "";
            if (cid.length() == 11 && !seenIds.contains(cid)) {
                String title = readLockupTitle(lvm);
                if (!title.isEmpty()) {
                    seenIds.add(cid);
                    MediaDownloaderEngine.MediaItem it = new MediaDownloaderEngine.MediaItem();
                    it.id = cid;
                    it.title = title;
                    it.uploader = fallbackUploader;
                    it.webpageUrl = "https://www.youtube.com/watch?v=" + cid;
                    it.sourceKind = "YouTube";
                    it.containerKind = containerKind;
                    it.containerName = containerName;
                    out.add(it);
                }
            }
        }

        Iterator<String> keys = obj.keys();
        while (keys.hasNext() && out.size() < limit) {
            String k = keys.next();
            Object child = obj.opt(k);
            if (child instanceof JSONObject || child instanceof JSONArray) {
                extractItemsRecursive(child, out, seenIds, limit, containerKind, containerName, fallbackUploader);
            }
        }
    }

    private static String readLockupTitle(JSONObject lvm) {
        try {
            JSONObject md = lvm.optJSONObject("metadata");
            JSONObject lm = md != null ? md.optJSONObject("lockupMetadataViewModel") : null;
            JSONObject titleObj = lm != null ? lm.optJSONObject("title") : null;
            if (titleObj != null) return titleObj.optString("content", "").trim();
        } catch (Throwable ignored) {}
        return "";
    }

    private static void extractContinuationTokens(Object node, List<String> tokens) {
        if (node == null) return;
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) extractContinuationTokens(arr.opt(i), tokens);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject obj = (JSONObject) node;
        if (obj.has("continuationCommand")) {
            JSONObject cc = obj.optJSONObject("continuationCommand");
            String t = cc != null ? cc.optString("token", "") : "";
            if (!t.isEmpty() && !tokens.contains(t)) tokens.add(t);
        }
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            Object ch = obj.opt(keys.next());
            if (ch instanceof JSONObject || ch instanceof JSONArray) extractContinuationTokens(ch, tokens);
        }
    }

    private static String extractPlaylistTitle(JSONObject root) {
        try {
            JSONObject hdr = root.optJSONObject("header");
            if (hdr != null) {
                JSONObject ph = hdr.optJSONObject("playlistHeaderRenderer");
                if (ph != null) {
                    String t = readTextField(ph.opt("title"));
                    if (!t.isEmpty()) return t;
                }
                JSONObject pgh = hdr.optJSONObject("pageHeaderRenderer");
                if (pgh != null) {
                    String t = pgh.optString("pageTitle", "").trim();
                    if (!t.isEmpty()) return t;
                }
            }
            JSONObject meta = root.optJSONObject("metadata");
            JSONObject pm = meta != null ? meta.optJSONObject("playlistMetadataRenderer") : null;
            if (pm != null) return pm.optString("title", "").trim();
        } catch (Throwable ignored) {}
        return "";
    }

    private static String extractChannelTitle(JSONObject root) {
        try {
            JSONObject meta = root.optJSONObject("metadata");
            JSONObject cm = meta != null ? meta.optJSONObject("channelMetadataRenderer") : null;
            if (cm != null) return cm.optString("title", "").trim();
        } catch (Throwable ignored) {}
        return "";
    }

    private static String readTextField(Object field) {
        if (field == null) return "";
        if (field instanceof String) return ((String) field).trim();
        if (field instanceof JSONObject) {
            JSONObject o = (JSONObject) field;
            String s = o.optString("simpleText", "").trim();
            if (!s.isEmpty()) return s;
            String content = o.optString("content", "").trim();
            if (!content.isEmpty()) return content;
            JSONArray runs = o.optJSONArray("runs");
            if (runs != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < runs.length(); i++) {
                    JSONObject r = runs.optJSONObject(i);
                    if (r != null) sb.append(r.optString("text", ""));
                }
                return sb.toString().trim();
            }
        }
        return "";
    }

    private static long parseLongSafe(String s) {
        try { return TextUtils.isEmpty(s) ? 0L : Long.parseLong(s.trim()); }
        catch (Throwable t) { return 0L; }
    }
}
