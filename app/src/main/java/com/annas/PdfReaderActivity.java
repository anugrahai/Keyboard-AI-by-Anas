package com.annas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Keyboard PDF Anas" — Pembaca PDF & Dokumen Aksesibel (ala Envision AI).
 * - Membuka PDF secara instan hingga ribuan halaman (On-Demand + Cache Disk + OCR Offline 15+ Bahasa).
 * - Navigasi gestur 1 jari kiri/kanan, tombol Next/Prev, Lompat Halaman, & Cari Kata.
 * - Mode Reading TTS Novel Berkelanjutan (otomatis lanjut antar halaman tanpa terputus).
 * - Mode Edit 1 Halaman vs Edit Seluruh PDF ("Kotak Edit Cerdas" tunggal anti-OOM).
 */
public class PdfReaderActivity extends Activity {

    private static final int REQ_OPEN_PDF = 9101;
    private static final int REQ_EXPORT_FILE = 9102;
    private static final int REQ_MANUAL_LANG = 9103;

    private SharedPreferences uiPrefs;
    private PdfDocumentCache docCache;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService preFetchWorker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int currentPage = 0;
    private String currentPageTag = "";
    private String currentPageText = "";

    // Mode: 0 = Reader (Envision), 1 = Edit 1 Halaman, 2 = Edit Seluruh PDF (Kotak Edit Cerdas)
    private int editMode = 0;
    private boolean smartEditDirty = false;
    private boolean loadingEditorText = false;

    // UI Components
    private LinearLayout rootLayout;
    private TextView titleLabel, metaLabel, pageBadge, statusLabel;
    private Button btnPrev, btnNext, btnJump, btnLang, btnEditMenu, btnExportMenu, btnPlayNovel, btnStopNovel;
    private LinearLayout readerContainer, smartEditContainer, smartEditAllNavRow;
    private TextView readerTextView;
    private ScrollView readerScroll;
    private EditText smartEditBox;
    private TextView smartEditHintLabel;
    private Button btnSmartPrev, btnSmartNext, btnSmartFindReplace, btnSmartSaveOk, btnSmartCancel;

    // Continuous Novel TTS
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsPlaying = false;
    private long ttsSessionId = 0L;
    private final ArrayList<String> ttsQueueChunks = new ArrayList<>();
    private int ttsChunkIdx = 0;

    // 1-Finger Swipe Gesture Detection
    private float swipeStartX = 0f;
    private float swipeStartY = 0f;
    private boolean swipeTracking = false;
    private boolean swipeTriggered = false;

    private String pendingExportType = "docx";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uiPrefs = OfficeUtils.mainPrefs(this);
        UiKit.applySystemBars(this, uiPrefs);
        OfficeUtils.ensureAllWorkspaceFolders(this);
        buildUi();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        Uri targetUri = extractUriFromIntent(intent);
        if (targetUri != null) {
            openDocumentUri(targetUri);
        } else {
            readerTextView.setText("Ketuk tombol 'Buka PDF / Dokumen' di bawah untuk memilih berkas PDF, DOCX, ODT, atau TXT.\n\n"
                    + "Tips:\n"
                    + "• Mendukung PDF bergambar/scan (OCR Offline 15+ Bahasa).\n"
                    + "• Geser 1 jari kiri/kanan untuk pindah halaman.\n"
                    + "• Mode Baca Novel TTS otomatis lanjut ke halaman berikutnya sampai ribuan halaman.");
            metaLabel.setText("Siap membuka berkas • Bahasa OCR aktif: " + OcrEngineManager.getLanguageDisplayName(OcrEngineManager.getActiveLanguageCombo(this)));
        }
    }

    private Uri extractUriFromIntent(Intent intent) {
        if (intent == null) return null;
        if (intent.getData() != null) return intent.getData();
        String extraStr = intent.getStringExtra("extra_uri");
        if (extraStr != null && !extraStr.isEmpty()) {
            try { return Uri.parse(extraStr); } catch (Throwable ignored) {}
        }
        try {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) return stream;
        } catch (Throwable ignored) {}
        return null;
    }

    private void buildUi() {
        int bg = UiKit.bg(uiPrefs);
        int surface = UiKit.surface(uiPrefs);
        int surfaceAlt = UiKit.surfaceAlt(uiPrefs);
        int txt = UiKit.textPrimary(uiPrefs);
        int muted = UiKit.textMuted(uiPrefs);
        int border = UiKit.border(uiPrefs);
        int p = UiKit.dp(this, 10);

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(bg);
        rootLayout.setPadding(p, p, p, p);

        // Header Ringkas & Jelas
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        titleLabel = new TextView(this);
        titleLabel.setText("Pembaca PDF & Dokumen — Anas");
        titleLabel.setTextColor(txt);
        titleLabel.setTextSize(17);
        titleLabel.setTypeface(null, Typeface.BOLD);
        titleLabel.setSingleLine(true);
        titleLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headerRow.addView(titleLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        headerRow.addView(OfficeUtils.makeChipButton(this, "Buka PDF", "Pilih berkas PDF atau dokumen lain",
                UiKit.ACCENT_KB, Color.WHITE, v -> launchFilePicker()));
        headerRow.addView(OfficeUtils.makeChipButton(this, "AI PDF", "Ringkas, jelaskan, terjemahkan, atau tanya isi PDF (Premium)",
                UiKit.ACCENT_AI, Color.WHITE, v -> {
                    int total = docCache != null ? Math.max(1, docCache.getTotalPages()) : 1;
                    OfficeAiHelper.showPdfAiDialog(this, uiPrefs, currentPageText,
                            titleLabel.getText().toString(), currentPage + 1, total);
                }));
        headerRow.addView(OfficeUtils.makeChipButton(this, "Word", "Pindah ke editor Microsoft Word lengkap",
                surfaceAlt, txt, v -> {
                    startActivity(new Intent(this, DocumentStudioActivity.class));
                }));
        rootLayout.addView(headerRow);

        metaLabel = new TextView(this);
        metaLabel.setTextColor(muted);
        metaLabel.setTextSize(12);
        metaLabel.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 2), UiKit.dp(this, 2), UiKit.dp(this, 4));
        rootLayout.addView(metaLabel);

        // Baris Aksi Utama (4 Tombol Besar & Jelas)
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        btnEditMenu = OfficeUtils.makeRowButton(this, "Edit PDF", "Pilih Edit Halaman Ini atau Edit Seluruh PDF",
                UiKit.ACCENT_AI, Color.WHITE, v -> showEditModeChooser());
        btnLang = OfficeUtils.makeRowButton(this, "Bahasa OCR", "Pilih bahasa OCR atau unduh bahasa tambahan",
                UiKit.ACCENT_KB_ALT, Color.WHITE, v -> showOcrToolsChooser());
        btnExportMenu = OfficeUtils.makeRowButton(this, "Ekspor PDF", "Simpan atau bagikan ke DOCX, PDF, TXT, atau Audio",
                UiKit.ACCENT_OFFICE, Color.WHITE, v -> showExportChooser());
        Button btnImgPreview = OfficeUtils.makeRowButton(this, "Lihat Gambar", "Lihat gambar asli halaman PDF atau cari kata",
                UiKit.ACCENT_WARM, Color.WHITE, v -> showQuickToolsChooser());
        actionRow.addView(btnEditMenu);
        actionRow.addView(btnLang);
        actionRow.addView(btnExportMenu);
        actionRow.addView(btnImgPreview);
        rootLayout.addView(actionRow);

        // Baris Navigasi Halaman (Geser Kiri/Kanan 1 Jari atau Ketuk Langsung Pilih Halaman)
        LinearLayout navCard = new LinearLayout(this);
        navCard.setOrientation(LinearLayout.HORIZONTAL);
        navCard.setGravity(Gravity.CENTER_VERTICAL);
        navCard.setBackground(UiKit.roundedBox(surface, border, UiKit.dp(this, 10), UiKit.dp(this, 1)));
        navCard.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 4), UiKit.dp(this, 6), UiKit.dp(this, 4));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = UiKit.dp(this, 6);
        navCard.setLayoutParams(nlp);

        pageBadge = new TextView(this);
        pageBadge.setText("Hal. 0 / 0 • Geser Kiri/Kanan atau Ketuk Pilih Halaman");
        pageBadge.setTextColor(txt);
        pageBadge.setTextSize(13);
        pageBadge.setTypeface(null, Typeface.BOLD);
        pageBadge.setGravity(Gravity.CENTER_VERTICAL);
        pageBadge.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 6), UiKit.dp(this, 8), UiKit.dp(this, 6));
        pageBadge.setContentDescription("Ketuk untuk memilih atau lompat langsung ke halaman yang dituju, atau geser satu jari ke kiri dan kanan");
        pageBadge.setOnClickListener(v -> showJumpPageDialog());

        Button btnPickPageDirect = OfficeUtils.makeChipButton(this, "Pilih Halaman", "Ketuk untuk memilih halaman yang dituju secara langsung", surfaceAlt, txt, v -> showJumpPageDialog());
        btnJump = OfficeUtils.makeChipButton(this, "Cari Teks", "Cari kata di seluruh halaman dokumen", surfaceAlt, txt, v -> showSearchInPdfDialog());

        navCard.addView(pageBadge, new LinearLayout.LayoutParams(0, -2, 1f));
        navCard.addView(btnPickPageDirect);
        navCard.addView(btnJump);
        rootLayout.addView(navCard);

        // ============== MODE 1: LAYAR PEMBACA PDF (ALA ENVISION AI) ==============
        readerContainer = new LinearLayout(this);
        readerContainer.setOrientation(LinearLayout.VERTICAL);

        readerScroll = new ScrollView(this);
        readerScroll.setFillViewport(true);
        readerScroll.setBackground(UiKit.roundedBox(surface, border, UiKit.dp(this, 12), UiKit.dp(this, 1)));
        LinearLayout.LayoutParams rslp = new LinearLayout.LayoutParams(-1, 0, 1f);
        rslp.topMargin = UiKit.dp(this, 6);

        readerTextView = new TextView(this);
        readerTextView.setTextColor(txt);
        readerTextView.setTextSize(17);
        readerTextView.setLineSpacing(UiKit.dp(this, 4), 1.2f);
        readerTextView.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 14), UiKit.dp(this, 16), UiKit.dp(this, 14));
        readerTextView.setTextIsSelectable(true);
        readerTextView.setFocusable(true);
        readerScroll.addView(readerTextView);
        readerContainer.addView(readerScroll, rslp);

        // Baris Kontrol Baca Novel TTS Berkelanjutan
        LinearLayout ttsBar = new LinearLayout(this);
        ttsBar.setOrientation(LinearLayout.HORIZONTAL);
        ttsBar.setGravity(Gravity.CENTER_VERTICAL);
        ttsBar.setBackground(UiKit.roundedBox(surfaceAlt, border, UiKit.dp(this, 10), UiKit.dp(this, 1)));
        ttsBar.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 4), UiKit.dp(this, 6), UiKit.dp(this, 4));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = UiKit.dp(this, 6);
        ttsBar.setLayoutParams(tlp);

        btnPlayNovel = OfficeUtils.makeRowButton(this, "Baca Novel TTS",
                "Bacakan suara terus menerus dari halaman ini hingga akhir dokumen",
                UiKit.ACCENT_OFFICE, Color.WHITE, v -> toggleNovelTts());
        btnStopNovel = OfficeUtils.makeChipButton(this, "Hentikan", "Hentikan pembacaan suara",
                UiKit.ACCENT_DANGER, Color.WHITE, v -> stopNovelTts());
        Button btnTtsSet = OfficeUtils.makeChipButton(this, "Atur Suara", "Pengaturan suara TTS dan kecepatan baca",
                surface, txt, v -> showTtsSettingsDialog());
        ttsBar.addView(btnPlayNovel);
        ttsBar.addView(btnStopNovel);
        ttsBar.addView(btnTtsSet);
        readerContainer.addView(ttsBar);

        rootLayout.addView(readerContainer, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ============== MODE 2: KOTAK EDIT CERDAS (TUNGGAL, AMAN RIBUAN HALAMAN) ==============
        smartEditContainer = new LinearLayout(this);
        smartEditContainer.setOrientation(LinearLayout.VERTICAL);
        smartEditContainer.setVisibility(View.GONE);

        smartEditHintLabel = new TextView(this);
        smartEditHintLabel.setTextColor(UiKit.isDark(uiPrefs) ? Color.parseColor("#93C5FD") : UiKit.ACCENT_AI);
        smartEditHintLabel.setTextSize(12);
        smartEditHintLabel.setTypeface(null, Typeface.BOLD);
        smartEditHintLabel.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 6), UiKit.dp(this, 4), UiKit.dp(this, 4));
        smartEditContainer.addView(smartEditHintLabel);

        // Baris navigasi khusus Mode Edit Seluruh PDF (Geser 1 jari kiri/kanan atau pilih halaman langsung)
        smartEditAllNavRow = new LinearLayout(this);
        smartEditAllNavRow.setOrientation(LinearLayout.HORIZONTAL);
        smartEditAllNavRow.setGravity(Gravity.CENTER_VERTICAL);
        Button btnSmartJumpPage = OfficeUtils.makeChipButton(this, "Pilih Halaman Edit",
                "Pilih halaman PDF yang ingin diedit secara langsung", surfaceAlt, txt, v -> showJumpPageDialog());
        btnSmartFindReplace = OfficeUtils.makeChipButton(this, "Cari & Ganti Semua",
                "Cari dan ganti teks di seluruh halaman PDF sekaligus", UiKit.ACCENT_WARM, Color.WHITE, v -> showGlobalFindReplaceDialog());
        smartEditAllNavRow.addView(btnSmartJumpPage);
        smartEditAllNavRow.addView(btnSmartFindReplace);
        smartEditContainer.addView(smartEditAllNavRow);

        ScrollView editScroll = new ScrollView(this);
        editScroll.setFillViewport(true);
        smartEditBox = new EditText(this);
        smartEditBox.setGravity(Gravity.TOP | Gravity.START);
        smartEditBox.setTextSize(16);
        smartEditBox.setTextColor(txt);
        smartEditBox.setHintTextColor(muted);
        smartEditBox.setHint("Teks halaman aktif siap diedit di sini...");
        smartEditBox.setBackground(UiKit.roundedBox(surface, border, UiKit.dp(this, 12), UiKit.dp(this, 1)));
        smartEditBox.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12), UiKit.dp(this, 14), UiKit.dp(this, 12));
        smartEditBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        smartEditBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!loadingEditorText) smartEditDirty = true;
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        editScroll.addView(smartEditBox, new ScrollView.LayoutParams(-1, -1));
        LinearLayout.LayoutParams eslp = new LinearLayout.LayoutParams(-1, 0, 1f);
        eslp.topMargin = UiKit.dp(this, 4);
        smartEditContainer.addView(editScroll, eslp);

        LinearLayout editBottomBar = new LinearLayout(this);
        editBottomBar.setOrientation(LinearLayout.HORIZONTAL);
        editBottomBar.setPadding(0, UiKit.dp(this, 6), 0, 0);
        btnSmartSaveOk = OfficeUtils.makeRowButton(this, "Simpan / OK",
                "Simpan hasil edit halaman dan pilih langkah selanjutnya",
                UiKit.ACCENT_OFFICE, Color.WHITE, v -> onSmartEditOkPressed());
        btnSmartCancel = OfficeUtils.makeRowButton(this, "Kembali ke Mode Baca",
                "Keluar dari kotak edit dan kembali ke layar pembaca PDF",
                UiKit.ACCENT_SYSTEM, Color.WHITE, v -> exitEditToReaderMode(false));
        editBottomBar.addView(btnSmartSaveOk);
        editBottomBar.addView(btnSmartCancel);
        smartEditContainer.addView(editBottomBar);

        rootLayout.addView(smartEditContainer, new LinearLayout.LayoutParams(-1, 0, 1f));

        statusLabel = new TextView(this);
        statusLabel.setText("Mode Pembaca PDF Aksesibel • Geser 1 jari kiri/kanan untuk pindah halaman.");
        statusLabel.setTextColor(muted);
        statusLabel.setTextSize(12);
        statusLabel.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 6), UiKit.dp(this, 4), UiKit.dp(this, 2));
        rootLayout.addView(statusLabel);

        setContentView(rootLayout);
    }

    /** Deteksi gestur geser 1 jari kiri/kanan di seluruh layar (Reader maupun Kotak Edit Cerdas). */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (ev.getPointerCount() == 1) {
            if (action == MotionEvent.ACTION_DOWN) {
                swipeStartX = ev.getX();
                swipeStartY = ev.getY();
                swipeTracking = true;
                swipeTriggered = false;
            } else if (action == MotionEvent.ACTION_MOVE && swipeTracking && !swipeTriggered) {
                float dx = ev.getX() - swipeStartX;
                float dy = ev.getY() - swipeStartY;
                float thresh = UiKit.dp(this, 65);
                if (Math.abs(dx) > thresh && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    swipeTriggered = true;
                    MotionEvent cancelEv = MotionEvent.obtain(ev);
                    cancelEv.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancelEv);
                    cancelEv.recycle();
                    hideSoftKeyboard();
                    if (dx < 0) stepPage(1); // geser kiri -> halaman berikutnya
                    else stepPage(-1);       // geser kanan -> halaman sebelumnya
                    return true;
                }
            }
        } else {
            swipeTracking = false;
            swipeTriggered = false;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            swipeTracking = false;
            swipeTriggered = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void hideSoftKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}
    }

    private void launchFilePicker() {
        OfficeUtils.launchFilePicker(this, REQ_OPEN_PDF, "pdf", "Buka PDF / Dokumen");
    }

    private void openDocumentUri(Uri uri) {
        stopNovelTts();
        if (docCache != null) {
            docCache.close();
            docCache = null;
        }
        setStatus("Membuka dokumen…");
        readerTextView.setText("Memuat halaman dokumen, mohon tunggu sebentar…");
        worker.submit(() -> {
            try {
                PdfDocumentCache cache = PdfDocumentCache.open(this, uri);
                docCache = cache;
                int bookmark = cache.getSavedBookmarkPage();
                mainHandler.post(() -> {
                    titleLabel.setText(cache.getDisplayName());
                    editMode = 0;
                    applyEditModeUi();
                    if (bookmark > 0) {
                        loadPage(0, false, false);
                        UiKit.dialog(this, uiPrefs)
                                .setTitle("Lanjutkan Bacaan?")
                                .setMessage("Terakhir kali kamu membaca di halaman " + (bookmark + 1) + " dari " + cache.getTotalPages() + ". Mau langsung lompat ke sana?")
                                .setNegativeButton("Mulai Hal. 1", null)
                                .setPositiveButton("Lanjut Hal. " + (bookmark + 1), (d, w) -> loadPage(bookmark, false, false))
                                .show();
                    } else {
                        loadPage(0, false, false);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setStatus("Gagal membuka berkas: " + e.getMessage());
                    UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE))
                            .setTitle("Gagal Membuka Dokumen")
                            .setMessage(e.getMessage() == null ? "Berkas tidak dapat dibaca." : e.getMessage())
                            .show();
                });
            }
        });
    }

    private void loadPage(final int pageIndex, final boolean forceOcr, final boolean startTtsAfter) {
        if (docCache == null) return;
        final int target = Math.max(0, Math.min(docCache.getTotalPages() - 1, pageIndex));
        if (editMode != 0 && smartEditDirty) {
            saveCurrentSmartEditSync();
        }
        currentPage = target;
        docCache.saveBookmarkPage(target);
        updateNavControls();

        final String lang = OcrEngineManager.getActiveLanguageCombo(this);
        final String loadingMsg = "Memuat halaman " + (target + 1) + " dari " + docCache.getTotalPages()
                + " (jika halaman bergambar, otomatis dipindai OCR bahasa " + OcrEngineManager.getLanguageDisplayName(lang) + ")…";
        setStatus(loadingMsg);
        if (editMode == 0) {
            readerTextView.setText(loadingMsg);
        }

        worker.submit(() -> {
            try {
                PdfDocumentCache.PageResult res = docCache.loadOrExtractPage(target, lang, forceOcr);
                mainHandler.post(() -> {
                    if (docCache == null || currentPage != target) return;
                    currentPageText = res.text;
                    currentPageTag = res.sourceTag;
                    smartEditDirty = false;

                    if (editMode == 0) {
                        String shown = res.text.trim().isEmpty()
                                ? "[Halaman " + (target + 1) + " kosong / tidak terbaca teksnya. Coba ubah Bahasa OCR di tombol kanan atas lalu pilih Pindai Ulang.]"
                                : res.text;
                        readerTextView.setText(shown);
                        readerScroll.scrollTo(0, 0);
                    } else {
                        loadingEditorText = true;
                        smartEditBox.setText(res.text);
                        smartEditBox.setSelection(0);
                        loadingEditorText = false;
                        smartEditDirty = false;
                    }
                    updateNavControls();
                    String info = "Halaman " + (target + 1) + " dari " + docCache.getTotalPages()
                            + " • Sumber: " + res.sourceTag
                            + " • OCR: " + OcrEngineManager.getLanguageDisplayName(lang);
                    setStatus(info);
                    OfficeUtils.announce(rootLayout, "Halaman " + (target + 1) + " dari " + docCache.getTotalPages());

                    if (startTtsAfter) {
                        beginSpeakCurrentPageText();
                    }
                });

                // Pre-OCR / Pre-fetch 2 halaman berikutnya di background agar pindah halaman & TTS Novel super mulus
                preFetchAround(target, lang);
            } catch (Exception e) {
                mainHandler.post(() -> setStatus("Gagal memuat halaman " + (target + 1) + ": " + e.getMessage()));
            }
        });
    }

    private void preFetchAround(final int pageIndex, final String lang) {
        final PdfDocumentCache ref = docCache;
        if (ref == null) return;
        preFetchWorker.submit(() -> {
            for (int next = pageIndex + 1; next <= Math.min(ref.getTotalPages() - 1, pageIndex + 2); next++) {
                try {
                    if (docCache != ref) return;
                    if (!ref.isPageCached(next)) {
                        ref.loadOrExtractPage(next, lang, false);
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private void stepPage(int delta) {
        if (docCache == null) return;
        int target = currentPage + delta;
        if (target < 0) {
            Toast.makeText(this, "Sudah berada di halaman pertama.", Toast.LENGTH_SHORT).show();
            OfficeUtils.announce(rootLayout, "Sudah di halaman pertama");
            return;
        }
        if (target >= docCache.getTotalPages()) {
            Toast.makeText(this, "Sudah berada di halaman terakhir.", Toast.LENGTH_SHORT).show();
            OfficeUtils.announce(rootLayout, "Sudah di halaman terakhir");
            return;
        }
        boolean wasTtsPlaying = ttsPlaying && editMode == 0;
        if (wasTtsPlaying) {
            ttsSessionId++;
            if (tts != null) try { tts.stop(); } catch (Throwable ignored) {}
        }
        loadPage(target, false, wasTtsPlaying);
    }

    private void updateNavControls() {
        int total = docCache == null ? 0 : docCache.getTotalPages();
        pageBadge.setText("Hal. " + (total == 0 ? 0 : (currentPage + 1)) + " / " + total + " • Geser Kiri/Kanan");
        if (btnPrev != null) btnPrev.setEnabled(docCache != null && currentPage > 0);
        if (btnNext != null) btnNext.setEnabled(docCache != null && currentPage + 1 < total);
        if (btnSmartPrev != null) btnSmartPrev.setEnabled(docCache != null && currentPage > 0);
        if (btnSmartNext != null) btnSmartNext.setEnabled(docCache != null && currentPage + 1 < total);
        metaLabel.setText((docCache == null ? "Belum ada berkas" : docCache.getTotalPages() + " halaman • " + currentPageTag)
                + " • OCR: " + OcrEngineManager.getActiveLanguageCombo(this));
    }

    // ==================== EDIT PER HALAMAN VS EDIT SELURUH PDF (KOTAK EDIT CERDAS) ====================

    private void showEditModeChooser() {
        if (docCache == null) {
            Toast.makeText(this, "Buka berkas PDF atau dokumen terlebih dahulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        stopNovelTts();
        String[] items = {
                "1. Edit Halaman Ini Saja (Halaman " + (currentPage + 1) + ")",
                "2. Edit Seluruh PDF — Kotak Edit Cerdas (Geser 1 jari / tombol antar semua halaman)",
                "3. Pindai Ulang Halaman Ini dengan OCR (" + OcrEngineManager.getActiveLanguageCombo(this) + ")",
                "4. Ekstrak Massal Semua Halaman ke Cache Disk (Persiapan Dokumen Besar)"
        };
        UiKit.dialog(this, uiPrefs)
                .setTitle("Pilih Mode Edit PDF")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        enterEditMode(1, currentPage);
                    } else if (which == 1) {
                        // Sesuai alur: klik Edit Seluruh PDF -> halaman 1 (atau aktif) langsung dijeplak ke kotak edit cerdas!
                        if (currentPage != 0) {
                            UiKit.dialog(this, uiPrefs)
                                    .setTitle("Mulai Edit Seluruh PDF")
                                    .setMessage("Mau mulai dari Halaman 1 atau dari Halaman " + (currentPage + 1) + " yang sedang dibaca?")
                                    .setPositiveButton("Mulai Halaman 1", (dd, ww) -> enterEditMode(2, 0))
                                    .setNeutralButton("Mulai Halaman " + (currentPage + 1), (dd, ww) -> enterEditMode(2, currentPage))
                                    .show();
                        } else {
                            enterEditMode(2, 0);
                        }
                    } else if (which == 2) {
                        loadPage(currentPage, true, false);
                    } else {
                        runBatchExtractAllPages();
                    }
                }).show();
    }

    private void enterEditMode(int mode, int startPage) {
        editMode = mode;
        applyEditModeUi();
        if (startPage != currentPage) {
            loadPage(startPage, false, false);
        } else {
            loadingEditorText = true;
            smartEditBox.setText(currentPageText);
            smartEditBox.setSelection(0);
            loadingEditorText = false;
            smartEditDirty = false;
            OfficeUtils.announce(smartEditBox, "Mode edit aktif pada halaman " + (currentPage + 1));
        }
    }

    private void applyEditModeUi() {
        if (editMode == 0) {
            readerContainer.setVisibility(View.VISIBLE);
            smartEditContainer.setVisibility(View.GONE);
            btnEditMenu.setText("Edit PDF");
        } else {
            readerContainer.setVisibility(View.GONE);
            smartEditContainer.setVisibility(View.VISIBLE);
            if (editMode == 1) {
                smartEditAllNavRow.setVisibility(View.GONE);
                smartEditHintLabel.setText("Edit Halaman " + (currentPage + 1) + " • Ubah teks di kotak ini lalu tekan Simpan / OK.");
                btnSmartSaveOk.setText("Simpan / OK");
            } else {
                smartEditAllNavRow.setVisibility(View.VISIBLE);
                smartEditHintLabel.setText("Kotak Edit Cerdas (Seluruh PDF) • Geser 1 jari kiri/kanan atau ketuk 'Pilih Halaman Edit' untuk pindah halaman (otomatis simpan!).");
                btnSmartSaveOk.setText("OK / Selesai Semua");
            }
        }
    }

    private void saveCurrentSmartEditSync() {
        if (docCache == null || smartEditBox == null) return;
        String edited = smartEditBox.getText() == null ? "" : smartEditBox.getText().toString();
        currentPageText = edited;
        currentPageTag = "Diedit";
        smartEditDirty = false;
        try {
            docCache.saveEditedPage(currentPage, edited);
        } catch (Exception e) {
            Toast.makeText(this, "Gagal menyimpan halaman: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void onSmartEditOkPressed() {
        saveCurrentSmartEditSync();
        hideSoftKeyboard();
        if (editMode == 1) {
            try {
                if (docCache != null) {
                    OfficeUtils.saveTextToBackupFolder(this, "Editor PDF",
                            docCache.getDisplayName().replaceAll("\\.[^.]+$", "") + "_hal_" + (currentPage + 1),
                            currentPageText);
                }
            } catch (Exception ignored) {}
            exitEditToReaderMode(true);
            Toast.makeText(this, "Perubahan halaman " + (currentPage + 1) + " tersimpan ke folder Editor PDF.", Toast.LENGTH_LONG).show();
        } else {
            UiKit.dialog(this, uiPrefs)
                    .setTitle("Edit Seluruh PDF Disimpan")
                    .setMessage("Semua perubahan halaman sudah tersimpan aman. Mau ekspor sekarang ke folder Editor PDF?")
                    .setPositiveButton("Ekspor DOCX / PDF / TXT", (d, w) -> showExportChooser())
                    .setNeutralButton("Kembali ke Mode Baca", (d, w) -> exitEditToReaderMode(true))
                    .setNegativeButton("Lanjut Edit", null)
                    .show();
        }
    }

    private void exitEditToReaderMode(boolean reloadText) {
        if (smartEditDirty) saveCurrentSmartEditSync();
        hideSoftKeyboard();
        editMode = 0;
        applyEditModeUi();
        if (reloadText) {
            readerTextView.setText(currentPageText.trim().isEmpty() ? "[Halaman kosong]" : currentPageText);
            updateNavControls();
            OfficeUtils.announce(rootLayout, "Kembali ke mode pembaca PDF halaman " + (currentPage + 1));
        }
    }

    // ==================== MODE READING TTS NOVEL BERKELANJUTAN ====================

    private void toggleNovelTts() {
        if (docCache == null) {
            Toast.makeText(this, "Buka berkas PDF terlebih dahulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ttsPlaying) {
            stopNovelTts();
            return;
        }
        initTtsIfNeeded(() -> {
            ttsPlaying = true;
            btnPlayNovel.setText("Jeda Baca");
            btnPlayNovel.setBackground(UiKit.roundedBox(UiKit.ACCENT_WARM, Color.TRANSPARENT, UiKit.dp(this, 12), 0));
            beginSpeakCurrentPageText();
        });
    }

    private void stopNovelTts() {
        ttsPlaying = false;
        ttsSessionId++;
        if (tts != null) {
            try { tts.stop(); } catch (Throwable ignored) {}
        }
        if (btnPlayNovel != null) {
            btnPlayNovel.setText("Baca Novel TTS");
            btnPlayNovel.setBackground(UiKit.roundedBox(UiKit.ACCENT_OFFICE, Color.TRANSPARENT, UiKit.dp(this, 12), 0));
        }
    }

    private void initTtsIfNeeded(final Runnable onReady) {
        if (tts != null && ttsReady) {
            onReady.run();
            return;
        }
        SharedPreferences prefs = OfficeUtils.mainPrefs(this);
        String engine = prefs.getString("document_tts_engine", null);
        if ("ANAS_AI_SUPERTONIC".equals(engine)) {
            engine = "";
            prefs.edit().putString("document_tts_engine", "").putString("document_tts_engine_name", "Default Sistem Android").apply();
        }
        TextToSpeech.OnInitListener listener = status -> {
            ttsReady = (status == TextToSpeech.SUCCESS);
            if (!ttsReady || tts == null) {
                Toast.makeText(this, "Mesin suara TTS tidak siap.", Toast.LENGTH_SHORT).show();
                return;
            }
            applyTtsSettings(prefs);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onError(String utteranceId) {
                    mainHandler.post(() -> handleUtteranceDone(utteranceId));
                }
                @Override public void onDone(String utteranceId) {
                    mainHandler.post(() -> handleUtteranceDone(utteranceId));
                }
            });
            onReady.run();
        };
        try {
            if (engine != null && !engine.trim().isEmpty()) {
                tts = new TextToSpeech(this, listener, engine.trim());
            } else {
                tts = new TextToSpeech(this, listener);
            }
        } catch (Throwable t) {
            tts = new TextToSpeech(this, listener);
        }
    }

    private void applyTtsSettings(SharedPreferences prefs) {
        if (tts == null) return;
        try {
            int r = tts.setLanguage(new Locale("id", "ID"));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault());
            }
        } catch (Throwable ignored) {}
        try { tts.setSpeechRate(prefs.getFloat("document_tts_rate", 1.0f)); } catch (Throwable ignored) {}
        try { tts.setPitch(prefs.getFloat("document_tts_pitch", 1.0f)); } catch (Throwable ignored) {}
    }

    private void beginSpeakCurrentPageText() {
        if (!ttsPlaying || tts == null || !ttsReady) return;
        ttsQueueChunks.clear();
        String normalized = HumanSpeechNormalizer.normalizeForDocumentTts(currentPageText);
        if (normalized.isEmpty()) {
            normalized = "Halaman " + (currentPage + 1) + " kosong.";
        }
        // Pecah per paragraf / max 320 karakter agar TTS stabil & responsif
        String[] paras = normalized.split("\\n+");
        for (String p : paras) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            while (t.length() > 320) {
                int cut = t.lastIndexOf('.', 320);
                if (cut < 120) cut = t.lastIndexOf(' ', 320);
                if (cut < 80) cut = 320;
                ttsQueueChunks.add(t.substring(0, cut).trim());
                t = t.substring(cut).trim();
            }
            if (!t.isEmpty()) ttsQueueChunks.add(t);
        }
        if (ttsQueueChunks.isEmpty()) ttsQueueChunks.add("Halaman " + (currentPage + 1) + " kosong.");
        ttsChunkIdx = 0;
        ttsSessionId++;
        speakNextChunk();
    }

    private void speakNextChunk() {
        if (!ttsPlaying || tts == null || !ttsReady) return;
        if (ttsChunkIdx >= ttsQueueChunks.size()) {
            // Selesai halaman ini -> otomatis lanjut ke halaman berikutnya kayak baca novel panjang!
            if (docCache != null && currentPage + 1 < docCache.getTotalPages()) {
                setStatus("Selesai halaman " + (currentPage + 1) + ", lanjut ke halaman " + (currentPage + 2) + "…");
                loadPage(currentPage + 1, false, true);
            } else {
                stopNovelTts();
                setStatus("Pembacaan seluruh dokumen selesai.");
                OfficeUtils.announce(rootLayout, "Pembacaan dokumen selesai.");
            }
            return;
        }
        String chunk = ttsQueueChunks.get(ttsChunkIdx);
        String uttId = ttsSessionId + "_" + ttsChunkIdx;
        tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, uttId);
    }

    private void handleUtteranceDone(String utteranceId) {
        if (!ttsPlaying || utteranceId == null) return;
        String expectedPrefix = ttsSessionId + "_";
        if (!utteranceId.startsWith(expectedPrefix)) return;
        ttsChunkIdx++;
        speakNextChunk();
    }

    private void showTtsSettingsDialog() {
        SharedPreferences prefs = OfficeUtils.mainPrefs(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 12), UiKit.dp(this, 16), UiKit.dp(this, 8));

        TextView rateLbl = new TextView(this);
        float curRate = prefs.getFloat("document_tts_rate", 1.0f);
        rateLbl.setText(String.format(Locale.US, "Kecepatan Baca: %.2fx", curRate));
        box.addView(rateLbl);

        android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(20); // 0.5x .. 2.5x
        seek.setProgress(Math.max(0, Math.min(20, Math.round((curRate - 0.5f) * 10f))));
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int prog, boolean fromUser) {
                float r = 0.5f + (prog / 10f);
                rateLbl.setText(String.format(Locale.US, "Kecepatan Baca: %.2fx", r));
                prefs.edit().putFloat("document_tts_rate", r).apply();
                if (tts != null) tts.setSpeechRate(r);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        box.addView(seek);

        Button pickEngine = UiKit.makeMenuButton(this, "Pilih Mesin Suara TTS",
                "Saat ini: " + prefs.getString("document_tts_engine_name", "Default Android"),
                UiKit.ACCENT_AI, prefs, v -> {
                    java.util.List<TextToSpeech.EngineInfo> avail = BrailleTTSManager.getAllAvailableEngines(this);
                    ArrayList<String> labels = new ArrayList<>();
                    ArrayList<String> pkgs = new ArrayList<>();
                    labels.add("Default Sistem Android"); pkgs.add("");
                    if (avail != null) for (TextToSpeech.EngineInfo ei : avail) {
                        if (ei == null || ei.name == null) continue;
                        labels.add(ei.label + " (" + ei.name + ")");
                        pkgs.add(ei.name);
                    }
                    UiKit.dialog(this, uiPrefs).setTitle("Mesin TTS Pembaca")
                            .setItems(labels.toArray(new String[0]), (d, w) -> {
                                prefs.edit()
                                        .putString("document_tts_engine", pkgs.get(w))
                                        .putString("document_tts_engine_name", labels.get(w))
                                        .apply();
                                stopNovelTts();
                                if (tts != null) { try { tts.shutdown(); } catch (Throwable ignored) {} tts = null; ttsReady = false; }
                                Toast.makeText(this, "Mesin suara diganti ke " + labels.get(w), Toast.LENGTH_SHORT).show();
                            }).show();
                });
        box.addView(pickEngine);

        UiKit.dialog(this, prefs)
                .setTitle("Pengaturan Suara Baca Novel")
                .setView(box)
                .show();
    }

    // ==================== DIALOG NAVIGASI, CARI, EKSPOR & BAHASA ====================

    private void showJumpPageDialog() {
        if (docCache == null) return;
        final int total = docCache.getTotalPages();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 8), UiKit.dp(this, 16), UiKit.dp(this, 4));

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("Ketik nomor halaman (1 – " + total + ")");
        input.setText(String.valueOf(currentPage + 1));
        input.selectAll();
        box.addView(input);

        UiKit.dialog(this, uiPrefs)
                .setTitle("Pilih / Lompat Halaman (1 – " + total + ")")
                .setView(box)
                .setNegativeButton("Batal", null)
                .setNeutralButton("Pilih dari Daftar Halaman", (d, w) -> {
                    int maxList = Math.min(total, 300);
                    String[] pageItems = new String[maxList];
                    for (int i = 0; i < maxList; i++) {
                        pageItems[i] = "Halaman " + (i + 1) + (i == currentPage ? " (Sedang Dibuka)" : "");
                    }
                    UiKit.dialog(this, uiPrefs)
                            .setTitle("Ketuk Halaman yang Dituju")
                            .setItems(pageItems, (d2, which) -> loadPage(which, false, false))
                            .show();
                })
                .setPositiveButton("Buka Halaman", (d, w) -> {
                    try {
                        int p = Integer.parseInt(input.getText().toString().trim());
                        if (p < 1 || p > total) {
                            Toast.makeText(this, "Nomor halaman di luar batas 1–" + total, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        loadPage(p - 1, false, false);
                    } catch (Exception ignored) {}
                }).show();
    }

    private void showSearchInPdfDialog() {
        if (docCache == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 8), UiKit.dp(this, 16), UiKit.dp(this, 4));
        EditText qIn = new EditText(this);
        qIn.setHint("Kata atau kalimat yang dicari");
        box.addView(qIn);
        CheckBox matchCase = new CheckBox(this);
        matchCase.setText("Peka huruf besar/kecil");
        box.addView(matchCase);
        UiKit.dialog(this, uiPrefs)
                .setTitle("Cari di Dokumen (" + docCache.getTotalPages() + " Hal.)")
                .setView(box)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Cari Berikutnya", (d, w) -> {
                    String q = qIn.getText().toString();
                    if (q.isEmpty()) return;
                    setStatus("Mencari \"" + q + "\"…");
                    worker.submit(() -> {
                        try {
                            int hit = docCache.findNextPageContaining(currentPage + 1, q, matchCase.isChecked(),
                                    OcrEngineManager.getActiveLanguageCombo(this));
                            mainHandler.post(() -> {
                                if (hit >= 0) {
                                    loadPage(hit, false, false);
                                    Toast.makeText(this, "Ditemukan di halaman " + (hit + 1), Toast.LENGTH_SHORT).show();
                                } else {
                                    setStatus("Kata \"" + q + "\" tidak ditemukan pada halaman yang sudah terbaca.");
                                    Toast.makeText(this, "Tidak ditemukan. Jika PDF hasil scan, jalankan Ekstrak Massal dulu agar semua halaman bisa dicari.", Toast.LENGTH_LONG).show();
                                }
                            });
                        } catch (Exception e) {
                            mainHandler.post(() -> setStatus("Pencarian gagal: " + e.getMessage()));
                        }
                    });
                }).show();
    }

    private void showGlobalFindReplaceDialog() {
        if (docCache == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 8), UiKit.dp(this, 16), UiKit.dp(this, 4));
        EditText findIn = new EditText(this); findIn.setHint("Teks yang dicari di semua halaman"); box.addView(findIn);
        EditText repIn = new EditText(this); repIn.setHint("Ganti dengan"); box.addView(repIn);
        CheckBox mc = new CheckBox(this); mc.setText("Peka huruf besar/kecil"); box.addView(mc);
        UiKit.dialog(this, uiPrefs)
                .setTitle("Cari & Ganti Semua (" + docCache.getTotalPages() + " Hal.)")
                .setView(box)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Ganti Semua", (d, w) -> {
                    String f = findIn.getText().toString();
                    String r = repIn.getText().toString();
                    if (f.isEmpty()) return;
                    saveCurrentSmartEditSync();
                    final boolean[] cancel = new boolean[]{false};
                    AlertDialog prog = UiKit.dialog(this, uiPrefs)
                            .setTitle("Memproses Semua Halaman…")
                            .setMessage("Mengganti \"" + f + "\" secara aman di disk…")
                            .setNegativeButton("Batalkan", (dd, ww) -> cancel[0] = true)
                            .setCancelable(false)
                            .show();
                    worker.submit(() -> {
                        try {
                            int count = docCache.globalReplaceAllPages(f, r, mc.isChecked(),
                                    OcrEngineManager.getActiveLanguageCombo(this), cancel,
                                    (done, tot, det) -> mainHandler.post(() -> prog.setMessage(det)));
                            mainHandler.post(() -> {
                                prog.dismiss();
                                loadPage(currentPage, false, false);
                                Toast.makeText(this, "Selesai mengganti " + count + " kecocokan di seluruh dokumen!", Toast.LENGTH_LONG).show();
                            });
                        } catch (Exception e) {
                            mainHandler.post(() -> {
                                prog.dismiss();
                                Toast.makeText(this, "Gagal: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            });
                        }
                    });
                }).show();
    }

    private void runBatchExtractAllPages() {
        if (docCache == null) return;
        final boolean[] cancel = new boolean[]{false};
        AlertDialog prog = UiKit.dialog(this, uiPrefs)
                .setTitle("Ekstrak Massal PDF (Streaming Aman)")
                .setMessage("Menyiapkan halaman 1 dari " + docCache.getTotalPages() + "…")
                .setNegativeButton("Hentikan", (d, w) -> cancel[0] = true)
                .setCancelable(false)
                .show();
        final String lang = OcrEngineManager.getActiveLanguageCombo(this);
        worker.submit(() -> {
            try {
                docCache.ensureRangeExtractedToDisk(0, docCache.getTotalPages() - 1, lang, false, cancel, (done, tot, det) ->
                        mainHandler.post(() -> {
                            prog.setMessage("Mengekstrak " + det + " (" + (done * 100 / Math.max(1, tot)) + "%)");
                        }));
                mainHandler.post(() -> {
                    prog.dismiss();
                    loadPage(currentPage, false, false);
                    String msg = cancel[0] ? "Ekstraksi dihentikan sebagian. Halaman yang sudah selesai tetap tersimpan."
                            : "Seluruh " + docCache.getTotalPages() + " halaman berhasil diekstrak ke cache disk!";
                    setStatus(msg);
                    OfficeUtils.announce(rootLayout, msg);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    prog.dismiss();
                    Toast.makeText(this, "Error ekstrak massal: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showOcrToolsChooser() {
        String[] items = {
                "Pilih Bahasa OCR Aktif (Bisa Multi-Bahasa, misal ID + EN + Arab)",
                "Download Paket Bahasa Tambahan (Tampilan GitHub 100+ Bahasa)",
                "Muat Berkas Bahasa (.traineddata) Manual dari Penyimpanan HP",
                "Pindai Ulang Halaman Ini dengan OCR Sekarang"
        };
        UiKit.dialog(this, uiPrefs)
                .setTitle("Bahasa & Mesin OCR Offline")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        OcrEngineManager.showActiveLanguagePickerDialog(this, () -> {
                            updateNavControls();
                            if (docCache != null) loadPage(currentPage, true, false);
                        });
                    } else if (which == 1) {
                        OcrEngineManager.showLanguageDownloaderDialog(this, this::launchManualLanguagePicker, this::updateNavControls);
                    } else if (which == 2) {
                        launchManualLanguagePicker();
                    } else if (docCache != null) {
                        loadPage(currentPage, true, false);
                    }
                }).show();
    }

    private void launchManualLanguagePicker() {
        OfficeUtils.launchFilePicker(this, REQ_MANUAL_LANG, "all", "Pilih Berkas Bahasa OCR (.traineddata)");
    }

    private void showQuickToolsChooser() {
        String[] items = {
                "Lihat Gambar Asli Halaman " + (currentPage + 1),
                "Lompat ke Nomor Halaman",
                "Cari Teks di Dokumen",
                "Statistik Halaman Aktif"
        };
        UiKit.dialog(this, uiPrefs)
                .setTitle("Alat Tambahan")
                .setItems(items, (d, w) -> {
                    if (w == 0) showOriginalPageBitmapDialog();
                    else if (w == 1) showJumpPageDialog();
                    else if (w == 2) showSearchInPdfDialog();
                    else {
                        int chars = currentPageText.length();
                        int words = currentPageText.trim().isEmpty() ? 0 : currentPageText.trim().split("\\s+").length;
                        UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE)).setTitle("Info Halaman " + (currentPage + 1))
                                .setMessage("Jumlah kata: " + words + "\nJumlah karakter: " + chars + "\nSumber: " + currentPageTag)
                                .show();
                    }
                }).show();
    }

    private void showOriginalPageBitmapDialog() {
        if (docCache == null || !docCache.isPdf()) {
            Toast.makeText(this, "Pratinjau gambar hanya tersedia untuk berkas PDF.", Toast.LENGTH_SHORT).show();
            return;
        }
        worker.submit(() -> {
            try {
                Bitmap bmp = docCache.renderPagePreviewBitmap(currentPage, 1080);
                mainHandler.post(() -> {
                    if (bmp == null) return;
                    ImageView iv = new ImageView(this);
                    iv.setImageBitmap(bmp);
                    iv.setAdjustViewBounds(true);
                    ScrollView sc = new ScrollView(this);
                    sc.addView(iv);
                    UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE))
                            .setTitle("Gambar Asli Halaman " + (currentPage + 1))
                            .setView(sc)
                            .setOnDismissListener(d -> { if (!bmp.isRecycled()) bmp.recycle(); })
                            .show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this, "Gagal merender gambar: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showExportChooser() {
        if (docCache == null) {
            Toast.makeText(this, "Belum ada dokumen untuk diekspor.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (editMode != 0 && smartEditDirty) saveCurrentSmartEditSync();
        String[] opts = {
                "Simpan / Ekspor sebagai DOCX (Microsoft Word)",
                "Simpan / Ekspor sebagai PDF Teks Rapi",
                "Simpan / Ekspor sebagai TXT (Teks Polos)",
                "Bagikan Cepat sebagai DOCX (WhatsApp / Email)",
                "Bagikan Cepat sebagai TXT (WhatsApp / Email)"
        };
        UiKit.dialog(this, uiPrefs)
                .setTitle("Simpan & Bagikan Dokumen")
                .setItems(opts, (d, which) -> {
                    if (which == 0) startCreateDocument("docx", ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                    else if (which == 1) startCreateDocument("pdf", ".pdf", "application/pdf");
                    else if (which == 2) startCreateDocument("txt", ".txt", "text/plain");
                    else if (which == 3) quickShareExport("docx", ".docx");
                    else quickShareExport("txt", ".txt");
                }).show();
    }

    private void startCreateDocument(String type, String ext, String mime) {
        pendingExportType = type;
        String base = docCache.getDisplayName().replaceAll("\\.[^.]+$", "");
        OfficeUtils.launchFileSaver(this, REQ_EXPORT_FILE, base + "_olah" + ext, "Simpan Hasil Editor PDF", "Editor PDF");
    }

    private void quickShareExport(String type, String ext) {
        if (docCache == null) return;
        final boolean[] cancel = new boolean[]{false};
        AlertDialog prog = UiKit.dialog(this, uiPrefs)
                .setTitle("Menyiapkan Berkas…")
                .setMessage("Menggabungkan halaman secara streaming…")
                .setNegativeButton("Batalkan", (d, w) -> cancel[0] = true)
                .setCancelable(false)
                .show();
        String base = docCache.getDisplayName().replaceAll("\\.[^.]+$", "").replaceAll("[\\\\/:*?\"<>|]", "_");
        File dir = new File(getCacheDir(), "shared_exports");
        if (!dir.exists()) dir.mkdirs();
        File outFile = new File(dir, base + "_Anas" + ext);
        String lang = OcrEngineManager.getActiveLanguageCombo(this);
        worker.submit(() -> {
            try (FileOutputStream fos = new FileOutputStream(outFile, false)) {
                docCache.exportStreaming(fos, type, lang, cancel, (done, tot, det) ->
                        mainHandler.post(() -> prog.setMessage(det)));
                mainHandler.post(() -> {
                    prog.dismiss();
                    if (!cancel[0]) OfficeUtils.shareLocalFile(this, outFile, outFile.getName());
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    prog.dismiss();
                    Toast.makeText(this, "Gagal menyiapkan ekspor: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_OPEN_PDF) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {}
            openDocumentUri(uri);
        } else if (requestCode == REQ_MANUAL_LANG) {
            worker.submit(() -> {
                try {
                    String code = OcrEngineManager.importTrainedDataFromUri(this, uri);
                    mainHandler.post(() -> {
                        OcrEngineManager.setActiveLanguageCombo(this, code);
                        updateNavControls();
                        Toast.makeText(this, "Bahasa OCR manual \"" + code + "\" berhasil dimuat & diaktifkan!", Toast.LENGTH_LONG).show();
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE)).setTitle("Gagal Memuat Bahasa")
                            .setMessage(e.getMessage()).show());
                }
            });
        } else if (requestCode == REQ_EXPORT_FILE && docCache != null) {
            final boolean[] cancel = new boolean[]{false};
            AlertDialog prog = UiKit.dialog(this, uiPrefs)
                    .setTitle("Menyimpan Dokumen…")
                    .setMessage("Menulis berkas ke lokasi tujuan…")
                    .setNegativeButton("Batalkan", (d, w) -> cancel[0] = true)
                    .setCancelable(false)
                    .show();
            String lang = OcrEngineManager.getActiveLanguageCombo(this);
            worker.submit(() -> {
                try (OutputStream out = OfficeUtils.openWrite(this, uri)) {
                    docCache.exportStreaming(out, pendingExportType, lang, cancel, (done, tot, det) ->
                            mainHandler.post(() -> prog.setMessage(det)));
                    mainHandler.post(() -> {
                        prog.dismiss();
                        if (!cancel[0]) {
                            setStatus("Berhasil diekspor!");
                            UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE)).setTitle("Ekspor Selesai")
                                    .setMessage("Dokumen berhasil disimpan.")
                                    .setNeutralButton("Bagikan", (d, w) -> OfficeUtils.shareContentUri(this, uri, "Dokumen." + pendingExportType))
                                    .show();
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        prog.dismiss();
                        Toast.makeText(this, "Gagal menyimpan: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        }
    }

    private void setStatus(String s) {
        if (statusLabel != null) statusLabel.setText(s);
    }

    @Override
    protected void onDestroy() {
        stopNovelTts();
        if (tts != null) { try { tts.shutdown(); } catch (Throwable ignored) {} }
        if (docCache != null) docCache.close();
        worker.shutdownNow();
        preFetchWorker.shutdownNow();
        super.onDestroy();
    }
}
