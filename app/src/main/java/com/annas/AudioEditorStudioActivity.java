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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

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
 * Studio Editor Audio Pro (FFmpeg 8.1 Native C/NEON).
 * - Ketika dibuka langsung menyuguhkan 36 fitur berbeda fungsi (tanpa memaksa pilih audio di awal).
 * - 1 Fitur = 1 Halaman Khusus Murni (tidak mencampur tombol fitur lain di halaman yang sama).
 * - Khusus lintas media di Audio Editor hanya ada "Audio ke Video MP4 + Foto Cover".
 * - Pemutar simpel (Mundur, Putar/Jeda, Maju) + Potong sambil Play dengan Undo/Redo bertingkat
 *   (langsung memutar rentang titik Awal-Akhir lewat tombol Putar utama di atas tanpa tombol pratinjau terpisah).
 * - Studio Efek Suara Drama/Film & Backsound Sambil Putar (tempel Kaca Pecah, Tabrakan, Ledakan, dll.
 *   tepat di detik berjalan dan langsung ikut berbunyi saat di-Play + Undo/Redo).
 * - Terintegrasi penuh dengan Studio Gudang & Laboratorium Audio (Backsound 1-2 menit & 32 Efek Film).
 */
public class AudioEditorStudioActivity extends Activity {

    public static final String EXTRA_AUDIO_URI = "extra_audio_uri";

    private static final int FEATURE_HUB = 0;

    private static final int REQ_PICK_SINGLE_FM = 9301;
    private static final int REQ_PICK_SINGLE_SYS = 9302;
    private static final int REQ_PICK_ADD_MERGE_FM = 9303;
    private static final int REQ_PICK_ADD_MERGE_SYS = 9304;
    private static final int REQ_PICK_ADD_MIX_FM = 9305;
    private static final int REQ_PICK_ADD_MIX_SYS = 9306;
    private static final int REQ_PERM_MIC = 9308;
    private static final int REQ_PICK_COVER_IMG_FM = 9309;
    private static final int REQ_PICK_COVER_IMG_SYS = 9310;
    private static final int REQ_PICK_LIVE_SFX_FM = 9313;
    private static final int REQ_PICK_LIVE_SFX_SYS = 9314;

    private static final String[] FORMAT_EXTENSIONS = {"mp3", "m4a", "wav", "flac", "ogg", "opus"};
    private static final String[] FORMAT_LABELS = {
            "MP3 Universal (Kompatibel Semua HP & Pemutar)",
            "M4A / AAC Studio (Jernih & Ukuran Efisien)",
            "WAV Studio PCM 16-Bit (Tanpa Kompresi)",
            "FLAC Lossless (Kualitas Master Tanpa Hilang Detail)",
            "OGG Vorbis (Audio Terbuka Jernih)",
            "OPUS Voice & Musik (Sangat Hemat Ukuran)"
    };

    private static final int[] BITRATE_VALUES = {64, 96, 128, 160, 192, 256, 320};
    private static final String[] BITRATE_LABELS = {
            "64 kbps (Suara Bicara / Sangat Hemat Kuota)",
            "96 kbps (Rekaman Ceramah & Voice Note Ringan)",
            "128 kbps (Standar MP3 & Musik Harian)",
            "160 kbps (Kualitas Menengah Jernih)",
            "192 kbps (Kualitas Tinggi Studio — Direkomendasikan)",
            "256 kbps (Kualitas Musik Premium)",
            "320 kbps (Kualitas Maksimal Master MP3/AAC)"
    };

    private static final int[] SAMPLE_RATE_VALUES = {44100, 48000, 24000, 16000};
    private static final String[] SAMPLE_RATE_LABELS = {
            "44100 Hz (Standar CD & Musik Universal)",
            "48000 Hz (Standar Audio Video & Studio Pro)",
            "24000 Hz (Standar Suara AI & Podcast)",
            "16000 Hz (Suara Bicara Ringkas)"
    };

    private static final int[] CHANNEL_VALUES = {2, 1};
    private static final String[] CHANNEL_LABELS = {
            "Stereo (2 Kanal Kiri dan Kanan)",
            "Mono (1 Kanal Fokus Tengah)"
    };

    private SharedPreferences uiPrefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private File workDir;

    // 0 = Hub Utama, 1..36 = Halaman Khusus Masing-Masing Fitur Tunggal
    private int activeFeatureId = FEATURE_HUB;

    private int selectedFormatIdx = 1;     // M4A / AAC Hemat & Jernih
    private int selectedBitrateIdx = 2;    // 128 kbps
    private int selectedSampleRateIdx = 0; // 44100 Hz
    private int selectedChannelIdx = 0;    // Stereo

    // Berkas audio aktif
    private Uri singleUri;
    private AudioDspEngine.AudioMeta singleMeta = new AudioDspEngine.AudioMeta();
    private EditText etInTime, etOutTime;
    private TextView tvSingleInfo, tvPlayPos, tvCutRangeBadge;
    private SeekBar seekBar;
    private Button btnPlayPause, btnUndoCut, btnRedoCut;

    // State Pemotongan Sambil Play + Undo / Redo Bertingkat
    private static final class CutState {
        final long inMs;
        final long outMs;
        final boolean hasIn;
        final boolean hasOut;
        final String label;
        CutState(long inMs, long outMs, boolean hasIn, boolean hasOut, String label) {
            this.inMs = inMs;
            this.outMs = outMs;
            this.hasIn = hasIn;
            this.hasOut = hasOut;
            this.label = label;
        }
    }
    private long activeInMs = 0L;
    private long activeOutMs = 0L;
    private boolean hasMarkedIn = false;
    private boolean hasMarkedOut = false;
    private final ArrayList<CutState> cutUndoStack = new ArrayList<>();
    private final ArrayList<CutState> cutRedoStack = new ArrayList<>();

    // State Studio Efek Drama/Film & Backsound Sambil Putar (Tab Centang + Live Play + Undo/Redo)
    private final ArrayList<DramaSoundPaletteHelper.CueItem> liveSfxCues = new ArrayList<>();
    private final ArrayList<ArrayList<DramaSoundPaletteHelper.CueItem>> sfxUndoStack = new ArrayList<>();
    private final ArrayList<ArrayList<DramaSoundPaletteHelper.CueItem>> sfxRedoStack = new ArrayList<>();
    private DramaSoundPaletteHelper.Controller paletteController;
    private final ArrayList<MediaPlayer> activeCuePlayers = new ArrayList<>();
    private long lastTickerPosMs = -1L;
    private float swipeStartX = 0f, swipeStartY = 0f;
    private boolean swipeTracking = false, swipeTriggered = false;

    // State Gabung Banyak Audio
    private static final class ClipSlot {
        Uri uri;
        String title;
        long durationMs;
    }
    private final ArrayList<ClipSlot> mergeClips = new ArrayList<>();
    private LinearLayout mergeListContainer;
    private EditText etMergeGap;

    // State Mixer Multi-Track
    private final ArrayList<AudioDspEngine.MixTrackSpec> mixTracks = new ArrayList<>();
    private LinearLayout mixListContainer;
    private CheckBox cbMixDucking;

    // State Audio ke Video & Foto Cover
    private Uri coverImageUri;
    private String coverImageName = "";

    // Pemutar Utama
    private MediaPlayer player;
    private boolean isPlaying = false;

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
            AuthManager.showPremiumRequiredDialog(this, "Studio Editor Audio Pro", null);
            Toast.makeText(this, "Fitur Studio Editor Audio Pro khusus Anggota Premium.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        selectedFormatIdx = Math.max(0, Math.min(FORMAT_EXTENSIONS.length - 1, uiPrefs.getInt("aed_fmt_idx", 1)));
        selectedBitrateIdx = Math.max(0, Math.min(BITRATE_VALUES.length - 1, uiPrefs.getInt("aed_br_idx", 2)));
        selectedSampleRateIdx = Math.max(0, Math.min(SAMPLE_RATE_VALUES.length - 1, uiPrefs.getInt("aed_sr_idx", 0)));
        selectedChannelIdx = Math.max(0, Math.min(CHANNEL_VALUES.length - 1, uiPrefs.getInt("aed_ch_idx", 0)));

        workDir = new File(getCacheDir(), "ffmpeg_audio_work");
        if (!workDir.exists()) workDir.mkdirs();

        Uri initial = getIntent() != null ? getIntent().getData() : null;
        if (initial == null && getIntent() != null) {
            String extra = getIntent().getStringExtra(EXTRA_AUDIO_URI);
            if (extra != null && !extra.isEmpty()) initial = Uri.parse(extra);
        }
        if (initial != null) {
            setSingleUriInstant(initial);
        }
        renderCurrentScreen();
    }

    private void saveExportPrefs() {
        uiPrefs.edit()
                .putInt("aed_fmt_idx", selectedFormatIdx)
                .putInt("aed_br_idx", selectedBitrateIdx)
                .putInt("aed_sr_idx", selectedSampleRateIdx)
                .putInt("aed_ch_idx", selectedChannelIdx)
                .apply();
    }

    private String getActiveFormatExt() {
        return FORMAT_EXTENSIONS[Math.max(0, Math.min(FORMAT_EXTENSIONS.length - 1, selectedFormatIdx))];
    }

    private int getActiveBitrateKbps() {
        return BITRATE_VALUES[Math.max(0, Math.min(BITRATE_VALUES.length - 1, selectedBitrateIdx))];
    }

    private int getActiveSampleRate() {
        return SAMPLE_RATE_VALUES[Math.max(0, Math.min(SAMPLE_RATE_VALUES.length - 1, selectedSampleRateIdx))];
    }

    private int getActiveChannels() {
        return CHANNEL_VALUES[Math.max(0, Math.min(CHANNEL_VALUES.length - 1, selectedChannelIdx))];
    }

    private String getExportSummaryText() {
        String fmt = getActiveFormatExt().toUpperCase(Locale.ROOT);
        int br = getActiveBitrateKbps();
        int sr = getActiveSampleRate();
        String ch = getActiveChannels() == 1 ? "Mono" : "Stereo";
        if ("WAV".equals(fmt) || "FLAC".equals(fmt)) {
            return fmt + " Lossless • " + sr + " Hz • " + ch;
        }
        return fmt + " • " + br + " kbps • " + sr + " Hz • " + ch;
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (activeFeatureId != FEATURE_HUB && singleUri != null && ev.getPointerCount() == 1) {
            int action = ev.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                boolean onSeekBar = false;
                if (seekBar != null && seekBar.getVisibility() == View.VISIBLE) {
                    int[] loc = new int[2];
                    seekBar.getLocationOnScreen(loc);
                    float rx = ev.getRawX(), ry = ev.getRawY();
                    if (rx >= loc[0] && rx <= loc[0] + seekBar.getWidth()
                            && ry >= loc[1] - 24 && ry <= loc[1] + seekBar.getHeight() + 24) {
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
                if (Math.abs(dy) > UiKit.dp(this, 24) && Math.abs(dy) >= Math.abs(dx)) {
                    swipeTracking = false;
                } else {
                    float thresh = UiKit.dp(this, 90);
                    if (Math.abs(dx) > thresh && Math.abs(dx) > Math.abs(dy) * 2.0f) {
                        swipeTriggered = true;
                        android.view.MotionEvent cancelEv = android.view.MotionEvent.obtain(ev);
                        cancelEv.setAction(android.view.MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancelEv);
                        cancelEv.recycle();
                        nudgePlayhead(dx < 0 ? -5000L : 5000L);
                        return true;
                    }
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
            releasePlayer();
            activeFeatureId = FEATURE_HUB;
            renderCurrentScreen();
            return;
        }
        super.onBackPressed();
    }

    private void openFeature(int featureId) {
        if (activeFeatureId == 2 && featureId != 2) {
            clearLiveSfxSession();
        } else {
            StudioGudangAudioManager.stopPreview();
        }
        releasePlayer();
        activeFeatureId = featureId;
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
        releasePlayer();
        if (activeFeatureId == FEATURE_HUB) {
            renderHubScreen();
            return;
        }
        switch (activeFeatureId) {
            case 1: renderAudioToVideoScreen(); break;
            case 2: renderLiveDramaSfxStudioScreen(); break;
            case 3: renderDedicatedCutScreen(false); break; // Trim Awal & Akhir
            case 4: renderDedicatedCutScreen(true); break;  // Buang Bagian Tengah
            case 5: renderDedicatedStatusWaScreen(); break; // Khusus Status WA saja
            case 6: renderDedicatedSplit2Screen(); break;   // Khusus Belah 2 Berkas saja
            case 7: renderDedicatedEqualSplitScreen(); break; // Khusus Bagi Sama Rata saja
            case 8: renderDedicatedRingtoneScreen(); break; // Khusus Nada Dering saja
            case 9: renderMergeScreen(); break;             // Khusus Gabung Banyak Audio
            case 10: renderMixerScreen(); break;            // Khusus Campur Vokal & Backsound
            case 11: renderDedicatedConvertFormatScreen(); break;
            case 12: renderCoverTagScreen(); break;
            case 13: renderDedicatedCompressScreen(); break;
            case 14: renderDedicatedInsertSilenceScreen(); break;
            case 34: renderDedicatedToneGeneratorScreen(); break;
            case 35: startMicRecordingDialog(); activeFeatureId = FEATURE_HUB; break;
            case 36: renderDedicatedStatsTranscribeScreen(); break;
            default:
                renderDedicatedSingleDspToolScreen(activeFeatureId);
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
        head.setText("Studio Editor Audio Pro — Pilih Fitur yang Ingin Digunakan");
        head.setTextSize(19);
        head.setTypeface(null, Typeface.BOLD);
        head.setTextColor(UiKit.textPrimary(uiPrefs));
        root.addView(head);

        TextView sub = new TextView(this);
        sub.setText("Setiap fitur di bawah memiliki halaman khusus tersendiri dan tidak tercampur dengan fitur lain. Pemotongan dan pemasangan efek drama/film dapat dilakukan langsung sambil memutar audio dilengkapi tombol Undo dan Redo.");
        sub.setTextSize(13.5f);
        sub.setTextColor(UiKit.textMuted(uiPrefs));
        sub.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 10));
        root.addView(sub);

        // KATEGORI A: AUDIO KE VIDEO + COVER, STUDIO EFEK DRAMA FILM SAMBIL PUTAR & KONVERSI
        LinearLayout catA = UiKit.makeCard(this, uiPrefs);
        catA.addView(UiKit.makeSectionLabel(this, "KATEGORI A: AUDIO KE VIDEO, EFEK DRAMA FILM SAMBIL PUTAR & KONVERSI", UiKit.ACCENT_AI));

        catA.addView(UiKit.makeMenuButton(this,
                "1. Audio ke Video MP4 + Pasang Foto Cover / Sampul (Audio to Video)",
                "Halaman khusus mengubah berkas audio atau musik menjadi Video MP4 lengkap dengan Foto Cover/Sampul pilihan (latar blur, layar penuh, atau gelombang suara) dalam rasio 16:9 YouTube, 9:16 TikTok/Status WA, atau 1:1.",
                UiKit.ACCENT_AI, uiPrefs, v -> openFeature(1)));

        catA.addView(UiKit.makeMenuButton(this,
                "2. Studio Efek Suara Drama/Film & Backsound Sambil Putar (Kaca Pecah, Tabrakan + Live Play)",
                "Putar audio Anda lalu klik tombol efek film (Kaca Pecah, Tabrakan Mobil, Ledakan, Pukulan, Pedang, Petir, Horor) atau Backsound Musik tepat di detik adegan. Langsung ikut berbunyi saat di-Play dan dilengkapi tombol Undo/Redo.",
                UiKit.ACCENT_WARM, uiPrefs, v -> openFeature(2)));

        catA.addView(UiKit.makeMenuButton(this,
                "3. Konversi Format & Ubah Bitrate Audio (MP3, M4A, WAV, FLAC, OGG, OPUS • 64-320 kbps)",
                "Halaman khusus mengubah format berkas audio, kualitas bitrate (64 sampai 320 kbps), Sample Rate (16000-48000 Hz), dan kanal Stereo/Mono.",
                UiKit.ACCENT_KB, uiPrefs, v -> openFeature(11)));

        catA.addView(UiKit.makeMenuButton(this,
                "4. Pasang Foto Cover / Sampul Album & Edit Tag Metadata MP3 (ID3 Cover Art)",
                "Halaman khusus menyematkan gambar foto sampul ke dalam berkas MP3 serta mengubah Judul Lagu, Nama Artis, dan Album.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> openFeature(12)));

        catA.addView(UiKit.makeMenuButton(this,
                "5. Kompres Ukuran Berkas Audio (Hemat Kuota Kirim WhatsApp / Telegram)",
                "Halaman khusus mengecilkan ukuran berkas rekaman panjang hingga 75 persen lebih ringan tanpa menghilangkan kejelasan suara.",
                UiKit.ACCENT_KB_ALT, uiPrefs, v -> openFeature(13)));
        root.addView(catA);

        // KATEGORI B: POTONG SAMBIL PLAY (UNDO/REDO), STATUS WA, BELAH, GABUNG & MIXER BACKSOUND
        LinearLayout catB = UiKit.makeCard(this, uiPrefs);
        catB.addView(UiKit.makeSectionLabel(this, "KATEGORI B: POTONG SAMBIL PLAY (UNDO/REDO), STATUS WA, GABUNG & MIXER", UiKit.ACCENT_OFFICE));

        catB.addView(UiKit.makeMenuButton(this,
                "6. Potong Audio Sambil Putar + Undo / Redo (Ambil Bagian Awal sampai Akhir)",
                "Halaman khusus pemotongan fleksibel sambil Play: klik Tandai Titik Awal (audio lanjut berputar), klik Tandai Titik Akhir, lalu klik Putar di atas untuk langsung mendengar hasil potongan. Salah potong tinggal klik Undo (1x batal Akhir, 2x batal Awal) atau Redo.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> openFeature(3)));

        catB.addView(UiKit.makeMenuButton(this,
                "7. Buang Bagian Tengah Audio Sambil Putar + Undo / Redo (Hapus Salah Bicara)",
                "Halaman khusus untuk menandai dan membuang bagian tengah yang salah ucap atau batuk, lalu menyambung otomatis bagian depan dan belakang.",
                UiKit.ACCENT_DANGER, uiPrefs, v -> openFeature(4)));

        catB.addView(UiKit.makeMenuButton(this,
                "8. Pecah Audio Otomatis Khusus untuk Status WhatsApp (Per 15 / 30 / 60 / 90 Detik)",
                "Halaman murni khusus Status WhatsApp (tanpa dicampur tombol potong lain). Pilih audio, pilih durasi Status WA, langsung pecah otomatis menjadi Part 1, Part 2, dan seterusnya.",
                UiKit.ACCENT_KB_ALT, uiPrefs, v -> openFeature(5)));

        catB.addView(UiKit.makeMenuButton(this,
                "9. Belah Audio Menjadi 2 Berkas Terpisah di Titik Pilihan",
                "Halaman khusus membelah satu berkas audio tepat pada detik yang Anda tandai menjadi Bagian 1 dan Bagian 2.",
                UiKit.ACCENT_WARM, uiPrefs, v -> openFeature(6)));

        catB.addView(UiKit.makeMenuButton(this,
                "10. Bagi Audio Menjadi Banyak Bagian Sama Rata (Split 2 s/d 30 Bagian)",
                "Halaman khusus membagi satu rekaman panjang menjadi beberapa berkas dengan durasi yang sama rata.",
                UiKit.ACCENT_KB, uiPrefs, v -> openFeature(7)));

        catB.addView(UiKit.makeMenuButton(this,
                "11. Pembuat Nada Dering HP (Ringtone Maker 30 Detik + Fade Halus)",
                "Halaman khusus membuat potongan nada dering maksimal 30 detik lengkap dengan efek suara masuk dan keluar halus.",
                UiKit.ACCENT_AI_ALT, uiPrefs, v -> openFeature(8)));

        catB.addView(UiKit.makeMenuButton(this,
                "12. Gabung Banyak Audio + Backsound Musik (Audio 1 + Audio 2 + Audio 3 dst.)",
                "Halaman khusus menyambung banyak audio berurutan. Bisa menambah audio dari memori HP sendiri maupun dari koleksi Backsound/Efek Aplikasi.",
                UiKit.ACCENT_KB, uiPrefs, v -> openFeature(9)));

        catB.addView(UiKit.makeMenuButton(this,
                "13. Campur Vokal & Backsound Musik Latar (Multi-Track Mixer + Auto-Ducking)",
                "Halaman khusus mencampur suara bicara utama dengan Backsound Musik (bisa pilih kategori Islami, Piano Santai, Jazz, Rock, Jedag-Jedug, Tegang dari Aplikasi atau musik sendiri dari HP) dengan Auto-Ducking.",
                UiKit.ACCENT_AI, uiPrefs, v -> openFeature(10)));

        catB.addView(UiKit.makeMenuButton(this,
                "14. Sisipkan Jeda Hening (Insert Silence) pada Detik Pilihan",
                "Halaman khusus menambahkan durasi diam hening di awal, tengah, atau akhir rekaman.",
                UiKit.ACCENT_SYSTEM, uiPrefs, v -> openFeature(14)));
        root.addView(catB);

        // KATEGORI C: VOLUME, MASTERING PODCAST, KEJERNIHAN VOKAL & PEMBERSIH NOISE
        LinearLayout catC = UiKit.makeCard(this, uiPrefs);
        catC.addView(UiKit.makeSectionLabel(this, "KATEGORI C: VOLUME, MASTERING PODCAST & PEMBERSIH NOISE", UiKit.ACCENT_WARM));

        catC.addView(UiKit.makeMenuButton(this,
                "15. Perkeras atau Kecilkan Volume Audio Manual (Gain -24 dB s/d +24 dB)",
                "Halaman khusus menaikkan atau menurunkan kekerasan suara dalam satuan desibel (dB).",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> openFeature(15)));

        catC.addView(UiKit.makeMenuButton(this,
                "16. Standarisasi Volume Siaran & Musik (Loudnorm -14 LUFS Standar YouTube/Spotify)",
                "Halaman khusus meratakan kekerasan suara otomatis ke standar resmi platform streaming (-14 LUFS).",
                UiKit.ACCENT_KB, uiPrefs, v -> openFeature(16)));

        catC.addView(UiKit.makeMenuButton(this,
                "17. Mastering Suara Podcast, Audiobook & Ceramah (Loudnorm -16 LUFS)",
                "Halaman khusus menyeimbangkan dinamika suara bicara panjang agar nyaman didengar di headset maupun speaker.",
                UiKit.ACCENT_KB_ALT, uiPrefs, v -> openFeature(17)));

        catC.addView(UiKit.makeMenuButton(this,
                "18. Penguat Volume Maksimal Anti-Pecah (Brickwall Limiter +6 dB)",
                "Halaman khusus mengangkat suara pelan hingga batas maksimal sambil menahan puncak gelombang agar tidak sember.",
                UiKit.ACCENT_WARM, uiPrefs, v -> openFeature(18)));

        catC.addView(UiKit.makeMenuButton(this,
                "19. Penguat & Perata Vokal Studio (Studio Vocal Compressor)",
                "Halaman khusus menebalkan suara bicara yang pelan dan meredam lonjakan keras agar terdengar berwibawa.",
                UiKit.ACCENT_AI, uiPrefs, v -> openFeature(19)));

        catC.addView(UiKit.makeMenuButton(this,
                "20. Efek Suara Penyiar Radio FM Broadcast",
                "Halaman khusus memberikan karakter suara penyiar radio FM yang hangat, padat, dan dekat di telinga.",
                UiKit.ACCENT_AI_ALT, uiPrefs, v -> openFeature(20)));

        catC.addView(UiKit.makeMenuButton(this,
                "21. Pembersih Desis Noise, AC & Kipas Angin (FFT Studio Denoise)",
                "Halaman khusus menghilangkan suara desis latar ruangan menggunakan filter spektral FFmpeg.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> openFeature(21)));

        catC.addView(UiKit.makeMenuButton(this,
                "22. Pembersih Noise Berat & Angin Luar Ruangan (Heavy Outdoor Denoise)",
                "Halaman khusus pembersihan ekstra kuat untuk rekaman di pinggir jalan atau tempat berangin.",
                UiKit.ACCENT_DANGER, uiPrefs, v -> openFeature(22)));

        catC.addView(UiKit.makeMenuButton(this,
                "23. Penghalus Desis Huruf S Tajam (Vocal De-Esser) & Penguat Suara Jauh",
                "Halaman khusus meredam bunyi huruf S yang menusuk telinga atau mengangkat kejelasan rekaman kelas/rapat dari jarak jauh.",
                UiKit.ACCENT_KB, uiPrefs, v -> openFeature(23)));
        root.addView(catC);

        // KATEGORI D: EQUALIZER, KECEPATAN, PITCH, REVERB MASJID & KARAKTER SUARA
        LinearLayout catD = UiKit.makeCard(this, uiPrefs);
        catD.addView(UiKit.makeSectionLabel(this, "KATEGORI D: EQUALIZER, SPEED/PITCH, REVERB MASJID & KARAKTER", UiKit.ACCENT_KB_ALT));

        catD.addView(UiKit.makeMenuButton(this,
                "24. Equalizer Studio Siap Pakai (16 Preset Musik, Vokal, Murottal & Bioskop)",
                "Halaman khusus memilih 16 preset warna suara: Podcast, Murottal, Bass Boost, Subwoofer, Treble, Pop, Rock, Akustik, Klasik, Lo-Fi, dan Bioskop.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> openFeature(24)));

        catD.addView(UiKit.makeMenuButton(this,
                "25. Equalizer Kustom 5-Band (Atur Sendiri dB Bass, Low-Mid, Mid, Presence, Treble)",
                "Halaman khusus mengatur penguatan -15 dB s/d +15 dB pada frekuensi 60 Hz, 250 Hz, 1000 Hz, 4000 Hz, dan 12000 Hz.",
                UiKit.ACCENT_KB, uiPrefs, v -> openFeature(25)));

        catD.addView(UiKit.makeMenuButton(this,
                "26. Ubah Kecepatan Audio (0,25x - 4,0x) & Nada Pitch (-12 s/d +12 Semitone)",
                "Halaman khusus mempercepat atau melambatkan tempo bicara tanpa mengubah nada, atau mengubah kunci nada tanpa mengubah durasi.",
                UiKit.ACCENT_AI, uiPrefs, v -> openFeature(26)));

        catD.addView(UiKit.makeMenuButton(this,
                "27. Pengubah Karakter Suara (8 Karakter: Berat Berwibawa, Narator Film, Raksasa, Anak, Chipmunk)",
                "Halaman khusus mengubah karakter vokal menjadi suara berat, narator film, raksasa, anak ceria, atau chipmunk.",
                UiKit.ACCENT_AI_ALT, uiPrefs, v -> openFeature(27)));

        catD.addView(UiKit.makeMenuButton(this,
                "28. Reverb & Gema Ruangan Realistis (12 Ruangan: Masjid Agung, Kubah, Musholla, Studio, Konser, Gunung)",
                "Halaman khusus memberikan pantulan akustik ruangan bebas serak untuk murottal, adzan, shalawat, ceramah, atau musik.",
                UiKit.ACCENT_WARM, uiPrefs, v -> openFeature(28)));

        catD.addView(UiKit.makeMenuButton(this,
                "29. Efek Fade In (Suara Masuk Perlahan) & Fade Out (Suara Memudar Halus)",
                "Halaman khusus mengatur durasi muncul dan memudarnya suara di awal serta akhir audio.",
                UiKit.ACCENT_SYSTEM, uiPrefs, v -> openFeature(29)));

        catD.addView(UiKit.makeMenuButton(this,
                "30. Keseimbangan Speaker Kiri/Kanan (Stereo Pan) & Pelebar Panggung Stereo",
                "Halaman khusus mengatur fokus suara ke telinga kiri/kanan, menukar kanal stereo, atau memperlebar ruang stereo.",
                UiKit.ACCENT_KB_ALT, uiPrefs, v -> openFeature(30)));
        root.addView(catD);

        // KATEGORI E: AUDIO 8D, KARAOKE, GENERATOR NADA, REKAMAN, AI & STUDIO GUDANG
        LinearLayout catE = UiKit.makeCard(this, uiPrefs);
        catE.addView(UiKit.makeSectionLabel(this, "KATEGORI E: AUDIO 8D, KARAOKE, GENERATOR NADA, REKAMAN & STUDIO GUDANG", UiKit.ACCENT_KB));

        catE.addView(UiKit.makeMenuButton(this,
                "31. Efek Audio 8D Berputar Keliling Kepala (8D Surround Headset)",
                "Halaman khusus membuat suara musik atau lantunan berputar mengelilingi telinga kiri dan kanan di headset.",
                UiKit.ACCENT_AI, uiPrefs, v -> openFeature(31)));

        catE.addView(UiKit.makeMenuButton(this,
                "32. Pembuat Musik Karaoke (Hapus Vokal Tengah) & Isolasi Fokus Vokal",
                "Halaman khusus meredam suara vokal penyanyi untuk karaoke, menonjolkan vokal tengah, atau memberi efek Radio/Telepon/Megafon/Robot.",
                UiKit.ACCENT_WARM, uiPrefs, v -> openFeature(32)));

        catE.addView(UiKit.makeMenuButton(this,
                "33. Hapus Bagian Diam Otomatis (Auto Silence Remover), Putar Terbalik (Reverse) & Looper (2x-10x)",
                "Halaman khusus memangkas jeda hening panjang otomatis, membalik arah suara dari belakang ke depan, atau mengulang audio berkali-kali.",
                UiKit.ACCENT_SYSTEM, uiPrefs, v -> openFeature(33)));

        catE.addView(UiKit.makeMenuButton(this,
                "34. Generator Nada Frekuensi (Hz), Bunyi Beep Sensor & White/Pink/Brown Noise Terapi",
                "Halaman khusus membuat berkas audio baru berisi nada frekuensi murni, bunyi sensor beep, atau derau penenang tidur tanpa file input.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> openFeature(34)));

        catE.addView(UiKit.makeMenuButton(this,
                "35. Rekam Suara Mikrofon Baru Langsung ke Studio",
                "Merekam suara bicara atau nyanyian langsung dari mikrofon HP menjadi berkas WAV jernih.",
                UiKit.ACCENT_DANGER, uiPrefs, v -> openFeature(35)));

        catE.addView(UiKit.makeMenuButton(this,
                "36. Analisis Statistik Detail Audio & Transkrip Ucapan ke Teks (AI Gemini)",
                "Halaman khusus melihat spesifikasi lengkap berkas audio atau mengubah rekaman bicara menjadi teks tertulis rapi.",
                UiKit.ACCENT_AI_ALT, uiPrefs, v -> openFeature(36)));

        // TOMBOL PALING BAWAH SENDIRI: STUDIO GUDANG & LABORATORIUM BACKSOUND / EFEK FILM
        catE.addView(UiKit.makeMenuButton(this,
                "Studio Gudang & Laboratorium Backsound / Efek Drama Film (Kelola, Uji Suara & Hapus Audio)",
                "Buka gudang penyimpanan puluhan Backsound Musik (Islami, Piano Santai, Jazz, Rock, Jedag-Jedug, Tegang 1-2 menit) dan 32 Efek Suara Drama Film. Uji suara di Laboratorium atau hapus audio yang tidak diperlukan lalu klik Simpan.",
                UiKit.ACCENT_AI, uiPrefs, v -> StudioGudangAudioManager.showStudioGudangMainDialog(
                        this, uiPrefs, (uri, title, durSec, isBgm) -> {
                            setSingleUriInstant(uri);
                            Toast.makeText(this, "Audio Gudang dipilih: " + title, Toast.LENGTH_SHORT).show();
                        })));
        root.addView(catE);

        sv.addView(root);
        setContentView(sv);
        OfficeUtils.announce(head, "Studio Editor Audio Pro. Silakan pilih fitur yang diinginkan.");
    }

    // ==================== KOMPONEN HEADER, PEMUTAR SIMPEL (MUNDUR/PUTAR/MAJU) & FORMAT ====================

    private LinearLayout buildSubHeaderAndBack(String toolTitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, UiKit.dp(this, 8));

        Button btnBack = UiKit.makeMenuButton(this,
                "Kembali ke Daftar Semua Fitur Studio Audio",
                "Menutup halaman fitur ini dan kembali ke menu utama Studio Editor Audio.",
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

    /**
     * Kartu Pemilih Audio + Pemutar Simpel (Hanya 3 tombol: Mundur, Putar/Jeda, Maju).
     * Jika includeCutUndoRedo = true, menampilkan tombol Tandai Titik Awal, Tandai Titik Akhir, Undo, Redo,
     * serta kotak ketik waktu awal dan akhir. Saat tombol Putar di atas ditekan, otomatis memutar
     * rentang titik potong (tanpa tombol pratinjau terpisah).
     */
    private LinearLayout buildSimplePlayerAndCutCard(boolean includeCutUndoRedo) {
        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        card.addView(UiKit.makeSectionLabel(this, "A. PILIH BERKAS AUDIO & PEMUTAR UTAMA", UiKit.ACCENT_OFFICE));

        tvSingleInfo = new TextView(this);
        tvSingleInfo.setTextSize(14);
        tvSingleInfo.setTextColor(UiKit.textPrimary(uiPrefs));
        card.addView(tvSingleInfo);

        LinearLayout pickRow = new LinearLayout(this);
        pickRow.setOrientation(LinearLayout.HORIZONTAL);
        pickRow.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 4));
        pickRow.addView(OfficeUtils.makeRowButton(this,
                "Pilih Audio (File Manager)",
                "Pilih berkas audio dari File Manager aplikasi",
                UiKit.ACCENT_KB, Color.WHITE,
                v -> OfficeUtils.launchFilePicker(this, REQ_PICK_SINGLE_FM, "audio", "Pilih Berkas Audio")));
        pickRow.addView(OfficeUtils.makeRowButton(this,
                "Pilih Audio (Galeri / HP)",
                "Pilih berkas audio dari penyimpanan sistem Android",
                UiKit.ACCENT_SYSTEM, Color.WHITE,
                v -> launchSystemPicker("audio/*", REQ_PICK_SINGLE_SYS)));
        pickRow.addView(OfficeUtils.makeRowButton(this,
                "Dari Studio Gudang",
                "Pilih musik atau efek suara dari koleksi bawaan Studio Gudang aplikasi",
                UiKit.ACCENT_AI, Color.WHITE,
                v -> StudioGudangAudioManager.showStudioGudangMainDialog(this, uiPrefs, (uri, title, durSec, isBgm) -> {
                    setSingleUriInstant(uri);
                    ensurePlayerPreparedFor(uri);
                    Toast.makeText(this, "Dimuat dari Gudang: " + title, Toast.LENGTH_SHORT).show();
                })));
        card.addView(pickRow);

        tvPlayPos = new TextView(this);
        tvPlayPos.setTextSize(14.5f);
        tvPlayPos.setTypeface(null, Typeface.BOLD);
        tvPlayPos.setTextColor(UiKit.textPrimary(uiPrefs));
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            tvPlayPos.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        }
        card.addView(tvPlayPos);

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setContentDescription("Penggeser posisi putar audio");
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && player != null && singleMeta.durationMs > 0) {
                    long target = (singleMeta.durationMs * p) / 1000L;
                    try { player.seekTo((int) target); } catch (Throwable ignored) {}
                    updatePlayheadUi();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                OfficeUtils.announce(tvPlayPos, tvPlayPos.getText().toString());
            }
        });
        card.addView(seekBar);

        // Pemutar Simpel Tanpa Tombol Mundur/Maju (Cukup Putar/Jeda + Geser Kiri Mundur / Geser Kanan Maju)
        int txt = UiKit.textPrimary(uiPrefs);
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 2));

        btnPlayPause = OfficeUtils.makeRowButton(this,
                "Putar",
                "Putar atau jeda audio. Geser layar ke kiri untuk mundur 5 detik, geser layar ke kanan untuk maju 5 detik.",
                UiKit.ACCENT_OFFICE, Color.WHITE, v -> toggleSinglePlayPause());
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

            // Baris Klik Tandai Sambil Play
            LinearLayout markRow = new LinearLayout(this);
            markRow.setOrientation(LinearLayout.HORIZONTAL);
            markRow.addView(OfficeUtils.makeRowButton(this,
                    "Tandai Titik Awal",
                    "Kunci detik saat ini sebagai Titik Awal potongan dan lanjut berputar terus dari titik ini",
                    UiKit.ACCENT_KB, Color.WHITE, v -> markInWhilePlaying()));
            markRow.addView(OfficeUtils.makeRowButton(this,
                    "Tandai Titik Akhir",
                    "Kunci detik saat ini sebagai Titik Akhir potongan dan langsung putar hasil potongan dari Awal ke Akhir",
                    UiKit.ACCENT_KB_ALT, Color.WHITE, v -> markOutWhilePlaying()));
            card.addView(markRow);

            // Baris Undo & Redo Bertingkat
            LinearLayout undoRow = new LinearLayout(this);
            undoRow.setOrientation(LinearLayout.HORIZONTAL);
            undoRow.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 6));
            btnUndoCut = OfficeUtils.makeRowButton(this,
                    "Undo (Batalkan Langkah)",
                    "Undo sekali membatalkan Titik Akhir, Undo dua kali membatalkan Titik Awal, Undo seterusnya membatalkan langkah sebelumnya",
                    UiKit.ACCENT_WARM, Color.WHITE, v -> performCutUndo());
            btnRedoCut = OfficeUtils.makeRowButton(this,
                    "Redo (Kembalikan)",
                    "Mengembalikan titik potong yang baru saja dibatalkan dengan Undo",
                    UiKit.ACCENT_AI, Color.WHITE, v -> performCutRedo());
            undoRow.addView(btnUndoCut);
            undoRow.addView(btnRedoCut);
            card.addView(undoRow);

            // Kotak Ketik Manual Waktu Awal & Akhir
            TextView lblIn = new TextView(this);
            lblIn.setText("Atau Ketik Waktu Titik Awal (contoh: 10 atau 01:15):");
            lblIn.setTextSize(13);
            lblIn.setTextColor(txt);
            card.addView(lblIn);

            etInTime = new EditText(this);
            etInTime.setHint("00:00.00");
            etInTime.setContentDescription("Kotak ketik waktu Titik Awal");
            UiKit.styleEditText(this, etInTime, uiPrefs);
            card.addView(etInTime);

            TextView lblOut = new TextView(this);
            lblOut.setText("Atau Ketik Waktu Titik Akhir (contoh: 45 atau 02:30):");
            lblOut.setTextSize(13);
            lblOut.setTextColor(txt);
            lblOut.setPadding(0, UiKit.dp(this, 4), 0, 0);
            card.addView(lblOut);

            etOutTime = new EditText(this);
            etOutTime.setHint("00:30.00");
            etOutTime.setContentDescription("Kotak ketik waktu Titik Akhir");
            UiKit.styleEditText(this, etOutTime, uiPrefs);
            card.addView(etOutTime);

            card.addView(UiKit.makeMenuButton(this,
                    "Terapkan Angka Waktu yang Diketik di Kotak ke Pemutar",
                    "Mengunci angka detik Awal dan Akhir yang Anda ketik secara manual dan langsung memutarnya.",
                    UiKit.surfaceAlt(uiPrefs), uiPrefs, v -> applyTypedCutTimes()));
        }

        refreshSingleCardUi();
        return card;
    }

    private void pushCutUndoSnapshot(String actionLabel) {
        cutUndoStack.add(new CutState(activeInMs, activeOutMs, hasMarkedIn, hasMarkedOut, actionLabel));
        cutRedoStack.clear();
    }

    private void markInWhilePlaying() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        pushCutUndoSnapshot("Sebelum Tandai Awal");
        long pos = getPlayheadMs();
        activeInMs = pos;
        hasMarkedIn = true;
        if (hasMarkedOut && activeOutMs <= activeInMs) {
            hasMarkedOut = false;
            activeOutMs = singleMeta.durationMs;
        }
        syncCutUiFromState();
        // Pastikan audio tetap lanjut berputar dari Titik Awal tersebut
        if (player == null) ensurePlayerPreparedFor(singleUri);
        if (player != null) {
            try {
                player.seekTo((int) activeInMs);
                if (!isPlaying) {
                    player.start();
                    isPlaying = true;
                    if (btnPlayPause != null) btnPlayPause.setText("Jeda");
                    mainHandler.removeCallbacks(ticker);
                    mainHandler.post(ticker);
                }
            } catch (Throwable ignored) {}
        }
        String msg = "Titik Awal dikunci di " + AudioDspEngine.formatMsShort(activeInMs) + ". Audio lanjut berputar, klik Tandai Titik Akhir saat sampai di ujung potongan.";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        OfficeUtils.announce(tvPlayPos, msg);
    }

    private void markOutWhilePlaying() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        long pos = getPlayheadMs();
        if (pos <= activeInMs + 200L) {
            Toast.makeText(this, "Titik Akhir harus berada setelah Titik Awal (" + AudioDspEngine.formatMsShort(activeInMs) + ").", Toast.LENGTH_SHORT).show();
            return;
        }
        pushCutUndoSnapshot("Sebelum Tandai Akhir");
        activeOutMs = pos;
        hasMarkedOut = true;
        if (!hasMarkedIn) {
            hasMarkedIn = true;
            activeInMs = 0L;
        }
        syncCutUiFromState();
        // Otomatis langsung putar dari Titik Awal sampai Titik Akhir yang baru ditandai!
        if (player == null) ensurePlayerPreparedFor(singleUri);
        if (player != null) {
            try {
                player.seekTo((int) activeInMs);
                player.start();
                isPlaying = true;
                if (btnPlayPause != null) btnPlayPause.setText("Jeda");
                mainHandler.removeCallbacks(ticker);
                mainHandler.post(ticker);
            } catch (Throwable ignored) {}
        }
        String msg = "Titik Akhir dikunci di " + AudioDspEngine.formatMsShort(activeOutMs)
                + ". Memutar hasil potongan dari " + AudioDspEngine.formatMsShort(activeInMs)
                + " sampai " + AudioDspEngine.formatMsShort(activeOutMs) + ".";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        OfficeUtils.announce(tvPlayPos, msg);
    }

    private void applyTypedCutTimes() {
        if (singleUri == null || etInTime == null || etOutTime == null) return;
        long typedIn = AudioDspEngine.parseTimeToMs(etInTime.getText().toString(), singleMeta.durationMs);
        long typedOut = AudioDspEngine.parseTimeToMs(etOutTime.getText().toString(), singleMeta.durationMs);
        if (typedOut <= typedIn) typedOut = singleMeta.durationMs;
        pushCutUndoSnapshot("Sebelum Ketik Waktu");
        activeInMs = typedIn;
        activeOutMs = typedOut;
        hasMarkedIn = true;
        hasMarkedOut = (typedOut < singleMeta.durationMs);
        syncCutUiFromState();
        seekAbsolute(activeInMs);
        if (!isPlaying) toggleSinglePlayPause();
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
                Toast.makeText(this, "Undo: Titik Awal dibatalkan. Kembali ke awal 00:00.", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "Tidak ada langkah potong lagi untuk di-Undo.", Toast.LENGTH_SHORT).show();
            return;
        }
        cutRedoStack.add(new CutState(activeInMs, activeOutMs, hasMarkedIn, hasMarkedOut, "Redo"));
        CutState prev = cutUndoStack.remove(cutUndoStack.size() - 1);
        activeInMs = prev.inMs;
        activeOutMs = prev.outMs;
        hasMarkedIn = prev.hasIn;
        hasMarkedOut = prev.hasOut;
        syncCutUiFromState();
        seekAbsolute(activeInMs);
        String msg = "Undo berhasil: Awal " + AudioDspEngine.formatMsShort(activeInMs)
                + " • Akhir " + (hasMarkedOut ? AudioDspEngine.formatMsShort(activeOutMs) : "Akhir Audio");
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        OfficeUtils.announce(tvPlayPos, msg);
    }

    private void performCutRedo() {
        if (cutRedoStack.isEmpty()) {
            Toast.makeText(this, "Tidak ada langkah untuk di-Redo.", Toast.LENGTH_SHORT).show();
            return;
        }
        cutUndoStack.add(new CutState(activeInMs, activeOutMs, hasMarkedIn, hasMarkedOut, "Undo"));
        CutState next = cutRedoStack.remove(cutRedoStack.size() - 1);
        activeInMs = next.inMs;
        activeOutMs = next.outMs;
        hasMarkedIn = next.hasIn;
        hasMarkedOut = next.hasOut;
        syncCutUiFromState();
        seekAbsolute(activeInMs);
        String msg = "Redo berhasil: Awal " + AudioDspEngine.formatMsShort(activeInMs)
                + " • Akhir " + (hasMarkedOut ? AudioDspEngine.formatMsShort(activeOutMs) : "Akhir Audio");
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        OfficeUtils.announce(tvPlayPos, msg);
    }

    private void syncCutUiFromState() {
        if (etInTime != null) etInTime.setText(AudioDspEngine.formatMs(activeInMs));
        if (etOutTime != null) etOutTime.setText(AudioDspEngine.formatMs(activeOutMs > activeInMs ? activeOutMs : singleMeta.durationMs));
        if (tvCutRangeBadge != null) {
            if (!hasMarkedIn && !hasMarkedOut) {
                tvCutRangeBadge.setText("Rentang Aktif: Seluruh Audio (Belum ada tanda potong • Putar lalu klik Tandai Titik Awal)");
            } else if (hasMarkedIn && !hasMarkedOut) {
                tvCutRangeBadge.setText("Titik Awal Terkunci: " + AudioDspEngine.formatMs(activeInMs)
                        + " -> Sampai Akhir (Klik Tandai Titik Akhir untuk mengunci ujung potongan)");
            } else {
                long dur = Math.max(0L, activeOutMs - activeInMs);
                tvCutRangeBadge.setText("Potongan Terkunci: " + AudioDspEngine.formatMs(activeInMs)
                        + " s/d " + AudioDspEngine.formatMs(activeOutMs)
                        + " (Durasi Potongan: " + AudioDspEngine.formatMsShort(dur) + " • Tekan Putar di atas untuk mendengar)");
            }
        }
        if (btnUndoCut != null) btnUndoCut.setText("Undo (" + cutUndoStack.size() + ")");
        if (btnRedoCut != null) btnRedoCut.setText("Redo (" + cutRedoStack.size() + ")");
    }

    private LinearLayout buildExportFormatCard() {
        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        card.addView(UiKit.makeSectionLabel(this, "PENGATURAN FORMAT & BITRATE HASIL EKSPOR", UiKit.ACCENT_KB_ALT));

        final TextView tvSummary = new TextView(this);
        tvSummary.setText("Setelan Ekspor Saat Ini: " + getExportSummaryText());
        tvSummary.setTextSize(13.5f);
        tvSummary.setTypeface(null, Typeface.BOLD);
        tvSummary.setTextColor(UiKit.textPrimary(uiPrefs));
        tvSummary.setPadding(0, 0, 0, UiKit.dp(this, 6));
        card.addView(tvSummary);

        int txt = UiKit.textPrimary(uiPrefs);
        TextView lblFmt = new TextView(this); lblFmt.setText("Pilih Format Berkas Audio Hasil:"); lblFmt.setTextSize(13); lblFmt.setTextColor(txt); card.addView(lblFmt);
        Spinner spFmt = new Spinner(this);
        spFmt.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, FORMAT_LABELS));
        spFmt.setSelection(selectedFormatIdx);
        card.addView(spFmt);

        TextView lblBr = new TextView(this); lblBr.setText("Pilih Kualitas Bitrate Audio (kbps):"); lblBr.setTextSize(13); lblBr.setTextColor(txt); lblBr.setPadding(0, UiKit.dp(this, 6), 0, 0); card.addView(lblBr);
        Spinner spBr = new Spinner(this);
        spBr.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, BITRATE_LABELS));
        spBr.setSelection(selectedBitrateIdx);
        card.addView(spBr);

        TextView lblSr = new TextView(this); lblSr.setText("Pilih Frekuensi Sample Rate (Hz):"); lblSr.setTextSize(13); lblSr.setTextColor(txt); lblSr.setPadding(0, UiKit.dp(this, 6), 0, 0); card.addView(lblSr);
        Spinner spSr = new Spinner(this);
        spSr.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, SAMPLE_RATE_LABELS));
        spSr.setSelection(selectedSampleRateIdx);
        card.addView(spSr);

        TextView lblCh = new TextView(this); lblCh.setText("Pilih Kanal Suara (Stereo / Mono):"); lblCh.setTextSize(13); lblCh.setTextColor(txt); lblCh.setPadding(0, UiKit.dp(this, 6), 0, 0); card.addView(lblCh);
        Spinner spCh = new Spinner(this);
        spCh.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, CHANNEL_LABELS));
        spCh.setSelection(selectedChannelIdx);
        card.addView(spCh);

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedFormatIdx = spFmt.getSelectedItemPosition();
                selectedBitrateIdx = spBr.getSelectedItemPosition();
                selectedSampleRateIdx = spSr.getSelectedItemPosition();
                selectedChannelIdx = spCh.getSelectedItemPosition();
                saveExportPrefs();
                tvSummary.setText("Setelan Ekspor Saat Ini: " + getExportSummaryText());
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        spFmt.setOnItemSelectedListener(listener);
        spBr.setOnItemSelectedListener(listener);
        spSr.setOnItemSelectedListener(listener);
        spCh.setOnItemSelectedListener(listener);
        return card;
    }

    private void setSingleUriInstant(Uri uri) {
        singleUri = uri;
        singleMeta = AudioDspEngine.inspectMedia(this, uri);
        activeInMs = 0L;
        activeOutMs = singleMeta.durationMs;
        hasMarkedIn = false;
        hasMarkedOut = false;
        cutUndoStack.clear();
        cutRedoStack.clear();
        if (tvSingleInfo != null) refreshSingleCardUi();
    }

    private void refreshSingleCardUi() {
        if (tvSingleInfo == null) return;
        if (singleUri == null) {
            tvSingleInfo.setText("Belum ada berkas audio dipilih. Tekan tombol Pilih Audio di bawah untuk memuat berkas.");
            tvPlayPos.setText("Posisi Putar: 00:00.00 dari 00:00.00");
        } else {
            tvSingleInfo.setText("Berkas Aktif: " + singleMeta.displayName
                    + "\nDurasi Total: " + AudioDspEngine.formatMs(singleMeta.durationMs)
                    + " • Ukuran: " + fmtSize(singleMeta.sizeBytes));
            tvPlayPos.setText("Posisi Putar: " + AudioDspEngine.formatMs(getPlayheadMs()) + " dari " + AudioDspEngine.formatMs(singleMeta.durationMs));
        }
        syncCutUiFromState();
    }

    // ==================== FITUR 2: STUDIO EFEK DRAMA/FILM & BACKSOUND SAMBIL PUTAR (LIVE SFX + UNDO/REDO) ====================

    private void renderLiveDramaSfxStudioScreen() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(UiKit.bg(uiPrefs));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, UiKit.dp(this, 16));

        root.addView(buildSubHeaderAndBack("Studio Efek Drama/Film, Kartun & Backsound"));
        root.addView(UiKit.makeMenuButton(this,
                "Panduan Singkat Editor Drama (Ketuk untuk Baca)",
                "Putar audio -> pilih Kategori -> ketuk nama Efek/Backsound untuk pasang di detik tujuan -> Konversi.",
                UiKit.ACCENT_KB_ALT, uiPrefs, v -> UiKit.dialog(this, uiPrefs)
                        .setTitle("Panduan Singkat Efek & Backsound")
                        .setMessage("1. Pilih audio utama lalu tekan Putar (geser kiri/kanan = mundur/maju 5 detik).\n"
                                + "2. Ketuk 'Kategori' -> pilih Efek Singkat atau Backsound Musik.\n"
                                + "3. Tekan Putar untuk tes, lalu ketuk nama audio pas di detik target (bisa dipasang berulang kali di detik berbeda).\n"
                                + "4. Jika ingin batal, tombol 'Nggak Jadi' otomatis muncul setelah audio terpasang — bisa hapus detik tertentu saja tanpa menghapus detik lain.\n"
                                + "5. Untuk Backsound yang sudah terpasang, tombol Tandai Awal / Tandai Akhir muncul di baris lagu itu.\n"
                                + "6. Ketuk 'Mulai Konversi Audio' di pojok kanan bawah layar.")
                        .setPositiveButton("Mengerti", null).show()));

        root.addView(buildSimplePlayerAndCutCard(false));

        final TextView tvFooterInfo = new TextView(this);
        tvFooterInfo.setTextSize(13f);
        tvFooterInfo.setTypeface(null, Typeface.BOLD);
        tvFooterInfo.setTextColor(UiKit.textPrimary(uiPrefs));
        tvFooterInfo.setText("Terpasang: " + liveSfxCues.size() + " item");

        if (paletteController == null) {
            paletteController = new DramaSoundPaletteHelper.Controller(
                    this, uiPrefs, new DramaSoundPaletteHelper.Host() {
                @Override public long getPlayheadMs() { return AudioEditorStudioActivity.this.getPlayheadMs(); }
                @Override public long getTotalDurationMs() { return singleMeta.durationMs; }
                @Override public void playTransitionFromMs(long cueAtMs) { playTransitionPreviewAt(cueAtMs); }
                @Override public void stopPlaybackForPreviewSwitch() { if (isPlaying) toggleSinglePlayPause(); }
                @Override public void triggerOneShotCue(DramaSoundPaletteHelper.CueItem cue) { triggerOneCuePreview(cue); }
                @Override public void onPickAudioFromPhone() {
                    UiKit.dialog(AudioEditorStudioActivity.this, uiPrefs)
                            .setTitle("Pilih Audio dari Penyimpanan HP")
                            .setItems(new String[]{"Buka File Manager Aplikasi", "Buka Galeri / Pemilih Sistem Android"}, (d, w) -> {
                                if (w == 0) OfficeUtils.launchFilePicker(AudioEditorStudioActivity.this, REQ_PICK_LIVE_SFX_FM, "audio", "Pilih Efek / Backsound dari HP");
                                else launchSystemPicker("audio/*", REQ_PICK_LIVE_SFX_SYS);
                            }).show();
                }
                @Override public void onPaletteAppliedCue(DramaSoundPaletteHelper.CueItem addedOrNull) {
                    if (addedOrNull != null) {
                        Toast.makeText(AudioEditorStudioActivity.this,
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

        root.addView(buildExportFormatCard());

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
        btnConvertPinned.setText("Mulai Konversi Audio");
        btnConvertPinned.setContentDescription("Mulai konversi dan gabungkan semua efek suara dan backsound ke audio sekarang");
        btnConvertPinned.setTextSize(13.5f);
        btnConvertPinned.setTypeface(null, Typeface.BOLD);
        btnConvertPinned.setTextColor(Color.WHITE);
        btnConvertPinned.setBackground(UiKit.roundedBox(UiKit.ACCENT_OFFICE, Color.TRANSPARENT, UiKit.dp(this, 10), 0));
        btnConvertPinned.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 10), UiKit.dp(this, 16), UiKit.dp(this, 10));
        btnConvertPinned.setOnClickListener(v -> executeRenderLiveSfxMix());
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
     * lalu langsung memutar melewati detik ke-15 dan terus lanjut hingga pengguna menekan Jeda.
     */
    private void playTransitionPreviewAt(long cueAtMs) {
        if (singleUri == null) return;
        if (player == null) ensurePlayerPreparedFor(singleUri);
        if (player == null) return;
        long preRollMs = Math.max(0L, cueAtMs - 2000L);
        try {
            stopAllCuePlayers();
            for (DramaSoundPaletteHelper.CueItem c : liveSfxCues) {
                if (c.atMs >= preRollMs) {
                    c.lastTriggeredPlaybackMs = -5000L;
                }
            }
            player.seekTo((int) preRollMs);
            lastTickerPosMs = preRollMs - 50L;
            if (!isPlaying) {
                player.start();
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

    private void executeRenderLiveSfxMix() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio utama terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        if (liveSfxCues.isEmpty()) { Toast.makeText(this, "Tempel minimal 1 efek suara atau backsound terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        final List<AudioDspEngine.MixTrackSpec> tracks = new ArrayList<>();
        AudioDspEngine.MixTrackSpec mainTrack = new AudioDspEngine.MixTrackSpec();
        mainTrack.uri = singleUri;
        mainTrack.title = singleMeta.displayName;
        mainTrack.volume = 1.0f;
        mainTrack.delayMs = 0L;
        mainTrack.loop = false;
        tracks.add(mainTrack);

        for (DramaSoundPaletteHelper.CueItem c : liveSfxCues) {
            AudioDspEngine.MixTrackSpec tr = new AudioDspEngine.MixTrackSpec();
            tr.uri = c.uri;
            tr.title = c.title;
            tr.volume = c.volume;
            tr.delayMs = c.atMs;
            tr.endMs = c.endMs;
            tr.loop = c.isBacksoundLoop;
            tracks.add(tr);
        }
        final String fmt = getActiveFormatExt();
        final int br = getActiveBitrateKbps();
        runFfmpegJob("Menggabungkan Audio + " + liveSfxCues.size() + " Efek/Backsound...", () -> {
            File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
            File out = new File(outDir, cleanBaseName(singleMeta.displayName) + "_DramaMix_" + System.currentTimeMillis() + "." + fmt);
            AudioDspEngine.mixMultiTracks(this, tracks, workDir, out, false, fmt, br);
            return out;
        });
    }

    // ==================== HALAMAN KHUSUS MASING-MASING FITUR PEMOTONGAN (TIDAK TERCAMPUR!) ====================

    /** Fitur 6 (Trim Awal-Akhir) atau Fitur 7 (Buang Bagian Tengah). */
    private void renderDedicatedCutScreen(final boolean isCutMiddleMode) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack(isCutMiddleMode
                ? "Buang Bagian Tengah Audio Sambil Putar + Undo / Redo"
                : "Potong Audio Sambil Putar + Undo / Redo"));

        root.addView(MediaEditorCatalog.makeGuideCard(this, uiPrefs,
                isCutMiddleMode ? "Cara Membuang Bagian Tengah Audio Sambil Putar" : "Cara Memotong Audio Sambil Putar + Undo / Redo",
                isCutMiddleMode
                        ? "Putar audio, klik Tandai Titik Awal pada awal bagian yang salah, lalu klik Tandai Titik Akhir pada akhir bagian yang salah. Gunakan Undo jika salah tandai, lalu klik Buang Bagian Tengah & Simpan."
                        : "1. Klik Putar di atas. Saat sampai di bagian awal yang ingin diambil, klik Tandai Titik Awal (audio akan terus berputar).\n"
                        + "2. Saat sampai di ujung yang ingin diambil, klik Tandai Titik Akhir. Sekarang tombol Putar di atas otomatis hanya memutar bagian potongan tersebut.\n"
                        + "3. Jika kurang pas, klik Undo 1x untuk batal Titik Akhir, atau Undo 2x untuk batal Titik Awal.",
                isCutMiddleMode ? "Bagian tengah yang ditandai akan dihapus dan sisanya disambung halus." : "Tidak perlu tombol pratinjau terpisah, cukup tekan Putar di bagian atas."));

        root.addView(buildSimplePlayerAndCutCard(true));
        root.addView(buildExportFormatCard());

        LinearLayout actCard = UiKit.makeCard(this, uiPrefs);
        if (!isCutMiddleMode) {
            actCard.addView(UiKit.makeMenuButton(this,
                    "Potong & Simpan Bagian Awal sampai Akhir Sekarang (" + getExportSummaryText() + ")",
                    "Menyimpan hasil potongan dari Titik Awal hingga Titik Akhir ke penyimpanan HP.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> executeSingleTrim()));
        } else {
            actCard.addView(UiKit.makeMenuButton(this,
                    "Buang Bagian Tengah & Simpan Audio Sekarang (" + getExportSummaryText() + ")",
                    "Menghapus bagian di antara Titik Awal dan Titik Akhir lalu menyambung bagian depan dan belakang.",
                    UiKit.ACCENT_DANGER, uiPrefs, v -> executeSingleCut()));
        }
        root.addView(actCard);

        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 8: Halaman 100% Khusus Status WhatsApp Saja (Tanpa Tombol Potong Lain!). */
    private void renderDedicatedStatusWaScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Pecah Audio Khusus untuk Status WhatsApp"));
        root.addView(MediaEditorCatalog.makeGuideCard(this, uiPrefs,
                "Halaman Khusus Pemotong Status WhatsApp / Story",
                "Halaman ini khusus untuk memecah audio panjang menjadi bagian-bagian berurutan (Part 1, Part 2, dan seterusnya) sesuai batas durasi Status WhatsApp.",
                "Audio ceramah atau lagu 5 menit langsung terpecah otomatis menjadi 5 berkas berdurasi 60 detik."));

        root.addView(buildSimplePlayerAndCutCard(false));
        root.addView(buildExportFormatCard());

        LinearLayout waCard = UiKit.makeCard(this, uiPrefs);
        waCard.addView(UiKit.makeSectionLabel(this, "PILIH DURASI PER BAGIAN STATUS WHATSAPP", UiKit.ACCENT_KB_ALT));

        String[] choices = {
                "60 Detik per Bagian (Standar Status WhatsApp 1 Menit)",
                "30 Detik per Bagian (Status WA Klasik 30 Detik)",
                "15 Detik per Bagian (Story Singkat 15 Detik)",
                "90 Detik per Bagian (1,5 Menit)"
        };
        int[] secVals = {60, 30, 15, 90};
        final Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
        waCard.addView(sp);

        waCard.addView(UiKit.makeMenuButton(this,
                "Pecah Audio untuk Status WhatsApp Sekarang",
                "Memotong berkas audio aktif secara otomatis menjadi Part 1, Part 2, dan seterusnya sesuai durasi pilihan di atas.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                    if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                    final int sec = secVals[sp.getSelectedItemPosition()];
                    final String fmt = getActiveFormatExt();
                    final int br = getActiveBitrateKbps();
                    runBusyMulti("Memecah Audio Status WhatsApp (" + sec + " detik per bagian)...", () -> {
                        File outDir = OfficeUtils.resolveBackupSubfolder(this, "Status WA Media");
                        return AudioDspEngine.splitAudioStatusParts(this, singleUri, workDir, outDir, cleanBaseName(singleMeta.displayName), sec, fmt, br);
                    });
                }));
        root.addView(waCard);

        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 9: Halaman Khusus Belah Audio Menjadi 2 Berkas. */
    private void renderDedicatedSplit2Screen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Belah Audio Menjadi 2 Berkas Terpisah"));
        root.addView(MediaEditorCatalog.makeGuideCard(this, uiPrefs,
                "Halaman Khusus Membelah Audio Menjadi 2 Bagian",
                "Putar audio lalu klik Tandai Titik Awal tepat pada detik yang ingin dibelah (atau ketik detiknya), kemudian tekan Belah Menjadi 2 Berkas Sekarang.",
                "Menghasilkan Bagian 1 (dari awal sampai titik belah) dan Bagian 2 (dari titik belah sampai akhir)."));

        root.addView(buildSimplePlayerAndCutCard(true));
        root.addView(buildExportFormatCard());

        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        card.addView(UiKit.makeMenuButton(this,
                "Belah Audio Menjadi 2 Berkas di Titik Awal Sekarang",
                "Memecah audio aktif menjadi Bagian 1 dan Bagian 2 tepat pada detik Titik Awal.",
                UiKit.ACCENT_WARM, uiPrefs, v -> executeSingleSplit2()));
        root.addView(card);

        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 10: Halaman Khusus Bagi Audio Menjadi Banyak Bagian Sama Rata. */
    private void renderDedicatedEqualSplitScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Bagi Audio Menjadi Banyak Bagian Sama Rata (2 s/d 30 Bagian)"));
        root.addView(buildSimplePlayerAndCutCard(false));
        root.addView(buildExportFormatCard());

        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        card.addView(UiKit.makeSectionLabel(this, "MASUKKAN JUMLAH BAGIAN SAMA RATA", UiKit.ACCENT_KB));
        TextView lbl = new TextView(this);
        lbl.setText("Jumlah bagian yang diinginkan (2 sampai 30 bagian):");
        lbl.setTextColor(UiKit.textPrimary(uiPrefs));
        card.addView(lbl);
        final EditText etCount = new EditText(this);
        etCount.setText("3");
        UiKit.styleEditText(this, etCount, uiPrefs);
        card.addView(etCount);

        card.addView(UiKit.makeMenuButton(this,
                "Bagi Audio Sama Rata Sekarang",
                "Membagi total durasi audio menjadi sejumlah berkas dengan durasi yang sama persis.",
                UiKit.ACCENT_KB, uiPrefs, v -> {
                    if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                    final int count = Math.max(2, Math.min(30, (int) safeDouble(etCount.getText().toString(), 3)));
                    final String fmt = getActiveFormatExt();
                    final int br = getActiveBitrateKbps();
                    final int sr = getActiveSampleRate();
                    final int ch = getActiveChannels();
                    runBusyMulti("Membagi Audio Menjadi " + count + " Bagian Sama Rata...", () -> {
                        File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
                        return AudioDspEngine.splitAudioEqualParts(this, singleUri, workDir, outDir,
                                cleanBaseName(singleMeta.displayName), count, fmt, br, sr, ch);
                    });
                }));
        root.addView(card);

        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 11: Halaman Khusus Pembuat Nada Dering. */
    private void renderDedicatedRingtoneScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Pembuat Nada Dering HP (Ringtone Maker 30 Detik + Fade Halus)"));
        root.addView(buildSimplePlayerAndCutCard(true));
        root.addView(buildExportFormatCard());

        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        card.addView(UiKit.makeMenuButton(this,
                "Buat & Simpan Nada Dering Sekarang",
                "Mengambil potongan maksimal 30 detik mulai dari Titik Awal dengan efek Fade In dan Fade Out halus siap dijadikan ringtone.",
                UiKit.ACCENT_AI_ALT, uiPrefs, v -> executeMakeRingtone()));
        root.addView(card);

        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 13: Halaman Khusus Kompres Ukuran Audio. */
    private void renderDedicatedCompressScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Kompres Ukuran Berkas Audio (Hemat Kuota WhatsApp / Telegram)"));
        root.addView(buildSimplePlayerAndCutCard(false));

        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        card.addView(UiKit.makeSectionLabel(this, "PILIH TINGKAT KOMPRESI UKURAN AUDIO", UiKit.ACCENT_KB_ALT));

        card.addView(UiKit.makeMenuButton(this,
                "1. Kompres Vokal Jernih (M4A/AAC 64 kbps Mono — ~480 KB/menit, Seperti Ori)",
                "Ukuran super ringan (< 0,5 MB/menit) dengan kejernihan vokal tetap utuh seperti aslinya untuk TTS, ceramah, & WhatsApp.",
                UiKit.ACCENT_AI, uiPrefs, v -> runQuickCompress("m4a", 64, 24000, 1, "KompresM4A64k")));

        card.addView(UiKit.makeMenuButton(this,
                "2. Kompres Seimbang Musik & Suara (M4A/AAC 96 kbps Stereo — ~720 KB/menit)",
                "Ukuran di bawah 1 MB/menit dengan detail musik & vokal stereo tetap jernih seperti kualitas studio.",
                UiKit.ACCENT_KB, uiPrefs, v -> runQuickCompress("m4a", 96, 44100, 2, "KompresM4A96k")));

        card.addView(UiKit.makeMenuButton(this,
                "3. Kompres Voice Super Ringan (OPUS 48 kbps — Format Modern Paling Kecil)",
                "Kompresi format OPUS 48 kbps (~360 KB/menit) dengan kejernihan vokal maksimal di ukuran terkecil.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> runQuickCompress("opus", 48, 48000, 1, "KompresOpus")));

        card.addView(UiKit.makeMenuButton(this,
                "4. Kompres MP3 Universal (MP3 96 kbps Stereo 44100 Hz)",
                "Kompatibel di seluruh pemutar MP3 lawas dengan ukuran tetap ringan.",
                UiKit.ACCENT_KB_ALT, uiPrefs, v -> runQuickCompress("mp3", 96, 44100, 2, "KompresMP396k")));

        root.addView(card);
        sv.addView(root);
        setContentView(sv);
    }

    private void runQuickCompress(final String fmt, final int br, final int sr, final int ch, final String tag) {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        runFfmpegJob("Mengompres Berkas Audio (" + br + " kbps)...", () -> {
            File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
            File out = new File(outDir, cleanBaseName(singleMeta.displayName) + "_" + tag + "_" + System.currentTimeMillis() + "." + fmt);
            AudioDspEngine.trimAudio(this, singleUri, workDir, out, 0L, 0L, fmt, br, sr, ch);
            return out;
        });
    }

    /** Fitur 14: Halaman Khusus Sisipkan Jeda Hening. */
    private void renderDedicatedInsertSilenceScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Sisipkan Jeda Hening (Insert Silence) pada Detik Pilihan"));
        root.addView(buildSimplePlayerAndCutCard(true));
        root.addView(buildExportFormatCard());

        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        TextView lbl = new TextView(this);
        lbl.setText("Durasi jeda hening yang ingin disisipkan pada Titik Awal (dalam detik, 0.5 s/d 30):");
        lbl.setTextColor(UiKit.textPrimary(uiPrefs));
        card.addView(lbl);
        final EditText etSec = new EditText(this);
        etSec.setText("2.0");
        UiKit.styleEditText(this, etSec, uiPrefs);
        card.addView(etSec);

        card.addView(UiKit.makeMenuButton(this,
                "Sisipkan Jeda Hening & Simpan Sekarang",
                "Menambahkan ruang hening pada posisi Titik Awal sesuai durasi detik di atas.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                    if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                    final double sec = Math.max(0.2, Math.min(30.0, safeDouble(etSec.getText().toString(), 2.0)));
                    final long atMs = activeInMs;
                    final String fmt = getActiveFormatExt();
                    final int br = getActiveBitrateKbps();
                    runFfmpegJob("Menyisipkan Jeda Hening " + sec + " Detik...", () -> {
                        File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
                        File out = new File(outDir, cleanBaseName(singleMeta.displayName) + "_Jeda_" + System.currentTimeMillis() + "." + fmt);
                        AudioDspEngine.insertSilence(this, singleUri, workDir, out, atMs, sec, singleMeta.durationMs, fmt, br);
                        return out;
                    });
                }));
        root.addView(card);
        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 11: Halaman Khusus Konversi Format & Bitrate. */
    private void renderDedicatedConvertFormatScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Konversi Format & Ubah Bitrate Audio (MP3/M4A/WAV/FLAC/OGG/OPUS)"));
        root.addView(buildSimplePlayerAndCutCard(false));
        root.addView(buildExportFormatCard());

        LinearLayout actCard = UiKit.makeCard(this, uiPrefs);
        actCard.addView(UiKit.makeMenuButton(this,
                "Konversi Audio ke Format & Bitrate Pilihan Sekarang (" + getExportSummaryText() + ")",
                "Mengonversi berkas audio aktif sesuai format, bitrate (64-320 kbps), sample rate, dan kanal di atas.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> executeDirectFormatConvert()));
        root.addView(actCard);
        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 34: Halaman Khusus Generator Nada Frekuensi & White Noise. */
    private void renderDedicatedToneGeneratorScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Generator Nada Frekuensi (Hz), Beep Sensor & White Noise"));
        root.addView(buildExportFormatCard());

        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        int txt = UiKit.textPrimary(uiPrefs);
        final String[] types = {
                "Nada Frekuensi Murni (Sine Wave Hz — Uji Speaker / Beep Sensor)",
                "White Noise (Derau Putih — Terapi Fokus & Penutup Bising)",
                "Pink Noise (Derau Merah Muda — Suara Hujan Halus Pengantar Tidur)",
                "Brown Noise (Derau Cokelat Dalam — Relaksasi Frekuensi Rendah)"
        };
        final String[] codes = {"sine", "white_noise", "pink_noise", "brown_noise"};
        Spinner spType = new Spinner(this);
        spType.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, types));
        card.addView(spType);

        TextView lHz = new TextView(this); lHz.setText("Frekuensi Nada dalam Hz (20 s/d 16000 Hz, contoh: 1000 untuk Beep Sensor, 440 untuk Nada A, 60 untuk Bass):"); lHz.setTextColor(txt); card.addView(lHz);
        final EditText etHz = new EditText(this); etHz.setText("1000"); UiKit.styleEditText(this, etHz, uiPrefs); card.addView(etHz);

        TextView lDur = new TextView(this); lDur.setText("Durasi Audio yang Dibuat (dalam detik, contoh: 10 atau 60):"); lDur.setTextColor(txt); card.addView(lDur);
        final EditText etDur = new EditText(this); etDur.setText("15"); UiKit.styleEditText(this, etDur, uiPrefs); card.addView(etDur);

        card.addView(UiKit.makeMenuButton(this,
                "Buat & Simpan Audio Generator Sekarang",
                "Menghasilkan berkas audio baru sesuai jenis gelombang dan durasi di atas.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                    final String gType = codes[spType.getSelectedItemPosition()];
                    final int hz = Math.max(20, Math.min(16000, (int) safeDouble(etHz.getText().toString(), 1000)));
                    final double dur = Math.max(1.0, Math.min(600.0, safeDouble(etDur.getText().toString(), 15.0)));
                    final String fmt = getActiveFormatExt();
                    final int br = getActiveBitrateKbps();
                    runFfmpegJob("Membuat Audio Generator...", () -> {
                        File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
                        String tag = "sine".equals(gType) ? ("Nada_" + hz + "Hz") : gType;
                        File out = new File(outDir, "Generator_" + tag + "_" + System.currentTimeMillis() + "." + fmt);
                        AudioDspEngine.generateToneOrNoise(out, gType, hz, dur, fmt, br);
                        return out;
                    });
                }));
        root.addView(card);
        sv.addView(root);
        setContentView(sv);
    }

    /** Fitur 36: Halaman Khusus Statistik & Transkrip AI. */
    private void renderDedicatedStatsTranscribeScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Analisis Statistik Detail Audio & Transkrip Ucapan ke Teks (AI Gemini)"));
        root.addView(buildSimplePlayerAndCutCard(false));

        LinearLayout card = UiKit.makeCard(this, uiPrefs);
        card.addView(UiKit.makeMenuButton(this,
                "1. Tampilkan Statistik Teknis Lengkap Audio Ini",
                "Melihat rincian durasi persis, ukuran berkas, sample rate asli, dan bitrate.",
                UiKit.ACCENT_KB, uiPrefs, v -> showStatsOrTranscribeDialog()));
        card.addView(UiKit.makeMenuButton(this,
                "2. Transkrip Ucapan Audio Ini Menjadi Teks Rapi (AI Gemini)",
                "Mengubah rekaman suara bicara menjadi naskah teks tertulis dengan AI Gemini.",
                UiKit.ACCENT_AI, uiPrefs, v -> runAiTranscribeSingle()));
        root.addView(card);
        sv.addView(root);
        setContentView(sv);
    }

    /**
     * Halaman Khusus Masing-Masing Fitur DSP Tunggal (Fitur 15 s/d 33).
     * Setiap fitur memiliki halaman sendiri dan TIDAK mencampur fitur lain.
     */
    private void renderDedicatedSingleDspToolScreen(final int fid) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        String title;
        switch (fid) {
            case 15: title = "Perkeras atau Kecilkan Volume Audio (Gain Manual dB)"; break;
            case 16: title = "Standarisasi Volume Siaran & Musik (Loudnorm -14 LUFS)"; break;
            case 17: title = "Mastering Suara Podcast & Ceramah (Loudnorm -16 LUFS)"; break;
            case 18: title = "Penguat Volume Maksimal Anti-Pecah (Brickwall Limiter +6 dB)"; break;
            case 19: title = "Penguat & Perata Vokal Studio (Studio Vocal Compressor)"; break;
            case 20: title = "Efek Suara Penyiar Radio FM Broadcast"; break;
            case 21: title = "Pembersih Desis Noise, AC & Kipas Angin (FFT Studio Denoise)"; break;
            case 22: title = "Pembersih Noise Berat & Angin Luar Ruangan (Outdoor Denoise)"; break;
            case 23: title = "Penghalus Desis Huruf S Tajam (De-Esser) & Penguat Suara Jauh"; break;
            case 24: title = "Equalizer Studio Siap Pakai (16 Preset Musik, Vokal & Murottal)"; break;
            case 25: title = "Equalizer Kustom 5-Band (-15 dB s/d +15 dB)"; break;
            case 26: title = "Ubah Kecepatan Audio (0,25x - 4,0x) & Nada Pitch (-12..+12)"; break;
            case 27: title = "Pengubah Karakter Suara (8 Karakter Vokal)"; break;
            case 28: title = "Reverb & Gema Ruangan Realistis (12 Ruangan: Masjid Agung dll)"; break;
            case 29: title = "Efek Fade In & Fade Out Halus"; break;
            case 30: title = "Keseimbangan Speaker Kiri/Kanan (Stereo Pan) & Pelebar Stereo"; break;
            case 31: title = "Efek Audio 8D Berputar Keliling Kepala (8D Surround)"; break;
            case 32: title = "Pembuat Musik Karaoke (Hapus Vokal) & Isolasi Fokus Vokal"; break;
            default: title = "Hapus Bagian Diam Otomatis (Silence Remover), Reverse & Looper"; break;
        }

        root.addView(buildSubHeaderAndBack(title));
        root.addView(buildSimplePlayerAndCutCard(false));
        root.addView(buildExportFormatCard());

        LinearLayout actCard = UiKit.makeCard(this, uiPrefs);
        actCard.addView(UiKit.makeSectionLabel(this, "PENGATURAN & EKSEKUSI FITUR INI", UiKit.ACCENT_AI));
        int txt = UiKit.textPrimary(uiPrefs);

        if (fid == 15) {
            TextView lbl = new TextView(this); lbl.setText("Masukkan nilai Gain dalam dB (-24 s/d +24, positif = perkeras, negatif = kecilkan):"); lbl.setTextColor(txt); actCard.addView(lbl);
            final EditText etDb = new EditText(this); etDb.setText("8"); UiKit.styleEditText(this, etDb, uiPrefs); actCard.addView(etDb);
            actCard.addView(UiKit.makeMenuButton(this, "Terapkan Perubahan Volume Gain & Simpan", "Memproses penguatan volume sesuai angka dB di atas.", UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                double db = Math.max(-24, Math.min(24, safeDouble(etDb.getText().toString(), 8)));
                runSimpleFilter("Mengatur Volume Gain " + db + " dB...", String.format(Locale.US, "volume=%.1fdB", db), "Gain");
            }));
        } else if (fid == 16) {
            actCard.addView(UiKit.makeMenuButton(this, "Proses Standarisasi Volume Loudnorm -14 LUFS Sekarang",
                    "Meratakan kekerasan audio ke standar resmi YouTube dan Spotify (-14 LUFS) dengan pembatas puncak -1.5 dBTP.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSimpleFilter("Standarisasi Loudnorm -14 LUFS...", "loudnorm=I=-14:TP=-1.5:LRA=11", "Loudnorm14");
                    }));
        } else if (fid == 17) {
            actCard.addView(UiKit.makeMenuButton(this, "Proses Mastering Podcast & Ceramah (-16 LUFS) Sekarang",
                    "Meratakan dinamika suara bicara ke standar nyaman podcast dan audiobook (-16 LUFS).",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSimpleFilter("Mastering Podcast -16 LUFS...", "loudnorm=I=-16:TP=-1.5:LRA=11", "Loudnorm16");
                    }));
        } else if (fid == 18) {
            actCard.addView(UiKit.makeMenuButton(this, "Proses Penguat Maksimal Anti-Pecah (+6 dB Limiter) Sekarang",
                    "Menaikkan volume +6 dB sekaligus menahan puncak gelombang agar tidak sember.",
                    UiKit.ACCENT_WARM, uiPrefs, v -> {
                        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSimpleFilter("Penguat Maksimal Anti-Pecah...", "volume=6dB,alimiter=limit=0.91:attack=5:release=50", "MaxLimiter");
                    }));
        } else if (fid == 19) {
            actCard.addView(UiKit.makeMenuButton(this, "Proses Penguat & Perata Vokal Studio (Vocal Compressor) Sekarang",
                    "Menebalkan suara bicara yang pelan dan menahan lonjakan keras dengan kompresor rasio 3.5:1.",
                    UiKit.ACCENT_AI, uiPrefs, v -> {
                        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSimpleFilter("Memproses Studio Vocal Compressor...", "highpass=f=85,acompressor=threshold=-20dB:ratio=3.5:attack=15:release=150:makeup=4dB", "VocalComp");
                    }));
        } else if (fid == 20) {
            actCard.addView(UiKit.makeMenuButton(this, "Proses Efek Suara Penyiar Radio FM Broadcast Sekarang",
                    "Memberikan karakter suara penyiar radio FM yang hangat, padat, dan bebas dengung.",
                    UiKit.ACCENT_AI_ALT, uiPrefs, v -> {
                        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSimpleFilter("Memproses Suara Penyiar Radio FM...", "highpass=f=80,equalizer=f=120:width_type=o:width=1.2:g=3.5,equalizer=f=3200:width_type=o:width=1.4:g=4,acompressor=threshold=-18dB:ratio=4:attack=10:release=120:makeup=5dB", "RadioFM");
                    }));
        } else if (fid == 21) {
            actCard.addView(UiKit.makeMenuButton(this, "Bersihkan Desis Noise, AC & Kipas Angin Sekarang",
                    "Membuang dengung bawah 100 Hz dan meredam desis latar sebesar 25 dB dengan FFT Denoise.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSimpleFilter("Membersihkan Desis & Noise...", "highpass=f=100,afftdn=nf=-25", "DenoiseStd");
                    }));
        } else if (fid == 22) {
            actCard.addView(UiKit.makeMenuButton(this, "Bersihkan Noise Berat & Angin Luar Ruangan Sekarang",
                    "Pembersihan ekstra kuat (-36 dB FFT Denoise + filter angin) untuk rekaman lapangan.",
                    UiKit.ACCENT_DANGER, uiPrefs, v -> {
                        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSimpleFilter("Membersihkan Noise Berat Luar Ruangan...", "highpass=f=135,lowpass=f=9000,afftdn=nf=-36:tn=1", "DenoiseHeavy");
                    }));
        } else if (fid == 23) {
            actCard.addView(UiKit.makeMenuButton(this, "1. Haluskan Desis Huruf S Tajam pada Vokal (Vocal De-Esser)",
                    "Meredam lonjakan frekuensi sibilance huruf S di area 6000-8500 Hz.",
                    UiKit.ACCENT_KB, uiPrefs, v -> {
                        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSimpleFilter("Memproses Vocal De-Esser...", "equalizer=f=6500:width_type=o:width=1.0:g=-5,equalizer=f=8200:width_type=o:width=1.2:g=-4", "DeEsser");
                    }));
            actCard.addView(UiKit.makeMenuButton(this, "2. Perjelas Rekaman Suara Jauh / Rapat Kelas (Speech Clarity Booster)",
                    "Mengangkat frekuensi percakapan manusia dan memperkeras kata-kata yang pelan dari jarak jauh.",
                    UiKit.ACCENT_WARM, uiPrefs, v -> {
                        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSimpleFilter("Memperjelas Rekaman Suara Jauh...", "highpass=f=150,lowpass=f=7500,equalizer=f=2500:width_type=o:width=1.5:g=6,acompressor=threshold=-24dB:ratio=4:makeup=7dB", "SpeechBoost");
                    }));
        } else if (fid == 24) {
            actCard.addView(UiKit.makeMenuButton(this, "Pilih dari 16 Preset Equalizer Studio & Terapkan",
                    "Membuka daftar 16 preset Equalizer (Podcast, Murottal, Bass Boost, Subwoofer, Treble, Pop, Rock, Akustik, Klasik, Lo-Fi, Bioskop).",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> showEqPresetDialog()));
        } else if (fid == 25) {
            actCard.addView(UiKit.makeMenuButton(this, "Atur 5 Pita Frekuensi Equalizer Kustom (-15 dB s/d +15 dB)",
                    "Membuka pengaturan angka dB untuk 60 Hz, 250 Hz, 1000 Hz, 4000 Hz, dan 12000 Hz.",
                    UiKit.ACCENT_KB, uiPrefs, v -> showCustom5BandEqDialog()));
        } else if (fid == 26) {
            actCard.addView(UiKit.makeMenuButton(this, "Atur Kecepatan (0,25x - 4,0x) & Nada Pitch (-12..+12 Semitone)",
                    "Membuka pengaturan angka kecepatan putar dan tinggi-rendah nada suara.",
                    UiKit.ACCENT_AI, uiPrefs, v -> showSpeedPitchDialog()));
        } else if (fid == 27) {
            actCard.addView(UiKit.makeMenuButton(this, "Pilih Karakter Suara & Terapkan",
                    "Pilih dari 8 karakter vokal: Berat Berwibawa, Narator Film, Raksasa, Muda Ceria, Chipmunk, Bicara Cepat, atau Tenang.",
                    UiKit.ACCENT_AI_ALT, uiPrefs, v -> showVoiceCharacterPresetDialog()));
        } else if (fid == 28) {
            actCard.addView(UiKit.makeMenuButton(this, "Pilih Reverb Ruangan (Masjid Agung, Kubah, Musholla, Studio, Konser, Gunung)",
                    "Pilih dari 12 akustik gema ruangan realistis bebas serak.",
                    UiKit.ACCENT_WARM, uiPrefs, v -> showReverbEchoDialog()));
        } else if (fid == 29) {
            actCard.addView(UiKit.makeMenuButton(this, "Atur Durasi Fade In & Fade Out Sekarang",
                    "Mengatur berapa detik suara muncul perlahan di awal dan memudar halus di akhir.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> showFadeDialog()));
        } else if (fid == 30) {
            actCard.addView(UiKit.makeMenuButton(this, "1. Atur Keseimbangan Speaker Kiri & Kanan (Stereo Balance)",
                    "Seimbangkan tengah, fokus kiri/kanan, atau tukar kanal kiri dan kanan.",
                    UiKit.ACCENT_KB, uiPrefs, v -> showStereoPanDialog()));
            actCard.addView(UiKit.makeMenuButton(this, "2. Perlebar Panggung Suara Stereo (Extra Stereo Widener)",
                    "Membuat suara musik stereo terdengar lebih luas dan megah di headset.",
                    UiKit.ACCENT_AI, uiPrefs, v -> {
                        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSimpleFilter("Memperlebar Panggung Stereo...", "extrastereo=m=1.8", "StereoWide");
                    }));
        } else if (fid == 31) {
            actCard.addView(UiKit.makeMenuButton(this, "1. Audio 8D Berputar Halus (Keliling Telinga Kiri-Kanan di Headset)",
                    "Suara berputar mengelilingi kepala secara lembut.",
                    UiKit.ACCENT_AI, uiPrefs, v -> {
                        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSimpleFilter("Membuat Audio 8D Berputar Halus...", "apulsator=mode=sine:hz=0.16,extrastereo=m=1.35", "8DHalus");
                    }));
            actCard.addView(UiKit.makeMenuButton(this, "2. Audio 8D Berputar Cepat + Gema Ruangan Imersif",
                    "Putaran 8D lebih cepat dilengkapi pantulan ruang stereo.",
                    UiKit.ACCENT_KB, uiPrefs, v -> {
                        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSimpleFilter("Membuat Audio 8D Cepat...", "apulsator=mode=sine:hz=0.32,aecho=0.8:0.85:40|80:0.28|0.18", "8DCepat");
                    }));
        } else if (fid == 32) {
            actCard.addView(UiKit.makeMenuButton(this, "1. Buat Musik Karaoke (Hapus / Kurangi Suara Vokal Tengah Lagu)",
                    "Meredam vokal penyanyi di kanal tengah sehingga tersisa musik pengiring.",
                    UiKit.ACCENT_WARM, uiPrefs, v -> {
                        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSimpleFilter("Membuat Musik Karaoke...", "pan=stereo|c0=c0-c1|c1=c1-c0", "Karaoke");
                    }));
            actCard.addView(UiKit.makeMenuButton(this, "2. Isolasi Fokus Vokal Tengah (Perjelas Vokal & Kurangi Instrumen Samping)",
                    "Memfokuskan suara bicara/vokal utama di tengah.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
                        runSimpleFilter("Mengisolasi Vokal Tengah...", "pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1,highpass=f=120,lowpass=f=4500", "IsolasiVokal");
                    }));
            actCard.addView(UiKit.makeMenuButton(this, "3. Pilih Efek Perangkat & Kreatif Lainnya (Chorus, Flanger, Radio AM, Telepon, Megafon, Robot)",
                    "Membuka pilihan 14 efek khusus audio.",
                    UiKit.ACCENT_KB, uiPrefs, v -> showSpecialFxDialog()));
        } else {
            actCard.addView(UiKit.makeMenuButton(this, "Pilih Aksi: Hapus Diam Otomatis, Putar Balik (Reverse), atau Looper (2x-10x)",
                    "Membuka pilihan pemangkas jeda diam otomatis, reverse audio, atau pengganda durasi loop.",
                    UiKit.ACCENT_OFFICE, uiPrefs, v -> showUtilityEffectsDialog()));
        }

        root.addView(actCard);
        sv.addView(root);
        setContentView(sv);
    }

    // ==================== EKSEKUSI POTONG, GABUNG & MIXER (DENGAN PILIH BACKSOUND APLIKASI / HP) ====================

    private void executeSingleTrim() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        long s = hasMarkedIn ? activeInMs : AudioDspEngine.parseTimeToMs(etInTime.getText().toString(), singleMeta.durationMs);
        long e = hasMarkedOut ? activeOutMs : AudioDspEngine.parseTimeToMs(etOutTime.getText().toString(), singleMeta.durationMs);
        if (e <= s) { Toast.makeText(this, "Titik Akhir harus lebih besar dari Titik Awal.", Toast.LENGTH_SHORT).show(); return; }
        final long fs = s, fe = e;
        final String fmt = getActiveFormatExt();
        final int br = getActiveBitrateKbps();
        final int sr = getActiveSampleRate();
        final int ch = getActiveChannels();
        runFfmpegJob("Memotong Audio (" + getExportSummaryText() + ")...", () -> {
            File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
            File out = new File(outDir, cleanBaseName(singleMeta.displayName) + "_Trim_" + br + "kbps_" + System.currentTimeMillis() + "." + fmt);
            AudioDspEngine.trimAudio(this, singleUri, workDir, out, fs, fe, fmt, br, sr, ch);
            return out;
        });
    }

    private void executeSingleCut() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        long s = hasMarkedIn ? activeInMs : AudioDspEngine.parseTimeToMs(etInTime.getText().toString(), singleMeta.durationMs);
        long e = hasMarkedOut ? activeOutMs : AudioDspEngine.parseTimeToMs(etOutTime.getText().toString(), singleMeta.durationMs);
        if (e <= s) { Toast.makeText(this, "Titik Akhir harus lebih besar dari Titik Awal.", Toast.LENGTH_SHORT).show(); return; }
        final long fs = s, fe = e;
        final String fmt = getActiveFormatExt();
        final int br = getActiveBitrateKbps();
        runFfmpegJob("Membuang Bagian Tengah Audio...", () -> {
            File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
            File out = new File(outDir, cleanBaseName(singleMeta.displayName) + "_Cut_" + br + "kbps_" + System.currentTimeMillis() + "." + fmt);
            AudioDspEngine.cutRangeAudio(this, singleUri, workDir, out, fs, fe, singleMeta.durationMs, fmt, br);
            return out;
        });
    }

    private void executeSingleSplit2() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        long at = hasMarkedIn ? activeInMs : AudioDspEngine.parseTimeToMs(etInTime.getText().toString(), singleMeta.durationMs);
        if (at <= 200 || at >= singleMeta.durationMs - 200) {
            Toast.makeText(this, "Tandai Titik Awal di bagian tengah durasi audio sebagai titik belah.", Toast.LENGTH_LONG).show();
            return;
        }
        final long fat = at;
        final String fmt = getActiveFormatExt();
        final int br = getActiveBitrateKbps();
        final int sr = getActiveSampleRate();
        final int ch = getActiveChannels();
        runBusyMulti("Membelah Audio Menjadi 2 Berkas...", () -> {
            File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
            String base = cleanBaseName(singleMeta.displayName);
            File f1 = new File(outDir, base + "_Bagian1_" + System.currentTimeMillis() + "." + fmt);
            File f2 = new File(outDir, base + "_Bagian2_" + System.currentTimeMillis() + "." + fmt);
            AudioDspEngine.trimAudio(this, singleUri, workDir, f1, 0L, fat, fmt, br, sr, ch);
            AudioDspEngine.trimAudio(this, singleUri, workDir, f2, fat, singleMeta.durationMs, fmt, br, sr, ch);
            List<File> res = new ArrayList<>(); res.add(f1); res.add(f2);
            return res;
        });
    }

    private void executeMakeRingtone() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        long s = hasMarkedIn ? activeInMs : AudioDspEngine.parseTimeToMs(etInTime.getText().toString(), singleMeta.durationMs);
        long e = hasMarkedOut ? activeOutMs : AudioDspEngine.parseTimeToMs(etOutTime.getText().toString(), singleMeta.durationMs);
        if (e <= s) e = Math.min(singleMeta.durationMs, s + 30000L);
        if (e - s > 30000L) e = s + 30000L;
        final long fs = s, fe = e;
        final double durS = Math.max(1.0, (fe - fs) / 1000.0);
        final double fadeOutStart = Math.max(0.5, durS - 2.0);
        final String fmt = getActiveFormatExt();
        final int br = getActiveBitrateKbps();
        runFfmpegJob("Membuat Nada Dering (Maks 30 Detik + Fade Halus)...", () -> {
            File tmp = new File(workDir, "rt_trim_" + System.currentTimeMillis() + "." + fmt);
            AudioDspEngine.trimAudio(this, singleUri, workDir, tmp, fs, fe, fmt, br);
            File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
            File out = new File(outDir, cleanBaseName(singleMeta.displayName) + "_Ringtone_" + System.currentTimeMillis() + "." + fmt);
            String chain = String.format(Locale.US, "afade=t=in:st=0:d=0.8,afade=t=out:st=%.2f:d=2.0", fadeOutStart);
            try {
                AudioDspEngine.applyAudioFilter(this, Uri.fromFile(tmp), workDir, out, chain, fmt, br);
            } finally {
                try { tmp.delete(); } catch (Throwable ignored) {}
            }
            return out;
        });
    }

    private void executeDirectFormatConvert() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        final String fmt = getActiveFormatExt();
        final int br = getActiveBitrateKbps();
        final int sr = getActiveSampleRate();
        final int ch = getActiveChannels();
        runFfmpegJob("Mengonversi ke " + getExportSummaryText() + "...", () -> {
            File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
            File out = new File(outDir, cleanBaseName(singleMeta.displayName) + "_" + br + "kbps_" + sr + "Hz." + fmt);
            AudioDspEngine.trimAudio(this, singleUri, workDir, out, 0L, 0L, fmt, br, sr, ch);
            return out;
        });
    }

    // ==================== FITUR 12: GABUNG BANYAK AUDIO + BACKSOUND APLIKASI / HP ====================

    private void renderMergeScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Gabung Banyak Audio Berurutan (Audio 1 + Audio 2 + Audio 3 dst.)"));

        LinearLayout addCard = UiKit.makeCard(this, uiPrefs);
        addCard.addView(UiKit.makeSectionLabel(this, "A. TAMBAH KLIP AUDIO ATAU BACKSOUND KE DAFTAR GABUNG", UiKit.ACCENT_KB));
        addCard.addView(UiKit.makeMenuButton(this,
                "1. Tambah Audio dari File Manager HP",
                "Pilih berkas audio dari File Manager untuk dimasukkan ke urutan daftar gabung.",
                UiKit.ACCENT_KB, uiPrefs,
                v -> OfficeUtils.launchFilePicker(this, REQ_PICK_ADD_MERGE_FM, "audio", "Tambah Audio ke Daftar Gabung")));
        addCard.addView(UiKit.makeMenuButton(this,
                "2. Tambah Audio dari Galeri / Musik HP",
                "Pilih berkas audio dari penyimpanan sistem Android ke daftar gabung.",
                UiKit.ACCENT_SYSTEM, uiPrefs,
                v -> launchSystemPicker("audio/*", REQ_PICK_ADD_MERGE_SYS)));
        addCard.addView(UiKit.makeMenuButton(this,
                "3. Tambah Backsound Musik / Efek Drama Film dari Koleksi Aplikasi",
                "Pilih musik intro/outro (Islami, Piano, Jazz, Rock, Jedag-Jedug, Sinematik) atau Efek Film langsung dari Studio Gudang Aplikasi.",
                UiKit.ACCENT_AI, uiPrefs,
                v -> StudioGudangAudioManager.showUniversalAudioSourceChooser(
                        this, uiPrefs, "Tambah Backsound / Efek ke Daftar Gabung", true, true,
                        (uri, title, durSec, isBgm) -> {
                            ClipSlot s = new ClipSlot();
                            s.uri = uri; s.title = title; s.durationMs = durSec * 1000L;
                            mergeClips.add(s);
                            rebuildMergeListUi();
                        },
                        () -> OfficeUtils.launchFilePicker(this, REQ_PICK_ADD_MERGE_FM, "audio", "Tambah Audio"),
                        () -> launchSystemPicker("audio/*", REQ_PICK_ADD_MERGE_SYS))));
        root.addView(addCard);

        mergeListContainer = new LinearLayout(this);
        mergeListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(mergeListContainer);
        rebuildMergeListUi();

        root.addView(buildExportFormatCard());

        LinearLayout optCard = UiKit.makeCard(this, uiPrefs);
        optCard.addView(UiKit.makeSectionLabel(this, "B. PENGATURAN JEDA & EKSEKUSI GABUNG", UiKit.ACCENT_OFFICE));
        TextView gapLbl = new TextView(this);
        gapLbl.setText("Jeda Hening Antar Sambungan Audio (detik, isi 0 jika langsung menyambung):");
        gapLbl.setTextColor(UiKit.textPrimary(uiPrefs));
        optCard.addView(gapLbl);
        etMergeGap = new EditText(this);
        etMergeGap.setText("0.5");
        UiKit.styleEditText(this, etMergeGap, uiPrefs);
        optCard.addView(etMergeGap);

        optCard.addView(UiKit.makeMenuButton(this,
                "Gabungkan Semua Audio di Daftar Sekarang",
                "Menyatukan seluruh klip Audio 1 sampai terakhir menjadi satu berkas utuh.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> executeMergeAll()));
        root.addView(optCard);

        sv.addView(root);
        setContentView(sv);
    }

    private void rebuildMergeListUi() {
        if (mergeListContainer == null) return;
        mergeListContainer.removeAllViews();
        if (mergeClips.isEmpty()) {
            LinearLayout emptyCard = UiKit.makeCard(this, uiPrefs);
            TextView empty = new TextView(this);
            empty.setText("Daftar Gabung Masih Kosong. Tekan tombol Tambah Audio di atas untuk memasukkan Audio 1, Audio 2, dan seterusnya.");
            empty.setTextColor(UiKit.textMuted(uiPrefs));
            emptyCard.addView(empty);
            mergeListContainer.addView(emptyCard);
            return;
        }
        for (int i = 0; i < mergeClips.size(); i++) {
            final int idx = i;
            final int num = i + 1;
            final ClipSlot slot = mergeClips.get(i);
            LinearLayout card = UiKit.makeCard(this, uiPrefs);
            TextView tv = new TextView(this);
            tv.setText("Urutan Audio " + num + ": " + slot.title + "\nDurasi: " + AudioDspEngine.formatMsShort(slot.durationMs));
            tv.setTextSize(14.5f);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setTextColor(UiKit.textPrimary(uiPrefs));
            card.addView(tv);

            LinearLayout row1 = new LinearLayout(this);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            row1.setPadding(0, UiKit.dp(this, 6), 0, 0);
            int alt = UiKit.surfaceAlt(uiPrefs), txt = UiKit.textPrimary(uiPrefs);
            row1.addView(OfficeUtils.makeRowButton(this, "Putar Audio " + num, "Putar " + slot.title,
                    UiKit.ACCENT_OFFICE, Color.WHITE, v -> playOneUriQuick(slot.uri)));
            row1.addView(OfficeUtils.makeRowButton(this, "Hapus Audio " + num, "Hapus " + slot.title,
                    UiKit.ACCENT_DANGER, Color.WHITE, v -> { mergeClips.remove(idx); rebuildMergeListUi(); }));
            card.addView(row1);

            LinearLayout row2 = new LinearLayout(this);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            row2.addView(OfficeUtils.makeRowButton(this, "Geser Naik", "Majukan urutan Audio " + num, alt, txt, v -> {
                if (idx > 0) { ClipSlot s = mergeClips.remove(idx); mergeClips.add(idx - 1, s); rebuildMergeListUi(); }
            }));
            row2.addView(OfficeUtils.makeRowButton(this, "Geser Turun", "Mundurkan urutan Audio " + num, alt, txt, v -> {
                if (idx < mergeClips.size() - 1) { ClipSlot s = mergeClips.remove(idx); mergeClips.add(idx + 1, s); rebuildMergeListUi(); }
            }));
            card.addView(row2);
            mergeListContainer.addView(card);
        }
    }

    private void executeMergeAll() {
        if (mergeClips.size() < 2) {
            Toast.makeText(this, "Tambahkan minimal 2 berkas audio untuk digabungkan.", Toast.LENGTH_LONG).show();
            return;
        }
        double gap = Math.max(0.0, Math.min(10.0, safeDouble(etMergeGap.getText().toString(), 0.0)));
        final String fmt = getActiveFormatExt();
        final int br = getActiveBitrateKbps();
        List<Uri> uris = new ArrayList<>();
        for (ClipSlot c : mergeClips) uris.add(c.uri);
        runFfmpegJob("Menggabungkan " + uris.size() + " Berkas Audio...", () -> {
            File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
            File out = new File(outDir, "Gabung_" + uris.size() + "Audio_" + br + "kbps_" + System.currentTimeMillis() + "." + fmt);
            AudioDspEngine.mergeAudios(this, uris, workDir, out, gap, fmt, br);
            return out;
        });
    }

    // ==================== FITUR 13: MIXER MULTI-TRACK & BACKSOUND APLIKASI / HP ====================

    private void renderMixerScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Campur Vokal & Backsound Musik Latar (Multi-Track Mixer)"));

        LinearLayout addCard = UiKit.makeCard(this, uiPrefs);
        addCard.addView(UiKit.makeSectionLabel(this, "A. TAMBAH SUARA UTAMA ATAU BACKSOUND MUSIK KE MIXER", UiKit.ACCENT_KB));
        addCard.addView(UiKit.makeMenuButton(this,
                "1. Pilih Backsound Musik / Efek Drama Film dari Koleksi Aplikasi (1-2 Menit)",
                "Pilih Backsound Islami, Piano Santai, Jazz, Rock, Jedag-Jedug, Tegang/Horor, Sinematik, atau Efek Film dari aplikasi (otomatis dipotong pas mengikuti durasi suara utama).",
                UiKit.ACCENT_AI, uiPrefs,
                v -> StudioGudangAudioManager.showUniversalAudioSourceChooser(
                        this, uiPrefs, "Pilih Backsound Musik Latar / Efek", true, true,
                        (uri, title, durSec, isBgm) -> {
                            AudioDspEngine.MixTrackSpec tr = new AudioDspEngine.MixTrackSpec();
                            tr.uri = uri; tr.title = title;
                            tr.volume = mixTracks.isEmpty() ? 1.0f : 0.30f;
                            tr.loop = !mixTracks.isEmpty() && isBgm;
                            mixTracks.add(tr);
                            rebuildMixerListUi();
                        },
                        () -> OfficeUtils.launchFilePicker(this, REQ_PICK_ADD_MIX_FM, "audio", "Tambah Track dari HP"),
                        () -> launchSystemPicker("audio/*", REQ_PICK_ADD_MIX_SYS))));
        addCard.addView(UiKit.makeMenuButton(this,
                "2. Tambah Vokal / Backsound Sendiri dari File Manager HP",
                "Pilih berkas audio milik Anda sendiri dari File Manager.",
                UiKit.ACCENT_KB, uiPrefs,
                v -> OfficeUtils.launchFilePicker(this, REQ_PICK_ADD_MIX_FM, "audio", "Tambah Track ke Mixer")));
        addCard.addView(UiKit.makeMenuButton(this,
                "3. Tambah Vokal / Backsound Sendiri dari Galeri atau Musik HP",
                "Pilih berkas audio milik Anda sendiri dari penyimpanan sistem Android.",
                UiKit.ACCENT_SYSTEM, uiPrefs,
                v -> launchSystemPicker("audio/*", REQ_PICK_ADD_MIX_SYS)));
        root.addView(addCard);

        mixListContainer = new LinearLayout(this);
        mixListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(mixListContainer);
        rebuildMixerListUi();

        root.addView(buildExportFormatCard());

        LinearLayout optCard = UiKit.makeCard(this, uiPrefs);
        optCard.addView(UiKit.makeSectionLabel(this, "B. PENGATURAN AUTO-DUCKING & EKSPOR MIXER", UiKit.ACCENT_AI));
        cbMixDucking = new CheckBox(this);
        cbMixDucking.setText("Aktifkan Auto-Ducking Pintar (Volume Backsound otomatis mengecil saat Vokal Track 1 berbicara)");
        cbMixDucking.setChecked(true);
        cbMixDucking.setTextColor(UiKit.textPrimary(uiPrefs));
        optCard.addView(cbMixDucking);

        optCard.addView(UiKit.makeMenuButton(this,
                "Campur dan Simpan Semua Track Sekarang",
                "Menyatukan vokal utama dan backsound musik (backsound otomatis dipotong mengikuti panjang track utama).",
                UiKit.ACCENT_AI, uiPrefs, v -> executeMixAll()));
        root.addView(optCard);

        sv.addView(root);
        setContentView(sv);
    }

    private void rebuildMixerListUi() {
        if (mixListContainer == null) return;
        mixListContainer.removeAllViews();
        if (mixTracks.isEmpty()) {
            LinearLayout emptyCard = UiKit.makeCard(this, uiPrefs);
            TextView empty = new TextView(this);
            empty.setText("Belum Ada Track di Mixer. Tambahkan Track 1 (Vokal Utama) lalu tambahkan Track 2 (Backsound Musik dari Aplikasi atau HP).");
            empty.setTextColor(UiKit.textMuted(uiPrefs));
            emptyCard.addView(empty);
            mixListContainer.addView(emptyCard);
            return;
        }
        for (int i = 0; i < mixTracks.size(); i++) {
            final int idx = i;
            final int num = i + 1;
            final AudioDspEngine.MixTrackSpec tr = mixTracks.get(i);
            LinearLayout card = UiKit.makeCard(this, uiPrefs);
            TextView tv = new TextView(this);
            String role = (i == 0) ? " (Track Utama / Vokal)" : " (Backsound Musik / Efek)";
            tv.setText(String.format(Locale.ROOT, "Track %d%s: %s\nVolume: %d%% • Mulai Detik: %.1f dtk • Loop & Potong Otomatis: %s",
                    num, role, tr.title, Math.round(tr.volume * 100f), tr.delayMs / 1000.0, tr.loop ? "Aktif" : "Tidak"));
            tv.setTextSize(14);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setTextColor(UiKit.textPrimary(uiPrefs));
            card.addView(tv);

            LinearLayout row1 = new LinearLayout(this);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            row1.setPadding(0, UiKit.dp(this, 6), 0, 0);
            int alt = UiKit.surfaceAlt(uiPrefs), txt = UiKit.textPrimary(uiPrefs);
            row1.addView(OfficeUtils.makeRowButton(this, "Volume -15%", "Kecilkan volume Track " + num, alt, txt, v -> {
                tr.volume = Math.max(0.05f, tr.volume - 0.15f); rebuildMixerListUi();
            }));
            row1.addView(OfficeUtils.makeRowButton(this, "Volume +15%", "Besarkan volume Track " + num, alt, txt, v -> {
                tr.volume = Math.min(2.5f, tr.volume + 0.15f); rebuildMixerListUi();
            }));
            card.addView(row1);

            LinearLayout row2 = new LinearLayout(this);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            row2.addView(OfficeUtils.makeRowButton(this, "Atur Detail", "Atur volume, detik mulai & loop",
                    UiKit.ACCENT_KB, Color.WHITE, v -> editTrackDetailDialog(tr)));
            row2.addView(OfficeUtils.makeRowButton(this, "Putar", "Putar Track " + num,
                    UiKit.ACCENT_OFFICE, Color.WHITE, v -> playOneUriQuick(tr.uri)));
            row2.addView(OfficeUtils.makeRowButton(this, "Hapus", "Hapus Track " + num,
                    UiKit.ACCENT_DANGER, Color.WHITE, v -> { mixTracks.remove(idx); rebuildMixerListUi(); }));
            card.addView(row2);
            mixListContainer.addView(card);
        }
    }

    private void editTrackDetailDialog(final AudioDspEngine.MixTrackSpec tr) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 10), UiKit.dp(this, 14), UiKit.dp(this, 6));
        EditText volE = new EditText(this); volE.setText(String.valueOf(Math.round(tr.volume * 100f))); UiKit.styleEditText(this, volE, uiPrefs);
        EditText delayE = new EditText(this); delayE.setText(String.format(Locale.US, "%.1f", tr.delayMs / 1000.0)); UiKit.styleEditText(this, delayE, uiPrefs);
        CheckBox cbLp = new CheckBox(this);
        cbLp.setText("Ulangi & potong otomatis mengikuti durasi Track Utama");
        cbLp.setTextColor(UiKit.textPrimary(uiPrefs));
        cbLp.setChecked(tr.loop);
        TextView t1 = new TextView(this); t1.setText("Persentase Volume Track (5% sampai 250%):"); t1.setTextColor(UiKit.textPrimary(uiPrefs)); box.addView(t1); box.addView(volE);
        TextView t2 = new TextView(this); t2.setText("Detik Mulai Masuk (Delay dalam detik):"); t2.setTextColor(UiKit.textPrimary(uiPrefs)); box.addView(t2); box.addView(delayE);
        box.addView(cbLp);
        UiKit.dialog(this, uiPrefs).setTitle("Pengaturan Track: " + tr.title).setView(box).setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (d, w) -> {
                    tr.volume = (float) Math.max(0.05, Math.min(2.5, safeDouble(volE.getText().toString(), 100) / 100.0));
                    tr.delayMs = Math.max(0L, Math.round(safeDouble(delayE.getText().toString(), 0) * 1000.0));
                    tr.loop = cbLp.isChecked();
                    rebuildMixerListUi();
                }).show();
    }

    private void executeMixAll() {
        if (mixTracks.size() < 2) {
            Toast.makeText(this, "Tambahkan minimal 2 track (Vokal + Backsound) untuk dicampur.", Toast.LENGTH_LONG).show();
            return;
        }
        boolean duck = cbMixDucking.isChecked();
        final String fmt = getActiveFormatExt();
        final int br = getActiveBitrateKbps();
        List<AudioDspEngine.MixTrackSpec> copy = new ArrayList<>(mixTracks);
        runFfmpegJob("Mencampur " + copy.size() + " Track Audio...", () -> {
            File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
            File out = new File(outDir, "Mix_" + copy.size() + "Track_" + br + "kbps_" + System.currentTimeMillis() + "." + fmt);
            AudioDspEngine.mixMultiTracks(this, copy, workDir, out, duck, fmt, br);
            return out;
        });
    }

    // ==================== FITUR 1: AUDIO KE VIDEO MP4 + PASANG FOTO COVER ====================

    private void renderAudioToVideoScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Audio ke Video MP4 + Pasang Foto Cover / Sampul"));
        root.addView(buildSimplePlayerAndCutCard(false));

        LinearLayout covCard = UiKit.makeCard(this, uiPrefs);
        covCard.addView(UiKit.makeSectionLabel(this, "B. PILIH FOTO COVER / SAMPUL VIDEO (OPSIONAL)", UiKit.ACCENT_WARM));
        TextView tvCov = new TextView(this);
        tvCov.setTextSize(14);
        tvCov.setTextColor(UiKit.textPrimary(uiPrefs));
        tvCov.setText(coverImageUri == null
                ? "Foto Cover Saat Ini: Belum ada foto dipilih (akan memakai Gelombang Spektrum Suara Bergerak)."
                : "Foto Cover Aktif: " + coverImageName);
        covCard.addView(tvCov);

        LinearLayout covRow = new LinearLayout(this);
        covRow.setOrientation(LinearLayout.HORIZONTAL);
        covRow.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 4));
        covRow.addView(OfficeUtils.makeRowButton(this, "Pilih Foto Cover (File Manager)", "Pilih gambar dari File Manager",
                UiKit.ACCENT_KB, Color.WHITE, v -> OfficeUtils.launchFilePicker(this, REQ_PICK_COVER_IMG_FM, "image", "Pilih Foto Cover Video")));
        covRow.addView(OfficeUtils.makeRowButton(this, "Pilih Foto Cover (Galeri HP)", "Pilih gambar dari Galeri HP",
                UiKit.ACCENT_SYSTEM, Color.WHITE, v -> launchSystemPicker("image/*", REQ_PICK_COVER_IMG_SYS)));
        covCard.addView(covRow);
        root.addView(covCard);

        LinearLayout optCard = UiKit.makeCard(this, uiPrefs);
        optCard.addView(UiKit.makeSectionLabel(this, "C. ATUR RASIO LAYAR, GAYA TAMPILAN & BUAT VIDEO MP4", UiKit.ACCENT_AI));
        int txt = UiKit.textPrimary(uiPrefs);

        final String[] ratioLabels = {
                "16:9 Lanskap YouTube & TV (1280 x 720 HD)",
                "9:16 Tegak TikTok, Reels & Status WhatsApp (720 x 1280 HD)",
                "1:1 Persegi Feed Instagram (1080 x 1080)",
                "4:5 Potret Sosial Media (864 x 1080)"
        };
        final String[] ratioCodes = {"16:9", "9:16", "1:1", "4:5"};
        TextView lRatio = new TextView(this); lRatio.setText("Pilih Ukuran Rasio Layar Video MP4:"); lRatio.setTextColor(txt); optCard.addView(lRatio);
        final Spinner spRatio = new Spinner(this);
        spRatio.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ratioLabels));
        optCard.addView(spRatio);

        final String[] styleLabels = {
                "Foto Cover Tengah + Latar Belakang Blur Estetis (Ala CapCut)",
                "Foto Cover + Gelombang Spektrum Suara Bergerak di Bawah",
                "Foto Cover Penuh (Latar Hitam Rapi)",
                "Gelombang Spektrum Suara Bergerak Tanpa Foto (Waveform Visualizer)",
                "Latar Warna Biru Tua Studio Gelap"
        };
        final String[] styleCodes = {"cover_blur", "cover_wave", "cover_plain", "waveform", "solid_navy"};
        TextView lStyle = new TextView(this); lStyle.setText("Pilih Gaya Tampilan Visual Video:"); lStyle.setTextColor(txt); lStyle.setPadding(0, UiKit.dp(this, 6), 0, 0); optCard.addView(lStyle);
        final Spinner spStyle = new Spinner(this);
        spStyle.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, styleLabels));
        optCard.addView(spStyle);

        optCard.addView(UiKit.makeMenuButton(this,
                "Buat Video MP4 dari Audio Sekarang",
                "Merender berkas audio dan foto cover menjadi video MP4 berkualitas tinggi.",
                UiKit.ACCENT_AI, uiPrefs, v -> {
                    if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_LONG).show(); return; }
                    final String ratio = ratioCodes[spRatio.getSelectedItemPosition()];
                    final String style = styleCodes[spStyle.getSelectedItemPosition()];
                    final int abr = getActiveBitrateKbps();
                    releasePlayer();
                    ProgressDialog pd = ProgressDialog.show(this, "Membuat Video MP4 dari Audio...",
                            "Sedang merender Audio ke Video MP4 dengan FFmpeg...", true, false);
                    worker.submit(() -> {
                        try {
                            File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Video");
                            File out = new File(outDir, cleanBaseName(singleMeta.displayName) + "_AudioKeVideo_" + System.currentTimeMillis() + ".mp4");
                            AudioDspEngine.renderAudioToVideoMp4(this, singleUri, coverImageUri, workDir, out, ratio, style, abr);
                            mainHandler.post(() -> {
                                if (pd.isShowing()) pd.dismiss();
                                UiKit.dialog(this, uiPrefs)
                                        .setTitle("Audio ke Video MP4 Selesai")
                                        .setMessage("Video MP4 berhasil dibuat dan disimpan ke:\n" + out.getAbsolutePath()
                                                + "\n\nUkuran Video: " + fmtSize(out.length()))
                                        .setNeutralButton("Bagikan Video MP4", (d, w) -> shareFile(out, "video/*"))
                                        .setPositiveButton("Buka di Studio Editor Video", (d, w) -> {
                                            Intent vi = new Intent(this, VideoEditorStudioActivity.class);
                                            vi.putExtra(VideoEditorStudioActivity.EXTRA_VIDEO_URI, Uri.fromFile(out).toString());
                                            startActivity(vi);
                                        })
                                        .setNegativeButton("Tutup", null)
                                        .show();
                            });
                        } catch (Throwable t) {
                            mainHandler.post(() -> {
                                if (pd.isShowing()) pd.dismiss();
                                Toast.makeText(this, "Gagal membuat video: " + t.getMessage(), Toast.LENGTH_LONG).show();
                            });
                        }
                    });
                }));
        root.addView(optCard);
        sv.addView(root);
        setContentView(sv);
    }

    // ==================== FITUR 4: PASANG FOTO COVER SAMPUL & TAG METADATA MP3 ====================

    private void renderCoverTagScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(uiPrefs));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 12);
        root.setPadding(p, p, p, p);

        root.addView(buildSubHeaderAndBack("Pasang Foto Cover Sampul & Edit Tag Metadata MP3"));
        root.addView(buildSimplePlayerAndCutCard(false));

        LinearLayout covCard = UiKit.makeCard(this, uiPrefs);
        covCard.addView(UiKit.makeSectionLabel(this, "B. PILIH GAMBAR FOTO SAMPUL (COVER ART MP3)", UiKit.ACCENT_WARM));
        TextView tvCov = new TextView(this);
        tvCov.setTextSize(14);
        tvCov.setTextColor(UiKit.textPrimary(uiPrefs));
        tvCov.setText(coverImageUri == null ? "Foto Sampul MP3: Belum ada gambar dipilih." : "Foto Sampul Aktif: " + coverImageName);
        covCard.addView(tvCov);

        LinearLayout covRow = new LinearLayout(this);
        covRow.setOrientation(LinearLayout.HORIZONTAL);
        covRow.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 4));
        covRow.addView(OfficeUtils.makeRowButton(this, "Pilih Foto Sampul (File Manager)", "Pilih gambar dari File Manager",
                UiKit.ACCENT_KB, Color.WHITE, v -> OfficeUtils.launchFilePicker(this, REQ_PICK_COVER_IMG_FM, "image", "Pilih Foto Sampul MP3")));
        covRow.addView(OfficeUtils.makeRowButton(this, "Pilih Foto Sampul (Galeri HP)", "Pilih gambar dari Galeri HP",
                UiKit.ACCENT_SYSTEM, Color.WHITE, v -> launchSystemPicker("image/*", REQ_PICK_COVER_IMG_SYS)));
        covCard.addView(covRow);
        root.addView(covCard);

        LinearLayout tagCard = UiKit.makeCard(this, uiPrefs);
        tagCard.addView(UiKit.makeSectionLabel(this, "C. ISI INFORMASI JUDUL, ARTIS & ALBUM", UiKit.ACCENT_OFFICE));
        int txt = UiKit.textPrimary(uiPrefs);

        TextView l1 = new TextView(this); l1.setText("Judul Lagu / Rekaman (Title):"); l1.setTextColor(txt); tagCard.addView(l1);
        final EditText etTitle = new EditText(this);
        if (singleUri != null) etTitle.setText(cleanBaseName(singleMeta.displayName).replace('_', ' '));
        UiKit.styleEditText(this, etTitle, uiPrefs); tagCard.addView(etTitle);

        TextView l2 = new TextView(this); l2.setText("Nama Artis / Pembicara (Artist):"); l2.setTextColor(txt); tagCard.addView(l2);
        final EditText etArtist = new EditText(this); UiKit.styleEditText(this, etArtist, uiPrefs); tagCard.addView(etArtist);

        TextView l3 = new TextView(this); l3.setText("Nama Album / Koleksi (Album):"); l3.setTextColor(txt); tagCard.addView(l3);
        final EditText etAlbum = new EditText(this); UiKit.styleEditText(this, etAlbum, uiPrefs); tagCard.addView(etAlbum);

        tagCard.addView(UiKit.makeMenuButton(this,
                "Simpan Berkas MP3 dengan Foto Sampul & Tag Sekarang",
                "Menyematkan foto cover dan informasi metadata langsung ke dalam berkas MP3.",
                UiKit.ACCENT_OFFICE, uiPrefs, v -> {
                    if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_LONG).show(); return; }
                    final String titleStr = etTitle.getText().toString().trim();
                    final String artistStr = etArtist.getText().toString().trim();
                    final String albumStr = etAlbum.getText().toString().trim();
                    final int br = getActiveBitrateKbps();
                    runFfmpegJob("Menyimpan Foto Cover & Tag Metadata MP3...", () -> {
                        File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
                        String base = titleStr.isEmpty() ? cleanBaseName(singleMeta.displayName) : cleanBaseName(titleStr);
                        File out = new File(outDir, base + "_CoverTag_" + System.currentTimeMillis() + ".mp3");
                        AudioDspEngine.embedAudioCoverArtAndTags(this, singleUri, coverImageUri, workDir, out, titleStr, artistStr, albumStr, br);
                        return out;
                    });
                }));
        root.addView(tagCard);
        sv.addView(root);
        setContentView(sv);
    }

    // ==================== DIALOG PARAMETER DSP ====================

    private void showFadeDialog() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 10), UiKit.dp(this, 14), UiKit.dp(this, 6));
        EditText fi = new EditText(this); fi.setText("1.5"); UiKit.styleEditText(this, fi, uiPrefs);
        EditText fo = new EditText(this); fo.setText("2.5"); UiKit.styleEditText(this, fo, uiPrefs);
        TextView t1 = new TextView(this); t1.setText("Durasi Fade In di Awal Audio (detik):"); t1.setTextColor(UiKit.textPrimary(uiPrefs)); box.addView(t1); box.addView(fi);
        TextView t2 = new TextView(this); t2.setText("Durasi Fade Out di Akhir Audio (detik):"); t2.setTextColor(UiKit.textPrimary(uiPrefs)); box.addView(t2); box.addView(fo);
        UiKit.dialog(this, uiPrefs).setTitle("Atur Fade In dan Fade Out").setView(box).setNegativeButton("Batal", null)
                .setPositiveButton("Terapkan", (d, w) -> {
                    double inS = Math.max(0.0, safeDouble(fi.getText().toString(), 1.5));
                    double outS = Math.max(0.0, safeDouble(fo.getText().toString(), 2.5));
                    double durS = Math.max(0.5, singleMeta.durationMs / 1000.0);
                    List<String> parts = new ArrayList<>();
                    if (inS > 0.05) parts.add(String.format(Locale.US, "afade=t=in:st=0:d=%.2f", inS));
                    if (outS > 0.05 && durS > outS) parts.add(String.format(Locale.US, "afade=t=out:st=%.2f:d=%.2f", durS - outS, outS));
                    if (parts.isEmpty()) parts.add("anull");
                    runSimpleFilter("Menerapkan Fade In/Out...", String.join(",", parts), "Fade");
                }).show();
    }

    private void showStereoPanDialog() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        String[] names = {
                "Seimbang Tengah (Gabung Kiri & Kanan Rata)",
                "Fokus Speaker Kiri 80% + Kanan 20%",
                "Fokus Speaker Kanan 80% + Kiri 20%",
                "Hanya Speaker Kiri Saja (100% Kiri)",
                "Hanya Speaker Kanan Saja (100% Kanan)",
                "Tukar Posisi Speaker Kiri dan Kanan (Swap Stereo)"
        };
        String[] chains = {
                "pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1",
                "pan=stereo|c0=0.8*c0+0.2*c1|c1=0.2*c1",
                "pan=stereo|c0=0.2*c0|c1=0.2*c0+0.8*c1",
                "pan=stereo|c0=c0|c1=0*c0",
                "pan=stereo|c0=0*c1|c1=c1",
                "pan=stereo|c0=c1|c1=c0"
        };
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        UiKit.dialog(this, uiPrefs).setTitle("Atur Keseimbangan Stereo").setView(sp).setNegativeButton("Batal", null)
                .setPositiveButton("Terapkan", (d, w) -> runSimpleFilter("Mengatur Keseimbangan Stereo...", chains[sp.getSelectedItemPosition()], "StereoPan")).show();
    }

    private void showEqPresetDialog() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        String[] names = {
                "1. Podcast & Vokal Jernih (Potong Dengung + Angkat Artikulasi)",
                "2. Murottal & Ceramah Khidmat (Bulat, Hangat & Jelas)",
                "3. Bass Boost Mantap (Dentuman Bass Padat)",
                "4. Mega Bass Subwoofer (Ekstra Frekuensi Rendah Dalam)",
                "5. Treble Detail & Kristal (Suara Atas Gemerincing Jernih)",
                "6. Vokal Hangat Studio (Tebal di Area Low-Mid)",
                "7. Vokal Pria Berwibawa (Tegas & Dalam)",
                "8. Vokal Wanita Jernih & Halus (Lembut Tanpa Tajam)",
                "9. Kurangi Suara Desis Huruf S (Smooth Sibilance)",
                "10. Lo-Fi Hangat Santai (Redam Frekuensi Tinggi Tajam)",
                "11. Musik Pop Modern (Bass & Treble Seimbang Segar)",
                "12. Musik Rock & Gitar Bertenaga (Punchy Mid & Bass)",
                "13. Musik Akustik & Petikan Natural",
                "14. Musik Klasik & Orkestra Luas",
                "15. Kejernihan Pidato & Rapat (Fokus Kata-Kata)",
                "16. Bioskop Surround (Efek Sinematik Dalam)"
        };
        String[] chains = {
                "highpass=f=90,equalizer=f=250:width_type=o:width=1.5:g=-2,equalizer=f=3000:width_type=o:width=1.5:g=5",
                "highpass=f=75,equalizer=f=160:width_type=o:width=1.4:g=3,equalizer=f=2600:width_type=o:width=1.5:g=3.5,equalizer=f=7000:width_type=o:width=1.5:g=2",
                "equalizer=f=80:width_type=o:width=1.5:g=7,equalizer=f=200:width_type=o:width=1.5:g=4,alimiter=limit=0.95",
                "equalizer=f=55:width_type=o:width=1.3:g=9,equalizer=f=130:width_type=o:width=1.4:g=5,alimiter=limit=0.95",
                "equalizer=f=4000:width_type=o:width=1.5:g=5,equalizer=f=10000:width_type=o:width=1.5:g=6",
                "equalizer=f=220:width_type=o:width=1.5:g=4,equalizer=f=1200:width_type=o:width=1.5:g=3",
                "highpass=f=70,equalizer=f=130:width_type=o:width=1.3:g=4.5,equalizer=f=2400:width_type=o:width=1.4:g=3.5",
                "highpass=f=100,equalizer=f=320:width_type=o:width=1.4:g=3,equalizer=f=3500:width_type=o:width=1.4:g=3,equalizer=f=6800:width_type=o:width=1.2:g=-3",
                "equalizer=f=5500:width_type=o:width=1.2:g=-5,equalizer=f=9000:width_type=o:width=1.5:g=-5",
                "equalizer=f=250:width_type=o:width=1.5:g=3,lowpass=f=3800",
                "equalizer=f=90:width_type=o:width=1.4:g=4.5,equalizer=f=1000:width_type=o:width=1.5:g=-1.5,equalizer=f=4500:width_type=o:width=1.5:g=4,equalizer=f=11000:width_type=o:width=1.5:g=4",
                "equalizer=f=75:width_type=o:width=1.3:g=5.5,equalizer=f=400:width_type=o:width=1.4:g=-2,equalizer=f=2800:width_type=o:width=1.4:g=5,equalizer=f=8500:width_type=o:width=1.4:g=4.5",
                "equalizer=f=110:width_type=o:width=1.4:g=2.5,equalizer=f=2200:width_type=o:width=1.4:g=3.5,equalizer=f=9500:width_type=o:width=1.5:g=4",
                "equalizer=f=65:width_type=o:width=1.5:g=3.5,equalizer=f=3500:width_type=o:width=1.5:g=2.5,equalizer=f=11000:width_type=o:width=1.5:g=3.5",
                "highpass=f=130,lowpass=f=7800,equalizer=f=1800:width_type=o:width=1.4:g=5,equalizer=f=3400:width_type=o:width=1.4:g=4",
                "equalizer=f=60:width_type=o:width=1.3:g=6,equalizer=f=350:width_type=o:width=1.4:g=-2.5,equalizer=f=3200:width_type=o:width=1.4:g=4,extrastereo=m=1.45"
        };
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        UiKit.dialog(this, uiPrefs).setTitle("Pilih Preset Equalizer").setView(sp).setNegativeButton("Batal", null)
                .setPositiveButton("Terapkan Equalizer", (d, w) -> runSimpleFilter("Memproses Equalizer...", chains[sp.getSelectedItemPosition()], "EQ")).show();
    }

    private void showCustom5BandEqDialog() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 10), UiKit.dp(this, 14), UiKit.dp(this, 6));
        int txt = UiKit.textPrimary(uiPrefs);
        EditText b1 = makeBandInput(box, txt, "Band 1 — 60 Hz (Sub-Bass) [dB]:", "4");
        EditText b2 = makeBandInput(box, txt, "Band 2 — 250 Hz (Low-Mid) [dB]:", "1");
        EditText b3 = makeBandInput(box, txt, "Band 3 — 1000 Hz (Mid Vokal) [dB]:", "2");
        EditText b4 = makeBandInput(box, txt, "Band 4 — 4000 Hz (High-Mid Artikulasi) [dB]:", "3");
        EditText b5 = makeBandInput(box, txt, "Band 5 — 12000 Hz (Treble Udara) [dB]:", "3");
        ScrollView sv = new ScrollView(this); sv.addView(box);
        UiKit.dialog(this, uiPrefs).setTitle("Equalizer Kustom 5-Band").setView(sv).setNegativeButton("Batal", null)
                .setPositiveButton("Terapkan EQ Kustom", (d, w) -> {
                    double g1 = Math.max(-15, Math.min(15, safeDouble(b1.getText().toString(), 0)));
                    double g2 = Math.max(-15, Math.min(15, safeDouble(b2.getText().toString(), 0)));
                    double g3 = Math.max(-15, Math.min(15, safeDouble(b3.getText().toString(), 0)));
                    double g4 = Math.max(-15, Math.min(15, safeDouble(b4.getText().toString(), 0)));
                    double g5 = Math.max(-15, Math.min(15, safeDouble(b5.getText().toString(), 0)));
                    String chain = String.format(Locale.US,
                            "equalizer=f=60:width_type=o:width=1.4:g=%.1f,"
                                    + "equalizer=f=250:width_type=o:width=1.4:g=%.1f,"
                                    + "equalizer=f=1000:width_type=o:width=1.4:g=%.1f,"
                                    + "equalizer=f=4000:width_type=o:width=1.4:g=%.1f,"
                                    + "equalizer=f=12000:width_type=o:width=1.4:g=%.1f,alimiter=limit=0.96",
                            g1, g2, g3, g4, g5);
                    runSimpleFilter("Menerapkan Equalizer 5-Band...", chain, "CustomEQ");
                }).show();
    }

    private EditText makeBandInput(LinearLayout parent, int txtColor, String label, String defVal) {
        TextView tv = new TextView(this); tv.setText(label); tv.setTextColor(txtColor); tv.setTextSize(13); parent.addView(tv);
        EditText et = new EditText(this); et.setText(defVal); UiKit.styleEditText(this, et, uiPrefs); parent.addView(et);
        return et;
    }

    private void showSpeedPitchDialog() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 10), UiKit.dp(this, 14), UiKit.dp(this, 6));
        EditText spdE = new EditText(this); spdE.setText("1.0"); UiKit.styleEditText(this, spdE, uiPrefs);
        EditText pitE = new EditText(this); pitE.setText("0"); UiKit.styleEditText(this, pitE, uiPrefs);
        CheckBox cbKeep = new CheckBox(this);
        cbKeep.setText("Pertahankan tempo/durasi asli saat mengubah nada Pitch");
        cbKeep.setTextColor(UiKit.textPrimary(uiPrefs));
        cbKeep.setChecked(true);
        TextView t1 = new TextView(this); t1.setText("Kecepatan Putar (0.25 s.d 4.0, normal = 1.0):"); t1.setTextColor(UiKit.textPrimary(uiPrefs)); box.addView(t1); box.addView(spdE);
        TextView t2 = new TextView(this); t2.setText("Nada Pitch Semitone (-12 s.d +12, normal = 0):"); t2.setTextColor(UiKit.textPrimary(uiPrefs)); box.addView(t2); box.addView(pitE);
        box.addView(cbKeep);
        UiKit.dialog(this, uiPrefs).setTitle("Atur Kecepatan & Nada Pitch").setView(box).setNegativeButton("Batal", null)
                .setPositiveButton("Terapkan", (d, w) -> {
                    float spd = (float) safeDouble(spdE.getText().toString(), 1.0);
                    float pit = (float) safeDouble(pitE.getText().toString(), 0.0);
                    String chain = AudioDspEngine.buildSpeedPitchFilter(spd, pit, cbKeep.isChecked());
                    if (chain.isEmpty()) chain = "anull";
                    runSimpleFilter("Mengubah Kecepatan & Pitch...", chain, "SpeedPitch");
                }).show();
    }

    private void showVoiceCharacterPresetDialog() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        String[] names = {
                "1. Suara Berat & Berwibawa (Pitch -2.5 Semitone, Tempo Tetap)",
                "2. Suara Narator Film Dalam (Pitch -4 Semitone + Bass Hangat)",
                "3. Suara Raksasa / Monster Dalam (Pitch -7 Semitone)",
                "4. Suara Muda & Ceria (Pitch +3 Semitone, Tempo Tetap)",
                "5. Suara Karakter Kartun / Chipmunk (Pitch +7 Semitone)",
                "6. Bicara Cepat & Padat (Tempo 1.25x, Nada Normal)",
                "7. Bicara Cepat Kuliah / Podcast (Tempo 1.50x, Nada Normal)",
                "8. Bicara Tenang & Lambat Jelas (Tempo 0.85x, Nada Normal)"
        };
        float[] speeds = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.25f, 1.50f, 0.85f};
        float[] pitches = {-2.5f, -4.0f, -7.0f, 3.0f, 7.0f, 0.0f, 0.0f, 0.0f};
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        UiKit.dialog(this, uiPrefs).setTitle("Pilih Karakter Suara").setView(sp).setNegativeButton("Batal", null)
                .setPositiveButton("Terapkan Karakter", (d, w) -> {
                    int idx = sp.getSelectedItemPosition();
                    String chain = AudioDspEngine.buildSpeedPitchFilter(speeds[idx], pitches[idx], true);
                    runSimpleFilter("Mengubah Karakter Suara...", chain, "Karakter");
                }).show();
    }

    private void showReverbEchoDialog() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        String[] names = {
                "1. Reverb Masjid Agung Megah (Gema Luas, Halus & Khidmat)",
                "2. Reverb Aula Kubah Besar (Pantulan Panjang Mengalun)",
                "3. Reverb Musholla / Ruangan Sedang (Gema Hangat & Dekat)",
                "4. Reverb Studio Rekaman Rapat (Akustik Profesional Bersih)",
                "5. Reverb Panggung Konser (Megah & Lebar)",
                "6. Reverb Kamar Akustik (Pantulan Dinding Natural)",
                "7. Reverb Gua Dalam (Gema Misterius Berlapis)",
                "8. Echo Kamar Kosong (Pantulan Tunggal Jelas)",
                "9. Echo Tebing Gunung Epik (Pantulan Berulang Jauh)",
                "10. Echo Lembah Panjang (Pantulan Dramatis Berlapis)",
                "11. Slapback Delay Klasik (Tebal untuk Vokal Pidato)",
                "12. Double Track Vocal (Efek Vokal Ganda Tebal)"
        };
        String[] chains = {
                "aecho=0.8:0.88:55|105|175|260:0.42|0.34|0.26|0.18",
                "aecho=0.8:0.90:70|140|230|340:0.48|0.38|0.28|0.20",
                "aecho=0.8:0.86:35|75|125:0.35|0.25|0.15",
                "aecho=0.8:0.85:22|42:0.28|0.18",
                "aecho=0.8:0.89:48|96|160|240:0.40|0.32|0.24|0.16,extrastereo=m=1.3",
                "aecho=0.8:0.84:28|58:0.34|0.22",
                "aecho=0.8:0.91:85|170|275|400:0.54|0.44|0.35|0.26",
                "aecho=0.8:0.85:160:0.45",
                "aecho=0.8:0.90:320|640:0.52|0.32",
                "aecho=0.8:0.90:250|500|750:0.48|0.34|0.20",
                "aecho=0.85:0.85:75:0.38",
                "aecho=0.85:0.85:24|38:0.35|0.28,extrastereo=m=1.25"
        };
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        UiKit.dialog(this, uiPrefs).setTitle("Pilih Reverb & Echo Ruangan").setView(sp).setNegativeButton("Batal", null)
                .setPositiveButton("Terapkan Reverb", (d, w) -> runSimpleFilter("Merender " + names[sp.getSelectedItemPosition()] + "...", chains[sp.getSelectedItemPosition()], "Reverb")).show();
    }

    private void showSpecialFxDialog() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        String[] names = {
                "1. Chorus Paduan Suara (Suara Terdengar Banyak & Harmonis)",
                "2. Flanger Jet Stereo (Efek Sapuan Gelombang Musik)",
                "3. Phaser Ruang Angkasa (Efek Fase Mengayun)",
                "4. Tremolo Bergetar Halus (Ayunan Volume Klasik)",
                "5. Vibrato Nada (Ayunan Pitch Ekspresif)",
                "6. Efek Radio AM Jadul (Suara Radio Klasik)",
                "7. Efek Suara Telepon / Walkie-Talkie (Fokus Mid Sempit)",
                "8. Efek Megafon / Toa Pengumuman Lapangan",
                "9. Efek Kaset Tape / Vinyl Tua (Lo-Fi Nostalgia)",
                "10. Efek Suara Robot Metalik"
        };
        String[] chains = {
                "chorus=0.5:0.9:50|60|40:0.4|0.32|0.3:0.25|0.4|0.3:2|2.3|1.3",
                "flanger=delay=4:depth=4:regen=25:width=75:speed=0.6",
                "aphaser=in_gain=0.6:out_gain=0.8:delay=3.0:decay=0.4:speed=0.5:type=t",
                "tremolo=f=5.5:d=0.65",
                "vibrato=f=5.0:d=0.35",
                "highpass=f=350,lowpass=f=3000,equalizer=f=1200:width_type=o:width=1.5:g=6",
                "highpass=f=500,lowpass=f=2400,acompressor=threshold=-24dB:ratio=4:makeup=4dB",
                "highpass=f=450,lowpass=f=3600,equalizer=f=1800:width_type=o:width=1.0:g=8,aecho=0.8:0.8:90:0.3",
                "highpass=f=140,lowpass=f=3400,vibrato=f=1.8:d=0.08,equalizer=f=600:width_type=o:width=1.5:g=3",
                "aecho=0.8:0.88:12|24|36:0.6|0.45|0.3,flanger=delay=2:depth=6:regen=50:speed=1.5"
        };
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        UiKit.dialog(this, uiPrefs).setTitle("Pilih Efek Perangkat & Kreatif").setView(sp).setNegativeButton("Batal", null)
                .setPositiveButton("Terapkan Efek", (d, w) -> runSimpleFilter("Memproses Efek Khusus...", chains[sp.getSelectedItemPosition()], "FX")).show();
    }

    private void showUtilityEffectsDialog() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        String[] names = {
                "1. Hapus Bagian Diam Otomatis Standar (Pangkas Jeda > 1,0 Detik di Bawah -35 dB)",
                "2. Hapus Bagian Diam Cepat & Padat (Pangkas Jeda > 0,5 Detik di Bawah -32 dB)",
                "3. Hapus Diam di Awal dan Akhir Rekaman Saja (Trim Silence Ujung)",
                "4. Putar Balik Audio dari Akhir ke Awal (Reverse Audio)",
                "5. Ulangi / Gandakan Audio 2 Kali Berturut-turut (Loop 2x)",
                "6. Ulangi / Gandakan Audio 3 Kali Berturut-turut (Loop 3x)",
                "7. Ulangi / Gandakan Audio 5 Kali Berturut-turut (Loop 5x)",
                "8. Ulangi / Gandakan Audio 10 Kali Berturut-turut (Loop 10x)"
        };
        String[] chains = {
                "silenceremove=stop_periods=-1:stop_duration=1.0:stop_threshold=-35dB",
                "silenceremove=stop_periods=-1:stop_duration=0.5:stop_threshold=-32dB",
                "silenceremove=start_periods=1:start_duration=0.2:start_threshold=-38dB,areverse,silenceremove=start_periods=1:start_duration=0.2:start_threshold=-38dB,areverse",
                "areverse",
                "aloop=loop=1:size=2000000000",
                "aloop=loop=2:size=2000000000",
                "aloop=loop=4:size=2000000000",
                "aloop=loop=9:size=2000000000"
        };
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        UiKit.dialog(this, uiPrefs).setTitle("Pilih Alat Otomatis").setView(sp).setNegativeButton("Batal", null)
                .setPositiveButton("Jalankan Sekarang", (d, w) -> runSimpleFilter("Memproses Audio...", chains[sp.getSelectedItemPosition()], "Auto")).show();
    }

    private void runSimpleFilter(String title, String filterChain, String suffix) {
        final String fmt = getActiveFormatExt();
        final int br = getActiveBitrateKbps();
        final int sr = getActiveSampleRate();
        final int ch = getActiveChannels();
        runFfmpegJob(title, () -> {
            File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
            File out = new File(outDir, cleanBaseName(singleMeta.displayName) + "_" + suffix + "_" + br + "kbps_" + System.currentTimeMillis() + "." + fmt);
            AudioDspEngine.applyAudioFilter(this, singleUri, workDir, out, filterChain, fmt, br, sr, ch);
            return out;
        });
    }

    // ==================== REKAM MIC, ANALISIS STATISTIK & AI TRANSKRIP ====================

    private void startMicRecordingDialog() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM_MIC);
            return;
        }
        releasePlayer();
        final boolean[] recording = new boolean[]{true};
        File outDir = OfficeUtils.resolveBackupSubfolder(this, "Editor Audio");
        final File recWav = new File(outDir, "Rekaman_" + System.currentTimeMillis() + ".wav");
        final long startTime = System.currentTimeMillis();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 8));
        TextView tvTimer = new TextView(this);
        tvTimer.setText("Sedang Merekam Suara Mikrofon... 00:00");
        tvTimer.setTextSize(17); tvTimer.setTypeface(null, Typeface.BOLD);
        tvTimer.setTextColor(UiKit.textPrimary(uiPrefs));
        box.addView(tvTimer);

        AlertDialog dlg = UiKit.dialog(this, uiPrefs)
                .setTitle("Rekam Suara Mikrofon Baru")
                .setView(box).setCancelable(false)
                .setNegativeButton("Batal Rekam", (d, w) -> { recording[0] = false; try { recWav.delete(); } catch (Throwable ignored) {} })
                .setPositiveButton("Selesai & Muat ke Pemotongan", (d, w) -> recording[0] = false)
                .show();

        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override public void run() {
                if (!recording[0] || !dlg.isShowing()) return;
                long sec = (System.currentTimeMillis() - startTime) / 1000L;
                tvTimer.setText(String.format(Locale.US, "Sedang Merekam Suara Mikrofon... %02d:%02d", sec / 60, sec % 60));
                h.postDelayed(this, 400L);
            }
        });

        new Thread(() -> {
            int sr = 44100;
            int minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord ar = null;
            FileOutputStream fos = null;
            long written = 0L;
            try {
                ar = new AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 2, 8192));
                fos = new FileOutputStream(recWav, false);
                fos.write(new byte[44]);
                ar.startRecording();
                byte[] buf = new byte[4096];
                while (recording[0]) {
                    int n = ar.read(buf, 0, buf.length);
                    if (n > 0) {
                        byte[] st = new byte[n * 2];
                        for (int i = 0; i + 1 < n; i += 2) {
                            st[i * 2] = buf[i]; st[i * 2 + 1] = buf[i + 1];
                            st[i * 2 + 2] = buf[i]; st[i * 2 + 3] = buf[i + 1];
                        }
                        fos.write(st);
                        written += st.length;
                    }
                }
                fos.flush(); fos.close(); fos = null;
                try (RandomAccessFile raf = new RandomAccessFile(recWav, "rw")) { AudioDspEngine.writeWavHeader(raf, sr, 2, written); }
                if (written > 4000L && recWav.exists()) {
                    runOnUiThread(() -> {
                        setSingleUriInstant(Uri.fromFile(recWav));
                        activeFeatureId = 3;
                        renderCurrentScreen();
                        Toast.makeText(this, "Rekaman mikrofon selesai dan siap diedit.", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this, "Gagal merekam: " + t.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (fos != null) { try { fos.close(); } catch (Throwable ignored) {} }
                if (ar != null) { try { ar.stop(); } catch (Throwable ignored) {} try { ar.release(); } catch (Throwable ignored) {} }
            }
        }).start();
    }

    private void showStatsOrTranscribeDialog() {
        if (singleUri == null) {
            Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_LONG).show();
            return;
        }
        String info = "Nama Berkas: " + singleMeta.displayName
                + "\nDurasi Persis: " + AudioDspEngine.formatMs(singleMeta.durationMs) + " (" + (singleMeta.durationMs / 1000) + " detik)"
                + "\nUkuran Berkas: " + fmtSize(singleMeta.sizeBytes)
                + "\nSample Rate Asli: " + singleMeta.sampleRate + " Hz"
                + "\nBitrate Asli: " + (singleMeta.bitrate > 0 ? (singleMeta.bitrate / 1000) + " kbps" : "Tidak diketahui")
                + "\nTarget Format Ekspor Aktif: " + getExportSummaryText();
        UiKit.dialog(this, uiPrefs)
                .setTitle("Statistik Audio & Transkrip AI")
                .setMessage(info)
                .setNegativeButton("Tutup", null)
                .setPositiveButton("Transkrip Audio Ini ke Teks (AI Gemini)", (d, w) -> runAiTranscribeSingle())
                .show();
    }

    private void runAiTranscribeSingle() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_LONG).show(); return; }
        String key = AiEngine.resolveApiKey(this, uiPrefs, "Google Gemini");
        if (key == null || key.trim().isEmpty()) { Toast.makeText(this, "Membutuhkan API Key Gemini di menu Otak AI.", Toast.LENGTH_LONG).show(); return; }
        runBusyRaw("AI Gemini Mentranskrip Audio ke Teks...", () -> {
            File tmpM4a = new File(workDir, "tr_" + System.currentTimeMillis() + ".m4a");
            AudioDspEngine.trimAudio(this, singleUri, workDir, tmpM4a, 0L, 0L, "m4a", 64);
            if (tmpM4a.length() > 12L * 1024L * 1024L) {
                try { tmpM4a.delete(); } catch (Throwable ignored) {}
                throw new IllegalStateException("Audio terkompresi melebihi 12 MB. Potong terlebih dahulu menjadi bagian yang lebih pendek.");
            }
            byte[] rawBytes;
            try (FileInputStream fis = new FileInputStream(tmpM4a)) {
                rawBytes = new byte[(int) tmpM4a.length()];
                int off = 0, n;
                while (off < rawBytes.length && (n = fis.read(rawBytes, off, rawBytes.length - off)) != -1) off += n;
            } finally {
                try { tmpM4a.delete(); } catch (Throwable ignored) {}
            }
            String b64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP);
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("text", "Transkrip audio ini secara akurat dengan tanda baca yang rapi. Langsung tuliskan isi transkripnya tanpa kalimat pengantar."));
            parts.put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", "audio/mp4").put("data", b64)));
            JSONObject req = new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)));
            String raw = AiEngine.httpPostJson(
                    new URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + key),
                    null, req.toString());
            String txt = new JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
            runOnUiThread(() -> AiPowerToolsUI.showResultDialog(this, "Hasil Transkrip AI: " + singleMeta.displayName, txt));
        });
    }

    // ==================== PEMUTAR UTAMA (OTOMATIS PUTAR POTONGAN & BUNYIKAN LIVE SFX) ====================

    private void ensurePlayerPreparedFor(Uri uri) {
        releasePlayer();
        if (uri == null) return;
        try {
            player = new MediaPlayer();
            player.setDataSource(this, uri);
            player.prepare();
            player.setOnCompletionListener(mp -> {
                isPlaying = false;
                stopAllCuePlayers();
                if (btnPlayPause != null) btnPlayPause.setText("Putar");
                mainHandler.removeCallbacks(ticker);
                updatePlayheadUi();
            });
        } catch (Throwable t) {
            Toast.makeText(this, "Gagal membuka pemutar audio: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void playOneUriQuick(Uri uri) {
        ensurePlayerPreparedFor(uri);
        if (player != null) {
            try { player.start(); isPlaying = true; } catch (Throwable ignored) {}
        }
    }

    private void toggleSinglePlayPause() {
        if (singleUri == null) { Toast.makeText(this, "Pilih berkas audio terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        if (player == null) ensurePlayerPreparedFor(singleUri);
        if (player == null) return;
        if (isPlaying) {
            try { player.pause(); } catch (Throwable ignored) {}
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
                    player.seekTo((int) startBound);
                    cur = startBound;
                }
                lastTickerPosMs = cur - 50L;
                for (DramaSoundPaletteHelper.CueItem cue : liveSfxCues) {
                    cue.lastTriggeredPlaybackMs = -5000L;
                }
                player.start();
                isPlaying = true;
                if (btnPlayPause != null) btnPlayPause.setText("Jeda");
                DramaSoundPaletteHelper.syncCuesWithPlayhead(this, liveSfxCues, lastTickerPosMs, cur, true);
                mainHandler.removeCallbacks(ticker);
                mainHandler.post(ticker);
            } catch (Throwable ignored) {}
        }
    }

    private void releasePlayer() {
        isPlaying = false;
        stopAllCuePlayers();
        mainHandler.removeCallbacks(ticker);
        if (btnPlayPause != null) btnPlayPause.setText("Putar");
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
    }

    private long getPlayheadMs() {
        if (player == null) return 0L;
        try { return Math.max(0L, player.getCurrentPosition()); } catch (Throwable ignored) { return 0L; }
    }

    private void seekAbsolute(long ms) {
        if (singleUri == null) return;
        if (player == null) ensurePlayerPreparedFor(singleUri);
        if (player == null) return;
        long target = Math.max(0L, Math.min(singleMeta.durationMs, ms));
        try { player.seekTo((int) target); } catch (Throwable ignored) {}
        lastTickerPosMs = target;
        updatePlayheadUi();
    }

    private void nudgePlayhead(long deltaMs) {
        if (singleUri == null) return;
        if (player == null) ensurePlayerPreparedFor(singleUri);
        long t = Math.max(0L, Math.min(singleMeta.durationMs, getPlayheadMs() + deltaMs));
        for (DramaSoundPaletteHelper.CueItem cue : liveSfxCues) {
            if (cue.atMs >= t) cue.lastTriggeredPlaybackMs = -5000L;
        }
        seekAbsolute(t);
        if (isPlaying) {
            DramaSoundPaletteHelper.syncCuesWithPlayhead(this, liveSfxCues, t - 50L, t, true);
        }
        OfficeUtils.announce(tvPlayPos, (deltaMs < 0 ? "Mundur ke " : "Maju ke ") + AudioDspEngine.formatMsShort(t));
    }

    private void updatePlayheadUi() {
        if (player == null || tvPlayPos == null) return;
        long pos = getPlayheadMs();

        // 1. Jika ada Titik Akhir (OUT) yang ditandai, otomatis berhenti tepat di Titik Akhir dan kembali ke Titik Awal
        if (isPlaying && hasMarkedOut && activeOutMs > activeInMs && pos >= activeOutMs) {
            try {
                player.pause();
                player.seekTo((int) activeInMs);
            } catch (Throwable ignored) {}
            stopAllCuePlayers();
            isPlaying = false;
            if (btnPlayPause != null) btnPlayPause.setText("Putar");
            mainHandler.removeCallbacks(ticker);
            pos = activeInMs;
        }

        // 2. Bunyikan Live SFX & Backsound Cues yang dilewati playhead saat sedang Play
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

        String posText = "Posisi Putar: " + AudioDspEngine.formatMs(pos) + " dari " + AudioDspEngine.formatMs(singleMeta.durationMs);
        tvPlayPos.setText(posText);
        if (seekBar != null && singleMeta.durationMs > 0) {
            seekBar.setProgress((int) Math.min(1000L, (pos * 1000L) / singleMeta.durationMs));
            seekBar.setContentDescription("Posisi audio " + AudioDspEngine.formatMsShort(pos) + " dari " + AudioDspEngine.formatMsShort(singleMeta.durationMs));
        }
    }

    // ==================== JOB RUNNERS & RESULT DIALOGS ====================

    private interface SingleFileJob { File run() throws Exception; }
    private interface MultiFileJob { List<File> run() throws Exception; }
    private interface RawVoidJob { void run() throws Exception; }

    @SuppressWarnings("deprecation")
    private void runFfmpegJob(String title, SingleFileJob job) {
        releasePlayer();
        ProgressDialog pd = ProgressDialog.show(this, title, "Sedang memproses dengan mesin FFmpeg Studio (" + getExportSummaryText() + ")...", true, false);
        worker.submit(() -> {
            try {
                File out = job.run();
                mainHandler.post(() -> {
                    if (pd.isShowing()) pd.dismiss();
                    showSingleResultDialog(out);
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (pd.isShowing()) pd.dismiss();
                    Toast.makeText(this, "Gagal memproses audio: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void runBusyMulti(String title, MultiFileJob job) {
        releasePlayer();
        ProgressDialog pd = ProgressDialog.show(this, title, "Sedang memproses bagian-bagian audio dengan FFmpeg...", true, false);
        worker.submit(() -> {
            try {
                List<File> list = job.run();
                mainHandler.post(() -> {
                    if (pd.isShowing()) pd.dismiss();
                    showMultiResultDialog(list);
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (pd.isShowing()) pd.dismiss();
                    Toast.makeText(this, "Gagal memproses: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void runBusyRaw(String title, RawVoidJob job) {
        ProgressDialog pd = ProgressDialog.show(this, title, "Mohon tunggu sebentar...", true, false);
        worker.submit(() -> {
            try {
                job.run();
                mainHandler.post(() -> { if (pd.isShowing()) pd.dismiss(); });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (pd.isShowing()) pd.dismiss();
                    Toast.makeText(this, "Gagal: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showSingleResultDialog(File out) {
        if (tvSingleInfo != null) {
            OfficeUtils.announce(tvSingleInfo, "Proses selesai. Berkas disimpan dengan nama " + out.getName());
        }
        UiKit.dialog(this, uiPrefs)
                .setTitle("Audio Selesai Diproses")
                .setMessage("Berkas berhasil disimpan ke:\n" + out.getAbsolutePath()
                        + "\n\nUkuran Berkas: " + fmtSize(out.length())
                        + "\nFormat & Bitrate: " + getExportSummaryText())
                .setNeutralButton("Bagikan ke WhatsApp / Aplikasi Lain", (d, w) -> shareFile(out, "audio/*"))
                .setNegativeButton("Putar Hasil Sekarang", (d, w) -> {
                    setSingleUriInstant(Uri.fromFile(out));
                    if (activeFeatureId == FEATURE_HUB || activeFeatureId == 9 || activeFeatureId == 10) activeFeatureId = 3;
                    renderCurrentScreen();
                    toggleSinglePlayPause();
                })
                .setPositiveButton("Muat Hasil untuk Diedit Lagi", (d, w) -> {
                    setSingleUriInstant(Uri.fromFile(out));
                    renderCurrentScreen();
                }).show();
    }

    private void showMultiResultDialog(final List<File> files) {
        if (files == null || files.isEmpty()) return;
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12), UiKit.dp(this, 14), UiKit.dp(this, 12));

        TextView hint = new TextView(this);
        hint.setText("Ketuk Bagian 1 untuk bagikan -> setelah kembali, daftar ini tetap terbuka agar bisa lanjut kirim Bagian 2, 3, dst.");
        hint.setTextColor(UiKit.textMuted(uiPrefs));
        hint.setTextSize(12.5f);
        hint.setPadding(0, 0, 0, UiKit.dp(this, 10));
        root.addView(hint);

        if (files.size() > 1) {
            Button btnAll = UiKit.makeMenuButton(this,
                    "Bagikan Semua Sekaligus (" + files.size() + " Bagian)",
                    "Kirim seluruh bagian audio sekaligus.",
                    UiKit.ACCENT_AI, uiPrefs, v -> shareMultipleAudioFiles(files));
            root.addView(btnAll);
        }

        for (int i = 0; i < files.size(); i++) {
            final int idx = i;
            final File f = files.get(i);
            final String baseTitle = "Bagian " + (idx + 1) + " dari " + files.size() + " (" + fmtSize(f.length()) + ")";
            final Button btnPart = UiKit.makeMenuButton(this,
                    baseTitle,
                    f.getName() + " • Ketuk untuk bagikan",
                    UiKit.ACCENT_OFFICE, uiPrefs, null);
            btnPart.setOnClickListener(v -> {
                shareFile(f, "audio/*");
                btnPart.setBackground(UiKit.makeAccentCardDrawable(this, uiPrefs, UiKit.ACCENT_KB_ALT));
                btnPart.setText("✓ " + baseTitle + " — Sudah Dibuka (Ketuk lagi jika ulang)\n" + f.getName());
            });
            root.addView(btnPart);
        }
        sv.addView(root);

        UiKit.dialog(this, uiPrefs)
                .setTitle("Selesai (" + files.size() + " Bagian Audio)")
                .setView(sv)
                .setCancelable(false)
                .setPositiveButton("Selesai / Tutup", null)
                .show();
    }

    private void shareMultipleAudioFiles(List<File> files) {
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            for (File f : files) {
                uris.add(AudioDspEngine.prepareShareUri(this, f));
            }
            Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.setType("audio/*");
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Bagikan " + files.size() + " Bagian Audio"));
        } catch (Throwable t) {
            Toast.makeText(this, "Gagal membagikan semua bagian: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareFile(File f, String mime) {
        try {
            Uri u = AudioDspEngine.prepareShareUri(this, f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Bagikan " + f.getName()));
        } catch (Throwable t) {
            Toast.makeText(this, "Gagal membagikan berkas: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void launchSystemPicker(String mime, int req) {
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
        if (requestCode == REQ_PICK_SINGLE_FM || requestCode == REQ_PICK_SINGLE_SYS) {
            setSingleUriInstant(uri);
            ensurePlayerPreparedFor(uri);
            renderCurrentScreen();
            Toast.makeText(this, "Audio dimuat: " + singleMeta.displayName, Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_PICK_ADD_MERGE_FM || requestCode == REQ_PICK_ADD_MERGE_SYS) {
            AudioDspEngine.AudioMeta m = AudioDspEngine.inspectMedia(this, uri);
            ClipSlot s = new ClipSlot();
            s.uri = uri; s.title = m.displayName; s.durationMs = m.durationMs;
            mergeClips.add(s);
            rebuildMergeListUi();
        } else if (requestCode == REQ_PICK_ADD_MIX_FM || requestCode == REQ_PICK_ADD_MIX_SYS) {
            AudioDspEngine.AudioMeta m = AudioDspEngine.inspectMedia(this, uri);
            AudioDspEngine.MixTrackSpec tr = new AudioDspEngine.MixTrackSpec();
            tr.uri = uri; tr.title = m.displayName;
            tr.volume = mixTracks.isEmpty() ? 1.0f : 0.30f;
            tr.loop = !mixTracks.isEmpty();
            mixTracks.add(tr);
            rebuildMixerListUi();
        } else if (requestCode == REQ_PICK_COVER_IMG_FM || requestCode == REQ_PICK_COVER_IMG_SYS) {
            coverImageUri = uri;
            coverImageName = AudioDspEngine.resolveDisplayName(this, uri);
            renderCurrentScreen();
            Toast.makeText(this, "Foto Cover dipilih: " + coverImageName, Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_PICK_LIVE_SFX_FM || requestCode == REQ_PICK_LIVE_SFX_SYS) {
            final String name = AudioDspEngine.resolveDisplayName(this, uri);
            UiKit.dialog(this, uiPrefs)
                    .setTitle("Pasang \"" + name + "\" Sebagai Apa?")
                    .setItems(new String[]{
                            "1. Efek Suara (Tempel di Detik Pemutar Saat Ini)",
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
            startMicRecordingDialog();
        }
    }

    private static String fmtSize(long b) {
        if (b < 1024L) return b + " B";
        if (b < 1048576L) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        return String.format(Locale.ROOT, "%.2f MB", b / 1048576.0);
    }

    private static double safeDouble(String raw, double def) {
        try { return Double.parseDouble(raw.trim().replace(',', '.')); } catch (Throwable t) { return def; }
    }

    private static String cleanBaseName(String n) {
        if (n == null) return "Audio";
        int d = n.lastIndexOf('.');
        String b = d > 0 ? n.substring(0, d) : n;
        String c = b.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return c.isEmpty() ? "Audio" : c;
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        StudioGudangAudioManager.stopPreview();
        OfficeUtils.clearDirectoryContents(workDir);
        worker.shutdownNow();
        super.onDestroy();
    }
}
