package com.annas;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Studio Pengunduh Lagu, Video, Playlist, Channel & Pencarian YouTube Pro.
 * Tampilan bersih, simpel, & rapi (tanpa daftar panjang berserak):
 * - Halaman 1: Tempel/Cari -> muncul pilihan Tipe & Bitrate/Resolusi -> tombol Unduh Semua atau Pilih Per Item (dialog menu).
 * - Halaman 2: Slider Progres Utama ("Mengunduh X item • Y%") + tombol opsional "Tampilkan Semua Lagu" & Batalkan Semua.
 *   Item yang selesai atau dibatalkan langsung hilang otomatis seketika (tanpa tombol Bersihkan).
 * - Halaman 3: Daftar hasil unduhan siap Putar, Edit, Bagikan, atau Hapus.
 */
public class MediaDownloaderActivity extends Activity implements MediaDownloadService.QueueListener {

    private static final int PAGE_INPUT = 0;
    private static final int PAGE_ACTIVE = 1;
    private static final int PAGE_DONE = 2;

    private static final String[] AUDIO_FORMAT_CODES = {
            MediaDownloaderEngine.MODE_AUDIO_MP3,
            MediaDownloaderEngine.MODE_AUDIO_M4A,
            MediaDownloaderEngine.MODE_AUDIO_OPUS,
            MediaDownloaderEngine.MODE_AUDIO_WAV
    };
    private static final String[] AUDIO_FORMAT_LABELS = {
            "MP3 Universal (Kompatibel Semua Pemutar)",
            "M4A / AAC Asli YouTube (Cepat & Hemat)",
            "OPUS (Paling Ringan & Hemat Kuota)",
            "WAV Studio PCM (Tanpa Kompresi)"
    };

    private static final int[] BITRATE_VALS = {64, 96, 128, 160, 192, 256, 320};
    private static final String[] BITRATE_LABELS = {
            "64 kbps — Sangat Hemat (±2,3 MB / 5 menit)",
            "96 kbps — Hemat Kuota (±3,5 MB / 5 menit)",
            "128 kbps — Standar Musik Hemat & Jernih (±4,7 MB)",
            "160 kbps — Jernih Menengah (±5,8 MB)",
            "192 kbps — Kualitas Tinggi Studio (±7,0 MB)",
            "256 kbps — Kualitas Premium (±9,4 MB)",
            "320 kbps — Kualitas Maksimal MP3 (±11,7 MB)"
    };

    private static final String[] VIDEO_RES_CODES = {
            MediaDownloaderEngine.MODE_VIDEO_360,
            MediaDownloaderEngine.MODE_VIDEO_480,
            MediaDownloaderEngine.MODE_VIDEO_720,
            MediaDownloaderEngine.MODE_VIDEO_1080,
            MediaDownloaderEngine.MODE_VIDEO_BEST
    };
    private static final String[] VIDEO_RES_LABELS = {
            "360p MP4 (Sangat Hemat & Cepat)",
            "480p MP4 (Sedang & Ringan)",
            "720p HD MP4 (Jernih & Cepat — Direkomendasikan)",
            "1080p Full HD MP4 (Sangat Tajam)",
            "Kualitas Video Terbaik (Full HD / 4K)"
    };

    private static final int[] SCAN_LIMIT_VALS = {100, 200, 300, 500};
    private static final String[] SCAN_LIMIT_LABELS = {
            "Maks 100 lagu", "Maks 200 lagu", "Maks 300 lagu", "Maks 500 lagu"
    };

    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();

    private int activePage = PAGE_INPUT;
    private FrameLayout pageHost;
    private View pageInputView, pageActiveView, pageDoneView;
    private Button tabBtn0, tabBtn1, tabBtn2;

    // Halaman 1: Step-by-Step Rapi
    private EditText inputLinkOrSearch;
    private Spinner spScanLimit, spAudioFormat, spAudioBitrate, spVideoRes;
    private Button btnPasteAndScan, btnScan, btnResetScan, btnUpdateEngine;
    private LinearLayout stepConfigCard, audioConfigBox, videoConfigBox, singleActionCard, collectionSummaryCard, limitRow;
    private Button btnTypeAudio, btnTypeVideo;
    private TextView scanSummaryText, singleTitleText, singleMetaText, bitrateTitleLabel, collectionTitleText, collectionDescText;
    private Button btnSinglePlay, btnSingleDownload, btnCollectionDownloadAll, btnCollectionPickItems;

    private boolean selectedTypeIsVideo = false;
    private final List<MediaDownloaderEngine.MediaItem> scannedItems = new ArrayList<>();
    private String lastCollectionLabel = "";
    private String activeScanProcessId = "";
    private volatile boolean scanning = false;

    // Mini Player Pratinjau
    private LinearLayout previewBar;
    private TextView previewTitleText, previewTimeText;
    private Button btnPreviewPlayPause, btnPreviewStop, btnPreviewDownload;
    private SeekBar previewSeekBar;
    private MediaPlayer audioPreviewPlayer;
    private MediaDownloaderEngine.MediaItem previewItem;
    private boolean previewPrepared = false;
    private boolean userSeeking = false;

    // Halaman 2: Slider Progres Utama + Toggle Tampilkan Semua Lagu (Otomatis Bersih)
    private TextView activeHeadlineText, activeSubText, activePercentText;
    private ProgressBar activeOverallProgress;
    private Button btnToggleQueueList, btnActiveCancelAll, btnActiveSelectAll, btnActiveCancelSelected;
    private LinearLayout queueDetailBox;
    private ListView activeListView;
    private boolean showQueueDetails = false;
    private final List<MediaDownloadService.DownloadTask> activeList = new ArrayList<>();
    private final Set<String> selectedQueueIds = new HashSet<>();
    private ActiveQueueAdapter activeAdapter;

    // Halaman 3: Sudah Terunduh
    private TextView doneSummaryText;
    private ListView doneListView;
    private final List<MediaDownloadService.CompletedEntry> doneList = new ArrayList<>();
    private CompletedAdapter doneAdapter;

    private GestureDetector swipeDetector;

    private final Runnable seekTicker = new Runnable() {
        @Override public void run() {
            updatePreviewSeekProgress();
            mainHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = OfficeUtils.mainPrefs(this);

        if (!AuthManager.isPremiumActive(this)) {
            AuthManager.showPremiumRequiredDialog(this, "Studio Pengunduh Lagu & Video Pro", null);
            Toast.makeText(this, "Fitur Pengunduh Lagu & Video Pro khusus Anggota Premium.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9701); } catch (Throwable ignored) {}
        }

        MediaDownloaderEngine.warmUpAsync(this);

        buildUi();
        int openTab = getIntent() != null ? getIntent().getIntExtra("open_tab", PAGE_INPUT) : PAGE_INPUT;
        switchPage(Math.max(PAGE_INPUT, Math.min(PAGE_DONE, openTab)), false);
        refreshQueueAndDoneLists();
    }

    @Override
    protected void onStart() {
        super.onStart();
        MediaDownloadService.addListener(this);
        refreshQueueAndDoneLists();
    }

    @Override
    protected void onStop() {
        super.onStop();
        MediaDownloadService.removeListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(seekTicker);
        stopAudioPreview();
        if (!activeScanProcessId.isEmpty()) {
            MediaDownloaderEngine.destroyProcessSafe(activeScanProcessId);
        }
        scanExecutor.shutdownNow();
    }

    @Override
    public void onQueueUpdated() {
        refreshQueueAndDoneLists();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeDetector != null) swipeDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    // =========================================================================
    // UI UTAMA & NAVIGASI 3 HALAMAN
    // =========================================================================

    private void buildUi() {
        UiKit.applySystemBars(this, prefs);

        swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_DIST = 140;
            private static final int SWIPE_VEL = 180;

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Math.abs(dy) * 1.6f && Math.abs(dx) > SWIPE_DIST && Math.abs(velocityX) > SWIPE_VEL) {
                    if (dx < 0 && activePage < PAGE_DONE) {
                        switchPage(activePage + 1, true);
                        return true;
                    } else if (dx > 0 && activePage > PAGE_INPUT) {
                        switchPage(activePage - 1, true);
                        return true;
                    }
                }
                return false;
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.bg(prefs));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(UiKit.surface(prefs));
        header.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10), UiKit.dp(this, 12), UiKit.dp(this, 10));

        Button btnBack = UiKit.makePillButton(this, "Kembali", UiKit.surfaceAlt(prefs), UiKit.textPrimary(prefs), v -> finish());
        btnBack.setContentDescription("Kembali ke Tab Kantor");
        header.addView(btnBack);

        TextView title = new TextView(this);
        title.setText("Pengunduh Lagu & Video");
        title.setTextColor(UiKit.textPrimary(prefs));
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(UiKit.dp(this, 10), 0, UiKit.dp(this, 8), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnUpdateEngine = UiKit.makePillButton(this, "Update yt-dlp", UiKit.ACCENT_AI_ALT, Color.WHITE, v -> runUpdateEngine());
        btnUpdateEngine.setContentDescription("Perbarui engine yt-dlp terbaru");
        header.addView(btnUpdateEngine);
        root.addView(header);

        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(UiKit.surfaceAlt(prefs));
        tabBar.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 6), UiKit.dp(this, 6), UiKit.dp(this, 6));

        tabBtn0 = makeTabButton("1. Link & Cari", PAGE_INPUT);
        tabBtn1 = makeTabButton("2. Diunduh (0)", PAGE_ACTIVE);
        tabBtn2 = makeTabButton("3. Selesai (0)", PAGE_DONE);
        tabBar.addView(tabBtn0, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabBar.addView(tabBtn1, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabBar.addView(tabBtn2, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabBar);

        TextView hintNav = new TextView(this);
        hintNav.setText("Geser kiri/kanan atau ketuk tab untuk pindah halaman");
        hintNav.setTextSize(11);
        hintNav.setTextColor(UiKit.textMuted(prefs));
        hintNav.setGravity(Gravity.CENTER);
        hintNav.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 4), UiKit.dp(this, 8), UiKit.dp(this, 4));
        root.addView(hintNav);

        pageHost = new FrameLayout(this);
        pageInputView = buildInputAndSearchPage();
        pageActiveView = buildActiveQueuePage();
        pageDoneView = buildCompletedPage();

        pageHost.addView(pageInputView);
        pageHost.addView(pageActiveView);
        pageHost.addView(pageDoneView);
        root.addView(pageHost, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        buildPreviewMiniPlayer();
        root.addView(previewBar);

        setContentView(root);
    }

    private Button makeTabButton(String label, final int pageIdx) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTypeface(null, Typeface.BOLD);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 8), UiKit.dp(this, 6), UiKit.dp(this, 8));
        b.setOnClickListener(v -> switchPage(pageIdx, true));
        return b;
    }

    private void switchPage(int pageIdx, boolean announce) {
        activePage = pageIdx;
        pageInputView.setVisibility(pageIdx == PAGE_INPUT ? View.VISIBLE : View.GONE);
        pageActiveView.setVisibility(pageIdx == PAGE_ACTIVE ? View.VISIBLE : View.GONE);
        pageDoneView.setVisibility(pageIdx == PAGE_DONE ? View.VISIBLE : View.GONE);

        styleTabButton(tabBtn0, pageIdx == PAGE_INPUT);
        styleTabButton(tabBtn1, pageIdx == PAGE_ACTIVE);
        styleTabButton(tabBtn2, pageIdx == PAGE_DONE);

        if (announce) {
            String name = pageIdx == PAGE_INPUT ? "Halaman 1: Tempel Link dan Pencarian YouTube"
                    : (pageIdx == PAGE_ACTIVE ? "Halaman 2: Progres Unduhan Aktif" : "Halaman 3: File Sudah Terunduh");
            OfficeUtils.announce(pageHost, name);
        }
    }

    private void styleTabButton(Button b, boolean selected) {
        b.setBackground(UiKit.roundedBox(
                selected ? UiKit.ACCENT_KB : UiKit.surface(prefs),
                selected ? UiKit.ACCENT_KB : UiKit.border(prefs),
                UiKit.dp(this, 10), UiKit.dp(this, 1)));
        b.setTextColor(selected ? Color.WHITE : UiKit.textPrimary(prefs));
    }

    // =========================================================================
    // HALAMAN 1: STEP-BY-STEP RAPI TANPA DAFTAR SCROLL BERSERAK
    // =========================================================================

    private View buildInputAndSearchPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 6), UiKit.dp(this, 12), UiKit.dp(this, 16));

        // LANGKAH 1
        LinearLayout step1Card = UiKit.makeCard(this, prefs);
        step1Card.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12));

        TextView step1Label = new TextView(this);
        step1Label.setText("Langkah 1: Tempel Tautan atau Cari Lagu di YouTube");
        step1Label.setTextColor(UiKit.textPrimary(prefs));
        step1Label.setTextSize(13);
        step1Label.setTypeface(null, Typeface.BOLD);
        step1Label.setPadding(0, 0, 0, UiKit.dp(this, 6));
        step1Card.addView(step1Label);

        inputLinkOrSearch = new EditText(this);
        inputLinkOrSearch.setHint("Tempel link YouTube/Playlist/Channel/SoundCloud atau ketik judul lagu...");
        inputLinkOrSearch.setHintTextColor(UiKit.textMuted(prefs));
        inputLinkOrSearch.setTextColor(UiKit.textPrimary(prefs));
        inputLinkOrSearch.setTextSize(14);
        inputLinkOrSearch.setMaxLines(2);
        inputLinkOrSearch.setContentDescription("Kotak masukan tautan lagu, playlist, channel, atau pencarian YouTube");
        inputLinkOrSearch.setBackground(UiKit.roundedBox(UiKit.surfaceAlt(prefs), UiKit.border(prefs), UiKit.dp(this, 10), UiKit.dp(this, 1)));
        inputLinkOrSearch.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10), UiKit.dp(this, 12), UiKit.dp(this, 10));
        step1Card.addView(inputLinkOrSearch);

        LinearLayout rowBtns = new LinearLayout(this);
        rowBtns.setOrientation(LinearLayout.HORIZONTAL);
        rowBtns.setPadding(0, UiKit.dp(this, 8), 0, 0);

        btnPasteAndScan = UiKit.makePillButton(this, "Tempel & Proses", UiKit.ACCENT_KB, Color.WHITE, v -> pasteAndScanNow());
        btnScan = UiKit.makePillButton(this, "Proses / Cari", UiKit.ACCENT_AI, Color.WHITE, v -> startScanOrSearch());
        btnResetScan = UiKit.makePillButton(this, "Reset", UiKit.surfaceAlt(prefs), UiKit.textPrimary(prefs), v -> resetScanStep());

        LinearLayout.LayoutParams bGap = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bGap.setMargins(0, 0, UiKit.dp(this, 6), 0);
        rowBtns.addView(btnPasteAndScan, bGap);
        rowBtns.addView(btnScan, bGap);
        rowBtns.addView(btnResetScan, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        step1Card.addView(rowBtns);

        limitRow = new LinearLayout(this);
        limitRow.setOrientation(LinearLayout.HORIZONTAL);
        limitRow.setGravity(Gravity.CENTER_VERTICAL);
        limitRow.setPadding(0, UiKit.dp(this, 8), 0, 0);
        TextView lblLim = new TextView(this);
        lblLim.setText("Batas Playlist/Channel: ");
        lblLim.setTextColor(UiKit.textMuted(prefs));
        lblLim.setTextSize(11);
        limitRow.addView(lblLim);
        spScanLimit = makeStyledSpinner(SCAN_LIMIT_LABELS);
        spScanLimit.setSelection(1);
        limitRow.addView(spScanLimit, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        step1Card.addView(limitRow);

        box.addView(step1Card);

        scanSummaryText = new TextView(this);
        scanSummaryText.setText("Tempel tautan atau ketik judul lagu, lalu tekan Tempel & Proses.");
        scanSummaryText.setTextColor(UiKit.textMuted(prefs));
        scanSummaryText.setTextSize(12);
        scanSummaryText.setPadding(UiKit.dp(this, 4), 0, UiKit.dp(this, 4), UiKit.dp(this, 8));
        box.addView(scanSummaryText);

        // LANGKAH 2: PILIH TIPE & BITRATE/RESOLUSI
        stepConfigCard = UiKit.makeCard(this, prefs);
        stepConfigCard.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12));
        stepConfigCard.setVisibility(View.GONE);

        TextView step2Label = new TextView(this);
        step2Label.setText("Langkah 2: Pilih Tipe & Kualitas Unduhan");
        step2Label.setTextColor(UiKit.textPrimary(prefs));
        step2Label.setTextSize(13);
        step2Label.setTypeface(null, Typeface.BOLD);
        step2Label.setPadding(0, 0, 0, UiKit.dp(this, 8));
        stepConfigCard.addView(step2Label);

        LinearLayout typeRow = new LinearLayout(this);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        btnTypeAudio = UiKit.makePillButton(this, "AUDIO (Musik / MP3)", UiKit.ACCENT_OFFICE, Color.WHITE, v -> selectMediaType(false));
        btnTypeVideo = UiKit.makePillButton(this, "VIDEO (MP4)", UiKit.surfaceAlt(prefs), UiKit.textPrimary(prefs), v -> selectMediaType(true));
        typeRow.addView(btnTypeAudio, bGap);
        typeRow.addView(btnTypeVideo, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        stepConfigCard.addView(typeRow);

        audioConfigBox = new LinearLayout(this);
        audioConfigBox.setOrientation(LinearLayout.VERTICAL);
        audioConfigBox.setPadding(0, UiKit.dp(this, 10), 0, 0);

        TextView lblAudioFmt = new TextView(this);
        lblAudioFmt.setText("Format Audio:");
        lblAudioFmt.setTextColor(UiKit.textMuted(prefs));
        lblAudioFmt.setTextSize(11);
        audioConfigBox.addView(lblAudioFmt);

        spAudioFormat = makeStyledSpinner(AUDIO_FORMAT_LABELS);
        spAudioFormat.setSelection(0);
        spAudioFormat.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                boolean isWav = (pos == 3);
                if (bitrateTitleLabel != null) bitrateTitleLabel.setVisibility(isWav ? View.GONE : View.VISIBLE);
                if (spAudioBitrate != null) spAudioBitrate.setVisibility(isWav ? View.GONE : View.VISIBLE);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        audioConfigBox.addView(spAudioFormat);

        bitrateTitleLabel = new TextView(this);
        bitrateTitleLabel.setText("Kualitas Bitrate (64 kbps s/d 320 kbps):");
        bitrateTitleLabel.setTextColor(UiKit.textMuted(prefs));
        bitrateTitleLabel.setTextSize(11);
        bitrateTitleLabel.setPadding(0, UiKit.dp(this, 8), 0, 0);
        audioConfigBox.addView(bitrateTitleLabel);

        spAudioBitrate = makeStyledSpinner(BITRATE_LABELS);
        spAudioBitrate.setSelection(2); // default 128 kbps
        audioConfigBox.addView(spAudioBitrate);
        stepConfigCard.addView(audioConfigBox);

        videoConfigBox = new LinearLayout(this);
        videoConfigBox.setOrientation(LinearLayout.VERTICAL);
        videoConfigBox.setPadding(0, UiKit.dp(this, 10), 0, 0);
        videoConfigBox.setVisibility(View.GONE);

        TextView lblVidRes = new TextView(this);
        lblVidRes.setText("Resolusi Video:");
        lblVidRes.setTextColor(UiKit.textMuted(prefs));
        lblVidRes.setTextSize(11);
        videoConfigBox.addView(lblVidRes);

        spVideoRes = makeStyledSpinner(VIDEO_RES_LABELS);
        spVideoRes.setSelection(2);
        videoConfigBox.addView(spVideoRes);
        stepConfigCard.addView(videoConfigBox);
        box.addView(stepConfigCard);

        // LANGKAH 3A: SINGLE LAGU/VIDEO
        singleActionCard = UiKit.makeCard(this, prefs);
        singleActionCard.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12));
        singleActionCard.setVisibility(View.GONE);

        singleTitleText = new TextView(this);
        singleTitleText.setTextColor(UiKit.textPrimary(prefs));
        singleTitleText.setTextSize(15);
        singleTitleText.setTypeface(null, Typeface.BOLD);
        singleActionCard.addView(singleTitleText);

        singleMetaText = new TextView(this);
        singleMetaText.setTextColor(UiKit.textMuted(prefs));
        singleMetaText.setTextSize(12);
        singleMetaText.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 10));
        singleActionCard.addView(singleMetaText);

        LinearLayout singleBtnRow = new LinearLayout(this);
        singleBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnSinglePlay = UiKit.makePillButton(this, "Putar Pratinjau", UiKit.ACCENT_AI_ALT, Color.WHITE, v -> {
            if (!scannedItems.isEmpty()) playPreviewForItem(scannedItems.get(0));
        });
        btnSingleDownload = UiKit.makePillButton(this, "Unduh Sekarang", UiKit.ACCENT_OFFICE, Color.WHITE, v -> {
            if (!scannedItems.isEmpty()) enqueueList(Collections.singletonList(scannedItems.get(0)));
        });
        singleBtnRow.addView(btnSinglePlay, bGap);
        singleBtnRow.addView(btnSingleDownload, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        singleActionCard.addView(singleBtnRow);
        box.addView(singleActionCard);

        // LANGKAH 3B: RINGKASAN PLAYLIST/CHANNEL/SEARCH (TANPA LIST PANJANG DI HALAMAN UTAMA!)
        collectionSummaryCard = UiKit.makeCard(this, prefs);
        collectionSummaryCard.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 14), UiKit.dp(this, 14), UiKit.dp(this, 14));
        collectionSummaryCard.setVisibility(View.GONE);

        collectionTitleText = new TextView(this);
        collectionTitleText.setTextColor(UiKit.textPrimary(prefs));
        collectionTitleText.setTextSize(15);
        collectionTitleText.setTypeface(null, Typeface.BOLD);
        collectionSummaryCard.addView(collectionTitleText);

        collectionDescText = new TextView(this);
        collectionDescText.setTextColor(UiKit.textMuted(prefs));
        collectionDescText.setTextSize(12);
        collectionDescText.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 12));
        collectionSummaryCard.addView(collectionDescText);

        btnCollectionDownloadAll = UiKit.makeMenuButton(this,
                "Unduh Semua Sekaligus",
                "Masukkan seluruh item ke antrean unduhan latar belakang.",
                UiKit.ACCENT_OFFICE, prefs, v -> downloadAllScanned());
        collectionSummaryCard.addView(btnCollectionDownloadAll);

        btnCollectionPickItems = UiKit.makeMenuButton(this,
                "Pilih Per Item (Buka Daftar Menu)",
                "Buka daftar judul lagu untuk Putar Pratinjau atau centang lagu tertentu.",
                UiKit.ACCENT_AI, prefs, v -> showPickItemsMenuDialog());
        collectionSummaryCard.addView(btnCollectionPickItems);
        box.addView(collectionSummaryCard);

        sv.addView(box);
        selectMediaType(false);
        return sv;
    }

    /** Membuka dialog daftar menu rapi untuk memilih & memutar per item (tanpa menuh-menuhin halaman utama). */
    private void showPickItemsMenuDialog() {
        if (scannedItems.isEmpty()) return;

        LinearLayout dlgRoot = new LinearLayout(this);
        dlgRoot.setOrientation(LinearLayout.VERTICAL);
        dlgRoot.setBackgroundColor(UiKit.bg(prefs));
        dlgRoot.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10), UiKit.dp(this, 10), UiKit.dp(this, 10));

        final TextView tvPickedCount = new TextView(this);
        tvPickedCount.setTextColor(UiKit.textPrimary(prefs));
        tvPickedCount.setTextSize(13);
        tvPickedCount.setTypeface(null, Typeface.BOLD);
        tvPickedCount.setPadding(0, 0, 0, UiKit.dp(this, 8));
        dlgRoot.addView(tvPickedCount);

        LinearLayout topBtns = new LinearLayout(this);
        topBtns.setOrientation(LinearLayout.HORIZONTAL);
        topBtns.setPadding(0, 0, 0, UiKit.dp(this, 8));

        Button btnPickAll = UiKit.makePillButton(this, "Pilih Semua", UiKit.surfaceAlt(prefs), UiKit.textPrimary(prefs), null);
        Button btnPickNone = UiKit.makePillButton(this, "Batal Pilih", UiKit.surfaceAlt(prefs), UiKit.textPrimary(prefs), null);
        LinearLayout.LayoutParams pGap = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pGap.setMargins(0, 0, UiKit.dp(this, 6), 0);
        topBtns.addView(btnPickAll, pGap);
        topBtns.addView(btnPickNone, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        dlgRoot.addView(topBtns);

        ListView lv = new ListView(this);
        lv.setDividerHeight(UiKit.dp(this, 6));
        lv.setDivider(null);
        dlgRoot.addView(lv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 360)));

        // Tombol Pilih & Unduh di bagian bawah sesuai permintaan Anas
        Button btnConfirmBottom = UiKit.makePillButton(this, "Unduh Item Terpilih (0)", UiKit.ACCENT_OFFICE, Color.WHITE, null);
        LinearLayout.LayoutParams botLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        botLp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        dlgRoot.addView(btnConfirmBottom, botLp);

        final Runnable updateCounter = () -> {
            int c = 0;
            for (MediaDownloaderEngine.MediaItem it : scannedItems) if (it.selected) c++;
            tvPickedCount.setText(lastCollectionLabel + " • " + c + " dari " + scannedItems.size() + " dipilih");
            btnConfirmBottom.setText("Unduh Item Terpilih (" + c + ")");
        };

        final DialogScanAdapter adapter = new DialogScanAdapter(updateCounter);
        lv.setAdapter(adapter);
        updateCounter.run();

        btnPickAll.setOnClickListener(v -> {
            for (MediaDownloaderEngine.MediaItem it : scannedItems) it.selected = true;
            adapter.notifyDataSetChanged();
            updateCounter.run();
        });
        btnPickNone.setOnClickListener(v -> {
            for (MediaDownloaderEngine.MediaItem it : scannedItems) it.selected = false;
            adapter.notifyDataSetChanged();
            updateCounter.run();
        });

        final AlertDialog dlg = UiKit.dialog(this, prefs)
                .setTitle("Pilih Lagu / Video")
                .setView(dlgRoot)
                .setNegativeButton("Tutup", null)
                .create();

        btnConfirmBottom.setOnClickListener(v -> {
            List<MediaDownloaderEngine.MediaItem> picks = new ArrayList<>();
            for (MediaDownloaderEngine.MediaItem it : scannedItems) if (it.selected) picks.add(it);
            if (picks.isEmpty()) {
                Toast.makeText(this, "Centang minimal 1 lagu dulu.", Toast.LENGTH_SHORT).show();
                return;
            }
            dlg.dismiss();
            enqueueList(picks);
        });

        dlg.show();
    }

    private void selectMediaType(boolean isVideo) {
        selectedTypeIsVideo = isVideo;
        btnTypeAudio.setBackground(UiKit.roundedBox(
                !isVideo ? UiKit.ACCENT_OFFICE : UiKit.surfaceAlt(prefs),
                Color.TRANSPARENT, UiKit.dp(this, 10), 0));
        btnTypeAudio.setTextColor(!isVideo ? Color.WHITE : UiKit.textPrimary(prefs));

        btnTypeVideo.setBackground(UiKit.roundedBox(
                isVideo ? UiKit.ACCENT_AI : UiKit.surfaceAlt(prefs),
                Color.TRANSPARENT, UiKit.dp(this, 10), 0));
        btnTypeVideo.setTextColor(isVideo ? Color.WHITE : UiKit.textPrimary(prefs));

        audioConfigBox.setVisibility(isVideo ? View.GONE : View.VISIBLE);
        videoConfigBox.setVisibility(isVideo ? View.VISIBLE : View.GONE);
    }

    private Spinner makeStyledSpinner(final String[] items) {
        Spinner sp = new Spinner(this);
        sp.setBackground(UiKit.roundedBox(UiKit.surfaceAlt(prefs), UiKit.border(prefs), UiKit.dp(this, 8), UiKit.dp(this, 1)));
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int pos, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, convertView, parent);
                tv.setTextColor(UiKit.textPrimary(prefs));
                tv.setTextSize(12);
                tv.setPadding(UiKit.dp(MediaDownloaderActivity.this, 10), UiKit.dp(MediaDownloaderActivity.this, 8),
                        UiKit.dp(MediaDownloaderActivity.this, 10), UiKit.dp(MediaDownloaderActivity.this, 8));
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                return tv;
            }
            @Override public View getDropDownView(int pos, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(pos, convertView, parent);
                tv.setBackgroundColor(UiKit.surface(prefs));
                tv.setTextColor(UiKit.textPrimary(prefs));
                tv.setTextSize(13);
                tv.setPadding(UiKit.dp(MediaDownloaderActivity.this, 12), UiKit.dp(MediaDownloaderActivity.this, 10),
                        UiKit.dp(MediaDownloaderActivity.this, 12), UiKit.dp(MediaDownloaderActivity.this, 10));
                return tv;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        return sp;
    }

    // =========================================================================
    // HALAMAN 2: SLIDER PROGRES GLOBAL + TOGGLE DETAIL (OTOMATIS BERSIH)
    // =========================================================================

    private View buildActiveQueuePage() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 8), UiKit.dp(this, 12), UiKit.dp(this, 8));

        // Kartu Ringkasan Slider Progres Global
        LinearLayout summaryCard = UiKit.makeCard(this, prefs);
        summaryCard.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 14), UiKit.dp(this, 14), UiKit.dp(this, 14));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        activeHeadlineText = new TextView(this);
        activeHeadlineText.setText("Tidak ada unduhan aktif");
        activeHeadlineText.setTextColor(UiKit.textPrimary(prefs));
        activeHeadlineText.setTextSize(15);
        activeHeadlineText.setTypeface(null, Typeface.BOLD);
        topRow.addView(activeHeadlineText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        activePercentText = new TextView(this);
        activePercentText.setText("0%");
        activePercentText.setTextColor(UiKit.ACCENT_KB_ALT);
        activePercentText.setTextSize(16);
        activePercentText.setTypeface(null, Typeface.BOLD);
        topRow.addView(activePercentText);
        summaryCard.addView(topRow);

        activeOverallProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        activeOverallProgress.setMax(100);
        activeOverallProgress.setProgress(0);
        activeOverallProgress.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 6));
        summaryCard.addView(activeOverallProgress);

        activeSubText = new TextView(this);
        activeSubText.setText("Item yang selesai atau dibatalkan langsung dibersihkan otomatis.");
        activeSubText.setTextColor(UiKit.textMuted(prefs));
        activeSubText.setTextSize(12);
        activeSubText.setPadding(0, 0, 0, UiKit.dp(this, 10));
        summaryCard.addView(activeSubText);

        LinearLayout ctrlRow = new LinearLayout(this);
        ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
        btnToggleQueueList = UiKit.makePillButton(this, "Tampilkan Semua Lagu", UiKit.surfaceAlt(prefs), UiKit.textPrimary(prefs), v -> {
            showQueueDetails = !showQueueDetails;
            updateQueueDetailVisibility();
        });
        btnActiveCancelAll = UiKit.makePillButton(this, "Batalkan Semua", UiKit.ACCENT_DANGER, Color.WHITE, v -> confirmCancelAllQueue());

        LinearLayout.LayoutParams cGap = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cGap.setMargins(0, 0, UiKit.dp(this, 6), 0);
        ctrlRow.addView(btnToggleQueueList, cGap);
        ctrlRow.addView(btnActiveCancelAll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        summaryCard.addView(ctrlRow);
        box.addView(summaryCard);

        // Daftar Detail Antrean (Tersembunyi secara default sampai user klik "Tampilkan Semua Lagu")
        queueDetailBox = new LinearLayout(this);
        queueDetailBox.setOrientation(LinearLayout.VERTICAL);
        queueDetailBox.setVisibility(View.GONE);

        LinearLayout batchCancelRow = new LinearLayout(this);
        batchCancelRow.setOrientation(LinearLayout.HORIZONTAL);
        batchCancelRow.setPadding(0, 0, 0, UiKit.dp(this, 6));
        btnActiveSelectAll = UiKit.makePillButton(this, "Pilih Semua", UiKit.surfaceAlt(prefs), UiKit.textPrimary(prefs), v -> toggleSelectAllQueue());
        btnActiveCancelSelected = UiKit.makePillButton(this, "Batal Terpilih (0)", UiKit.ACCENT_WARM, Color.WHITE, v -> cancelSelectedQueue());
        batchCancelRow.addView(btnActiveSelectAll, cGap);
        batchCancelRow.addView(btnActiveCancelSelected, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        queueDetailBox.addView(batchCancelRow);

        activeListView = new ListView(this);
        activeListView.setDividerHeight(UiKit.dp(this, 6));
        activeListView.setDivider(null);
        activeAdapter = new ActiveQueueAdapter();
        activeListView.setAdapter(activeAdapter);
        queueDetailBox.addView(activeListView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        box.addView(queueDetailBox, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return box;
    }

    private void updateQueueDetailVisibility() {
        boolean hasItems = !activeList.isEmpty();
        if (!hasItems) showQueueDetails = false;
        queueDetailBox.setVisibility((showQueueDetails && hasItems) ? View.VISIBLE : View.GONE);
        btnToggleQueueList.setText(showQueueDetails ? "Sembunyikan Daftar" : "Tampilkan Semua Lagu (" + activeList.size() + ")");
        btnToggleQueueList.setEnabled(hasItems);
        btnActiveCancelAll.setEnabled(hasItems);
    }

    // =========================================================================
    // HALAMAN 3: SUDAH TERUNDUH
    // =========================================================================

    private View buildCompletedPage() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 8), UiKit.dp(this, 12), UiKit.dp(this, 8));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setPadding(0, 0, 0, UiKit.dp(this, 8));

        doneSummaryText = new TextView(this);
        doneSummaryText.setText("Sudah terunduh (0 file)");
        doneSummaryText.setTextColor(UiKit.textPrimary(prefs));
        doneSummaryText.setTextSize(13);
        doneSummaryText.setTypeface(null, Typeface.BOLD);
        topRow.addView(doneSummaryText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnOpenFolder = UiKit.makePillButton(this, "Buka Folder Downloader", UiKit.ACCENT_KB, Color.WHITE, v -> {
            File dlDir = OfficeUtils.resolveBackupSubfolder(this, "Downloader");
            Intent i = new Intent(this, FileManagerActivity.class);
            i.putExtra(FileManagerActivity.EXTRA_TITLE, "Folder Downloader — Smart Tools & Typing AI");
            i.putExtra(FileManagerActivity.EXTRA_INITIAL_DIR, dlDir.getAbsolutePath());
            startActivity(i);
        });
        topRow.addView(btnOpenFolder);
        box.addView(topRow);

        doneListView = new ListView(this);
        doneListView.setDividerHeight(UiKit.dp(this, 6));
        doneListView.setDivider(null);
        doneAdapter = new CompletedAdapter();
        doneListView.setAdapter(doneAdapter);
        box.addView(doneListView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return box;
    }

    // =========================================================================
    // MINI PLAYER PRATINJAU
    // =========================================================================

    private void buildPreviewMiniPlayer() {
        previewBar = new LinearLayout(this);
        previewBar.setOrientation(LinearLayout.VERTICAL);
        previewBar.setBackgroundColor(UiKit.surface(prefs));
        previewBar.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 8), UiKit.dp(this, 12), UiKit.dp(this, 10));
        previewBar.setVisibility(View.GONE);

        previewTitleText = new TextView(this);
        previewTitleText.setTextColor(UiKit.textPrimary(prefs));
        previewTitleText.setTextSize(13);
        previewTitleText.setTypeface(null, Typeface.BOLD);
        previewTitleText.setSingleLine(true);
        previewTitleText.setEllipsize(TextUtils.TruncateAt.END);
        previewBar.addView(previewTitleText);

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);

        previewSeekBar = new SeekBar(this);
        previewSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && audioPreviewPlayer != null && previewPrepared) {
                    try { audioPreviewPlayer.seekTo(progress); } catch (Throwable ignored) {}
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { userSeeking = false; }
        });
        seekRow.addView(previewSeekBar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        previewTimeText = new TextView(this);
        previewTimeText.setText("0:00");
        previewTimeText.setTextSize(11);
        previewTimeText.setTextColor(UiKit.textMuted(prefs));
        seekRow.addView(previewTimeText);
        previewBar.addView(seekRow);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, UiKit.dp(this, 4), 0, 0);

        btnPreviewPlayPause = UiKit.makePillButton(this, "Jeda", UiKit.ACCENT_AI, Color.WHITE, v -> toggleAudioPreviewPlayPause());
        btnPreviewStop = UiKit.makePillButton(this, "Tutup", UiKit.surfaceAlt(prefs), UiKit.textPrimary(prefs), v -> stopAudioPreview());
        btnPreviewDownload = UiKit.makePillButton(this, "Unduh Ini", UiKit.ACCENT_OFFICE, Color.WHITE, v -> {
            if (previewItem != null) {
                enqueueList(Collections.singletonList(previewItem));
            }
        });
        LinearLayout.LayoutParams pGap = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pGap.setMargins(0, 0, UiKit.dp(this, 6), 0);
        btnRow.addView(btnPreviewPlayPause, pGap);
        btnRow.addView(btnPreviewStop, pGap);
        btnRow.addView(btnPreviewDownload, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        previewBar.addView(btnRow);
    }

    // =========================================================================
    // AKSI SCAN, SEARCH, RESET, PREVIEW & DOWNLOAD
    // =========================================================================

    private void pasteAndScanNow() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData cd = cm.getPrimaryClip();
                if (cd != null && cd.getItemCount() > 0 && cd.getItemAt(0).getText() != null) {
                    String txt = cd.getItemAt(0).getText().toString().trim();
                    if (!txt.isEmpty()) {
                        inputLinkOrSearch.setText(txt);
                        inputLinkOrSearch.setSelection(txt.length());
                        if (scanning) {
                            if (!activeScanProcessId.isEmpty()) MediaDownloaderEngine.destroyProcessSafe(activeScanProcessId);
                            scanning = false;
                        }
                        startScanOrSearch();
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
        Toast.makeText(this, "Papan klip kosong.", Toast.LENGTH_SHORT).show();
    }

    private void resetScanStep() {
        if (scanning && !activeScanProcessId.isEmpty()) {
            MediaDownloaderEngine.destroyProcessSafe(activeScanProcessId);
        }
        scanning = false;
        btnScan.setText("Proses / Cari");
        inputLinkOrSearch.setText("");
        scannedItems.clear();
        stepConfigCard.setVisibility(View.GONE);
        singleActionCard.setVisibility(View.GONE);
        collectionSummaryCard.setVisibility(View.GONE);
        if (limitRow != null) limitRow.setVisibility(View.VISIBLE);
        scanSummaryText.setText("Tempel tautan atau ketik judul lagu, lalu tekan Tempel & Proses.");
        OfficeUtils.announce(inputLinkOrSearch, "Direset, silakan tempel tautan baru");
    }

    private void runUpdateEngine() {
        btnUpdateEngine.setEnabled(false);
        btnUpdateEngine.setText("Memperbarui...");
        scanExecutor.submit(() -> {
            String msg;
            try {
                msg = MediaDownloaderEngine.updateYtDlpEngine(this);
            } catch (Throwable t) {
                msg = "Gagal memperbarui engine: " + t.getMessage();
            }
            final String finalMsg = msg;
            mainHandler.post(() -> {
                if (isFinishing()) return;
                btnUpdateEngine.setEnabled(true);
                btnUpdateEngine.setText("Update yt-dlp");
                Toast.makeText(this, finalMsg, Toast.LENGTH_LONG).show();
                OfficeUtils.announce(btnUpdateEngine, finalMsg);
            });
        });
    }

    private void startScanOrSearch() {
        final String query = inputLinkOrSearch.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "Tempel link atau ketik judul lagu dulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (scanning) {
            if (!activeScanProcessId.isEmpty()) MediaDownloaderEngine.destroyProcessSafe(activeScanProcessId);
            scanning = false;
            btnScan.setText("Proses / Cari");
            return;
        }
        scanning = true;
        activeScanProcessId = "scan_" + System.currentTimeMillis();
        btnScan.setText("Stop Scan");
        stepConfigCard.setVisibility(View.GONE);
        singleActionCard.setVisibility(View.GONE);
        collectionSummaryCard.setVisibility(View.GONE);

        scanSummaryText.setText(MediaDownloaderEngine.isLikelyUrl(query)
                ? "Sedang memproses tautan..."
                : "Sedang mencari di YouTube: " + query + "...");
        OfficeUtils.announce(scanSummaryText, scanSummaryText.getText().toString());

        final int limit = SCAN_LIMIT_VALS[Math.max(0, Math.min(spScanLimit.getSelectedItemPosition(), SCAN_LIMIT_VALS.length - 1))];
        final String pid = activeScanProcessId;

        scanExecutor.submit(() -> {
            try {
                MediaDownloaderEngine.ScanResult res = MediaDownloaderEngine.scanLinkOrSearch(this, query, limit, pid);
                mainHandler.post(() -> {
                    if (isFinishing()) return;
                    scanning = false;
                    btnScan.setText("Proses / Cari");
                    scannedItems.clear();
                    scannedItems.addAll(res.items);

                    stepConfigCard.setVisibility(View.VISIBLE);
                    if (limitRow != null) limitRow.setVisibility(View.GONE);

                    lastCollectionLabel = res.sourceKind + " • " + res.containerKind
                            + (res.containerTitle.isEmpty() ? "" : (" (" + res.containerTitle + ")"));
                    String info = lastCollectionLabel + " — " + res.items.size() + " item ditemukan.";
                    scanSummaryText.setText(info);

                    if (res.items.size() == 1) {
                        MediaDownloaderEngine.MediaItem single = res.items.get(0);
                        singleTitleText.setText(single.title);
                        String meta = single.sourceKind
                                + (single.uploader.isEmpty() ? "" : (" • " + single.uploader))
                                + (single.durationText.isEmpty() ? "" : (" • Durasi " + single.durationText));
                        singleMetaText.setText(meta);
                        singleActionCard.setVisibility(View.VISIBLE);
                        collectionSummaryCard.setVisibility(View.GONE);
                    } else {
                        singleActionCard.setVisibility(View.GONE);
                        collectionSummaryCard.setVisibility(View.VISIBLE);
                        collectionTitleText.setText(res.items.size() + " Lagu / Video Ditemukan");
                        collectionDescText.setText(lastCollectionLabel
                                + "\nPilih tipe & kualitas di atas lalu ketuk Unduh Semua atau pilih per item.");
                        btnCollectionDownloadAll.setText("Unduh Semua (" + res.items.size() + " Item)\n"
                                + "Langsung unduh seluruh daftar di latar belakang.");
                    }
                    OfficeUtils.announce(stepConfigCard, info + " Silakan atur Audio atau Video lalu unduh.");
                });
            } catch (final Throwable err) {
                mainHandler.post(() -> {
                    if (isFinishing()) return;
                    scanning = false;
                    btnScan.setText("Proses / Cari");
                    String msg = "Gagal memproses: " + (err.getMessage() == null ? "Periksa koneksi internet & tautan." : err.getMessage());
                    scanSummaryText.setText(msg);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    OfficeUtils.announce(scanSummaryText, msg);
                });
            }
        });
    }

    private void playPreviewForItem(final MediaDownloaderEngine.MediaItem item) {
        if (item == null || TextUtils.isEmpty(item.webpageUrl)) return;
        final boolean wantVideo = selectedTypeIsVideo;

        stopAudioPreview();
        previewItem = item;
        previewBar.setVisibility(View.VISIBLE);
        if (btnPreviewDownload != null) btnPreviewDownload.setVisibility(View.VISIBLE);
        previewTitleText.setText("Memuat pratinjau: " + item.title);
        previewTimeText.setText("...");
        btnPreviewPlayPause.setText("Memuat");
        btnPreviewPlayPause.setEnabled(false);
        OfficeUtils.announce(previewBar, "Memuat pratinjau " + item.title);

        scanExecutor.submit(() -> {
            try {
                String streamUrl = MediaDownloaderEngine.fetchPreviewStreamUrl(
                        this, item.webpageUrl, wantVideo, "prev_" + System.currentTimeMillis());
                mainHandler.post(() -> {
                    if (isFinishing()) return;
                    if (wantVideo) {
                        previewBar.setVisibility(View.GONE);
                        showVideoPreviewDialog(item, streamUrl);
                    } else {
                        startAudioPreviewStream(item, streamUrl);
                    }
                });
            } catch (final Throwable err) {
                mainHandler.post(() -> {
                    if (isFinishing()) return;
                    previewTitleText.setText("Gagal memuat pratinjau: " + item.title);
                    btnPreviewPlayPause.setText("Putar");
                    btnPreviewPlayPause.setEnabled(false);
                    Toast.makeText(this, "Pratinjau gagal: " + err.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void startAudioPreviewStream(MediaDownloaderEngine.MediaItem item, String streamUrl) {
        stopAudioPreview();
        previewItem = item;
        previewBar.setVisibility(View.VISIBLE);
        if (btnPreviewDownload != null) btnPreviewDownload.setVisibility(View.VISIBLE);
        previewTitleText.setText("Memutar: " + item.title);
        previewPrepared = false;

        try {
            audioPreviewPlayer = new MediaPlayer();
            audioPreviewPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build());
            audioPreviewPlayer.setDataSource(streamUrl);
            audioPreviewPlayer.setOnPreparedListener(mp -> {
                previewPrepared = true;
                previewSeekBar.setMax(Math.max(1, mp.getDuration()));
                mp.start();
                btnPreviewPlayPause.setEnabled(true);
                btnPreviewPlayPause.setText("Jeda");
                mainHandler.removeCallbacks(seekTicker);
                mainHandler.post(seekTicker);
            });
            audioPreviewPlayer.setOnCompletionListener(mp -> {
                btnPreviewPlayPause.setText("Putar");
                previewSeekBar.setProgress(0);
            });
            audioPreviewPlayer.setOnErrorListener((mp, what, extra) -> {
                previewTitleText.setText("Stream audio tidak dapat diputar.");
                btnPreviewPlayPause.setEnabled(false);
                return true;
            });
            audioPreviewPlayer.prepareAsync();
        } catch (Throwable t) {
            previewTitleText.setText("Gagal memulai pemutar pratinjau.");
        }
    }

    private void toggleAudioPreviewPlayPause() {
        if (audioPreviewPlayer == null || !previewPrepared) return;
        try {
            if (audioPreviewPlayer.isPlaying()) {
                audioPreviewPlayer.pause();
                btnPreviewPlayPause.setText("Putar");
            } else {
                audioPreviewPlayer.start();
                btnPreviewPlayPause.setText("Jeda");
            }
        } catch (Throwable ignored) {}
    }

    private void stopAudioPreview() {
        mainHandler.removeCallbacks(seekTicker);
        previewPrepared = false;
        if (audioPreviewPlayer != null) {
            try { audioPreviewPlayer.stop(); } catch (Throwable ignored) {}
            try { audioPreviewPlayer.release(); } catch (Throwable ignored) {}
            audioPreviewPlayer = null;
        }
        if (previewBar != null) previewBar.setVisibility(View.GONE);
    }

    private void updatePreviewSeekProgress() {
        if (audioPreviewPlayer != null && previewPrepared && !userSeeking) {
            try {
                int pos = audioPreviewPlayer.getCurrentPosition();
                int dur = audioPreviewPlayer.getDuration();
                previewSeekBar.setProgress(pos);
                previewTimeText.setText(MediaDownloaderEngine.formatDuration(pos / 1000)
                        + " / " + MediaDownloaderEngine.formatDuration(dur / 1000));
            } catch (Throwable ignored) {}
        }
    }

    private void showVideoPreviewDialog(final MediaDownloaderEngine.MediaItem item, final String streamUrl) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10), UiKit.dp(this, 10), UiKit.dp(this, 10));

        final VideoView vv = new VideoView(this);
        vv.setVideoURI(Uri.parse(streamUrl));
        vv.setOnPreparedListener(mp -> vv.start());
        box.addView(vv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 240)));

        android.widget.MediaController mc = new android.widget.MediaController(this);
        mc.setAnchorView(vv);
        vv.setMediaController(mc);

        AlertDialog dlg = UiKit.dialog(this, prefs)
                .setTitle(item.title)
                .setView(box)
                .setPositiveButton("Unduh Video Ini", (d, w) -> {
                    try { vv.stopPlayback(); } catch (Throwable ignored) {}
                    enqueueList(Collections.singletonList(item));
                })
                .setNegativeButton("Tutup", (d, w) -> {
                    try { vv.stopPlayback(); } catch (Throwable ignored) {}
                })
                .create();
        dlg.setOnDismissListener(d -> {
            try { vv.stopPlayback(); } catch (Throwable ignored) {}
        });
        dlg.show();
    }

    private void downloadAllScanned() {
        if (scannedItems.isEmpty()) {
            Toast.makeText(this, "Daftar masih kosong.", Toast.LENGTH_SHORT).show();
            return;
        }
        enqueueList(new ArrayList<>(scannedItems));
    }

    private void enqueueList(List<MediaDownloaderEngine.MediaItem> list) {
        String mode = getSelectedModeCode();
        int kbps = BITRATE_VALS[Math.max(0, Math.min(spAudioBitrate.getSelectedItemPosition(), BITRATE_VALS.length - 1))];
        MediaDownloadService.enqueueItems(this, list, mode, kbps);
        Toast.makeText(this, "Mengunduh " + list.size() + " item di latar belakang.", Toast.LENGTH_SHORT).show();
        OfficeUtils.announce(pageHost, "Mulai mengunduh " + list.size() + " item.");
        switchPage(PAGE_ACTIVE, false);
        refreshQueueAndDoneLists();
    }

    private String getSelectedModeCode() {
        if (selectedTypeIsVideo) {
            int idx = spVideoRes.getSelectedItemPosition();
            if (idx < 0 || idx >= VIDEO_RES_CODES.length) return MediaDownloaderEngine.MODE_VIDEO_720;
            return VIDEO_RES_CODES[idx];
        } else {
            int idx = spAudioFormat.getSelectedItemPosition();
            if (idx < 0 || idx >= AUDIO_FORMAT_CODES.length) return MediaDownloaderEngine.MODE_AUDIO_MP3;
            return AUDIO_FORMAT_CODES[idx];
        }
    }

    // =========================================================================
    // LOGIKA ANTREAN & PEMBATALAN FLEKSIBEL (OTOMATIS BERSIH)
    // =========================================================================

    private void refreshQueueAndDoneLists() {
        activeList.clear();
        activeList.addAll(MediaDownloadService.getActiveSnapshot());
        doneList.clear();
        doneList.addAll(MediaDownloadService.getCompletedSnapshot(this));

        MediaDownloadService.BatchSnapshot snap = MediaDownloadService.getBatchSnapshot();
        tabBtn1.setText("2. Diunduh (" + snap.remainingCount + ")");
        tabBtn2.setText("3. Selesai (" + doneList.size() + ")");

        if (activeHeadlineText != null) {
            if (snap.remainingCount > 0) {
                activeHeadlineText.setText("Mengunduh " + snap.totalInBatch + " item ("
                        + snap.completedInBatch + " selesai, sisa " + snap.remainingCount + ")");
                activePercentText.setText(snap.overallPercent + "%");
                activeOverallProgress.setProgress(snap.overallPercent);
                activeSubText.setText(snap.activeHeadline.isEmpty()
                        ? "Berjalan di latar belakang walau aplikasi ditutup..."
                        : ("Sedang diproses: " + snap.activeHeadline));
            } else {
                activeHeadlineText.setText("Tidak ada unduhan aktif");
                activePercentText.setText(snap.completedInBatch > 0 ? "100%" : "0%");
                activeOverallProgress.setProgress(snap.completedInBatch > 0 ? 100 : 0);
                if (!snap.lastFailureMsg.isEmpty()) {
                    activeSubText.setText("Catatan terakhir: " + snap.lastFailureMsg);
                } else {
                    activeSubText.setText("Semua unduhan selesai dan daftar otomatis dibersihkan.");
                }
            }
            updateQueueDetailVisibility();
        }

        if (activeAdapter != null) activeAdapter.notifyDataSetChanged();
        if (doneAdapter != null) doneAdapter.notifyDataSetChanged();
        if (doneSummaryText != null) doneSummaryText.setText("Sudah terunduh (" + doneList.size() + " file)");
        updateQueueCancelSelectedButton();
    }

    private void updateQueueCancelSelectedButton() {
        int count = 0;
        for (MediaDownloadService.DownloadTask t : activeList) {
            if (selectedQueueIds.contains(t.taskId)) count++;
        }
        if (btnActiveCancelSelected != null) {
            btnActiveCancelSelected.setText("Batal Terpilih (" + count + ")");
        }
    }

    private void toggleSelectAllQueue() {
        boolean hasUnselected = false;
        for (MediaDownloadService.DownloadTask t : activeList) {
            if (!selectedQueueIds.contains(t.taskId)) {
                hasUnselected = true;
                break;
            }
        }
        if (hasUnselected) {
            for (MediaDownloadService.DownloadTask t : activeList) selectedQueueIds.add(t.taskId);
        } else {
            selectedQueueIds.clear();
        }
        activeAdapter.notifyDataSetChanged();
        updateQueueCancelSelectedButton();
    }

    private void cancelSelectedQueue() {
        if (selectedQueueIds.isEmpty()) {
            Toast.makeText(this, "Centang item yang ingin dibatalkan dulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        MediaDownloadService.cancelTasks(new ArrayList<>(selectedQueueIds));
        selectedQueueIds.clear();
        refreshQueueAndDoneLists();
        OfficeUtils.announce(pageHost, "Item terpilih dibatalkan dan dibersihkan.");
    }

    private void confirmCancelAllQueue() {
        if (MediaDownloadService.getPendingOrRunningCount() == 0) {
            Toast.makeText(this, "Tidak ada antrean aktif.", Toast.LENGTH_SHORT).show();
            return;
        }
        UiKit.dialog(this, prefs)
                .setTitle("Batalkan Semua Unduhan?")
                .setMessage("Semua unduhan yang berjalan dan menunggu akan dihentikan serta langsung dibersihkan.")
                .setPositiveButton("Batalkan Semua", (d, w) -> {
                    MediaDownloadService.cancelAllActive();
                    selectedQueueIds.clear();
                    refreshQueueAndDoneLists();
                })
                .setNegativeButton("Tidak", null)
                .show();
    }

    // =========================================================================
    // ADAPTER DIALOG PILIH PER ITEM (VIEWHOLDER RINGAN & AKSESIBEL)
    // =========================================================================

    private final class DialogScanAdapter extends BaseAdapter {
        private final Runnable onCountChange;

        DialogScanAdapter(Runnable onCountChange) {
            this.onCountChange = onCountChange;
        }

        private final class VH {
            LinearLayout card;
            CheckBox cb;
            TextView tvTitle, tvMeta;
            Button btnPlay, btnDl;
        }

        @Override public int getCount() { return scannedItems.size(); }
        @Override public Object getItem(int pos) { return scannedItems.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            VH h;
            if (convertView == null) {
                h = new VH();
                h.card = new LinearLayout(MediaDownloaderActivity.this);
                h.card.setOrientation(LinearLayout.VERTICAL);
                h.card.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(MediaDownloaderActivity.this, 12), UiKit.dp(MediaDownloaderActivity.this, 1)));
                int p = UiKit.dp(MediaDownloaderActivity.this, 10);
                h.card.setPadding(p, p, p, p);

                LinearLayout top = new LinearLayout(MediaDownloaderActivity.this);
                top.setOrientation(LinearLayout.HORIZONTAL);
                top.setGravity(Gravity.CENTER_VERTICAL);

                h.cb = new CheckBox(MediaDownloaderActivity.this);
                top.addView(h.cb);

                LinearLayout texts = new LinearLayout(MediaDownloaderActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                h.tvTitle = new TextView(MediaDownloaderActivity.this);
                h.tvTitle.setTextColor(UiKit.textPrimary(prefs));
                h.tvTitle.setTextSize(14);
                h.tvTitle.setTypeface(null, Typeface.BOLD);
                h.tvTitle.setMaxLines(2);
                h.tvTitle.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(h.tvTitle);

                h.tvMeta = new TextView(MediaDownloaderActivity.this);
                h.tvMeta.setTextColor(UiKit.textMuted(prefs));
                h.tvMeta.setTextSize(11);
                texts.addView(h.tvMeta);
                top.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                h.card.addView(top);

                LinearLayout actions = new LinearLayout(MediaDownloaderActivity.this);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                actions.setPadding(0, UiKit.dp(MediaDownloaderActivity.this, 6), 0, 0);

                h.btnPlay = UiKit.makePillButton(MediaDownloaderActivity.this, "Putar Dulu", UiKit.ACCENT_AI_ALT, Color.WHITE, null);
                h.btnDl = UiKit.makePillButton(MediaDownloaderActivity.this, "Unduh Ini", UiKit.ACCENT_OFFICE, Color.WHITE, null);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(0, 0, UiKit.dp(MediaDownloaderActivity.this, 6), 0);
                actions.addView(h.btnPlay, lp);
                actions.addView(h.btnDl, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                h.card.addView(actions);

                h.card.setTag(h);
                convertView = h.card;
            } else {
                h = (VH) convertView.getTag();
            }

            final MediaDownloaderEngine.MediaItem item = scannedItems.get(position);
            h.tvTitle.setText((position + 1) + ". " + item.title);
            String meta = item.sourceKind
                    + (item.uploader.isEmpty() ? "" : (" • " + item.uploader))
                    + (item.durationText.isEmpty() ? "" : (" • " + item.durationText));
            h.tvMeta.setText(meta);

            h.cb.setOnCheckedChangeListener(null);
            h.cb.setChecked(item.selected);
            h.cb.setContentDescription("Pilih " + item.title);
            h.cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                item.selected = isChecked;
                if (onCountChange != null) onCountChange.run();
            });

            h.btnPlay.setContentDescription("Putar pratinjau " + item.title);
            h.btnPlay.setOnClickListener(v -> playPreviewForItem(item));

            h.btnDl.setContentDescription("Unduh " + item.title);
            h.btnDl.setOnClickListener(v -> enqueueList(Collections.singletonList(item)));

            return convertView;
        }
    }

    private final class ActiveQueueAdapter extends BaseAdapter {
        private final class VH {
            LinearLayout card;
            CheckBox cb;
            TextView tvTitle, tvStatus;
            ProgressBar pb;
            Button btnCancel;
        }

        @Override public int getCount() { return activeList.size(); }
        @Override public Object getItem(int pos) { return activeList.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            VH h;
            if (convertView == null) {
                h = new VH();
                h.card = new LinearLayout(MediaDownloaderActivity.this);
                h.card.setOrientation(LinearLayout.VERTICAL);
                h.card.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(MediaDownloaderActivity.this, 12), UiKit.dp(MediaDownloaderActivity.this, 1)));
                int p = UiKit.dp(MediaDownloaderActivity.this, 10);
                h.card.setPadding(p, p, p, p);

                LinearLayout top = new LinearLayout(MediaDownloaderActivity.this);
                top.setOrientation(LinearLayout.HORIZONTAL);
                top.setGravity(Gravity.CENTER_VERTICAL);

                h.cb = new CheckBox(MediaDownloaderActivity.this);
                top.addView(h.cb);

                LinearLayout info = new LinearLayout(MediaDownloaderActivity.this);
                info.setOrientation(LinearLayout.VERTICAL);
                h.tvTitle = new TextView(MediaDownloaderActivity.this);
                h.tvTitle.setTextColor(UiKit.textPrimary(prefs));
                h.tvTitle.setTextSize(14);
                h.tvTitle.setTypeface(null, Typeface.BOLD);
                h.tvTitle.setMaxLines(2);
                h.tvTitle.setEllipsize(TextUtils.TruncateAt.END);
                info.addView(h.tvTitle);

                h.tvStatus = new TextView(MediaDownloaderActivity.this);
                h.tvStatus.setTextColor(UiKit.textMuted(prefs));
                h.tvStatus.setTextSize(12);
                info.addView(h.tvStatus);
                top.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                h.btnCancel = UiKit.makePillButton(MediaDownloaderActivity.this, "Batalkan", UiKit.ACCENT_DANGER, Color.WHITE, null);
                top.addView(h.btnCancel);
                h.card.addView(top);

                h.pb = new ProgressBar(MediaDownloaderActivity.this, null, android.R.attr.progressBarStyleHorizontal);
                h.pb.setMax(100);
                h.pb.setPadding(0, UiKit.dp(MediaDownloaderActivity.this, 6), 0, 0);
                h.card.addView(h.pb);

                h.card.setTag(h);
                convertView = h.card;
            } else {
                h = (VH) convertView.getTag();
            }

            final MediaDownloadService.DownloadTask task = activeList.get(position);
            h.tvTitle.setText(task.item.title);
            h.tvStatus.setText(MediaDownloaderEngine.getModeDisplayLabel(task.mode)
                    + (MediaDownloaderEngine.isVideoMode(task.mode) ? "" : (" " + task.bitrateKbps + "k"))
                    + " • " + task.statusText);
            h.pb.setProgress(task.progress);

            h.cb.setOnCheckedChangeListener(null);
            h.cb.setChecked(selectedQueueIds.contains(task.taskId));
            h.cb.setContentDescription("Pilih antrean " + task.item.title);
            h.cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) selectedQueueIds.add(task.taskId);
                else selectedQueueIds.remove(task.taskId);
                updateQueueCancelSelectedButton();
            });

            h.btnCancel.setContentDescription("Batalkan unduhan " + task.item.title);
            h.btnCancel.setOnClickListener(v -> {
                MediaDownloadService.cancelTask(task.taskId);
                selectedQueueIds.remove(task.taskId);
                refreshQueueAndDoneLists();
            });

            return convertView;
        }
    }

    private final class CompletedAdapter extends BaseAdapter {
        private final class VH {
            LinearLayout card;
            TextView tvTitle, tvMeta;
            Button btnPlay, btnEdit, btnShare, btnDelete;
        }

        @Override public int getCount() { return doneList.size(); }
        @Override public Object getItem(int pos) { return doneList.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            VH h;
            if (convertView == null) {
                h = new VH();
                h.card = new LinearLayout(MediaDownloaderActivity.this);
                h.card.setOrientation(LinearLayout.VERTICAL);
                h.card.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(MediaDownloaderActivity.this, 12), UiKit.dp(MediaDownloaderActivity.this, 1)));
                int p = UiKit.dp(MediaDownloaderActivity.this, 10);
                h.card.setPadding(p, p, p, p);

                h.tvTitle = new TextView(MediaDownloaderActivity.this);
                h.tvTitle.setTextColor(UiKit.textPrimary(prefs));
                h.tvTitle.setTextSize(14);
                h.tvTitle.setTypeface(null, Typeface.BOLD);
                h.tvTitle.setMaxLines(2);
                h.tvTitle.setEllipsize(TextUtils.TruncateAt.END);
                h.card.addView(h.tvTitle);

                h.tvMeta = new TextView(MediaDownloaderActivity.this);
                h.tvMeta.setTextColor(UiKit.textMuted(prefs));
                h.tvMeta.setTextSize(11);
                h.tvMeta.setPadding(0, UiKit.dp(MediaDownloaderActivity.this, 2), 0, UiKit.dp(MediaDownloaderActivity.this, 6));
                h.card.addView(h.tvMeta);

                LinearLayout actions = new LinearLayout(MediaDownloaderActivity.this);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                h.btnPlay = UiKit.makePillButton(MediaDownloaderActivity.this, "Putar", UiKit.ACCENT_AI, Color.WHITE, null);
                h.btnEdit = UiKit.makePillButton(MediaDownloaderActivity.this, "Editor", UiKit.ACCENT_KB, Color.WHITE, null);
                h.btnShare = UiKit.makePillButton(MediaDownloaderActivity.this, "Bagikan", UiKit.ACCENT_OFFICE, Color.WHITE, null);
                h.btnDelete = UiKit.makePillButton(MediaDownloaderActivity.this, "Hapus", UiKit.ACCENT_DANGER, Color.WHITE, null);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(0, 0, UiKit.dp(MediaDownloaderActivity.this, 4), 0);
                actions.addView(h.btnPlay, lp);
                actions.addView(h.btnEdit, lp);
                actions.addView(h.btnShare, lp);
                actions.addView(h.btnDelete, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                h.card.addView(actions);

                h.card.setTag(h);
                convertView = h.card;
            } else {
                h = (VH) convertView.getTag();
            }

            final MediaDownloadService.CompletedEntry entry = doneList.get(position);
            final File f = new File(entry.filePath);
            h.tvTitle.setText(entry.title);
            h.tvMeta.setText(entry.sourceKind + " • " + entry.modeLabel + " • " + formatBytes(entry.fileSizeBytes) + "\n" + entry.filePath);

            h.btnPlay.setContentDescription("Putar hasil unduhan " + entry.title);
            h.btnPlay.setOnClickListener(v -> openOrPlayLocalFile(f, entry.title));

            h.btnEdit.setContentDescription("Buka di Editor Audio atau Video Pro");
            h.btnEdit.setOnClickListener(v -> openInStudioEditor(f));

            h.btnShare.setContentDescription("Bagikan file " + entry.title);
            h.btnShare.setOnClickListener(v -> shareLocalFile(f, entry.title));

            h.btnDelete.setContentDescription("Hapus file " + entry.title);
            h.btnDelete.setOnClickListener(v -> confirmDeleteCompleted(entry));

            return convertView;
        }
    }

    private void openOrPlayLocalFile(File f, String title) {
        if (!f.exists()) {
            Toast.makeText(this, "File sudah tidak ada di penyimpanan.", Toast.LENGTH_SHORT).show();
            return;
        }
        String low = f.getName().toLowerCase(Locale.ROOT);
        if (low.endsWith(".mp4") || low.endsWith(".mkv") || low.endsWith(".webm")) {
            showVideoPreviewDialogForLocal(f, title);
        } else {
            MediaDownloaderEngine.MediaItem fake = new MediaDownloaderEngine.MediaItem();
            fake.title = title;
            startAudioPreviewStream(fake, Uri.fromFile(f).toString());
            btnPreviewDownload.setVisibility(View.GONE);
        }
    }

    private void showVideoPreviewDialogForLocal(File f, String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        VideoView vv = new VideoView(this);
        vv.setVideoPath(f.getAbsolutePath());
        vv.setOnPreparedListener(mp -> vv.start());
        box.addView(vv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 240)));
        android.widget.MediaController mc = new android.widget.MediaController(this);
        mc.setAnchorView(vv);
        vv.setMediaController(mc);

        UiKit.dialog(this, prefs)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("Tutup", (d, w) -> {
                    try { vv.stopPlayback(); } catch (Throwable ignored) {}
                })
                .show();
    }

    private void openInStudioEditor(File f) {
        if (!f.exists()) {
            Toast.makeText(this, "File sudah tidak ada.", Toast.LENGTH_SHORT).show();
            return;
        }
        String low = f.getName().toLowerCase(Locale.ROOT);
        Intent i;
        if (low.endsWith(".mp4") || low.endsWith(".mkv") || low.endsWith(".webm")) {
            i = new Intent(this, VideoEditorStudioActivity.class);
            i.putExtra(VideoEditorStudioActivity.EXTRA_VIDEO_URI, Uri.fromFile(f).toString());
        } else {
            i = new Intent(this, AudioEditorStudioActivity.class);
            i.putExtra(AudioEditorStudioActivity.EXTRA_AUDIO_URI, Uri.fromFile(f).toString());
        }
        startActivity(i);
    }

    private void shareLocalFile(File f, String title) {
        if (!f.exists()) {
            Toast.makeText(this, "File tidak ditemukan.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            OfficeUtils.shareLocalFile(this, f, f.getName());
        } catch (Throwable t) {
            Toast.makeText(this, "Gagal membagikan file: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDeleteCompleted(final MediaDownloadService.CompletedEntry entry) {
        UiKit.dialog(this, prefs)
                .setTitle("Hapus Item?")
                .setMessage("Hapus \"" + entry.title + "\" dari riwayat saja atau hapus juga file dari penyimpanan?")
                .setPositiveButton("Hapus File & Riwayat", (d, w) -> {
                    MediaDownloadService.removeCompletedEntry(this, entry.taskId, true);
                    refreshQueueAndDoneLists();
                })
                .setNeutralButton("Riwayat Saja", (d, w) -> {
                    MediaDownloadService.removeCompletedEntry(this, entry.taskId, false);
                    refreshQueueAndDoneLists();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 KB";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
