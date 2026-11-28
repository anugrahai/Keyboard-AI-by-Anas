package com.annas;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import kotlin.Unit;

/**
 * Mesin Pengunduh Lagu, Video, Playlist, Channel & Pencarian YouTube berbasis yt-dlp + FFmpeg 8.1.
 * - Menggunakan downloader native yt-dlp (--concurrent-fragments 4 --newline) agar progres real-time
 *   tidak macet di 0% dan bebas hang aria2c pada URL stream YouTube.
 * - Mendukung auto-update engine yt-dlp berkala di background agar kecepatan & ekstraksi selalu segar.
 */
public final class MediaDownloaderEngine {

    private static final String TAG = "MediaDownloaderEngine";
    private static final String ENGINE_PREFS = "MediaDownloaderEnginePrefs";
    private static final String KEY_LAST_YTDLP_UPDATE = "last_ytdlp_update_ms";
    private static final long AUTO_UPDATE_INTERVAL_MS = 3L * 24L * 60L * 60L * 1000L; // 3 hari

    private MediaDownloaderEngine() {}

    public static final String MODE_AUDIO_MP3 = "AUDIO_MP3";
    public static final String MODE_AUDIO_M4A = "AUDIO_M4A";
    public static final String MODE_AUDIO_OPUS = "AUDIO_OPUS";
    public static final String MODE_AUDIO_WAV = "AUDIO_WAV";
    public static final String MODE_VIDEO_360 = "VIDEO_360";
    public static final String MODE_VIDEO_480 = "VIDEO_480";
    public static final String MODE_VIDEO_720 = "VIDEO_720";
    public static final String MODE_VIDEO_1080 = "VIDEO_1080";
    public static final String MODE_VIDEO_BEST = "VIDEO_BEST";

    private static final Object INIT_LOCK = new Object();
    private static final Object UPDATE_LOCK = new Object();
    private static volatile boolean ytdlReady = false;
    private static volatile boolean autoUpdateCheckedThisSession = false;

    public static final class MediaItem {
        public String id = "";
        public String title = "";
        public String uploader = "";
        public String durationText = "";
        public long durationSec = 0L;
        public String webpageUrl = "";
        public String sourceKind = "YouTube";   // YouTube, SoundCloud, TikTok, Instagram, Facebook, Lainnya
        public String containerKind = "Single"; // Single, Playlist, Channel, Pencarian
        public String containerName = "";
        public boolean selected = false;
    }

    public static final class ScanResult {
        public String containerKind = "Single";
        public String containerTitle = "";
        public String sourceKind = "YouTube";
        public final List<MediaItem> items = new ArrayList<>();
    }

    public interface ProgressListener {
        void onProgress(int percent, String detail);
        boolean isCancelled();
        void onFfmpegSessionStarted(long sessionId);
    }

    public static void ensureInitialized(Context ctx) throws YoutubeDLException {
        if (ytdlReady) return;
        synchronized (INIT_LOCK) {
            if (ytdlReady) return;
            Context appCtx = ctx.getApplicationContext();
            long t0 = System.currentTimeMillis();
            YoutubeDL.getInstance().init(appCtx);
            ytdlReady = true;
            Log.d(TAG, "YoutubeDL initialized in " + (System.currentTimeMillis() - t0) + " ms");
        }
    }

    /** Memanaskan runtime Python/yt-dlp di latar belakang saat menu dibuka agar scan terasa instan. */
    public static void warmUpAsync(final Context ctx) {
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                ensureInitialized(appCtx);
                autoUpdateIfDue(appCtx);
            } catch (Throwable t) {
                Log.w(TAG, "Warmup skipped: " + t.getMessage());
            }
        }, "ytdlp-warmup").start();
    }

    public static void autoUpdateIfDue(Context ctx) {
        if (autoUpdateCheckedThisSession || ctx == null) return;
        autoUpdateCheckedThisSession = true;
        try {
            SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(ENGINE_PREFS, Context.MODE_PRIVATE);
            long last = sp.getLong(KEY_LAST_YTDLP_UPDATE, 0L);
            if (System.currentTimeMillis() - last < AUTO_UPDATE_INTERVAL_MS) return;
            synchronized (UPDATE_LOCK) {
                ensureInitialized(ctx);
                YoutubeDL.UpdateStatus st = YoutubeDL.getInstance().updateYoutubeDL(
                        ctx.getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
                sp.edit().putLong(KEY_LAST_YTDLP_UPDATE, System.currentTimeMillis()).apply();
                Log.d(TAG, "Auto update yt-dlp status: " + st);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Auto update yt-dlp failed: " + t.getMessage());
        }
    }

    public static String updateYtDlpEngine(Context ctx) throws Exception {
        ensureInitialized(ctx);
        synchronized (UPDATE_LOCK) {
            YoutubeDL.UpdateStatus status = YoutubeDL.getInstance().updateYoutubeDL(
                    ctx.getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
            ctx.getApplicationContext().getSharedPreferences(ENGINE_PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_YTDLP_UPDATE, System.currentTimeMillis()).apply();
            autoUpdateCheckedThisSession = true;
            if (status == YoutubeDL.UpdateStatus.DONE) {
                return "Engine yt-dlp berhasil diperbarui ke versi terbaru.";
            }
            return "Engine yt-dlp sudah berada di versi terbaru.";
        }
    }

    public static String detectSourceKind(String url) {
        if (url == null) return "Lainnya";
        String low = url.toLowerCase(Locale.ROOT);
        if (low.contains("youtube.com") || low.contains("youtu.be") || low.startsWith("ytsearch")) {
            return "YouTube";
        }
        if (low.contains("soundcloud.com") || low.contains("on.soundcloud.com")) {
            return "SoundCloud";
        }
        if (low.contains("tiktok.com")) {
            return "TikTok";
        }
        if (low.contains("instagram.com")) {
            return "Instagram";
        }
        if (low.contains("facebook.com") || low.contains("fb.watch")) {
            return "Facebook";
        }
        if (low.contains("spotify.com")) {
            return "Spotify";
        }
        try {
            String host = Uri.parse(url).getHost();
            if (host != null && !host.trim().isEmpty()) {
                host = host.replaceFirst("^www\\.", "").replaceFirst("^m\\.", "");
                int dot = host.indexOf('.');
                if (dot > 0) host = host.substring(0, dot);
                if (!host.isEmpty()) {
                    return host.substring(0, 1).toUpperCase(Locale.ROOT) + host.substring(1);
                }
            }
        } catch (Throwable ignored) {}
        return "Lainnya";
    }

    public static boolean isLikelyUrl(String text) {
        if (text == null) return false;
        String t = text.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://");
    }

    private static boolean isCollectionUrl(String url) {
        if (url == null) return false;
        String low = url.toLowerCase(Locale.ROOT);
        return low.contains("list=") || low.contains("/playlist") || low.contains("/sets/")
                || low.contains("/@") || low.contains("/channel/") || low.contains("/c/")
                || low.contains("/user/") || low.endsWith("/videos") || low.endsWith("/shorts");
    }

    /**
     * Memindai tautan atau mencari YouTube dengan mode cepat:
     * - Single link: --no-playlist --skip-download --dump-single-json (langsung selesai)
     * - Playlist / Channel / Search: --flat-playlist --dump-single-json (tanpa unduh detail format tiap lagu)
     */
    public static ScanResult scanLinkOrSearch(Context ctx, String rawInput, int maxItems, String processId) throws Exception {
        ensureInitialized(ctx);
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isEmpty()) throw new IllegalArgumentException("Tautan atau kata kunci pencarian kosong.");

        boolean isUrl = isLikelyUrl(input);
        boolean isSearch = !isUrl;
        int limit = Math.max(1, Math.min(maxItems <= 0 ? 200 : maxItems, 500));

        // Jalur super cepat Java Innertube (0.5 - 3 detik tanpa spawn Python yt-dlp!)
        if (YouTubeFastBridge.isYouTubeUrlOrSearch(input)) {
            try {
                return YouTubeFastBridge.scanFast(input, limit);
            } catch (Throwable fastErr) {
                Log.w(TAG, "Fast scan fallback to yt-dlp: " + fastErr.getMessage());
            }
        }

        boolean isCollection = isSearch || isCollectionUrl(input);
        String target = isSearch ? ("ytsearch" + Math.min(limit, 25) + ":" + input) : input;

        YoutubeDLRequest req = new YoutubeDLRequest(target);
        req.addOption("--no-warnings");
        req.addOption("--ignore-errors");
        req.addOption("--skip-download");
        req.addOption("--socket-timeout", 15);
        req.addOption("--dump-single-json");
        if (isCollection) {
            req.addOption("--flat-playlist");
            req.addOption("--playlist-end", limit);
        } else {
            req.addOption("--no-playlist");
            // Untuk single video YouTube hanya butuh metadata dasar cepat, tidak perlu semua format berat
            if ("YouTube".equals(detectSourceKind(input))) {
                req.addOption("--no-check-formats");
            }
        }

        long t0 = System.currentTimeMillis();
        YoutubeDLResponse resp = YoutubeDL.getInstance().execute(req, processId, null);
        Log.d(TAG, "Scan done in " + (System.currentTimeMillis() - t0) + " ms");

        String out = resp != null ? resp.getOut() : "";
        if (out == null || out.trim().isEmpty()) {
            throw new IllegalStateException("Tidak ada data yang dikembalikan untuk tautan atau pencarian ini.");
        }

        String jsonStr = extractLastJsonObject(out);
        JSONObject root = new JSONObject(jsonStr);

        ScanResult result = new ScanResult();
        String detectedSource = isSearch ? "YouTube" : detectSourceKind(input);
        result.sourceKind = detectedSource;

        JSONArray entries = root.optJSONArray("entries");
        if (entries != null) {
            String rootTitle = cleanText(root.optString("title", ""));
            String uploader = cleanText(root.optString("uploader", root.optString("channel", "")));
            String cKind = isSearch ? "Pencarian" : detectContainerKindFromUrl(input, root);
            result.containerKind = cKind;
            result.containerTitle = !rootTitle.isEmpty() ? rootTitle : (isSearch ? ("Hasil: " + input) : uploader);

            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.optJSONObject(i);
                if (e == null) continue;
                JSONArray subEntries = e.optJSONArray("entries");
                if (subEntries != null) {
                    for (int j = 0; j < subEntries.length(); j++) {
                        JSONObject sub = subEntries.optJSONObject(j);
                        if (sub != null) {
                            MediaItem item = parseEntryObject(sub, detectedSource, cKind, result.containerTitle, uploader);
                            if (item != null) result.items.add(item);
                        }
                    }
                    continue;
                }
                MediaItem item = parseEntryObject(e, detectedSource, cKind, result.containerTitle, uploader);
                if (item != null) result.items.add(item);
            }
        } else {
            result.containerKind = "Single";
            result.containerTitle = cleanText(root.optString("title", ""));
            MediaItem single = parseEntryObject(root, detectedSource, "Single", "", "");
            if (single != null) {
                if (single.webpageUrl.isEmpty() && isUrl) single.webpageUrl = input;
                result.items.add(single);
            }
        }

        if (result.items.isEmpty()) {
            throw new IllegalStateException("Daftar lagu/video kosong atau konten bersifat privat.");
        }
        return result;
    }

    private static String detectContainerKindFromUrl(String url, JSONObject root) {
        String low = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String rootType = root.optString("_type", "");
        if (low.contains("/@") || low.contains("/channel/") || low.contains("/c/")
                || low.contains("/user/") || low.endsWith("/videos") || low.endsWith("/shorts")) {
            return "Channel";
        }
        if (low.contains("list=") || low.contains("/playlist") || low.contains("/sets/")
                || "playlist".equalsIgnoreCase(rootType)) {
            return "Playlist";
        }
        return "Playlist";
    }

    private static MediaItem parseEntryObject(JSONObject o, String sourceKind, String containerKind,
                                              String containerName, String fallbackUploader) {
        String title = cleanText(o.optString("title", ""));
        if (title.equalsIgnoreCase("[Deleted video]") || title.equalsIgnoreCase("[Private video]")) {
            return null;
        }
        String id = o.optString("id", "");
        String url = o.optString("webpage_url", "");
        if (url.isEmpty()) {
            String rawUrl = o.optString("url", "");
            if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                url = rawUrl;
            } else if (!id.isEmpty() && "YouTube".equals(sourceKind)) {
                url = "https://www.youtube.com/watch?v=" + id;
            } else if (!rawUrl.isEmpty() && "YouTube".equals(sourceKind)) {
                url = "https://www.youtube.com/watch?v=" + rawUrl;
            }
        }
        if (url.isEmpty()) return null;

        MediaItem item = new MediaItem();
        item.id = id.isEmpty() ? String.valueOf(url.hashCode()) : id;
        item.title = title.isEmpty() ? ("Media_" + item.id) : title;
        String up = cleanText(o.optString("uploader", o.optString("channel", o.optString("artist", ""))));
        item.uploader = up.isEmpty() ? fallbackUploader : up;
        double durDouble = o.optDouble("duration", 0);
        item.durationSec = durDouble > 0 ? Math.round(durDouble) : 0L;
        item.durationText = formatDuration(item.durationSec);
        item.webpageUrl = url;
        item.sourceKind = sourceKind;
        item.containerKind = containerKind;
        item.containerName = containerName;
        return item;
    }

    public static final class PreviewResult {
        public String url = "";
        public String userAgent = "";
        public final Map<String, String> headers = new HashMap<>();
    }

    public static PreviewResult fetchPreviewResult(Context ctx, String mediaUrl, boolean wantVideo, String processId) throws Exception {
        if (YouTubeFastBridge.isYouTubeUrlOrSearch(mediaUrl)) {
            try {
                YouTubeFastBridge.StreamSpec spec = YouTubeFastBridge.fetchFastStreamSpec(
                        mediaUrl, wantVideo, 480, MODE_AUDIO_M4A, 128);
                String u = wantVideo ? spec.videoUrl : spec.audioUrl;
                if (!TextUtils.isEmpty(u)) {
                    PreviewResult pr = new PreviewResult();
                    pr.userAgent = spec.userAgent;
                    if (!TextUtils.isEmpty(spec.userAgent)) pr.headers.put("User-Agent", spec.userAgent);
                    // Lewatkan proxy loopback lokal agar MediaPlayer Android bebas blokir HTTP 403/redirect
                    pr.url = LocalStreamProxy.getInstance().registerStream(u, pr.headers);
                    return pr;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Fast preview fallback to yt-dlp: " + t.getMessage());
            }
        }
        return fetchPreviewResultViaYtDlp(ctx, mediaUrl, wantVideo, processId);
    }

    public static PreviewResult fetchPreviewResultViaYtDlp(Context ctx, String mediaUrl, boolean wantVideo, String processId) throws Exception {
        ensureInitialized(ctx);
        YoutubeDLRequest req = new YoutubeDLRequest(mediaUrl);
        req.addOption("--no-playlist");
        req.addOption("--no-warnings");
        req.addOption("--no-check-formats");
        req.addOption("--skip-download");
        req.addOption("--socket-timeout", 10);
        req.addOption("--dump-single-json");
        if (wantVideo) {
            req.addOption("-f", "best[height<=480][ext=mp4]/best[ext=mp4]/best");
        } else {
            req.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best");
        }
        YoutubeDLResponse resp = YoutubeDL.getInstance().execute(req, processId, null);
        String out = resp != null ? resp.getOut() : "";
        if (out != null && !out.trim().isEmpty()) {
            try {
                String jsonStr = extractLastJsonObject(out);
                JSONObject root = new JSONObject(jsonStr);
                String streamUrl = root.optString("url", "").trim();
                JSONObject hdrObj = root.optJSONObject("http_headers");
                if (streamUrl.isEmpty() && root.has("requested_formats")) {
                    JSONArray rf = root.optJSONArray("requested_formats");
                    if (rf != null && rf.length() > 0) {
                        JSONObject first = rf.optJSONObject(0);
                        if (first != null) {
                            streamUrl = first.optString("url", "").trim();
                            if (hdrObj == null) hdrObj = first.optJSONObject("http_headers");
                        }
                    }
                }
                if (streamUrl.startsWith("http://") || streamUrl.startsWith("https://")) {
                    PreviewResult pr = new PreviewResult();
                    if (hdrObj != null) {
                        Iterator<String> keys = hdrObj.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            String v = hdrObj.optString(k, "");
                            if (!v.isEmpty()) pr.headers.put(k, v);
                        }
                    }
                    pr.userAgent = pr.headers.containsKey("User-Agent") ? pr.headers.get("User-Agent") : "";
                    pr.url = LocalStreamProxy.getInstance().registerStream(streamUrl, pr.headers);
                    return pr;
                }
            } catch (Throwable ignored) {}
        }
        throw new IllegalStateException("Gagal mengambil stream pratinjau.");
    }

    public static String fetchPreviewStreamUrl(Context ctx, String mediaUrl, boolean wantVideo, String processId) throws Exception {
        return fetchPreviewResult(ctx, mediaUrl, wantVideo, processId).url;
    }

    public static File resolveTargetDir(Context ctx, MediaItem item, String mode) {
        String platform = (item == null || TextUtils.isEmpty(item.sourceKind))
                ? "Lainnya" : sanitizeFileName(item.sourceKind);
        boolean isVideo = isVideoMode(mode);
        String cKind = item == null ? "Single" : item.containerKind;

        String subPath;
        if ("Playlist".equalsIgnoreCase(cKind)) {
            String plName = sanitizeFileName(TextUtils.isEmpty(item.containerName) ? "Playlist_Umum" : item.containerName);
            subPath = "Downloader/" + platform + "/Playlist/" + plName;
        } else if ("Channel".equalsIgnoreCase(cKind)) {
            String chName = sanitizeFileName(TextUtils.isEmpty(item.containerName)
                    ? (TextUtils.isEmpty(item.uploader) ? "Channel_Umum" : item.uploader)
                    : item.containerName);
            subPath = "Downloader/" + platform + "/Channel/" + chName;
        } else {
            subPath = "Downloader/" + platform + "/" + (isVideo ? "Video" : "Audio");
        }
        return OfficeUtils.resolveBackupSubfolder(ctx, subPath);
    }

    public static boolean isVideoMode(String mode) {
        return MODE_VIDEO_360.equals(mode) || MODE_VIDEO_480.equals(mode)
                || MODE_VIDEO_720.equals(mode) || MODE_VIDEO_1080.equals(mode)
                || MODE_VIDEO_BEST.equals(mode);
    }

    public static File downloadMediaItem(Context ctx, MediaItem item, String mode, int audioBitrateKbps,
                                         String processId, ProgressListener listener) throws Exception {
        if (item == null || TextUtils.isEmpty(item.webpageUrl)) {
            throw new IllegalArgumentException("URL media tidak valid.");
        }
        if (listener != null && listener.isCancelled()) {
            throw new InterruptedException("Dibatalkan oleh pengguna.");
        }
        ensureInitialized(ctx);

        File outDir = resolveTargetDir(ctx, item, mode);
        String safeBase = sanitizeFileName(item.title);
        if (safeBase.length() > 90) safeBase = safeBase.substring(0, 90).trim();
        if (safeBase.isEmpty()) safeBase = "Media_" + System.currentTimeMillis();

        String targetExt = getTargetExtension(mode);
        File finalOut = makeUniqueFile(outDir, safeBase, targetExt);

        File tmpRoot = new File(ctx.getCacheDir(), "ytdl_tmp/" + sanitizeFileName(processId));
        if (!tmpRoot.exists()) tmpRoot.mkdirs();

        try {
            if (!isVideoMode(mode)) {
                return downloadAndConvertAudio(ctx, item, mode, audioBitrateKbps, processId, tmpRoot, finalOut, listener);
            } else {
                return downloadAndMuxVideo(ctx, item, mode, processId, tmpRoot, finalOut, listener);
            }
        } finally {
            OfficeUtils.clearDirectoryContents(tmpRoot);
            try { tmpRoot.delete(); } catch (Throwable ignored) {}
        }
    }

    private static File downloadAndConvertAudio(Context ctx, MediaItem item, String mode, int bitrateKbps,
                                                String processId, File tmpRoot, File finalOut,
                                                ProgressListener listener) throws Exception {
        if (listener != null) listener.onProgress(1, "Menghubungi server...");
        String outTpl = new File(tmpRoot, "raw_audio.%(ext)s").getAbsolutePath();
        int kbps = (bitrateKbps >= 32 && bitrateKbps <= 320) ? bitrateKbps : 128;

        // Pilih format sumber paling hemat & langsung cocok agar tidak berat:
        // Jika target M4A atau OPUS dan bitrate <= 160, yt-dlp mengambil aliran asli hemat (~3-4 MB/5 mnt)
        String fmtExpr;
        if (MODE_AUDIO_OPUS.equals(mode)) {
            fmtExpr = "bestaudio[acodec=opus]/bestaudio/best";
        } else if (kbps <= 128) {
            fmtExpr = "bestaudio[ext=m4a][abr<=140]/bestaudio[ext=m4a]/bestaudio[abr<=160]/bestaudio/best";
        } else {
            fmtExpr = "bestaudio[ext=m4a]/bestaudio/best";
        }

        final boolean directCopyPossible = (MODE_AUDIO_M4A.equals(mode) && kbps >= 128) || MODE_AUDIO_OPUS.equals(mode);
        ensureInitialized(ctx);
        final int[] lastPct = {0};
        executeNativeFast(item.webpageUrl, fmtExpr, outTpl, processId, (progress, eta, line) -> {
            if (listener != null && !listener.isCancelled()) {
                int p = parseProgressPercent(progress, line, lastPct[0]);
                lastPct[0] = p;
                int scaled = directCopyPossible ? p : Math.min(78, Math.round(p * 0.78f));
                String etaTxt = (eta != null && eta > 0) ? (" • sisa " + eta + "d") : "";
                listener.onProgress(Math.max(1, scaled), "Mengunduh audio " + p + "%" + etaTxt);
            }
            return Unit.INSTANCE;
        });

        if (listener != null && listener.isCancelled()) throw new InterruptedException("Dibatalkan.");
        File downloaded = findFirstDownloadedFile(tmpRoot, "raw_audio");
        if (downloaded == null || !downloaded.exists() || downloaded.length() == 0) {
            throw new IllegalStateException("Berkas audio hasil unduhan kosong.");
        }

        String rawExt = getExtLower(downloaded.getName());
        // Direct copy tanpa FFmpeg jika format sudah cocok & hemat (super cepat 0 detik!)
        if ((MODE_AUDIO_M4A.equals(mode) && "m4a".equals(rawExt) && kbps >= 128)
                || (MODE_AUDIO_OPUS.equals(mode) && ("opus".equals(rawExt) || "webm".equals(rawExt)) && kbps >= 96)) {
            if ("webm".equals(rawExt) && MODE_AUDIO_OPUS.equals(mode)) {
                // Ekstrak opus murni dari container webm dengan -c:a copy (hitungan 0.2 detik!)
                File tmpOpus = new File(tmpRoot, "converted.opus");
                runFfmpegChecked("-y -threads 0 -i " + quoteArg(downloaded.getAbsolutePath())
                        + " -vn -c:a copy " + quoteArg(tmpOpus.getAbsolutePath()), listener);
                moveOrCopyFile(tmpOpus, finalOut);
            } else {
                moveOrCopyFile(downloaded, finalOut);
            }
            scanMediaFile(ctx, finalOut);
            if (listener != null) listener.onProgress(100, "Selesai");
            return finalOut;
        }

        if (listener != null) {
            listener.onProgress(82, "Mengonversi " + getTargetExtension(mode).toUpperCase(Locale.ROOT) + " " + kbps + "kbps...");
        }
        File tmpConverted = new File(tmpRoot, "converted." + getTargetExtension(mode));
        String cmd;
        if (MODE_AUDIO_MP3.equals(mode)) {
            cmd = "-y -threads 0 -i " + quoteArg(downloaded.getAbsolutePath())
                    + " -vn -codec:a libmp3lame -b:a " + kbps + "k " + quoteArg(tmpConverted.getAbsolutePath());
        } else if (MODE_AUDIO_WAV.equals(mode)) {
            cmd = "-y -threads 0 -i " + quoteArg(downloaded.getAbsolutePath())
                    + " -vn -codec:a pcm_s16le -ar 44100 " + quoteArg(tmpConverted.getAbsolutePath());
        } else if (MODE_AUDIO_OPUS.equals(mode)) {
            cmd = "-y -threads 0 -i " + quoteArg(downloaded.getAbsolutePath())
                    + " -vn -codec:a libopus -b:a " + kbps + "k " + quoteArg(tmpConverted.getAbsolutePath());
        } else {
            cmd = "-y -threads 0 -i " + quoteArg(downloaded.getAbsolutePath())
                    + " -vn -codec:a aac -b:a " + kbps + "k -movflags +faststart " + quoteArg(tmpConverted.getAbsolutePath());
        }

        runFfmpegChecked(cmd, listener);
        if (listener != null && listener.isCancelled()) throw new InterruptedException("Dibatalkan.");

        moveOrCopyFile(tmpConverted, finalOut);
        scanMediaFile(ctx, finalOut);
        if (listener != null) listener.onProgress(100, "Selesai");
        return finalOut;
    }

    private static File downloadAndMuxVideo(Context ctx, MediaItem item, String mode, String processId,
                                            File tmpRoot, File finalOut, ProgressListener listener) throws Exception {
        int maxH = 720;
        if (MODE_VIDEO_360.equals(mode)) maxH = 360;
        else if (MODE_VIDEO_480.equals(mode)) maxH = 480;
        else if (MODE_VIDEO_1080.equals(mode)) maxH = 1080;
        else if (MODE_VIDEO_BEST.equals(mode)) maxH = 2160;

        final int[] lastPct = {0};
        ensureInitialized(ctx);
        if (maxH <= 720) {
            try {
                if (listener != null) listener.onProgress(1, "Mengunduh video (" + maxH + "p)...");
                String singleTpl = new File(tmpRoot, "single_vid.%(ext)s").getAbsolutePath();
                String fmt = "best[height<=" + maxH + "][ext=mp4]/best[height<=" + maxH + "]";
                executeNativeFast(item.webpageUrl, fmt, singleTpl, processId, (progress, eta, line) -> {
                    if (listener != null && !listener.isCancelled()) {
                        int p = parseProgressPercent(progress, line, lastPct[0]);
                        lastPct[0] = p;
                        String etaTxt = (eta != null && eta > 0) ? (" • sisa " + eta + "d") : "";
                        listener.onProgress(Math.max(1, Math.min(95, p)), "Mengunduh video " + p + "%" + etaTxt);
                    }
                    return Unit.INSTANCE;
                });
                if (listener != null && listener.isCancelled()) throw new InterruptedException("Dibatalkan.");
                File rawSingle = findFirstDownloadedFile(tmpRoot, "single_vid");
                if (rawSingle != null && rawSingle.exists() && rawSingle.length() > 0) {
                    if ("mp4".equals(getExtLower(rawSingle.getName()))) {
                        moveOrCopyFile(rawSingle, finalOut);
                    } else {
                        File tmpMp4 = new File(tmpRoot, "remux.mp4");
                        runFfmpegChecked("-y -threads 0 -i " + quoteArg(rawSingle.getAbsolutePath())
                                + " -c copy -movflags +faststart " + quoteArg(tmpMp4.getAbsolutePath()), listener);
                        moveOrCopyFile(tmpMp4, finalOut);
                    }
                    scanMediaFile(ctx, finalOut);
                    if (listener != null) listener.onProgress(100, "Selesai");
                    return finalOut;
                }
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Throwable ignored) {
                OfficeUtils.clearDirectoryContents(tmpRoot);
            }
        }

        if (listener != null) listener.onProgress(1, "Mengunduh trek video HD...");
        lastPct[0] = 0;
        String vTpl = new File(tmpRoot, "v_stream.%(ext)s").getAbsolutePath();
        String vFmt = "bestvideo[height<=" + maxH + "][ext=mp4]/bestvideo[height<=" + maxH + "]/best[height<=" + maxH + "]";
        executeNativeFast(item.webpageUrl, vFmt, vTpl, processId, (progress, eta, line) -> {
            if (listener != null && !listener.isCancelled()) {
                int p = parseProgressPercent(progress, line, lastPct[0]);
                lastPct[0] = p;
                listener.onProgress(Math.max(1, Math.round(p * 0.55f)), "Unduh video " + p + "%");
            }
            return Unit.INSTANCE;
        });
        if (listener != null && listener.isCancelled()) throw new InterruptedException("Dibatalkan.");

        if (listener != null) listener.onProgress(58, "Mengunduh trek audio...");
        lastPct[0] = 0;
        String aTpl = new File(tmpRoot, "a_stream.%(ext)s").getAbsolutePath();
        executeNativeFast(item.webpageUrl, "bestaudio[ext=m4a]/bestaudio/best", aTpl, processId + "_a", (progress, eta, line) -> {
            if (listener != null && !listener.isCancelled()) {
                int p = parseProgressPercent(progress, line, lastPct[0]);
                lastPct[0] = p;
                listener.onProgress(58 + Math.round(p * 0.27f), "Unduh audio " + p + "%");
            }
            return Unit.INSTANCE;
        });
        if (listener != null && listener.isCancelled()) throw new InterruptedException("Dibatalkan.");

        File vFile = findFirstDownloadedFile(tmpRoot, "v_stream");
        File aFile = findFirstDownloadedFile(tmpRoot, "a_stream");
        if (vFile == null || !vFile.exists()) throw new IllegalStateException("Trek video tidak ditemukan.");

        if (listener != null) listener.onProgress(88, "Menggabungkan video & audio (FFmpeg)...");
        File tmpMux = new File(tmpRoot, "final_mux.mp4");
        String cmd = (aFile != null && aFile.exists())
                ? ("-y -threads 0 -i " + quoteArg(vFile.getAbsolutePath()) + " -i " + quoteArg(aFile.getAbsolutePath())
                + " -c:v copy -c:a aac -b:a 128k -shortest -movflags +faststart " + quoteArg(tmpMux.getAbsolutePath()))
                : ("-y -threads 0 -i " + quoteArg(vFile.getAbsolutePath())
                + " -c copy -movflags +faststart " + quoteArg(tmpMux.getAbsolutePath()));
        try {
            runFfmpegChecked(cmd, listener);
        } catch (Exception copyFail) {
            if (listener != null && listener.isCancelled()) throw new InterruptedException("Dibatalkan.");
            String fallbackCmd = (aFile != null && aFile.exists())
                    ? ("-y -threads 0 -i " + quoteArg(vFile.getAbsolutePath()) + " -i " + quoteArg(aFile.getAbsolutePath())
                    + " -c:v libx264 -preset veryfast -crf 23 -c:a aac -b:a 128k -shortest -movflags +faststart " + quoteArg(tmpMux.getAbsolutePath()))
                    : ("-y -threads 0 -i " + quoteArg(vFile.getAbsolutePath())
                    + " -c:v libx264 -preset veryfast -crf 23 -c:a aac -movflags +faststart " + quoteArg(tmpMux.getAbsolutePath()));
            runFfmpegChecked(fallbackCmd, listener);
        }

        if (listener != null && listener.isCancelled()) throw new InterruptedException("Dibatalkan.");
        moveOrCopyFile(tmpMux, finalOut);
        scanMediaFile(ctx, finalOut);
        if (listener != null) listener.onProgress(100, "Selesai");
        return finalOut;
    }

    private static void executeNativeFast(String url, String formatExpr, String outTemplate,
                                          String processId,
                                          kotlin.jvm.functions.Function3<Float, Long, String, Unit> cb) throws Exception {
        YoutubeDLRequest req = new YoutubeDLRequest(url);
        req.addOption("--no-playlist");
        req.addOption("--no-warnings");
        req.addOption("--newline");
        req.addOption("--progress");
        req.addOption("--socket-timeout", 15);
        req.addOption("--retries", 3);
        req.addOption("--fragment-retries", 3);
        req.addOption("--concurrent-fragments", 4);
        req.addOption("--http-chunk-size", "10M");
        req.addOption("-f", formatExpr);
        req.addOption("-o", outTemplate);
        YoutubeDL.getInstance().execute(req, processId, cb);
    }

    private static int parseProgressPercent(Float progress, String line, int fallbackPrev) {
        if (progress != null && progress > 0f) {
            return Math.min(100, Math.round(progress));
        }
        if (line != null && line.contains("%")) {
            try {
                int pctIdx = line.indexOf('%');
                int start = pctIdx - 1;
                while (start >= 0 && (Character.isDigit(line.charAt(start)) || line.charAt(start) == '.')) {
                    start--;
                }
                String num = line.substring(start + 1, pctIdx).trim();
                if (!num.isEmpty()) {
                    return Math.min(100, Math.round(Float.parseFloat(num)));
                }
            } catch (Throwable ignored) {}
        }
        return fallbackPrev;
    }

    private static void runFfmpegChecked(String cmd, ProgressListener listener) throws Exception {
        FFmpegSession session = FFmpegKit.executeAsync(cmd, s -> {}, log -> {}, stats -> {});
        if (listener != null) listener.onFfmpegSessionStarted(session.getSessionId());
        while (session.getState() != com.arthenica.ffmpegkit.SessionState.COMPLETED
                && session.getState() != com.arthenica.ffmpegkit.SessionState.FAILED) {
            if (listener != null && listener.isCancelled()) {
                try { FFmpegKit.cancel(session.getSessionId()); } catch (Throwable ignored) {}
                throw new InterruptedException("Konversi dibatalkan.");
            }
            try { Thread.sleep(120); } catch (InterruptedException ie) {
                try { FFmpegKit.cancel(session.getSessionId()); } catch (Throwable ignored) {}
                throw ie;
            }
        }
        if (!ReturnCode.isSuccess(session.getReturnCode())) {
            throw new IllegalStateException("FFmpeg gagal memproses media.");
        }
    }

    public static void destroyProcessSafe(String processId) {
        if (TextUtils.isEmpty(processId)) return;
        try {
            YoutubeDL.getInstance().destroyProcessById(processId);
        } catch (Throwable ignored) {}
    }

    private static File findFirstDownloadedFile(File dir, String prefix) {
        File[] list = dir.listFiles();
        if (list == null) return null;
        File best = null;
        for (File f : list) {
            if (f.isFile() && f.getName().startsWith(prefix) && !f.getName().endsWith(".part")) {
                if (best == null || f.length() > best.length()) best = f;
            }
        }
        return best;
    }

    private static void moveOrCopyFile(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (src.renameTo(dst)) return;
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst, false)) {
            byte[] buf = new byte[32768];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
        try { src.delete(); } catch (Throwable ignored) {}
    }

    private static File makeUniqueFile(File dir, String baseName, String ext) {
        File f = new File(dir, baseName + "." + ext);
        int idx = 1;
        while (f.exists()) {
            f = new File(dir, baseName + " (" + idx + ")." + ext);
            idx++;
        }
        return f;
    }

    private static void scanMediaFile(Context ctx, File f) {
        try {
            MediaScannerConnection.scanFile(ctx.getApplicationContext(), new String[]{f.getAbsolutePath()}, null, null);
        } catch (Throwable ignored) {}
    }

    public static String getTargetExtension(String mode) {
        if (MODE_AUDIO_M4A.equals(mode)) return "m4a";
        if (MODE_AUDIO_OPUS.equals(mode)) return "opus";
        if (MODE_AUDIO_WAV.equals(mode)) return "wav";
        if (isVideoMode(mode)) return "mp4";
        return "mp3";
    }

    public static String getModeDisplayLabel(String mode) {
        if (MODE_AUDIO_MP3.equals(mode)) return "Audio MP3";
        if (MODE_AUDIO_M4A.equals(mode)) return "Audio M4A";
        if (MODE_AUDIO_OPUS.equals(mode)) return "Audio OPUS";
        if (MODE_AUDIO_WAV.equals(mode)) return "Audio WAV";
        if (MODE_VIDEO_360.equals(mode)) return "Video 360p";
        if (MODE_VIDEO_480.equals(mode)) return "Video 480p";
        if (MODE_VIDEO_720.equals(mode)) return "Video 720p HD";
        if (MODE_VIDEO_1080.equals(mode)) return "Video 1080p";
        if (MODE_VIDEO_BEST.equals(mode)) return "Video Terbaik";
        return "Audio MP3";
    }

    public static String sanitizeFileName(String name) {
        if (name == null) return "Media";
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? "Media" : cleaned;
    }

    private static String extractLastJsonObject(String out) {
        String trimmed = out.trim();
        int idx = trimmed.lastIndexOf("\n{");
        if (idx >= 0) return trimmed.substring(idx + 1).trim();
        int first = trimmed.indexOf('{');
        return first >= 0 ? trimmed.substring(first) : trimmed;
    }

    private static String cleanText(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String getExtLower(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static String formatDuration(long sec) {
        if (sec <= 0) return "";
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    private static String quoteArg(String path) {
        if (path == null) return "\"\"";
        return "\"" + path.replace("\"", "\\\"") + "\"";
    }
}
