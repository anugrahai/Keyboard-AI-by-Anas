package com.annas;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Studio Editor Video Pro CapCut-Style (FFmpeg 8.1 Full GPL Native C/NEON).
 * - Ketika dibuka langsung menyuguhkan 36 fitur berbeda fungsi (tanpa memaksa pilih video di awal).
 * - 1 Fitur = 1 Halaman Khusus Murni (tidak mencampur tombol fitur lain di halaman yang sama).
 * - Khusus lintas media di Video Editor hanya ada "Video ke Audio (Ekstrak MP3/M4A/WAV/FLAC)".
 * - Pemutar simpel (Mundur, Putar/Jeda, Maju) + Potong sambil Play dengan Undo/Redo bertingkat
 *   (langsung memutar rentang titik Awal-Akhir lewat tombol Putar utama di atas tanpa tombol pratinjau terpisah).
 * - Studio Efek Suara Film & Backsound Sambil Putar (tempel Kaca Pecah, Tabrakan Mobil, Ledakan, Pukulan,
 *   Pedang, Horor, Petir, atau Backsound Musik tepat saat adegan berlangsung dan langsung ikut berbunyi
 *   saat video di-Play + Undo/Redo).
 * - Terintegrasi penuh dengan Studio Gudang & Laboratorium Audio (Backsound 1-2 menit & 32 Efek Film).
 */
public class VideoEditorStudioActivity extends Activity {

    public static final String EXTRA_VIDEO_URI = "extra_video_uri";

    private static final int FEATURE_HUB = 0;

    private static final int REQ_PICK_VID_FM = 9401;
    private static final int REQ_PICK_VID_SYS = 9402;
    private static final int REQ_PICK_MERGE_FM = 9403;
    private static final int REQ_PICK_MERGE_SYS = 9404;
    private static final int REQ_PICK_AUD_FM = 9405;
    private static final int REQ_PICK_AUD_SYS = 9406;
    private static final int REQ_PICK_SLIDE_IMG = 9407;
    private static final int REQ_PICK_SLIDE_BGM = 9408;
    private static final int REQ_PICK_SRT = 9409;
    private static final int REQ_PICK_WATERMARK = 9410;
    private static final int REQ_PERM_MIC = 9411;
    private static final int REQ_PICK_COVER_IMG_FM = 9414;
    private static final int REQ_PICK_COVER_IMG_SYS = 9415;
    private static final int REQ_PICK_SPLIT_VID2_FM = 9416;
    private static final int REQ_PICK_SPLIT_VID2_SYS = 9417;
    private static final int REQ_PICK_LIVE_SFX_FM = 9418;
    private static final int REQ_PICK_LIVE_SFX_SYS = 9419;

    private static final int[] RES_VALUES = {0, 1920, 1280, 854, 640};
    private static final String[] RES_LABELS = {
            "Resolusi Asli Video (Sesuai Sumber)",
            "1080p Full HD (1920 px — Sangat Tajam)",
            "720p HD Standar (1280 px — Direkomendasikan)",
            "480p SD Hemat Kuota (854 px)",
            "360p Ringan untuk Kirim Cepat WhatsApp (640 px)"
    };

    private static final int[] FPS_VALUES = {24, 25, 30, 60};
    private static final String[] FPS_LABELS = {
            "24 FPS (Standar Sinema Film)",
            "25 FPS (Standar Siaran PAL)",
            "30 FPS (Standar Video HP & Media Sosial)",
            "60 FPS (Gerakan Sangat Halus)"
    };

    private static final int[] CRF_VALUES = {18, 21, 23, 26, 28, 32};
    private static final String[] CRF_LABELS = {
            "CRF 18 (Kualitas Master Sangat Tinggi)",
            "CRF 21 (Kualitas Tinggi Jernih)",
            "CRF 23 (Standar Seimbang Tajam & Hemat)",
            "CRF 26 (Kompresi Menengah Hemat Ruang)",
            "CRF 28 (Kompresi Ukuran Kecil Siap WhatsApp)",
            "CRF 32 (Kompresi Ekstrem Ukuran Sangat Kecil)"
    };

    private static final int[] AUDIO_BR_VALUES = {96, 128, 160, 192, 256, 320};
    private static final String[] AUDIO_BR_LABELS = {
            "Audio 96 kbps (Suara Bicara Hemat Ukuran)",
            "Audio 128 kbps (Standar Video & Streaming)",
            "Audio 160 kbps (Kualitas Jernih Menengah)",
            "Audio 192 kbps (Kualitas Tinggi Studio — Direkomendasikan)",
            "Audio 256 kbps (Kualitas Musik Premium)",
            "Audio 320 kbps (Kualitas Maksimal Audio Master)"
    };

    private SharedPreferences uiPrefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private File workDir;

    private int activeFeatureId = FEATURE_HUB;

    private int selectedResIdx = 2;     // 720p
    private int selectedFpsIdx = 2;     // 30 FPS
    private int selectedCrfIdx = 3;     // CRF 26 (Hemat Ruang & Tajam)
    private int selectedAudioBrIdx = 1; // 128 kbps (Jernih & Hemat)

    // Video utama aktif
    private Uri singleVideoUri;
    private VideoEditEngine.VideoMeta singleMeta = new VideoEditEngine.VideoMeta();
    private final VideoEditEngine.RenderOptions renderOpts = new VideoEditEngine.RenderOptions();

    private VideoView videoView;
    private TextView tvVideoInfo, tvPlayPos, tvCutRangeBadge, tvOverlaySummary;
    private SeekBar videoSeek;
    private Button btnPlayPause, btnUndoCut, btnRedoCut;
    private EditText etInTime, etOutTime;
    private boolean isPlaying = false;

    // State Pemotongan Sambil Play + Undo / Redo Bertingkat
    private static final class CutState {
        final long inMs;
        final long outMs;
        final boolean hasIn;
        final boolean hasOut;
        CutState(long inMs, long outMs, boolean hasIn, boolean hasOut) {
            this.inMs = inMs;
            this.outMs = outMs;
            this.hasIn = hasIn;
            this.hasOut = hasOut;
        }
    }
    private long activeInMs = 0L;
    private long activeOutMs = 0L;
    private boolean hasMarkedIn = false;
    private boolean hasMarkedOut = false;
    private final ArrayList<CutState> cutUndoStack = new ArrayList<>();
    private final ArrayList<CutState> cutRedoStack = new ArrayList<>();

    // State Studio Efek Film & Backsound Sambil Putar (Tab Centang + Live Play + Undo/Redo)
    private final ArrayList<DramaSoundPaletteHelper.CueItem> liveSfxCues = new ArrayList<>();
    private final ArrayList<ArrayList<DramaSoundPaletteHelper.CueItem>> sfxUndoStack = new ArrayList<>();
    private final ArrayList<ArrayList<DramaSoundPaletteHelper.CueItem>> sfxRedoStack = new ArrayList<>();
    private DramaSoundPaletteHelper.Controller paletteController;
    private final ArrayList<MediaPlayer> activeCuePlayers = new ArrayList<>();
    private long lastTickerPosMs = -1L;
    private float swipeStartX = 0f, swipeStartY = 0f;
    private boolean swipeTracking = false, swipeTriggered = false;

    // Gabung Banyak Video
    private static final class VidSlot {
        Uri uri;
        String title;
        long durationMs;
    }
    private final ArrayList<VidSlot> mergeVideos = new ArrayList<>();
    private LinearLayout mergeListContainer;

    // Multi-Audio / Backsound pada Video
    private final ArrayList<AudioDspEngine.MixTrackSpec> extraVideoAudios = new ArrayList<>();
    private LinearLayout vidAudioListContainer;
    private CheckBox cbKeepOrigAudio, cbVideoAutoDucking;
    private float origVideoAudioVol = 1.0f;

    // Slideshow Foto
    private static final class ImgSlot {
        Uri uri;
        String title;
    }
    private final ArrayList<ImgSlot> slidePhotos = new ArrayList<>();
    private LinearLayout slidePhotoContainer;
    private Uri slideBgmUri;
    private String slideBgmTitle = "";
    private TextView tvSlideBgm;
    private EditText etSlideSec;

    // Foto Cover & Split Screen state
    private Uri coverImageUri;
    private String coverImageName = "";
    private Uri splitVid2Uri;
    private VideoEditEngine.VideoMeta splitVid2Meta = new VideoEditEngine.VideoMeta();

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updatePlayheadUi();
            if (isPlaying) mainHandler.postDelayed(this, 120L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uiPrefs = OfficeUtils.mainPrefs(this);
        UiKit.applySystemBars(this, uiPrefs);

        if (!AuthManager.isPremiumActive(this)) {
            AuthManager.showPremiumRequiredDialog(this, "Studio Editor Video Pro", null);
            Toast.makeText(this, "Fitur Studio Editor Video Pro khusus Anggota Premium.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        selectedResIdx = Math.max(0, Math.min(RES_VALUES.length - 1, uiPrefs.getInt("ved_res_idx", 2)));
        selectedFpsIdx = Math.max(0, Math.min(FPS_VALUES.length - 1, uiPrefs.getInt("ved_fps_idx", 2)));
        selectedCrfIdx = Math.max(0, Math.min(CRF_VALUES.length - 1, uiPrefs.getInt("ved_crf_idx", 3)));
        selectedAudioBrIdx = Math.max(0, Math.min(AUDIO_BR_VALUES.length - 1, uiPrefs.getInt("ved_abr_idx", 1)));
        syncRenderOptionsWithExportPrefs();

        workDir = new File(getCacheDir(), "ffmpeg_video_work");
        if (!workDir.exists()) workDir.mkdirs();

        Uri initial = getIntent() != null ? getIntent().getData() : null;
        if (initial == null && getIntent() != null) {
            String extra = getIntent().getStringExtra(EXTRA_VIDEO_URI);
            if (extra != null && !extra.isEmpty()) initial = Uri.parse(extra);
        }
        if (initial != null) {
            setSingleVideoInstant(initial);
        }
        renderCurrentScreen();
    }

    private void syncRenderOptionsWithExportPrefs() {
        renderOpts.targetLongSide = RES_VALUES[selectedResIdx];
        renderOpts.fps = FPS_VALUES[selectedFpsIdx];
        renderOpts.crf = CRF_VALUES[selectedCrfIdx];
        renderOpts.audioBitrateKbps = AUDIO_BR_VALUES[selectedAudioBrIdx];
    }

    private void saveExportPrefs() {
        uiPrefs.edit()
                .putInt("ved_res_idx", selectedResIdx)
                .putInt("ved_fps_idx", selectedFpsIdx)
                .putInt("ved_crf_idx", selectedCrfIdx)
                .putInt("ved_abr_idx", selectedAudioBrIdx)
                .apply();
        syncRenderOptionsWithExportPrefs();
    }

    private String getExportQualitySummary() {
        String r = selectedResIdx == 0 ? "Resolusi Asli" : (RES_VALUES[selectedResIdx] == 1920 ? "1080p" : (RES_VALUES[selectedResIdx] == 1280 ? "720p" : (RES_VALUES[selectedResIdx] == 854 ? "480p" : "360p")));
        return "MP4 (" + r + " • " + FPS_VALUES[selectedFpsIdx] + " FPS • CRF " + CRF_VALUES[selectedCrfIdx] + " • Audio " + AUDIO_BR_VALUES[selectedAudioBrIdx] + " kbps)";
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (activeFeatureId != FEATURE_HUB && singleVideoUri != null && ev.getPointerCount() == 1) {
            int action = ev.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                boolean onSeekBar = false;
                if (videoSeek != null && videoSeek.getVisibility() == View.VISIBLE) {
                    int[] loc = new int[2];
                    videoSeek.getLocationOnScreen(loc);
                    float rx = ev.getRawX(), ry = ev.getRawY();
                    if (rx >= loc[0] && rx <= loc[0] + videoSeek.getWidth()
                            && ry >= loc[1] - 24 && ry <= loc[1] + videoSeek.getHeight() + 24) {
                        onSeekBar = true;
                    }
                }
                swipeStartX = ev.getX();
                swipeStartY = ev.getY();
                swipeTracking = !onSeekBar;
                swipeTriggered = false;
            } else if (action == android.view.MotionEvent.ACTION_MOVE && swipeTracking && !swipeTriggered) {
                float dx = ev.getX() - swipeStartX;
                float dy = ev.getY() - swipeStartY;
                float thresh = UiKit.dp(this, 70);
                if (Math.abs(dx) > thresh && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                    swipeTriggered = true;
                    android.view.MotionEvent cancelEv = android.view.MotionEvent.obtain(ev);
                    cancelEv.setAction(android.view.MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancelEv);
                    cancelEv.recycle();
                    nudgePlayhead(dx < 0 ? -5000L : 5000L);
                    return true;
                }
            } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                swipeTracking = false;
                swipeTriggered = false;
            }
        } else {
            swipeTracking = false;
            swipeTriggered = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() {
        if (activeFeatureId == 2) {
            clearLiveSfxSession();
        } else {
            StudioGudangAudioManager.stopPreview();
        }
        if (activeFeatureId != FEATURE_HUB) {
            stopVideoPlayback();
            activeFeatureId = FEATURE_HUB;
            renderCurrentScreen();
            return;
        }
        super.onBackPressed();
    }

    private void openFeature(int fid) {
        if (activeFeatureId == 2 && fid != 2) {
            clearLiveSfxSession();
        } else {
            StudioGudangAudioManager.stopPreview();
        }
        stopVideoPlayback();
        activeFeatureId = fid;
        renderCurrentScreen();
    }

    private void clearLiveSfxSession() {
        StudioGudangAudioManager.stopPreview();
        DramaSoundPaletteHelper.stopAllLoopPlayers(liveSfxCues);
        liveSfxCues.clear();
        sfxUndoStack.clear();
        sfxRedoStack.clear();
        paletteController = null;
    }

    private void renderCurrentScreen() {
        stopVideoPlayback();
        if (activeFeatureId == FEATURE_HUB) {
            renderHubScreen();
            return;
        }
        switch (activeFeatureId) {
            case 1: renderVideoToAudioScreen(); break;
            case 2: renderLiveFilmSfxStudioScreen(); break;
            case 3: renderVideoCoverScreen(); break;
            case 4: renderDedicatedCompressResolutionScreen(); break;
            case 5: renderDedicatedGifScreen(); break;
            case 6: renderDedicatedCaptureFrameScreen(); break;
            case 7: renderDedicatedCutScreen(false, false); break; // Fast Trim
            case 8: renderDedicatedCutScreen(false, true); break;  // Accurate Trim
            case 9: renderDedicatedCutScreen(true, false); break;  // Cut Tengah
            case 10: renderDedicatedSplit2Screen(); break;
            case 11: renderDedicatedEqualSplitScreen(); break;
            case 12: renderDedicatedStatusWaScreen(); break;       // Khusus Status WA saja
            case 13: renderMergeScreen(); break;
            case 14: renderAudioMixScreen(); break;
            case 15: renderDedicatedDubbingScreen(); break;
            case 25: renderDedicatedSlideshowScreen(); break;
            case 26: renderSplitScreenCollageScreen(); break;
            default:
                renderDedicatedSingleVideoToolScreen(activeFeatureId);
                break;
        }
    }

    // ==================== LAYAR UTAMA: 36 FITUR + STUDIO GUDANG DI PALING BAWAH ====================

    private void renderHubScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 14);
        root.setPadding(p, p, p, p);

        TextView head = new TextView(this);
        head.setText("Studio Editor Video Pro — Pilih Fitur yang Ingin Digunakan");
        head.setTextSize(19);
        head.setTypeface(null, Typeface.BOLD);
        head.setTextColor(UiKit.textPrimary(uiPrefs));
        root.addView(head);

        TextView sub = new TextView(this);
        sub.setText("Setiap fitur di bawah memiliki halaman khusus tersendiri dan tidak tercampur dengan fitur lain. Pemotongan video dan pemasangan Efek Suara Film (Tabrakan, Kaca Pecah, Ledakan, dll.) dapat dilakukan langsung sambil memutar video dilengkapi tombol Undo dan Redo.");
        sub.setTextSize(13.5f);
        sub.setTextColor(UiKit.textMuted(uiPrefs));
        sub.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 10));
        root.addView(sub);

        // KATEGORI A: VIDEO KE AUDIO, STUDIO EFEK FILM SAMBIL PUTAR, PASANG COVER VIDEO, KOMPRES, GIF & FOTO FRAME
        LinearLayout catA = UiKit.makeCard(this, uiPrefs);
        catA.addView(UiKit.makeSectionLabel(this, "KATEGORI A: VIDEO KE AUDIO, EFEK FILM SAMBIL PUTAR, SAMPUL & KONVERSI", UiKit.ACCENT_AI));

        catA.addView(UiKit.makeMenuButton(this,
                "1. Video ke Audio (Ekstrak Suara Video ke MP3 / M4A / WAV / FLAC / OGG / OPUS)",
                "Halaman khusus mengambil jalur suara dari berkas video MP4 atau MKV menjadi berkas audio murni dengan pilihan bitrate 64 kbps sampai 320 kbps.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> openFeature(1)));

        catA.addView(UiKit.makeMenuButton(this,
                "2. Studio Efek Suara Film & Backsound Video Sambil Putar (Tabrakan, Kaca Pecah + Live Play)",
                "Putar film atau video Anda lalu klik tombol efek film (Kaca Pecah, Tabrakan Mobil, Ledakan, Pukulan, Pedang, Petir, Horor) atau Backsound Musik tepat saat adegan berlangsung. Langsung ikut berbunyi bareng video saat di-Play dan dilengkapi tombol Undo/Redo.",
                UiKit.ACCENT_WARM, uiPrefs, v -> openFeature(2)));

        catA.addView(UiKit.makeMenuButton(this,
                "3. Pasang atau Ganti Foto Cover Sampul Video (Thumbnail MP4 & Sampul Pembuka)",
                "Halaman khusus menyematkan gambar foto sampul (Cover Image) ke dalam berkas video MP4 sebagai thumbnail galeri maupun bingkai sampul pembuka video.",
                UiKit.ACCENT_AI, uiPrefs, v -> openFeature(3)));

        catA.addView(UiKit.makeMenuButton(this,
                "4. Ubah Resolusi, FPS, Bitrate Audio & Kompres Ukuran Video MP4",
                "Halaman khusus mengubah resolusi video (1080p, 720p, 480p, 360p), frame rate (24/25/30/60 FPS), kompresi CRF hemat WhatsApp, dan bitrate audio (96-320 kbps).",
                UiKit.ACCENT_KB, uiPrefs, v -> openFeature(4)));

        catA.addView(UiKit.makeMenuButton(this,
                "5. Ubah Rentang Video Menjadi Animasi GIF Bergerak",
                "Halaman khusus mengonversi potongan adegan video menjadi gambar bergerak format GIF.",
                UiKit.ACCENT_KB_ALT, uiPrefs, v -> openFeature(5)));

        catA.addView(UiKit.makeMenuButton(this,
                "6. Ambil Foto Tangkap Layar (Capture Frame PNG) dari Detik Video",
                "Halaman khusus menyimpan gambar foto PNG tajam beresolusi penuh tepat dari detik adegan video.",
                UiKit.ACCENT_SYSTEM, uiPrefs, v -> openFeature(6)));
        root.addView(catA);

        // KATEGORI B: POTONG SAMBIL PLAY (UNDO/REDO), STATUS WHATSAPP, BELAH & GABUNG VIDEO
        LinearLayout catB = UiKit.makeCard(this, uiPrefs);
        catB.addView(UiKit.makeSectionLabel(this, "KATEGORI B: POTONG SAMBIL PLAY (UNDO/REDO), STATUS WA, BELAH & GABUNG", UiKit.ACCENT_OFFICE));

        catB.addView(UiKit.makeMenuButton(this,
                "7. Potong Video Cepat Sambil Putar + Undo / Redo (Fast Trim Tanpa Render Ulang)",
                "Halaman khusus pemotongan fleksibel sambil Play: klik Tandai Titik Awal (video lanjut berputar), klik Tandai Titik Akhir, lalu klik Putar di atas untuk langsung memutar hasil potongan. Salah potong tinggal klik Undo (1x batal Akhir, 2x batal Awal) atau Redo.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> openFeature(7)));

        catB.addView(UiKit.makeMenuButton(this,
                "8. Potong Video Presisi Frame Sambil Putar + Undo / Redo (Accurate Re-encode Trim)",
                "Halaman khusus memotong video secara akurat tepat pada milidetik yang ditandai sesuai pengaturan resolusi aktif.",
                UiKit.ACCENT_KB, uiPrefs, v -> openFeature(8)));

        catB.addView(UiKit.makeMenuButton(this,
                "9. Buang Bagian Tengah Video Sambil Putar + Undo / Redo (Cut Adegan Tengah)",
                "Halaman khusus menandai dan menghapus adegan yang salah di bagian tengah video lalu menyambung bagian depan dan belakang.",
                UiKit.ACCENT_DANGER, uiPrefs, v -> openFeature(9)));

        catB.addView(UiKit.makeMenuButton(this,
                "10. Belah Video Menjadi 2 Berkas MP4 Terpisah",
                "Halaman khusus memecah satu berkas video tepat pada detik pilihan menjadi Bagian 1 dan Bagian 2.",
                UiKit.ACCENT_WARM, uiPrefs, v -> openFeature(10)));

        catB.addView(UiKit.makeMenuButton(this,
                "11. Bagi Video Menjadi Banyak Bagian Sama Rata (Split 2 s/d 30 Bagian)",
                "Halaman khusus membagi video panjang menjadi beberapa berkas MP4 dengan durasi yang sama rata.",
                UiKit.ACCENT_KB, uiPrefs, v -> openFeature(11)));

        catB.addView(UiKit.makeMenuButton(this,
                "12. Pecah Video Otomatis Khusus untuk Status WhatsApp (Per 15 / 30 / 60 / 90 Detik)",
                "Halaman murni khusus Status WhatsApp (tanpa dicampur tombol potong lain). Pilih video, pilih durasi Status WA, langsung pecah otomatis menjadi Part 1, Part 2, dan seterusnya.",
                UiKit.ACCENT_KB_ALT, uiPrefs, v -> openFeature(12)));

        catB.addView(UiKit.makeMenuButton(this,
                "13. Gabung Banyak Video Berurutan (Video 1 + Video 2 + Video 3 dan Seterusnya)",
                "Halaman khusus menyambung beberapa klip video sekaligus menjadi satu berkas MP4 utuh.",
                UiKit.ACCENT_AI, uiPrefs, v -> openFeature(13)));
        root.addView(catB);

        // KATEGORI C: BACKSOUND MUSIK VIDEO, DUBBING & PERJERNIH SUARA VIDEO
        LinearLayout catC = UiKit.makeCard(this, uiPrefs);
        catC.addView(UiKit.makeSectionLabel(this, "KATEGORI C: BACKSOUND MUSIK VIDEO, DUBBING & PERJERNIH SUARA", UiKit.ACCENT_WARM));

        catC.addView(UiKit.makeMenuButton(this,
                "14. Ganti atau Campur Backsound Musik Latar Video (Koleksi Aplikasi / Memori HP + Auto-Ducking)",
                "Halaman khusus menambahkan Backsound Musik 1-2 menit dari aplikasi (Islami, Piano Santai, Jazz, Rock, Jedag-Jedug, Tegang, Sinematik — otomatis dipotong sesuai durasi video) atau musik sendiri dari HP.",
                UiKit.ACCENT_AI, uiPrefs, v -> openFeature(14)));

        catC.addView(UiKit.makeMenuButton(this,
                "15. Rekam Dubbing / Voiceover Suara Mikrofon Langsung ke Video",
                "Halaman khusus merekam suara narasi dari mikrofon HP dan langsung memasangnya ke dalam jalur suara video.",
                UiKit.ACCENT_WARM, uiPrefs, v -> openFeature(15)));

        catC.addView(UiKit.makeMenuButton(this,
                "16. Bisukan Suara Asli Video (Mute Audio Track Instan)",
                "Halaman khusus menghapus seluruh jalur suara dari berkas video tanpa merender ulang gambar.",
                UiKit.ACCENT_DANGER, uiPrefs, v -> openFeature(16)));

        catC.addView(UiKit.makeMenuButton(this,
                "17. Perkeras & Standarisasi Volume Suara Video (Loudnorm Video -14 LUFS)",
                "Halaman khusus menaikkan dan meratakan volume suara di dalam video yang terlalu pelan.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> openFeature(17)));

        catC.addView(UiKit.makeMenuButton(this,
                "18. Bersihkan Noise Angin & Desis pada Suara Video (FFT Denoise Video)",
                "Halaman khusus menghilangkan suara desis angin, AC, atau kipas langsung pada jalur audio video.",
                UiKit.ACCENT_KB, uiPrefs, v -> openFeature(18)));

        catC.addView(UiKit.makeMenuButton(this,
                "19. Perjelas Suara Bicara / Ceramah di Dalam Video (Studio Vocal Compressor)",
                "Halaman khusus memadatkan dan mengangkat kejelasan vokal pembicara di dalam video.",
                UiKit.ACCENT_KB_ALT, uiPrefs, v -> openFeature(19)));

        catC.addView(UiKit.makeMenuButton(this,
                "20. Tambahkan Gema Reverb Masjid Agung pada Suara Video",
                "Halaman khusus memberikan pantulan akustik Masjid Agung langsung pada suara video murottal, adzan, atau kajian.",
                UiKit.ACCENT_AI_ALT, uiPrefs, v -> openFeature(20)));
        root.addView(catC);

        // KATEGORI D: RASIO LAYAR, CROP, PUTAR, SLIDESHOW FOTO & KOLASE 2 LAYAR
        LinearLayout catD = UiKit.makeCard(this, uiPrefs);
        catD.addView(UiKit.makeSectionLabel(this, "KATEGORI D: RASIO LAYAR, CROP, PUTAR, SLIDESHOW FOTO & KOLASE", UiKit.ACCENT_KB_ALT));

        catD.addView(UiKit.makeMenuButton(this,
                "21. Ubah Rasio Layar Video (9:16 TikTok/Reels, 16:9 YouTube, 1:1, 4:5, 21:9 + Latar Blur)",
                "Halaman khusus mengubah ukuran kanvas video dengan latar belakang Blur Halus, Blur Pekat, Hitam, Putih, atau Biru Tua.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> openFeature(21)));

        catD.addView(UiKit.makeMenuButton(this,
                "22. Potong Pinggiran Layar Video (Crop Area Layar dalam Persen)",
                "Halaman khusus memangkas pinggiran kiri, kanan, atas, atau bawah layar video.",
                UiKit.ACCENT_KB, uiPrefs, v -> openFeature(22)));

        catD.addView(UiKit.makeMenuButton(this,
                "23. Putar Orientasi Arah Video (Rotate 90, 180, atau 270 Derajat)",
                "Halaman khusus memperbaiki posisi rekaman video yang miring atau terbalik.",
                UiKit.ACCENT_WARM, uiPrefs, v -> openFeature(23)));

        catD.addView(UiKit.makeMenuButton(this,
                "24. Balik Cermin Video Horizontal atau Vertikal (Flip Mirror)",
                "Halaman khusus mencerminkan tampilan video dari kiri ke kanan atau dari atas ke bawah.",
                UiKit.ACCENT_AI, uiPrefs, v -> openFeature(24)));

        catD.addView(UiKit.makeMenuButton(this,
                "25. Buat Video Slideshow MP4 dari Kumpulan Foto + Backsound Musik",
                "Halaman khusus membuat video dari foto-foto galeri dilengkapi Backsound Musik dari Aplikasi (Islami, Piano, Jazz, dll) atau dari HP.",
                UiKit.ACCENT_KB_ALT, uiPrefs, v -> openFeature(25)));

        catD.addView(UiKit.makeMenuButton(this,
                "26. Video Kolase 2 Layar (Split Screen Atas-Bawah atau Kiri-Kanan)",
                "Halaman khusus menggabungkan 2 video berbeda agar tampil bersamaan dalam satu layar.",
                UiKit.ACCENT_AI_ALT, uiPrefs, v -> openFeature(26)));
        root.addView(catD);

        // KATEGORI E: KECEPATAN, FILTER WARNA, EFEK VISUAL, TEKS, SUBTITLE AI & STUDIO GUDANG
        LinearLayout catE = UiKit.makeCard(this, uiPrefs);
        catE.addView(UiKit.makeSectionLabel(this, "KATEGORI E: KECEPATAN, FILTER WARNA, EFEK VISUAL, TEKS & STUDIO GUDANG", UiKit.ACCENT_KB));

        catE.addView(UiKit.makeMenuButton(this,
                "27. Ubah Kecepatan Video (Slow Motion 0,25x - 0,5x & Timelapse 1,5x - 4,0x)",
                "Halaman khusus melambatkan atau mempercepat gerakan video beserta suaranya.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> openFeature(27)));

        catE.addView(UiKit.makeMenuButton(this,
                "28. Putar Video Mundur dari Akhir ke Awal (Reverse Video & Audio)",
                "Halaman khusus membalikkan arah pemutaran gambar video beserta suaranya.",
                UiKit.ACCENT_WARM, uiPrefs, v -> openFeature(28)));

        catE.addView(UiKit.makeMenuButton(this,
                "29. Ulangi Video Berkali-kali (Video Looper 2x - 5x & Efek Boomerang Maju-Mundur)",
                "Halaman khusus menggandakan putaran video atau membuat efek Boomerang maju-mundur.",
                UiKit.ACCENT_AI, uiPrefs, v -> openFeature(29)));

        catE.addView(UiKit.makeMenuButton(this,
                "30. Filter Warna Sinematik (18 Preset Warna Siap Pakai)",
                "Halaman khusus menerapkan 18 gaya warna sinematik pada video.",
                UiKit.ACCENT_KB, uiPrefs, v -> openFeature(30)));

        catE.addView(UiKit.makeMenuButton(this,
                "31. Atur Kecerahan, Kontras & Saturasi Warna Video Manual",
                "Halaman khusus menyesuaikan terang, kontras, dan kepekatan warna video secara manual.",
                UiKit.ACCENT_KB_ALT, uiPrefs, v -> openFeature(31)));

        catE.addView(UiKit.makeMenuButton(this,
                "32. Pertajam Gambar Video (HD Sharpen) atau Efek Cahaya Halus (Soft Glow)",
                "Halaman khusus meningkatkan ketajaman detail tekstur video.",
                UiKit.ACCENT_SYSTEM, uiPrefs, v -> openFeature(32)));

        catE.addView(UiKit.makeMenuButton(this,
                "33. Efek Transisi Fade In & Fade Out Layar Hitam pada Video",
                "Halaman khusus membuat awal video muncul perlahan dari gelap dan akhir video meredup halus.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> openFeature(33)));

        catE.addView(UiKit.makeMenuButton(this,
                "34. Sensor Blur Area Tertentu pada Video (Privacy Blur Box)",
                "Halaman khusus mengaburkan area tengah, atas, atau bawah pada layar video.",
                UiKit.ACCENT_DANGER, uiPrefs, v -> openFeature(34)));

        catE.addView(UiKit.makeMenuButton(this,
                "35. Tambahkan Teks Judul & Pasang Logo Watermark PNG pada Video",
                "Halaman khusus menempelkan tulisan judul atau gambar logo PNG di pojok video.",
                UiKit.ACCENT_AI_ALT, uiPrefs, v -> openFeature(35)));

        catE.addView(UiKit.makeMenuButton(this,
                "36. Buat Subtitle Otomatis dengan AI Gemini & Bakar File Subtitle (.SRT) ke Video",
                "Halaman khusus mengenali ucapan video menjadi subtitle otomatis AI atau memuat berkas .SRT.",
                UiKit.ACCENT_AI, uiPrefs, v -> openFeature(36)));

        // TOMBOL PALING BAWAH SENDIRI: STUDIO GUDANG & LABORATORIUM BACKSOUND / EFEK FILM
        catE.addView(UiKit.makeMenuButton(this,
                "Studio Gudang & Laboratorium Backsound / Efek Drama Film (Kelola, Uji Suara & Hapus Audio)",
                "Buka gudang penyimpanan puluhan Backsound Musik (Islami, Piano Santai, Jazz, Rock, Jedag-Jedug, Tegang 1-2 menit) dan 32 Efek Suara Drama Film. Uji suara di Laboratorium atau hapus audio yang tidak diperlukan lalu klik Simpan.",
                UiKit.ACCENT_WARM, uiPrefs, v -> StudioGudangAudioManager.showStudioGudangMainDialog(this, uiPrefs, null)));
        root.addView(catE);

        sv.addView(root);
        setContentView(sv);
        OfficeUtils.announce(head, "Studio Editor Video Pro. Silakan pilih fitur yang diinginkan.");
    }

    // ==================== HEADER, PEMUTAR VIDEO SIMPEL (MUNDUR/PUTAR/MAJU) & POTONG UNDO/REDO ====================

    private LinearLayout buildSubHeaderAndBack(String toolTitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, UiKit.dp(this, 8));

        Button btnBack = UiKit.makeMenuButton(this,
                "Kembali ke Daftar Semua Fitur Studio Video",
                "Menutup halaman fitur ini dan kembali ke menu utama Studio Editor Video.",
                UiKit.surfaceAlt(uiPrefs), uiPrefs, v -> onBackPressed());
        btnBack.setTextColor(UiKit.textPrimary(uiPrefs));
        box.addView(btnBack);

        TextView tv = new TextView(this);
        tv.setText(toolTitle);
        tv.setTextSize(18);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(UiKit.textPrimary(uiPrefs));
        tv.setPadding(0, UiKit.dp(this, 4), 0, 0);
        box.addView(tv);
        return box;
    }

    private LinearLayout buildSimpleVideoPlayerAndCutCard(boolean includeCutUndoRedo) {
        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        card.addView(UiKit.makeSectionLabel(this, "A. PILIH BERKAS VIDEO & PEMUTAR UTAMA", UiKit.ACCENT_OFFICE));

        tvVideoInfo = new TextView(this);
        tvVideoInfo.setTextSize(13.5f);
        tvVideoInfo.setTextColor(UiKit.textPrimary(uiPrefs));
        card.addView(tvVideoInfo);

        LinearLayout pickRow = new LinearLayout(this);
        pickRow.setOrientation(LinearLayout.HORIZONTAL);
        pickRow.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6));
        pickRow.addView(OfficeUtils.makeRowButton(this, "Pilih Video (File Manager)", "Pilih berkas video dari File Manager",
                UiKit.ACCENT_KB, Color.WHITE, v -> OfficeUtils.launchFilePicker(this, REQ_PICK_VID_FM, "video", "Pilih Berkas Video")));
        pickRow.addView(OfficeUtils.makeRowButton(this, "Pilih Video (Galeri HP)", "Pilih berkas video dari Galeri HP",
                UiKit.ACCENT_SYSTEM, Color.WHITE, v -> launchSysPicker("video/*", REQ_PICK_VID_SYS)));
        card.addView(pickRow);

        FrameLayout vBox = new FrameLayout(this);
        vBox.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, UiKit.dp(this, 210));
        vBox.setLayoutParams(vlp);
        videoView = new VideoView(this);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        vBox.addView(videoView, flp);
        card.addView(vBox);

        tvPlayPos = new TextView(this);
        tvPlayPos.setTextSize(14);
        tvPlayPos.setTypeface(null, Typeface.BOLD);
        tvPlayPos.setTextColor(UiKit.textPrimary(uiPrefs));
        tvPlayPos.setPadding(0, UiKit.dp(this, 6), 0, 0);
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            tvPlayPos.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        }
        card.addView(tvPlayPos);

        videoSeek = new SeekBar(this);
        videoSeek.setMax(1000);
        videoSeek.setContentDescription("Penggeser posisi putar video");
        videoSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && videoView != null && singleMeta.durationMs > 0) {
                    long t = (singleMeta.durationMs * p) / 1000L;
                    try { videoView.seekTo((int) t); } catch (Throwable ignored) {}
                    updatePlayheadUi();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                OfficeUtils.announce(tvPlayPos, tvPlayPos.getText().toString());
            }
        });
        card.addView(videoSeek);

        // Pemutar Simpel Tanpa Tombol Mundur/Maju (Cukup Putar/Jeda + Geser Kiri Mundur / Geser Kanan Maju)
        int txt = UiKit.textPrimary(uiPrefs);
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 2));
        btnPlayPause = OfficeUtils.makeRowButton(this, "Putar",
                "Putar atau jeda video. Geser layar ke kiri untuk mundur 5 detik, geser layar ke kanan untuk maju 5 detik.",
                UiKit.ACCENT_OFFICE, Color.WHITE, v -> togglePlayPause());
        ctrl.addView(btnPlayPause);
        card.addView(ctrl);

        TextView swipeHint = new TextView(this);
        swipeHint.setText("Geser Kiri = Mundur 5 detik  •  Geser Kanan = Maju 5 detik");
        swipeHint.setTextSize(12);
        swipeHint.setTextColor(UiKit.textMuted(uiPrefs));
        swipeHint.setGravity(Gravity.CENTER);
        swipeHint.setPadding(0, 0, 0, UiKit.dp(this, 4));
        card.addView(swipeHint);

        if (includeCutUndoRedo) {
            card.addView(UiKit.makeSectionLabel(this, "B. TANDAI TITIK POTONG SAMBIL PUTAR + UNDO / REDO", UiKit.ACCENT_WARM));

            tvCutRangeBadge = new TextView(this);
            tvCutRangeBadge.setTextSize(13.5f);
            tvCutRangeBadge.setTypeface(null, Typeface.BOLD);
            tvCutRangeBadge.setTextColor(UiKit.ACCENT_KB_ALT);
            tvCutRangeBadge.setPadding(0, 0, 0, UiKit.dp(this, 6));
            card.addView(tvCutRangeBadge);

            LinearLayout markRow = new LinearLayout(this);
            markRow.setOrientation(LinearLayout.HORIZONTAL);
            markRow.addView(OfficeUtils.makeRowButton(this, "Tandai Titik Awal",
                    "Kunci detik video saat ini sebagai Titik Awal dan lanjut berputar terus dari titik ini",
                    UiKit.ACCENT_KB, Color.WHITE, v -> markInWhilePlaying()));
            markRow.addView(OfficeUtils.makeRowButton(this, "Tandai Titik Akhir",
                    "Kunci detik video saat ini sebagai Titik Akhir dan langsung putar hasil potongan dari Awal ke Akhir",
                    UiKit.ACCENT_KB_ALT, Color.WHITE, v -> markOutWhilePlaying()));
            card.addView(markRow);

            LinearLayout undoRow = new LinearLayout(this);
            undoRow.setOrientation(LinearLayout.HORIZONTAL);
            undoRow.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 6));
            btnUndoCut = OfficeUtils.makeRowButton(this, "Undo (Batalkan Langkah)",
                    "Undo sekali membatalkan Titik Akhir, Undo dua kali membatalkan Titik Awal",
                    UiKit.ACCENT_WARM, Color.WHITE, v -> performCutUndo());
            btnRedoCut = OfficeUtils.makeRowButton(this, "Redo (Kembalikan)",
                    "Mengembalikan titik potong yang baru saja dibatalkan",
                    UiKit.ACCENT_AI, Color.WHITE, v -> performCutRedo());
            undoRow.addView(btnUndoCut);
            undoRow.addView(btnRedoCut);
            card.addView(undoRow);

            TextView lblIn = new TextView(this); lblIn.setText("Atau Ketik Waktu Titik Awal (contoh: 10 atau 01:15):"); lblIn.setTextSize(13); lblIn.setTextColor(txt); card.addView(lblIn);
            etInTime = new EditText(this); etInTime.setHint("00:00.00"); UiKit.styleEditText(this, etInTime, uiPrefs); card.addView(etInTime);

            TextView lblOut = new TextView(this); lblOut.setText("Atau Ketik Waktu Titik Akhir (contoh: 45 atau 02:30):"); lblOut.setTextSize(13); lblOut.setTextColor(txt); lblOut.setPadding(0, UiKit.dp(this, 4), 0, 0); card.addView(lblOut);
            etOutTime = new EditText(this); etOutTime.setHint("00:30.00"); UiKit.styleEditText(this, etOutTime, uiPrefs); card.addView(etOutTime);

            card.addView(UiKit.makeMenuButton(this,
                    "Terapkan Angka Waktu yang Diketik di Kotak ke Pemutar",
                    "Mengunci angka detik Awal dan Akhir yang Anda ketik secara manual dan langsung memutarnya.",
                    UiKit.surfaceAlt(uiPrefs), uiPrefs, v -> applyTypedCutTimes()));
        }

        refreshSingleVideoUi();
        return card;
    }

    private void pushCutUndoSnapshot() {
        cutUndoStack.add(new CutState(activeInMs, activeOutMs, hasMarkedIn, hasMarkedOut));
        cutRedoStack.clear();
    }

    private void markInWhilePlaying() {
        if (singleVideoUri == null || videoView == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        pushCutUndoSnapshot();
        long pos = getPlayheadMs();
        activeInMs = pos;
        hasMarkedIn = true;
        if (hasMarkedOut && activeOutMs <= activeInMs) {
            hasMarkedOut = false;
            activeOutMs = singleMeta.durationMs;
        }
        syncCutUiFromState();
        try {
            videoView.seekTo((int) activeInMs);
            if (!isPlaying) {
                videoView.start();
                isPlaying = true;
                if (btnPlayPause != null) btnPlayPause.setText("Jeda");
                mainHandler.removeCallbacks(ticker);
                mainHandler.post(ticker);
            }
        } catch (Throwable ignored) {}
        String msg = "Titik Awal dikunci di " + AudioDspEngine.formatMsShort(activeInMs) + ". Video lanjut berputar, klik Tandai Titik Akhir saat sampai di ujung potongan.";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        OfficeUtils.announce(tvPlayPos, msg);
    }

    private void markOutWhilePlaying() {
        if (singleVideoUri == null || videoView == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        long pos = getPlayheadMs();
        if (pos <= activeInMs + 200L) {
            Toast.makeText(this, "Titik Akhir harus berada setelah Titik Awal (" + AudioDspEngine.formatMsShort(activeInMs) + ").", Toast.LENGTH_SHORT).show();
            return;
        }
        pushCutUndoSnapshot();
        activeOutMs = pos;
        hasMarkedOut = true;
        if (!hasMarkedIn) {
            hasMarkedIn = true;
            activeInMs = 0L;
        }
        syncCutUiFromState();
        try {
            videoView.seekTo((int) activeInMs);
            videoView.start();
            isPlaying = true;
            if (btnPlayPause != null) btnPlayPause.setText("Jeda");
            mainHandler.removeCallbacks(ticker);
            mainHandler.post(ticker);
        } catch (Throwable ignored) {}
        String msg = "Titik Akhir dikunci di " + AudioDspEngine.formatMsShort(activeOutMs)
                + ". Memutar hasil potongan dari " + AudioDspEngine.formatMsShort(activeInMs)
                + " sampai " + AudioDspEngine.formatMsShort(activeOutMs) + ".";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        OfficeUtils.announce(tvPlayPos, msg);
    }

    private void applyTypedCutTimes() {
        if (singleVideoUri == null || etInTime == null || etOutTime == null) return;
        long typedIn = AudioDspEngine.parseTimeToMs(etInTime.getText().toString(), singleMeta.durationMs);
        long typedOut = AudioDspEngine.parseTimeToMs(etOutTime.getText().toString(), singleMeta.durationMs);
        if (typedOut <= typedIn) typedOut = singleMeta.durationMs;
        pushCutUndoSnapshot();
        activeInMs = typedIn;
        activeOutMs = typedOut;
        hasMarkedIn = true;
        hasMarkedOut = (typedOut < singleMeta.durationMs);
        syncCutUiFromState();
        seekAbsolute(activeInMs);
        if (!isPlaying) togglePlayPause();
    }

    private void performCutUndo() {
        if (cutUndoStack.isEmpty()) {
            if (hasMarkedOut) {
                hasMarkedOut = false;
                activeOutMs = singleMeta.durationMs;
                syncCutUiFromState();
                Toast.makeText(this, "Undo: Titik Akhir dibatalkan. Titik Awal tetap di " + AudioDspEngine.formatMsShort(activeInMs), Toast.LENGTH_SHORT).show();
                return;
            } else if (hasMarkedIn) {
                hasMarkedIn = false;
                activeInMs = 0L;
                syncCutUiFromState();
                Toast.makeText(this, "Undo: Titik Awal dibatalkan. Kembali ke 00:00.", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "Tidak ada langkah potong lagi untuk di-Undo.", Toast.LENGTH_SHORT).show();
            return;
        }
        cutRedoStack.add(new CutState(activeInMs, activeOutMs, hasMarkedIn, hasMarkedOut));
        CutState prev = cutUndoStack.remove(cutUndoStack.size() - 1);
        activeInMs = prev.inMs;
        activeOutMs = prev.outMs;
        hasMarkedIn = prev.hasIn;
        hasMarkedOut = prev.hasOut;
        syncCutUiFromState();
        seekAbsolute(activeInMs);
        Toast.makeText(this, "Undo berhasil: Awal " + AudioDspEngine.formatMsShort(activeInMs), Toast.LENGTH_SHORT).show();
    }

    private void performCutRedo() {
        if (cutRedoStack.isEmpty()) {
            Toast.makeText(this, "Tidak ada langkah untuk di-Redo.", Toast.LENGTH_SHORT).show();
            return;
        }
        cutUndoStack.add(new CutState(activeInMs, activeOutMs, hasMarkedIn, hasMarkedOut));
        CutState next = cutRedoStack.remove(cutRedoStack.size() - 1);
        activeInMs = next.inMs;
        activeOutMs = next.outMs;
        hasMarkedIn = next.hasIn;
        hasMarkedOut = next.hasOut;
        syncCutUiFromState();
        seekAbsolute(activeInMs);
        Toast.makeText(this, "Redo berhasil.", Toast.LENGTH_SHORT).show();
    }

    private void syncCutUiFromState() {
        if (etInTime != null) etInTime.setText(AudioDspEngine.formatMs(activeInMs));
        if (etOutTime != null) etOutTime.setText(AudioDspEngine.formatMs(activeOutMs > activeInMs ? activeOutMs : singleMeta.durationMs));
        if (tvCutRangeBadge != null) {
            if (!hasMarkedIn && !hasMarkedOut) {
                tvCutRangeBadge.setText("Rentang Aktif: Seluruh Video (Belum ada tanda potong • Putar lalu klik Tandai Titik Awal)");
            } else if (hasMarkedIn && !hasMarkedOut) {
                tvCutRangeBadge.setText("Titik Awal Terkunci: " + AudioDspEngine.formatMs(activeInMs)
                        + " -> Sampai Akhir (Klik Tandai Titik Akhir untuk mengunci ujung potongan)");
            } else {
                long dur = Math.max(0L, activeOutMs - activeInMs);
                tvCutRangeBadge.setText("Potongan Terkunci: " + AudioDspEngine.formatMs(activeInMs)
                        + " s/d " + AudioDspEngine.formatMs(activeOutMs)
                        + " (Durasi Potongan: " + AudioDspEngine.formatMsShort(dur) + " • Tekan Putar di atas untuk menonton)");
            }
        }
        if (btnUndoCut != null) btnUndoCut.setText("Undo (" + cutUndoStack.size() + ")");
        if (btnRedoCut != null) btnRedoCut.setText("Redo (" + cutRedoStack.size() + ")");
    }

    private LinearLayout buildVideoExportQualityCard() {
        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        card.addView(UiKit.makeSectionLabel(this, "PENGATURAN RESOLUSI, FPS, KOMPRESI & BITRATE AUDIO", UiKit.ACCENT_KB_ALT));

        final TextView tvSum = new TextView(this);
        tvSum.setText("Setelan Render Aktif: " + getExportQualitySummary());
        tvSum.setTextSize(13.5f);
        tvSum.setTypeface(null, Typeface.BOLD);
        tvSum.setTextColor(UiKit.textPrimary(uiPrefs));
        tvSum.setPadding(0, 0, 0, UiKit.dp(this, 6));
        card.addView(tvSum);

        int txt = UiKit.textPrimary(uiPrefs);
        TextView l1 = new TextView(this); l1.setText("Pilih Resolusi Layar Video Hasil:"); l1.setTextSize(13); l1.setTextColor(txt); card.addView(l1);
        Spinner spRes = new Spinner(this);
        spRes.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, RES_LABELS));
        spRes.setSelection(selectedResIdx);
        card.addView(spRes);

        TextView l2 = new TextView(this); l2.setText("Pilih Kelancaran Frame Rate (FPS):"); l2.setTextSize(13); l2.setTextColor(txt); l2.setPadding(0, UiKit.dp(this, 6), 0, 0); card.addView(l2);
        Spinner spFps = new Spinner(this);
        spFps.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, FPS_LABELS));
        spFps.setSelection(selectedFpsIdx);
        card.addView(spFps);

        TextView l3 = new TextView(this); l3.setText("Pilih Tingkat Kualitas Gambar & Kompresi Ukuran (CRF):"); l3.setTextSize(13); l3.setTextColor(txt); l3.setPadding(0, UiKit.dp(this, 6), 0, 0); card.addView(l3);
        Spinner spCrf = new Spinner(this);
        spCrf.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, CRF_LABELS));
        spCrf.setSelection(selectedCrfIdx);
        card.addView(spCrf);

        TextView l4 = new TextView(this); l4.setText("Pilih Kualitas Bitrate Audio di Dalam Video (kbps):"); l4.setTextSize(13); l4.setTextColor(txt); l4.setPadding(0, UiKit.dp(this, 6), 0, 0); card.addView(l4);
        Spinner spAbr = new Spinner(this);
        spAbr.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, AUDIO_BR_LABELS));
        spAbr.setSelection(selectedAudioBrIdx);
        card.addView(spAbr);

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedResIdx = spRes.getSelectedItemPosition();
                selectedFpsIdx = spFps.getSelectedItemPosition();
                selectedCrfIdx = spCrf.getSelectedItemPosition();
                selectedAudioBrIdx = spAbr.getSelectedItemPosition();
                saveExportPrefs();
                tvSum.setText("Setelan Render Aktif: " + getExportQualitySummary());
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        spRes.setOnItemSelectedListener(listener);
        spFps.setOnItemSelectedListener(listener);
        spCrf.setOnItemSelectedListener(listener);
        spAbr.setOnItemSelectedListener(listener);
        return card;
    }

    private void setSingleVideoInstant(Uri uri) {
        singleVideoUri = uri;
        singleMeta = VideoEditEngine.inspectVideo(this, uri);
        activeInMs = 0L;
        activeOutMs = singleMeta.durationMs;
        hasMarkedIn = false;
        hasMarkedOut = false;
        cutUndoStack.clear();
        cutRedoStack.clear();
        if (tvVideoInfo != null) refreshSingleVideoUi();
    }

    private void refreshSingleVideoUi() {
        if (tvVideoInfo == null) return;
        if (singleVideoUri == null) {
            tvVideoInfo.setText("Belum ada berkas video dipilih. Tekan tombol Pilih Video di bawah untuk memuat video.");
            tvPlayPos.setText("Posisi Putar: 00:00.00 dari 00:00.00");
        } else {
            tvVideoInfo.setText(String.format(Locale.ROOT,
                    "Berkas Aktif: %s\nDurasi: %s • Resolusi: %dx%d • Ukuran: %s",
                    singleMeta.displayName,
                    AudioDspEngine.formatMs(singleMeta.durationMs),
                    singleMeta.width, singleMeta.height,
                    fmtSize(singleMeta.sizeBytes)));
            tvPlayPos.setText("Posisi Putar: 00:00.00 dari " + AudioDspEngine.formatMs(singleMeta.durationMs));
            if (videoView != null) {
                try {
                    videoView.setVideoURI(singleVideoUri);
                    videoView.setOnPreparedListener(mp -> {
                        try { videoView.seekTo((int) activeInMs); } catch (Throwable ignored) {}
                    });
                    videoView.setOnCompletionListener(mp -> {
                        isPlaying = false;
                        stopAllCuePlayers();
                        if (btnPlayPause != null) btnPlayPause.setText("Putar");
                        mainHandler.removeCallbacks(ticker);
                    });
                } catch (Throwable ignored) {}
            }
        }
        syncCutUiFromState();
    }

    // ==================== FITUR 2: STUDIO EFEK SUARA FILM & BACKSOUND VIDEO SAMBIL PUTAR ====================

    private void renderLiveFilmSfxStudioScreen() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(UiKit.bg(uiPrefs));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, UiKit.dp(this, 16));

        root.addView(buildSubHeaderAndBack("Studio Efek Film, Kartun & Backsound Video"));
        root.addView(MediaEditorCatalog.makeGuideCard(this, uiPrefs,
                "Panduan Singkat Efek & Backsound Video",
                "1. Pilih video/film lalu tekan Putar (geser kiri = mundur 5 detik, geser kanan = maju 5 detik).\n"
                        + "2. Ketuk 'Kategori' -> pilih Efek Singkat atau Backsound Musik.\n"
                        + "3. Untuk Efek: tekan Putar untuk tes, lalu centang pas di detik target!\n"
                        + "4. Untuk Backsound: centang lagu di detik target (otomatis jalan sampai akhir video), atau pakai Tandai Awal / Tandai Akhir di baris lagu itu.\n"
                        + "5. Setelah beres, ketuk tombol 'Mulai Konversi Video' yang selalu terkunci di pojok kanan bawah layar.",
                "Semua daftar di satu guliran halus; tombol konversi terkunci di kanan bawah layar."));

        root.addView(buildSimpleVideoPlayerAndCutCard(false));

        final TextView tvFooterInfo = new TextView(this);
        tvFooterInfo.setTextSize(13f);
        tvFooterInfo.setTypeface(null, Typeface.BOLD);
        tvFooterInfo.setTextColor(UiKit.textPrimary(uiPrefs));
        tvFooterInfo.setText("Terpasang: " + liveSfxCues.size() + " item");

        if (paletteController == null) {
            paletteController = new DramaSoundPaletteHelper.Controller(
                    this, uiPrefs, new DramaSoundPaletteHelper.Host() {
                @Override public long getPlayheadMs() { return VideoEditorStudioActivity.this.getPlayheadMs(); }
                @Override public long getTotalDurationMs() { return singleMeta.durationMs; }
                @Override public void playTransitionFromMs(long cueAtMs) { playTransitionPreviewAt(cueAtMs); }
                @Override public void stopPlaybackForPreviewSwitch() { if (isPlaying) togglePlayPause(); }
                @Override public void triggerOneShotCue(DramaSoundPaletteHelper.CueItem cue) { triggerOneCuePreview(cue); }
                @Override public void onPickAudioFromPhone() {
                    UiKit.dialog(VideoEditorStudioActivity.this, uiPrefs)
                            .setTitle("Pilih Audio dari Penyimpanan HP")
                            .setItems(new String[]{"Buka File Manager Aplikasi", "Buka Galeri / Pemilih Sistem Android"}, (d, w) -> {
                                if (w == 0) OfficeUtils.launchFilePicker(VideoEditorStudioActivity.this, REQ_PICK_LIVE_SFX_FM, "audio", "Pilih Efek / Backsound dari HP");
                                else launchSysPicker("audio/*", REQ_PICK_LIVE_SFX_SYS);
                            }).show();
                }
                @Override public void onPaletteAppliedCue(DramaSoundPaletteHelper.CueItem addedOrNull) {
                    if (addedOrNull != null) {
                        Toast.makeText(VideoEditorStudioActivity.this,
                                "Terpasang: " + addedOrNull.title + " pada " + AudioDspEngine.formatMsShort(addedOrNull.atMs),
                                Toast.LENGTH_SHORT).show();
                    }
                }
                @Override public void onCuesChanged(int totalCount) {
                    tvFooterInfo.setText("Terpasang: " + totalCount + " item");
                }
                @Override public void onUndoRequested() { performSfxUndo(); }
                @Override public void onRedoRequested() { performSfxRedo(); }
                @Override public void onResetAllRequested() {
                    if (!liveSfxCues.isEmpty()) {
                        pushSfxUndoSnapshot();
                        DramaSoundPaletteHelper.stopAllLoopPlayers(liveSfxCues);
                        liveSfxCues.clear();
                        refreshLiveSfxUiInPlace();
                    }
                }
                @Override public int getUndoCount() { return sfxUndoStack.size(); }
                @Override public int getRedoCount() { return sfxRedoStack.size(); }
            }, liveSfxCues, sfxUndoStack, sfxRedoStack);
        }
        paletteController.mountInto(root);

        sv.addView(root);
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Pinned Footer Bar di paling bawah layar — tombol Konversi dikunci di sebelah kanan bawah!
        LinearLayout footerBar = new LinearLayout(this);
        footerBar.setOrientation(LinearLayout.HORIZONTAL);
        footerBar.setGravity(Gravity.CENTER_VERTICAL);
        footerBar.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10), UiKit.dp(this, 12), UiKit.dp(this, 10));
        footerBar.setBackgroundColor(UiKit.surfaceAlt(uiPrefs));

        footerBar.addView(tvFooterInfo, new LinearLayout.LayoutParams(0, -2, 1f));

        Button btnConvertPinned = new Button(this);
        btnConvertPinned.setAllCaps(false);
        btnConvertPinned.setText("Mulai Konversi Video");
        btnConvertPinned.setContentDescription("Mulai konversi dan gabungkan semua efek suara dan backsound ke video MP4 sekarang");
        btnConvertPinned.setTextSize(13.5f);
        btnConvertPinned.setTypeface(null, Typeface.BOLD);
        btnConvertPinned.setTextColor(Color.WHITE);
        btnConvertPinned.setBackground(UiKit.roundedBox(UiKit.ACCENT_OFFICE, Color.TRANSPARENT, UiKit.dp(this, 10), 0));
        btnConvertPinned.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 10), UiKit.dp(this, 16), UiKit.dp(this, 10));
        btnConvertPinned.setOnClickListener(v -> executeRenderLiveFilmSfxOntoVideo());
        footerBar.addView(btnConvertPinned, new LinearLayout.LayoutParams(-2, -2));

        outer.addView(footerBar, new LinearLayout.LayoutParams(-1, -2));
        setContentView(outer);
    }

    private ArrayList<DramaSoundPaletteHelper.CueItem> cloneCueList(List<DramaSoundPaletteHelper.CueItem> src) {
        ArrayList<DramaSoundPaletteHelper.CueItem> out = new ArrayList<>();
        for (DramaSoundPaletteHelper.CueItem c : src) out.add(c.copy());
        return out;
    }

    private void pushSfxUndoSnapshot() {
        sfxUndoStack.add(cloneCueList(liveSfxCues));
        sfxRedoStack.clear();
    }

    /**
     * Logika Transisi Edit Enak:
     * Jika efek/backsound ditempel di detik ke-15, otomatis mundur 2 detik ke detik ke-13 (atau detik 0 jika di awal)
     * lalu langsung memutar video & efek melewati detik ke-15 dan terus lanjut hingga pengguna menekan Jeda.
     */
    private void playTransitionPreviewAt(long cueAtMs) {
        if (singleVideoUri == null || videoView == null) return;
        long preRollMs = Math.max(0L, cueAtMs - 2000L);
        try {
            stopAllCuePlayers();
            for (DramaSoundPaletteHelper.CueItem c : liveSfxCues) {
                if (c.atMs >= preRollMs) {
                    c.lastTriggeredPlaybackMs = -5000L;
                }
            }
            videoView.seekTo((int) preRollMs);
            lastTickerPosMs = preRollMs - 50L;
            if (!isPlaying) {
                videoView.start();
                isPlaying = true;
                if (btnPlayPause != null) btnPlayPause.setText("Jeda");
            }
            DramaSoundPaletteHelper.syncCuesWithPlayhead(this, liveSfxCues, lastTickerPosMs, preRollMs, true);
            mainHandler.removeCallbacks(ticker);
            mainHandler.post(ticker);
            updatePlayheadUi();
        } catch (Throwable ignored) {}
    }

    private void refreshLiveSfxUiInPlace() {
        if (paletteController != null) paletteController.refreshAllInPlace();
    }

    private void performSfxUndo() {
        if (sfxUndoStack.isEmpty()) {
            Toast.makeText(this, "Tidak ada langkah untuk di-Undo.", Toast.LENGTH_SHORT).show();
            return;
        }
        sfxRedoStack.add(cloneCueList(liveSfxCues));
        DramaSoundPaletteHelper.stopAllLoopPlayers(liveSfxCues);
        ArrayList<DramaSoundPaletteHelper.CueItem> prev = sfxUndoStack.remove(sfxUndoStack.size() - 1);
        liveSfxCues.clear();
        liveSfxCues.addAll(prev);
        Toast.makeText(this, "Undo / Nggak Jadi berhasil. Tersisa " + liveSfxCues.size() + " item.", Toast.LENGTH_SHORT).show();
        refreshLiveSfxUiInPlace();
    }

    private void performSfxRedo() {
        if (sfxRedoStack.isEmpty()) {
            Toast.makeText(this, "Tidak ada langkah untuk di-Redo.", Toast.LENGTH_SHORT).show();
            return;
        }
        sfxUndoStack.add(cloneCueList(liveSfxCues));
        DramaSoundPaletteHelper.stopAllLoopPlayers(liveSfxCues);
        ArrayList<DramaSoundPaletteHelper.CueItem> next = sfxRedoStack.remove(sfxRedoStack.size() - 1);
        liveSfxCues.clear();
        liveSfxCues.addAll(next);
        Toast.makeText(this, "Redo berhasil. Total " + liveSfxCues.size() + " item.", Toast.LENGTH_SHORT).show();
        refreshLiveSfxUiInPlace();
    }

    private void triggerOneCuePreview(DramaSoundPaletteHelper.CueItem cue) {
        try {
            final MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(this, cue.uri);
            mp.prepare();
            mp.setVolume(Math.min(1.0f, cue.volume), Math.min(1.0f, cue.volume));
            mp.start();
            activeCuePlayers.add(mp);
            mp.setOnCompletionListener(p -> {
                try { p.release(); } catch (Throwable ignored) {}
                activeCuePlayers.remove(p);
            });
        } catch (Throwable ignored) {}
    }

    private void stopAllCuePlayers() {
        DramaSoundPaletteHelper.stopAllLoopPlayers(liveSfxCues);
        for (MediaPlayer mp : new ArrayList<>(activeCuePlayers)) {
            try { if (mp.isPlaying()) mp.stop(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
        }
        activeCuePlayers.clear();
    }

    private void executeRenderLiveFilmSfxOntoVideo() {
        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        if (liveSfxCues.isEmpty()) { Toast.makeText(this, "Tempel minimal 1 efek suara atau backsound terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        final List<AudioDspEngine.MixTrackSpec> specs = new ArrayList<>();
        for (DramaSoundPaletteHelper.CueItem c : liveSfxCues) {
            AudioDspEngine.MixTrackSpec tr = new AudioDspEngine.MixTrackSpec();
            tr.uri = c.uri;
            tr.title = c.title;
            tr.volume = c.volume;
            tr.delayMs = c.atMs;
            tr.endMs = c.endMs;
            tr.loop = c.isBacksoundLoop;
            specs.add(tr);
        }
        runSingleVideoJob("Memasang " + specs.size() + " Efek Film / Backsound ke Video...", () -> {
            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
            File out = new File(dir, cleanBase(singleMeta.displayName) + "_FilmSfx_" + System.currentTimeMillis() + ".mp4");
            VideoEditEngine.mixMultiAudioOntoVideo(this, singleVideoUri, true, 1.0f, specs, false, workDir, out);
            return out;
        });
    }

    // ==================== HALAMAN KHUSUS MASING-MASING FITUR PEMOTONGAN VIDEO (TIDAK TERCAMPUR!) ====================

    private void renderDedicatedCutScreen(final boolean isCutMiddle, final boolean isAccurateReencode) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        String title = isCutMiddle
                ? "Buang Bagian Tengah Video Sambil Putar + Undo / Redo"
                : (isAccurateReencode ? "Potong Video Presisi Frame Sambil Putar + Undo / Redo" : "Potong Video Cepat Sambil Putar + Undo / Redo");
        root.addView(buildSubHeaderAndBack(title));
        root.addView(buildSimpleVideoPlayerAndCutCard(true));
        if (isAccurateReencode) root.addView(buildVideoExportQualityCard());

        LinearLayout actCard = UiKit.makeCard(this, uiPrefs);
        if (isCutMiddle) {
            actCard.addView(UiKit.makeMenuButton(this,
                    "Buang Adegan Tengah & Sambung Video Sekarang",
                    "Menghapus adegan di antara Titik Awal dan Titik Akhir lalu menyambung bagian depan dan belakang.",
                    UiKit.ACCENT_DANGER, uiPrefs, v -> executeCutMiddle()));
        } else if (isAccurateReencode) {
            actCard.addView(UiKit.makeMenuButton(this,
                    "Potong Presisi Frame & Simpan Video Sekarang (" + getExportQualitySummary() + ")",
                    "Memotong video secara presisi pada detik Awal sampai Akhir.",
                    UiKit.ACCENT_KB, uiPrefs, v -> executeAccurateTrim()));
        } else {
            actCard.addView(UiKit.makeMenuButton(this,
                    "Potong Cepat & Simpan Video Sekarang (Tanpa Render Ulang)",
                    "Menyimpan potongan video dari Titik Awal sampai Titik Akhir secara instan.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> executeFastTrim()));
        }
        root.addView(actCard);
        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 12: Halaman 100% Khusus Status WhatsApp Video Saja (Tanpa Tombol Potong Lain!). */
    private void renderDedicatedStatusWaScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Pecah Video Khusus untuk Status WhatsApp"));
        root.addView(MediaEditorCatalog.makeGuideCard(this, uiPrefs,
                "Halaman Khusus Pemotong Video Status WhatsApp / Story",
                "Halaman ini khusus untuk memecah video berdurasi panjang menjadi Part 1, Part 2, dan seterusnya secara otomatis sesuai batas durasi Status WhatsApp.",
                "Video 3 menit langsung terpecah menjadi 3 berkas MP4 berurutan tanpa render ulang."));

        root.addView(buildSimpleVideoPlayerAndCutCard(false));

        LinearLayout waCard = UiKit.makeCard(this, uiPrefs);
        waCard.addView(UiKit.makeSectionLabel(this, "PILIH DURASI PER BAGIAN STATUS WHATSAPP", UiKit.ACCENT_KB_ALT));
        String[] choices = {
                "60 Detik per Bagian (Standar Status WhatsApp 1 Menit)",
                "30 Detik per Bagian (Status WA Klasik 30 Detik)",
                "15 Detik per Bagian (Story Singkat 15 Detik)",
                "90 Detik per Bagian (1,5 Menit)"
        };
        final int[] secs = {60, 30, 15, 90};
        final Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
        waCard.addView(sp);

        waCard.addView(UiKit.makeMenuButton(this,
                "Pecah Video untuk Status WhatsApp Sekarang",
                "Memotong video aktif secara otomatis menjadi Part 1, Part 2, dan seterusnya sesuai durasi pilihan di atas.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                    if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                    final int sec = secs[sp.getSelectedItemPosition()];
                    runMultiVideoJob("Memecah Video Status WhatsApp (" + sec + " detik per bagian)...", () -> {
                        File dir = OfficeUtils.resolveBackupSubfolder(this, "Status WA Media");
                        return VideoEditEngine.fastSplitStatusParts(this, singleVideoUri, workDir, dir, cleanBase(singleMeta.displayName), sec);
                    });
                }));
        root.addView(waCard);
        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 10: Halaman Khusus Belah Video Menjadi 2 Berkas. */
    private void renderDedicatedSplit2Screen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Belah Video Menjadi 2 Berkas MP4 Terpisah"));
        root.addView(buildSimpleVideoPlayerAndCutCard(true));

        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        card.addView(UiKit.makeMenuButton(this,
                "Belah Video Menjadi 2 Berkas di Titik Awal Sekarang",
                "Memecah video aktif tepat pada detik Titik Awal menjadi Bagian 1 dan Bagian 2.",
                UiKit.ACCENT_WARM, uiPrefs, v -> executeSplitTwo()));
        root.addView(card);
        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 11: Halaman Khusus Bagi Video Sama Rata. */
    private void renderDedicatedEqualSplitScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Bagi Video Menjadi Banyak Bagian Sama Rata (2 s/d 30 Bagian)"));
        root.addView(buildSimpleVideoPlayerAndCutCard(false));

        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        TextView lbl = new TextView(this); lbl.setText("Jumlah bagian sama rata (2 sampai 30):"); lbl.setTextColor(UiKit.textPrimary(uiPrefs)); card.addView(lbl);
        final EditText etCount = new EditText(this); etCount.setText("3"); UiKit.styleEditText(this, etCount, uiPrefs); card.addView(etCount);

        card.addView(UiKit.makeMenuButton(this,
                "Bagi Video Sama Rata Sekarang",
                "Membagi total durasi video menjadi sejumlah berkas MP4 berdurasi sama rata.",
                UiKit.ACCENT_KB, uiPrefs, v -> {
                    if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                    final int count = Math.max(2, Math.min(30, (int) safeDouble(etCount.getText().toString(), 3)));
                    runMultiVideoJob("Membagi Video Menjadi " + count + " Bagian...", () -> {
                        File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
                        return VideoEditEngine.fastSplitEqualVideoParts(this, singleVideoUri, workDir, dir, cleanBase(singleMeta.displayName), count);
                    });
                }));
        root.addView(card);
        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 5: Halaman Khusus Ubah Rentang Video Menjadi Animasi GIF. */
    private void renderDedicatedGifScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Ubah Rentang Video Menjadi Animasi GIF Bergerak"));
        root.addView(buildSimpleVideoPlayerAndCutCard(true));

        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        String[] sizes = {"Lebar 360 px • 10 FPS (Ringan untuk Chat)", "Lebar 480 px • 12 FPS (Standar Jernih)", "Lebar 600 px • 15 FPS (Kualitas Tinggi)"};
        final int[] widths = {360, 480, 600};
        final int[] fpsArr = {10, 12, 15};
        final Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, sizes));
        sp.setSelection(1);
        card.addView(sp);

        card.addView(UiKit.makeMenuButton(this,
                "Ekspor Rentang Video ke Animasi GIF Sekarang",
                "Mengubah bagian dari Titik Awal sampai Titik Akhir (maksimal 15 detik) menjadi berkas .GIF.",
                UiKit.ACCENT_KB_ALT, uiPrefs, v -> {
                    if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                    final long s = hasMarkedIn ? activeInMs : AudioDspEngine.parseTimeToMs(etInTime.getText().toString(), singleMeta.durationMs);
                    final long e = hasMarkedOut ? activeOutMs : AudioDspEngine.parseTimeToMs(etOutTime.getText().toString(), singleMeta.durationMs);
                    final int idx = sp.getSelectedItemPosition();
                    stopVideoPlayback();
                    ProgressDialog pd = ProgressDialog.show(this, "Membuat Animasi GIF...", "Merender GIF dengan FFmpeg...", true, false);
                    worker.submit(() -> {
                        try {
                            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
                            File out = new File(dir, cleanBase(singleMeta.displayName) + "_Animasi_" + System.currentTimeMillis() + ".gif");
                            VideoEditEngine.exportVideoRangeToGif(this, singleVideoUri, workDir, out, s, e, widths[idx], fpsArr[idx]);
                            mainHandler.post(() -> { if (pd.isShowing()) pd.dismiss(); showSavedDialog("Animasi GIF Selesai Dibuat", out, "image/gif"); });
                        } catch (Throwable t) {
                            mainHandler.post(() -> { if (pd.isShowing()) pd.dismiss(); Toast.makeText(this, "Gagal: " + t.getMessage(), Toast.LENGTH_LONG).show(); });
                        }
                    });
                }));
        root.addView(card);
        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 6: Halaman Khusus Ambil Foto Tangkap Layar PNG. */
    private void renderDedicatedCaptureFrameScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Ambil Foto Tangkap Layar (Capture Frame PNG) dari Detik Video"));
        root.addView(buildSimpleVideoPlayerAndCutCard(false));

        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        card.addView(UiKit.makeMenuButton(this,
                "Simpan Gambar Foto PNG dari Detik Video Saat Ini",
                "Menyimpan frame video yang sedang tampil di pemutar menjadi foto gambar PNG tajam.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> executeCaptureFrame()));
        root.addView(card);
        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 4: Halaman Khusus Ubah Resolusi, FPS, Bitrate Audio & Kompres MP4. */
    private void renderDedicatedCompressResolutionScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Ubah Resolusi, FPS, Bitrate Audio & Kompres Ukuran Video MP4"));
        root.addView(buildSimpleVideoPlayerAndCutCard(false));
        root.addView(buildVideoExportQualityCard());

        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        card.addView(UiKit.makeMenuButton(this,
                "Proses Konversi & Kompres Video MP4 Sekarang (" + getExportQualitySummary() + ")",
                "Merender video sesuai resolusi, FPS, tingkat kompresi CRF, dan bitrate audio yang dipilih di atas.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> executeRenderVisualNow()));
        root.addView(card);
        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 15: Halaman Khusus Rekam Dubbing Mikrofon ke Video. */
    private void renderDedicatedDubbingScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Rekam Dubbing / Voiceover Suara Mikrofon Langsung ke Video"));
        root.addView(buildSimpleVideoPlayerAndCutCard(false));

        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        card.addView(UiKit.makeMenuButton(this,
                "Mulai Rekam Suara Dubbing Mikrofon Sekarang",
                "Merekam suara narasi Anda dan langsung memasangnya ke dalam jalur audio video.",
                UiKit.ACCENT_WARM, uiPrefs, v -> startVideoDubbingRecorder()));
        root.addView(card);
        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 25: Halaman Khusus Buat Video Slideshow Foto + Backsound Aplikasi / HP. */
    private void renderDedicatedSlideshowScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Buat Video Slideshow MP4 dari Kumpulan Foto + Backsound Musik"));

        LinearLayout slideCard = UiKit.makeCard(this, uiPrefs);
        slideCard.addView(UiKit.makeSectionLabel(this, "A. TAMBAH FOTO & BACKSOUND MUSIK SLIDESHOW", UiKit.ACCENT_OFFICE));
        slideCard.addView(UiKit.makeMenuButton(this,
                "1. Tambah Foto ke Daftar Slideshow",
                "Memilih foto dari penyimpanan untuk dijadikan urutan slide video.",
                UiKit.ACCENT_KB, uiPrefs,
                v -> OfficeUtils.launchFilePicker(this, REQ_PICK_SLIDE_IMG, "image", "Tambah Foto Slideshow")));

        slidePhotoContainer = new LinearLayout(this);
        slidePhotoContainer.setOrientation(LinearLayout.VERTICAL);
        slideCard.addView(slidePhotoContainer);
        rebuildSlidePhotoUi();

        tvSlideBgm = new TextView(this);
        tvSlideBgm.setText(slideBgmUri == null ? "Backsound Musik Latar: Belum ada musik dipilih (Opsional)" : "Backsound Aktif: " + slideBgmTitle);
        tvSlideBgm.setTextColor(UiKit.textPrimary(uiPrefs));
        tvSlideBgm.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 4));
        slideCard.addView(tvSlideBgm);

        slideCard.addView(UiKit.makeMenuButton(this,
                "2. Pilih Backsound Musik Latar (Dari Koleksi Aplikasi 1-2 Menit atau Memori HP)",
                "Pilih kategori Backsound Islami, Piano Santai, Jazz, Rock, Jedag-Jedug, Sinematik dari aplikasi atau musik sendiri dari HP.",
                UiKit.ACCENT_AI, uiPrefs,
                v -> StudioGudangAudioManager.showUniversalAudioSourceChooser(
                        this, uiPrefs, "Pilih Backsound Musik Slideshow", true, false,
                        (uri, title, durSec, isBgm) -> {
                            slideBgmUri = uri;
                            slideBgmTitle = title;
                            if (tvSlideBgm != null) tvSlideBgm.setText("Backsound Aktif: " + title);
                        },
                        () -> OfficeUtils.launchFilePicker(this, REQ_PICK_SLIDE_BGM, "audio", "Pilih Musik Latar Slideshow"),
                        () -> launchSysPicker("audio/*", REQ_PICK_SLIDE_BGM))));

        etSlideSec = makeLabeledInput(slideCard, "Durasi Tampil Tiap Foto (detik):", "3.0");

        slideCard.addView(UiKit.makeMenuButton(this,
                "Buat Video Slideshow MP4 Sekarang",
                "Merender seluruh foto dan backsound musik menjadi berkas video MP4.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> executeRenderSlideshow()));
        root.addView(slideCard);

        sv.addView(root);
        setContentView(sv);
    }

    /** Halaman Khusus Masing-Masing Fitur Video Tunggal (Fitur 16..24, 27..36). */
    private void renderDedicatedSingleVideoToolScreen(final int fid) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        String title;
        switch (fid) {
            case 16: title = "Bisukan Suara Asli Video (Mute Audio Track Instan)"; break;
            case 17: title = "Perkeras & Standarisasi Volume Suara Video (Loudnorm -14 LUFS)"; break;
            case 18: title = "Bersihkan Noise Angin & Desis pada Suara Video (FFT Denoise)"; break;
            case 19: title = "Perjelas Suara Bicara / Ceramah di Video (Studio Vocal Compressor)"; break;
            case 20: title = "Tambahkan Gema Reverb Masjid Agung pada Suara Video"; break;
            case 21: title = "Ubah Rasio Layar Video (9:16, 16:9, 1:1, 4:5, 21:9 + Latar Blur)"; break;
            case 22: title = "Potong Pinggiran Layar Video (Crop Area Layar %)"; break;
            case 23: title = "Putar Orientasi Arah Video (Rotate 90, 180, 270 Derajat)"; break;
            case 24: title = "Balik Cermin Video Horizontal atau Vertikal (Flip Mirror)"; break;
            case 27: title = "Ubah Kecepatan Video (Slow Motion & Timelapse 0,25x - 4,0x)"; break;
            case 28: title = "Putar Video Mundur dari Akhir ke Awal (Reverse Video & Audio)"; break;
            case 29: title = "Ulangi Video Berkali-kali (Video Looper & Efek Boomerang)"; break;
            case 30: title = "Filter Warna Sinematik (18 Preset Warna Siap Pakai)"; break;
            case 31: title = "Atur Kecerahan, Kontras & Saturasi Warna Video Manual"; break;
            case 32: title = "Pertajam Gambar Video (HD Sharpen) & Efek Cahaya Halus"; break;
            case 33: title = "Efek Transisi Fade In & Fade Out Layar Hitam pada Video"; break;
            case 34: title = "Sensor Blur Area Tertentu pada Video (Privacy Blur Box)"; break;
            case 35: title = "Tambahkan Teks Judul & Pasang Logo Watermark PNG pada Video"; break;
            default: title = "Buat Subtitle Otomatis AI Gemini & Bakar File Subtitle (.SRT)"; break;
        }

        root.addView(buildSubHeaderAndBack(title));
        root.addView(buildSimpleVideoPlayerAndCutCard(false));

        LinearLayout actCard = UiKit.makeCard(this, uiPrefs);
        actCard.addView(UiKit.makeSectionLabel(this, "PENGATURAN & EKSEKUSI FITUR INI", UiKit.ACCENT_AI));

        if (fid == 16) {
            actCard.addView(UiKit.makeMenuButton(this, "Bisukan Suara Video & Simpan Sekarang (Tanpa Render Ulang Gambar)",
                    "Menghapus jalur audio dari video secara instan.",
                    UiKit.ACCENT_DANGER, uiPrefs, v -> executeMuteVideo()));
        } else if (fid == 17) {
            actCard.addView(UiKit.makeMenuButton(this, "1. Standarisasi Volume Suara Video (Loudnorm -14 LUFS)",
                    "Meratakan dan mengangkat volume suara video ke standar YouTube/TikTok tanpa render ulang gambar.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> applyVideoAudioFx("loudnorm=I=-14:TP=-1.5:LRA=11", "Loudnorm")));
            actCard.addView(UiKit.makeMenuButton(this, "2. Perkeras Suara Video Ekstra (+8 dB + Anti-Pecah Limiter)",
                    "Menaikkan volume suara video yang sangat pelan sebesar +8 dB.",
                    UiKit.ACCENT_WARM, uiPrefs, v -> applyVideoAudioFx("volume=8dB,alimiter=limit=0.93", "Boost8dB")));
        } else if (fid == 18) {
            actCard.addView(UiKit.makeMenuButton(this, "1. Bersihkan Desis & Angin Standar pada Video (FFT Denoise)",
                    "Menghilangkan desis AC, kipas, dan dengung rendah pada suara video.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> applyVideoAudioFx("highpass=f=100,afftdn=nf=-25", "Denoise")));
            actCard.addView(UiKit.makeMenuButton(this, "2. Bersihkan Noise Berat & Angin Luar Ruangan pada Video",
                    "Pembersihan ekstra kuat (-35 dB FFT Denoise) untuk video rekaman jalan raya atau lapangan.",
                    UiKit.ACCENT_DANGER, uiPrefs, v -> applyVideoAudioFx("highpass=f=135,lowpass=f=8800,afftdn=nf=-35:tn=1", "OutdoorDenoise")));
        } else if (fid == 19) {
            actCard.addView(UiKit.makeMenuButton(this, "Perjelas Suara Bicara / Ceramah di Video Sekarang (Vocal Compressor)",
                    "Memadatkan dan mempertegas artikulasi vokal pembicara di dalam video.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> applyVideoAudioFx("highpass=f=85,acompressor=threshold=-20dB:ratio=3.5:attack=15:release=150:makeup=4.5dB", "VocalClear")));
        } else if (fid == 20) {
            actCard.addView(UiKit.makeMenuButton(this, "Pasang Gema Reverb Masjid Agung pada Suara Video Sekarang",
                    "Memberikan pantulan akustik Masjid Agung yang khidmat pada jalur suara video.",
                    UiKit.ACCENT_WARM, uiPrefs, v -> applyVideoAudioFx("aecho=0.8:0.88:55|105|175:0.38|0.28|0.18", "ReverbMasjid")));
        } else if (fid == 21) {
            actCard.addView(UiKit.makeMenuButton(this, "Pilih Rasio Layar (9:16, 16:9, 1:1, 4:5, 21:9) & Latar Blur",
                    "Membuka pilihan rasio layar dan jenis latar belakang.",
                    UiKit.ACCENT_KB, uiPrefs, v -> showAspectAndBgDialog()));
            actCard.addView(UiKit.makeMenuButton(this, "Render & Simpan Perubahan Rasio Layar Video Sekarang",
                    "Menerapkan rasio layar terpilih ke berkas video MP4 baru.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> executeRenderVisualNow()));
        } else if (fid == 22) {
            actCard.addView(UiKit.makeMenuButton(this, "Atur Persentase Crop Pinggiran Layar Video",
                    "Masukkan persentase pemangkasan tepi kiri, kanan, atas, dan bawah.",
                    UiKit.ACCENT_KB, uiPrefs, v -> showCropMarginDialog()));
            actCard.addView(UiKit.makeMenuButton(this, "Render & Simpan Hasil Crop Video Sekarang",
                    "Menyimpan video yang telah dipotong pinggiran layarnya.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> executeRenderVisualNow()));
        } else if (fid == 23) {
            String[] rots = {"Putar 90 Derajat Searah Jarum Jam", "Putar 180 Derajat (Terbalik)", "Putar 270 Derajat (90 Derajat ke Kiri)"};
            final int[] rotVals = {90, 180, 270};
            final Spinner sp = new Spinner(this);
            sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, rots));
            actCard.addView(sp);
            actCard.addView(UiKit.makeMenuButton(this, "Putar Orientasi Video & Simpan Sekarang",
                    "Memutar arah layar video sesuai pilihan di atas.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                        renderOpts.extraRotateDeg = rotVals[sp.getSelectedItemPosition()];
                        executeRenderVisualNow();
                    }));
        } else if (fid == 24) {
            actCard.addView(UiKit.makeMenuButton(this, "1. Balik Cermin Kiri-Kanan (Horizontal Mirror Flip)",
                    "Mencerminkan tampilan video secara horizontal.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                        renderOpts.flipH = true; renderOpts.flipV = false;
                        executeRenderVisualNow();
                    }));
            actCard.addView(UiKit.makeMenuButton(this, "2. Balik Cermin Atas-Bawah (Vertical Flip)",
                    "Mencerminkan tampilan video secara vertikal.",
                    UiKit.ACCENT_KB, uiPrefs, v -> {
                        renderOpts.flipH = false; renderOpts.flipV = true;
                        executeRenderVisualNow();
                    }));
        } else if (fid == 27) {
            String[] spds = {
                    "0.25x (Super Slow Motion)", "0.50x (Slow Motion Halus)", "0.75x (Santai)",
                    "1.25x (Lebih Cepat & Padat)", "1.50x (Cepat)", "2.00x (Timelapse 2x)", "3.00x (Timelapse 3x)", "4.00x (Hyper Timelapse 4x)"
            };
            final float[] spdVals = {0.25f, 0.50f, 0.75f, 1.25f, 1.50f, 2.0f, 3.0f, 4.0f};
            final Spinner sp = new Spinner(this);
            sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, spds));
            sp.setSelection(1);
            actCard.addView(sp);
            actCard.addView(UiKit.makeMenuButton(this, "Ubah Kecepatan Video & Simpan Sekarang",
                    "Merender perubahan kecepatan video beserta penyesuaian tempo suaranya.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                        renderOpts.speedFactor = spdVals[sp.getSelectedItemPosition()];
                        executeRenderVisualNow();
                    }));
        } else if (fid == 28) {
            actCard.addView(UiKit.makeMenuButton(this, "Putar Mundur Video & Audio (Reverse) Sekarang",
                    "Membalik arah pemutaran video dan suaranya dari detik terakhir ke detik pertama.",
                    UiKit.ACCENT_WARM, uiPrefs, v -> {
                        renderOpts.reverse = true;
                        executeRenderVisualNow();
                    }));
        } else if (fid == 29) {
            actCard.addView(UiKit.makeMenuButton(this, "1. Efek Boomerang Video (Gerak Maju Lalu Mundur Otomatis)",
                    "Membuat video bergerak maju lalu memantul mundur secara mulus.",
                    UiKit.ACCENT_AI, uiPrefs, v -> {
                        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSingleVideoJob("Membuat Video Boomerang...", () -> {
                            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
                            File out = new File(dir, cleanBase(singleMeta.displayName) + "_Boomerang_" + System.currentTimeMillis() + ".mp4");
                            VideoEditEngine.renderBoomerangOrLoopVideo(this, singleVideoUri, workDir, out, true, 2);
                            return out;
                        });
                    }));
            actCard.addView(UiKit.makeMenuButton(this, "2. Ulangi Video 2 Kali Berturut-turut (Looper 2x Instan)", "Menggandakan video 2x.", UiKit.ACCENT_OFFICE, uiPrefs, v -> runVideoLoopCount(2)));
            actCard.addView(UiKit.makeMenuButton(this, "3. Ulangi Video 3 Kali Berturut-turut (Looper 3x Instan)", "Menggandakan video 3x.", UiKit.ACCENT_KB, uiPrefs, v -> runVideoLoopCount(3)));
            actCard.addView(UiKit.makeMenuButton(this, "4. Ulangi Video 5 Kali Berturut-turut (Looper 5x Instan)", "Menggandakan video 5x.", UiKit.ACCENT_KB_ALT, uiPrefs, v -> runVideoLoopCount(5)));
        } else if (fid == 30) {
            actCard.addView(UiKit.makeMenuButton(this, "Pilih dari 18 Preset Filter Warna Sinematik",
                    "Pilih Cinema, Warm, Cool, Vintage, Hitam Putih, Sepia, Vivid, Night, Cyberpunk, Pastel, Dokumenter, Sunset, Drama, Vignette.",
                    UiKit.ACCENT_KB, uiPrefs, v -> showColorFilterDialog()));
            actCard.addView(UiKit.makeMenuButton(this, "Terapkan Filter Warna & Simpan Video Sekarang",
                    "Merender video dengan filter warna yang telah dipilih.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> executeRenderVisualNow()));
        } else if (fid == 31) {
            actCard.addView(UiKit.makeMenuButton(this, "Atur Angka Kecerahan, Kontras & Saturasi Warna",
                    "Sesuaikan terang, ketajaman kontras, dan kepekatan warna.",
                    UiKit.ACCENT_KB, uiPrefs, v -> showColorFilterDialog()));
            actCard.addView(UiKit.makeMenuButton(this, "Simpan Perubahan Warna Video Sekarang",
                    "Merender video dengan pengaturan kecerahan, kontras, dan saturasi baru.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> executeRenderVisualNow()));
        } else if (fid == 32) {
            actCard.addView(UiKit.makeMenuButton(this, "1. Pertajam Detail Gambar Video (HD Unsharp / Clarify)",
                    "Meningkatkan ketajaman tepi objek pada video.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSingleVideoJob("Mempertajam Detail Gambar Video...", () -> {
                            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
                            File out = new File(dir, cleanBase(singleMeta.displayName) + "_SharpenHD_" + System.currentTimeMillis() + ".mp4");
                            VideoEditEngine.applyVideoQuickVisualFilter(this, singleVideoUri, workDir, out, "unsharp=5:5:1.25:5:5:0.0", null, AUDIO_BR_VALUES[selectedAudioBrIdx]);
                            return out;
                        });
                    }));
            actCard.addView(UiKit.makeMenuButton(this, "2. Efek Cahaya Lembut (Soft Glow Portrait)",
                    "Menghaluskan tampilan wajah dan memberikan efek cahaya hangat.",
                    UiKit.ACCENT_AI, uiPrefs, v -> {
                        renderOpts.colorPreset = "soft_glow";
                        executeRenderVisualNow();
                    }));
        } else if (fid == 33) {
            actCard.addView(UiKit.makeMenuButton(this, "Terapkan Transisi Fade In & Fade Out Layar Hitam Sekarang",
                    "Membuat 1,5 detik awal video muncul perlahan dari layar gelap dan 1,5 detik akhir video meredup halus.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        final double durS = Math.max(2.0, singleMeta.durationMs / 1000.0);
                        final double outSt = Math.max(0.5, durS - 1.5);
                        final String vf = String.format(Locale.US, "fade=t=in:st=0:d=1.5,fade=t=out:st=%.2f:d=1.5", outSt);
                        final String af = String.format(Locale.US, "afade=t=in:st=0:d=1.5,afade=t=out:st=%.2f:d=1.5", outSt);
                        runSingleVideoJob("Menerapkan Transisi Fade In & Fade Out Video...", () -> {
                            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
                            File out = new File(dir, cleanBase(singleMeta.displayName) + "_FadeInOut_" + System.currentTimeMillis() + ".mp4");
                            VideoEditEngine.applyVideoQuickVisualFilter(this, singleVideoUri, workDir, out, vf, af, AUDIO_BR_VALUES[selectedAudioBrIdx]);
                            return out;
                        });
                    }));
        } else if (fid == 34) {
            actCard.addView(UiKit.makeMenuButton(this, "Pilih Area Sensor Blur pada Video & Proses Sekarang",
                    "Kaburkan area tengah, bawah, atas, atau seluruh latar video.",
                    UiKit.ACCENT_DANGER, uiPrefs, v -> showPrivacyBlurDialog()));
        } else if (fid == 35) {
            tvOverlaySummary = new TextView(this);
            tvOverlaySummary.setTextColor(UiKit.textPrimary(uiPrefs));
            refreshOverlaySummary();
            actCard.addView(tvOverlaySummary);
            actCard.addView(UiKit.makeMenuButton(this, "1. Tambah Teks Judul ke Video", "Tulis teks dan detik kemunculan.", UiKit.ACCENT_KB, uiPrefs, v -> showAddTextOverlayDialog()));
            actCard.addView(UiKit.makeMenuButton(this, "2. Pasang Gambar Logo Watermark PNG", "Pilih logo transparan PNG untuk pojok video.", UiKit.ACCENT_WARM, uiPrefs, v -> showWatermarkOrClearDialog()));
            actCard.addView(UiKit.makeMenuButton(this, "3. Render & Simpan Video dengan Teks / Watermark Sekarang", "Menempelkan teks dan logo ke dalam berkas video MP4.", UiKit.ACCENT_OFFICE, uiPrefs, v -> executeRenderVisualNow()));
        } else {
            tvOverlaySummary = new TextView(this);
            tvOverlaySummary.setTextColor(UiKit.textPrimary(uiPrefs));
            refreshOverlaySummary();
            actCard.addView(tvOverlaySummary);
            actCard.addView(UiKit.makeMenuButton(this, "1. Buat Subtitle Otomatis dari Suara Video dengan AI Gemini", "AI mengenali ucapan video menjadi baris subtitle berwaktu.", UiKit.ACCENT_AI, uiPrefs, v -> generateAiSubtitleForVideo()));
            actCard.addView(UiKit.makeMenuButton(this, "2. Muat Berkas Subtitle (.SRT) dari Penyimpanan HP", "Impor file .SRT.", UiKit.ACCENT_KB, uiPrefs, v -> OfficeUtils.launchFilePicker(this, REQ_PICK_SRT, "text", "Pilih Berkas Subtitle .SRT")));
            actCard.addView(UiKit.makeMenuButton(this, "3. Bakar Subtitle ke Dalam Video MP4 & Simpan Sekarang", "Menempelkan seluruh baris subtitle secara permanen ke gambar video.", UiKit.ACCENT_OFFICE, uiPrefs, v -> executeRenderVisualNow()));
        }

        root.addView(actCard);
        sv.addView(root);
        setContentView(sv);
    }

    // ==================== EKSEKUSI POTONG VIDEO, GABUNG & MIXER BACKSOUND ====================

    private void executeFastTrim() {
        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        long s = hasMarkedIn ? activeInMs : AudioDspEngine.parseTimeToMs(etInTime.getText().toString(), singleMeta.durationMs);
        long e = hasMarkedOut ? activeOutMs : AudioDspEngine.parseTimeToMs(etOutTime.getText().toString(), singleMeta.durationMs);
        if (e <= s) { Toast.makeText(this, "Titik Akhir harus lebih besar dari Titik Awal.", Toast.LENGTH_SHORT).show(); return; }
        final long fs = s, fe = e;
        runSingleVideoJob("Memotong Video Cepat...", () -> {
            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
            File out = new File(dir, cleanBase(singleMeta.displayName) + "_Trim_" + System.currentTimeMillis() + ".mp4");
            VideoEditEngine.fastTrimVideo(this, singleVideoUri, workDir, out, fs, fe, true);
            return out;
        });
    }

    private void executeAccurateTrim() {
        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        long s = hasMarkedIn ? activeInMs : AudioDspEngine.parseTimeToMs(etInTime.getText().toString(), singleMeta.durationMs);
        long e = hasMarkedOut ? activeOutMs : AudioDspEngine.parseTimeToMs(etOutTime.getText().toString(), singleMeta.durationMs);
        if (e <= s) { Toast.makeText(this, "Titik Akhir harus lebih besar dari Titik Awal.", Toast.LENGTH_SHORT).show(); return; }
        final long fs = s, fe = e;
        syncRenderOptionsWithExportPrefs();
        runSingleVideoJob("Memotong Video Presisi Frame...", () -> {
            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
            File out = new File(dir, cleanBase(singleMeta.displayName) + "_TrimPresisi_" + System.currentTimeMillis() + ".mp4");
            VideoEditEngine.renderVisualVideo(this, singleVideoUri, workDir, out, fs, fe, renderOpts);
            return out;
        });
    }

    private void executeCutMiddle() {
        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        long s = hasMarkedIn ? activeInMs : AudioDspEngine.parseTimeToMs(etInTime.getText().toString(), singleMeta.durationMs);
        long e = hasMarkedOut ? activeOutMs : AudioDspEngine.parseTimeToMs(etOutTime.getText().toString(), singleMeta.durationMs);
        if (e <= s) { Toast.makeText(this, "Titik Akhir harus lebih besar dari Titik Awal.", Toast.LENGTH_SHORT).show(); return; }
        final long fs = s, fe = e;
        runSingleVideoJob("Membuang Adegan Tengah Video...", () -> {
            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
            File out = new File(dir, cleanBase(singleMeta.displayName) + "_CutTengah_" + System.currentTimeMillis() + ".mp4");
            VideoEditEngine.fastCutRangeVideo(this, singleVideoUri, workDir, out, fs, fe, singleMeta.durationMs, singleMeta.hasAudio);
            return out;
        });
    }

    private void executeSplitTwo() {
        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        long at = hasMarkedIn ? activeInMs : AudioDspEngine.parseTimeToMs(etInTime.getText().toString(), singleMeta.durationMs);
        if (at <= 300 || at >= singleMeta.durationMs - 300) {
            Toast.makeText(this, "Tandai Titik Awal di bagian tengah video sebagai titik belah.", Toast.LENGTH_LONG).show();
            return;
        }
        final long fat = at;
        runMultiVideoJob("Membelah Video Menjadi 2 Berkas...", () -> {
            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
            String b = cleanBase(singleMeta.displayName);
            File f1 = new File(dir, b + "_Bagian1_" + System.currentTimeMillis() + ".mp4");
            File f2 = new File(dir, b + "_Bagian2_" + System.currentTimeMillis() + ".mp4");
            VideoEditEngine.fastTrimVideo(this, singleVideoUri, workDir, f1, 0L, fat, true);
            VideoEditEngine.fastTrimVideo(this, singleVideoUri, workDir, f2, fat, singleMeta.durationMs, true);
            List<File> res = new ArrayList<>(); res.add(f1); res.add(f2);
            return res;
        });
    }

    private void executeCaptureFrame() {
        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        final long at = getPlayheadMs();
        stopVideoPlayback();
        ProgressDialog pd = ProgressDialog.show(this, "Menyimpan Foto Frame PNG...", "Mengambil gambar tajam...", true, false);
        worker.submit(() -> {
            try {
                File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
                File out = new File(dir, cleanBase(singleMeta.displayName) + "_FotoFrame_" + System.currentTimeMillis() + ".png");
                VideoEditEngine.extractFrameToPng(this, singleVideoUri, at, out);
                mainHandler.post(() -> { if (pd.isShowing()) pd.dismiss(); showSavedDialog("Foto Frame PNG Tersimpan", out, "image/png"); });
            } catch (Throwable t) {
                mainHandler.post(() -> { if (pd.isShowing()) pd.dismiss(); Toast.makeText(this, "Gagal: " + t.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    // ==================== FITUR 13: GABUNG BANYAK VIDEO ====================

    private void renderMergeScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Gabung Banyak Video Berurutan (Video 1 + Video 2 + Video 3 dst.)"));

        LinearLayout addCard = UiKit.makeCard(this, uiPrefs);
        addCard.addView(UiKit.makeSectionLabel(this, "A. TAMBAH KLIP VIDEO KE DAFTAR GABUNG", UiKit.ACCENT_KB));
        addCard.addView(UiKit.makeMenuButton(this, "Tambah Klip Video dari File Manager", "Pilih berkas video dari File Manager",
                UiKit.ACCENT_KB, uiPrefs, v -> OfficeUtils.launchFilePicker(this, REQ_PICK_MERGE_FM, "video", "Tambah Video ke Daftar Gabung")));
        addCard.addView(UiKit.makeMenuButton(this, "Tambah Klip Video dari Galeri HP", "Pilih berkas video dari Galeri HP",
                UiKit.ACCENT_SYSTEM, uiPrefs, v -> launchSysPicker("video/*", REQ_PICK_MERGE_SYS)));
        root.addView(addCard);

        mergeListContainer = new LinearLayout(this);
        mergeListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(mergeListContainer);
        rebuildMergeListUi();

        LinearLayout runCard = UiKit.makeCard(this, uiPrefs);
        runCard.addView(UiKit.makeMenuButton(this, "Gabungkan Semua Video di Daftar Sekarang",
                "Menyatukan seluruh klip Video 1 sampai terakhir menjadi satu berkas MP4 utuh.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> executeMergeVideos()));
        root.addView(runCard);
        sv.addView(root);
        setContentView(sv);
    }

    private void rebuildMergeListUi() {
        if (mergeListContainer == null) return;
        mergeListContainer.removeAllViews();
        if (mergeVideos.isEmpty()) {
            LinearLayout c = UiKit.makeCard(this, uiPrefs);
            TextView tv = new TextView(this);
            tv.setText("Daftar Gabung Video Masih Kosong. Tekan tombol Tambah Klip Video di atas.");
            tv.setTextColor(UiKit.textMuted(uiPrefs));
            c.addView(tv);
            mergeListContainer.addView(c);
            return;
        }
        for (int i = 0; i < mergeVideos.size(); i++) {
            final int idx = i;
            final int num = i + 1;
            final VidSlot s = mergeVideos.get(i);
            LinearLayout card = UiKit.makeCard(this, uiPrefs);
            TextView tv = new TextView(this);
            tv.setText("Urutan Video " + num + ": " + s.title + "\nDurasi: " + AudioDspEngine.formatMsShort(s.durationMs));
            tv.setTextSize(14); tv.setTypeface(null, Typeface.BOLD); tv.setTextColor(UiKit.textPrimary(uiPrefs));
            card.addView(tv);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, UiKit.dp(this, 6), 0, 0);
            int alt = UiKit.surfaceAlt(uiPrefs), txt = UiKit.textPrimary(uiPrefs);
            row.addView(OfficeUtils.makeRowButton(this, "Geser Naik", "Majukan Video " + num, alt, txt, v -> {
                if (idx > 0) { VidSlot item = mergeVideos.remove(idx); mergeVideos.add(idx - 1, item); rebuildMergeListUi(); }
            }));
            row.addView(OfficeUtils.makeRowButton(this, "Geser Turun", "Mundurkan Video " + num, alt, txt, v -> {
                if (idx < mergeVideos.size() - 1) { VidSlot item = mergeVideos.remove(idx); mergeVideos.add(idx + 1, item); rebuildMergeListUi(); }
            }));
            row.addView(OfficeUtils.makeRowButton(this, "Hapus Video " + num, "Hapus dari daftar", UiKit.ACCENT_DANGER, Color.WHITE, v -> {
                mergeVideos.remove(idx); rebuildMergeListUi();
            }));
            card.addView(row);
            mergeListContainer.addView(card);
        }
    }

    private void executeMergeVideos() {
        if (mergeVideos.size() < 2) {
            Toast.makeText(this, "Tambahkan minimal 2 klip video untuk digabungkan.", Toast.LENGTH_LONG).show();
            return;
        }
        List<Uri> uris = new ArrayList<>();
        for (VidSlot s : mergeVideos) uris.add(s.uri);
        runSingleVideoJob("Menggabungkan " + uris.size() + " Klip Video...", () -> {
            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
            File out = new File(dir, "Gabung_" + uris.size() + "Video_" + System.currentTimeMillis() + ".mp4");
            VideoEditEngine.mergeVideos(this, uris, workDir, out);
            return out;
        });
    }

    // ==================== FITUR 14: GANTI / CAMPUR BACKSOUND MUSIK LATAR VIDEO ====================

    private void renderAudioMixScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Ganti atau Campur Backsound Musik Latar Video"));
        root.addView(buildSimpleVideoPlayerAndCutCard(false));

        LinearLayout mixCard = UiKit.makeCard(this, uiPrefs);
        mixCard.addView(UiKit.makeSectionLabel(this, "B. TAMBAH BACKSOUND MUSIK DARI KOLEKSI APLIKASI ATAU MEMORI HP", UiKit.ACCENT_KB));

        cbKeepOrigAudio = new CheckBox(this);
        cbKeepOrigAudio.setText("Pertahankan Suara Asli Video (Matikan centang jika ingin mengganti total dengan Backsound baru)");
        cbKeepOrigAudio.setChecked(true);
        cbKeepOrigAudio.setTextColor(UiKit.textPrimary(uiPrefs));
        mixCard.addView(cbKeepOrigAudio);

        cbVideoAutoDucking = new CheckBox(this);
        cbVideoAutoDucking.setText("Aktifkan Auto-Ducking (Backsound musik otomatis mengecil saat suara asli video berbicara)");
        cbVideoAutoDucking.setChecked(true);
        cbVideoAutoDucking.setTextColor(UiKit.textPrimary(uiPrefs));
        mixCard.addView(cbVideoAutoDucking);

        mixCard.addView(UiKit.makeMenuButton(this,
                "1. Pilih Backsound Musik / Efek Film dari Koleksi Aplikasi (1-2 Menit)",
                "Pilih kategori Backsound Islami, Piano Santai, Jazz, Rock, Jedag-Jedug, Tegang/Horor, Sinematik dari aplikasi (otomatis dipotong pas mengikuti durasi video).",
                UiKit.ACCENT_AI, uiPrefs,
                v -> StudioGudangAudioManager.showUniversalAudioSourceChooser(
                        this, uiPrefs, "Pilih Backsound Musik Latar Video", true, true,
                        (uri, title, durSec, isBgm) -> {
                            AudioDspEngine.MixTrackSpec tr = new AudioDspEngine.MixTrackSpec();
                            tr.uri = uri; tr.title = title; tr.volume = 0.35f; tr.loop = isBgm;
                            extraVideoAudios.add(tr);
                            rebuildVidAudioListUi();
                        },
                        () -> OfficeUtils.launchFilePicker(this, REQ_PICK_AUD_FM, "audio", "Tambah Audio ke Video"),
                        () -> launchSysPicker("audio/*", REQ_PICK_AUD_SYS))));

        mixCard.addView(UiKit.makeMenuButton(this,
                "2. Pilih Backsound Musik Sendiri dari File Manager HP",
                "Pilih berkas lagu MP3/M4A/WAV milik Anda sendiri dari File Manager.",
                UiKit.ACCENT_KB, uiPrefs,
                v -> OfficeUtils.launchFilePicker(this, REQ_PICK_AUD_FM, "audio", "Tambah Audio ke Video")));

        mixCard.addView(UiKit.makeMenuButton(this,
                "3. Pilih Backsound Musik Sendiri dari Galeri / Musik HP",
                "Pilih berkas audio milik Anda sendiri dari penyimpanan sistem Android.",
                UiKit.ACCENT_SYSTEM, uiPrefs,
                v -> launchSysPicker("audio/*", REQ_PICK_AUD_SYS)));

        vidAudioListContainer = new LinearLayout(this);
        vidAudioListContainer.setOrientation(LinearLayout.VERTICAL);
        mixCard.addView(vidAudioListContainer);
        rebuildVidAudioListUi();

        mixCard.addView(UiKit.makeMenuButton(this,
                "Campur & Pasang Semua Backsound ke Video Sekarang",
                "Menyatukan backsound musik dengan video (backsound otomatis dipotong mengikuti panjang video tanpa render ulang gambar).",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> executeMixVideoAudios()));
        root.addView(mixCard);
        sv.addView(root);
        setContentView(sv);
    }

    private void rebuildVidAudioListUi() {
        if (vidAudioListContainer == null) return;
        vidAudioListContainer.removeAllViews();
        if (extraVideoAudios.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("Belum ada Backsound ditambahkan. Tekan tombol Pilih Backsound di atas.");
            tv.setTextColor(UiKit.textMuted(uiPrefs));
            tv.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6));
            vidAudioListContainer.addView(tv);
            return;
        }
        int alt = UiKit.surfaceAlt(uiPrefs), txt = UiKit.textPrimary(uiPrefs);
        for (int i = 0; i < extraVideoAudios.size(); i++) {
            final int idx = i;
            final int num = i + 1;
            final AudioDspEngine.MixTrackSpec tr = extraVideoAudios.get(i);
            LinearLayout card = UiKit.makeCard(this, uiPrefs);
            TextView tv = new TextView(this);
            tv.setText(String.format(Locale.ROOT, "Backsound %d: %s\nVolume: %d%% • Mulai Detik: %.1f dtk • Loop & Potong Otomatis: %s",
                    num, tr.title, Math.round(tr.volume * 100f), tr.delayMs / 1000.0, tr.loop ? "Aktif" : "Tidak"));
            tv.setTextSize(13.5f); tv.setTypeface(null, Typeface.BOLD); tv.setTextColor(txt);
            card.addView(tv);

            LinearLayout r1 = new LinearLayout(this);
            r1.setOrientation(LinearLayout.HORIZONTAL);
            r1.setPadding(0, UiKit.dp(this, 6), 0, 0);
            r1.addView(OfficeUtils.makeRowButton(this, "Vol -15%", "Kecilkan volume", alt, txt, v -> {
                tr.volume = Math.max(0.05f, tr.volume - 0.15f); rebuildVidAudioListUi();
            }));
            r1.addView(OfficeUtils.makeRowButton(this, "Vol +15%", "Besarkan volume", alt, txt, v -> {
                tr.volume = Math.min(2.5f, tr.volume + 0.15f); rebuildVidAudioListUi();
            }));
            r1.addView(OfficeUtils.makeRowButton(this, "Hapus", "Hapus Backsound " + num, UiKit.ACCENT_DANGER, Color.WHITE, v -> {
                extraVideoAudios.remove(idx); rebuildVidAudioListUi();
            }));
            card.addView(r1);
            vidAudioListContainer.addView(card);
        }
    }

    private void executeMuteVideo() {
        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        runSingleVideoJob("Membisukan Suara Video...", () -> {
            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
            File out = new File(dir, cleanBase(singleMeta.displayName) + "_Bisu_" + System.currentTimeMillis() + ".mp4");
            VideoEditEngine.fastTrimVideo(this, singleVideoUri, workDir, out, 0L, 0L, false);
            return out;
        });
    }

    private void applyVideoAudioFx(final String afChain, final String tag) {
        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        final int abr = AUDIO_BR_VALUES[selectedAudioBrIdx];
        runSingleVideoJob("Memproses Suara Video (" + tag + ")...", () -> {
            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
            File out = new File(dir, cleanBase(singleMeta.displayName) + "_" + tag + "_" + System.currentTimeMillis() + ".mp4");
            VideoEditEngine.applyAudioFilterOnVideo(this, singleVideoUri, workDir, out, afChain, abr);
            return out;
        });
    }

    private void executeMixVideoAudios() {
        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        if (extraVideoAudios.isEmpty()) { Toast.makeText(this, "Tambahkan minimal 1 Backsound Musik terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        final boolean keepOrig = cbKeepOrigAudio.isChecked();
        final boolean duck = cbVideoAutoDucking.isChecked();
        final List<AudioDspEngine.MixTrackSpec> copy = new ArrayList<>(extraVideoAudios);
        runSingleVideoJob("Mencampur Backsound ke Video...", () -> {
            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
            File out = new File(dir, cleanBase(singleMeta.displayName) + "_BacksoundMix_" + System.currentTimeMillis() + ".mp4");
            VideoEditEngine.mixMultiAudioOntoVideo(this, singleVideoUri, keepOrig, origVideoAudioVol, copy, duck, workDir, out);
            return out;
        });
    }

    // ==================== FITUR 1: VIDEO KE AUDIO (EKSTRAK MP3/M4A/WAV/FLAC) ====================

    private void renderVideoToAudioScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Video ke Audio (Ekstrak Suara Video ke MP3 / M4A / WAV / FLAC)"));
        root.addView(buildSimpleVideoPlayerAndCutCard(true));

        LinearLayout optCard = UiKit.makeCard(this, uiPrefs);
        optCard.addView(UiKit.makeSectionLabel(this, "PILIH FORMAT AUDIO, BITRATE & EKSTRAK SUARA", UiKit.ACCENT_AI));
        int txt = UiKit.textPrimary(uiPrefs);

        final String[] fmtLabels = {
                "MP3 Universal (Kompatibel Semua Pemutar & HP)",
                "M4A / AAC Studio (Jernih & Efisien)",
                "WAV Studio PCM 16-Bit (Tanpa Kompresi)",
                "FLAC Lossless (Master Tanpa Hilang Detail)",
                "OGG Vorbis",
                "OPUS Voice & Musik"
        };
        final String[] fmtExts = {"mp3", "m4a", "wav", "flac", "ogg", "opus"};
        TextView lFmt = new TextView(this); lFmt.setText("Pilih Format Berkas Audio Hasil:"); lFmt.setTextColor(txt); optCard.addView(lFmt);
        final Spinner spFmt = new Spinner(this);
        spFmt.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, fmtLabels));
        optCard.addView(spFmt);

        final String[] brLabels = {
                "64 kbps (Suara Bicara Hemat Ukuran)",
                "96 kbps (Rekaman Ceramah Ringan)",
                "128 kbps (Standar MP3 Harian)",
                "160 kbps (Kualitas Menengah Jernih)",
                "192 kbps (Kualitas Tinggi Studio — Direkomendasikan)",
                "256 kbps (Kualitas Musik Premium)",
                "320 kbps (Kualitas Maksimal Master)"
        };
        final int[] brVals = {64, 96, 128, 160, 192, 256, 320};
        TextView lBr = new TextView(this); lBr.setText("Pilih Kualitas Bitrate Audio (kbps):"); lBr.setTextColor(txt); lBr.setPadding(0, UiKit.dp(this, 6), 0, 0); optCard.addView(lBr);
        final Spinner spBr = new Spinner(this);
        spBr.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, brLabels));
        spBr.setSelection(4);
        optCard.addView(spBr);

        optCard.addView(UiKit.makeMenuButton(this,
                "1. Ekstrak Seluruh Suara Video ke Berkas Audio Sekarang",
                "Mengambil seluruh jalur suara dari video aktif sesuai format dan bitrate pilihan.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                    if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                    final String ext = fmtExts[spFmt.getSelectedItemPosition()];
                    final int br = brVals[spBr.getSelectedItemPosition()];
                    stopVideoPlayback();
                    ProgressDialog pd = ProgressDialog.show(this, "Mengubah Video ke Audio...", "Mengekstrak audio dengan FFmpeg...", true, false);
                    worker.submit(() -> {
                        try {
                            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
                            File out = new File(dir, cleanBase(singleMeta.displayName) + "_VideoKeAudio_" + br + "kbps_" + System.currentTimeMillis() + "." + ext);
                            AudioDspEngine.trimAudio(this, singleVideoUri, workDir, out, 0L, 0L, ext, br);
                            mainHandler.post(() -> { if (pd.isShowing()) pd.dismiss(); showSavedDialog("Video ke Audio Selesai", out, "audio/*"); });
                        } catch (Throwable t) {
                            mainHandler.post(() -> { if (pd.isShowing()) pd.dismiss(); Toast.makeText(this, "Gagal: " + t.getMessage(), Toast.LENGTH_LONG).show(); });
                        }
                    });
                }));

        optCard.addView(UiKit.makeMenuButton(this,
                "2. Ekstrak Khusus Bagian Potongan (Titik Awal sampai Akhir) ke Audio",
                "Mengambil hanya potongan suara dari Titik Awal hingga Titik Akhir yang Anda tandai.",
                UiKit.ACCENT_KB, uiPrefs, v -> {
                    if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                    final long s = hasMarkedIn ? activeInMs : AudioDspEngine.parseTimeToMs(etInTime.getText().toString(), singleMeta.durationMs);
                    final long e = hasMarkedOut ? activeOutMs : AudioDspEngine.parseTimeToMs(etOutTime.getText().toString(), singleMeta.durationMs);
                    final String ext = fmtExts[spFmt.getSelectedItemPosition()];
                    final int br = brVals[spBr.getSelectedItemPosition()];
                    stopVideoPlayback();
                    ProgressDialog pd = ProgressDialog.show(this, "Mengekstrak Potongan Audio Video...", "Memproses dengan FFmpeg...", true, false);
                    worker.submit(() -> {
                        try {
                            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
                            File out = new File(dir, cleanBase(singleMeta.displayName) + "_PotongAudio_" + br + "kbps_" + System.currentTimeMillis() + "." + ext);
                            AudioDspEngine.trimAudio(this, singleVideoUri, workDir, out, s, e > s ? e : 0L, ext, br);
                            mainHandler.post(() -> { if (pd.isShowing()) pd.dismiss(); showSavedDialog("Potongan Audio Video Selesai", out, "audio/*"); });
                        } catch (Throwable t) {
                            mainHandler.post(() -> { if (pd.isShowing()) pd.dismiss(); Toast.makeText(this, "Gagal: " + t.getMessage(), Toast.LENGTH_LONG).show(); });
                        }
                    });
                }));

        root.addView(optCard);
        sv.addView(root);
        setContentView(sv);
    }

    // ==================== FITUR 3: PASANG FOTO COVER SAMPUL VIDEO MP4 ====================

    private void renderVideoCoverScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Pasang atau Ganti Foto Cover Sampul Video MP4"));
        root.addView(buildSimpleVideoPlayerAndCutCard(false));

        LinearLayout covCard = UiKit.makeCard(this, uiPrefs);
        covCard.addView(UiKit.makeSectionLabel(this, "B. PILIH GAMBAR FOTO COVER SAMPUL VIDEO", UiKit.ACCENT_WARM));
        TextView tvCov = new TextView(this);
        tvCov.setTextSize(14);
        tvCov.setTextColor(UiKit.textPrimary(uiPrefs));
        tvCov.setText(coverImageUri == null ? "Foto Cover Video: Belum ada gambar dipilih." : "Foto Cover Aktif: " + coverImageName);
        covCard.addView(tvCov);

        LinearLayout covRow = new LinearLayout(this);
        covRow.setOrientation(LinearLayout.HORIZONTAL);
        covRow.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 4));
        covRow.addView(OfficeUtils.makeRowButton(this, "Pilih Foto Cover (File Manager)", "Pilih gambar dari File Manager",
                UiKit.ACCENT_KB, Color.WHITE, v -> OfficeUtils.launchFilePicker(this, REQ_PICK_COVER_IMG_FM, "image", "Pilih Foto Cover Video")));
        covRow.addView(OfficeUtils.makeRowButton(this, "Pilih Foto Cover (Galeri HP)", "Pilih gambar dari Galeri HP",
                UiKit.ACCENT_SYSTEM, Color.WHITE, v -> launchSysPicker("image/*", REQ_PICK_COVER_IMG_SYS)));
        covCard.addView(covRow);

        covCard.addView(UiKit.makeMenuButton(this,
                "1. Sematkan Foto Cover sebagai Thumbnail MP4 Instan (Tanpa Render Ulang)",
                "Menyimpan gambar foto sampul ke dalam metadata attached_pic berkas MP4 secara instan.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                    if (singleVideoUri == null || coverImageUri == null) { Toast.makeText(this, "Pilih video dan gambar Foto Cover terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                    runSingleVideoJob("Menyematkan Foto Cover Thumbnail MP4...", () -> {
                        File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
                        File out = new File(dir, cleanBase(singleMeta.displayName) + "_CoverThumb_" + System.currentTimeMillis() + ".mp4");
                        VideoEditEngine.embedVideoCoverImage(this, singleVideoUri, coverImageUri, workDir, out, false);
                        return out;
                    });
                }));

        covCard.addView(UiKit.makeMenuButton(this,
                "2. Pasang Foto Cover sebagai Bingkai Pembuka Video (1,2 Detik Awal + Thumbnail)",
                "Menampilkan foto cover selama 1,2 detik di awal video sehingga pasti terlihat sebagai sampul di WhatsApp dan semua pemutar.",
                UiKit.ACCENT_AI, uiPrefs, v -> {
                    if (singleVideoUri == null || coverImageUri == null) { Toast.makeText(this, "Pilih video dan gambar Foto Cover terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                    runSingleVideoJob("Memasang Bingkai Foto Cover Pembuka Video...", () -> {
                        File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
                        File out = new File(dir, cleanBase(singleMeta.displayName) + "_CoverIntro_" + System.currentTimeMillis() + ".mp4");
                        VideoEditEngine.embedVideoCoverImage(this, singleVideoUri, coverImageUri, workDir, out, true);
                        return out;
                    });
                }));

        root.addView(covCard);
        sv.addView(root);
        setContentView(sv);
    }

    // ==================== FITUR 26: VIDEO KOLASE 2 LAYAR (SPLIT SCREEN) ====================

    private void renderSplitScreenCollageScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Video Kolase 2 Layar (Split Screen Atas-Bawah / Kiri-Kanan)"));
        root.addView(buildSimpleVideoPlayerAndCutCard(false));

        LinearLayout v2Card = UiKit.makeCard(this, uiPrefs);
        v2Card.addView(UiKit.makeSectionLabel(this, "B. PILIH VIDEO KEDUA (LAYAR BAWAH ATAU KANAN)", UiKit.ACCENT_KB));
        TextView tvV2 = new TextView(this);
        tvV2.setTextSize(14);
        tvV2.setTextColor(UiKit.textPrimary(uiPrefs));
        tvV2.setText(splitVid2Uri == null ? "Video Kedua: Belum ada video kedua dipilih." : "Video Kedua Aktif: " + splitVid2Meta.displayName);
        v2Card.addView(tvV2);

        LinearLayout v2Row = new LinearLayout(this);
        v2Row.setOrientation(LinearLayout.HORIZONTAL);
        v2Row.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 4));
        v2Row.addView(OfficeUtils.makeRowButton(this, "Pilih Video 2 (File Manager)", "Pilih video kedua dari File Manager",
                UiKit.ACCENT_KB, Color.WHITE, v -> OfficeUtils.launchFilePicker(this, REQ_PICK_SPLIT_VID2_FM, "video", "Pilih Video Kedua")));
        v2Row.addView(OfficeUtils.makeRowButton(this, "Pilih Video 2 (Galeri HP)", "Pilih video kedua dari Galeri HP",
                UiKit.ACCENT_SYSTEM, Color.WHITE, v -> launchSysPicker("video/*", REQ_PICK_SPLIT_VID2_SYS)));
        v2Card.addView(v2Row);

        v2Card.addView(UiKit.makeMenuButton(this,
                "1. Buat Video Split Screen Atas & Bawah (Rasio Tegak 9:16 TikTok/Reels)",
                "Menempatkan Video 1 di bagian atas dan Video 2 di bagian bawah dalam satu layar tegak 720x1280.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                    if (singleVideoUri == null || splitVid2Uri == null) { Toast.makeText(this, "Pilih Video Pertama dan Video Kedua terlebih dahulu.", Toast.LENGTH_LONG).show(); return; }
                    runSingleVideoJob("Merender Kolase Video Atas-Bawah...", () -> {
                        File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
                        File out = new File(dir, "SplitAtasBawah_" + System.currentTimeMillis() + ".mp4");
                        VideoEditEngine.renderSplitScreenTwoVideos(this, singleVideoUri, splitVid2Uri, workDir, out, true);
                        return out;
                    });
                }));

        v2Card.addView(UiKit.makeMenuButton(this,
                "2. Buat Video Split Screen Kiri & Kanan (Rasio Lanskap 16:9 YouTube)",
                "Menempatkan Video 1 di sebelah kiri dan Video 2 di sebelah kanan dalam satu layar mendatar 1280x720.",
                UiKit.ACCENT_AI, uiPrefs, v -> {
                    if (singleVideoUri == null || splitVid2Uri == null) { Toast.makeText(this, "Pilih Video Pertama dan Video Kedua terlebih dahulu.", Toast.LENGTH_LONG).show(); return; }
                    runSingleVideoJob("Merender Kolase Video Kiri-Kanan...", () -> {
                        File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
                        File out = new File(dir, "SplitKiriKanan_" + System.currentTimeMillis() + ".mp4");
                        VideoEditEngine.renderSplitScreenTwoVideos(this, singleVideoUri, splitVid2Uri, workDir, out, false);
                        return out;
                    });
                }));

        root.addView(v2Card);
        sv.addView(root);
        setContentView(sv);
    }

    // ==================== DIALOG HELPER VISUAL & SLIDESHOW ====================

    private void rebuildSlidePhotoUi() {
        if (slidePhotoContainer == null) return;
        slidePhotoContainer.removeAllViews();
        if (slidePhotos.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("Belum ada foto dipilih.");
            tv.setTextColor(UiKit.textMuted(uiPrefs));
            slidePhotoContainer.addView(tv);
            return;
        }
        for (int i = 0; i < slidePhotos.size(); i++) {
            final int idx = i;
            final int num = i + 1;
            final ImgSlot s = slidePhotos.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView tv = new TextView(this);
            tv.setText("Foto " + num + ": " + s.title);
            tv.setTextColor(UiKit.textPrimary(uiPrefs));
            row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(OfficeUtils.makeChipButton(this, "Hapus Foto " + num, "Hapus", UiKit.ACCENT_DANGER, Color.WHITE, v -> {
                slidePhotos.remove(idx); rebuildSlidePhotoUi();
            }));
            slidePhotoContainer.addView(row);
        }
    }

    private void executeRenderSlideshow() {
        if (slidePhotos.isEmpty()) { Toast.makeText(this, "Tambahkan minimal 1 foto terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        double sec = Math.max(1.0, Math.min(20.0, safeDouble(etSlideSec.getText().toString(), 3.0)));
        List<Uri> imgs = new ArrayList<>();
        for (ImgSlot s : slidePhotos) imgs.add(s.uri);
        final Uri bgm = slideBgmUri;
        final String ratio = "ORIGINAL".equals(renderOpts.aspectRatio) ? "9:16" : renderOpts.aspectRatio;
        runSingleVideoJob("Merender Slideshow " + imgs.size() + " Foto...", () -> {
            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
            File out = new File(dir, "Slideshow_" + imgs.size() + "Foto_" + System.currentTimeMillis() + ".mp4");
            VideoEditEngine.renderPhotoSlideshow(this, imgs, bgm, workDir, out, sec, ratio);
            return out;
        });
    }

    private void showAspectAndBgDialog() {
        String[] ratios = {"ORIGINAL (Sesuai Video Asli)", "9:16 (TikTok / Reels / Status WA)", "16:9 (YouTube Lanskap)", "1:1 (Persegi Instagram)", "4:5 (Potret Feed)", "21:9 (Layar Lebar Bioskop)", "4:3 (Klasik TV)"};
        String[] rCodes = {"ORIGINAL", "9:16", "16:9", "1:1", "4:5", "21:9", "4:3"};
        String[] bgs = {"Latar Belakang Blur Halus Video Asli", "Latar Belakang Blur Pekat Sinematik", "Latar Belakang Hitam Pekat", "Latar Belakang Putih Bersih", "Latar Belakang Abu-Abu Studio", "Latar Belakang Biru Tua Navy"};
        String[] bCodes = {"BLUR", "BLUR_STRONG", "BLACK", "WHITE", "DARK_GRAY", "NAVY"};

        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 10), UiKit.dp(this, 14), UiKit.dp(this, 6));
        Spinner spR = new Spinner(this); spR.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ratios));
        Spinner spB = new Spinner(this); spB.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, bgs));
        box.addView(spR); box.addView(spB);
        UiKit.dialog(this, uiPrefs).setTitle("Atur Rasio & Latar Kanvas").setView(box).setNegativeButton("Batal", null)
                .setPositiveButton("Simpan Pengaturan", (d, w) -> {
                    renderOpts.aspectRatio = rCodes[spR.getSelectedItemPosition()];
                    renderOpts.bgMode = bCodes[spB.getSelectedItemPosition()];
                    Toast.makeText(this, "Rasio " + renderOpts.aspectRatio + " siap dirender.", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showCropMarginDialog() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 10), UiKit.dp(this, 14), UiKit.dp(this, 6));
        EditText l = makeLabeledInput(box, "Potong Tepi Kiri (%):", String.valueOf(renderOpts.cropLeftPct));
        EditText r = makeLabeledInput(box, "Potong Tepi Kanan (%):", String.valueOf(renderOpts.cropRightPct));
        EditText t = makeLabeledInput(box, "Potong Tepi Atas (%):", String.valueOf(renderOpts.cropTopPct));
        EditText b = makeLabeledInput(box, "Potong Tepi Bawah (%):", String.valueOf(renderOpts.cropBottomPct));
        UiKit.dialog(this, uiPrefs).setTitle("Crop Pinggiran Layar Video (%)").setView(box).setNegativeButton("Batal", null)
                .setPositiveButton("Simpan Crop", (d, w) -> {
                    renderOpts.cropLeftPct = Math.max(0, Math.min(35, (int) safeDouble(l.getText().toString(), 0)));
                    renderOpts.cropRightPct = Math.max(0, Math.min(35, (int) safeDouble(r.getText().toString(), 0)));
                    renderOpts.cropTopPct = Math.max(0, Math.min(35, (int) safeDouble(t.getText().toString(), 0)));
                    renderOpts.cropBottomPct = Math.max(0, Math.min(35, (int) safeDouble(b.getText().toString(), 0)));
                }).show();
    }

    private void showColorFilterDialog() {
        String[] presets = {
                "Normal (Warna Asli)", "Cinema (Kontras Bioskop)", "Warm (Hangat)", "Cool (Sejuk Biru)",
                "Vintage (Nostalgia)", "Hitam Putih (Noir)", "Sepia Klasik", "Vivid (Warna Cerah)",
                "Night (Malam)", "Negative", "HD Sharpen (Pertajam)", "Soft Glow (Halus)",
                "Cyberpunk Neon", "Pastel Lembut", "Dokumenter Tajam", "Sunset Golden Hour",
                "Drama Kontras", "Vignette Fokus Tengah"
        };
        String[] codes = {
                "none", "cinema", "warm", "cool", "vintage", "noir", "sepia", "vivid",
                "night", "negative", "sharpen", "soft_glow", "cyberpunk", "pastel",
                "documentary", "sunset", "drama", "vignette"
        };
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 10), UiKit.dp(this, 14), UiKit.dp(this, 6));
        Spinner sp = new Spinner(this); sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, presets));
        box.addView(sp);
        EditText br = makeLabeledInput(box, "Kecerahan (-0.4 s.d +0.4, normal = 0):", String.format(Locale.US, "%.2f", renderOpts.brightness));
        EditText ct = makeLabeledInput(box, "Kontras (0.5 s.d 1.8, normal = 1.0):", String.format(Locale.US, "%.2f", renderOpts.contrast));
        EditText st = makeLabeledInput(box, "Saturasi Warna (0.0 s.d 2.2, normal = 1.0):", String.format(Locale.US, "%.2f", renderOpts.saturation));
        UiKit.dialog(this, uiPrefs).setTitle("Filter & Penyesuaian Warna").setView(box).setNegativeButton("Batal", null)
                .setPositiveButton("Simpan Pengaturan Warna", (d, w) -> {
                    renderOpts.colorPreset = codes[sp.getSelectedItemPosition()];
                    renderOpts.brightness = (float) safeDouble(br.getText().toString(), 0.0);
                    renderOpts.contrast = (float) safeDouble(ct.getText().toString(), 1.0);
                    renderOpts.saturation = (float) safeDouble(st.getText().toString(), 1.0);
                }).show();
    }

    private void runVideoLoopCount(final int times) {
        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        runSingleVideoJob("Mengulang Video " + times + " Kali...", () -> {
            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
            File out = new File(dir, cleanBase(singleMeta.displayName) + "_Loop" + times + "x_" + System.currentTimeMillis() + ".mp4");
            VideoEditEngine.renderBoomerangOrLoopVideo(this, singleVideoUri, workDir, out, false, times);
            return out;
        });
    }

    private void showPrivacyBlurDialog() {
        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        final String[] areas = {
                "Sensor Blur Area Tengah Layar (Menutupi Wajah / Objek Tengah)",
                "Sensor Blur Area Bawah Layar (Menutupi Tulisan / Watermark Bawah)",
                "Sensor Blur Area Atas Layar (Menutupi Informasi Atas)",
                "Blur Lembut Seluruh Layar Video (Latar Belakang Puisi / Quotes)"
        };
        UiKit.dialog(this, uiPrefs).setTitle("Pilih Area Sensor Blur Video").setItems(areas, (d, which) -> {
            final String vf;
            if (which == 3) vf = "boxblur=12:2";
            else if (which == 0) vf = "split[main][tmp];[tmp]crop=iw/2:ih/3:iw/4:ih/3,boxblur=18:2[blr];[main][blr]overlay=W/4:H/3";
            else if (which == 1) vf = "split[main][tmp];[tmp]crop=iw*4/5:ih/5:iw/10:ih*3/4,boxblur=18:2[blr];[main][blr]overlay=W/10:H*3/4";
            else vf = "split[main][tmp];[tmp]crop=iw*4/5:ih/5:iw/10:ih/20,boxblur=18:2[blr];[main][blr]overlay=W/10:H/20";
            runSingleVideoJob("Menerapkan Sensor Blur pada Video...", () -> {
                File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
                File out = new File(dir, cleanBase(singleMeta.displayName) + "_SensorBlur_" + System.currentTimeMillis() + ".mp4");
                VideoEditEngine.applyVideoQuickVisualFilter(this, singleVideoUri, workDir, out, vf, null, AUDIO_BR_VALUES[selectedAudioBrIdx]);
                return out;
            });
        }).show();
    }

    private void refreshOverlaySummary() {
        if (tvOverlaySummary == null) return;
        String wm = (renderOpts.watermark != null && renderOpts.watermark.imageUri != null) ? "Aktif" : "Tidak Ada";
        tvOverlaySummary.setText("Status Antrean Saat Ini: " + renderOpts.overlays.size() + " Baris Teks/Subtitle • Logo Watermark: " + wm);
    }

    private void showAddTextOverlayDialog() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 10), UiKit.dp(this, 14), UiKit.dp(this, 6));
        EditText txtE = makeLabeledInput(box, "Tulisan Teks:", "Judul Video");
        EditText sE = makeLabeledInput(box, "Muncul Mulai Detik (IN):", AudioDspEngine.formatMs(activeInMs));
        EditText eE = makeLabeledInput(box, "Hilang pada Detik (OUT):", AudioDspEngine.formatMs(activeOutMs > activeInMs ? activeOutMs : 5000L));
        UiKit.dialog(this, uiPrefs).setTitle("Tambah Teks ke Video").setView(box).setNegativeButton("Batal", null)
                .setPositiveButton("Tambah ke Antrean", (d, w) -> {
                    String t = txtE.getText().toString().trim(); if (t.isEmpty()) return;
                    VideoEditEngine.TextOverlayItem ov = new VideoEditEngine.TextOverlayItem();
                    ov.text = t;
                    ov.startMs = AudioDspEngine.parseTimeToMs(sE.getText().toString(), singleMeta.durationMs);
                    ov.endMs = AudioDspEngine.parseTimeToMs(eE.getText().toString(), singleMeta.durationMs);
                    if (ov.endMs <= ov.startMs) ov.endMs = ov.startMs + 4000L;
                    renderOpts.overlays.add(ov);
                    refreshOverlaySummary();
                }).show();
    }

    private void showWatermarkOrClearDialog() {
        UiKit.dialog(this, uiPrefs).setTitle("Watermark Logo & Pengelola Antrean")
                .setItems(new String[]{
                        "1. Pilih Gambar Logo / Watermark PNG (Pasang di Pojok Kanan Atas)",
                        "2. Pilih Gambar Logo / Watermark PNG (Pasang di Pojok Kiri Atas)",
                        "3. Pilih Gambar Logo / Watermark PNG (Pasang di Pojok Kanan Bawah)",
                        "4. Hapus Semua Antrean Teks, Subtitle & Watermark"
                }, (d, w) -> {
                    if (w >= 0 && w <= 2) {
                        uiPrefs.edit().putInt("ved_wm_pos", w == 0 ? 1 : (w == 1 ? 0 : 3)).apply();
                        OfficeUtils.launchFilePicker(this, REQ_PICK_WATERMARK, "image", "Pilih Gambar Logo PNG");
                    } else {
                        renderOpts.overlays.clear();
                        renderOpts.watermark = null;
                        refreshOverlaySummary();
                    }
                }).show();
    }

    private void executeRenderVisualNow() {
        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        syncRenderOptionsWithExportPrefs();
        long s = hasMarkedIn ? activeInMs : 0L;
        long e = (hasMarkedOut && activeOutMs > s) ? activeOutMs : singleMeta.durationMs;
        if (e <= s) { s = 0L; e = singleMeta.durationMs; }
        final long fs = s, fe = e;
        runSingleVideoJob("Merender Video MP4 (" + getExportQualitySummary() + ")...", () -> {
            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
            File out = new File(dir, cleanBase(singleMeta.displayName) + "_Render_" + System.currentTimeMillis() + ".mp4");
            VideoEditEngine.renderVisualVideo(this, singleVideoUri, workDir, out, fs, fe, renderOpts);
            return out;
        });
    }

    // ==================== DUBBING MIC & AUTO-SUBTITLE AI ====================

    private void startVideoDubbingRecorder() {
        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM_MIC);
            return;
        }
        stopVideoPlayback();
        final boolean[] rec = new boolean[]{true};
        final File wav = new File(workDir, "vdub_" + System.currentTimeMillis() + ".wav");
        final long t0 = System.currentTimeMillis();
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 8));
        TextView tv = new TextView(this);
        tv.setText("Sedang Merekam Suara Dubbing... 00:00");
        tv.setTextSize(17); tv.setTypeface(null, Typeface.BOLD); tv.setTextColor(UiKit.textPrimary(uiPrefs));
        CheckBox cbKeep = new CheckBox(this);
        cbKeep.setText("Campur dengan suara asli video (suara asli dikecilkan ke 30%)");
        cbKeep.setTextColor(UiKit.textPrimary(uiPrefs));
        cbKeep.setChecked(false);
        box.addView(tv); box.addView(cbKeep);

        AlertDialog dlg = UiKit.dialog(this, uiPrefs).setTitle("Rekam Voiceover / Dubbing Video").setView(box).setCancelable(false)
                .setNegativeButton("Batal Rekam", (d, w) -> { rec[0] = false; try { wav.delete(); } catch (Throwable ignored) {} })
                .setPositiveButton("Selesai & Pasang ke Video", (d, w) -> rec[0] = false).show();
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override public void run() {
                if (!rec[0] || !dlg.isShowing()) return;
                long s = (System.currentTimeMillis() - t0) / 1000L;
                tv.setText(String.format(Locale.US, "Sedang Merekam Suara Dubbing... %02d:%02d", s / 60, s % 60));
                h.postDelayed(this, 400L);
            }
        });
        new Thread(() -> {
            int sr = 44100;
            int minB = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord ar = null; FileOutputStream fos = null; long written = 0L;
            try {
                ar = new AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minB * 2, 8192));
                fos = new FileOutputStream(wav, false); fos.write(new byte[44]); ar.startRecording();
                byte[] buf = new byte[4096];
                while (rec[0]) {
                    int n = ar.read(buf, 0, buf.length);
                    if (n > 0) {
                        byte[] st = new byte[n * 2];
                        for (int i = 0; i + 1 < n; i += 2) {
                            st[i * 2] = buf[i]; st[i * 2 + 1] = buf[i + 1]; st[i * 2 + 2] = buf[i]; st[i * 2 + 3] = buf[i + 1];
                        }
                        fos.write(st); written += st.length;
                    }
                }
                fos.flush(); fos.close(); fos = null;
                try (RandomAccessFile raf = new RandomAccessFile(wav, "rw")) { AudioDspEngine.writeWavHeader(raf, sr, 2, written); }
                if (written > 4000L && wav.exists()) {
                    boolean keep = cbKeep.isChecked();
                    runOnUiThread(() -> {
                        AudioDspEngine.MixTrackSpec tr = new AudioDspEngine.MixTrackSpec();
                        tr.uri = Uri.fromFile(wav); tr.title = "Rekaman Dubbing"; tr.volume = 1.0f;
                        List<AudioDspEngine.MixTrackSpec> list = new ArrayList<>(); list.add(tr);
                        runSingleVideoJob("Memasang Voiceover Dubbing ke Video...", () -> {
                            File dir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
                            File out = new File(dir, cleanBase(singleMeta.displayName) + "_Dubbing_" + System.currentTimeMillis() + ".mp4");
                            VideoEditEngine.mixMultiAudioOntoVideo(this, singleVideoUri, keep, 0.3f, list, false, workDir, out);
                            return out;
                        });
                    });
                }
            } catch (Throwable ignored) {
            } finally {
                if (fos != null) { try { fos.close(); } catch (Throwable ignored) {} }
                if (ar != null) { try { ar.stop(); } catch (Throwable ignored) {} try { ar.release(); } catch (Throwable ignored) {} }
            }
        }).start();
    }

    private void generateAiSubtitleForVideo() {
        if (singleVideoUri == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        String key = AiEngine.resolveApiKey(this, uiPrefs, "Google Gemini");
        if (key == null || key.trim().isEmpty()) { Toast.makeText(this, "Membutuhkan API Key Gemini di menu Otak AI.", Toast.LENGTH_LONG).show(); return; }
        runBusyVoid("AI Gemini Sedang Membuat Subtitle Video...", () -> {
            File m4a = new File(workDir, "sub_" + System.currentTimeMillis() + ".m4a");
            AudioDspEngine.trimAudio(this, singleVideoUri, workDir, m4a, 0L, 0L, "m4a", 64);
            if (m4a.length() > 12L * 1024L * 1024L) {
                try { m4a.delete(); } catch (Throwable ignored) {}
                throw new IllegalStateException("Audio video melebihi 12 MB. Potong terlebih dahulu video menjadi bagian yang lebih pendek.");
            }
            byte[] rawBytes;
            try (FileInputStream fis = new FileInputStream(m4a)) {
                rawBytes = new byte[(int) m4a.length()]; int off = 0, n;
                while (off < rawBytes.length && (n = fis.read(rawBytes, off, rawBytes.length - off)) != -1) off += n;
            } finally {
                try { m4a.delete(); } catch (Throwable ignored) {}
            }
            String b64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP);
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("text", "Buatkan subtitle JSON array murni berisi {start_sec, end_sec, text} sesuai ucapan di audio ini dalam bahasa aslinya."));
            parts.put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", "audio/mp4").put("data", b64)));
            JSONObject req = new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)));
            String raw = AiEngine.httpPostJson(
                    new URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + key),
                    null, req.toString());
            String txt = new JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
            int sIdx = txt.indexOf('['), eIdx = txt.lastIndexOf(']');
            if (sIdx < 0 || eIdx <= sIdx) throw new IllegalStateException("Format subtitle AI tidak valid.");
            JSONArray arr = new JSONArray(txt.substring(sIdx, eIdx + 1));
            int count = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i); if (o == null) continue;
                String line = o.optString("text", "").trim(); if (line.isEmpty()) continue;
                VideoEditEngine.TextOverlayItem ov = new VideoEditEngine.TextOverlayItem();
                ov.text = line;
                ov.startMs = Math.round(o.optDouble("start_sec", 0) * 1000.0);
                ov.endMs = Math.round(o.optDouble("end_sec", 2) * 1000.0);
                renderOpts.overlays.add(ov); count++;
            }
            final int total = count;
            runOnUiThread(() -> {
                refreshOverlaySummary();
                Toast.makeText(this, total + " baris subtitle AI berhasil ditambahkan. Tekan tombol Bakar Subtitle ke Video untuk menyimpan.", Toast.LENGTH_LONG).show();
            });
        });
    }

    // ==================== PEMUTAR UTAMA (OTOMATIS PUTAR POTONGAN & BUNYIKAN LIVE SFX) ====================

    private void togglePlayPause() {
        if (singleVideoUri == null || videoView == null) { Toast.makeText(this, "Pilih berkas video terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        if (isPlaying) {
            try { videoView.pause(); } catch (Throwable ignored) {}
            stopAllCuePlayers();
            isPlaying = false;
            if (btnPlayPause != null) btnPlayPause.setText("Putar");
            mainHandler.removeCallbacks(ticker);
        } else {
            try {
                long cur = getPlayheadMs();
                long startBound = hasMarkedIn ? activeInMs : 0L;
                long endBound = (hasMarkedOut && activeOutMs > startBound) ? activeOutMs : singleMeta.durationMs;
                if (cur < startBound || cur >= endBound - 150L) {
                    videoView.seekTo((int) startBound);
                    cur = startBound;
                }
                lastTickerPosMs = cur - 50L;
                for (DramaSoundPaletteHelper.CueItem cue : liveSfxCues) {
                    cue.lastTriggeredPlaybackMs = -5000L;
                }
                videoView.start();
                isPlaying = true;
                if (btnPlayPause != null) btnPlayPause.setText("Jeda");
                DramaSoundPaletteHelper.syncCuesWithPlayhead(this, liveSfxCues, lastTickerPosMs, cur, true);
                mainHandler.removeCallbacks(ticker);
                mainHandler.post(ticker);
            } catch (Throwable ignored) {}
        }
    }

    private void stopVideoPlayback() {
        isPlaying = false;
        stopAllCuePlayers();
        mainHandler.removeCallbacks(ticker);
        if (btnPlayPause != null) btnPlayPause.setText("Putar");
        if (videoView != null) { try { if (videoView.isPlaying()) videoView.pause(); } catch (Throwable ignored) {} }
    }

    private long getPlayheadMs() {
        if (videoView == null) return 0L;
        try { return Math.max(0L, videoView.getCurrentPosition()); } catch (Throwable ignored) { return 0L; }
    }

    private void seekAbsolute(long ms) {
        if (videoView == null || singleVideoUri == null) return;
        long t = Math.max(0L, Math.min(singleMeta.durationMs, ms));
        try { videoView.seekTo((int) t); } catch (Throwable ignored) {}
        lastTickerPosMs = t;
        updatePlayheadUi();
    }

    private void nudgePlayhead(long delta) {
        if (singleVideoUri == null) return;
        long t = Math.max(0L, Math.min(singleMeta.durationMs, getPlayheadMs() + delta));
        for (DramaSoundPaletteHelper.CueItem cue : liveSfxCues) {
            if (cue.atMs >= t) cue.lastTriggeredPlaybackMs = -5000L;
        }
        seekAbsolute(t);
        if (isPlaying) {
            DramaSoundPaletteHelper.syncCuesWithPlayhead(this, liveSfxCues, t - 50L, t, true);
        }
        OfficeUtils.announce(tvPlayPos, (delta < 0 ? "Mundur ke " : "Maju ke ") + AudioDspEngine.formatMsShort(t));
    }

    private void updatePlayheadUi() {
        if (tvPlayPos == null) return;
        long pos = getPlayheadMs();

        if (isPlaying && hasMarkedOut && activeOutMs > activeInMs && pos >= activeOutMs) {
            try {
                videoView.pause();
                videoView.seekTo((int) activeInMs);
            } catch (Throwable ignored) {}
            stopAllCuePlayers();
            isPlaying = false;
            if (btnPlayPause != null) btnPlayPause.setText("Putar");
            mainHandler.removeCallbacks(ticker);
            pos = activeInMs;
        }

        if (isPlaying && activeFeatureId == 2 && !liveSfxCues.isEmpty()) {
            DramaSoundPaletteHelper.syncCuesWithPlayhead(this, liveSfxCues, lastTickerPosMs, pos, true);
            for (DramaSoundPaletteHelper.CueItem cue : liveSfxCues) {
                if (cue.isBacksoundLoop) continue;
                if (pos >= cue.atMs && lastTickerPosMs < cue.atMs && Math.abs(pos - cue.lastTriggeredPlaybackMs) > 400L) {
                    cue.lastTriggeredPlaybackMs = pos;
                    triggerOneCuePreview(cue);
                }
            }
        }
        lastTickerPosMs = pos;

        tvPlayPos.setText("Posisi Putar: " + AudioDspEngine.formatMs(pos) + " dari " + AudioDspEngine.formatMs(singleMeta.durationMs));
        if (videoSeek != null && singleMeta.durationMs > 0) {
            videoSeek.setProgress((int) Math.min(1000L, (pos * 1000L) / singleMeta.durationMs));
            videoSeek.setContentDescription("Posisi video " + AudioDspEngine.formatMsShort(pos) + " dari " + AudioDspEngine.formatMsShort(singleMeta.durationMs));
        }
    }

    // ==================== JOB RUNNERS & PICKER ====================

    private interface SingleJob { File run() throws Exception; }
    private interface MultiJob { List<File> run() throws Exception; }
    private interface VoidJob { void run() throws Exception; }

    @SuppressWarnings("deprecation")
    private void runSingleVideoJob(String title, SingleJob job) {
        stopVideoPlayback();
        ProgressDialog pd = ProgressDialog.show(this, title, "Sedang memproses video dengan mesin FFmpeg...", true, false);
        worker.submit(() -> {
            try {
                File out = job.run();
                mainHandler.post(() -> { if (pd.isShowing()) pd.dismiss(); showSavedDialog("Video Selesai Diproses", out, "video/mp4"); });
            } catch (Throwable t) {
                mainHandler.post(() -> { if (pd.isShowing()) pd.dismiss(); Toast.makeText(this, "Gagal: " + t.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void runMultiVideoJob(String title, MultiJob job) {
        stopVideoPlayback();
        ProgressDialog pd = ProgressDialog.show(this, title, "Sedang memproses potongan MP4...", true, false);
        worker.submit(() -> {
            try {
                List<File> list = job.run();
                mainHandler.post(() -> { if (pd.isShowing()) pd.dismiss(); showMultiSavedDialog(list); });
            } catch (Throwable t) {
                mainHandler.post(() -> { if (pd.isShowing()) pd.dismiss(); Toast.makeText(this, "Gagal: " + t.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void runBusyVoid(String title, VoidJob job) {
        ProgressDialog pd = ProgressDialog.show(this, title, "Mohon tunggu sebentar...", true, false);
        worker.submit(() -> {
            try {
                job.run();
                mainHandler.post(() -> { if (pd.isShowing()) pd.dismiss(); });
            } catch (Throwable t) {
                mainHandler.post(() -> { if (pd.isShowing()) pd.dismiss(); Toast.makeText(this, "Gagal: " + t.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private void showSavedDialog(String title, File out, String mime) {
        if (tvVideoInfo != null) {
            OfficeUtils.announce(tvVideoInfo, title + ". Disimpan dengan nama " + out.getName());
        }
        AlertDialog.Builder b = UiKit.dialog(this, uiPrefs)
                .setTitle(title)
                .setMessage("Berkas berhasil disimpan ke:\n" + out.getAbsolutePath()
                        + "\n\nUkuran Berkas: " + fmtSize(out.length()))
                .setNeutralButton("Bagikan ke WhatsApp / Aplikasi Lain", (d, w) -> shareFile(out, mime))
                .setNegativeButton("Tutup", null);
        if (mime.startsWith("video")) {
            b.setPositiveButton("Muat Hasil untuk Diedit Lagi", (d, w) -> {
                setSingleVideoInstant(Uri.fromFile(out));
                renderCurrentScreen();
            });
        }
        b.show();
    }

    private void showMultiSavedDialog(List<File> files) {
        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            labels[i] = "Bagian " + (i + 1) + ": " + files.get(i).getName() + " (" + fmtSize(files.get(i).length()) + ")";
        }
        UiKit.dialog(this, uiPrefs)
                .setTitle("Selesai (" + files.size() + " Berkas Video) — Ketuk Item untuk Membagikan")
                .setItems(labels, (d, which) -> shareFile(files.get(which), "video/mp4"))
                .setPositiveButton("Tutup", null).show();
    }

    private void shareFile(File f, String mime) {
        try {
            Uri u = AudioDspEngine.prepareShareUri(this, f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime); i.putExtra(Intent.EXTRA_STREAM, u);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Bagikan " + f.getName()));
        } catch (Throwable t) {
            Toast.makeText(this, "Gagal membagikan berkas: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private EditText makeLabeledInput(LinearLayout parent, String label, String def) {
        TextView tv = new TextView(this); tv.setText(label); tv.setTextColor(UiKit.textPrimary(uiPrefs));
        tv.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 2)); parent.addView(tv);
        EditText et = new EditText(this); et.setText(def);
        et.setContentDescription(label);
        UiKit.styleEditText(this, et, uiPrefs);
        parent.addView(et); return et;
    }

    private void launchSysPicker(String mime, int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_PICK_VID_FM || requestCode == REQ_PICK_VID_SYS) {
            setSingleVideoInstant(uri);
            renderCurrentScreen();
            Toast.makeText(this, "Video dimuat: " + singleMeta.displayName, Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_PICK_MERGE_FM || requestCode == REQ_PICK_MERGE_SYS) {
            VideoEditEngine.VideoMeta m = VideoEditEngine.inspectVideo(this, uri);
            VidSlot s = new VidSlot(); s.uri = uri; s.title = m.displayName; s.durationMs = m.durationMs;
            mergeVideos.add(s); rebuildMergeListUi();
        } else if (requestCode == REQ_PICK_AUD_FM || requestCode == REQ_PICK_AUD_SYS) {
            AudioDspEngine.AudioMeta m = AudioDspEngine.inspectMedia(this, uri);
            AudioDspEngine.MixTrackSpec tr = new AudioDspEngine.MixTrackSpec();
            tr.uri = uri; tr.title = m.displayName; tr.volume = 0.35f; tr.loop = true;
            extraVideoAudios.add(tr); rebuildVidAudioListUi();
        } else if (requestCode == REQ_PICK_SLIDE_IMG) {
            ImgSlot s = new ImgSlot(); s.uri = uri; s.title = AudioDspEngine.resolveDisplayName(this, uri);
            slidePhotos.add(s); rebuildSlidePhotoUi();
        } else if (requestCode == REQ_PICK_SLIDE_BGM) {
            slideBgmUri = uri;
            slideBgmTitle = AudioDspEngine.resolveDisplayName(this, uri);
            if (tvSlideBgm != null) tvSlideBgm.setText("Backsound Aktif: " + slideBgmTitle);
        } else if (requestCode == REQ_PICK_SRT) {
            runBusyVoid("Memuat Berkas Subtitle .SRT...", () -> {
                List<VideoEditEngine.TextOverlayItem> items = VideoEditEngine.parseSrtOverlays(this, uri);
                renderOpts.overlays.addAll(items);
                runOnUiThread(() -> {
                    refreshOverlaySummary();
                    Toast.makeText(this, items.size() + " baris subtitle .SRT berhasil ditambahkan.", Toast.LENGTH_SHORT).show();
                });
            });
        } else if (requestCode == REQ_PICK_WATERMARK) {
            VideoEditEngine.WatermarkConfig wm = new VideoEditEngine.WatermarkConfig();
            wm.imageUri = uri;
            wm.position = uiPrefs.getInt("ved_wm_pos", 1);
            renderOpts.watermark = wm;
            refreshOverlaySummary();
            Toast.makeText(this, "Logo watermark PNG dipasang.", Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_PICK_COVER_IMG_FM || requestCode == REQ_PICK_COVER_IMG_SYS) {
            coverImageUri = uri;
            coverImageName = AudioDspEngine.resolveDisplayName(this, uri);
            renderCurrentScreen();
            Toast.makeText(this, "Foto Cover dipilih: " + coverImageName, Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_PICK_SPLIT_VID2_FM || requestCode == REQ_PICK_SPLIT_VID2_SYS) {
            splitVid2Uri = uri;
            splitVid2Meta = VideoEditEngine.inspectVideo(this, uri);
            renderCurrentScreen();
            Toast.makeText(this, "Video kedua dipilih: " + splitVid2Meta.displayName, Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_PICK_LIVE_SFX_FM || requestCode == REQ_PICK_LIVE_SFX_SYS) {
            final String name = AudioDspEngine.resolveDisplayName(this, uri);
            UiKit.dialog(this, uiPrefs)
                    .setTitle("Pasang \"" + name + "\" Sebagai Apa?")
                    .setItems(new String[]{
                            "1. Efek Suara (Tempel di Detik Video Saat Ini)",
                            "2. Backsound Musik (Ikuti Pilihan Mode Full / Detik Ini / Potong)"
                    }, (d, w) -> {
                        if (paletteController != null) {
                            paletteController.addPhoneCue(uri, name, w == 1);
                        }
                    }).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_MIC && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVideoDubbingRecorder();
        }
    }

    private static double safeDouble(String raw, double def) {
        try { return Double.parseDouble(raw.trim().replace(',', '.')); } catch (Throwable t) { return def; }
    }

    private static String fmtSize(long b) {
        if (b < 1024L) return b + " B";
        if (b < 1048576L) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        return String.format(Locale.ROOT, "%.2f MB", b / 1048576.0);
    }

    private static String cleanBase(String n) {
        if (n == null) return "Video";
        int d = n.lastIndexOf('.');
        String b = d > 0 ? n.substring(0, d) : n;
        String c = b.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return c.isEmpty() ? "Video" : c;
    }

    @Override
    protected void onDestroy() {
        stopVideoPlayback();
        StudioGudangAudioManager.stopPreview();
        OfficeUtils.clearDirectoryContents(workDir);
        worker.shutdownNow();
        super.onDestroy();
    }
}
