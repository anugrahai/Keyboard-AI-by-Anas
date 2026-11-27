package com.annas;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mesin Audio Studio Berbasis FFmpeg 8.1 Native C/NEON (Bening Kualitas Studio, Zero Serak, Cepat).
 * - Tidak ada lagi decoding WAV saat memilih berkas (buka & putar 0 detik!).
 * - Mendukung seluruh format: MP3, M4A/AAC, WAV, OGG, FLAC, OPUS, dan audio dari video MP4/MKV.
 */
public final class AudioDspEngine {

    public interface ProgressCallback {
        void onProgress(int percent, String status);
    }

    public static final class AudioMeta {
        public String displayName = "audio";
        public long durationMs = 0L;
        public int sampleRate = 44100;
        public int channels = 2;
        public long bitrate = 128000L;
        public long sizeBytes = 0L;
        public boolean hasAudio = false;
    }

    public static final class MixTrackSpec {
        public Uri uri;
        public String title = "Audio";
        public float volume = 1.0f;    // 0.0 .. 2.5
        public long delayMs = 0L;
        public long endMs = 0L;        // 0 = sampai akhir proyek; > delayMs = stop tepat pada detik endMs
        public boolean loop = false;
    }

    private AudioDspEngine() {}

    // ==================== WAKTU & INSPEKSI KILAT ====================

    public static String formatMs(long ms) {
        if (ms < 0L) ms = 0L;
        long totalSec = ms / 1000L;
        long remMs = ms % 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0) return String.format(Locale.US, "%02d:%02d:%02d.%02d", h, m, s, remMs / 10L);
        return String.format(Locale.US, "%02d:%02d.%02d", m, s, remMs / 10L);
    }

    public static String formatMsShort(long ms) {
        if (ms < 0L) ms = 0L;
        long totalSec = ms / 1000L;
        long m = totalSec / 60L;
        long s = totalSec % 60L;
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    public static String formatSecForFfmpeg(long ms) {
        if (ms < 0L) ms = 0L;
        return String.format(Locale.US, "%.3f", ms / 1000.0);
    }

    public static long parseTimeToMs(String raw, long maxMs) {
        if (raw == null) return 0L;
        String t = raw.trim().replace(',', '.');
        if (t.isEmpty()) return 0L;
        try {
            double sec;
            if (t.contains(":")) {
                String[] parts = t.split(":");
                if (parts.length == 2) sec = Double.parseDouble(parts[0].trim()) * 60.0 + Double.parseDouble(parts[1].trim());
                else if (parts.length == 3) sec = Double.parseDouble(parts[0].trim()) * 3600.0 + Double.parseDouble(parts[1].trim()) * 60.0 + Double.parseDouble(parts[2].trim());
                else sec = Double.parseDouble(parts[0].trim());
            } else {
                sec = Double.parseDouble(t);
            }
            long ms = Math.round(sec * 1000.0);
            if (ms < 0L) ms = 0L;
            if (maxMs > 0L && ms > maxMs) ms = maxMs;
            return ms;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static String resolveDisplayName(Context ctx, Uri uri) {
        if (uri == null) return "audio";
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath()).getName();
        }
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.trim().isEmpty()) return n;
                }
            }
        } catch (Throwable ignored) {}
        String last = uri.getLastPathSegment();
        return (last == null || last.trim().isEmpty()) ? "media" : last;
    }

    public static AudioMeta inspectMedia(Context ctx, Uri uri) {
        AudioMeta meta = new AudioMeta();
        meta.displayName = resolveDisplayName(ctx, uri);
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                File f = new File(uri.getPath());
                meta.sizeBytes = f.length();
                mmr.setDataSource(f.getAbsolutePath());
            } else {
                try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
                    if (pfd != null) {
                        meta.sizeBytes = pfd.getStatSize();
                        mmr.setDataSource(pfd.getFileDescriptor());
                    }
                }
            }
            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durStr != null) meta.durationMs = Long.parseLong(durStr);
            String brStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (brStr != null) meta.bitrate = Long.parseLong(brStr);
            String srStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
            if (srStr != null) meta.sampleRate = Integer.parseInt(srStr);
            String hasA = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            meta.hasAudio = "yes".equalsIgnoreCase(hasA) || meta.durationMs > 0L;
        } catch (Throwable ignored) {
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
        if (meta.durationMs <= 0L) {
            try {
                String p = "file".equals(uri.getScheme()) && uri.getPath() != null
                        ? uri.getPath()
                        : FFmpegKitConfig.getSafParameterForRead(ctx, uri);
                MediaInformationSession mis = FFprobeKit.getMediaInformation(p);
                MediaInformation mi = mis != null ? mis.getMediaInformation() : null;
                if (mi != null && mi.getDuration() != null) {
                    meta.durationMs = Math.round(Double.parseDouble(mi.getDuration()) * 1000.0);
                    meta.hasAudio = true;
                }
            } catch (Throwable ignored) {}
        }
        return meta;
    }

    /** Memastikan URI dapat dibaca dengan mulus & seekable oleh FFmpeg tanpa men-decode PCM. */
    public static File materializeRawCopyIfContent(Context ctx, Uri uri, File workDir, String prefix) throws Exception {
        if (uri == null) throw new IOException("Berkas kosong.");
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            if (f.exists() && f.canRead()) return f;
        }
        String name = resolveDisplayName(ctx, uri);
        String ext = ".dat";
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) ext = name.substring(dot).replaceAll("[^a-zA-Z0-9.]", "");
        if (!workDir.exists()) workDir.mkdirs();
        File dst = new File(workDir, prefix + "_" + System.currentTimeMillis() + ext);
        try (InputStream in = OfficeUtils.openRead(ctx, uri);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst, false), 65536)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return dst;
    }

    public static String q(File f) {
        return "\"" + f.getAbsolutePath().replace("\"", "\\\"") + "\"";
    }

    public static String codecArgsForOutput(String fmt, int bitrateKbps) {
        return codecArgsForOutput(fmt, bitrateKbps, 44100, 2);
    }

    public static String codecArgsForOutput(String fmt, int bitrateKbps, int sampleRate, int channels) {
        String lower = fmt == null ? "m4a" : fmt.toLowerCase(Locale.ROOT);
        int br = Math.max(32, Math.min(320, bitrateKbps));
        int sr = sampleRate <= 0 ? 44100 : sampleRate;
        int ch = (channels == 1) ? 1 : 2;
        if ("mp3".equals(lower)) {
            return "-vn -c:a libmp3lame -b:a " + br + "k -ar " + sr + " -ac " + ch;
        }
        if ("wav".equals(lower)) {
            return "-vn -c:a pcm_s16le -ar " + sr + " -ac " + ch;
        }
        if ("flac".equals(lower)) {
            return "-vn -c:a flac -ar " + sr + " -ac " + ch;
        }
        if ("ogg".equals(lower)) {
            return "-vn -c:a libvorbis -b:a " + br + "k -ar " + sr + " -ac " + ch;
        }
        if ("opus".equals(lower)) {
            return "-vn -c:a libopus -b:a " + br + "k -vbr on -compression_level 10 -application audio -ar 48000 -ac " + ch;
        }
        return "-vn -c:a aac -profile:a aac_low -b:a " + br + "k -ar " + sr + " -ac " + ch + " -movflags +faststart";
    }

    /**
     * Mengompres berkas audio (mis. WAV mentah dari Gemini TTS) ke M4A/OPUS/MP3/WAV dengan filter
     * pembersih sub-bass (<55 Hz) & anti-clipping agar ukuran sangat hemat namun vokal tetap jernih seperti ori.
     */
    public static void encodeAudioClean(
            File inFile, File outFile, String outFmt, int bitrateKbps,
            int sampleRate, int channels, boolean voiceClarityFilter
    ) throws Exception {
        String lower = outFmt == null ? "m4a" : outFmt.toLowerCase(Locale.ROOT);
        String af = voiceClarityFilter
                ? "-af \"highpass=f=55,alimiter=limit=0.95\" "
                : "";
        if ("wav".equals(lower)) {
            af = voiceClarityFilter ? "-af \"highpass=f=55,alimiter=limit=0.95\" " : "";
        }
        String cmd = "-y -i " + q(inFile) + " " + af
                + codecArgsForOutput(lower, bitrateKbps, sampleRate, channels) + " " + q(outFile);
        runFfmpegOrThrow(cmd);
    }

    public static void runFfmpegOrThrow(String cmd) {
        FFmpegSession session = FFmpegKit.execute(cmd);
        if (!ReturnCode.isSuccess(session.getReturnCode())) {
            String logs = session.getAllLogsAsString();
            String tail = logs == null ? "FFmpeg error" : logs.substring(Math.max(0, logs.length() - 400));
            throw new IllegalStateException("Proses FFmpeg gagal: " + tail);
        }
    }

    // ==================== OPERASI POTONG, BELAH, GABUNG (FFMPEG NATIVE) ====================

    public static void trimAudio(
            Context ctx, Uri srcUri, File workDir, File outFile,
            long startMs, long endMs, String outFmt, int bitrateKbps
    ) throws Exception {
        trimAudio(ctx, srcUri, workDir, outFile, startMs, endMs, outFmt, bitrateKbps, 44100, 2);
    }

    public static void trimAudio(
            Context ctx, Uri srcUri, File workDir, File outFile,
            long startMs, long endMs, String outFmt, int bitrateKbps, int sampleRate, int channels
    ) throws Exception {
        File inFile = materializeRawCopyIfContent(ctx, srcUri, workDir, "trim_in");
        boolean cleanTmp = !"file".equals(srcUri.getScheme());
        try {
            StringBuilder cmd = new StringBuilder("-y ");
            if (startMs > 0) cmd.append("-ss ").append(formatSecForFfmpeg(startMs)).append(" ");
            if (endMs > startMs) cmd.append("-to ").append(formatSecForFfmpeg(endMs)).append(" ");
            cmd.append("-i ").append(q(inFile)).append(" ");
            cmd.append(codecArgsForOutput(outFmt, bitrateKbps, sampleRate, channels)).append(" ").append(q(outFile));
            runFfmpegOrThrow(cmd.toString());
        } finally {
            if (cleanTmp) { try { inFile.delete(); } catch (Throwable ignored) {} }
        }
    }

    /** Membagi 1 berkas audio menjadi N bagian sama rata secara presisi. */
    public static List<File> splitAudioEqualParts(
            Context ctx, Uri srcUri, File workDir, File outDir,
            String baseName, int partCount, String outFmt, int bitrateKbps, int sampleRate, int channels
    ) throws Exception {
        if (!outDir.exists()) outDir.mkdirs();
        int n = Math.max(2, Math.min(30, partCount));
        AudioMeta meta = inspectMedia(ctx, srcUri);
        long totalMs = Math.max(2000L, meta.durationMs);
        long partMs = Math.max(1000L, totalMs / n);
        String cleanBase = baseName.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        List<File> outFiles = new ArrayList<>();

        File inFile = materializeRawCopyIfContent(ctx, srcUri, workDir, "speq_in");
        boolean cleanTmp = !"file".equals(srcUri.getScheme());
        try {
            for (int i = 0; i < n; i++) {
                long s = i * partMs;
                long e = (i == n - 1) ? totalMs : Math.min(totalMs, (i + 1) * partMs);
                File out = new File(outDir, cleanBase + "_Bagian" + (i + 1) + "_dari_" + n + "." + outFmt);
                String cmd = "-y -ss " + formatSecForFfmpeg(s) + " -to " + formatSecForFfmpeg(e)
                        + " -i " + q(inFile) + " " + codecArgsForOutput(outFmt, bitrateKbps, sampleRate, channels) + " " + q(out);
                runFfmpegOrThrow(cmd);
                outFiles.add(out);
            }
        } finally {
            if (cleanTmp) { try { inFile.delete(); } catch (Throwable ignored) {} }
        }
        return outFiles;
    }

    public static void applyAudioFilter(
            Context ctx, Uri srcUri, File workDir, File outFile,
            String filterChain, String outFmt, int bitrateKbps, int sampleRate, int channels
    ) throws Exception {
        File inFile = materializeRawCopyIfContent(ctx, srcUri, workDir, "fx_in");
        boolean cleanTmp = !"file".equals(srcUri.getScheme());
        try {
            String fArg = (filterChain == null || filterChain.trim().isEmpty())
                    ? "" : ("-filter:a \"" + filterChain + "\" ");
            String cmd = "-y -i " + q(inFile) + " " + fArg
                    + codecArgsForOutput(outFmt, bitrateKbps, sampleRate, channels) + " " + q(outFile);
            runFfmpegOrThrow(cmd);
        } finally {
            if (cleanTmp) { try { inFile.delete(); } catch (Throwable ignored) {} }
        }
    }

    public static void cutRangeAudio(
            Context ctx, Uri srcUri, File workDir, File outFile,
            long cutStartMs, long cutEndMs, long totalDurMs, String outFmt, int bitrateKbps
    ) throws Exception {
        File inFile = materializeRawCopyIfContent(ctx, srcUri, workDir, "cut_in");
        boolean cleanTmp = !"file".equals(srcUri.getScheme());
        try {
            boolean hasBefore = cutStartMs > 100L;
            boolean hasAfter = cutEndMs + 100L < totalDurMs;
            if (!hasBefore && !hasAfter) throw new IllegalStateException("Rentang buang mencakup seluruh audio.");
            if (!hasBefore) {
                String cmd = "-y -ss " + formatSecForFfmpeg(cutEndMs) + " -i " + q(inFile) + " "
                        + codecArgsForOutput(outFmt, bitrateKbps) + " " + q(outFile);
                runFfmpegOrThrow(cmd);
                return;
            }
            if (!hasAfter) {
                String cmd = "-y -to " + formatSecForFfmpeg(cutStartMs) + " -i " + q(inFile) + " "
                        + codecArgsForOutput(outFmt, bitrateKbps) + " " + q(outFile);
                runFfmpegOrThrow(cmd);
                return;
            }
            String fc = String.format(Locale.US,
                    "[0:a]aresample=44100:async=1,aformat=sample_fmts=fltp:channel_layouts=stereo,atrim=0:%s,asetpts=PTS-STARTPTS[a0];"
                            + "[0:a]aresample=44100:async=1,aformat=sample_fmts=fltp:channel_layouts=stereo,atrim=%s,asetpts=PTS-STARTPTS[a1];"
                            + "[a0][a1]concat=n=2:v=0:a=1[outa]",
                    formatSecForFfmpeg(cutStartMs), formatSecForFfmpeg(cutEndMs));
            String cmd = "-y -i " + q(inFile) + " -filter_complex \"" + fc + "\" -map \"[outa]\" "
                    + codecArgsForOutput(outFmt, bitrateKbps) + " " + q(outFile);
            runFfmpegOrThrow(cmd);
        } finally {
            if (cleanTmp) { try { inFile.delete(); } catch (Throwable ignored) {} }
        }
    }

    /** Gabung Audio 1 + Audio 2 + Audio 3 + ... dengan jeda opsional antar lagu & samakan format otomatis. */
    public static void mergeAudios(
            Context ctx, List<Uri> uris, File workDir, File outFile,
            double gapSec, String outFmt, int bitrateKbps
    ) throws Exception {
        if (uris == null || uris.isEmpty()) throw new IllegalArgumentException("Pilih minimal 1 audio.");
        List<File> tmpTemps = new ArrayList<>();
        List<File> matFiles = new ArrayList<>();
        try {
            for (int i = 0; i < uris.size(); i++) {
                Uri u = uris.get(i);
                File f = materializeRawCopyIfContent(ctx, u, workDir, "m_" + i);
                if (!"file".equals(u.getScheme())) tmpTemps.add(f);
                matFiles.add(f);
            }
            if (matFiles.size() == 1) {
                String cmd = "-y -i " + q(matFiles.get(0)) + " " + codecArgsForOutput(outFmt, bitrateKbps) + " " + q(outFile);
                runFfmpegOrThrow(cmd);
                return;
            }
            StringBuilder inputs = new StringBuilder();
            StringBuilder fc = new StringBuilder();
            StringBuilder concatInputs = new StringBuilder();
            int totalConcatCount = 0;
            for (int i = 0; i < matFiles.size(); i++) {
                inputs.append("-i ").append(q(matFiles.get(i))).append(" ");
                fc.append(String.format(Locale.US,
                        "[%d:a]aresample=44100:async=1,aformat=sample_fmts=fltp:channel_layouts=stereo,asetpts=PTS-STARTPTS[a%d];",
                        i, i));
                concatInputs.append("[a").append(i).append("]");
                totalConcatCount++;
                if (gapSec >= 0.05 && i < matFiles.size() - 1) {
                    fc.append(String.format(Locale.US,
                            "aevalsrc=0:channel_layout=stereo:sample_rate=44100:d=%.2f[sil%d];", gapSec, i));
                    concatInputs.append("[sil").append(i).append("]");
                    totalConcatCount++;
                }
            }
            fc.append(concatInputs).append("concat=n=").append(totalConcatCount).append(":v=0:a=1[outa]");
            String cmd = "-y " + inputs + "-filter_complex \"" + fc + "\" -map \"[outa]\" "
                    + codecArgsForOutput(outFmt, bitrateKbps) + " " + q(outFile);
            runFfmpegOrThrow(cmd);
        } finally {
            for (File t : tmpTemps) { try { t.delete(); } catch (Throwable ignored) {} }
        }
    }

    /** Multi-Track Mixer (Audio 1 + Audio 2 + Audio 3...) dengan kontrol volume, delay, loop, dan Auto-Ducking. */
    public static void mixMultiTracks(
            Context ctx, List<MixTrackSpec> tracks, File workDir, File outFile,
            boolean autoDuckingWithTrack1, String outFmt, int bitrateKbps
    ) throws Exception {
        mixMultiTracks(ctx, tracks, workDir, outFile, autoDuckingWithTrack1, outFmt, bitrateKbps, 44100, 2);
    }

    public static void mixMultiTracks(
            Context ctx, List<MixTrackSpec> tracks, File workDir, File outFile,
            boolean autoDuckingWithTrack1, String outFmt, int bitrateKbps,
            int sampleRate, int channels
    ) throws Exception {
        if (tracks == null || tracks.isEmpty()) throw new IllegalArgumentException("Tambahkan minimal 1 track audio.");
        int sr = sampleRate <= 0 ? 44100 : sampleRate;
        int ch = (channels == 1) ? 1 : 2;
        String chLayout = (ch == 1) ? "mono" : "stereo";
        List<File> tmpTemps = new ArrayList<>();
        List<File> matFiles = new ArrayList<>();
        try {
            for (int i = 0; i < tracks.size(); i++) {
                MixTrackSpec t = tracks.get(i);
                File f = materializeRawCopyIfContent(ctx, t.uri, workDir, "mix_" + i);
                if (!"file".equals(t.uri.getScheme())) tmpTemps.add(f);
                matFiles.add(f);
            }
            if (tracks.size() == 1) {
                MixTrackSpec t0 = tracks.get(0);
                String cmd = "-y -i " + q(matFiles.get(0)) + " -filter:a \"volume=" + String.format(Locale.US, "%.2f", t0.volume) + ",alimiter=limit=0.95\" "
                        + codecArgsForOutput(outFmt, bitrateKbps, sr, ch) + " " + q(outFile);
                runFfmpegOrThrow(cmd);
                return;
            }
            AudioMeta mainMeta = inspectMedia(ctx, Uri.fromFile(matFiles.get(0)));
            long mainDurMs = mainMeta.durationMs;

            StringBuilder inputs = new StringBuilder();
            StringBuilder fc = new StringBuilder();
            for (int i = 0; i < tracks.size(); i++) {
                MixTrackSpec t = tracks.get(i);
                if (i > 0 && t.loop) inputs.append("-stream_loop -1 ");
                inputs.append("-i ").append(q(matFiles.get(i))).append(" ");
                String trimStr = "";
                if (i > 0 && t.endMs > t.delayMs) {
                    long durLimit = t.endMs - t.delayMs;
                    if (mainDurMs > 0) durLimit = Math.min(durLimit, Math.max(200L, mainDurMs - t.delayMs));
                    trimStr = ",atrim=0:" + formatSecForFfmpeg(durLimit) + ",asetpts=PTS-STARTPTS";
                } else if (i > 0 && t.loop && mainDurMs > 0) {
                    long durLimit = Math.max(200L, mainDurMs - t.delayMs);
                    trimStr = ",atrim=0:" + formatSecForFfmpeg(durLimit) + ",asetpts=PTS-STARTPTS";
                }
                String delayStr = t.delayMs > 0 ? (",adelay=" + t.delayMs + "|" + t.delayMs) : "";
                fc.append(String.format(Locale.US,
                        "[%d:a]aresample=%d:async=1,aformat=sample_fmts=fltp:channel_layouts=%s%s,volume=%.2f%s[tr%d];",
                        i, sr, chLayout, trimStr, t.volume, delayStr, i));
            }
            if (autoDuckingWithTrack1 && tracks.size() >= 2) {
                // Track 1 sebagai sidechain pengontrol musik latar (track 2..N)
                StringBuilder bgTags = new StringBuilder();
                for (int i = 1; i < tracks.size(); i++) bgTags.append("[tr").append(i).append("]");
                if (tracks.size() == 2) {
                    fc.append("[tr0]asplit=2[vmain][vsc];");
                    fc.append("[tr1][vsc]sidechaincompress=threshold=0.03:ratio=5:attack=40:release=350[bgduck];");
                    fc.append("[vmain][bgduck]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.95[outa]");
                } else {
                    fc.append(bgTags).append("amix=inputs=").append(tracks.size() - 1).append(":duration=first:normalize=0[bgall];");
                    fc.append("[tr0]asplit=2[vmain][vsc];");
                    fc.append("[bgall][vsc]sidechaincompress=threshold=0.03:ratio=5:attack=40:release=350[bgduck];");
                    fc.append("[vmain][bgduck]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.95[outa]");
                }
            } else {
                StringBuilder allTags = new StringBuilder();
                for (int i = 0; i < tracks.size(); i++) allTags.append("[tr").append(i).append("]");
                fc.append(allTags).append("amix=inputs=").append(tracks.size())
                        .append(":duration=first:normalize=0,alimiter=limit=0.95[outa]");
            }
            String limitArg = mainDurMs > 0 ? ("-t " + formatSecForFfmpeg(mainDurMs) + " ") : "";
            String cmd = "-y " + inputs + "-filter_complex \"" + fc + "\" -map \"[outa]\" "
                    + limitArg + codecArgsForOutput(outFmt, bitrateKbps, sr, ch) + " " + q(outFile);
            runFfmpegOrThrow(cmd);
        } finally {
            for (File t : tmpTemps) { try { t.delete(); } catch (Throwable ignored) {} }
        }
    }

    /** Memecah audio panjang (misal 5 menit) menjadi Part 1..N sesuai durasi Status WA (15/30/60/90 dtk). */
    public static List<File> splitAudioStatusParts(
            Context ctx, Uri srcUri, File workDir, File outDir,
            String baseName, int partSec, String outFmt, int bitrateKbps
    ) throws Exception {
        if (!outDir.exists()) outDir.mkdirs();
        AudioMeta meta = inspectMedia(ctx, srcUri);
        long totalMs = Math.max(1000L, meta.durationMs);
        long partMs = Math.max(3000L, partSec * 1000L);
        int totalParts = (int) Math.max(1L, (totalMs + partMs - 1L) / partMs);
        String cleanBase = baseName.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        List<File> outFiles = new ArrayList<>();

        File inFile = materializeRawCopyIfContent(ctx, srcUri, workDir, "sp_in");
        boolean cleanTmp = !"file".equals(srcUri.getScheme());
        try {
            for (int i = 0; i < totalParts; i++) {
                long s = i * partMs;
                long e = Math.min(totalMs, (i + 1) * partMs);
                File out = new File(outDir, cleanBase + "_Part" + (i + 1) + "." + outFmt);
                String cmd = "-y -ss " + formatSecForFfmpeg(s) + " -to " + formatSecForFfmpeg(e)
                        + " -i " + q(inFile) + " " + codecArgsForOutput(outFmt, bitrateKbps) + " " + q(out);
                runFfmpegOrThrow(cmd);
                outFiles.add(out);
            }
        } finally {
            if (cleanTmp) { try { inFile.delete(); } catch (Throwable ignored) {} }
        }
        return outFiles;
    }

    /** Menjalankan filter FFmpeg apa saja (`-filter:a ...`) untuk hasil jernih kualitas studio. */
    public static void applyAudioFilter(
            Context ctx, Uri srcUri, File workDir, File outFile,
            String filterChain, String outFmt, int bitrateKbps
    ) throws Exception {
        File inFile = materializeRawCopyIfContent(ctx, srcUri, workDir, "fx_in");
        boolean cleanTmp = !"file".equals(srcUri.getScheme());
        try {
            String fArg = (filterChain == null || filterChain.trim().isEmpty())
                    ? "" : ("-filter:a \"" + filterChain + "\" ");
            String cmd = "-y -i " + q(inFile) + " " + fArg + codecArgsForOutput(outFmt, bitrateKbps) + " " + q(outFile);
            runFfmpegOrThrow(cmd);
        } finally {
            if (cleanTmp) { try { inFile.delete(); } catch (Throwable ignored) {} }
        }
    }

    /** Membuat filter Speed (0.25x-4x) dan Pitch (-12..+12 semitone) dengan atempo berantai yang jernih. */
    public static String buildSpeedPitchFilter(float speed, float pitchSemitones, boolean preserveTempoOnPitch) {
        double s = Math.max(0.25, Math.min(4.0, speed));
        double p = Math.pow(2.0, pitchSemitones / 12.0);
        List<String> parts = new ArrayList<>();
        if (Math.abs(pitchSemitones) >= 0.1f) {
            int newRate = (int) Math.round(44100.0 * p);
            parts.add("asetrate=" + newRate);
            parts.add("aresample=44100");
            if (preserveTempoOnPitch) {
                s = s / p;
            }
        }
        // Rantai atempo agar selalu berada di rentang legal FFmpeg [0.5 .. 2.0]
        double rem = Math.max(0.25, Math.min(4.0, s));
        while (rem > 2.0) {
            parts.add("atempo=2.0");
            rem /= 2.0;
        }
        while (rem < 0.5) {
            parts.add("atempo=0.5");
            rem /= 0.5;
        }
        if (Math.abs(rem - 1.0) >= 0.01) {
            parts.add(String.format(Locale.US, "atempo=%.3f", rem));
        }
        return String.join(",", parts);
    }

    public static void insertSilence(
            Context ctx, Uri srcUri, File workDir, File outFile,
            long atMs, double silenceSec, long totalDurMs, String outFmt, int bitrateKbps
    ) throws Exception {
        File inFile = materializeRawCopyIfContent(ctx, srcUri, workDir, "ins_in");
        boolean cleanTmp = !"file".equals(srcUri.getScheme());
        try {
            String fc;
            if (atMs <= 100L) {
                fc = String.format(Locale.US,
                        "aevalsrc=0:channel_layout=stereo:sample_rate=44100:d=%.2f[sil];[0:a]aresample=44100:async=1,aformat=sample_fmts=fltp:channel_layouts=stereo[a0];[sil][a0]concat=n=2:v=0:a=1[outa]",
                        silenceSec);
            } else if (atMs + 100L >= totalDurMs) {
                fc = String.format(Locale.US,
                        "[0:a]aresample=44100:async=1,aformat=sample_fmts=fltp:channel_layouts=stereo[a0];aevalsrc=0:channel_layout=stereo:sample_rate=44100:d=%.2f[sil];[a0][sil]concat=n=2:v=0:a=1[outa]",
                        silenceSec);
            } else {
                fc = String.format(Locale.US,
                        "[0:a]aresample=44100:async=1,aformat=sample_fmts=fltp:channel_layouts=stereo,atrim=0:%s,asetpts=PTS-STARTPTS[a0];"
                                + "aevalsrc=0:channel_layout=stereo:sample_rate=44100:d=%.2f[sil];"
                                + "[0:a]aresample=44100:async=1,aformat=sample_fmts=fltp:channel_layouts=stereo,atrim=%s,asetpts=PTS-STARTPTS[a1];"
                                + "[a0][sil][a1]concat=n=3:v=0:a=1[outa]",
                        formatSecForFfmpeg(atMs), silenceSec, formatSecForFfmpeg(atMs));
            }
            String cmd = "-y -i " + q(inFile) + " -filter_complex \"" + fc + "\" -map \"[outa]\" "
                    + codecArgsForOutput(outFmt, bitrateKbps) + " " + q(outFile);
            runFfmpegOrThrow(cmd);
        } finally {
            if (cleanTmp) { try { inFile.delete(); } catch (Throwable ignored) {} }
        }
    }

    public static void writeWavHeader(RandomAccessFile raf, int sampleRate, int channels, long dataBytes) throws IOException {
        raf.seek(0L);
        int byteRate = sampleRate * channels * 2;
        short blockAlign = (short) (channels * 2);
        ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes());
        bb.putInt((int) (36L + dataBytes));
        bb.put("WAVE".getBytes());
        bb.put("fmt ".getBytes());
        bb.putInt(16);
        bb.putShort((short) 1);
        bb.putShort((short) channels);
        bb.putInt(sampleRate);
        bb.putInt(byteRate);
        bb.putShort(blockAlign);
        bb.putShort((short) 16);
        bb.put("data".getBytes());
        bb.putInt((int) dataBytes);
        raf.write(bb.array());
    }

    public static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst, false))) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    public static Uri prepareShareUri(Context ctx, File file) throws IOException {
        File dir = new File(ctx.getCacheDir(), "shared_exports");
        if (!dir.exists()) dir.mkdirs();
        File dst = new File(dir, file.getName());
        if (!file.getAbsolutePath().equals(dst.getAbsolutePath())) {
            copyFile(file, dst);
        }
        return Uri.parse("content://" + ExportFileProvider.AUTHORITY + "/" + Uri.encode(dst.getName()));
    }

    /**
     * Mengubah Berkas Audio menjadi Video MP4 (Audio ke Video) lengkap dengan Foto Cover / Sampul
     * (dengan latar blur estetis atau layar penuh) maupun Gelombang Spektrum Suara (Waveform Visualizer).
     */
    public static void renderAudioToVideoMp4(
            Context ctx, Uri audioUri, Uri coverImageUri, File workDir, File outMp4,
            String aspectRatio, String visualMode, int audioBitrateKbps
    ) throws Exception {
        File inAudio = materializeRawCopyIfContent(ctx, audioUri, workDir, "a2v_aud");
        boolean cleanAud = !"file".equals(audioUri.getScheme());
        File inCover = null;
        boolean cleanCov = false;
        try {
            int outW = 1280, outH = 720;
            if ("9:16".equals(aspectRatio)) { outW = 720; outH = 1280; }
            else if ("1:1".equals(aspectRatio)) { outW = 1080; outH = 1080; }
            else if ("4:5".equals(aspectRatio)) { outW = 864; outH = 1080; }

            int abr = Math.max(64, Math.min(320, audioBitrateKbps));

            if (coverImageUri != null) {
                inCover = materializeRawCopyIfContent(ctx, coverImageUri, workDir, "a2v_cov");
                cleanCov = !"file".equals(coverImageUri.getScheme());
                String vf;
                if ("cover_wave".equals(visualMode)) {
                    int waveH = Math.max(120, outH / 5);
                    String fc = String.format(Locale.US,
                            "[0:v]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,boxblur=18:2[bg];"
                                    + "[0:v]scale=%d:%d:force_original_aspect_ratio=decrease[fg];"
                                    + "[bg][fg]overlay=(W-w)/2:(H-h)/2[base];"
                                    + "[1:a]showwaves=s=%dx%d:mode=cline:rate=8:colors=0x38BDF8[wv];"
                                    + "[base][wv]overlay=0:H-%d,format=yuv420p[outv]",
                            outW, outH, outW, outH, outW, outH, outW, waveH, waveH + 24);
                    String cmd = "-y -loop 1 -r 8 -i " + q(inCover) + " -i " + q(inAudio)
                            + " -filter_complex \"" + fc + "\" -map \"[outv]\" -map 1:a "
                            + "-c:v libx264 -preset fast -crf 27 -g 40 -c:a aac -profile:a aac_low -b:a " + abr + "k -shortest -movflags +faststart "
                            + q(outMp4);
                    runFfmpegOrThrow(cmd);
                    return;
                } else if ("cover_blur".equals(visualMode)) {
                    vf = String.format(Locale.US,
                            "[0:v]scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d,boxblur=20:2[bg];"
                                    + "[0:v]scale=%d:%d:force_original_aspect_ratio=decrease[fg];"
                                    + "[bg][fg]overlay=(W-w)/2:(H-h)/2,setsar=1,format=yuv420p[outv]",
                            outW, outH, outW, outH, outW, outH);
                    String cmd = "-y -loop 1 -r 5 -i " + q(inCover) + " -i " + q(inAudio)
                            + " -filter_complex \"" + vf + "\" -map \"[outv]\" -map 1:a "
                            + "-c:v libx264 -preset fast -tune stillimage -crf 27 -g 25 -c:a aac -profile:a aac_low -b:a " + abr + "k -shortest -movflags +faststart "
                            + q(outMp4);
                    runFfmpegOrThrow(cmd);
                    return;
                } else {
                    vf = String.format(Locale.US,
                            "scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2:black,setsar=1,format=yuv420p",
                            outW, outH, outW, outH);
                    String cmd = "-y -loop 1 -r 5 -i " + q(inCover) + " -i " + q(inAudio)
                            + " -vf \"" + vf + "\" -c:v libx264 -preset fast -tune stillimage -crf 27 -g 25 "
                            + "-c:a aac -profile:a aac_low -b:a " + abr + "k -shortest -movflags +faststart " + q(outMp4);
                    runFfmpegOrThrow(cmd);
                    return;
                }
            }

            // Tanpa gambar cover: gunakan Gelombang Suara (Waveform) atau Latar Warna Studio
            if ("solid_dark".equals(visualMode) || "solid_navy".equals(visualMode)) {
                String hex = "solid_navy".equals(visualMode) ? "0x0F172A" : "0x111111";
                String cmd = String.format(Locale.US,
                        "-y -f lavfi -i color=c=%s:s=%dx%d:r=5 -i %s -c:v libx264 -preset fast -tune stillimage -crf 28 -g 25 -pix_fmt yuv420p -c:a aac -profile:a aac_low -b:a %dk -shortest -movflags +faststart %s",
                        hex, outW, outH, q(inAudio), abr, q(outMp4));
                runFfmpegOrThrow(cmd);
            } else {
                String fc = String.format(Locale.US,
                        "[0:a]showwaves=s=%dx%d:mode=cline:rate=8:colors=0x38BDF8|0xA855F7,format=yuv420p[outv]",
                        outW, outH);
                String cmd = "-y -i " + q(inAudio)
                        + " -filter_complex \"" + fc + "\" -map \"[outv]\" -map 0:a "
                        + "-c:v libx264 -preset fast -crf 28 -g 40 -c:a aac -profile:a aac_low -b:a " + Math.min(abr, 128) + "k -shortest -movflags +faststart "
                        + q(outMp4);
                runFfmpegOrThrow(cmd);
            }
        } finally {
            if (cleanAud) { try { inAudio.delete(); } catch (Throwable ignored) {} }
            if (cleanCov && inCover != null) { try { inCover.delete(); } catch (Throwable ignored) {} }
        }
    }

    /**
     * Memasang Foto Cover / Sampul Album (ID3 Attached Picture) dan Tag Metadata (Judul, Artis, Album)
     * ke dalam berkas MP3 atau M4A.
     */
    public static void embedAudioCoverArtAndTags(
            Context ctx, Uri audioUri, Uri coverImageUri, File workDir, File outFile,
            String title, String artist, String album, int bitrateKbps
    ) throws Exception {
        File inAudio = materializeRawCopyIfContent(ctx, audioUri, workDir, "tag_aud");
        boolean cleanAud = !"file".equals(audioUri.getScheme());
        File inCover = null;
        boolean cleanCov = false;
        try {
            StringBuilder metaArgs = new StringBuilder();
            if (title != null && !title.trim().isEmpty()) {
                metaArgs.append("-metadata title=\"").append(title.trim().replace("\"", "'")).append("\" ");
            }
            if (artist != null && !artist.trim().isEmpty()) {
                metaArgs.append("-metadata artist=\"").append(artist.trim().replace("\"", "'")).append("\" ");
            }
            if (album != null && !album.trim().isEmpty()) {
                metaArgs.append("-metadata album=\"").append(album.trim().replace("\"", "'")).append("\" ");
            }

            int br = Math.max(64, Math.min(320, bitrateKbps));
            if (coverImageUri != null) {
                inCover = materializeRawCopyIfContent(ctx, coverImageUri, workDir, "tag_cov");
                cleanCov = !"file".equals(coverImageUri.getScheme());
                String cmd = "-y -i " + q(inAudio) + " -i " + q(inCover)
                        + " -map 0:a:0 -map 1:v:0 -c:a libmp3lame -b:a " + br + "k "
                        + "-c:v mjpeg -vf \"scale='min(800,iw)':'min(800,ih)':force_original_aspect_ratio=decrease\" "
                        + "-id3v2_version 3 -metadata:s:v title=\"Album cover\" -metadata:s:v comment=\"Cover (front)\" "
                        + "-disposition:v attached_pic " + metaArgs + q(outFile);
                runFfmpegOrThrow(cmd);
            } else {
                String cmd = "-y -i " + q(inAudio) + " -c:a libmp3lame -b:a " + br + "k -id3v2_version 3 "
                        + metaArgs + q(outFile);
                runFfmpegOrThrow(cmd);
            }
        } finally {
            if (cleanAud) { try { inAudio.delete(); } catch (Throwable ignored) {} }
            if (cleanCov && inCover != null) { try { inCover.delete(); } catch (Throwable ignored) {} }
        }
    }

    /**
     * Generator Nada Frekuensi (Sine Hz), Bunyi Beep Sensor, atau Derau Terapi Tidur (White/Pink/Brown Noise).
     */
    public static void generateToneOrNoise(
            File outFile, String generatorType, int freqHz, double durationSec, String outFmt, int bitrateKbps
    ) throws Exception {
        double dur = Math.max(1.0, Math.min(600.0, durationSec));
        int hz = Math.max(20, Math.min(16000, freqHz));
        String src;
        if ("white_noise".equals(generatorType)) {
            src = String.format(Locale.US, "anoisesrc=color=white:sample_rate=44100:amplitude=0.35:duration=%.2f", dur);
        } else if ("pink_noise".equals(generatorType)) {
            src = String.format(Locale.US, "anoisesrc=color=pink:sample_rate=44100:amplitude=0.45:duration=%.2f", dur);
        } else if ("brown_noise".equals(generatorType)) {
            src = String.format(Locale.US, "anoisesrc=color=brown:sample_rate=44100:amplitude=0.55:duration=%.2f", dur);
        } else {
            src = String.format(Locale.US, "sine=frequency=%d:sample_rate=44100:duration=%.2f", hz, dur);
        }
        String cmd = "-y -f lavfi -i \"" + src + "\" -filter:a \"pan=stereo|c0=c0|c1=c0\" "
                + codecArgsForOutput(outFmt, bitrateKbps) + " " + q(outFile);
        runFfmpegOrThrow(cmd);
    }
}
