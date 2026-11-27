package com.annas;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mesin Editor Video Berbasis FFmpeg 8.1 Full GPL Native C/NEON.
 * - Remux instan (-c copy): Trim, Cut tengah, Belah 2, Pemotong Status WA 5 menit -> Part.
 * - Gabung banyak video beda resolusi/orientasi tanpa gagal.
 * - Multi-Audio Mixer pada video (Audio 1, Audio 2, Audio 3 + Auto-Ducking).
 * - Visual CapCut-Style: Rasio 9:16 Blur Background, Crop, Putar, Flip, Filter Warna,
 *   Burn-in Teks & Subtitle (.SRT / Auto-Subtitle AI) via libass, Watermark PNG, Speed/Reverse, Kompres MP4.
 */
public final class VideoEditEngine {

    public interface ProgressCallback {
        void onProgress(int percent, String status);
    }

    public static final class VideoMeta {
        public String displayName = "video.mp4";
        public long durationMs = 0L;
        public int width = 1280;
        public int height = 720;
        public int rotation = 0;
        public int fps = 30;
        public long bitrate = 2500000L;
        public long sizeBytes = 0L;
        public boolean hasVideo = false;
        public boolean hasAudio = false;
    }

    public static final class TextOverlayItem {
        public String text = "";
        public long startMs = 0L;
        public long endMs = 60000L;
        public int position = 2; // 0=Atas, 1=Tengah, 2=Bawah
    }

    public static final class WatermarkConfig {
        public Uri imageUri;
        public int position = 1; // 0=Kiri Atas, 1=Kanan Atas, 2=Kiri Bawah, 3=Kanan Bawah
        public int widthPx = 160;
    }

    public static final class RenderOptions {
        public String aspectRatio = "ASLI"; // ASLI | 9:16 | 16:9 | 1:1 | 4:5 | 21:9 | 4:3
        public String bgMode = "BLUR";      // BLUR | BLUR_STRONG | BLACK | WHITE | DARK_GRAY | NAVY
        public int targetLongSide = 0;      // 0=auto/asli, 640(360p), 854(480p), 1280(720p), 1920(1080p)
        public int fps = 30;
        public int crf = 26;                // 18 (tinggi), 23 (tajam), 26 (seimbang hemat), 28 (kompres hemat)
        public int audioBitrateKbps = 128;  // 96, 128, 160, 192, 256, 320 kbps
        public int cropLeftPct = 0, cropRightPct = 0, cropTopPct = 0, cropBottomPct = 0;
        public int extraRotateDeg = 0;      // 0, 90, 180, 270
        public boolean flipH = false;
        public boolean flipV = false;
        public String colorPreset = "normal";
        public float brightness = 0f;       // -0.5 .. +0.5
        public float contrast = 1.0f;       // 0.5 .. 2.0
        public float saturation = 1.0f;     // 0.0 .. 2.5
        public float speedFactor = 1.0f;    // 0.25 .. 4.0
        public boolean reverse = false;
        public int fadeInMs = 0;
        public int fadeOutMs = 0;
        public boolean muteAudio = false;
        public final List<TextOverlayItem> overlays = new ArrayList<>();
        public WatermarkConfig watermark = null;
    }

    private VideoEditEngine() {}

    public static VideoMeta inspectVideo(Context ctx, Uri uri) {
        VideoMeta m = new VideoMeta();
        m.displayName = AudioDspEngine.resolveDisplayName(ctx, uri);
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                File f = new File(uri.getPath());
                m.sizeBytes = f.length();
                mmr.setDataSource(f.getAbsolutePath());
            } else {
                try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
                    if (pfd != null) {
                        m.sizeBytes = pfd.getStatSize();
                        mmr.setDataSource(pfd.getFileDescriptor());
                    }
                }
            }
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            String br = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            String hv = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            String ha = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            if (dur != null) m.durationMs = Long.parseLong(dur);
            if (w != null) m.width = Integer.parseInt(w);
            if (h != null) m.height = Integer.parseInt(h);
            if (rot != null) m.rotation = Integer.parseInt(rot);
            if (br != null) m.bitrate = Long.parseLong(br);
            m.hasVideo = "yes".equalsIgnoreCase(hv) || m.width > 0;
            m.hasAudio = "yes".equalsIgnoreCase(ha);
        } catch (Throwable ignored) {
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
        if (m.durationMs <= 0L && "file".equals(uri.getScheme()) && uri.getPath() != null) {
            try {
                MediaInformationSession mis = FFprobeKit.getMediaInformation(uri.getPath());
                MediaInformation mi = mis != null ? mis.getMediaInformation() : null;
                if (mi != null && mi.getDuration() != null) {
                    m.durationMs = Math.round(Double.parseDouble(mi.getDuration()) * 1000.0);
                    m.hasVideo = true;
                }
            } catch (Throwable ignored) {}
        }
        return m;
    }

    // ==================== JALUR CEPAT (-c copy) ====================

    public static void fastTrimVideo(
            Context ctx, Uri srcUri, File workDir, File outMp4,
            long startMs, long endMs, boolean includeAudio
    ) throws Exception {
        File inFile = AudioDspEngine.materializeRawCopyIfContent(ctx, srcUri, workDir, "vtrim");
        boolean cleanTmp = !"file".equals(srcUri.getScheme());
        try {
            StringBuilder cmd = new StringBuilder("-y ");
            if (startMs > 0) cmd.append("-ss ").append(AudioDspEngine.formatSecForFfmpeg(startMs)).append(" ");
            if (endMs > startMs) cmd.append("-to ").append(AudioDspEngine.formatSecForFfmpeg(endMs)).append(" ");
            cmd.append("-i ").append(AudioDspEngine.q(inFile)).append(" ");
            cmd.append(includeAudio ? "-c copy " : "-c:v copy -an ");
            cmd.append("-avoid_negative_ts make_zero -movflags +faststart ").append(AudioDspEngine.q(outMp4));
            AudioDspEngine.runFfmpegOrThrow(cmd.toString());
        } finally {
            if (cleanTmp) { try { inFile.delete(); } catch (Throwable ignored) {} }
        }
    }

    public static void fastCutRangeVideo(
            Context ctx, Uri srcUri, File workDir, File outMp4,
            long cutStartMs, long cutEndMs, long totalDurMs, boolean hasAudio
    ) throws Exception {
        File inFile = AudioDspEngine.materializeRawCopyIfContent(ctx, srcUri, workDir, "vcut");
        boolean cleanTmp = !"file".equals(srcUri.getScheme());
        try {
            boolean hasBefore = cutStartMs > 200L;
            boolean hasAfter = cutEndMs + 200L < totalDurMs;
            if (!hasBefore && !hasAfter) throw new IllegalStateException("Rentang buang mencakup seluruh video.");
            if (!hasBefore) {
                fastTrimVideo(ctx, Uri.fromFile(inFile), workDir, outMp4, cutEndMs, totalDurMs, hasAudio);
                return;
            }
            if (!hasAfter) {
                fastTrimVideo(ctx, Uri.fromFile(inFile), workDir, outMp4, 0L, cutStartMs, hasAudio);
                return;
            }
            String fc;
            String mapArgs;
            if (hasAudio) {
                fc = String.format(Locale.US,
                        "[0:v]trim=0:%s,setpts=PTS-STARTPTS[v0];[0:a]atrim=0:%s,asetpts=PTS-STARTPTS[a0];"
                                + "[0:v]trim=%s,setpts=PTS-STARTPTS[v1];[0:a]atrim=%s,asetpts=PTS-STARTPTS[a1];"
                                + "[v0][a0][v1][a1]concat=n=2:v=1:a=1[outv][outa]",
                        AudioDspEngine.formatSecForFfmpeg(cutStartMs), AudioDspEngine.formatSecForFfmpeg(cutStartMs),
                        AudioDspEngine.formatSecForFfmpeg(cutEndMs), AudioDspEngine.formatSecForFfmpeg(cutEndMs));
                mapArgs = "-map \"[outv]\" -map \"[outa]\" -c:a aac -profile:a aac_low -b:a 112k ";
            } else {
                fc = String.format(Locale.US,
                        "[0:v]trim=0:%s,setpts=PTS-STARTPTS[v0];[0:v]trim=%s,setpts=PTS-STARTPTS[v1];[v0][v1]concat=n=2:v=1:a=0[outv]",
                        AudioDspEngine.formatSecForFfmpeg(cutStartMs), AudioDspEngine.formatSecForFfmpeg(cutEndMs));
                mapArgs = "-map \"[outv]\" -an ";
            }
            String cmd = "-y -threads 0 -i " + AudioDspEngine.q(inFile) + " -filter_complex \"" + fc + "\" "
                    + mapArgs + "-c:v libx264 -preset superfast -crf 25 -pix_fmt yuv420p -movflags +faststart "
                    + AudioDspEngine.q(outMp4);
            AudioDspEngine.runFfmpegOrThrow(cmd);
        } finally {
            if (cleanTmp) { try { inFile.delete(); } catch (Throwable ignored) {} }
        }
    }

    /** Memecah video panjang (misal 5 menit) menjadi Part 1..N sesuai detik Status WA secara kilat! */
    public static List<File> fastSplitStatusParts(
            Context ctx, Uri srcUri, File workDir, File outDir, String baseName, int partSeconds
    ) throws Exception {
        if (!outDir.exists()) outDir.mkdirs();
        VideoMeta meta = inspectVideo(ctx, srcUri);
        long totalMs = Math.max(1000L, meta.durationMs);
        long partMs = Math.max(3000L, partSeconds * 1000L);
        int totalParts = (int) Math.max(1L, (totalMs + partMs - 1L) / partMs);
        String cleanBase = baseName.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        List<File> outFiles = new ArrayList<>();

        File inFile = AudioDspEngine.materializeRawCopyIfContent(ctx, srcUri, workDir, "vst");
        boolean cleanTmp = !"file".equals(srcUri.getScheme());
        try {
            for (int i = 0; i < totalParts; i++) {
                long s = i * partMs;
                long e = Math.min(totalMs, (i + 1) * partMs);
                if (e - s < 500L && i > 0) break;
                File part = new File(outDir, cleanBase + "_Status_Part" + (i + 1) + ".mp4");
                String cmd = "-y -ss " + AudioDspEngine.formatSecForFfmpeg(s)
                        + " -to " + AudioDspEngine.formatSecForFfmpeg(e)
                        + " -i " + AudioDspEngine.q(inFile)
                        + " -c copy -avoid_negative_ts make_zero -movflags +faststart "
                        + AudioDspEngine.q(part);
                AudioDspEngine.runFfmpegOrThrow(cmd);
                outFiles.add(part);
            }
        } finally {
            if (cleanTmp) { try { inFile.delete(); } catch (Throwable ignored) {} }
        }
        return outFiles;
    }

    /** Gabung Video 1 + Video 2 + Video 3... (otomatis menyamakan skala resolusi & audio agar bebas gagal). */
    public static void mergeVideos(Context ctx, List<Uri> uris, File workDir, File outMp4) throws Exception {
        if (uris == null || uris.isEmpty()) throw new IllegalArgumentException("Pilih minimal 1 video.");
        List<File> tmpTemps = new ArrayList<>();
        List<File> files = new ArrayList<>();
        try {
            for (int i = 0; i < uris.size(); i++) {
                Uri u = uris.get(i);
                File f = AudioDspEngine.materializeRawCopyIfContent(ctx, u, workDir, "vm_" + i);
                if (!"file".equals(u.getScheme())) tmpTemps.add(f);
                files.add(f);
            }
            if (files.size() == 1) {
                AudioDspEngine.copyFile(files.get(0), outMp4);
                return;
            }
            VideoMeta m0 = inspectVideo(ctx, Uri.fromFile(files.get(0)));
            int w = m0.width > 0 ? ((m0.width / 2) * 2) : 1280;
            int h = m0.height > 0 ? ((m0.height / 2) * 2) : 720;
            if (m0.rotation == 90 || m0.rotation == 270) { int t = w; w = h; h = t; }
            if (Math.max(w, h) > 1280) {
                double sc = 1280.0 / Math.max(w, h);
                w = ((int) Math.round(w * sc) / 2) * 2;
                h = ((int) Math.round(h * sc) / 2) * 2;
            }

            StringBuilder inputs = new StringBuilder();
            StringBuilder fc = new StringBuilder();
            StringBuilder tags = new StringBuilder();
            for (int i = 0; i < files.size(); i++) {
                VideoMeta mi = inspectVideo(ctx, Uri.fromFile(files.get(i)));
                inputs.append("-i ").append(AudioDspEngine.q(files.get(i))).append(" ");
                fc.append(String.format(Locale.US,
                        "[%d:v]scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=30,format=yuv420p[v%d];",
                        i, w, h, w, h, i));
                if (mi.hasAudio) {
                    fc.append(String.format(Locale.US,
                            "[%d:a]aresample=44100:async=1,aformat=sample_fmts=fltp:channel_layouts=stereo[a%d];", i, i));
                } else {
                    double durS = Math.max(0.5, mi.durationMs / 1000.0);
                    fc.append(String.format(Locale.US,
                            "aevalsrc=0:channel_layout=stereo:sample_rate=44100:d=%.2f[a%d];", durS, i));
                }
                tags.append("[v").append(i).append("][a").append(i).append("]");
            }
            fc.append(tags).append("concat=n=").append(files.size()).append(":v=1:a=1[outv][outa]");
            String cmd = "-y -threads 0 " + inputs + "-filter_complex \"" + fc + "\" -map \"[outv]\" -map \"[outa]\" "
                    + "-c:v libx264 -preset superfast -crf 25 -c:a aac -profile:a aac_low -b:a 112k -movflags +faststart "
                    + AudioDspEngine.q(outMp4);
            AudioDspEngine.runFfmpegOrThrow(cmd);
        } finally {
            for (File t : tmpTemps) { try { t.delete(); } catch (Throwable ignored) {} }
        }
    }

    /** Mengganti atau mencampur trek Audio 1 + Audio 2 + ... (bisa tambah/kurang) ke video dengan Auto-Ducking! */
    public static void mixMultiAudioOntoVideo(
            Context ctx, Uri videoUri, boolean keepOriginalAudio, float origVol,
            List<AudioDspEngine.MixTrackSpec> extraTracks, boolean autoDucking,
            File workDir, File outMp4
    ) throws Exception {
        File inVideo = AudioDspEngine.materializeRawCopyIfContent(ctx, videoUri, workDir, "va_vid");
        boolean cleanVid = !"file".equals(videoUri.getScheme());
        List<File> tmpAudios = new ArrayList<>();
        try {
            VideoMeta vm = inspectVideo(ctx, Uri.fromFile(inVideo));
            boolean hasOrig = keepOriginalAudio && vm.hasAudio;
            if (!hasOrig && (extraTracks == null || extraTracks.isEmpty())) {
                fastTrimVideo(ctx, Uri.fromFile(inVideo), workDir, outMp4, 0L, 0L, false);
                return;
            }
            StringBuilder inputs = new StringBuilder("-i " + AudioDspEngine.q(inVideo) + " ");
            StringBuilder fc = new StringBuilder();
            int streamCount = 0;

            if (hasOrig) {
                fc.append(String.format(Locale.US,
                        "[0:a]aresample=44100:async=1,aformat=sample_fmts=fltp:channel_layouts=stereo,volume=%.2f[t0];", origVol));
                streamCount++;
            }
            int extraStartIdx = 1;
            if (extraTracks != null) {
                for (int i = 0; i < extraTracks.size(); i++) {
                    AudioDspEngine.MixTrackSpec tr = extraTracks.get(i);
                    File af = AudioDspEngine.materializeRawCopyIfContent(ctx, tr.uri, workDir, "va_ext_" + i);
                    if (!"file".equals(tr.uri.getScheme())) tmpAudios.add(af);
                    if (tr.loop) inputs.append("-stream_loop -1 ");
                    inputs.append("-i ").append(AudioDspEngine.q(af)).append(" ");
                    int inputIdx = extraStartIdx + i;
                    String trimStr = "";
                    if (tr.endMs > tr.delayMs) {
                        long durLimit = tr.endMs - tr.delayMs;
                        if (vm.durationMs > 0) durLimit = Math.min(durLimit, Math.max(200L, vm.durationMs - tr.delayMs));
                        trimStr = ",atrim=0:" + AudioDspEngine.formatSecForFfmpeg(durLimit) + ",asetpts=PTS-STARTPTS";
                    } else if (tr.loop && vm.durationMs > 0) {
                        long durLimit = Math.max(200L, vm.durationMs - tr.delayMs);
                        trimStr = ",atrim=0:" + AudioDspEngine.formatSecForFfmpeg(durLimit) + ",asetpts=PTS-STARTPTS";
                    }
                    String delay = tr.delayMs > 0 ? (",adelay=" + tr.delayMs + "|" + tr.delayMs) : "";
                    fc.append(String.format(Locale.US,
                            "[%d:a]aresample=44100:async=1,aformat=sample_fmts=fltp:channel_layouts=stereo%s,volume=%.2f%s[t%d];",
                            inputIdx, trimStr, tr.volume, delay, streamCount));
                    streamCount++;
                }
            }

            if (streamCount == 1) {
                fc.append("[t0]alimiter=limit=0.95[outa]");
            } else if (autoDucking && hasOrig) {
                StringBuilder bgTags = new StringBuilder();
                for (int k = 1; k < streamCount; k++) bgTags.append("[t").append(k).append("]");
                if (streamCount == 2) {
                    fc.append("[t0]asplit=2[vmain][vsc];[t1][vsc]sidechaincompress=threshold=0.03:ratio=5:attack=40:release=350[bgd];[vmain][bgd]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.95[outa]");
                } else {
                    fc.append(bgTags).append("amix=inputs=").append(streamCount - 1).append(":duration=first:normalize=0[bgall];");
                    fc.append("[t0]asplit=2[vmain][vsc];[bgall][vsc]sidechaincompress=threshold=0.03:ratio=5:attack=40:release=350[bgd];[vmain][bgd]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.95[outa]");
                }
            } else {
                StringBuilder allTags = new StringBuilder();
                for (int k = 0; k < streamCount; k++) allTags.append("[t").append(k).append("]");
                fc.append(allTags).append("amix=inputs=").append(streamCount).append(":duration=first:normalize=0,alimiter=limit=0.95[outa]");
            }

            String limitArg = vm.durationMs > 0 ? ("-t " + AudioDspEngine.formatSecForFfmpeg(vm.durationMs) + " ") : "";
            String cmd = "-y " + inputs + "-filter_complex \"" + fc + "\" "
                    + "-map 0:v -map \"[outa]\" -c:v copy -c:a aac -profile:a aac_low -b:a 112k " + limitArg + "-shortest -movflags +faststart "
                    + AudioDspEngine.q(outMp4);
            AudioDspEngine.runFfmpegOrThrow(cmd);
        } finally {
            if (cleanVid) { try { inVideo.delete(); } catch (Throwable ignored) {} }
            for (File f : tmpAudios) { try { f.delete(); } catch (Throwable ignored) {} }
        }
    }

    // ==================== VISUAL RENDER PIPELINE (FFMPEG LIBX264 + LIBASS SUBTITLES) ====================

    public static void renderVisualVideo(
            Context ctx, Uri srcUri, File workDir, File outMp4,
            long startMs, long endMs, RenderOptions opts
    ) throws Exception {
        File inFile = AudioDspEngine.materializeRawCopyIfContent(ctx, srcUri, workDir, "vren_in");
        boolean cleanVid = !"file".equals(srcUri.getScheme());
        File srtFile = null;
        File wmFile = null;
        boolean cleanWm = false;
        try {
            VideoMeta meta = inspectVideo(ctx, Uri.fromFile(inFile));
            long dur = Math.max(1000L, meta.durationMs);
            long sMs = Math.max(0L, startMs);
            long eMs = (endMs <= sMs || endMs > dur) ? dur : endMs;
            double clipSec = Math.max(0.5, (eMs - sMs) / 1000.0);

            int srcW = (meta.rotation == 90 || meta.rotation == 270) ? meta.height : meta.width;
            int srcH = (meta.rotation == 90 || meta.rotation == 270) ? meta.width : meta.height;
            int[] targetDim = resolveCanvasDimensions(srcW, srcH, opts.aspectRatio, opts.targetLongSide, opts.extraRotateDeg);
            int outW = targetDim[0], outH = targetDim[1];

            StringBuilder inputs = new StringBuilder();
            if (sMs > 0) inputs.append("-ss ").append(AudioDspEngine.formatSecForFfmpeg(sMs)).append(" ");
            if (eMs < dur) inputs.append("-to ").append(AudioDspEngine.formatSecForFfmpeg(eMs)).append(" ");
            inputs.append("-i ").append(AudioDspEngine.q(inFile)).append(" ");

            if (opts.watermark != null && opts.watermark.imageUri != null) {
                wmFile = AudioDspEngine.materializeRawCopyIfContent(ctx, opts.watermark.imageUri, workDir, "wm");
                cleanWm = !"file".equals(opts.watermark.imageUri.getScheme());
                inputs.append("-i ").append(AudioDspEngine.q(wmFile)).append(" ");
            }

            List<String> preFilters = new ArrayList<>();
            // Crop (%)
            if (opts.cropLeftPct > 0 || opts.cropRightPct > 0 || opts.cropTopPct > 0 || opts.cropBottomPct > 0) {
                double wKeep = Math.max(0.2, 1.0 - (opts.cropLeftPct + opts.cropRightPct) / 100.0);
                double hKeep = Math.max(0.2, 1.0 - (opts.cropTopPct + opts.cropBottomPct) / 100.0);
                double xOff = Math.max(0.0, opts.cropLeftPct / 100.0);
                double yOff = Math.max(0.0, opts.cropTopPct / 100.0);
                preFilters.add(String.format(Locale.US, "crop=iw*%.2f:ih*%.2f:iw*%.2f:ih*%.2f", wKeep, hKeep, xOff, yOff));
            }
            if (opts.extraRotateDeg == 90) preFilters.add("transpose=1");
            else if (opts.extraRotateDeg == 180) preFilters.add("hflip,vflip");
            else if (opts.extraRotateDeg == 270) preFilters.add("transpose=2");
            if (opts.flipH) preFilters.add("hflip");
            if (opts.flipV) preFilters.add("vflip");

            // Filter warna & penyesuaian
            String colorVf = buildColorFilter(opts.colorPreset, opts.brightness, opts.contrast, opts.saturation);
            if (!colorVf.isEmpty()) preFilters.add(colorVf);

            // Speed & Reverse video
            if (opts.reverse) preFilters.add("reverse");
            float spd = Math.max(0.25f, Math.min(4.0f, opts.speedFactor));
            if (Math.abs(spd - 1.0f) >= 0.03f) {
                preFilters.add(String.format(Locale.US, "setpts=PTS/%.3f", spd));
            }

            String preChain = preFilters.isEmpty() ? "null" : String.join(",", preFilters);
            StringBuilder fc = new StringBuilder();
            fc.append("[0:v]").append(preChain).append("[vprep];");

            // Kanvas Rasio + Latar Blur / Hitam / Putih / Abu-Abu / Navy
            if ("ASLI".equals(opts.aspectRatio) && opts.targetLongSide <= 0) {
                fc.append(String.format(Locale.US, "[vprep]scale=trunc(iw/2)*2:trunc(ih/2)*2,fps=%d[vcan];", opts.fps));
            } else if ("BLUR".equals(opts.bgMode) || "BLUR_STRONG".equals(opts.bgMode)) {
                String blurStrength = "BLUR_STRONG".equals(opts.bgMode) ? "32:6" : "18:4";
                fc.append(String.format(Locale.US,
                        "[vprep]split=2[vbg][vfg];"
                                + "[vbg]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,boxblur=%s[vblur];"
                                + "[vfg]scale=%d:%d:force_original_aspect_ratio=decrease[vscaled];"
                                + "[vblur][vscaled]overlay=(W-w)/2:(H-h)/2,fps=%d[vcan];",
                        outW, outH, outW, outH, blurStrength, outW, outH, opts.fps));
            } else {
                String col = "WHITE".equals(opts.bgMode) ? "white"
                        : ("DARK_GRAY".equals(opts.bgMode) ? "0x1E293B"
                        : ("NAVY".equals(opts.bgMode) ? "0x0F172A" : "black"));
                fc.append(String.format(Locale.US,
                        "[vprep]scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2:%s,setsar=1,fps=%d[vcan];",
                        outW, outH, outW, outH, col, opts.fps));
            }

            String curV = "vcan";

            // Watermark PNG
            if (wmFile != null) {
                int wPx = Math.max(60, Math.min(outW / 2, opts.watermark.widthPx));
                String xy = "W-w-20:20";
                if (opts.watermark.position == 0) xy = "20:20";
                else if (opts.watermark.position == 2) xy = "20:H-h-20";
                else if (opts.watermark.position == 3) xy = "W-w-20:H-h-20";
                fc.append(String.format(Locale.US, "[1:v]scale=%d:-1[wm];[%s][wm]overlay=%s[vwm];", wPx, curV, xy));
                curV = "vwm";
            }

            // Subtitle / Teks Overlay via libass .srt burn-in (aman emoji/simbol/multiline!)
            if (!opts.overlays.isEmpty()) {
                srtFile = new File(workDir, "burn_sub_" + System.currentTimeMillis() + ".srt");
                writeSrtFile(opts.overlays, sMs, srtFile);
                String escPath = srtFile.getAbsolutePath().replace("\\", "/").replace(":", "\\:");
                fc.append(String.format(Locale.US,
                        "[%s]subtitles='%s':force_style='FontSize=18,PrimaryColour=&H00FFFFFF,BackColour=&H88000000,BorderStyle=4,MarginV=24'[vsub];",
                        curV, escPath));
                curV = "vsub";
            }

            // Fade In / Out Video
            double outDurSec = clipSec / spd;
            List<String> post = new ArrayList<>();
            if (opts.fadeInMs > 0) post.add(String.format(Locale.US, "fade=t=in:st=0:d=%.2f", opts.fadeInMs / 1000.0));
            if (opts.fadeOutMs > 0) {
                double st = Math.max(0.0, outDurSec - (opts.fadeOutMs / 1000.0));
                post.add(String.format(Locale.US, "fade=t=out:st=%.2f:d=%.2f", st, opts.fadeOutMs / 1000.0));
            }
            post.add("format=yuv420p");
            fc.append("[").append(curV).append("]").append(String.join(",", post)).append("[outv]");

            int aBr = Math.max(64, Math.min(320, opts.audioBitrateKbps));
            String mapAudio = "";
            if (!opts.muteAudio && meta.hasAudio) {
                List<String> aParts = new ArrayList<>();
                aParts.add("aresample=44100:async=1");
                if (opts.reverse) aParts.add("areverse");
                if (Math.abs(spd - 1.0f) >= 0.03f) aParts.add(AudioDspEngine.buildSpeedPitchFilter(spd, 0f, false));
                fc.append(";[0:a]").append(String.join(",", aParts)).append("[outa]");
                mapAudio = "-map \"[outa]\" -c:a aac -profile:a aac_low -b:a " + aBr + "k ";
            } else {
                mapAudio = "-an ";
            }

            String cmd = "-y -threads 0 " + inputs + "-filter_complex \"" + fc + "\" -map \"[outv]\" " + mapAudio
                    + "-c:v libx264 -preset superfast -crf " + opts.crf + " -movflags +faststart "
                    + AudioDspEngine.q(outMp4);
            AudioDspEngine.runFfmpegOrThrow(cmd);
        } finally {
            if (cleanVid) { try { inFile.delete(); } catch (Throwable ignored) {} }
            if (cleanWm && wmFile != null) { try { wmFile.delete(); } catch (Throwable ignored) {} }
            if (srtFile != null) { try { srtFile.delete(); } catch (Throwable ignored) {} }
        }
    }

    /** Membagi 1 video menjadi N bagian sama rata secara instan (-c copy). */
    public static List<File> fastSplitEqualVideoParts(
            Context ctx, Uri srcUri, File workDir, File outDir, String baseName, int partCount
    ) throws Exception {
        if (!outDir.exists()) outDir.mkdirs();
        int n = Math.max(2, Math.min(30, partCount));
        VideoMeta meta = inspectVideo(ctx, srcUri);
        long totalMs = Math.max(2000L, meta.durationMs);
        long partMs = Math.max(1000L, totalMs / n);
        String cleanBase = baseName.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        List<File> outFiles = new ArrayList<>();

        File inFile = AudioDspEngine.materializeRawCopyIfContent(ctx, srcUri, workDir, "vspeq");
        boolean cleanTmp = !"file".equals(srcUri.getScheme());
        try {
            for (int i = 0; i < n; i++) {
                long s = i * partMs;
                long e = (i == n - 1) ? totalMs : Math.min(totalMs, (i + 1) * partMs);
                File part = new File(outDir, cleanBase + "_Bagian" + (i + 1) + "_dari_" + n + ".mp4");
                String cmd = "-y -ss " + AudioDspEngine.formatSecForFfmpeg(s)
                        + " -to " + AudioDspEngine.formatSecForFfmpeg(e)
                        + " -i " + AudioDspEngine.q(inFile)
                        + " -c copy -avoid_negative_ts make_zero -movflags +faststart "
                        + AudioDspEngine.q(part);
                AudioDspEngine.runFfmpegOrThrow(cmd);
                outFiles.add(part);
            }
        } finally {
            if (cleanTmp) { try { inFile.delete(); } catch (Throwable ignored) {} }
        }
        return outFiles;
    }

    /** Menerapkan filter peningkatan suara (Normalisasi Loudnorm, Pembersih Noise, Compressor, Reverb, Gain) langsung ke video tanpa render ulang gambar (-c:v copy). */
    public static void applyAudioFilterOnVideo(
            Context ctx, Uri srcUri, File workDir, File outMp4, String audioFilterChain, int audioBitrateKbps
    ) throws Exception {
        File inFile = AudioDspEngine.materializeRawCopyIfContent(ctx, srcUri, workDir, "vafx");
        boolean cleanTmp = !"file".equals(srcUri.getScheme());
        try {
            int br = Math.max(64, Math.min(320, audioBitrateKbps));
            String cmd = "-y -i " + AudioDspEngine.q(inFile)
                    + " -filter:a \"" + audioFilterChain + "\" -c:v copy -c:a aac -profile:a aac_low -b:a " + br + "k -movflags +faststart "
                    + AudioDspEngine.q(outMp4);
            AudioDspEngine.runFfmpegOrThrow(cmd);
        } finally {
            if (cleanTmp) { try { inFile.delete(); } catch (Throwable ignored) {} }
        }
    }

    /** Mengekspor rentang video (IN-OUT) menjadi animasi GIF bergerak yang ringan dan tajam. */
    public static void exportVideoRangeToGif(
            Context ctx, Uri srcUri, File workDir, File outGif, long startMs, long endMs, int widthPx, int fps
    ) throws Exception {
        File inFile = AudioDspEngine.materializeRawCopyIfContent(ctx, srcUri, workDir, "vgif");
        boolean cleanTmp = !"file".equals(srcUri.getScheme());
        try {
            long durMs = Math.max(1000L, Math.min(15000L, endMs > startMs ? (endMs - startMs) : 5000L));
            int w = Math.max(240, Math.min(720, widthPx));
            int f = Math.max(8, Math.min(20, fps));
            String cmd = "-y -ss " + AudioDspEngine.formatSecForFfmpeg(startMs)
                    + " -t " + AudioDspEngine.formatSecForFfmpeg(durMs)
                    + " -i " + AudioDspEngine.q(inFile)
                    + " -vf \"fps=" + f + ",scale=" + w + ":-1:flags=lanczos\" -loop 0 "
                    + AudioDspEngine.q(outGif);
            AudioDspEngine.runFfmpegOrThrow(cmd);
        } finally {
            if (cleanTmp) { try { inFile.delete(); } catch (Throwable ignored) {} }
        }
    }

    /** Membuat Video Slideshow MP4 dari daftar Foto 1..N + Musik latar opsional. */
    public static void renderPhotoSlideshow(
            Context ctx, List<Uri> imageUris, Uri bgMusicUri, File workDir, File outMp4,
            double secPerPhoto, String aspectRatio
    ) throws Exception {
        if (imageUris == null || imageUris.isEmpty()) throw new IllegalArgumentException("Pilih minimal 1 foto.");
        int[] dims = resolveCanvasDimensions(1280, 720, aspectRatio, 1280, 0);
        int outW = dims[0], outH = dims[1];

        List<File> tmpFiles = new ArrayList<>();
        try {
            StringBuilder inputs = new StringBuilder();
            StringBuilder fc = new StringBuilder();
            StringBuilder vtags = new StringBuilder();
            for (int i = 0; i < imageUris.size(); i++) {
                Uri u = imageUris.get(i);
                File img = AudioDspEngine.materializeRawCopyIfContent(ctx, u, workDir, "ss_img_" + i);
                if (!"file".equals(u.getScheme())) tmpFiles.add(img);
                inputs.append(String.format(Locale.US, "-loop 1 -r 10 -t %.2f -i %s ", secPerPhoto, AudioDspEngine.q(img)));
                fc.append(String.format(Locale.US,
                        "[%d:v]scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=10,format=yuv420p[v%d];",
                        i, outW, outH, outW, outH, i));
                vtags.append("[v").append(i).append("]");
            }
            fc.append(vtags).append("concat=n=").append(imageUris.size()).append(":v=1:a=0[outv]");

            String audioMap = "-an ";
            double totalSlideSec = Math.max(0.5, imageUris.size() * secPerPhoto);
            if (bgMusicUri != null) {
                File bg = AudioDspEngine.materializeRawCopyIfContent(ctx, bgMusicUri, workDir, "ss_bgm");
                if (!"file".equals(bgMusicUri.getScheme())) tmpFiles.add(bg);
                inputs.append("-stream_loop -1 -i ").append(AudioDspEngine.q(bg)).append(" ");
                int bgIdx = imageUris.size();
                audioMap = String.format(Locale.US, "-map %d:a -c:a aac -profile:a aac_low -b:a 112k -t %.3f -shortest ", bgIdx, totalSlideSec);
            }

            String cmd = "-y -threads 0 " + inputs + "-filter_complex \"" + fc + "\" -map \"[outv]\" " + audioMap
                    + "-c:v libx264 -preset superfast -tune stillimage -crf 26 -pix_fmt yuv420p -movflags +faststart "
                    + AudioDspEngine.q(outMp4);
            AudioDspEngine.runFfmpegOrThrow(cmd);
        } finally {
            for (File f : tmpFiles) { try { f.delete(); } catch (Throwable ignored) {} }
        }
    }

    public static void extractFrameToPng(Context ctx, Uri srcUri, long atMs, File outPng) throws Exception {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            if ("file".equals(srcUri.getScheme()) && srcUri.getPath() != null) {
                mmr.setDataSource(srcUri.getPath());
            } else {
                try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(srcUri, "r")) {
                    if (pfd != null) mmr.setDataSource(pfd.getFileDescriptor());
                }
            }
            Bitmap bmp = mmr.getFrameAtTime(Math.max(0L, atMs) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
            if (bmp == null) throw new IllegalStateException("Gagal mengambil frame video.");
            File parent = outPng.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(outPng, false)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            bmp.recycle();
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
    }

    public static List<TextOverlayItem> parseSrtOverlays(Context ctx, Uri srtUri) throws Exception {
        List<TextOverlayItem> items = new ArrayList<>();
        try (InputStream in = ctx.getContentResolver().openInputStream(srtUri);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            StringBuilder text = new StringBuilder();
            long sMs = 0L, eMs = 0L;
            boolean inTime = false;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.contains("-->")) {
                    String[] p = line.split("-->");
                    if (p.length == 2) {
                        sMs = AudioDspEngine.parseTimeToMs(p[0].trim().replace(',', '.'), Long.MAX_VALUE);
                        eMs = AudioDspEngine.parseTimeToMs(p[1].trim().replace(',', '.'), Long.MAX_VALUE);
                        inTime = true;
                        text.setLength(0);
                    }
                } else if (line.isEmpty()) {
                    if (inTime && text.length() > 0) {
                        TextOverlayItem it = new TextOverlayItem();
                        it.text = text.toString().trim();
                        it.startMs = sMs;
                        it.endMs = Math.max(sMs + 500L, eMs);
                        items.add(it);
                    }
                    inTime = false;
                    text.setLength(0);
                } else if (inTime) {
                    if (text.length() > 0) text.append("\n");
                    text.append(line.replaceAll("<[^>]+>", ""));
                }
            }
            if (inTime && text.length() > 0) {
                TextOverlayItem it = new TextOverlayItem();
                it.text = text.toString().trim();
                it.startMs = sMs;
                it.endMs = Math.max(sMs + 500L, eMs);
                items.add(it);
            }
        }
        return items;
    }

    private static void writeSrtFile(List<TextOverlayItem> items, long offsetStartMs, File outSrt) throws Exception {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (TextOverlayItem it : items) {
            if (it == null || it.text == null || it.text.trim().isEmpty()) continue;
            long s = Math.max(0L, it.startMs - offsetStartMs);
            long e = Math.max(s + 400L, it.endMs - offsetStartMs);
            sb.append(idx++).append("\n")
                    .append(formatSrtTime(s)).append(" --> ").append(formatSrtTime(e)).append("\n")
                    .append(it.text.trim()).append("\n\n");
        }
        try (FileOutputStream fos = new FileOutputStream(outSrt, false)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String formatSrtTime(long ms) {
        long h = ms / 3600000L;
        long m = (ms % 3600000L) / 60000L;
        long s = (ms % 60000L) / 1000L;
        long rem = ms % 1000L;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, rem);
    }

    private static int[] resolveCanvasDimensions(int srcW, int srcH, String ratio, int longSide, int extraRot) {
        int baseW = (extraRot == 90 || extraRot == 270) ? srcH : srcW;
        int baseH = (extraRot == 90 || extraRot == 270) ? srcW : srcH;
        if (baseW <= 0) baseW = 1280;
        if (baseH <= 0) baseH = 720;
        int maxDim = longSide > 0 ? longSide : Math.min(1280, Math.max(baseW, baseH));
        int outW, outH;
        if ("9:16".equals(ratio)) { outH = maxDim; outW = Math.round(maxDim * 9f / 16f); }
        else if ("16:9".equals(ratio)) { outW = maxDim; outH = Math.round(maxDim * 9f / 16f); }
        else if ("1:1".equals(ratio)) { outW = Math.min(1080, maxDim); outH = outW; }
        else if ("4:5".equals(ratio)) { outH = maxDim; outW = Math.round(maxDim * 4f / 5f); }
        else if ("21:9".equals(ratio)) { outW = maxDim; outH = Math.round(maxDim * 9f / 21f); }
        else if ("4:3".equals(ratio)) { outW = maxDim; outH = Math.round(maxDim * 3f / 4f); }
        else {
            float sc = Math.min(1.0f, (float) maxDim / Math.max(baseW, baseH));
            outW = Math.round(baseW * sc);
            outH = Math.round(baseH * sc);
        }
        outW = Math.max(256, (outW / 2) * 2);
        outH = Math.max(256, (outH / 2) * 2);
        return new int[]{outW, outH};
    }

    private static String buildColorFilter(String preset, float brightness, float contrast, float saturation) {
        List<String> f = new ArrayList<>();
        if ("noir".equals(preset)) f.add("hue=s=0");
        else if ("sepia".equals(preset)) f.add("colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131");
        else if ("warm".equals(preset)) f.add("colorbalance=rs=.12:gs=.04:bs=-.10");
        else if ("cool".equals(preset)) f.add("colorbalance=rs=-.10:gs=.02:bs=.14");
        else if ("cinema".equals(preset)) f.add("eq=contrast=1.15:saturation=1.15:brightness=-0.03");
        else if ("vintage".equals(preset)) f.add("eq=contrast=0.95:saturation=0.68,colorchannelmixer=.9:.1:0:0:0:.95:.05:0:0:0:.85");
        else if ("vivid".equals(preset)) f.add("eq=saturation=1.45:contrast=1.08");
        else if ("night".equals(preset)) f.add("eq=brightness=-0.08:saturation=0.75,colorbalance=bs=.12");
        else if ("negative".equals(preset)) f.add("negate");
        else if ("sharpen".equals(preset)) f.add("unsharp=5:5:1.2:5:5:0.0");
        else if ("soft_glow".equals(preset)) f.add("eq=brightness=0.04:saturation=1.08,boxblur=1:1");
        else if ("cyberpunk".equals(preset)) f.add("eq=contrast=1.22:saturation=1.55,colorbalance=rs=.18:gs=-.08:bs=.22");
        else if ("pastel".equals(preset)) f.add("eq=brightness=0.05:contrast=0.90:saturation=0.80");
        else if ("documentary".equals(preset)) f.add("eq=contrast=1.18:saturation=0.85,unsharp=5:5:0.8:5:5:0.0");
        else if ("sunset".equals(preset)) f.add("colorbalance=rs=.22:gs=.08:bs=-.16,eq=saturation=1.25");
        else if ("drama".equals(preset)) f.add("eq=contrast=1.35:brightness=-0.05:saturation=0.85");
        else if ("vignette".equals(preset)) f.add("vignette=PI/4");

        if (Math.abs(brightness) >= 0.02f || Math.abs(contrast - 1.0f) >= 0.03f || Math.abs(saturation - 1.0f) >= 0.03f) {
            f.add(String.format(Locale.US, "eq=brightness=%.2f:contrast=%.2f:saturation=%.2f",
                    Math.max(-0.5f, Math.min(0.5f, brightness)),
                    Math.max(0.5f, Math.min(2.0f, contrast)),
                    Math.max(0.0f, Math.min(2.5f, saturation))));
        }
        return String.join(",", f);
    }

    /**
     * Memasang Foto Cover / Sampul (Thumbnail MP4 & Frame Sampul Pembuka) pada berkas Video MP4.
     * Mode 0: Sematkan gambar sebagai attached_pic thumbnail MP4 tanpa render ulang video.
     * Mode 1: Sisipkan gambar sampul selama 1.5 detik di awal video + attached_pic agar tampil di WhatsApp/Galeri.
     */
    public static void embedVideoCoverImage(
            Context ctx, Uri videoUri, Uri coverImgUri, File workDir, File outMp4, boolean prependIntroFrame
    ) throws Exception {
        File inVid = AudioDspEngine.materializeRawCopyIfContent(ctx, videoUri, workDir, "vcov_v");
        boolean cleanVid = !"file".equals(videoUri.getScheme());
        File inCov = AudioDspEngine.materializeRawCopyIfContent(ctx, coverImgUri, workDir, "vcov_i");
        boolean cleanCov = !"file".equals(coverImgUri.getScheme());
        try {
            if (!prependIntroFrame) {
                String cmd = "-y -i " + AudioDspEngine.q(inVid) + " -i " + AudioDspEngine.q(inCov)
                        + " -map 0 -map 1 -c copy -c:v:1 mjpeg -disposition:v:1 attached_pic -movflags +faststart "
                        + AudioDspEngine.q(outMp4);
                AudioDspEngine.runFfmpegOrThrow(cmd);
            } else {
                VideoMeta meta = inspectVideo(ctx, videoUri);
                int w = Math.max(360, (meta.width > 0 ? (meta.width / 2) * 2 : 720));
                int h = Math.max(360, (meta.height > 0 ? (meta.height / 2) * 2 : 1280));
                String fc = String.format(Locale.US,
                        "[0:v]scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=30,format=yuv420p[v0];"
                                + "[1:v]scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=30,format=yuv420p[v1];"
                                + "aevalsrc=0:channel_layout=stereo:sample_rate=44100:d=1.2[a0];"
                                + "[1:a]aresample=44100:async=1,aformat=sample_fmts=fltp:channel_layouts=stereo[a1];"
                                + "[v0][a0][v1][a1]concat=n=2:v=1:a=1[outv][outa]",
                        w, h, w, h, w, h, w, h);
                String cmd = "-y -threads 0 -loop 1 -t 1.2 -i " + AudioDspEngine.q(inCov) + " -i " + AudioDspEngine.q(inVid)
                        + " -filter_complex \"" + fc + "\" -map \"[outv]\" -map \"[outa]\" "
                        + "-c:v libx264 -preset superfast -crf 25 -c:a aac -profile:a aac_low -b:a 112k -movflags +faststart "
                        + AudioDspEngine.q(outMp4);
                AudioDspEngine.runFfmpegOrThrow(cmd);
            }
        } finally {
            if (cleanVid) { try { inVid.delete(); } catch (Throwable ignored) {} }
            if (cleanCov) { try { inCov.delete(); } catch (Throwable ignored) {} }
        }
    }

    /**
     * Mengulang video berkali-kali (Video Looper 2x - 5x) atau membuat efek Boomerang (Maju lalu Mundur).
     */
    public static void renderBoomerangOrLoopVideo(
            Context ctx, Uri videoUri, File workDir, File outMp4, boolean boomerang, int loopTimes
    ) throws Exception {
        File inVid = AudioDspEngine.materializeRawCopyIfContent(ctx, videoUri, workDir, "vloop_v");
        boolean cleanVid = !"file".equals(videoUri.getScheme());
        try {
            if (!boomerang) {
                int loops = Math.max(1, Math.min(5, loopTimes - 1));
                String cmd = "-y -stream_loop " + loops + " -i " + AudioDspEngine.q(inVid)
                        + " -c copy -movflags +faststart " + AudioDspEngine.q(outMp4);
                AudioDspEngine.runFfmpegOrThrow(cmd);
            } else {
                String cmd = "-y -threads 0 -t 6 -i " + AudioDspEngine.q(inVid)
                        + " -filter_complex \"[0:v]split[v1][v2];[v2]reverse[vr];[v1][vr]concat=n=2:v=1:a=0,format=yuv420p[outv]\" "
                        + "-map \"[outv]\" -an -c:v libx264 -preset superfast -crf 25 -movflags +faststart "
                        + AudioDspEngine.q(outMp4);
                AudioDspEngine.runFfmpegOrThrow(cmd);
            }
        } finally {
            if (cleanVid) { try { inVid.delete(); } catch (Throwable ignored) {} }
        }
    }

    /**
     * Menerapkan efek visual cepat pada video (Fade In/Out Hitam, Sensor Blur Area, Pertajam, dll).
     */
    public static void applyVideoQuickVisualFilter(
            Context ctx, Uri videoUri, File workDir, File outMp4, String vfChain, String afChain, int audioBitrateKbps
    ) throws Exception {
        File inVid = AudioDspEngine.materializeRawCopyIfContent(ctx, videoUri, workDir, "vqvf_v");
        boolean cleanVid = !"file".equals(videoUri.getScheme());
        try {
            int abr = Math.max(64, Math.min(320, audioBitrateKbps));
            String afArg = (afChain == null || afChain.trim().isEmpty()) ? "-c:a aac -profile:a aac_low -b:a " + abr + "k "
                    : ("-af \"" + afChain + "\" -c:a aac -profile:a aac_low -b:a " + abr + "k ");
            String cmd = "-y -threads 0 -i " + AudioDspEngine.q(inVid)
                    + " -vf \"" + vfChain + ",format=yuv420p\" "
                    + "-c:v libx264 -preset superfast -crf 25 " + afArg + "-movflags +faststart "
                    + AudioDspEngine.q(outMp4);
            AudioDspEngine.runFfmpegOrThrow(cmd);
        } finally {
            if (cleanVid) { try { inVid.delete(); } catch (Throwable ignored) {} }
        }
    }

    /**
     * Menggabungkan 2 video menjadi Kolase 2 Layar (Split Screen Atas-Bawah 9:16 atau Kiri-Kanan 16:9).
     */
    public static void renderSplitScreenTwoVideos(
            Context ctx, Uri vid1Uri, Uri vid2Uri, File workDir, File outMp4, boolean topBottom
    ) throws Exception {
        File f1 = AudioDspEngine.materializeRawCopyIfContent(ctx, vid1Uri, workDir, "split_v1");
        boolean c1 = !"file".equals(vid1Uri.getScheme());
        File f2 = AudioDspEngine.materializeRawCopyIfContent(ctx, vid2Uri, workDir, "split_v2");
        boolean c2 = !"file".equals(vid2Uri.getScheme());
        try {
            String fc;
            if (topBottom) {
                fc = "[0:v]scale=720:640:force_original_aspect_ratio=increase,crop=720:640,setsar=1[v0];"
                        + "[1:v]scale=720:640:force_original_aspect_ratio=increase,crop=720:640,setsar=1[v1];"
                        + "[v0][v1]vstack=inputs=2,format=yuv420p[outv]";
            } else {
                fc = "[0:v]scale=640:720:force_original_aspect_ratio=increase,crop=640:720,setsar=1[v0];"
                        + "[1:v]scale=640:720:force_original_aspect_ratio=increase,crop=640:720,setsar=1[v1];"
                        + "[v0][v1]hstack=inputs=2,format=yuv420p[outv]";
            }
            String cmd = "-y -threads 0 -i " + AudioDspEngine.q(f1) + " -i " + AudioDspEngine.q(f2)
                    + " -filter_complex \"" + fc + "\" -map \"[outv]\" -map 0:a? "
                    + "-c:v libx264 -preset superfast -crf 25 -c:a aac -profile:a aac_low -b:a 112k -shortest -movflags +faststart "
                    + AudioDspEngine.q(outMp4);
            AudioDspEngine.runFfmpegOrThrow(cmd);
        } finally {
            if (c1) { try { f1.delete(); } catch (Throwable ignored) {} }
            if (c2) { try { f2.delete(); } catch (Throwable ignored) {} }
        }
    }
}
