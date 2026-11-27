package com.annas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Konverter Audio (M4A/MP3/OPUS/WAV + Musik Latar opsional) -> Video MP4 (H.264 libx264 + AAC-LC)
 * berbasis FFmpeg native untuk Status WhatsApp & Video Utuh.
 * Dirancang hemat ukuran (~1 MB/menit) dengan kualitas suara tetap jernih seperti aslinya
 * dan timestamp deterministik mulai 00:00.
 */
public final class AudioToVideoConverter {

    public interface ProgressListener {
        void onProgress(int partIndex, int totalParts, int percentInPart, String status);
        boolean isCancelled();
    }

    private AudioToVideoConverter() {}

    public static double getWavDurationSeconds(File audioFile) {
        return getAudioDurationSeconds(audioFile);
    }

    /** Mendeteksi durasi berkas audio apa saja (M4A, MP3, OPUS, WAV) dalam detik. */
    public static double getAudioDurationSeconds(File audioFile) {
        if (audioFile == null || !audioFile.exists() || audioFile.length() <= 0) return 0;
        String name = audioFile.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".wav") && audioFile.length() > 44) {
            try (FileInputStream fis = new FileInputStream(audioFile)) {
                byte[] hdr = new byte[44];
                if (fis.read(hdr) == 44 && hdr[0] == 'R' && hdr[1] == 'I' && hdr[2] == 'F' && hdr[3] == 'F') {
                    int channels = (hdr[22] & 0xFF) | ((hdr[23] & 0xFF) << 8);
                    int sampleRate = (hdr[24] & 0xFF) | ((hdr[25] & 0xFF) << 8)
                            | ((hdr[26] & 0xFF) << 16) | ((hdr[27] & 0xFF) << 24);
                    int bits = (hdr[34] & 0xFF) | ((hdr[35] & 0xFF) << 8);
                    if (channels <= 0) channels = 1;
                    if (sampleRate <= 0) sampleRate = 24000;
                    if (bits <= 0) bits = 16;
                    long byteRate = (long) sampleRate * channels * (bits / 8);
                    long dataBytes = Math.max(0, audioFile.length() - 44L);
                    if (byteRate > 0) return (double) dataBytes / byteRate;
                }
            } catch (Exception ignored) {}
        }
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(audioFile.getAbsolutePath());
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null && !dur.isEmpty()) {
                return Long.parseLong(dur) / 1000.0;
            }
        } catch (Throwable ignored) {
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
        try {
            MediaInformationSession mis = FFprobeKit.getMediaInformation(audioFile.getAbsolutePath());
            MediaInformation mi = mis != null ? mis.getMediaInformation() : null;
            if (mi != null && mi.getDuration() != null) {
                return Double.parseDouble(mi.getDuration());
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    public static double getMediaDurationSeconds(Context ctx, Uri uri) {
        if (ctx == null || uri == null) return 0;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                mmr.setDataSource(uri.getPath());
            } else {
                mmr.setDataSource(ctx, uri);
            }
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null && !dur.isEmpty()) {
                return Long.parseLong(dur) / 1000.0;
            }
        } catch (Throwable ignored) {
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
        return 0;
    }

    public static String formatDuration(double seconds) {
        int total = Math.max(0, (int) Math.round(seconds));
        int m = total / 60;
        int s = total % 60;
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    /**
     * Mengubah file audio TTS (+ musik latar opsional) menjadi satu atau beberapa video MP4
     * hemat ukuran berdurasi masing-masing maksimal {@code splitSeconds} (kelipatan 30 detik).
     */
    public static List<File> convertWavToStatusVideos(
            Context ctx,
            File audioFile,
            File outputDir,
            String scriptPreviewText,
            int splitSeconds,
            Uri musicUri,
            int musicStartSec,
            float musicVolume,
            ProgressListener listener) throws Exception {

        if (audioFile == null || !audioFile.exists() || audioFile.length() <= 0) {
            throw new IllegalStateException("Berkas audio tidak ditemukan atau kosong.");
        }
        if (!outputDir.exists()) outputDir.mkdirs();

        double totalSecDouble = getAudioDurationSeconds(audioFile);
        if (totalSecDouble <= 0) totalSecDouble = 1.0;

        File workDir = new File(ctx.getCacheDir(), "wa_video_work");
        if (!workDir.exists()) workDir.mkdirs();

        File preparedAudio = audioFile;
        File tempMixedAudio = null;
        File tempMusicMat = null;
        File tempCardPng = null;
        boolean hasMusic = musicUri != null;

        try {
            if (hasMusic) {
                if (listener != null) {
                    listener.onProgress(1, 1, 8, "Menyelaraskan musik latar dengan suara TTS...");
                }
                tempMusicMat = AudioDspEngine.materializeRawCopyIfContent(ctx, musicUri, workDir, "a2v_bgm");
                tempMixedAudio = new File(workDir, "mixed_master_" + System.currentTimeMillis() + ".flac");
                prepareMixedMasterAudio(audioFile, tempMusicMat, tempMixedAudio, totalSecDouble, musicStartSec, musicVolume);
                preparedAudio = tempMixedAudio;
            }

            int totalSeconds = Math.max(1, (int) Math.ceil(totalSecDouble));
            boolean fullSingleVideo = splitSeconds <= 0;
            int chunkSec = fullSingleVideo ? totalSeconds : Math.max(30, splitSeconds);
            int totalParts = fullSingleVideo ? 1 : Math.max(1, (int) Math.ceil((double) totalSeconds / chunkSec));

            List<File> outFiles = new ArrayList<>();
            long baseTime = System.currentTimeMillis();
            tempCardPng = new File(workDir, "card_" + baseTime + ".png");

            for (int part = 0; part < totalParts; part++) {
                if (listener != null && listener.isCancelled()) {
                    throw new InterruptedException("Konversi dibatalkan.");
                }
                double startSec = part * (double) chunkSec;
                double partDurSec = fullSingleVideo
                        ? totalSecDouble
                        : Math.min((double) chunkSec, Math.max(0.5, totalSecDouble - startSec));
                if (partDurSec <= 0.1) break;

                String fileName = fullSingleVideo
                        ? ("GeminiTTS_Video_" + baseTime + ".mp4")
                        : String.format(Locale.US, "StatusWA_%02d_dari_%02d.mp4", part + 1, totalParts);
                File outFile = new File(outputDir, fileName);
                if (outFile.exists()) outFile.delete();

                int pctStart = Math.max(10, Math.min(90, 10 + (part * 80) / Math.max(1, totalParts)));
                if (listener != null) {
                    listener.onProgress(part + 1, totalParts, pctStart,
                            "Merender Video MP4 Hemat Bagian " + (part + 1) + "/" + totalParts + "...");
                }

                renderCardTemplatePng(tempCardPng, part + 1, totalParts, fullSingleVideo, partDurSec, scriptPreviewText);
                encodePartWithFfmpeg(tempCardPng, preparedAudio, outFile, startSec, partDurSec, hasMusic, listener);

                try { outFile.setLastModified(baseTime + (part * 1000L)); } catch (Throwable ignored) {}
                outFiles.add(outFile);

                if (listener != null) {
                    int pctDone = Math.min(99, 10 + ((part + 1) * 88) / Math.max(1, totalParts));
                    listener.onProgress(part + 1, totalParts, pctDone,
                            "Selesai Bagian " + (part + 1) + "/" + totalParts + " (" + Math.max(1, outFile.length() / 1024) + " KB)");
                }
            }
            return outFiles;
        } finally {
            if (tempMixedAudio != null) { try { tempMixedAudio.delete(); } catch (Throwable ignored) {} }
            if (tempMusicMat != null && !"file".equals(musicUri != null ? musicUri.getScheme() : "")) {
                try { tempMusicMat.delete(); } catch (Throwable ignored) {}
            }
            if (tempCardPng != null) { try { tempCardPng.delete(); } catch (Throwable ignored) {} }
        }
    }

    /** Mengekspor seluruh audio (+ musik opsional) menjadi 1 video MP4 utuh tanpa pemotongan. */
    public static File convertWavToFullVideo(
            Context ctx,
            File audioFile,
            File outputDir,
            String scriptPreviewText,
            Uri musicUri,
            int musicStartSec,
            float musicVolume,
            ProgressListener listener) throws Exception {
        List<File> res = convertWavToStatusVideos(ctx, audioFile, outputDir, scriptPreviewText,
                0, musicUri, musicStartSec, musicVolume, listener);
        if (res.isEmpty()) throw new IllegalStateException("Gagal membuat file video MP4.");
        return res.get(0);
    }

    private static void prepareMixedMasterAudio(
            File ttsFile,
            File musicFile,
            File outFlac,
            double ttsDurSec,
            int musicStartSec,
            float musicVolume
    ) throws Exception {
        double fadeDur = Math.min(2.0, Math.max(0.2, ttsDurSec * 0.25));
        double fadeStart = Math.max(0.0, ttsDurSec - fadeDur);
        float vol = Math.max(0.02f, Math.min(1.0f, musicVolume));

        // Seek musik dari musicStartSec, loop tak terbatas agar pasti cukup sepanjang durasi TTS, lalu potong + fade-out + auto-ducking halus
        String ssArg = musicStartSec > 0 ? ("-ss " + musicStartSec + " ") : "";
        String fc = String.format(Locale.US,
                "[0:a]aresample=44100:async=1,aformat=sample_fmts=fltp:channel_layouts=stereo,highpass=f=55[v0];"
                        + "[1:a]aresample=44100:async=1,aformat=sample_fmts=fltp:channel_layouts=stereo,"
                        + "atrim=0:%.3f,asetpts=PTS-STARTPTS,volume=%.2f,"
                        + "afade=t=out:st=%.3f:d=%.3f[bg0];"
                        + "[v0]asplit=2[vmain][vsc];"
                        + "[bg0][vsc]sidechaincompress=threshold=0.03:ratio=4:attack=40:release=350[bgduck];"
                        + "[vmain][bgduck]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.95[outa]",
                ttsDurSec, vol, fadeStart, fadeDur);

        String cmd = "-y -i " + AudioDspEngine.q(ttsFile) + " "
                + "-stream_loop -1 " + ssArg + "-i " + AudioDspEngine.q(musicFile) + " "
                + "-filter_complex \"" + fc + "\" -map \"[outa]\" -t " + String.format(Locale.US, "%.3f", ttsDurSec) + " "
                + "-c:a flac " + AudioDspEngine.q(outFlac);
        AudioDspEngine.runFfmpegOrThrow(cmd);
    }

    private static void encodePartWithFfmpeg(
            File cardPng,
            File audioFile,
            File outMp4,
            double startSec,
            double partDurSec,
            boolean hasMusic,
            ProgressListener listener
    ) throws Exception {
        if (listener != null && listener.isCancelled()) {
            throw new InterruptedException("Konversi video dibatalkan.");
        }

        // Latar kartu teks statis + gelombang suara dinamis (560x110 @ 6 FPS): sangat hemat bitrate namun teks & suara tetap jernih!
        String audioFilter = hasMusic
                ? "[1:a]asplit=2[aw][ao]"
                : "[1:a]highpass=f=55,alimiter=limit=0.95,asplit=2[aw][ao]";
        String fc = audioFilter + ";"
                + "[aw]showwaves=s=560x110:mode=cline:rate=6:colors=0x38BDF8|0x60A5FA[wv];"
                + "[0:v][wv]overlay=80:880,format=yuv420p[outv]";

        String audioArgs = hasMusic
                ? "-c:a aac -profile:a aac_low -b:a 96k -ar 44100 -ac 2"
                : "-c:a aac -profile:a aac_low -b:a 64k -ar 24000 -ac 1";

        String ssArg = startSec > 0.01 ? String.format(Locale.US, "-ss %.3f ", startSec) : "";
        String tArg = String.format(Locale.US, "-t %.3f ", partDurSec);

        String cmd = "-y -loop 1 -r 6 -i " + AudioDspEngine.q(cardPng) + " "
                + ssArg + tArg + "-i " + AudioDspEngine.q(audioFile) + " "
                + "-filter_complex \"" + fc + "\" -map \"[outv]\" -map \"[ao]\" "
                + "-c:v libx264 -preset fast -tune stillimage -crf 28 -maxrate 180k -bufsize 360k -g 30 "
                + audioArgs + " -shortest -movflags +faststart " + AudioDspEngine.q(outMp4);

        FFmpegSession session = FFmpegKit.execute(cmd);
        if (!ReturnCode.isSuccess(session.getReturnCode())) {
            String logs = session.getAllLogsAsString();
            String tail = logs == null ? "FFmpeg error" : logs.substring(Math.max(0, logs.length() - 400));
            throw new IllegalStateException("Gagal merender video MP4: " + tail);
        }
    }

    private static void renderCardTemplatePng(
            File outPng,
            int partNum,
            int totalParts,
            boolean fullSingleVideo,
            double partDurSec,
            String scriptPreviewText
    ) throws Exception {
        final int w = 720;
        final int h = 1280;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            Canvas c = new Canvas(bmp);

            Paint bgPaint = new Paint();
            bgPaint.setShader(new LinearGradient(0, 0, w, h,
                    Color.parseColor("#0F172A"), Color.parseColor("#1E1B4B"), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, bgPaint);

            Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            cardPaint.setColor(Color.parseColor("#CC1E293B"));
            c.drawRoundRect(new RectF(44, 170, w - 44, h - 170), 32, 32, cardPaint);

            Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            badgePaint.setColor(Color.parseColor("#2563EB"));
            c.drawRoundRect(new RectF(80, 215, 460, 275), 24, 24, badgePaint);

            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.WHITE);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            textPaint.setTextSize(25f);
            String badgeTxt = fullSingleVideo
                    ? "VIDEO MP4 UTUH • GEMINI TTS"
                    : ("STATUS WA • BAGIAN " + partNum + "/" + totalParts);
            c.drawText(badgeTxt, 102, 255, textPaint);

            textPaint.setTextSize(32f);
            textPaint.setColor(Color.parseColor("#38BDF8"));
            c.drawText("Keyboard AI by Anas • Gemini TTS", 80, 338, textPaint);

            String cleanSnippet = (scriptPreviewText == null ? "" : scriptPreviewText)
                    .replaceAll("\\[[^\\]]{1,40}\\]", "")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (cleanSnippet.length() > 380) cleanSnippet = cleanSnippet.substring(0, 377) + "...";
            if (cleanSnippet.isEmpty()) cleanSnippet = "Audio Gemini Text-to-Speech • Keyboard AI by Anas";

            TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            bodyPaint.setColor(Color.parseColor("#E2E8F0"));
            bodyPaint.setTextSize(34f);

            c.save();
            c.translate(80, 385);
            StaticLayout sl = StaticLayout.Builder.obtain(cleanSnippet, 0, cleanSnippet.length(), bodyPaint, w - 160)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(6f, 1.15f)
                    .setMaxLines(10)
                    .build();
            sl.draw(c);
            c.restore();

            // Panel gelap presisi untuk area waveform dinamis FFmpeg (80, 880, 640, 990)
            Paint waveBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            waveBoxPaint.setColor(Color.parseColor("#000000"));
            c.drawRoundRect(new RectF(72, 872, 648, 998), 18, 18, waveBoxPaint);

            Paint waveBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
            waveBorder.setStyle(Paint.Style.STROKE);
            waveBorder.setStrokeWidth(2.5f);
            waveBorder.setColor(Color.parseColor("#334155"));
            c.drawRoundRect(new RectF(72, 872, 648, 998), 18, 18, waveBorder);

            textPaint.setTextSize(26f);
            textPaint.setColor(Color.parseColor("#CBD5E1"));
            String durTxt = "Durasi Klip: " + formatDuration(partDurSec)
                    + (fullSingleVideo ? "" : ("  •  Bagian " + partNum + " dari " + totalParts));
            c.drawText(durTxt, 80, 1055, textPaint);

            try (FileOutputStream fos = new FileOutputStream(outPng, false)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 95, fos);
            }
        } finally {
            bmp.recycle();
        }
    }
}
