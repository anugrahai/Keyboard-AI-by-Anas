package com.annas;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.Html;
import android.text.Spannable;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.SubscriptSpan;
import android.text.style.TypefaceSpan;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Accessible document workspace: rich HTML editing, common document import/export, and native PDF text extraction. */
public class DocumentStudioActivity extends Activity {
    private TextToSpeech readingTts;
    private boolean readingTtsReady = false;
    private int readingCursor = 0;
    private String readingText = "";
    private boolean readingPaused = true;
    private long readingSessionId = 0L;
    private static final int REQ_OPEN = 8101;
    private static final int REQ_SAVE = 8102;
    private static final int REQ_IMAGE = 8103;
    private static final int REQ_FILE_MANAGER = 8104;
    private static final int REQ_STORAGE_PERMISSION = 8105;
    private static final int REQ_OCR_IMAGE = 8107;
    private static final int REQ_MANUAL_OCR_LANG = 8108;
    private WebView editor;
    private EditText wordEdit;
    private TextView wordPageCounter;
    private boolean updatingWordEdit = false;
    private boolean wordEditDirty = false;
    private TextView status;
    private Spinner fontSpinner;
    private Spinner sizeSpinner;
    private String currentName = "Dokumen_Baru";
    private String selectedFont = "Liberation Serif";
    private int selectedSize = 12;
    private Uri pendingSaveUri;
    private File pendingSaveDirectory;
    private String pendingSaveType = "html";
    private String cachedHtml = "<p></p>";
    private String cachedText = "";
    private Uri lastPdfUri;
    private int activePdfPage = 0;
    private int activePdfPageCount = 0;
    private android.widget.ImageView pdfPageImage;
    private TextView pdfPageCounter;
    // PDF menggunakan kotak edit Word utama; teks tiap halaman disimpan terpisah agar perpindahan aman.
    private ArrayList<String> pdfPageTexts = new ArrayList<>();
    private Spinner pdfPageSpinner;
    private LinearLayout pdfPageNavRow;
    private Button pdfPagePrevButton, pdfPageNextButton;
    private TextView pdfPageNavLabel;
    private boolean pdfDocumentActive = false;
    private int activePdfTextPage = 0;
    private boolean changingPdfPage = false;
    private float docSwipeStartX = 0f, docSwipeStartY = 0f;
    private boolean docSwipeTracking = false, docSwipeTriggered = false;
    // Mode halaman buku / naskah panjang: tiap halaman punya isi sendiri di kotak edit utama.
    private ArrayList<String> documentPages = new ArrayList<>();
    private int activeDocumentPage = 0;
    private boolean documentPagedMode = true; // default aktif biar bisa nulis buku per halaman
    private boolean changingDocPage = false;
    private LinearLayout docPageNavRow;
    private Spinner docPageSpinner;
    private Button docPagePrevButton, docPageNextButton, docPageAddButton;
    private boolean nativeTextEditorActive = false;
    private final ArrayList<CharSequence> wordUndoStack = new ArrayList<>();
    private final ArrayList<CharSequence> wordRedoStack = new ArrayList<>();
    private boolean applyingWordHistory = false;
    private long lastWordUndoSnapshotMs = 0L;
    private static final int NATIVE_EDIT_CHUNK_CHARS = 5000;
    private ArrayList<String> nativeDocumentChunks = new ArrayList<>();
    private int activeNativeDocumentChunk = 0;
    private EditText nativeDocumentEdit;
    private TextView nativeDocumentChunkLabel;
    private Button nativeDocumentPrev, nativeDocumentNext;
    private String nativeDocumentTitle = "Dokumen";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.applySystemBars(this, OfficeUtils.mainPrefs(this));
        PDFBoxResourceLoader.init(getApplicationContext());
        android.content.SharedPreferences docPrefs = getSharedPreferences("document_studio", MODE_PRIVATE);
        selectedFont = docPrefs.getString("font", "Liberation Serif");
        selectedSize = docPrefs.getInt("font_size", 12);
        buildUi();
        loadHtml(cachedHtml);
        restoreMostRecentDraft();
        if (wordEdit != null) wordEdit.post(() -> updateWordPageEstimate());
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private void buildUi() {
        android.content.SharedPreferences mainUiPrefs = OfficeUtils.mainPrefs(this);
        boolean darkUi = UiKit.isDark(mainUiPrefs);
        int bg = UiKit.bg(mainUiPrefs);
        int surface = UiKit.surface(mainUiPrefs);
        int altBg = UiKit.surfaceAlt(mainUiPrefs);
        int txt = UiKit.textPrimary(mainUiPrefs);
        int muted = UiKit.textMuted(mainUiPrefs);
        int border = UiKit.border(mainUiPrefs);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));

        // Header Ringkas + Tombol Opsi Lainnya (Panduan, Download Bahasa OCR, Lisensi)
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dp(2), dp(2), dp(2), dp(6));

        TextView heading = new TextView(this);
        heading.setText("Word & Dokumen — Anas");
        heading.setTextColor(txt);
        heading.setTextSize(18);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setContentDescription("Microsoft Word Pro, editor dokumen lengkap dan ramah pembaca layar");
        headerRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));

        headerRow.addView(OfficeUtils.makeChipButton(this, "Baca PDF", "Buka pembaca PDF Envision dengan OCR offline 15 bahasa",
                UiKit.ACCENT_KB_ALT, Color.WHITE, v -> startActivity(new Intent(this, PdfReaderActivity.class))));
        headerRow.addView(OfficeUtils.makeChipButton(this, "Opsi Lainnya", "Panduan lengkap, download paket bahasa OCR, dan lisensi resmi",
                UiKit.ACCENT_AI_ALT, Color.WHITE, v -> showMoreOptionsMenu()));
        root.addView(headerRow);

        // Baris 1: 4 Grup Utama yang Rapi, Jelas & Mudah Dipahami Tanpa Baca Panduan
        LinearLayout fileRow = new LinearLayout(this);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);
        fileRow.addView(OfficeUtils.makeRowButton(this, "Berkas", "Buka berkas, pembaca PDF OCR, editor kode, gabung, atau bagikan",
                UiKit.ACCENT_OFFICE, Color.WHITE, v -> showBerkasGroupMenu()));
        fileRow.addView(OfficeUtils.makeRowButton(this, "Format", "Huruf tebal, miring, gaya judul, perataan, dan warna",
                UiKit.ACCENT_AI, Color.WHITE, v -> showFormatGroupMenu()));
        fileRow.addView(OfficeUtils.makeRowButton(this, "Template & AI", "Template siap pakai, Asisten AI (Premium), kop surat, gambar & tabel",
                UiKit.ACCENT_KB, Color.WHITE, v -> showInsertAndTemplateMenu()));
        fileRow.addView(OfficeUtils.makeRowButton(this, "Halaman & Baca", "Navigasi bab, kelola halaman buku, cari ganti, dan pengaturan kertas",
                UiKit.ACCENT_WARM, Color.WHITE, v -> showNavigationAndReadMenu()));
        root.addView(fileRow);

        // Baris Aksi Cepat Ringkas (Scroll Horizontal agar tidak sesak di layar kecil)
        HorizontalScrollView quickScroll = new HorizontalScrollView(this);
        quickScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout quickStrip = new LinearLayout(this);
        quickStrip.setOrientation(LinearLayout.HORIZONTAL);
        quickStrip.setGravity(Gravity.CENTER_VERTICAL);
        quickStrip.setPadding(0, dp(6), 0, dp(4));
        quickStrip.addView(OfficeUtils.makeChipButton(this, "↶ Undo", "Batalkan perubahan terakhir", altBg, txt, v -> undoWordEdit()));
        quickStrip.addView(OfficeUtils.makeChipButton(this, "↷ Redo", "Ulangi perubahan yang dibatalkan", altBg, txt, v -> redoWordEdit()));
        quickStrip.addView(OfficeUtils.makeChipButton(this, "B/I/U Format", "Tampilkan atau sembunyikan bilah format teks cepat",
                altBg, txt, v -> toggleQuickToolbar()));
        quickStrip.addView(OfficeUtils.makeChipButton(this, "OCR Foto", "Pindai teks dari foto atau screenshot ke dalam dokumen",
                UiKit.ACCENT_KB, Color.WHITE, v -> launchOcrImagePicker()));
        quickStrip.addView(OfficeUtils.makeChipButton(this, "Asisten AI", "Buat, rapikan, ringkas, atau terjemahkan dokumen dengan AI (Premium)",
                UiKit.ACCENT_AI_ALT, Color.WHITE, v -> openWordAiAssistant()));
        quickStrip.addView(OfficeUtils.makeChipButton(this, "Bilah Baca", "Tampilkan bilah navigasi paragraf dan baca suara aksesibel",
                UiKit.ACCENT_AI, Color.WHITE, v -> toggleAccessibleReadingBar()));
        quickStrip.addView(OfficeUtils.makeChipButton(this, "Simpan Dokumen", "Simpan atau ekspor ke DOCX, PDF, HTML, ODT",
                UiKit.ACCENT_OFFICE, Color.WHITE, v -> showSaveOptions()));
        quickScroll.addView(quickStrip);
        root.addView(quickScroll);

        // Bilah Baca Aksesibel (Tersembunyi default agar layar bersih; aktif saat dibuka)
        accessibleReadingBar = new LinearLayout(this);
        accessibleReadingBar.setOrientation(LinearLayout.HORIZONTAL);
        accessibleReadingBar.setGravity(Gravity.CENTER_VERTICAL);
        accessibleReadingBar.setVisibility(View.GONE);
        accessibleReadingBar.setBackground(UiKit.roundedBox(UiKit.surface(mainUiPrefs), UiKit.border(mainUiPrefs), dp(10), dp(1)));
        accessibleReadingBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        accessibleReadingBar.addView(OfficeUtils.makeChipButton(this, "Paragraf Sebelumnya", "Lompat dan bacakan paragraf sebelumnya", altBg, txt, v -> jumpParagraphAccessible(-1)));
        accessibleReadingBar.addView(OfficeUtils.makeChipButton(this, "Bacakan Teks", "Bacakan dari posisi kursor", UiKit.ACCENT_OFFICE, Color.WHITE, v -> speakFromCursorAccessible()));
        accessibleReadingBar.addView(OfficeUtils.makeChipButton(this, "Hentikan Baca", "Hentikan suara baca", UiKit.ACCENT_DANGER, Color.WHITE, v -> stopAccessibleTts()));
        accessibleReadingBar.addView(OfficeUtils.makeChipButton(this, "Paragraf Berikutnya", "Lompat dan bacakan paragraf berikutnya", altBg, txt, v -> jumpParagraphAccessible(1)));
        accessibleReadingBar.addView(OfficeUtils.makeChipButton(this, "Bab/Judul", "Lompat ke daftar judul bab dokumen", UiKit.ACCENT_AI, Color.WHITE, v -> showNavigationOutline()));
        accessibleReadingBar.addView(OfficeUtils.makeChipButton(this, "Info", "Umumkan posisi halaman, paragraf, dan gaya kursor", altBg, txt, v -> showCursorFormattingInspector()));
        HorizontalScrollView readBarScroll = new HorizontalScrollView(this);
        readBarScroll.setHorizontalScrollBarEnabled(false);
        readBarScroll.addView(accessibleReadingBar);
        root.addView(readBarScroll);

        collapsibleToolbarScroll = new HorizontalScrollView(this);
        HorizontalScrollView toolbarScroll = collapsibleToolbarScroll;
        toolbarScroll.setHorizontalScrollBarEnabled(true);
        toolbarScroll.setVisibility(View.GONE);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(0, dp(4), 0, dp(4));

        fontSpinner = new Spinner(this);
        String[] fonts = {"Times New Roman*", "Arial", "Calibri", "Aptos", "Georgia", "Verdana", "Tahoma", "Courier New", "Liberation Serif", "Liberation Sans", "Liberation Mono", "DejaVu Sans", "DejaVu Sans Mono", "Serif", "Sans Serif", "Monospace"};
        fontSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, fonts));
        fontSpinner.setContentDescription("Pilih font dokumen");
        int savedFontIndex = 0;
        for (int i = 0; i < fonts.length; i++) {
            String candidate = "Times New Roman*".equals(fonts[i]) ? "Liberation Serif" : fonts[i];
            if (candidate.equals(selectedFont)) { savedFontIndex = i; break; }
        }
        fontSpinner.setSelection(savedFontIndex);
        fontSpinner.setMinimumWidth(dp(160));
        fontSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
            String label = fonts[position];
            selectedFont = "Times New Roman*".equals(label) ? "Liberation Serif" : label;
            getSharedPreferences("document_studio", MODE_PRIVATE).edit().putString("font", selectedFont).apply();
            if (wordEdit != null) wordEdit.setTypeface(Typeface.create(cssFontFamily(), Typeface.NORMAL));
            if (wordEdit != null && wordEdit.getSelectionStart() != wordEdit.getSelectionEnd()) applyNativeWordCommand("fontName", selectedFont);
        }));
        toolbar.addView(fontSpinner);

        sizeSpinner = new Spinner(this);
        String[] sizes = {"8", "9", "10", "11", "12", "14", "16", "18", "20", "24", "28", "32", "36", "48", "72"};
        sizeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, sizes));
        int savedSizeIndex = 4;
        for (int i = 0; i < sizes.length; i++) if (Integer.parseInt(sizes[i]) == selectedSize) savedSizeIndex = i;
        sizeSpinner.setSelection(savedSizeIndex);
        sizeSpinner.setContentDescription("Ukuran huruf");
        sizeSpinner.setMinimumWidth(dp(80));
        sizeSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
            try { selectedSize = Integer.parseInt(sizes[position]); getSharedPreferences("document_studio", MODE_PRIVATE).edit().putInt("font_size", selectedSize).apply(); if (wordEdit != null) wordEdit.setTextSize(selectedSize); if (wordEdit != null && wordEdit.getSelectionStart() != wordEdit.getSelectionEnd()) applyNativeWordCommand("fontSize", String.valueOf(selectedSize)); } catch (Exception ignored) {}
        }));
        toolbar.addView(sizeSpinner);
        addButton(toolbar, "Tebal", v -> applyNativeWordCommand("bold", null), 0);
        addButton(toolbar, "Miring", v -> applyNativeWordCommand("italic", null), 0);
        addButton(toolbar, "Garis bawah", v -> applyNativeWordCommand("underline", null), 0);
        addButton(toolbar, "Coret", v -> applyNativeWordCommand("strikeThrough", null), 0);
        addButton(toolbar, "Rata kiri", v -> applyNativeWordCommand("justifyLeft", null), 0);
        addButton(toolbar, "Tengah", v -> applyNativeWordCommand("justifyCenter", null), 0);
        addButton(toolbar, "Rata kanan", v -> applyNativeWordCommand("justifyRight", null), 0);
        addButton(toolbar, "Rata penuh", v -> applyNativeWordCommand("justifyFull", null), 0);
        addButton(toolbar, "Poin", v -> applyNativeWordCommand("insertUnorderedList", null), 0);
        addButton(toolbar, "Nomor", v -> applyNativeWordCommand("insertOrderedList", null), 0);
        addButton(toolbar, "Bersihkan format", v -> applyNativeWordCommand("removeFormat", null), 0);
        addButton(toolbar, "Subskrip", v -> applyNativeWordCommand("subscript", null), 0);
        addButton(toolbar, "Superskrip", v -> applyNativeWordCommand("superscript", null), 0);
        addButton(toolbar, "Judul 1", v -> applyNativeWordCommand("formatBlock", "h1"), 0);
        addButton(toolbar, "Judul 2", v -> applyNativeWordCommand("formatBlock", "h2"), 0);
        addButton(toolbar, "Paragraf", v -> applyNativeWordCommand("formatBlock", "p"), 0);
        addButton(toolbar, "Warna teks", v -> showColorPicker(false), 0);
        addButton(toolbar, "Sorot", v -> showColorPicker(true), 0);
        toolbarScroll.addView(toolbar);
        root.addView(toolbarScroll);

        // Navigasi PDF berada di layar utama, tepat di atas kotak edit Word yang sama (Geser Kiri/Kanan atau Pilih Halaman Langsung).
        pdfPageNavRow = new LinearLayout(this);
        pdfPageNavRow.setOrientation(LinearLayout.HORIZONTAL);
        pdfPageNavRow.setGravity(Gravity.CENTER_VERTICAL);
        pdfPageSpinner = new Spinner(this);
        pdfPageSpinner.setContentDescription("Ketuk untuk memilih halaman PDF langsung, atau geser satu jari ke kiri dan kanan");
        pdfPageNavRow.addView(pdfPageSpinner, new LinearLayout.LayoutParams(0, dp(44), 1.0f));
        pdfPageNavRow.setVisibility(View.GONE);
        root.addView(pdfPageNavRow);
        pdfPageSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
            if (pdfDocumentActive && !changingPdfPage && position != activePdfTextPage) switchNativePdfPage(position, null);
        }));

        // Navigasi halaman dokumen Word (mode buku / naskah) — Geser Kiri/Kanan atau Pilih Halaman Langsung di atas kotak edit.
        docPageNavRow = new LinearLayout(this);
        docPageNavRow.setOrientation(LinearLayout.HORIZONTAL);
        docPageNavRow.setGravity(Gravity.CENTER_VERTICAL);
        docPageSpinner = new Spinner(this);
        docPageSpinner.setContentDescription("Ketuk untuk memilih halaman dokumen Word secara langsung, atau geser satu jari ke kiri dan kanan");
        docPageNavRow.addView(docPageSpinner, new LinearLayout.LayoutParams(0, dp(44), 1.5f));
        docPageAddButton = new Button(this); docPageAddButton.setText("Tambah Halaman"); docPageAddButton.setAllCaps(false);
        docPageNavRow.addView(docPageAddButton, new LinearLayout.LayoutParams(0, dp(44), 1.0f));
        root.addView(docPageNavRow);
        ensureDocumentPagesInitialized();
        refreshDocPageSpinner();
        docPageAddButton.setOnClickListener(v -> addNewDocumentPage());
        docPageSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
            if (documentPagedMode && !changingDocPage && !pdfDocumentActive && position != activeDocumentPage) {
                switchDocumentPage(position);
            }
        }));

        TextView tip = new TextView(this);
        tip.setText("Ketik langsung di kotak di bawah (otomatis tersimpan per halaman • geser 1 jari kiri/kanan atau ketuk daftar halaman di atas untuk pindah halaman).");
        tip.setTextSize(12);
        tip.setTextColor(muted);
        tip.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.addView(tip);

        editor = new WebView(this);
        editor.setBackgroundColor(Color.WHITE);
        editor.getSettings().setJavaScriptEnabled(true);
        editor.getSettings().setDomStorageEnabled(false);
        editor.getSettings().setDatabaseEnabled(false);
        editor.getSettings().setSupportMultipleWindows(false);
        editor.getSettings().setLoadsImagesAutomatically(true);
        editor.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final float[] swipeStart = new float[]{0f, 0f};
        // Keep WebView as a hidden conversion/formatting backend only. Typing happens directly
        // in the native EditText below; users no longer need to activate a preview or open a dialog.
        editor.setContentDescription("Backend dokumen tersembunyi untuk konversi dan ekspor.");
        editor.setWebChromeClient(new WebChromeClient());
        editor.setWebViewClient(new WebViewClient());
        editor.addJavascriptInterface(new EditorBridge(), "AnasEditor");
        editor.setVisibility(View.GONE);
        root.addView(editor, new LinearLayout.LayoutParams(1, 1));

        wordPageCounter = new TextView(this);
        wordPageCounter.setText("Perkiraan halaman: 1 • sekitar 40 baris per halaman A4");
        wordPageCounter.setTextSize(12);
        wordPageCounter.setTextColor(muted);
        wordPageCounter.setContentDescription("Informasi halaman dokumen yang diperbarui otomatis saat mengetik");
        wordPageCounter.setPadding(dp(6), dp(4), dp(6), dp(4));
        root.addView(wordPageCounter);

        ScrollView wordScroll = new ScrollView(this);
        wordScroll.setFillViewport(true);
        wordScroll.setVerticalScrollBarEnabled(true);
        wordEdit = createAccessibleDocumentEditText("Tulis sesuatu di sini");
        wordEdit.setHint("Tulis sesuatu di sini");
        wordEdit.setTextSize(selectedSize);
        wordEdit.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        int savedTheme = getSharedPreferences("document_studio", MODE_PRIVATE).getInt("editor_theme", darkUi ? 1 : 0);
        wordEdit.setBackground(UiKit.roundedBox(
                savedTheme == 1 ? surface : (savedTheme == 2 ? Color.parseColor("#FFF8DC") : Color.WHITE),
                border, dp(12), dp(1)));
        wordEdit.setTextColor(savedTheme == 1 ? txt : Color.parseColor("#111111"));
        wordEdit.setHintTextColor(muted);
        wordEdit.setMinHeight(dp(300));
        wordEdit.setContentDescription("Tulis sesuatu di sini");
        wordEdit.setFocusable(true);
        wordEdit.setFocusableInTouchMode(true);
        wordEdit.setShowSoftInputOnFocus(true);
        wordEdit.setOnClickListener(v -> wordEdit.postDelayed(() -> {
            if (wordEdit == null || !wordEdit.isAttachedToWindow()) return;
            wordEdit.requestFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(wordEdit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 120));
        wordEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (!updatingWordEdit && !applyingWordHistory && wordEdit != null) {
                    long now = System.currentTimeMillis();
                    if (wordUndoStack.isEmpty() || now - lastWordUndoSnapshotMs >= 1500L) {
                        wordUndoStack.add(new SpannedString(wordEdit.getText()));
                        if (wordUndoStack.size() > 5) wordUndoStack.remove(0);
                        lastWordUndoSnapshotMs = now;
                        wordRedoStack.clear();
                    }
                }
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (updatingWordEdit || changingDocPage) return;
                String changedText = s == null ? "" : s.toString();
                if (pdfDocumentActive && activePdfTextPage >= 0 && activePdfTextPage < pdfPageTexts.size()) {
                    // Only update the active page while typing; do not join hundreds of pages per keystroke.
                    pdfPageTexts.set(activePdfTextPage, changedText);
                    cachedText = changedText;
                } else if (documentPagedMode && documentPages != null && activeDocumentPage >= 0 && activeDocumentPage < documentPages.size()) {
                    documentPages.set(activeDocumentPage, changedText);
                    cachedText = joinDocumentPages();
                } else {
                    cachedText = changedText;
                }
                // Avoid serializing the entire document to HTML on every keystroke; export builds HTML on demand.
                wordEditDirty = true;
                nativeTextEditorActive = true;
                scheduleDraftAutosave();
                if (wordPageCounter != null && wordEdit != null) {
                    wordEdit.post(() -> updateWordPageEstimate());
                }
            }
            @Override public void afterTextChanged(android.text.Editable e) {}
        });
        wordScroll.addView(wordEdit, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams wordScrollLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        wordScrollLp.topMargin = dp(4);
        root.addView(wordScroll, wordScrollLp);

        status = new TextView(this);
        status.setText("Dokumen baru — mode halaman buku aktif. Ketik di kotak, atau pilih 4 menu utama di atas. 100% ramah pembaca layar.");
        ensureDocumentPagesInitialized();
        refreshDocPageSpinner();
        status.setTextColor(UiKit.textMuted(OfficeUtils.mainPrefs(this)));
        status.setTextSize(12);
        status.setPadding(dp(4), dp(6), dp(4), dp(2));
        root.addView(status);
        setContentView(root);
    }

    /** Live page estimate for the lightweight native editor. Exporters lay out physical pages separately. */
    private void updateWordPageEstimate() {
        if (wordEdit == null || wordPageCounter == null) return;
        int lineCount = 1;
        android.text.Layout layout = wordEdit.getLayout();
        if (layout != null) {
            lineCount = Math.max(1, layout.getLineCount());
        } else {
            CharSequence text = wordEdit.getText();
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lineCount++;
        }
        final int estimatedLinesPerPage = getSharedPreferences("document_studio", MODE_PRIVATE)
                .getInt("estimated_lines_per_page", 40);
        if (documentPagedMode && !pdfDocumentActive && documentPages != null && !documentPages.isEmpty()) {
            int totalPages = documentPages.size();
            int totalChars = 0;
            for (String p : documentPages) totalChars += (p == null ? 0 : p.length());
            wordPageCounter.setText("Halaman aktif: " + (activeDocumentPage + 1) + " / " + totalPages
                    + " • Baris halaman ini: " + lineCount
                    + " • Total karakter: " + totalChars
                    + " • Mode buku (isi per halaman terpisah). Tersimpan otomatis di Brankas.");
        } else {
            int pages = Math.max(1, (lineCount + estimatedLinesPerPage - 1) / estimatedLinesPerPage);
            wordPageCounter.setText("Perkiraan halaman A4: " + pages + " • Baris tampak: " + lineCount
                    + " • Patokan " + estimatedLinesPerPage + " baris per halaman. Dokumen tersimpan otomatis di Brankas.");
        }
    }

    private interface PositionAction { void run(int position); }
    private static class SimpleItemSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        private final PositionAction action;
        SimpleItemSelectedListener(PositionAction action) { this.action = action; }
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { action.run(position); }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }

    private void addButton(LinearLayout parent, String label, View.OnClickListener listener, int weight) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setMinHeight(dp(44));
        b.setContentDescription(label);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(weight == 1 ? 0 : LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, weight == 1 ? 1f : 0f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        parent.addView(b, lp);
    }

    private String editorShell(String body) { return editorShell(body, true); }

    private String editorShell(String body, boolean editable) {
        android.content.SharedPreferences p = getSharedPreferences("document_studio", MODE_PRIVATE);
        int margin = p.getInt("editor_margin", 16);
        int theme = p.getInt("editor_theme", 0);
        String pageBg = theme == 1 ? "#202124" : theme == 2 ? "#FFF8DC" : "#FFFFFF";
        String pageFg = theme == 1 ? "#F1F3F4" : "#111111";
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><style>"+
                "@font-face{font-family:'Liberation Serif';src:url('file:///android_asset/fonts/LiberationSerif-Regular.ttf')}"+
                "@font-face{font-family:'Liberation Serif';font-weight:bold;src:url('file:///android_asset/fonts/LiberationSerif-Bold.ttf')}"+
                "@font-face{font-family:'Liberation Sans';src:url('file:///android_asset/fonts/LiberationSans-Regular.ttf')}"+
                "@font-face{font-family:'Liberation Sans';font-weight:bold;src:url('file:///android_asset/fonts/LiberationSans-Bold.ttf')}"+
                "@font-face{font-family:'Liberation Mono';src:url('file:///android_asset/fonts/LiberationMono-Regular.ttf')}"+
                "@font-face{font-family:'Liberation Mono';font-weight:bold;src:url('file:///android_asset/fonts/LiberationMono-Bold.ttf')}"+
                "@font-face{font-family:'DejaVu Sans';src:url('file:///android_asset/fonts/DejaVuSans.ttf')}"+
                "@font-face{font-family:'DejaVu Sans';font-weight:bold;src:url('file:///android_asset/fonts/DejaVuSans-Bold.ttf')}"+
                "@font-face{font-family:'DejaVu Sans Mono';src:url('file:///android_asset/fonts/DejaVuSansMono.ttf')}"+
                "body{font-family:'"+cssFontFamily()+"',serif;font-size:"+selectedSize+"pt;color:"+pageFg+";background:"+pageBg+";margin:0;line-height:1.45}#editor{min-height:100%;outline:none;white-space:normal;font-family:'"+cssFontFamily()+"',serif;font-size:"+selectedSize+"pt;padding:0;background:transparent;color:"+pageFg+"}.anas-page{box-sizing:border-box;min-height:1056px;padding:"+margin+"px;background:"+pageBg+";border:1px solid #d9dfe7;margin:0 auto 18px auto;max-width:794px;overflow-wrap:anywhere;box-shadow:0 1px 4px rgba(0,0,0,.08)}p{margin:0 0 10pt 0}table{border-collapse:collapse;max-width:100%}td,th{border:1px solid #555;padding:5px}</style></head>"+
                "<body><div id='editor' contenteditable='"+(editable ? "true" : "false")+"' spellcheck='true' autocapitalize='sentences' role='"+(editable ? "textbox" : "document")+"' aria-label='"+(editable ? "Isi dokumen" : "Pratinjau dokumen. Ketuk untuk membuka editor teks ringan")+"'>"+body+"</div></body></html>";
    }
    private String cssFontFamily() {
        String f = selectedFont == null ? "Liberation Serif" : selectedFont;
        if (f.equals("Times New Roman*") || f.equalsIgnoreCase("Times New Roman") || f.equalsIgnoreCase("Georgia") || f.equalsIgnoreCase("Serif")) return "Liberation Serif";
        if (f.equalsIgnoreCase("Arial") || f.equalsIgnoreCase("Calibri") || f.equalsIgnoreCase("Aptos") || f.equalsIgnoreCase("Sans Serif")) return "Liberation Sans";
        if (f.equalsIgnoreCase("Verdana") || f.equalsIgnoreCase("Tahoma")) return "DejaVu Sans";
        if (f.equalsIgnoreCase("Courier New") || f.equalsIgnoreCase("Monospace")) return "Liberation Mono";
        if (f.equalsIgnoreCase("DejaVu Sans Mono")) return "DejaVu Sans Mono";
        return f;
    }

    private void loadHtml(String html) {
        pdfDocumentActive = false;
        if (pdfPageNavRow != null) pdfPageNavRow.setVisibility(View.GONE);
        if (docPageNavRow != null) docPageNavRow.setVisibility(View.VISIBLE);
        String source = html == null ? "" : html;
        if (editor != null && source.length() <= 100000) editor.loadDataWithBaseURL("file:///android_asset/", editorShell(source, false), "text/html", "UTF-8", null);
        CharSequence styled;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) styled = Html.fromHtml(source, Html.FROM_HTML_MODE_LEGACY);
            else styled = Html.fromHtml(source);
        } catch (Exception ignored) { styled = source.replaceAll("<[^>]*>", ""); }
        setWordEditorContent(styled);
        cachedHtml = source;
        wordEditDirty = false;
        nativeTextEditorActive = true;
    }

    private void setWordEditorContent(String text) { setWordEditorContent((CharSequence) (text == null ? "" : text)); }

    private void setWordEditorContent(CharSequence content) {
        CharSequence value = content == null ? "" : content;
        String full = value.toString();
        cachedText = full;
        cachedHtml = value instanceof Spanned ? Html.toHtml((Spanned) value, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE) : textToHtml(cachedText, true);
        wordEditDirty = false;
        wordUndoStack.clear(); wordRedoStack.clear(); lastWordUndoSnapshotMs = 0L;
        // Pecah berdasarkan form-feed (\f) biar mode halaman buku tetap utuh saat load/simpan.
        if (documentPagedMode && !pdfDocumentActive) {
            documentPages = new ArrayList<>();
            if (full.contains("\f")) {
                String[] parts = full.split("\f", -1);
                for (String p : parts) {
                    String cleaned = p == null ? "" : p.replaceAll("^\\n+|\\n+$", "");
                    documentPages.add(cleaned);
                }
            } else {
                documentPages.add(full);
            }
            if (documentPages.isEmpty()) documentPages.add("");
            activeDocumentPage = 0;
            if (wordEdit != null) {
                loadDocumentPageIntoEditor(0);
            }
            refreshDocPageSpinner();
            return;
        }
        if (wordEdit != null) {
            updatingWordEdit = true;
            wordEdit.setText(value, EditText.BufferType.SPANNABLE);
            wordEdit.setSelection(0);
            updatingWordEdit = false;
        }
    }

    private String nativeWordHtml() {
        if (wordEdit == null) return textToHtml(cachedText, true);
        try { return Html.toHtml(new SpannedString(wordEdit.getText()), Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE); }
        catch (Exception ignored) { return textToHtml(wordEdit.getText().toString(), true); }
    }

    private int[] wordSelectionOrCurrentWord() {
        if (wordEdit == null) return new int[]{0,0};
        android.text.Editable e = wordEdit.getText(); int a = Math.max(0, wordEdit.getSelectionStart()), b = Math.max(0, wordEdit.getSelectionEnd());
        if (a != b) return new int[]{Math.min(a,b), Math.max(a,b)};
        int len = e.length(); if (len == 0) return new int[]{0,0};
        int start = Math.min(a, len), end = start;
        while (start > 0 && !Character.isWhitespace(e.charAt(start-1))) start--;
        while (end < len && !Character.isWhitespace(e.charAt(end))) end++;
        if (start == end) { start = Math.max(0, Math.min(a, len)); end = Math.min(len, start+1); }
        return new int[]{start,end};
    }

    private void applyNativeWordCommand(String command, String value) {
        if (wordEdit == null) return;
        android.text.Editable e = wordEdit.getText();
        int[] range = wordSelectionOrCurrentWord(); int start = range[0], end = range[1];
        if ("insertUnorderedList".equals(command) || "insertOrderedList".equals(command)) {
            int pos = Math.max(0, wordEdit.getSelectionStart());
            String prefix = "insertOrderedList".equals(command) ? "1. " : "• ";
            e.insert(pos, prefix); wordEdit.setSelection(pos + prefix.length()); return;
        }
        if ("justifyLeft".equals(command) || "justifyCenter".equals(command) || "justifyRight".equals(command) || "justifyFull".equals(command)) {
            int pos = Math.max(0, wordEdit.getSelectionStart());
            String all = e.toString(); int a = all.lastIndexOf("\n", Math.max(0, pos-1)) + 1;
            int b = all.indexOf("\n", pos); if (b < 0) b = e.length(); else b++;
            android.text.Layout.Alignment align = "justifyCenter".equals(command) ? android.text.Layout.Alignment.ALIGN_CENTER : "justifyRight".equals(command) ? android.text.Layout.Alignment.ALIGN_OPPOSITE : android.text.Layout.Alignment.ALIGN_NORMAL;
            if (b > a) e.setSpan(new AlignmentSpan.Standard(align), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            cachedHtml = nativeWordHtml(); cachedText = e.toString(); wordEditDirty = true; return;
        }
        if (start == end && "formatBlock".equals(command)) {
            String all = e.toString(); int cursor = Math.max(0, Math.min(wordEdit.getSelectionStart(), all.length()));
            start = all.lastIndexOf("\n", Math.max(0, cursor - 1)) + 1;
            end = all.indexOf("\n", cursor); if (end < 0) end = all.length();
        }
        if (start == end) { Toast.makeText(this, "Pilih teks dahulu agar format diterapkan tepat pada teks yang diinginkan.", Toast.LENGTH_SHORT).show(); return; }
        wordUndoStack.add(new SpannedString(e)); if (wordUndoStack.size() > 40) wordUndoStack.remove(0); wordRedoStack.clear();
        if ("removeFormat".equals(command)) {
            Object[] spans = e.getSpans(start, end, Object.class);
            for (Object span : spans) if (!(span instanceof android.text.NoCopySpan)) e.removeSpan(span);
        } else if ("bold".equals(command)) e.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        else if ("italic".equals(command)) e.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        else if ("underline".equals(command)) e.setSpan(new UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        else if ("strikeThrough".equals(command)) e.setSpan(new StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        else if ("subscript".equals(command)) e.setSpan(new SubscriptSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        else if ("superscript".equals(command)) e.setSpan(new SuperscriptSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        else if ("fontName".equals(command)) e.setSpan(new TypefaceSpan(cssFontFamily()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        else if ("fontSize".equals(command)) { try { e.setSpan(new AbsoluteSizeSpan(Integer.parseInt(value), true), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); } catch (Exception ignored) {} }
        else if ("formatBlock".equals(command)) {
            String block = value == null ? "p" : value.toLowerCase(Locale.ROOT);
            if ("h1".equals(block)) { e.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); e.setSpan(new RelativeSizeSpan(1.6f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); }
            else if ("h2".equals(block)) { e.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); e.setSpan(new RelativeSizeSpan(1.35f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); }
            else if ("h3".equals(block)) { e.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); e.setSpan(new RelativeSizeSpan(1.2f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); }
            else if ("h4".equals(block)) { e.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); }
            else if ("blockquote".equals(block)) { e.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); e.setSpan(new ForegroundColorSpan(Color.DKGRAY), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); }
            else if ("pre".equals(block)) e.setSpan(new TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        wordEdit.setSelection(Math.min(end, e.length()));
        cachedText = e.toString(); cachedHtml = nativeWordHtml(); wordEditDirty = true; nativeTextEditorActive = true;
    }

    private void applyNativeWordColor(boolean highlight, String colorValue) {
        if (wordEdit == null) return;
        int[] range = wordSelectionOrCurrentWord(); int start=range[0], end=range[1];
        if (start >= end) { Toast.makeText(this, "Pilih teks terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        int color = Color.BLACK; try { color = Color.parseColor(colorValue); } catch (Exception ignored) {}
        wordUndoStack.add(new SpannedString(wordEdit.getText())); if (wordUndoStack.size() > 40) wordUndoStack.remove(0); wordRedoStack.clear();
        if (highlight) wordEdit.getText().setSpan(new BackgroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        else wordEdit.getText().setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        cachedHtml = nativeWordHtml(); cachedText = wordEdit.getText().toString(); wordEditDirty = true;
    }

    private void undoWordEdit() { if (wordEdit == null || wordUndoStack.isEmpty()) return; wordRedoStack.add(new SpannedString(wordEdit.getText())); CharSequence prior=wordUndoStack.remove(wordUndoStack.size()-1); applyingWordHistory=true; wordEdit.setText(prior, EditText.BufferType.SPANNABLE); wordEdit.setSelection(wordEdit.length()); applyingWordHistory=false; cachedText=wordEdit.getText().toString(); cachedHtml=nativeWordHtml(); wordEditDirty=true; }
    private void redoWordEdit() { if (wordEdit == null || wordRedoStack.isEmpty()) return; wordUndoStack.add(new SpannedString(wordEdit.getText())); CharSequence next=wordRedoStack.remove(wordRedoStack.size()-1); applyingWordHistory=true; wordEdit.setText(next, EditText.BufferType.SPANNABLE); wordEdit.setSelection(wordEdit.length()); applyingWordHistory=false; cachedText=wordEdit.getText().toString(); cachedHtml=nativeWordHtml(); wordEditDirty=true; }


    private void loadNativeInlineText(String title, String text) {
        pdfDocumentActive = false;
        if (pdfPageNavRow != null) pdfPageNavRow.setVisibility(View.GONE);
        if (docPageNavRow != null) docPageNavRow.setVisibility(View.VISIBLE);
        currentName = title == null ? "Dokumen" : title;
        setWordEditorContent(text);
        nativeTextEditorActive = true;
        setStatus("Dibuka: " + currentName + ". Mode halaman buku siap. Ganti halaman lewat tombol di atas.");
    }

    /** All typing in the Word document area is routed to the lightweight native EditText dialog.
     * The WebView remains a read-only preview so tapping it cannot trigger its old contenteditable
     * keyboard path, which was freezing on some screen-reader/device combinations. */
    private void openWordNativeEditorFromPreview() {
        if (nativeTextEditorActive && cachedText != null && !cachedText.isEmpty()) {
            showNativeDocumentTextEditor(currentName, cachedText, true);
            return;
        }
        if (editor == null) return;
        editor.evaluateJavascript("(function(){var e=document.getElementById('editor');return e ? (e.innerText||e.textContent||'') : '';})()", value -> {
            String text = decodeJsString(value);
            showNativeDocumentTextEditor(currentName, text, true);
        });
    }
    private void openWordAiAssistant() {
        String cur = wordEdit == null ? "" : wordEdit.getText().toString();
        OfficeAiHelper.showWordAiDialog(this, OfficeUtils.mainPrefs(this), cur, (text, replaceAll) -> {
            if (wordEdit == null) return;
            String clean = OfficeAiHelper.cleanWordDocumentOutput(text);
            if (replaceAll) {
                setWordEditorContent(clean);
            } else {
                insertNativeTextAtCursor(clean);
            }
            wordEditDirty = true;
            scheduleDraftAutosave();
            Toast.makeText(this, "Hasil AI bersih diterapkan ke Word.", Toast.LENGTH_SHORT).show();
        });
    }

    private void insertNativeTextAtCursor(String text) {
        if (wordEdit == null) return;
        int start = Math.max(0, wordEdit.getSelectionStart()), end = Math.max(start, wordEdit.getSelectionEnd());
        wordEdit.getText().replace(start, end, text == null ? "" : text);
        int pos = start + (text == null ? 0 : text.length()); wordEdit.setSelection(Math.min(pos, wordEdit.length()));
        wordEdit.requestFocus();
    }

    private void runJs(String js) { if (editor != null) editor.evaluateJavascript(js, null); }
    private void runEditorCommand(String command, String value) {
        String val = value == null ? "null" : "'" + jsEscape(value) + "'";
        runJs("document.execCommand('" + jsEscape(command) + "',false," + val + ");document.getElementById('editor').focus();");
    }
    private String jsEscape(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\n").replace("\r", ""); }

    private class EditorBridge { @JavascriptInterface public void loaded(String value) { cachedText = value == null ? "" : value; } }

    private void openBuiltInFileManager() {
        Intent intent = new Intent(this, FileManagerActivity.class);
        startActivityForResult(intent, REQ_FILE_MANAGER);
    }

    private void showStorageHelp() {
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this))
            .setTitle("File Manager dan izin penyimpanan")
            .setMessage("File Manager dapat menelusuri folder penyimpanan bersama seperti Download, Documents, dan folder lain di /storage/emulated/0. Pada Android 11 ke atas, akses semua berkas memerlukan izin khusus yang harus diaktifkan sendiri melalui Pengaturan Android. Izin ini tidak otomatis diberikan hanya karena tercantum di AndroidManifest.\n\nFitur Simpan / Ekspor versi ini memakai dialog nama berkas milik aplikasi dan menyimpan ke /storage/emulated/0/Microsoft Word setelah izin diberikan. Jika izin tidak diberikan, penyimpanan langsung ke folder utama tidak dapat dijamin.")
            .setPositiveButton("Mengerti", null).show();
    }

    private void showPageNavigation() {
        if (pdfDocumentActive) {
            setStatus("Sedang mode PDF. Gunakan spinner halaman PDF di atas.");
            return;
        }
        ensureDocumentPagesInitialized();
        final int totalPages = Math.max(1, documentPages.size());
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(12), dp(8), dp(12), dp(4));
        TextView info = new TextView(this);
        info.setText("Mode halaman buku aktif.\nTotal halaman: " + totalPages + "\nHalaman aktif: " + (activeDocumentPage + 1)
                + "\n\nPilih nomor halaman di bawah. Isi halaman sebelumnya otomatis disimpan; kotak edit akan menampilkan isi halaman yang dipilih (kosong jika belum ada tulisan).");
        info.setPadding(0, 0, 0, dp(8)); box.addView(info);
        EditText pageInput = new EditText(this); pageInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        pageInput.setHint("Nomor halaman (1–" + totalPages + ")"); pageInput.setText(String.valueOf(activeDocumentPage + 1)); box.addView(pageInput);
        UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE)).setTitle("Navigasi halaman dokumen")
            .setView(box)
            .setNeutralButton("+ Halaman baru", (d,w) -> addNewDocumentPage())
            .setPositiveButton("Pergi", (d,w) -> {
                int page = parseBounded(pageInput.getText().toString(), 1, 1, totalPages);
                switchDocumentPage(page - 1);
                setStatus("Pindah ke halaman " + page + " dari " + totalPages + ".");
            }).show();
    }

    private void jumpNativeDocumentToOffset(int offset) {
        if (wordEdit == null) return;
        int pos = Math.max(0, Math.min(offset, wordEdit.length()));
        wordEdit.requestFocus(); wordEdit.setSelection(pos);
        wordEdit.post(() -> {
            android.text.Layout layout = wordEdit.getLayout();
            if (layout != null && pos <= wordEdit.length()) {
                int line = layout.getLineForOffset(pos);
                wordEdit.scrollTo(0, Math.max(0, layout.getLineTop(line) - dp(24)));
            }
        });
    }

    private void insertPageBreak() {
        // Di mode halaman buku: simpan halaman ini lalu buat halaman baru kosong.
        if (documentPagedMode && !pdfDocumentActive) {
            saveActiveDocumentPage();
            addNewDocumentPage();
            setStatus("Halaman baru dibuat. Isi halaman sebelumnya sudah disimpan. Silakan lanjut nulis di halaman ini.");
            return;
        }
        insertNativeTextAtCursor(String.valueOf((char)12));
        setStatus("Pemisah halaman ditambahkan. Saat ekspor PDF, teks setelah pemisah akan dimulai pada halaman berikutnya.");
    }

    private void ensureDocumentPagesInitialized() {
        if (documentPages == null) documentPages = new ArrayList<>();
        if (documentPages.isEmpty()) {
            String seed = (wordEdit != null && !updatingWordEdit) ? wordEdit.getText().toString() : (cachedText == null ? "" : cachedText);
            documentPages.add(seed == null ? "" : seed);
            activeDocumentPage = 0;
        }
        if (activeDocumentPage < 0) activeDocumentPage = 0;
        if (activeDocumentPage >= documentPages.size()) activeDocumentPage = documentPages.size() - 1;
    }

    private String joinDocumentPages() {
        if (documentPages == null || documentPages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < documentPages.size(); i++) {
            if (i > 0) sb.append("\n\f\n"); // form-feed sebagai penanda halaman
            sb.append(documentPages.get(i) == null ? "" : documentPages.get(i));
        }
        return sb.toString();
    }

    private void saveActiveDocumentPage() {
        if (wordEdit == null || documentPages == null) return;
        if (activeDocumentPage >= 0 && activeDocumentPage < documentPages.size()) {
            documentPages.set(activeDocumentPage, wordEdit.getText() == null ? "" : wordEdit.getText().toString());
        }
        cachedText = joinDocumentPages();
    }

    private void loadDocumentPageIntoEditor(int position) {
        if (wordEdit == null || documentPages == null) return;
        if (position < 0 || position >= documentPages.size()) return;
        changingDocPage = true;
        updatingWordEdit = true;
        String pageText = documentPages.get(position);
        wordEdit.setText(pageText == null ? "" : pageText, EditText.BufferType.SPANNABLE);
        wordEdit.setSelection(0);
        updatingWordEdit = false;
        changingDocPage = false;
        activeDocumentPage = position;
        wordEditDirty = true;
        nativeTextEditorActive = true;
        wordEdit.setHint("Halaman " + (position + 1) + " — ketik di sini (kosong = halaman baru)");
        refreshDocPageSpinner();
        updateWordPageEstimate();
        if (docPageNavRow != null) docPageNavRow.setVisibility(pdfDocumentActive ? View.GONE : View.VISIBLE);
    }

    private boolean docPageTargetValid() {
        return pdfDocumentActive || (documentPagedMode && documentPages != null && documentPages.size() > 1);
    }

    private void stepPageBySwipe(int delta) {
        if (pdfDocumentActive && pdfPageTexts != null) {
            int next = activePdfTextPage + delta;
            if (next >= 0 && next < pdfPageTexts.size()) {
                switchNativePdfPage(next, null);
                OfficeUtils.announce(rootLayoutOrStatus(), "Halaman PDF " + (next + 1) + " dari " + pdfPageTexts.size());
            }
        } else if (documentPagedMode && documentPages != null) {
            int next = activeDocumentPage + delta;
            if (next >= 0 && next < documentPages.size()) {
                switchDocumentPage(next);
                OfficeUtils.announce(rootLayoutOrStatus(), "Halaman " + (next + 1) + " dari " + documentPages.size());
            }
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (docPageTargetValid() && ev.getPointerCount() == 1) {
            int action = ev.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                docSwipeStartX = ev.getX();
                docSwipeStartY = ev.getY();
                docSwipeTracking = true;
                docSwipeTriggered = false;
            } else if (action == android.view.MotionEvent.ACTION_MOVE && docSwipeTracking && !docSwipeTriggered) {
                float dx = ev.getX() - docSwipeStartX;
                float dy = ev.getY() - docSwipeStartY;
                float thresh = UiKit.dp(this, 75);
                if (Math.abs(dx) > thresh && Math.abs(dx) > Math.abs(dy) * 1.7f) {
                    docSwipeTriggered = true;
                    android.view.MotionEvent cancelEv = android.view.MotionEvent.obtain(ev);
                    cancelEv.setAction(android.view.MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancelEv);
                    cancelEv.recycle();
                    stepPageBySwipe(dx < 0 ? 1 : -1);
                    return true;
                }
            } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                docSwipeTracking = false;
                docSwipeTriggered = false;
            }
        } else {
            docSwipeTracking = false;
            docSwipeTriggered = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void switchDocumentPage(int position) {
        if (pdfDocumentActive) return;
        ensureDocumentPagesInitialized();
        if (position < 0 || position >= documentPages.size()) return;
        if (position == activeDocumentPage) return;
        saveActiveDocumentPage();
        loadDocumentPageIntoEditor(position);
        setStatus("Halaman " + (position + 1) + " dari " + documentPages.size() + ". Isi halaman lain tetap aman tersimpan.");
    }

    private void addNewDocumentPage() {
        if (pdfDocumentActive) return;
        ensureDocumentPagesInitialized();
        saveActiveDocumentPage();
        documentPages.add("");
        activeDocumentPage = documentPages.size() - 1;
        loadDocumentPageIntoEditor(activeDocumentPage);
        setStatus("Halaman baru #" + (activeDocumentPage + 1) + " ditambahkan. Silakan mulai nulis.");
    }

    private void refreshDocPageSpinner() {
        if (docPageSpinner == null) return;
        ensureDocumentPagesInitialized();
        ArrayList<String> labels = new ArrayList<>();
        for (int i = 0; i < documentPages.size(); i++) {
            String preview = documentPages.get(i);
            if (preview == null) preview = "";
            preview = preview.replace("\n", " ").trim();
            if (preview.length() > 28) preview = preview.substring(0, 28) + "…";
            if (preview.isEmpty()) preview = "(kosong)";
            labels.add("Hal. " + (i + 1) + " — " + preview);
        }
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels);
        changingDocPage = true;
        docPageSpinner.setAdapter(adapter);
        if (activeDocumentPage >= 0 && activeDocumentPage < labels.size()) {
            docPageSpinner.setSelection(activeDocumentPage);
        }
        changingDocPage = false;
        if (docPagePrevButton != null) docPagePrevButton.setEnabled(activeDocumentPage > 0);
        if (docPageNextButton != null) docPageNextButton.setEnabled(activeDocumentPage < documentPages.size() - 1);
    }

    private void openFilePicker() {
        OfficeUtils.launchFilePicker(this, REQ_OPEN, "office", "Buka Dokumen Word / PDF / Teks");
    }

    // Brankas menyimpan draf kerja internal secara otomatis. Ekspor tidak menutup editor.
    private File draftVaultDir() {
        File dir = new File(getFilesDir(), "document_vault");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private String safeDraftKey(String name) {
        String value = name == null ? "Dokumen_Baru" : name.trim();
        value = value.replaceAll("\\.[^.]+$", "").replaceAll("[^A-Za-z0-9._-]", "_");
        return value.isEmpty() ? "Dokumen_Baru" : value;
    }

    private File draftFileFor(String name) { return new File(draftVaultDir(), safeDraftKey(name) + ".draft.txt"); }

    private void scheduleDraftAutosave() {
        if (wordEdit == null || pdfDocumentActive) return;
        if (documentPagedMode) saveActiveDocumentPage();
        final String snapshot = documentPagedMode ? joinDocumentPages()
                : (wordEdit.getText() == null ? "" : wordEdit.getText().toString());
        final String draftName = currentName == null ? "Dokumen_Baru" : currentName;
        wordEdit.removeCallbacks(draftAutosaveRunnable);
        pendingDraftText = snapshot;
        pendingDraftName = draftName;
        wordEdit.postDelayed(draftAutosaveRunnable, 650L);
    }

    private String pendingDraftText = "";
    private String pendingDraftName = "Dokumen_Baru";
    private final Runnable draftAutosaveRunnable = new Runnable() {
        @Override public void run() { writeDraft(pendingDraftName, pendingDraftText); }
    };

    private void writeDraft(String name, String text) {
        try {
            File target = draftFileFor(name);
            try (FileOutputStream out = new FileOutputStream(target, false)) {
                out.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            int caret = wordEdit == null ? 0 : Math.max(0, wordEdit.getSelectionStart());
            getSharedPreferences("document_studio", MODE_PRIVATE).edit()
                    .putString("last_draft_name", name == null ? "Dokumen_Baru" : name)
                    .putInt("last_draft_caret", caret)
                    .putLong("last_draft_saved", System.currentTimeMillis()).apply();
        } catch (Exception ignored) { }
    }

    private void restoreMostRecentDraft() {
        android.content.SharedPreferences prefs = getSharedPreferences("document_studio", MODE_PRIVATE);
        String name = prefs.getString("last_draft_name", "");
        if (name == null || name.trim().isEmpty()) return;
        File draft = draftFileFor(name);
        if (!draft.isFile()) return;
        try (FileInputStream in = new FileInputStream(draft); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            currentName = name;
            setWordEditorContent(text);
            cachedText = text;
            int caret = Math.min(text.length(), Math.max(0, prefs.getInt("last_draft_caret", text.length())));
            if (wordEdit != null) wordEdit.setSelection(caret);
            wordEditDirty = !text.isEmpty();
            setStatus("Draf dipulihkan dari Brankas: " + name + ". Posisi pengetikan terakhir dipulihkan.");
        } catch (Exception ignored) { }
    }

    private void showDraftVault() {
        File[] drafts = draftVaultDir().listFiles((dir, filename) -> filename.endsWith(".draft.txt"));
        if (drafts == null || drafts.length == 0) {
            UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Brankas pekerjaan")
                    .setMessage("Belum ada draf tersimpan. Draf akan disimpan otomatis ketika lo mengetik.")
                    .setPositiveButton("Mengerti", null).show();
            return;
        }
        java.util.Arrays.sort(drafts, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] labels = new String[drafts.length];
        for (int i = 0; i < drafts.length; i++) {
            String fileName = drafts[i].getName().replaceFirst("\\.draft\\.txt$", "");
            labels[i] = fileName + ", terakhir diubah " + new java.text.SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(new java.util.Date(drafts[i].lastModified()));
        }
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Brankas pekerjaan — pilih draf")
                .setItems(labels, (dialog, which) -> {
                    File selected = drafts[which];
                    try (FileInputStream in = new FileInputStream(selected); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                        currentName = selected.getName().replaceFirst("\\.draft\\.txt$", "");
                        pdfDocumentActive = false;
                        setWordEditorContent(text);
                        cachedText = text;
                        wordEditDirty = true;
                        setStatus("Draf dibuka: " + currentName + ". Editor tetap aktif.");
                    } catch (Exception e) { showError("Gagal membuka draf", e); }
                }).show();
    }

    private void removeExportedDraft(String name) {
        try { File draft = draftFileFor(name); if (draft.exists()) draft.delete(); } catch (Exception ignored) { }
    }

    private void showSaveOptions() {
        final String[] labels = {"DOCX (Microsoft Word)", "PDF", "HTML", "TXT (teks polos)", "RTF", "Markdown", "DOTX (template Word)", "ODT (OpenDocument)"};
        final String[] types = {"docx", "pdf", "html", "txt", "rtf", "md", "dotx", "odt"};
        final String[] exts = {".docx", ".pdf", ".html", ".txt", ".rtf", ".md", ".dotx", ".odt"};
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Simpan ke folder Microsoft Word")
            .setItems(labels, (d, which) -> showSaveNameDialog(types[which], exts[which])).show();
    }

    private void showSaveNameDialog(String type, String extension) {
        pendingSaveType = type;
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16), dp(8), dp(16), dp(4));
        TextView location = new TextView(this);
        File chosen = pendingSaveDirectory != null ? pendingSaveDirectory : OfficeUtils.resolveBackupSubfolder(this, "Dokumen Word");
        location.setText("Folder tujuan: " + chosen.getAbsolutePath());
        location.setTextSize(13); box.addView(location);
        EditText nameInput = new EditText(this); nameInput.setSingleLine(true); nameInput.setHint("Tulis nama dokumen di sini");
        String base = currentName == null || currentName.trim().isEmpty() ? "Dokumen_Baru" : currentName.replaceAll("\\.[^.]+$", "");
        nameInput.setText(base); nameInput.setSelection(nameInput.length()); box.addView(nameInput);
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Nama berkas")
            .setView(box).setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", (dialog, which) -> {
                String baseName = nameInput.getText().toString().trim();
                if (baseName.isEmpty()) { Toast.makeText(this, "Nama dokumen belum diisi.", Toast.LENGTH_SHORT).show(); return; }
                baseName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (baseName.toLowerCase(Locale.ROOT).endsWith(extension)) baseName = baseName.substring(0, baseName.length() - extension.length());
                final String safeBaseName = baseName;
                if (!hasAllFilesAccess()) {
                    UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Izinkan akses folder Microsoft Word")
                        .setMessage("Agar dokumen dapat disimpan langsung ke /storage/emulated/0/Microsoft Word, aktifkan izin akses semua berkas untuk aplikasi ini. Setelah kembali, tekan Simpan / Ekspor lagi.")
                        .setNegativeButton("Batal", null).setPositiveButton("Izinkan", (dd, ww) -> requestStorageAccess()).show();
                    return;
                }
                File dir = pendingSaveDirectory != null ? pendingSaveDirectory : OfficeUtils.resolveBackupSubfolder(this, "Dokumen Word");
                pendingSaveDirectory = null;
                if (!dir.exists() && !dir.mkdirs()) { showError("Folder tidak dapat dibuat", new IOException("Tidak bisa membuat folder " + dir.getAbsolutePath())); return; }
                File target = new File(dir, safeBaseName + extension);
                if (target.exists()) {
                    final File overwriteTarget = target;
                    UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Berkas sudah ada")
                        .setMessage("" + target.getName() + " sudah ada. Ganti berkas tersebut?")
                        .setNegativeButton("Batal", null).setPositiveButton("Ganti", (dd, ww) -> saveCurrentDocumentToFile(overwriteTarget, type))
                        .setNeutralButton("Simpan salinan", (dd, ww) -> {
                            File copy = new File(dir, safeBaseName + "_" + System.currentTimeMillis() + extension);
                            saveCurrentDocumentToFile(copy, type);
                        }).show();
                } else saveCurrentDocumentToFile(target, type);
            }).show();
    }

    private boolean hasAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT >= 30) return android.os.Environment.isExternalStorageManager();
        if (android.os.Build.VERSION.SDK_INT >= 29) return false; // Scoped storage Android 10 tidak mengizinkan penulisan umum ke root dengan path langsung.
        if (android.os.Build.VERSION.SDK_INT >= 23) return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return true;
    }

    private void requestStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                catch (Exception ignored) { showError("Pengaturan izin tidak tersedia", e); }
            }
        } else if (android.os.Build.VERSION.SDK_INT >= 23 && android.os.Build.VERSION.SDK_INT <= 28) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE_PERMISSION);
        } else if (android.os.Build.VERSION.SDK_INT == 29) {
            UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Pembatasan penyimpanan Android 10")
                .setMessage("Android 10 membatasi penulisan langsung ke folder utama untuk aplikasi yang memakai penyimpanan terbatas. Untuk menjaga keamanan, versi ini tidak akan berpura-pura bisa menyimpan ke folder Microsoft Word pada sistem tersebut.")
                .setPositiveButton("Mengerti", null).show();
        }
    }

    private void saveCurrentDocumentToFile(File target, String type) {
        final String saveText;
        final String saveHtml;
        try {
            if (pdfDocumentActive) {
                saveActivePdfPageText();
                cachedText = joinPdfPages(pdfPageTexts);
                saveText = cachedText;
                // Build page-preserving HTML only for moderate documents; plain text is safer for huge exports.
                saveHtml = saveText.length() <= 100000 ? pdfPagesToHtml(pdfPageTexts) : "";
            } else {
                if (documentPagedMode) {
                    saveActiveDocumentPage();
                    cachedText = joinDocumentPages();
                } else {
                    cachedText = wordEdit == null ? cachedText : wordEdit.getText().toString();
                }
                cachedHtml = cachedText.contains("\f") ? textToHtml(cachedText, true) : nativeWordHtml();
                saveText = cachedText;
                saveHtml = cachedHtml;
            }
        } catch (Exception e) { showError("Dokumen belum siap disimpan", e); return; }
        setStatus("Menyimpan " + target.getName() + "…");
        new Thread(() -> {
            try {
                saveToUri(Uri.fromFile(target), type, saveHtml, saveText);
                runOnUiThread(() -> {
                    if (wordEdit != null) wordEdit.removeCallbacks(draftAutosaveRunnable);
                    String previousDraftName = currentName;
                    currentName = target.getName();
                    removeExportedDraft(previousDraftName);
                    removeExportedDraft(currentName);
                    setStatus("Tersimpan: " + target.getAbsolutePath());
                    OfficeUtils.announce(rootLayoutOrStatus(), "Dokumen tersimpan ke " + target.getName());
                    UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE))
                        .setTitle("Dokumen Tersimpan")
                        .setMessage("Berkas berhasil disimpan di:\n" + target.getAbsolutePath())
                        .setNeutralButton("Bagikan Sekarang", (dd, ww) -> OfficeUtils.shareLocalFile(this, target, target.getName()))
                        .show();
                });
            } catch (Exception e) { runOnUiThread(() -> showError("Gagal menyimpan dokumen", e)); }
        }).start();
    }

    private void showDocumentTools() {
        String[] items = {"Cari teks", "Ganti teks", "Hitung kata dan karakter", "Sisipkan tabel", "Sisipkan tautan", "Sisipkan tanggal", "Gaya judul dan paragraf", "Pengaturan editor", "Baca penjelasan format berkas", "Template dokumen siap pakai", "Daftar isi otomatis", "Sisipkan gambar", "Pemisah halaman", "Header dan footer", "Catatan / komentar dokumen", "Tata letak dua kolom", "Mode fokus membaca"};
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Alat dokumen").setItems(items, (d, which) -> {
            if (which == 0) findOrReplace(false);
            else if (which == 1) findOrReplace(true);
            else if (which == 2) countWords();
            else if (which == 3) insertTable();
            else if (which == 4) insertLink();
            else if (which == 5) insertNativeTextAtCursor(new java.text.SimpleDateFormat("dd MMMM yyyy", new Locale("id", "ID")).format(new java.util.Date()));
            else if (which == 6) showStylePicker();
            else if (which == 7) showEditorSettings();
            else if (which == 8) showFormatHelp();
            else if (which == 9) showDocumentTemplates();
            else if (which == 10) insertTableOfContents();
            else if (which == 11) pickImage();
            else if (which == 12) insertPageBreak();
            else if (which == 13) insertHeaderFooter();
            else if (which == 14) insertCommentNote();
            else if (which == 15) toggleColumns();
            else showReadingMode();
        }).show();
    }

    private void showAdvancedWordTools() {
        String[] items = {"Template makalah", "Template surat resmi", "Template CV / daftar riwayat hidup", "Template laporan", "Daftar isi otomatis", "Sisipkan gambar", "Pemisah halaman", "Header / footer", "Catatan dokumen", "Tata letak dua kolom", "Mode fokus membaca", "Pengaturan halaman"};
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Fitur Word lanjutan").setItems(items, (d, which) -> {
            if (which <= 3) applyTemplate(which);
            else if (which == 4) insertTableOfContents();
            else if (which == 5) pickImage();
            else if (which == 6) insertPageBreak();
            else if (which == 7) insertHeaderFooter();
            else if (which == 8) insertCommentNote();
            else if (which == 9) toggleColumns();
            else if (which == 10) showReadingMode();
            else showEditorSettings();
        }).show();
    }

    private static final String[] TEMPLATE_CATEGORIES = {
        "Surat-menyurat", "Akademik dan tugas", "Laporan dan kegiatan",
        "Pekerjaan dan karier", "Bisnis dan administrasi", "Pribadi dan kreatif",
        "Buku dan naskah panjang", "Hukum dan formalitas", "Pendidikan & pelatihan", "Kreatif & hiburan"
    };
    private static final String[][] DOCUMENT_TEMPLATE_NAMES = {
        {"Surat resmi", "Surat permohonan", "Surat izin", "Surat undangan", "Surat pemberitahuan", "Surat keterangan", "Surat pengantar", "Surat tugas", "Surat kuasa", "Surat pernyataan", "Surat keputusan", "Surat edaran", "Surat rekomendasi", "Surat balasan", "Surat pengaduan", "Surat ucapan terima kasih", "Surat permintaan maaf", "Surat penawaran", "Surat pemesanan", "Surat penagihan", "Surat lamaran kerja", "Surat pengunduran diri", "Surat cuti", "Surat dispensasi", "Surat keterangan domisili", "Surat keterangan sehat", "Surat keterangan kerja", "Surat perjanjian sewa", "Surat peringatan", "Surat undangan rapat"},
        {"Makalah akademik", "Esai argumentatif", "Esai deskriptif", "PR / Tugas harian sederhana", "Tugas sekolah formal", "Tugas proyek sekolah", "Tugas kelompok sekolah", "Tugas kuliah", "Artikel ilmiah", "Proposal penelitian", "Laporan penelitian", "Ringkasan buku", "Resensi buku", "Resensi film", "Laporan praktikum", "Laporan observasi", "Laporan wawancara", "Daftar pustaka", "Rencana pembelajaran", "Skripsi bab 1", "Skripsi bab 2", "Skripsi bab 3", "Thesis abstract", "Paper conference", "Review jurnal", "Karya tulis ilmiah", "Laporan studi kasus", "Lembar kerja siswa (LKS)", "Soal latihan + kunci"},
        {"Laporan kegiatan", "Laporan pertanggungjawaban", "Proposal kegiatan", "Proposal acara", "Notulen rapat", "Agenda rapat", "Berita acara", "Laporan bulanan", "Laporan mingguan", "Laporan tahunan", "Laporan evaluasi", "Laporan keuangan sederhana", "Rencana kerja", "Jadwal kegiatan", "Laporan harian", "Laporan progress", "Laporan insiden", "Laporan kunjungan", "Tor / term of reference", "Laporan akhir proyek"},
        {"CV / daftar riwayat hidup", "Profil profesional", "Portofolio", "Surat lamaran kerja", "Email lamaran kerja", "Surat pengunduran diri", "Surat rekomendasi kerja", "Rencana karier", "Deskripsi pekerjaan", "Evaluasi kinerja", "Cover letter", "Curriculum vitae internasional", "Surat referensi", "Personal statement", "Motivation letter", "Biodata pelamar"},
        {"Rencana bisnis", "Proposal usaha", "Profil perusahaan", "Penawaran kerja sama", "Kontrak sederhana", "Invoice / tagihan", "Kuitansi", "Memo internal", "SOP / prosedur kerja", "Pengumuman kantor", "Formulir pendaftaran", "Daftar inventaris", "Business plan singkat", "Pitch deck teks", "Kontrak kerja", "NDA sederhana", "Purchase order", "Surat penawaran harga", "Laporan penjualan", "Rencana pemasaran"},
        {"Autobiografi", "Biodata", "Surat pribadi", "Undangan acara keluarga", "Ucapan terima kasih", "Pidato sambutan", "Naskah presentasi", "Cerita pendek", "Rencana belajar", "Rencana proyek pribadi", "Jurnal harian", "Catatan perjalanan", "Doa / doa pembuka", "Ucapan selamat", "Kartu ucapan", "Naskah MC", "Puisi", "Cerpen pendek"},
        {"Naskah novel (halaman 1)", "Naskah novel lanjut", "Bab buku nonfiksi", "Daftar isi buku", "Prakata / kata pengantar buku", "Pendahuluan buku", "Bab 1 buku", "Bab 2 buku", "Bab 3 buku", "Kesimpulan buku", "Daftar pustaka buku", "Sinopsis novel", "Outline buku / kerangka", "Naskah skenario", "Naskah drama", "Komik script", "Buku harian panjang", "Serial cerita", "Epilog", "Catatan kaki & referensi bab"},
        {"Surat kuasa notaris", "Surat perjanjian", "Surat pernyataan sumpah", "Surat keterangan ahli waris", "Berita acara serah terima", "Surat gugatan sederhana", "Surat jawaban", "Surat somasi", "Kontrak sewa rumah", "Perjanjian pinjam-meminjam", "Surat wasiat sederhana", "Akta di bawah tangan"},
        {"Modul pelatihan", "Handout materi", "Rencana pelaksanaan pembelajaran (RPP)", "Silabus singkat", "Lembar kerja siswa", "Soal ujian", "Kunci jawaban", "Rubrik penilaian", "Laporan praktik mengajar", "Catatan mentoring", "Materi workshop", "Panduan tutor"},
        {"Naskah stand-up", "Script podcast", "Script YouTube", "Review produk", "Caption panjang IG", "Thread Twitter/X", "Cerita fanfic", "Lirik lagu", "Naskah iklan", "Press release", "Blog post", "Newsletter"}
    };

    private void showDocumentTemplates() {
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Kategori template Word")
            .setItems(TEMPLATE_CATEGORIES, (dialog, category) -> showTemplateList(category))
            .setNegativeButton("Batal", null).show();
    }

    private void showTemplateList(int category) {
        if (category < 0 || category >= DOCUMENT_TEMPLATE_NAMES.length) return;
        String[] names = DOCUMENT_TEMPLATE_NAMES[category];
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Template: " + TEMPLATE_CATEGORIES[category])
            .setItems(names, (dialog, which) -> showTemplateForm(TEMPLATE_CATEGORIES[category], names[which]))
            .setNegativeButton("Kembali", (dialog, which) -> showDocumentTemplates()).show();
    }

    /** Formulir aksesibel: pengguna mengisi kolom terpisah, lalu dokumen langsung dibuat. */



    private void showTemplateForm(String category, String templateName) {
        final String tn = templateName.toLowerCase(Locale.ROOT);
        final String[] labels;
        final String[] hints;
        final String formType;

        if (tn.contains("pr / tugas harian") || tn.contains("tugas harian sederhana")) {
            formType = "pr_sederhana";
            labels = new String[]{"Nama siswa", "Kelas", "Mata pelajaran", "Tanggal (opsional)", "Isi tugas / jawaban"};
            hints = new String[]{"contoh: Anugerah Anas", "contoh: VIII-A / XII IPA 2", "contoh: Matematika / Bahasa Indonesia / IPA", "contoh: 3 November 2026 (boleh dikosongkan)", "contoh: 1. Jawaban soal nomor 1 ...\n2. Jawaban soal nomor 2 ...\n\nAtau langsung tulis apa yang diminta guru."};
        } else if (tn.contains("tugas sekolah formal")) {
            formType = "tugas_sekolah_formal";
            labels = new String[]{"Nama siswa", "Kelas", "Nomor absen / NIS (opsional)", "Mata pelajaran", "Nama guru", "Jenis tugas", "Tanggal diberikan", "Tanggal dikumpulkan", "Soal / perintah guru", "Jawaban / pengerjaan"};
            hints = new String[]{"contoh: Anugerah Anas", "contoh: IX-B", "contoh: 12", "contoh: IPA / Sejarah", "contoh: Ibu Siti Aminah, S.Pd.", "contoh: Tugas individu / PR mingguan", "contoh: 1 November 2026", "contoh: 5 November 2026", "contoh: 1. Jelaskan ...\n2. Hitung ...", "contoh: 1. ...\n2. ..."};
        } else if (tn.contains("tugas proyek sekolah") || tn.contains("tugas kelompok sekolah")) {
            formType = "tugas_proyek_sekolah";
            labels = new String[]{"Judul proyek / tugas", "Mata pelajaran", "Nama guru", "Kelas", "Nama anggota kelompok (satu baris per orang)", "Tanggal mulai", "Tanggal dikumpulkan", "Tujuan proyek", "Langkah pengerjaan / hasil", "Pembagian tugas (opsional)", "Kesimpulan / refleksi"};
            hints = new String[]{"contoh: Membuat Poster Hemat Energi", "contoh: IPA", "contoh: Bapak Andi, S.Pd.", "contoh: VIII-A", "contoh: Anugerah Anas\nBudi Santoso\nSiti Nurhaliza", "contoh: 20 Oktober 2026", "contoh: 3 November 2026", "contoh: Meningkatkan kesadaran hemat energi", "contoh: 1. Riset\n2. Desain\n3. Presentasi", "contoh: Anas: desain\nBudi: materi", "contoh: Proyek berjalan lancar."};
        } else if (tn.contains("lembar kerja siswa") || tn.contains("lks") || tn.contains("soal latihan")) {
            formType = "lks";
            labels = new String[]{"Judul LKS / Latihan", "Mata pelajaran", "Kelas", "Nama guru (opsional)", "Petunjuk pengerjaan", "Soal-soal", "Ruang jawaban / kunci (opsional)"};
            hints = new String[]{"contoh: Latihan Soal Bilangan Bulat", "contoh: Matematika", "contoh: VII", "contoh: Ibu Rina", "contoh: Kerjakan dengan teliti. Waktu 30 menit.", "contoh: 1. Hasil dari −15 + 8 adalah ...\n2. ...", "contoh: (kosongkan untuk siswa, atau isi kunci untuk guru)"};
        } else if (tn.equals("tugas kuliah") || tn.contains("tugas kuliah")) {
            formType = "tugas_kuliah";
            labels = new String[]{"Mata kuliah", "Kode MK (opsional)", "Nama dosen", "Nama mahasiswa", "NIM", "Program studi / semester", "Jenis tugas", "Judul / topik", "Tanggal dikumpulkan", "Uraian / jawaban tugas", "Daftar pustaka singkat"};
            hints = new String[]{"contoh: Algoritma dan Pemrograman", "contoh: IF2103", "contoh: Dr. Budi Santoso, M.Kom.", "contoh: Anugerah Anas", "contoh: 2101234567", "contoh: Teknik Informatika / Semester 3", "contoh: Tugas individu", "contoh: Implementasi Binary Search", "contoh: 10 November 2026", "contoh: Penjelasan + pseudocode + kode ...", "contoh: Cormen et al."};
        } else if (tn.contains("makalah") || tn.contains("karya tulis ilmiah") || tn.contains("artikel ilmiah")) {
            formType = "makalah";
            labels = new String[]{"Judul Makalah", "Nama Penulis", "NIM / Nomor Induk", "Kelas / Semester / Prodi", "Instansi / Universitas", "Nama Dosen Pembimbing", "Kota dan Tanggal", "Kata Pengantar", "Abstrak (opsional)", "Latar Belakang", "Rumusan Masalah", "Tujuan Penulisan", "Manfaat Penulisan", "Tinjauan Pustaka / Landasan Teori", "Metodologi (opsional)", "Pembahasan", "Kesimpulan", "Saran", "Daftar Pustaka", "Lampiran (opsional)"};
            hints = new String[]{"contoh: Pengaruh Media Sosial terhadap Prestasi Belajar Mahasiswa", "contoh: Anugerah Anas", "contoh: 2101234567", "contoh: Semester 5 / Teknik Informatika", "contoh: Universitas Syiah Kuala", "contoh: Dr. Budi Santoso, M.Kom.", "contoh: Banda Aceh, 3 November 2026", "contoh: Puji syukur ...", "contoh: Makalah ini membahas ...", "contoh: Di era digital ...", "contoh: 1. Bagaimana pengaruh ...?", "contoh: Menjelaskan pengaruh ...", "contoh: Bagi mahasiswa, dosen ...", "contoh: Teori Uses and Gratifications ...", "contoh: Studi literatur ...", "contoh: Sisi positif dan negatif ...", "contoh: Pengaruh signifikan ...", "contoh: Batasi waktu hiburan ...", "contoh: Katz et al. (1974). ...", "contoh: Lampiran 1: Kuesioner"};
        } else if (tn.contains("skripsi bab 1")) {
            formType = "skripsi_bab1";
            labels = new String[]{"Judul Skripsi", "Nama Mahasiswa", "NIM", "Program Studi", "Dosen Pembimbing 1", "Dosen Pembimbing 2 (opsional)", "Latar Belakang Masalah", "Identifikasi Masalah", "Batasan Masalah", "Rumusan Masalah", "Tujuan Penelitian", "Manfaat Penelitian", "Sistematika Penulisan"};
            hints = new String[]{"contoh: Pengembangan Keyboard AI ...", "contoh: Anugerah Anas", "contoh: 2101234567", "contoh: Teknik Informatika", "contoh: Dr. Budi Santoso, M.Kom.", "contoh: Dr. Siti Aminah, M.T.", "contoh: Mahasiswa tunanetra mengalami kesulitan ...", "contoh: 1. Keterbatasan keyboard fisik\n2. Minim fitur Braille", "contoh: Dibatasi pada Android dan bahasa Indonesia.", "contoh: 1. Bagaimana merancang ...?", "contoh: Menghasilkan prototype ...", "contoh: Teoritis dan praktis ...", "contoh: Bab I ... Bab V ..."};
        } else if (tn.contains("skripsi bab 2")) {
            formType = "skripsi_bab2";
            labels = new String[]{"Judul Skripsi (singkat)", "Landasan Teori utama", "Penelitian Terdahulu 1", "Penelitian Terdahulu 2", "Penelitian Terdahulu 3 (opsional)", "Kerangka Pemikiran", "Hipotesis (jika ada)"};
            hints = new String[]{"contoh: Keyboard AI untuk Tunanetra", "contoh: Accessibility, Braille, NLP", "contoh: Nama (tahun). Hasil: ... Gap: ...", "contoh: Nama (tahun). Hasil: ...", "contoh: Opsional", "contoh: Keyboard Braille + AI → kecepatan & kepuasan naik", "contoh: H1: ..."};
        } else if (tn.contains("skripsi bab 3")) {
            formType = "skripsi_bab3";
            labels = new String[]{"Jenis penelitian", "Metode pengumpulan data", "Populasi & sampel", "Instrumen penelitian", "Teknik analisis data", "Tahapan penelitian", "Jadwal (opsional)"};
            hints = new String[]{"contoh: R&D prototyping", "contoh: Observasi, wawancara, SUS", "contoh: Mahasiswa tunanetra, sampel 10 orang", "contoh: Prototype, kuesioner SUS", "contoh: Deskriptif kuantitatif + kualitatif", "contoh: Analisis → Desain → Implementasi → Testing", "contoh: Agustus–Desember 2026"};
        } else if (tn.contains("outline buku") || tn.contains("kerangka")) {
            formType = "outline_buku";
            labels = new String[]{"Judul buku sementara", "Genre / jenis", "Target pembaca", "Sinopsis singkat", "Daftar bab (satu baris per bab)", "Catatan alur / tema", "Estimasi halaman / kata"};
            hints = new String[]{"contoh: Keyboard yang Mendengar", "contoh: Nonfiksi teknologi", "contoh: Mahasiswa & praktisi aksesibilitas", "contoh: Kisah pengembangan keyboard AI untuk tunanetra di Aceh...", "contoh: Bab 1: Awal mula\nBab 2: Riset pengguna\nBab 3: Prototipe\nBab 4: Kegagalan\nBab 5: Rilis", "contoh: Tema: teknologi yang manusiawi", "contoh: ±200 halaman / 50.000 kata"};
        } else if (tn.contains("bab buku") || tn.contains("bab 1 buku") || tn.contains("bab 2 buku") || tn.contains("bab 3 buku") || tn.contains("naskah novel") || tn.contains("pendahuluan buku") || tn.contains("prakata") || tn.contains("epilog") || tn.contains("kesimpulan buku")) {
            formType = "bab_buku";
            labels = new String[]{"Judul buku", "Nomor & judul bab", "Nama penulis", "Isi bab (boleh sangat panjang)"};
            hints = new String[]{"contoh: Keyboard yang Mendengar", "contoh: Bab 3 – Prototipe Pertama", "contoh: Anugerah Anas", "contoh: Tulis isi bab di sini. Bisa panjang. Gabungkan beberapa dokumen kalau perlu ratusan halaman."};
        } else if (tn.contains("undangan rapat")) {
            formType = "surat_undangan_rapat";
            labels = new String[]{"Nama instansi", "Alamat", "Nomor surat", "Perihal", "Kota & tanggal", "Kepada", "Hari/tanggal rapat", "Waktu", "Tempat", "Agenda", "Catatan", "Nama & jabatan"};
            hints = new String[]{"contoh: HMIF Unsyiah", "contoh: Sekretariat HMIF", "contoh: 12/HMIF/XI/2026", "contoh: Undangan Rapat", "contoh: Banda Aceh, 3 November 2026", "contoh: Seluruh anggota", "contoh: Senin, 10 November 2026", "contoh: 14.00 WIB", "contoh: Ruang Seminar", "contoh: 1. Evaluasi\n2. Jobdesk", "contoh: Harap hadir tepat waktu", "contoh: Anugerah Anas – Ketua"};
        } else if (tn.contains("undangan")) {
            formType = "surat_undangan";
            labels = new String[]{"Nama pengirim", "Alamat", "Nomor (opsional)", "Perihal", "Kota & tanggal", "Kepada", "Hari/tanggal acara", "Waktu", "Tempat", "Acara", "Catatan", "Nama & jabatan"};
            hints = new String[]{"contoh: Panitia Wisuda", "contoh: Kampus Unsyiah", "contoh: 08/PNT/XI/2026", "contoh: Undangan Wisuda", "contoh: Banda Aceh, 1 November 2026", "contoh: Bapak/Ibu Orangtua", "contoh: Sabtu, 15 November 2026", "contoh: 08.00 WIB", "contoh: Aula Utama", "contoh: Prosesi Wisuda", "contoh: Busana formal", "contoh: Anugerah Anas – Ketua"};
        } else if (tn.contains("lamaran")) {
            formType = "surat_lamaran";
            labels = new String[]{"Nama pelamar", "Alamat", "HP", "Email", "Kota & tanggal", "Kepada HRD", "Perusahaan", "Posisi", "Sumber lowongan", "Isi surat", "Lampiran", "Penutup"};
            hints = new String[]{"contoh: Anugerah Anas", "contoh: Jl. Teuku Umar 45", "contoh: 0812-3456-7890", "contoh: anugerah@email.com", "contoh: Banda Aceh, 3 November 2026", "contoh: Bapak/Ibu HRD", "contoh: PT Teknologi Nusantara", "contoh: Junior Android Developer", "contoh: LinkedIn", "contoh: Dengan hormat, saya mengajukan diri karena ...", "contoh: CV, Ijazah, Transkrip", "contoh: Saya berharap dapat mengikuti proses selanjutnya."};
        } else if (tn.contains("izin") || tn.contains("cuti") || tn.contains("dispensasi")) {
            formType = "surat_izin";
            labels = new String[]{"Nama", "Kelas / jabatan / NIS", "Alamat", "Kota & tanggal", "Kepada", "Instansi", "Jenis izin", "Dari tanggal", "Sampai tanggal", "Alasan", "Lampiran", "Tanda tangan orang tua (opsional)"};
            hints = new String[]{"contoh: Anugerah Anas", "contoh: XII IPA 1", "contoh: Jl. Merdeka 12", "contoh: Banda Aceh, 3 November 2026", "contoh: Bapak Kepala Sekolah", "contoh: SMAN 1 Banda Aceh", "contoh: Izin sakit", "contoh: 3 November 2026", "contoh: 5 November 2026", "contoh: Demam tinggi", "contoh: Surat dokter terlampir", "contoh: Orang tua: Muhammad Yusuf"};
        } else if (category.equals("Surat-menyurat") || tn.contains("surat")) {
            formType = "surat_umum";
            labels = new String[]{"Nama instansi / pengirim", "Alamat pengirim", "Nomor surat (opsional)", "Lampiran (opsional)", "Perihal", "Kota dan tanggal", "Nama penerima", "Jabatan penerima", "Alamat tujuan", "Isi utama", "Penutup", "Nama penanda tangan", "Jabatan"};
            hints = new String[]{"contoh: Universitas Syiah Kuala", "contoh: Jl. Teuku Nyak Arief", "contoh: 123/SK/XI/2026", "contoh: 1 berkas", "contoh: Perihal", "contoh: Banda Aceh, 3 November 2026", "contoh: Bapak/Ibu ...", "contoh: Jabatan", "contoh: Alamat", "contoh: Isi surat ...", "contoh: Demikian, terima kasih.", "contoh: Anugerah Anas", "contoh: Ketua"};
        } else if (tn.contains("cv") || tn.contains("daftar riwayat") || tn.contains("curriculum") || tn.contains("biodata pelamar")) {
            formType = "cv";
            labels = new String[]{"Nama lengkap", "Tempat, tanggal lahir", "Alamat", "No. HP", "Email", "LinkedIn / portfolio (opsional)", "Profil singkat", "Pendidikan", "Pengalaman kerja / magang", "Organisasi", "Keterampilan teknis", "Keterampilan non-teknis", "Prestasi & sertifikat", "Bahasa", "Referensi (opsional)"};
            hints = new String[]{"contoh: Anugerah Anas", "contoh: Banda Aceh, 12 Januari 2003", "contoh: Jl. Merdeka 10", "contoh: 0812-3456-7890", "contoh: anugerah@email.com", "contoh: linkedin.com/in/...", "contoh: Lulusan Teknik Informatika fokus Android & AI", "contoh: S1 Teknik Informatika – Unsyiah (2021–2025), IPK 3.75", "contoh: Magang Android Developer (2024)", "contoh: Ketua HMIF 2023/2024", "contoh: Java, Kotlin, Android, Python", "contoh: Leadership, public speaking", "contoh: Juara 1 Hackathon Aceh 2024", "contoh: Indonesia (native), English", "contoh: Dr. Budi Santoso"};
        } else if (tn.contains("notulen")) {
            formType = "notulen";
            labels = new String[]{"Judul rapat", "Hari/tanggal", "Waktu", "Tempat", "Pemimpin rapat", "Notulis", "Peserta", "Agenda", "Pembahasan & keputusan", "Tindak lanjut"};
            hints = new String[]{"contoh: Rapat Koordinasi", "contoh: Senin, 3 November 2026", "contoh: 14.00–16.00", "contoh: Ruang Rapat", "contoh: Anugerah Anas", "contoh: Budi", "contoh: 15 orang", "contoh: 1. Evaluasi\n2. Jobdesk", "contoh: Disepakati ...", "contoh: Laporkan progress tiap Jumat"};
        } else if (tn.contains("laporan kegiatan") || tn.contains("laporan pertanggungjawaban") || tn.contains("laporan akhir")) {
            formType = "laporan_kegiatan";
            labels = new String[]{"Judul kegiatan", "Penyelenggara", "Penanggung jawab", "Tanggal", "Waktu & tempat", "Latar belakang", "Tujuan", "Peserta", "Rangkaian acara", "Hasil", "Kendala", "Evaluasi", "Kesimpulan", "Tindak lanjut", "Anggaran ringkas"};
            hints = new String[]{"contoh: Seminar Nasional Teknologi", "contoh: HMIF", "contoh: Anugerah Anas", "contoh: 15 Oktober 2026", "contoh: 08.00–16.00, Aula", "contoh: ...", "contoh: Meningkatkan wawasan AI", "contoh: 120 peserta", "contoh: Pembukaan – Keynote – Panel", "contoh: Materi tersampaikan", "contoh: Sound system kurang", "contoh: Berhasil", "contoh: Tujuan tercapai", "contoh: Modul dibagikan", "contoh: Rp 15.000.000"};
        } else if (tn.contains("invoice") || tn.contains("tagihan") || tn.contains("kuitansi")) {
            formType = "invoice";
            labels = new String[]{"Nama usaha", "Alamat usaha", "Nomor invoice", "Tanggal", "Nama klien", "Alamat klien", "Rincian item & harga", "Subtotal", "Pajak / diskon (opsional)", "Total", "Metode pembayaran", "Catatan / jatuh tempo"};
            hints = new String[]{"contoh: CV Anugerah Digital", "contoh: Banda Aceh", "contoh: INV/2026/11/001", "contoh: 3 November 2026", "contoh: PT Klien", "contoh: Jakarta", "contoh: Jasa development – Rp 25.000.000", "contoh: 25.000.000", "contoh: PPN 11%", "contoh: 27.750.000", "contoh: Transfer BCA", "contoh: Jatuh tempo 17 November 2026"};
        } else {
            formType = "generik";
            labels = new String[]{"Judul dokumen", "Nama / identitas", "Tanggal", "Konteks", "Tujuan", "Pembuka", "Isi utama", "Poin penting", "Penutup", "Catatan tambahan"};
            hints = new String[]{"contoh: Judul", "contoh: Anugerah Anas", "contoh: 3 November 2026", "contoh: Kepada pihak terkait", "contoh: Tujuan", "contoh: Pembuka", "contoh: Isi utama", "contoh: - Poin 1", "contoh: Penutup", "contoh: Catatan"};
        }

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(18, 8, 18, 8);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);

        TextView info = new TextView(this);
        String tip = "Template: " + templateName + "\n\n";
        if ("pr_sederhana".equals(formType)) tip += "Form PR paling simpel. Nama, kelas, mapel, tanggal opsional, langsung isi jawaban. Nggak ribet.";
        else if ("tugas_sekolah_formal".equals(formType)) tip += "Versi formal: ada guru, nomor absen, tanggal, soal + jawaban terpisah.";
        else if ("tugas_proyek_sekolah".equals(formType)) tip += "Untuk proyek / kelompok. Bisa isi anggota dan pembagian tugas.";
        else if ("bab_buku".equals(formType) || "outline_buku".equals(formType)) tip += "Untuk buku / novel. Isi bab boleh panjang sekali (ratusan halaman digabung dari beberapa dokumen).";
        else tip += "Field opsional boleh dikosongkan. Ganti contoh dengan data lo.";
        info.setText(tip + "\n\nAnda dapat menyusun dokumen ini dengan 2 cara: otomatis memakai AI (klik tombol AI di bawah) atau mengisi kolom secara manual tanpa AI (100% offline).");
        info.setTextSize(13);
        info.setPadding(0, 0, 0, dp(8));
        form.addView(info);

        final AlertDialog[] tplDlgRef = new AlertDialog[1];
        Button btnAiFillTemplate = UiKit.makeMenuButton(this,
                "Buat & Susun Otomatis \"" + templateName + "\" dengan AI",
                "Cukup tulis judul, topik, atau nama singkat — AI langsung membuatkan naskah lengkap sesuai struktur template ini.",
                UiKit.ACCENT_AI, OfficeUtils.mainPrefs(this), v -> {
                    if (!AuthManager.isPremiumActive(this)) {
                        AuthManager.showPremiumRequiredDialog(this, "AI Template Word Pro", null);
                        return;
                    }
                    final EditText etPrompt = new EditText(this);
                    etPrompt.setHint("Tulis topik, judul, nama pengirim/penerima, atau instruksi untuk " + templateName + "...");
                    etPrompt.setMinLines(3);
                    UiKit.dialog(this, OfficeUtils.mainPrefs(this))
                            .setTitle("Susun " + templateName + " dengan AI")
                            .setView(etPrompt)
                            .setNegativeButton("Batal", null)
                            .setPositiveButton("Buat dengan AI Sekarang", (dAi, wAi) -> {
                                String userTopic = etPrompt.getText().toString().trim();
                                if (userTopic.isEmpty()) userTopic = "Buatkan contoh lengkap yang rapi dan formal.";
                                StringBuilder fieldList = new StringBuilder();
                                for (int k = 0; k < labels.length; k++) {
                                    if (k > 0) fieldList.append(", ");
                                    fieldList.append(labels[k]);
                                }
                                String sys = "Kamu penulis dokumen profesional berbahasa Indonesia. Susun dokumen '"
                                        + templateName + "' (kategori " + category + ") secara lengkap, rapi, dan siap cetak mengikuti struktur bagian: "
                                        + fieldList + ". Keluarkan HANYA isi dokumen polos tanpa sintaks Markdown (jangan pakai **, ###, *, atau ```) dan tanpa kalimat basa-basi pembuka/penutup."
                                        + OfficeAiHelper.buildRealtimeDateInstruction();
                                final AlertDialog wait = UiKit.dialog(this, OfficeUtils.mainPrefs(this))
                                        .setTitle("Menyusun " + templateName + "...")
                                        .setMessage("AI sedang menulis dokumen lengkap...")
                                        .setCancelable(false)
                                        .create();
                                wait.show();
                                AiEngine.singlePrompt(this, OfficeUtils.mainPrefs(this), sys,
                                        "Topik / Data Pengguna:\n" + userTopic,
                                        new AiEngine.TextCallback() {
                                            @Override public void onSuccess(String rawText) {
                                                try { wait.dismiss(); } catch (Throwable ignored) {}
                                                if (isFinishing()) return;
                                                String clean = OfficeAiHelper.cleanWordDocumentOutput(rawText);
                                                if (tplDlgRef[0] != null && tplDlgRef[0].isShowing()) tplDlgRef[0].dismiss();
                                                currentName = templateName.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
                                                setWordEditorContent(clean);
                                                wordEditDirty = true;
                                                scheduleDraftAutosave();
                                                setStatus("Dokumen \"" + templateName + "\" berhasil disusun dengan AI.");
                                                Toast.makeText(DocumentStudioActivity.this, "Dokumen AI siap di editor Word!", Toast.LENGTH_SHORT).show();
                                            }
                                            @Override public void onError(String err) {
                                                try { wait.dismiss(); } catch (Throwable ignored) {}
                                                if (isFinishing()) return;
                                                UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this))
                                                        .setTitle("Gagal Menyusun dengan AI")
                                                        .setMessage(err)
                                                        .show();
                                            }
                                        });
                            })
                            .show();
                });
        form.addView(btnAiFillTemplate);

        final EditText[] inputs = new EditText[labels.length];
        Locale idLoc = new Locale("id", "ID");
        java.util.Date nowDate = new java.util.Date();
        String todayHariTanggal = new java.text.SimpleDateFormat("EEEE, dd MMMM yyyy", idLoc).format(nowDate);
        String todayTanggal = new java.text.SimpleDateFormat("dd MMMM yyyy", idLoc).format(nowDate);
        String todayTahun = new java.text.SimpleDateFormat("yyyy", idLoc).format(nowDate);
        for (int i = 0; i < labels.length; i++) {
            TextView lbl = new TextView(this);
            lbl.setText((i + 1) + ". " + labels[i]);
            lbl.setTextSize(13);
            lbl.setTypeface(Typeface.DEFAULT_BOLD);
            lbl.setPadding(0, dp(10), 0, dp(2));
            form.addView(lbl);
            EditText et = new EditText(this);
            et.setHint(hints[i]);
            String lowerLbl = labels[i].toLowerCase(Locale.ROOT);
            if (lowerLbl.contains("hari") && lowerLbl.contains("tanggal")) {
                et.setText(todayHariTanggal);
            } else if (lowerLbl.contains("tanggal") && !lowerLbl.contains("lahir")) {
                et.setText(todayTanggal);
            } else if (lowerLbl.contains("tahun") && !lowerLbl.contains("ajaran")) {
                et.setText(todayTahun);
            }
            boolean multi = lowerLbl.matches(".*(isi|jawaban|pembahasan|latar|kata pengantar|abstrak|tinjauan|metodologi|daftar pustaka|lampiran|soal|prosedur|hasil|analisis|argumen|rincian|langkah|agenda|keputusan|pengalaman|keterampilan|prestasi|bab|sinopsis|outline|kerangka).*");
            et.setMinLines(multi ? 4 : 1);
            et.setMaxLines(30);
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            et.setContentDescription(labels[i]);
            form.addView(et);
            inputs[i] = et;
        }

        AlertDialog dialog = UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Isi template: " + templateName)
                .setView(scroll)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Buat dokumen", null)
                .create();
        tplDlgRef[0] = dialog;
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            StringBuilder html = new StringBuilder();
            html.append("<div style='font-family:\"Times New Roman\",serif;font-size:12pt;line-height:1.55;max-width:794px;margin:0 auto;padding:28px 32px;color:#111'>");

            if ("pr_sederhana".equals(formType)) {
                html.append("<p style='margin:0 0 2px 0'><b>Nama:</b> ").append(val(inputs,0)).append("</p>");
                html.append("<p style='margin:0 0 2px 0'><b>Kelas:</b> ").append(val(inputs,1)).append("</p>");
                html.append("<p style='margin:0 0 2px 0'><b>Mata Pelajaran:</b> ").append(val(inputs,2)).append("</p>");
                if (!value(inputs,3).isEmpty()) html.append("<p style='margin:0 0 12px 0'><b>Tanggal:</b> ").append(val(inputs,3)).append("</p>");
                else html.append("<p style='margin:0 0 12px 0'></p>");
                html.append("<hr style='margin:8px 0 16px 0'>");
                html.append("<p style='text-align:justify;white-space:pre-wrap'>").append(val(inputs,4)).append("</p>");
            } else if ("tugas_sekolah_formal".equals(formType)) {
                html.append("<h2 style='text-align:center;margin-bottom:12px'>TUGAS SEKOLAH</h2>");
                html.append("<table style='width:100%;font-size:11pt;margin-bottom:14px'>");
                html.append("<tr><td style='width:38%'><b>Nama</b></td><td>: ").append(val(inputs,0)).append("</td></tr>");
                html.append("<tr><td><b>Kelas</b></td><td>: ").append(val(inputs,1)).append("</td></tr>");
                if (!value(inputs,2).isEmpty()) html.append("<tr><td><b>No. Absen / NIS</b></td><td>: ").append(val(inputs,2)).append("</td></tr>");
                html.append("<tr><td><b>Mata Pelajaran</b></td><td>: ").append(val(inputs,3)).append("</td></tr>");
                html.append("<tr><td><b>Guru</b></td><td>: ").append(val(inputs,4)).append("</td></tr>");
                html.append("<tr><td><b>Jenis Tugas</b></td><td>: ").append(val(inputs,5)).append("</td></tr>");
                html.append("<tr><td><b>Diberikan</b></td><td>: ").append(val(inputs,6)).append("</td></tr>");
                html.append("<tr><td><b>Dikumpulkan</b></td><td>: ").append(val(inputs,7)).append("</td></tr>");
                html.append("</table><hr>");
                html.append("<h3>Soal / Perintah</h3><p style='white-space:pre-wrap'>").append(val(inputs,8)).append("</p>");
                html.append("<h3>Jawaban</h3><p style='white-space:pre-wrap'>").append(val(inputs,9)).append("</p>");
            } else if ("bab_buku".equals(formType)) {
                html.append("<p style='text-align:center;color:#555;font-size:11pt;margin-bottom:4px'>").append(val(inputs,0)).append("</p>");
                html.append("<h1 style='text-align:center;font-size:16pt;margin-top:8px'>").append(val(inputs,1)).append("</h1>");
                if (!value(inputs,2).isEmpty()) html.append("<p style='text-align:center;font-size:11pt;color:#555'>").append(val(inputs,2)).append("</p>");
                html.append("<hr style='margin:20px 0'>");
                html.append("<div style='text-align:justify;white-space:pre-wrap;line-height:1.7'>").append(val(inputs,3)).append("</div>");
            } else if ("outline_buku".equals(formType)) {
                html.append("<h1 style='text-align:center'>OUTLINE / KERANGKA BUKU</h1>");
                html.append("<p><b>Judul:</b> ").append(val(inputs,0)).append("<br><b>Genre:</b> ").append(val(inputs,1));
                html.append("<br><b>Target pembaca:</b> ").append(val(inputs,2)).append("</p>");
                html.append("<h3>Sinopsis</h3><p style='text-align:justify'>").append(val(inputs,3)).append("</p>");
                html.append("<h3>Daftar Bab</h3><p style='white-space:pre-wrap'>").append(val(inputs,4)).append("</p>");
                if (!value(inputs,5).isEmpty()) html.append("<h3>Catatan alur / tema</h3><p>").append(val(inputs,5)).append("</p>");
                if (!value(inputs,6).isEmpty()) html.append("<p><b>Estimasi:</b> ").append(val(inputs,6)).append("</p>");
            } else if ("makalah".equals(formType)) {
                html.append("<div style='text-align:center;padding-top:40px;margin-bottom:40px'>");
                html.append("<p style='font-size:14pt;font-weight:bold;text-transform:uppercase'>").append(val(inputs,0)).append("</p>");
                html.append("<p style='margin-top:24px'>Disusun oleh:</p><p style='font-weight:bold'>").append(val(inputs,1)).append("</p>");
                if (!value(inputs,2).isEmpty()) html.append("<p>NIM: ").append(val(inputs,2)).append("</p>");
                if (!value(inputs,3).isEmpty()) html.append("<p>").append(val(inputs,3)).append("</p>");
                html.append("<p style='margin-top:16px'>").append(val(inputs,4)).append("</p>");
                if (!value(inputs,5).isEmpty()) html.append("<p>Dosen Pembimbing: ").append(val(inputs,5)).append("</p>");
                html.append("<p style='margin-top:36px'>").append(val(inputs,6)).append("</p></div>");
                if (!value(inputs,7).isEmpty()) html.append("<h2 style='text-align:center'>KATA PENGANTAR</h2><p style='text-align:justify'>").append(val(inputs,7)).append("</p>");
                if (!value(inputs,8).isEmpty()) html.append("<h2 style='text-align:center'>ABSTRAK</h2><p style='text-align:justify'>").append(val(inputs,8)).append("</p>");
                html.append("<h2 style='text-align:center'>BAB I<br>PENDAHULUAN</h2>");
                if (!value(inputs,9).isEmpty()) html.append("<h3>1.1 Latar Belakang</h3><p style='text-align:justify'>").append(val(inputs,9)).append("</p>");
                if (!value(inputs,10).isEmpty()) html.append("<h3>1.2 Rumusan Masalah</h3><p style='text-align:justify'>").append(val(inputs,10)).append("</p>");
                if (!value(inputs,11).isEmpty()) html.append("<h3>1.3 Tujuan Penulisan</h3><p style='text-align:justify'>").append(val(inputs,11)).append("</p>");
                if (!value(inputs,12).isEmpty()) html.append("<h3>1.4 Manfaat Penulisan</h3><p style='text-align:justify'>").append(val(inputs,12)).append("</p>");
                if (!value(inputs,13).isEmpty()) html.append("<h2 style='text-align:center'>BAB II<br>TINJAUAN PUSTAKA</h2><p style='text-align:justify'>").append(val(inputs,13)).append("</p>");
                if (!value(inputs,14).isEmpty()) html.append("<h2 style='text-align:center'>BAB III<br>METODOLOGI</h2><p style='text-align:justify'>").append(val(inputs,14)).append("</p>");
                if (!value(inputs,15).isEmpty()) html.append("<h2 style='text-align:center'>BAB ").append(value(inputs,14).isEmpty()?"III":"IV").append("<br>PEMBAHASAN</h2><p style='text-align:justify'>").append(val(inputs,15)).append("</p>");
                html.append("<h2 style='text-align:center'>BAB ").append(value(inputs,14).isEmpty()?"IV":"V").append("<br>KESIMPULAN DAN SARAN</h2>");
                if (!value(inputs,16).isEmpty()) html.append("<h3>Kesimpulan</h3><p style='text-align:justify'>").append(val(inputs,16)).append("</p>");
                if (!value(inputs,17).isEmpty()) html.append("<h3>Saran</h3><p style='text-align:justify'>").append(val(inputs,17)).append("</p>");
                html.append("<h2 style='text-align:center'>DAFTAR PUSTAKA</h2><p style='text-align:justify'>").append(value(inputs,18).isEmpty()?"(Tambahkan referensi.)":val(inputs,18)).append("</p>");
                if (!value(inputs,19).isEmpty()) html.append("<h2 style='text-align:center'>LAMPIRAN</h2><p>").append(val(inputs,19)).append("</p>");
            } else if ("cv".equals(formType)) {
                html.append("<h1 style='text-align:center;margin-bottom:4px'>").append(val(inputs,0)).append("</h1>");
                html.append("<p style='text-align:center;font-size:11pt'>").append(val(inputs,1)).append(" | ").append(val(inputs,2)).append("<br>").append(val(inputs,3)).append(" | ").append(val(inputs,4));
                if (!value(inputs,5).isEmpty()) html.append("<br>").append(val(inputs,5));
                html.append("</p><hr>");
                String[] cvHeads = {"Profil", "Pendidikan", "Pengalaman Kerja / Magang", "Organisasi", "Keterampilan Teknis", "Keterampilan Non-teknis", "Prestasi & Sertifikat", "Bahasa", "Referensi"};
                for (int i = 6; i < labels.length; i++) {
                    if (value(inputs,i).isEmpty()) continue;
                    html.append("<h3>").append(cvHeads[i-6]).append("</h3><p style='white-space:pre-wrap'>").append(val(inputs,i)).append("</p>");
                }
            } else {
                for (int i = 0; i < labels.length; i++) {
                    if (value(inputs,i).isEmpty()) continue;
                    html.append("<h3 style='margin-top:14px'>").append(htmlEscape(labels[i])).append("</h3>");
                    html.append("<p style='text-align:justify;white-space:pre-wrap'>").append(val(inputs,i)).append("</p>");
                }
            }

            html.append("</div>");
            cachedHtml = html.toString();
            loadHtml(cachedHtml);
            setStatus("Dokumen \"" + templateName + "\" berhasil dibuat.");
            Toast.makeText(this, "Template " + templateName + " siap!", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        }));
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
    }

    private String value(EditText[] fields, int index) {
        if (index < 0 || index >= fields.length || fields[index] == null || fields[index].getText() == null) return "";
        return fields[index].getText().toString().trim();
    }
    private String val(EditText[] fields, int index) {
        return htmlEscape(value(fields, index)).replace("\n", "<br>");
    }

    private void applyTemplate(int which) {
        String[] legacy = {"Makalah akademik", "Surat resmi", "CV / daftar riwayat hidup", "Laporan kegiatan"};
        int category = which == 1 ? 0 : which == 0 ? 1 : which == 2 ? 3 : 2;
        showTemplateForm(TEMPLATE_CATEGORIES[category], legacy[Math.max(0, Math.min(3, which))]);
    }

    private void insertTableOfContents() {
        if (wordEdit == null) return;
        String[] lines = wordEdit.getText().toString().split("\n");
        StringBuilder toc = new StringBuilder("DAFTAR ISI\n"); int count = 0;
        for (String line : lines) { String t=line.trim(); if (!t.isEmpty() && t.length() <= 120 && (t.matches("(?i)^(BAB|BAB\\s|BAB[ \t]|[IVX]+[.)].*|[0-9]+(\\.[0-9]+)*[.)]?\\s+.*|PENDAHULUAN|PEMBAHASAN|KESIMPULAN|DAFTAR PUSTAKA).*"))) { toc.append(t).append("\n"); count++; } }
        if (count == 0) { Toast.makeText(this, "Belum ditemukan judul. Tulis judul bab terlebih dahulu.", Toast.LENGTH_SHORT).show(); return; }
        insertNativeTextAtCursor(toc.append("\n").toString());
    }

    private void pickImage() { OfficeUtils.launchFilePicker(this, REQ_IMAGE, "image", "Pilih Gambar untuk Dokumen"); }
    private void insertHeaderFooter() {
        android.content.SharedPreferences p = getSharedPreferences("document_studio", MODE_PRIVATE);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(8), dp(14), dp(4));
        EditText header = new EditText(this); header.setHint("Teks Header atas setiap halaman (mis. Nama Instansi / Judul)");
        header.setText(p.getString("doc_header_text", "")); box.addView(header);
        EditText footer = new EditText(this); footer.setHint("Teks Footer bawah setiap halaman (mis. Rahasia / Nama Penulis)");
        footer.setText(p.getString("doc_footer_text", "")); box.addView(footer);
        android.widget.CheckBox cbNum = new android.widget.CheckBox(this);
        cbNum.setText("Tampilkan nomor halaman otomatis di Footer (DOCX & PDF)");
        cbNum.setChecked(p.getBoolean("doc_page_numbers", true));
        box.addView(cbNum);
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Header, Footer & Nomor Halaman")
            .setMessage("Header, footer, dan nomor halaman otomatis berulang di setiap halaman saat diekspor ke DOCX maupun PDF.")
            .setView(box).setNegativeButton("Batal", null)
            .setNeutralButton("Kosongkan", (d, w) -> {
                p.edit().putString("doc_header_text", "").putString("doc_footer_text", "").putBoolean("doc_page_numbers", false).apply();
                setStatus("Header/Footer halaman dinonaktifkan.");
            })
            .setPositiveButton("Terapkan", (d, w) -> {
                String h = header.getText().toString().trim();
                String f = footer.getText().toString().trim();
                p.edit().putString("doc_header_text", h).putString("doc_footer_text", f).putBoolean("doc_page_numbers", cbNum.isChecked()).apply();
                String info = "Header/Footer diterapkan untuk ekspor DOCX & PDF" + (cbNum.isChecked() ? " (dengan nomor halaman)." : ".");
                setStatus(info);
                OfficeUtils.announce(rootLayoutOrStatus(), info);
            }).show();
    }
    private void insertCommentNote() { EditText note=new EditText(this);note.setHint("Tulis komentar atau catatan review");UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Sisipkan Komentar Dokumen").setView(note).setNegativeButton("Batal",null).setPositiveButton("Sisipkan",(d,w)->{String value=note.getText().toString().trim();if(!value.isEmpty()){insertNativeTextAtCursor("[Komentar: "+value+"]\n");}}).show(); }
    private void toggleColumns() {
        android.content.SharedPreferences p = getSharedPreferences("document_studio", MODE_PRIVATE);
        boolean next = !p.getBoolean("two_columns", false);
        p.edit().putBoolean("two_columns", next).apply();
        String msg = next ? "Tata letak 2 KOLOM diaktifkan (aktif saat ekspor DOCX & PDF)." : "Tata letak kembali ke 1 KOLOM standar.";
        setStatus(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        OfficeUtils.announce(rootLayoutOrStatus(), msg);
    }
    /**
     * Mode baca aksesibel untuk teks dokumen dan PDF yang teksnya bisa diekstrak.
     * Tombol ±10 detik menggeser posisi teks secara perkiraan karena Android TTS
     * menghasilkan ucapan langsung, bukan berkas audio dengan durasi yang bisa dicari.
     */
    private void showAccessibleReadingMode() {
        final ArrayList<String> voiceNames = new ArrayList<>();
        final ArrayList<Voice> voices = new ArrayList<>();
        voiceNames.add("Suara bawaan TTS");
        voices.add(null);
        final ArrayAdapter<String>[] voiceAdapterHolder = new ArrayAdapter[1];
        if (readingTts == null) {
            android.content.SharedPreferences ttsPrefs = getSharedPreferences("VoiceInput_Settings", MODE_PRIVATE);
            String selectedEngine = ttsPrefs.getString("document_tts_engine", null);
            if ("ANAS_AI_SUPERTONIC".equals(selectedEngine)) {
                selectedEngine = "";
                ttsPrefs.edit().putString("document_tts_engine", "").putString("document_tts_engine_name", "Default Sistem Android").apply();
            }
            TextToSpeech.OnInitListener initListener = statusCode -> {
                readingTtsReady = statusCode == TextToSpeech.SUCCESS;
                if (readingTtsReady && readingTts != null) {
                    try {
                        int languageResult = readingTts.setLanguage(new Locale("id", "ID"));
                        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) readingTts.setLanguage(Locale.getDefault());
                    } catch (Throwable ignored) {}
                    try { readingTts.setSpeechRate(ttsPrefs.getFloat("document_tts_rate", 1.0f)); } catch (Throwable ignored) {}
                    try { readingTts.setPitch(ttsPrefs.getFloat("document_tts_pitch", 1.0f)); } catch (Throwable ignored) {}
                    try {
                        String voiceName = ttsPrefs.getString("document_tts_voice_name", "");
                        if (android.os.Build.VERSION.SDK_INT >= 21 && voiceName != null && !voiceName.isEmpty() && readingTts.getVoices() != null) {
                            for (Voice voice : readingTts.getVoices()) if (voice != null && voiceName.equals(voice.getName())) { readingTts.setVoice(voice); break; }
                        }
                    } catch (Throwable ignored) {}
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 21 && readingTts.getVoices() != null) {
                            for (Voice voice : readingTts.getVoices()) {
                                if (voice == null) continue;
                                boolean exists = false;
                                for (Voice existing : voices) if (existing != null && existing.getName().equals(voice.getName())) { exists = true; break; }
                                if (!exists) {
                                    String localeLabel = voice.getLocale() == null ? "Bahasa tidak diketahui" : voice.getLocale().getDisplayName();
                                    voiceNames.add(voice.getName() + " (" + localeLabel + (voice.isNetworkConnectionRequired() ? ", online" : ", lokal") + ")");
                                    voices.add(voice);
                                }
                            }
                            if (voiceAdapterHolder[0] != null) voiceAdapterHolder[0].notifyDataSetChanged();
                        }
                    } catch (Throwable ignored) {}
                }
            };
            try {
                if (selectedEngine != null && !selectedEngine.trim().isEmpty()) readingTts = new TextToSpeech(this, initListener, selectedEngine.trim());
                else readingTts = new TextToSpeech(this, initListener);
            } catch (Throwable ignored) { readingTts = new TextToSpeech(this, initListener); }
        }
        if (pdfDocumentActive && activePdfTextPage >= 0 && activePdfTextPage < pdfPageTexts.size()) {
            readingText = pdfPageTexts.get(activePdfTextPage);
        } else if (wordEdit != null) {
            readingText = wordEdit.getText().toString();
        } else {
            readingText = cachedText == null ? "" : cachedText;
        }
        readingCursor = 0;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(8));
        TextView info = new TextView(this);
        info.setText("Mode baca dokumen. Untuk PDF, gunakan tombol halaman sebelumnya/berikutnya. Tombol mundur/maju 10 detik memindahkan perkiraan posisi bacaan berdasarkan panjang teks.");
        info.setTextSize(16);
        box.addView(info);
        Button engineButton = new Button(this);
        android.content.SharedPreferences readerPrefs = getSharedPreferences("VoiceInput_Settings", MODE_PRIVATE);
        String currentDocEngineName = readerPrefs.getString("document_tts_engine_name", "Default Sistem Android");
        engineButton.setText("Mesin TTS Dokumen: " + currentDocEngineName);
        engineButton.setAllCaps(false);
        engineButton.setContentDescription("Pilih mesin text to speech khusus untuk pembaca dokumen, terpisah dari Braille");
        engineButton.setOnClickListener(v -> {
            java.util.List<TextToSpeech.EngineInfo> available = BrailleTTSManager.getAllAvailableEngines(DocumentStudioActivity.this);
            ArrayList<String> labels = new ArrayList<>();
            ArrayList<String> packages = new ArrayList<>();
            labels.add("Default Sistem Android"); packages.add("");
            if (available != null) for (TextToSpeech.EngineInfo engineInfo : available) {
                if (engineInfo == null || engineInfo.name == null) continue;
                labels.add(engineInfo.label + " (" + engineInfo.name + ")"); packages.add(engineInfo.name);
            }
            String saved = readerPrefs.getString("document_tts_engine", "");
            int selected = Math.max(0, packages.indexOf(saved == null ? "" : saved));
            UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Pilih Mesin TTS Dokumen")
                .setSingleChoiceItems(labels.toArray(new String[0]), selected, (dialog, which) -> {
                    readerPrefs.edit().putString("document_tts_engine", packages.get(which)).putString("document_tts_engine_name", labels.get(which)).apply();
                    try { if (readingTts != null) { readingTts.stop(); readingTts.shutdown(); } } catch (Throwable ignored) {}
                    readingTts = null; readingTtsReady = false; dialog.dismiss();
                    Toast.makeText(DocumentStudioActivity.this, "Mesin TTS dokumen disimpan. Tutup lalu buka kembali mode baca untuk menerapkan mesin baru.", Toast.LENGTH_LONG).show();
                    engineButton.setText("Mesin TTS Dokumen: " + labels.get(which));
                }).setNegativeButton("Batal", null).show();
        });
        box.addView(engineButton);
        Spinner voiceSpinner = new Spinner(this);
        try {
            if (readingTts != null && android.os.Build.VERSION.SDK_INT >= 21 && readingTts.getVoices() != null) {
                for (Voice voice : readingTts.getVoices()) {
                    if (voice == null) continue;
                    boolean exists = false;
                    for (Voice existing : voices) if (existing != null && existing.getName().equals(voice.getName())) { exists = true; break; }
                    if (!exists) {
                        String localeLabel = voice.getLocale() == null ? "Bahasa tidak diketahui" : voice.getLocale().getDisplayName();
                        voiceNames.add(voice.getName() + " (" + localeLabel + (voice.isNetworkConnectionRequired() ? ", online" : ", lokal") + ")");
                        voices.add(voice);
                    }
                }
            }
        } catch (Throwable ignored) {}
        ArrayAdapter<String> voiceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, voiceNames);
        voiceAdapterHolder[0] = voiceAdapter;
        voiceSpinner.setAdapter(voiceAdapter);
        String savedReaderVoice = getSharedPreferences("VoiceInput_Settings", MODE_PRIVATE).getString("document_tts_voice_name", "");
        if (savedReaderVoice != null && !savedReaderVoice.isEmpty()) {
            for (int i = 1; i < voices.size(); i++) {
                if (voices.get(i) != null && savedReaderVoice.equals(voices.get(i).getName())) { voiceSpinner.setSelection(i); break; }
            }
        }
        voiceSpinner.setContentDescription("Pilih suara TTS untuk mode baca");
        voiceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                try {
                    if (position >= 0 && position < voices.size()) {
                        Voice selected = voices.get(position);
                        if (selected == null) {
                            getSharedPreferences("VoiceInput_Settings", MODE_PRIVATE).edit().putString("document_tts_voice_name", "").apply();
                        } else if (readingTts != null && android.os.Build.VERSION.SDK_INT >= 21) {
                            readingTts.setVoice(selected);
                            getSharedPreferences("VoiceInput_Settings", MODE_PRIVATE).edit().putString("document_tts_voice_name", selected.getName()).apply();
                        }
                    }
                } catch (Throwable ignored) {}
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        box.addView(voiceSpinner);
        android.content.SharedPreferences readerTtsPrefs = getSharedPreferences("VoiceInput_Settings", MODE_PRIVATE);
        final TextView rateLabel = new TextView(this);
        float initialRate = readerTtsPrefs.getFloat("document_tts_rate", 1.0f);
        rateLabel.setText("Kecepatan membaca: " + String.format(java.util.Locale.US, "%.2fx", initialRate));
        rateLabel.setContentDescription("Kecepatan suara pembaca dokumen");
        box.addView(rateLabel);
        SeekBar rateSeek = new SeekBar(this);
        rateSeek.setMax(350);
        rateSeek.setProgress(Math.max(0, Math.min(350, Math.round((initialRate - 0.5f) * 100))));
        rateSeek.setContentDescription("Atur kecepatan membaca dari 0,5 sampai 4 kali");
        rateSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float rate = 0.5f + progress / 100.0f;
                rateLabel.setText("Kecepatan membaca: " + String.format(java.util.Locale.US, "%.2fx", rate));
                if (fromUser) {
                    getSharedPreferences("VoiceInput_Settings", MODE_PRIVATE).edit().putFloat("document_tts_rate", rate).apply();
                    try { if (readingTtsReady && readingTts != null) readingTts.setSpeechRate(rate); } catch (Throwable ignored) {}
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        box.addView(rateSeek);
        final TextView pitchLabel = new TextView(this);
        float initialPitch = readerTtsPrefs.getFloat("document_tts_pitch", 1.0f);
        pitchLabel.setText("Nada suara: " + String.format(java.util.Locale.US, "%.2fx", initialPitch));
        box.addView(pitchLabel);
        SeekBar pitchSeek = new SeekBar(this);
        pitchSeek.setMax(150);
        pitchSeek.setProgress(Math.max(0, Math.min(150, Math.round((initialPitch - 0.5f) * 100))));
        pitchSeek.setContentDescription("Atur nada suara dari rendah ke tinggi");
        pitchSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float pitch = 0.5f + progress / 100.0f;
                pitchLabel.setText("Nada suara: " + String.format(java.util.Locale.US, "%.2fx", pitch));
                if (fromUser) {
                    getSharedPreferences("VoiceInput_Settings", MODE_PRIVATE).edit().putFloat("document_tts_pitch", pitch).apply();
                    try { if (readingTtsReady && readingTts != null) readingTts.setPitch(pitch); } catch (Throwable ignored) {}
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        box.addView(pitchSeek);
        TextView progress = new TextView(this);
        progress.setText("Posisi bacaan: awal dokumen");
        progress.setPadding(0, dp(8), 0, dp(8));
        box.addView(progress);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button back = new Button(this); back.setText("Mundur sekitar 10 detik"); back.setAllCaps(false); back.setContentDescription("Mundur ke bagian teks sebelumnya, perkiraan sepuluh detik");
        Button play = new Button(this); play.setText("Baca / Lanjutkan"); play.setAllCaps(false); play.setContentDescription("Mulai atau lanjutkan pembacaan dokumen");
        Button pause = new Button(this); pause.setText("Hentikan sementara"); pause.setAllCaps(false); pause.setContentDescription("Hentikan sementara suara pembacaan");
        Button forward = new Button(this); forward.setText("Maju sekitar 10 detik"); forward.setAllCaps(false); forward.setContentDescription("Maju ke bagian teks berikutnya, perkiraan sepuluh detik");
        row.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(play, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(row);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(pause, new LinearLayout.LayoutParams(0, -2, 1));
        row2.addView(forward, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(row2);
        LinearLayout pages = new LinearLayout(this);
        pages.setOrientation(LinearLayout.HORIZONTAL);
        Button prevPage = new Button(this); prevPage.setText("Halaman sebelumnya"); prevPage.setAllCaps(false); prevPage.setContentDescription("Buka halaman PDF sebelumnya");
        Button nextPage = new Button(this); nextPage.setText("Halaman berikutnya"); nextPage.setAllCaps(false); nextPage.setContentDescription("Buka halaman PDF berikutnya");
        pages.addView(prevPage, new LinearLayout.LayoutParams(0, -2, 1));
        pages.addView(nextPage, new LinearLayout.LayoutParams(0, -2, 1));
        pages.setVisibility(pdfDocumentActive && !pdfPageTexts.isEmpty() ? View.VISIBLE : View.GONE);
        box.addView(pages);
        final int approxTenSecondsChars = 220;
        final int maxChunkChars = 2500;
        final int[] utteranceBase = {0};
        final int[] utteranceEnd = {0};
        final boolean[] paused = {true};
        final long[] session = {0L};
        final Runnable[] speakNextChunk = new Runnable[1];
        Runnable updateProgress = () -> progress.setText("Posisi bacaan: karakter " + Math.min(readingText.length(), readingCursor + 1) + " dari " + Math.max(1, readingText.length()));
        if (readingTts != null) {
            try {
                readingTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {}
                    @Override public void onDone(String utteranceId) {
                        runOnUiThread(() -> {
                            if (utteranceId == null || !utteranceId.startsWith("doc_read_" + session[0] + "_")) return;
                            if (paused[0]) return;
                            readingCursor = Math.min(readingText.length(), utteranceEnd[0]);
                            updateProgress.run();
                            if (readingCursor < readingText.length() && speakNextChunk[0] != null) speakNextChunk[0].run();
                            else { paused[0] = true; }
                        });
                    }
                    @Override public void onError(String utteranceId) {
                        runOnUiThread(() -> {
                            if (utteranceId == null || !utteranceId.startsWith("doc_read_" + session[0] + "_")) return;
                            if (paused[0]) return;
                            paused[0] = true;
                            Toast.makeText(DocumentStudioActivity.this, "Mesin TTS gagal membaca bagian ini. Tekan Baca/Lanjutkan untuk mencoba lagi dari posisi terakhir.", Toast.LENGTH_LONG).show();
                        });
                    }
                    @Override public void onRangeStart(String utteranceId, int start, int end, int frame) {
                        runOnUiThread(() -> {
                            if (utteranceId != null && utteranceId.startsWith("doc_read_" + session[0] + "_")) {
                                readingCursor = Math.max(0, Math.min(readingText.length(), utteranceBase[0] + start));
                                updateProgress.run();
                            }
                        });
                    }
                });
            } catch (Throwable ignored) {}
        }
        speakNextChunk[0] = () -> {
            if (paused[0] || !readingTtsReady || readingTts == null || readingText == null) return;
            if (readingCursor >= readingText.length()) { paused[0] = true; updateProgress.run(); return; }
            int startAt = Math.max(0, Math.min(readingCursor, readingText.length()));
            int endAt = Math.min(readingText.length(), startAt + maxChunkChars);
            if (endAt < readingText.length()) {
                int boundary = readingText.lastIndexOf(' ', endAt);
                if (boundary > startAt + maxChunkChars / 2) endAt = boundary;
            }
            String part = readingText.substring(startAt, endAt);
            if (part.trim().isEmpty()) { readingCursor = Math.min(readingText.length(), endAt + 1); speakNextChunk[0].run(); return; }
            utteranceBase[0] = startAt;
            utteranceEnd[0] = endAt;
            String id = "doc_read_" + session[0] + "_" + startAt;
            try {
                int result = readingTts.speak(part, TextToSpeech.QUEUE_FLUSH, null, id);
                if (result == TextToSpeech.ERROR) {
                    paused[0] = true;
                    Toast.makeText(this, "Mesin TTS menolak bagian teks ini. Coba lanjutkan lagi.", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable error) {
                paused[0] = true;
                Toast.makeText(this, "Pembacaan gagal pada bagian ini. Posisi bacaan tetap disimpan.", Toast.LENGTH_SHORT).show();
            }
            updateProgress.run();
        };
        play.setOnClickListener(v -> {
            if (!readingTtsReady || readingTts == null) {
                Toast.makeText(this, "Mesin TTS sedang disiapkan atau baru diganti. Tutup lalu buka kembali mode baca.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (readingText == null || readingText.trim().isEmpty()) {
                Toast.makeText(this, "Dokumen belum berisi teks yang bisa dibaca.", Toast.LENGTH_LONG).show();
                return;
            }
            if (readingCursor >= readingText.length()) readingCursor = 0;
            session[0]++;
            paused[0] = false;
            speakNextChunk[0].run();
        });
        pause.setOnClickListener(v -> { paused[0] = true; try { if (readingTts != null) readingTts.stop(); } catch (Throwable ignored) {} updateProgress.run(); });
        back.setOnClickListener(v -> { paused[0] = true; try { if (readingTts != null) readingTts.stop(); } catch (Throwable ignored) {} session[0]++; readingCursor = Math.max(0, readingCursor - approxTenSecondsChars); updateProgress.run(); });
        forward.setOnClickListener(v -> { paused[0] = true; try { if (readingTts != null) readingTts.stop(); } catch (Throwable ignored) {} session[0]++; readingCursor = Math.min(Math.max(0, readingText.length()), readingCursor + approxTenSecondsChars); updateProgress.run(); });
        prevPage.setOnClickListener(v -> {
            if (activePdfTextPage > 0) { paused[0] = true; session[0]++; try { if (readingTts != null) readingTts.stop(); } catch (Throwable ignored) {} activePdfTextPage--; loadPdfPageIntoMainEditor(activePdfTextPage); readingText = pdfPageTexts.get(activePdfTextPage); readingCursor = 0; updateProgress.run(); }
            else Toast.makeText(this, "Ini halaman pertama.", Toast.LENGTH_SHORT).show();
        });
        nextPage.setOnClickListener(v -> {
            if (activePdfTextPage + 1 < pdfPageTexts.size()) { paused[0] = true; session[0]++; try { if (readingTts != null) readingTts.stop(); } catch (Throwable ignored) {} activePdfTextPage++; loadPdfPageIntoMainEditor(activePdfTextPage); readingText = pdfPageTexts.get(activePdfTextPage); readingCursor = 0; updateProgress.run(); }
            else Toast.makeText(this, "Ini halaman terakhir.", Toast.LENGTH_SHORT).show();
        });
        ScrollView readerScroll = new ScrollView(this);
        readerScroll.setFillViewport(true);
        readerScroll.addView(box);
        UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE))
            .setTitle("Mode Baca Dokumen & PDF").setView(readerScroll)
            .setOnDismissListener(d -> { paused[0] = true; session[0]++; try { if (readingTts != null) readingTts.stop(); } catch (Throwable ignored) {} })
            .show();
    }

    private void showReadingMode() { UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Mode fokus membaca").setMessage("Memperbesar jarak baris kotak edit tanpa menonaktifkan keyboard.").setNegativeButton("Batal",null).setPositiveButton("Aktifkan",(d,w)->{if(wordEdit!=null){wordEdit.setLineSpacing(dp(5),1.15f);setStatus("Mode baca aktif. Ketuk kotak teks untuk melanjutkan pengeditan.");}}).show(); }

    private void showColorPicker(boolean highlight) {
        final String[] names = {"Hitam", "Biru", "Merah", "Hijau", "Ungu", "Oranye", "Kuning"};
        final String[] colors = {"#111111", "#1565C0", "#C62828", "#2E7D32", "#6A1B9A", "#EF6C00", "#FFF176"};
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle(highlight ? "Warna sorotan" : "Warna teks").setItems(names, (d, which) -> applyNativeWordColor(highlight, colors[which])).show();
    }

    private void showStylePicker() {
        final String[] labels = {"Judul 1", "Judul 2", "Judul 3", "Judul biasa", "Paragraf normal", "Kutipan", "Kode / monospace"};
        final String[] tags = {"h1", "h2", "h3", "h4", "p", "blockquote", "pre"};
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Gaya teks dan paragraf").setItems(labels, (d, which) -> applyNativeWordCommand("formatBlock", tags[which])).show();
    }

    private void showEditorSettings() {
        android.content.SharedPreferences p = getSharedPreferences("document_studio", MODE_PRIVATE);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(8), dp(14), dp(4));

        final Spinner theme = new Spinner(this);
        theme.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Kertas Putih, Teks Hitam", "Mode Malam: Gelap", "Kuning Lembut Nyaman Baca"}));
        theme.setSelection(Math.max(0, Math.min(2, p.getInt("editor_theme", 0))));
        box.addView(new TextView(this) {{ setText("Tema warna kertas editor"); setPadding(0, dp(6), 0, dp(2)); }}); box.addView(theme);

        final Spinner paper = new Spinner(this);
        paper.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Kertas A4 (210 x 297 mm)", "Kertas Letter (8.5 x 11 in)", "Kertas Legal (8.5 x 14 in)", "Kertas F4 / Folio (215 x 330 mm)"}));
        paper.setSelection(Math.max(0, Math.min(3, p.getInt("page_size", 0))));
        box.addView(new TextView(this) {{ setText("Ukuran kertas ekspor DOCX & PDF"); setPadding(0, dp(8), 0, dp(2)); }}); box.addView(paper);

        final Spinner orientation = new Spinner(this);
        orientation.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Potret (Tegak)", "Lanskap (Mendatar)"}));
        orientation.setSelection(p.getBoolean("page_landscape", false) ? 1 : 0);
        box.addView(new TextView(this) {{ setText("Orientasi halaman"); setPadding(0, dp(8), 0, dp(2)); }}); box.addView(orientation);

        final Spinner marginPreset = new Spinner(this);
        marginPreset.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Margin Sempit (1,27 cm)", "Margin Normal Standar (2,54 cm)", "Margin Lebar (3,81 cm)"}));
        marginPreset.setSelection(Math.max(0, Math.min(2, p.getInt("page_margin_preset", 1))));
        box.addView(new TextView(this) {{ setText("Ukuran margin halaman cetak"); setPadding(0, dp(8), 0, dp(2)); }}); box.addView(marginPreset);

        final Spinner spacing = new Spinner(this);
        spacing.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Spasi 1.0 (Rapat)", "Spasi 1.15 (Standar Word)", "Spasi 1.5 (Rapi Skripsi/Makalah)", "Spasi 2.0 (Ganda)"}));
        spacing.setSelection(Math.max(0, Math.min(3, p.getInt("line_spacing_idx", 1))));
        box.addView(new TextView(this) {{ setText("Spasi baris paragraf"); setPadding(0, dp(8), 0, dp(2)); }}); box.addView(spacing);

        final android.widget.CheckBox twoCol = new android.widget.CheckBox(this);
        twoCol.setText("Aktifkan tata letak 2 Kolom saat ekspor DOCX & PDF");
        twoCol.setChecked(p.getBoolean("two_columns", false));
        box.addView(twoCol);

        final android.widget.CheckBox pageNums = new android.widget.CheckBox(this);
        pageNums.setText("Cetak nomor halaman di Footer");
        pageNums.setChecked(p.getBoolean("doc_page_numbers", true));
        box.addView(pageNums);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Tata Letak Halaman & Editor")
            .setView(sc).setNegativeButton("Batal", null).setPositiveButton("Terapkan", (d, w) -> {
                int spIdx = spacing.getSelectedItemPosition();
                p.edit()
                    .putInt("editor_theme", theme.getSelectedItemPosition())
                    .putInt("page_size", paper.getSelectedItemPosition())
                    .putBoolean("page_landscape", orientation.getSelectedItemPosition() == 1)
                    .putInt("page_margin_preset", marginPreset.getSelectedItemPosition())
                    .putInt("line_spacing_idx", spIdx)
                    .putBoolean("two_columns", twoCol.isChecked())
                    .putBoolean("doc_page_numbers", pageNums.isChecked())
                    .apply();
                if (wordEdit != null) {
                    int t = theme.getSelectedItemPosition();
                    wordEdit.setBackgroundColor(t == 1 ? Color.parseColor("#202124") : t == 2 ? Color.parseColor("#FFF8DC") : Color.WHITE);
                    wordEdit.setTextColor(t == 1 ? Color.parseColor("#F1F3F4") : Color.parseColor("#111111"));
                    float mult = spIdx == 0 ? 1.0f : spIdx == 2 ? 1.35f : spIdx == 3 ? 1.7f : 1.15f;
                    wordEdit.setLineSpacing(dp(2), mult);
                }
                setStatus("Pengaturan kertas, margin, spasi, & tema berhasil diperbarui.");
            }).show();
    }

    private void findOrReplace(boolean replace) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(8), dp(14), dp(4));
        EditText find = new EditText(this); find.setHint("Kata atau teks yang dicari"); box.addView(find);
        EditText repl = new EditText(this); repl.setHint("Ganti dengan"); if (replace) box.addView(repl);
        android.widget.CheckBox matchCase = new android.widget.CheckBox(this);
        matchCase.setText("Peka huruf besar/kecil (Match Case)");
        box.addView(matchCase);
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle(replace ? "Cari dan ganti" : "Cari teks").setView(box).setNegativeButton("Batal", null)
            .setPositiveButton(replace ? "Ganti semua" : "Cari", (d, w) -> {
                String a = find.getText().toString(); String b = repl.getText().toString();
                if (a.isEmpty() || wordEdit == null) return;
                String source = wordEdit.getText().toString();
                boolean exact = matchCase.isChecked();
                if (replace) {
                    String replaced;
                    int occurrences = 0;
                    if (exact) {
                        int from = 0; while ((from = source.indexOf(a, from)) >= 0) { occurrences++; from += a.length(); }
                        replaced = source.replace(a, b);
                    } else {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(a), java.util.regex.Pattern.CASE_INSENSITIVE).matcher(source);
                        StringBuffer sb = new StringBuffer();
                        while (m.find()) { occurrences++; m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(b)); }
                        m.appendTail(sb);
                        replaced = sb.toString();
                    }
                    setWordEditorContent(replaced); wordEditDirty = true; cachedText = wordEdit.getText().toString(); cachedHtml = nativeWordHtml();
                    String msg = "Penggantian selesai: " + occurrences + " kecocokan diganti.";
                    setStatus(msg);
                    OfficeUtils.announce(rootLayoutOrStatus(), msg);
                } else {
                    int cursor = Math.max(0, wordEdit.getSelectionEnd());
                    String hay = exact ? source : source.toLowerCase(Locale.ROOT);
                    String needle = exact ? a : a.toLowerCase(Locale.ROOT);
                    int index = hay.indexOf(needle, cursor);
                    if (index < 0) index = hay.indexOf(needle);
                    if (index >= 0) {
                        jumpNativeDocumentToOffset(index);
                        wordEdit.setSelection(index, Math.min(source.length(), index + a.length()));
                        Toast.makeText(this, "Ditemukan pada posisi karakter " + index, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Teks \"" + a + "\" tidak ditemukan.", Toast.LENGTH_SHORT).show();
                    }
                }
            }).show();
    }

    private void countWords() {
        String text = wordEdit == null ? cachedText : wordEdit.getText().toString();
        String trimmed = text.trim();
        int words = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
        int charsWithSpace = text.length();
        int charsNoSpace = text.replaceAll("\\s+", "").length();
        int paragraphs = trimmed.isEmpty() ? 0 : trimmed.split("\\n+").length;
        int sentences = trimmed.isEmpty() ? 0 : trimmed.split("[.!?]+").length;
        int readMins = Math.max(1, (int) Math.ceil(words / 200.0));
        int speakMins = Math.max(1, (int) Math.ceil(words / 130.0));
        int bookPages = (!pdfDocumentActive && documentPages != null) ? documentPages.size() : (pdfPageTexts != null && !pdfPageTexts.isEmpty() ? pdfPageTexts.size() : 1);
        UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE)).setTitle("Statistik Lengkap Dokumen")
            .setMessage("• Kata (halaman aktif): " + words
                    + "\n• Karakter (dengan spasi): " + charsWithSpace
                    + "\n• Karakter (tanpa spasi): " + charsNoSpace
                    + "\n• Paragraf: " + paragraphs
                    + "\n• Perkiraan Kalimat: " + sentences
                    + "\n• Total Halaman Dokumen: " + bookPages
                    + "\n• Perkiraan Waktu Baca Senyap: ~" + (words == 0 ? 0 : readMins) + " menit"
                    + "\n• Perkiraan Waktu Pidato / TTS: ~" + (words == 0 ? 0 : speakMins) + " menit")
            .show();
    }

    private void insertTable() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        EditText rows = new EditText(this); rows.setHint("Jumlah baris (mis. 3)"); rows.setInputType(2); box.addView(rows);
        EditText cols = new EditText(this); cols.setHint("Jumlah kolom (mis. 3)"); cols.setInputType(2); box.addView(cols);
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Sisipkan tabel").setView(box).setNegativeButton("Batal", null).setPositiveButton("Sisipkan", (d, w) -> {
            int r = parseBounded(rows.getText().toString(), 3, 1, 20); int c = parseBounded(cols.getText().toString(), 3, 1, 10);
            StringBuilder table = new StringBuilder();
            for (int i=0;i<r;i++){ for(int j=0;j<c;j++){ if(j>0)table.append("\t"); table.append("[Sel ").append(i+1).append(",").append(j+1).append("]"); } table.append("\n"); }
            insertNativeTextAtCursor(table.toString());
        }).show();
    }
    private int parseBounded(String s, int def, int min, int max) { try { return Math.max(min, Math.min(max, Integer.parseInt(s.trim()))); } catch (Exception e) { return def; } }
    private void insertLink() {
        EditText url = new EditText(this); url.setHint("https://contoh.com");
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Sisipkan tautan").setView(url).setNegativeButton("Batal", null).setPositiveButton("Sisipkan", (d,w)-> {String u=url.getText().toString().trim(); if(!u.isEmpty()) insertNativeTextAtCursor(u);}).show();
    }
    private void showFormatHelp() {
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Format yang didukung").setMessage("Editor dapat membuka DOCX/DOCM/DOTX/DOTM berbasis XML, PDF untuk ekstraksi teks, HTML, TXT, RTF dasar, ODT, ODP, PPTX/PPTM/PPSX/POTX, XLSX/XLSM/XLTX, CSV, XML, dan beberapa format teks lain. XLSX/ODS juga dapat dibuka di menu Excel & Spreadsheet. Tombol File Manager menelusuri penyimpanan bersama perangkat, mencari berkas, membuat folder, membuka dokumen, dan memilih folder tujuan simpan. Pada Android 11 ke atas, akses semua berkas harus diaktifkan secara manual di Pengaturan. Navigasi halaman memberikan perkiraan halaman dan lompatan ke bagian dokumen; jumlah halaman tidak dibatasi oleh angka tetap aplikasi, tetapi dokumen sangat besar tetap bergantung pada memori perangkat dan WebView. PDF berbasis teks diekstrak seluruh halamannya dan halaman pertama langsung dimuat ke kotak edit Word utama; pilih halaman melalui daftar atau tombol Sebelumnya/Berikutnya, dan teks halaman sebelumnya tetap disimpan saat berpindah. PDF hasil pemindaian yang hanya berupa gambar tidak diproses dengan OCR. DOC lama, XLS biner, XLSB, dokumen terenkripsi, makro yang dapat dijalankan, SmartArt, pivot table, dan tata letak kompleks belum didukung penuh. Ekspor tersedia ke DOCX, DOTX, PDF, HTML, TXT, RTF, Markdown, dan ODT. Pemformatan dan objek kompleks dapat berubah saat konversi.").setPositiveButton("Mengerti", null).show();
    }

    private void launchOcrImagePicker() {
        OfficeUtils.launchFilePicker(this, REQ_OCR_IMAGE, "image", "Pilih Gambar untuk OCR");
    }

    private void launchManualOcrLangPicker() {
        OfficeUtils.launchFilePicker(this, REQ_MANUAL_OCR_LANG, "all", "Pilih Berkas Bahasa OCR (.traineddata)");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_FILE_MANAGER && data.getStringExtra("selected_folder") != null) {
            pendingSaveDirectory = new File(data.getStringExtra("selected_folder"));
            showSaveOptions();
            return;
        }
        if (data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_MANUAL_OCR_LANG) {
            new Thread(() -> {
                try {
                    String code = OcrEngineManager.importTrainedDataFromUri(this, uri);
                    runOnUiThread(() -> {
                        OcrEngineManager.setActiveLanguageCombo(this, code);
                        Toast.makeText(this, "Bahasa OCR manual \"" + code + "\" dimuat dan diaktifkan!", Toast.LENGTH_LONG).show();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> showError("Gagal memuat paket bahasa", e));
                }
            }).start();
            return;
        }
        if (requestCode == REQ_OCR_IMAGE) {
            setStatus("Memindai teks dari gambar dengan OCR offline…");
            new Thread(() -> {
                Bitmap bmp = null;
                try {
                    android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IllegalStateException("Gambar tidak bisa dibaca");
                        android.graphics.BitmapFactory.decodeStream(in, null, bounds);
                    }
                    int sample = 1;
                    while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2;
                    android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    try (InputStream in2 = getContentResolver().openInputStream(uri)) {
                        bmp = android.graphics.BitmapFactory.decodeStream(in2, null, opts);
                    }
                    if (bmp == null) throw new IllegalStateException("Format gambar tidak didukung.");
                    String lang = OcrEngineManager.getActiveLanguageCombo(this);
                    String extracted = OcrEngineManager.recognizeBitmap(this, bmp, lang);
                    runOnUiThread(() -> {
                        if (extracted.isEmpty()) {
                            Toast.makeText(this, "Tidak ada teks terbaca pada gambar ini.", Toast.LENGTH_LONG).show();
                            setStatus("OCR gambar selesai (teks kosong).");
                        } else {
                            insertNativeTextAtCursor("\n" + extracted + "\n");
                            String msg = "Berhasil mengekstrak " + extracted.length() + " karakter dari gambar (" + lang + ").";
                            setStatus(msg);
                            OfficeUtils.announce(rootLayoutOrStatus(), msg);
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> showError("Gagal OCR gambar", e));
                } finally {
                    if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                }
            }).start();
            return;
        }
        if (requestCode == REQ_IMAGE) {
            new Thread(() -> {
                try {
                    String displayName = getDisplayName(uri);
                    android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IllegalStateException("Gambar tidak bisa dibaca");
                        android.graphics.BitmapFactory.decodeStream(in, null, bounds);
                    }
                    int sample = 1;
                    while (bounds.outWidth / sample > 1440 || bounds.outHeight / sample > 1440) sample *= 2;
                    android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    Bitmap bmp;
                    try (InputStream in2 = getContentResolver().openInputStream(uri)) {
                        bmp = android.graphics.BitmapFactory.decodeStream(in2, null, opts);
                    }
                    if (bmp == null) throw new IllegalStateException("Format gambar tidak dapat diproses.");
                    String fname = "img_" + System.currentTimeMillis() + ".jpg";
                    File dest = new File(WordExporter.imageDir(this), fname);
                    try (FileOutputStream fos = new FileOutputStream(dest, false)) {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos);
                    }
                    bmp.recycle();
                    String safeCap = displayName == null ? "Gambar" : displayName.replaceAll("[|\\]]", "");
                    runOnUiThread(() -> {
                        insertNativeTextAtCursor("\n[GAMBAR:" + fname + "|" + safeCap + "]\n");
                        String msg = "Gambar \"" + safeCap + "\" disematkan! Akan tampil utuh saat diekspor ke DOCX & PDF.";
                        setStatus(msg);
                        OfficeUtils.announce(rootLayoutOrStatus(), msg);
                    });
                } catch (Exception e) { runOnUiThread(() -> showError("Gagal menyisipkan gambar", e)); }
            }).start();
            return;
        }
        if (requestCode == REQ_MERGE) {
            new Thread(() -> {
                try {
                    String name = getDisplayName(uri);
                    String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
                    String merged;
                    if (lower.endsWith(".pdf")) merged = extractPdf(uri, 1, 20);
                    else if (lower.endsWith(".docx") || lower.endsWith(".docm") || lower.endsWith(".dotx") || lower.endsWith(".pptx") || lower.endsWith(".xlsx") || lower.endsWith(".odt") || lower.endsWith(".odp") || lower.endsWith(".ods")) merged = extractZipOfficeText(uri, lower);
                    else merged = readAll(getContentResolver().openInputStream(uri));
                    final String finalMerged = merged == null ? "" : merged.trim();
                    runOnUiThread(() -> {
                        if (finalMerged.isEmpty()) { Toast.makeText(this, "Berkas kosong.", Toast.LENGTH_SHORT).show(); return; }
                        insertNativeTextAtCursor("\n\n" + finalMerged + "\n");
                        setStatus("Isi berkas \"" + name + "\" berhasil digabungkan di posisi kursor.");
                    });
                } catch (Exception e) { runOnUiThread(() -> showError("Gagal menggabungkan berkas", e)); }
            }).start();
            return;
        }
        if (requestCode == REQ_OPEN || requestCode == REQ_FILE_MANAGER) {
            try {
                String pickedName = requestCode == REQ_FILE_MANAGER && data.getStringExtra("selected_name") != null ? data.getStringExtra("selected_name") : getDisplayName(uri);
                if (pickedName != null && pickedName.toLowerCase(Locale.ROOT).endsWith(".pdf")) lastPdfUri = uri;
            } catch (Exception ignored) {}
            try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); } catch (Exception ignored) {}
            final String selectedName = requestCode == REQ_FILE_MANAGER && data.getStringExtra("selected_name") != null ? data.getStringExtra("selected_name") : getDisplayName(uri);
            if (selectedName != null && selectedName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this))
                    .setTitle("Buka Berkas PDF: " + selectedName)
                    .setMessage("Pilih mode pembuka PDF:")
                    .setPositiveButton("Mode Pembaca PDF + OCR (Disarankan)", (dd, ww) -> {
                        Intent pdfIntent = new Intent(this, PdfReaderActivity.class);
                        pdfIntent.putExtra("extra_uri", uri.toString());
                        startActivity(pdfIntent);
                    })
                    .setNeutralButton("Ekstrak Teks ke Editor Word", (dd, ww) -> showPdfTextRangeDialog(uri, selectedName))
                    .setNegativeButton("Batal", null)
                    .show();
                return;
            }
            setStatus("Membuka berkas…");
            new Thread(() -> {
                try {
                    String name = selectedName; String lower = name.toLowerCase(Locale.ROOT); String body; String nativeOfficeText = null;
                    if (lower.endsWith(".pdf")) {
                        String text = extractPdf(uri, 1, 20);
                        if (text.trim().isEmpty()) {
                            runOnUiThread(() -> UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Teks PDF tidak tersedia").setMessage("PDF ini tidak menyediakan lapisan teks yang bisa diekstrak. Kemungkinan halamannya berupa gambar hasil pemindaian. Sesuai pengaturan, aplikasi tidak menjalankan OCR agar tulisan Arab/Indonesia tidak ditebak atau diubah. Gunakan PDF yang memiliki teks digital atau ekspor teks dari sumber aslinya.").setPositiveButton("Mengerti", null).show());
                            return;
                        }
                        body = textToHtml(text, true);
                    } else if (isImage(lower)) {
                        runOnUiThread(() -> UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Gambar tidak memiliki teks digital").setMessage("Aplikasi hanya mengekstrak teks yang memang tersimpan di berkas. OCR tidak dijalankan, sehingga tulisan pada gambar tidak akan ditebak atau diubah.").setPositiveButton("Mengerti", null).show());
                        return;
                    } else if (lower.endsWith(".doc") || lower.endsWith(".xls") || lower.endsWith(".xlsb") || lower.endsWith(".ppt") || lower.endsWith(".pps") || lower.endsWith(".wps")) {
                        throw new IllegalArgumentException("Format biner lama atau format khusus ini belum didukung untuk ekstraksi langsung. Buka di Microsoft Office atau LibreOffice lalu simpan sebagai DOCX, XLSX, atau PPTX, kemudian buka kembali di sini.");
                    } else if (lower.endsWith(".docx") || lower.endsWith(".docm") || lower.endsWith(".dotx") || lower.endsWith(".dotm") || lower.endsWith(".odt") || lower.endsWith(".pptx") || lower.endsWith(".pptm") || lower.endsWith(".ppsx") || lower.endsWith(".potx") || lower.endsWith(".potm") || lower.endsWith(".ppsm") || lower.endsWith(".xlsx") || lower.endsWith(".xlsm") || lower.endsWith(".xltx") || lower.endsWith(".xltm") || lower.endsWith(".ods") || lower.endsWith(".odp")) {
                        nativeOfficeText = extractZipOfficeText(uri, lower);
                        // Native chunk editor handles this text; do not also build a huge WebView HTML copy.
                        body = "";
                    } else {
                        String raw = readAll(getContentResolver().openInputStream(uri));
                        if (lower.endsWith(".html") || lower.endsWith(".htm")) body = sanitizeHtmlFragment(extractHtmlBody(raw));
                        else if (lower.endsWith(".rtf")) body = textToHtml(stripRtf(raw));
                        else body = textToHtml(raw);
                    }
                    final String finalBody = body; final String finalName = name; final String finalNativeOfficeText = nativeOfficeText;
                    runOnUiThread(() -> {
                        currentName = finalName; cachedHtml = finalBody;
                        if (finalNativeOfficeText != null) {
                            loadNativeInlineText(finalName, finalNativeOfficeText);
                        } else {
                            loadHtml(finalBody);
                            setStatus("Dibuka: " + finalName + ". Ketuk area teks untuk langsung mengetik.");
                        }
                    });
                } catch (Exception e) { runOnUiThread(() -> showError("Gagal membuka berkas", e)); }
            }).start();
        } else if (requestCode == REQ_SAVE) {
            pendingSaveUri = uri;
            setStatus("Menyimpan dokumen…");
            if (nativeTextEditorActive) {
                try {
                    if (pdfDocumentActive) { saveActivePdfPageText(); cachedText = joinPdfPages(pdfPageTexts); cachedHtml = cachedText.length() <= 100000 ? pdfPagesToHtml(pdfPageTexts) : ""; }
                    else {
                        if (documentPagedMode) {
                            saveActiveDocumentPage();
                            cachedText = joinDocumentPages();
                        } else if (wordEdit != null) {
                            cachedText = wordEdit.getText() == null ? "" : wordEdit.getText().toString();
                        }
                        if (wordEditDirty) cachedHtml = nativeWordHtml();
                    }
                    saveToUri(uri, pendingSaveType, cachedHtml, cachedText);
                    currentName = getDisplayName(uri);
                    setStatus("Tersimpan: " + currentName);
                    UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE))
                        .setTitle("Dokumen Tersimpan")
                        .setMessage("Berkas \"" + currentName + "\" selesai diekspor.")
                        .setNeutralButton("Bagikan Sekarang", (d, w) -> OfficeUtils.shareContentUri(this, uri, currentName))
                        .show();
                } catch (Exception e) { showError("Gagal menyimpan dokumen", e); }
                return;
            }
            editor.evaluateJavascript("(function(){var e=document.getElementById('editor');var c=e.cloneNode(true);var ps=c.querySelectorAll('.anas-page');if(ps.length){var parts=[];ps.forEach(function(p){parts.push(p.innerText||'');});c.innerHTML='';c.appendChild(document.createTextNode(parts.join(String.fromCharCode(12))));}c.querySelectorAll('[data-anas-page-break=\"true\"]').forEach(function(n){n.replaceWith(document.createTextNode(String.fromCharCode(12)));});return JSON.stringify({html:e.innerHTML,text:c.innerText});})()", value -> {
                try {
                    org.json.JSONObject obj = new org.json.JSONObject(decodeJsString(value));
                    cachedHtml = obj.optString("html", ""); cachedText = obj.optString("text", "");
                    saveToUri(uri, pendingSaveType, cachedHtml, cachedText);
                    currentName = getDisplayName(uri);
                    setStatus("Tersimpan: " + currentName);
                    UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE))
                        .setTitle("Dokumen Tersimpan")
                        .setMessage("Berkas \"" + currentName + "\" selesai diekspor.")
                        .setNeutralButton("Bagikan Sekarang", (d, w) -> OfficeUtils.shareContentUri(this, uri, currentName))
                        .show();
                } catch (Exception e) { showError("Gagal menyimpan dokumen", e); }
            });
        }
    }

    /** Ekstrak seluruh halaman PDF; halaman aktif ditampilkan langsung pada kotak EditText Word di layar utama. */
    private void showPdfTextRangeDialog(Uri uri, String name) {
        lastPdfUri = uri;
        setStatus("Mengekstrak seluruh halaman PDF…");
        Toast.makeText(this, "Memproses semua halaman PDF. Berkas besar dapat memerlukan waktu dan ruang sementara.", Toast.LENGTH_LONG).show();
        new Thread(() -> {
            try {
                ArrayList<String> pages = extractAllPdfPages(uri);
                boolean hasDigitalText = false;
                for (String page : pages) if (page != null && !page.trim().isEmpty()) { hasDigitalText = true; break; }
                if (!hasDigitalText) {
                    runOnUiThread(() -> UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Teks PDF tidak tersedia")
                        .setMessage("PDF ini tidak menyediakan teks digital yang bisa diekstrak. Kemungkinan halaman berupa gambar hasil pemindaian. OCR tidak dijalankan, jadi tulisan pada gambar tidak akan ditebak atau diubah.")
                        .setPositiveButton("Mengerti", null).show());
                    return;
                }
                runOnUiThread(() -> {
                    currentName = name;
                    pdfPageTexts = pages;
                    pdfDocumentActive = true;
                    activePdfTextPage = 0;
                    if (pdfPageNavRow != null) pdfPageNavRow.setVisibility(View.VISIBLE);
                    if (docPageNavRow != null) docPageNavRow.setVisibility(View.GONE);
                    ArrayList<String> labels = new ArrayList<>();
                    for (int i = 1; i <= pages.size(); i++) labels.add("Halaman " + i + " dari " + pages.size());
                    pdfPageSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
                    changingPdfPage = true;
                    pdfPageSpinner.setSelection(0);
                    changingPdfPage = false;
                    showNativePdfPageEditor();
                    setStatus("PDF dibuka: halaman 1 dari " + pages.size() + ". Kotak edit utama sekarang berisi hasil ekstraksi halaman aktif.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("Gagal mengekstrak PDF", e));
            }
        }).start();
    }

    private ArrayList<String> extractAllPdfPages(Uri uri) throws Exception {
        ArrayList<String> pages = new ArrayList<>();
        try (InputStream in = getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in, MemoryUsageSetting.setupTempFileOnly())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int total = doc.getNumberOfPages();
            for (int page = 1; page <= total; page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8)) { stripper.writeText(doc, writer); }
                pages.add(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        }
        return pages;
    }

    /** Menampilkan kontrol halaman; tidak membuat dialog atau kotak edit PDF kedua. */
    private void showNativePdfPageEditor() {
        if (pdfPageTexts == null || pdfPageTexts.isEmpty()) return;
        if (pdfPageNavRow != null) pdfPageNavRow.setVisibility(View.VISIBLE);
        updatePdfPageControls();
        loadPdfPageIntoMainEditor(0);
    }

    private void updatePdfPageControls() {
        if (pdfPageNavRow == null || pdfPageTexts == null || pdfPageTexts.isEmpty()) return;
        if (pdfPagePrevButton != null) pdfPagePrevButton.setEnabled(activePdfTextPage > 0);
        if (pdfPageNextButton != null) pdfPageNextButton.setEnabled(activePdfTextPage < pdfPageTexts.size() - 1);
        if (pdfPageSpinner != null && pdfPageSpinner.getSelectedItemPosition() != activePdfTextPage) {
            changingPdfPage = true; pdfPageSpinner.setSelection(activePdfTextPage); changingPdfPage = false;
        }
    }

    private void switchNativePdfPage(int position, TextView ignoredLabel) {
        if (position < 0 || position >= pdfPageTexts.size() || position == activePdfTextPage) return;
        saveActivePdfPageText();
        activePdfTextPage = position;
        loadPdfPageIntoMainEditor(position);
        updatePdfPageControls();
        setStatus("PDF: halaman " + (position + 1) + " dari " + pdfPageTexts.size() + ". Suntingan halaman sebelumnya tetap tersimpan.");
    }

    private void loadPdfPageIntoMainEditor(int position) {
        if (position < 0 || position >= pdfPageTexts.size() || wordEdit == null) return;
        updatingWordEdit = true;
        wordEdit.setText(pdfPageTexts.get(position) == null ? "" : pdfPageTexts.get(position), EditText.BufferType.SPANNABLE);
        wordEdit.setSelection(0);
        updatingWordEdit = false;
        cachedText = pdfPageTexts.get(position) == null ? "" : pdfPageTexts.get(position);
        wordEditDirty = false;
        nativeTextEditorActive = true;
        wordEdit.setHint("Tulis sesuatu di sini");
        wordEdit.setContentDescription(null);
        updatePdfPageControls();
    }

    private void saveActivePdfPageText() {
        if (pdfDocumentActive && wordEdit != null && pdfPageTexts != null && activePdfTextPage >= 0 && activePdfTextPage < pdfPageTexts.size()) {
            pdfPageTexts.set(activePdfTextPage, wordEdit.getText() == null ? "" : wordEdit.getText().toString());
        }
    }

    private ArrayList<String> splitIntoChunks(String text, int maxChars) {
        ArrayList<String> chunks = new ArrayList<>();
        String source = text == null ? "" : text;
        if (source.isEmpty()) { chunks.add(""); return chunks; }
        int start = 0;
        while (start < source.length()) {
            int end = Math.min(source.length(), start + maxChars);
            if (end < source.length()) {
                int newline = source.lastIndexOf('\n', end - 1);
                if (newline > start + (maxChars / 2)) end = newline + 1;
            }
            if (end <= start) end = Math.min(source.length(), start + maxChars);
            chunks.add(source.substring(start, end)); start = end;
        }
        return chunks;
    }

    private String joinChunks(ArrayList<String> chunks) {
        StringBuilder out = new StringBuilder();
        if (chunks != null) for (String chunk : chunks) out.append(chunk == null ? "" : chunk);
        return out.toString();
    }

    private String pdfPagesToHtml(ArrayList<String> pages) {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            html.append("<div class='anas-page' data-pdf-page='").append(i + 1).append("'><h3>Halaman ").append(i + 1).append("</h3>");
            html.append(pdfPageTextToHtml(pages.get(i))); html.append("</div>");
        }
        return html.toString();
    }

    private EditText createAccessibleDocumentEditText(String description) {
        EditText edit = new EditText(this);
        edit.setTextSize(18);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setSingleLine(false);
        edit.setHorizontallyScrolling(false);
        edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_ACTION_NONE);
        // Do not override the native hint with a generic content description; screen readers
        // should announce the hint (for an empty editor: “Tulis sesuatu di sini”).
        edit.setPadding(dp(12), dp(12), dp(12), dp(12));
        edit.setBackgroundColor(Color.WHITE);
        edit.setMinHeight(dp(220));
        edit.setTextIsSelectable(false);
        return edit;
    }

    /** Existing native editor used by imported Word/Excel/OpenDocument text. */
    private void showNativeDocumentTextEditor(String title, String sourceText) {
        showNativeDocumentTextEditor(title, sourceText, false);
    }

    /** Chunked native EditText dialog. Preserve cached content when launched from the Word preview,
     * so pressing Cancel cannot erase the previously loaded document from the save state. */
    private void showNativeDocumentTextEditor(String title, String sourceText, boolean preserveCacheOnCancel) {
        nativeDocumentTitle = title == null ? "Dokumen" : title;
        nativeDocumentChunks = splitIntoChunks(sourceText, NATIVE_EDIT_CHUNK_CHARS);
        if (!preserveCacheOnCancel) {
            // Imported office files are represented by their chunk list while being edited.
            cachedText = "";
            cachedHtml = "";
        }
        activeNativeDocumentChunk = 0;
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(4));
        TextView note = new TextView(this);
        note.setText("Editor teks ringan • teks panjang dibagi menjadi bagian kecil supaya mengetik tidak membebani satu kotak edit.");
        note.setTextSize(14); panel.addView(note);
        LinearLayout nav = new LinearLayout(this); nav.setOrientation(LinearLayout.HORIZONTAL);
        nativeDocumentPrev = new Button(this); nativeDocumentPrev.setText("Bagian sebelumnya");
        nav.addView(nativeDocumentPrev, new LinearLayout.LayoutParams(0, dp(46), 1));
        nativeDocumentChunkLabel = new TextView(this); nativeDocumentChunkLabel.setGravity(Gravity.CENTER);
        nav.addView(nativeDocumentChunkLabel, new LinearLayout.LayoutParams(0, dp(46), 1));
        nativeDocumentNext = new Button(this); nativeDocumentNext.setText("Bagian berikutnya");
        nav.addView(nativeDocumentNext, new LinearLayout.LayoutParams(0, dp(46), 1)); panel.addView(nav);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false);
        nativeDocumentEdit = createAccessibleDocumentEditText("Tulis sesuatu di sini");
        nativeDocumentEdit.setHint("Tulis sesuatu di sini");
        scroll.addView(nativeDocumentEdit, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        nativeDocumentPrev.setOnClickListener(v -> switchNativeDocumentChunk(-1));
        nativeDocumentNext.setOnClickListener(v -> switchNativeDocumentChunk(1));
        AlertDialog dialog = UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Editor teks — " + nativeDocumentTitle)
                .setView(panel).setNegativeButton("Batal", null).setPositiveButton("Selesai", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                saveActiveNativeDocumentChunk();
                cachedText = joinChunks(nativeDocumentChunks);
                if (cachedText.length() <= 100000) {
                    cachedHtml = textToHtml(cachedText, true);
                    dialog.dismiss();
                    loadHtml(cachedHtml);
                } else {
                    cachedHtml = "";
                    nativeTextEditorActive = true;
                    dialog.dismiss();
                }
                setStatus("Teks " + nativeDocumentTitle + " selesai diedit. Bagian-bagian digabungkan saat penyimpanan.");
            });
        });
        dialog.show(); dialog.getWindow().setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT);
        loadActiveNativeDocumentChunk();
    }

    private void saveActiveNativeDocumentChunk() {
        if (nativeDocumentEdit != null && nativeDocumentChunks != null && activeNativeDocumentChunk >= 0 && activeNativeDocumentChunk < nativeDocumentChunks.size())
            nativeDocumentChunks.set(activeNativeDocumentChunk, nativeDocumentEdit.getText() == null ? "" : nativeDocumentEdit.getText().toString());
    }

    private void loadActiveNativeDocumentChunk() {
        if (nativeDocumentChunks == null || nativeDocumentChunks.isEmpty()) nativeDocumentChunks = splitIntoChunks("", NATIVE_EDIT_CHUNK_CHARS);
        if (activeNativeDocumentChunk >= nativeDocumentChunks.size()) activeNativeDocumentChunk = nativeDocumentChunks.size() - 1;
        nativeDocumentChunkLabel.setText("Bagian " + (activeNativeDocumentChunk + 1) + " dari " + nativeDocumentChunks.size());
        nativeDocumentPrev.setEnabled(activeNativeDocumentChunk > 0);
        nativeDocumentNext.setEnabled(activeNativeDocumentChunk < nativeDocumentChunks.size() - 1);
        nativeDocumentEdit.setText(nativeDocumentChunks.get(activeNativeDocumentChunk));
        nativeDocumentEdit.setSelection(0);
    }

    private void switchNativeDocumentChunk(int direction) {
        saveActiveNativeDocumentChunk();
        int target = activeNativeDocumentChunk + direction;
        if (target < 0 || target >= nativeDocumentChunks.size()) return;
        activeNativeDocumentChunk = target; loadActiveNativeDocumentChunk();
    }

    private String pdfPageTextToHtml(String text) {
        String source = text == null ? "" : text.replace("\r\n", "\n").replace("\r", "\n");
        StringBuilder html = new StringBuilder();
        String[] lines = source.split("\n", -1);
        for (String line : lines) {
            String escaped = htmlEscape(line).replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;");
            html.append("<p>").append(escaped.isEmpty() ? "<br>" : escaped).append("</p>");
        }
        return html.toString();
    }

    private String joinPdfPages(ArrayList<String> pages) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) out.append('\f');
            out.append(pages.get(i) == null ? "" : pages.get(i));
        }
        return out.toString();
    }

    private String getDisplayName(Uri uri) {
        String name = "Dokumen";
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i); }
        } catch (Exception ignored) {}
        return name;
    }
    // Pembacaan dibatasi agar berkas teks raksasa tidak menggandakan ratusan MB di heap/WebView.
    // Untuk berkas yang melampaui batas, bagian awal tetap dapat ditinjau tanpa mematikan aplikasi.
    private String readAll(InputStream in) throws Exception {
        if (in == null) throw new IllegalStateException("Berkas tidak bisa dibaca");
        final int maxBytes = 16 * 1024 * 1024;
        try (InputStream src = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n; boolean truncated = false;
            while ((n = src.read(b)) != -1) {
                int room = maxBytes - out.size();
                if (room <= 0) { truncated = true; break; }
                if (n > room) { out.write(b, 0, room); truncated = true; break; }
                out.write(b, 0, n);
            }
            String text = out.toString("UTF-8");
            if (truncated) text += "\n\n[Pratinjau dipotong pada 16 MB untuk menjaga kestabilan perangkat. Gunakan editor khusus atau pecah berkas menjadi beberapa bagian untuk mengedit keseluruhan isi.]";
            return text;
        }
    }

    private String extractPdf(Uri uri, int requestedStartPage, int requestedEndPage) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(in, MemoryUsageSetting.setupTempFileOnly())) {
            final int maxOutputChars = 250 * 1024;
            // Keep the editable WebView/accessibility tree bounded on low-memory devices.
            // The original PDF remains available in the single-page PDF image viewer.
            final int maxPagesPerImport = 20;
            final StringBuilder buffer = new StringBuilder();
            final boolean[] truncated = new boolean[]{false};
            java.io.Writer bounded = new java.io.Writer() {
                @Override public void write(char[] cbuf, int off, int len) {
                    int room = maxOutputChars - buffer.length();
                    if (room <= 0) { truncated[0] = true; return; }
                    int take = Math.min(room, len);
                    buffer.append(cbuf, off, take);
                    if (take < len) truncated[0] = true;
                }
                @Override public void flush() {}
                @Override public void close() {}
            };
            PDFTextStripper stripper = new PDFTextStripper();
            // pdfbox-android 2.0.27.0 does not expose setPageSeparator().
            // Extract one page at a time and insert the page break ourselves.
            int startPage = Math.max(1, requestedStartPage);
            int pagesToRead = Math.min(doc.getNumberOfPages(), Math.min(requestedEndPage, startPage + maxPagesPerImport - 1));
            for (int page = startPage; page <= pagesToRead && !truncated[0]; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                stripper.writeText(doc, bounded);
                if (page < pagesToRead && buffer.length() < maxOutputChars) {
                    bounded.write("\f");
                }
            }
            if (truncated[0]) buffer.append("\n\n[Ekstraksi teks dipotong pada batas aman 250 KB agar editor dan pembaca layar tetap responsif.]");
            if (doc.getNumberOfPages() > pagesToRead) buffer.append("\n\n[PDF asli memiliki ").append(doc.getNumberOfPages()).append(" halaman. Rentang teks yang diekstrak: halaman ").append(startPage).append("–").append(pagesToRead).append(". Buka PDF lagi untuk mengekstrak rentang selanjutnya; maksimal 20 halaman per sesi agar editor dan pembaca layar tetap ringan.]");
            return buffer.toString();
        }
    }

    private String extractZipOfficeText(Uri uri, String lowerName) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return extractZipOfficeTextFromStream(in, lowerName);
        }
    }

    public static String extractZipOfficeTextStatic(File file, String lowerName) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            return extractZipOfficeTextFromStream(in, lowerName);
        }
    }

    private static String extractZipOfficeTextFromStream(InputStream in, String lowerName) throws Exception {
        java.util.TreeMap<String, String> entries = new java.util.TreeMap<>();
        final long maxXmlBytesTotal = 24L * 1024L * 1024L;
        final int maxSingleXmlBytes = 8 * 1024 * 1024;
        final boolean[] zipTruncated = new boolean[]{false};
        long totalXmlBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().endsWith(".xml")) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[4096]; int count;
                while ((count = zip.read(b)) != -1) {
                    if (out.size() + count > maxSingleXmlBytes || totalXmlBytes + out.size() + count > maxXmlBytesTotal) {
                        int room = Math.min(maxSingleXmlBytes - out.size(), (int)Math.max(0, maxXmlBytesTotal - totalXmlBytes - out.size()));
                        if (room > 0) out.write(b, 0, Math.min(room, count));
                        zipTruncated[0] = true;
                        break;
                    }
                    out.write(b, 0, count);
                }
                totalXmlBytes += out.size();
                entries.put(entry.getName(), new String(out.toByteArray(), StandardCharsets.UTF_8));
                if (zipTruncated[0]) break;
            }
        }
        ArrayList<String> chunks = new ArrayList<>();
        if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xlsm") || lowerName.endsWith(".xltx") || lowerName.endsWith(".xltm") || lowerName.endsWith(".ods")) {
            if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xlsm") || lowerName.endsWith(".xltx") || lowerName.endsWith(".xltm")) {
                ArrayList<String> shared = new ArrayList<>();
                String sharedXml = entries.get("xl/sharedStrings.xml");
                if (sharedXml != null) {
                    java.util.regex.Matcher si = java.util.regex.Pattern.compile("(?is)<si\\b[^>]*>(.*?)</si>").matcher(sharedXml);
                    while (si.find()) shared.add(stripXmlText(si.group(1)));
                }
                for (java.util.Map.Entry<String,String> e : entries.entrySet()) {
                    if (!e.getKey().matches("xl/worksheets/sheet[0-9]+\\.xml")) continue;
                    StringBuilder sheet = new StringBuilder();
                    java.util.regex.Matcher rows = java.util.regex.Pattern.compile("(?is)<row\\b[^>]*>(.*?)</row>").matcher(e.getValue());
                    while (rows.find()) {
                        java.util.regex.Matcher cells = java.util.regex.Pattern.compile("(?is)<c\\b([^>]*)>(.*?)</c>").matcher(rows.group(1));
                        boolean first = true;
                        while (cells.find()) {
                            if (!first) sheet.append('\t'); first = false;
                            String attrs = cells.group(1), cell = cells.group(2), value = "";
                            java.util.regex.Matcher vm = java.util.regex.Pattern.compile("(?is)<v[^>]*>(.*?)</v>").matcher(cell);
                            if (attrs.matches("(?s).*\\bt=\\\"s\\\".*") && vm.find()) {
                                try { int idx = Integer.parseInt(vm.group(1).trim()); if (idx >= 0 && idx < shared.size()) value = shared.get(idx); } catch (Exception ignored) {}
                            } else if (attrs.matches("(?s).*\\bt=\\\"inlineStr\\\".*")) value = stripXmlText(cell);
                            else if (vm.find(0)) value = decodeXmlEntities(vm.group(1).trim());
                            sheet.append(value);
                        }
                        sheet.append('\n');
                    }
                    if (sheet.length() > 0) chunks.add(sheet.toString().trim());
                }
            } else {
                String content = entries.get("content.xml");
                if (content != null) {
                    content = content.replaceAll("(?is)</table:table-cell>", "\t").replaceAll("(?is)</table:table-row>", "\n").replaceAll("(?is)</text:p>|</text:h>", "\n");
                    chunks.add(stripXmlText(content));
                }
            }
        } else if (lowerName.endsWith(".docx") || lowerName.endsWith(".docm") || lowerName.endsWith(".dotx") || lowerName.endsWith(".dotm")) {
            String xml = entries.get("word/document.xml");
            if (xml != null) chunks.add(stripXmlText(xml.replaceAll("(?is)</w:p>", "\n").replaceAll("(?is)</w:tab>", "\t")));
        } else if (lowerName.endsWith(".pptx") || lowerName.endsWith(".pptm") || lowerName.endsWith(".ppsx") || lowerName.endsWith(".potx") || lowerName.endsWith(".potm") || lowerName.endsWith(".ppsm")) {
            for (java.util.Map.Entry<String,String> e : entries.entrySet()) {
                if (e.getKey().matches("ppt/slides/slide[0-9]+\\.xml")) chunks.add(stripXmlText(e.getValue().replaceAll("(?is)</a:p>", "\n")));
            }
        } else if (lowerName.endsWith(".odt") || lowerName.endsWith(".odp")) {
            String xml = entries.get("content.xml");
            if (xml != null) chunks.add(stripXmlText(xml.replaceAll("(?is)</text:p>|</text:h>", "\n")));
        }
        String result = TextUtils.join("\n\n", chunks).trim();
        if (result.isEmpty()) throw new IllegalArgumentException("Teks tidak ditemukan atau format dokumen belum didukung.");
        if (zipTruncated[0]) result += "\n\n[Pratinjau dokumen besar dipotong pada batas aman 24 MB agar aplikasi tidak kehabisan memori.]";
        return result;
    }

    private static String stripXmlText(String xml) {
        if (xml == null) return "";
        return decodeXmlEntities(xml.replaceAll("(?is)<[^>]+>", ""));
    }
    private static String decodeXmlEntities(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#10;", "\n").replace("&#13;", "\r");
    }

    private String stripRtf(String raw) {
        String s = raw.replaceAll("(?s)\\\\fonttbl.*?}", "").replaceAll("\\\\'[0-9a-fA-F]{2}", "");
        s = s.replaceAll("\\\\par[d]?", "\n").replaceAll("\\\\tab", "\t").replaceAll("\\\\'[0-9a-fA-F]{2}", "");
        s = s.replaceAll("\\\\[a-zA-Z]+-?[0-9]* ?", "").replaceAll("[{}]", "");
        return s;
    }
    private boolean isImage(String n) { return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp") || n.endsWith(".bmp"); }
    private String extractHtmlBody(String html) {
        if (html == null) return "<p></p>";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?is)<body[^>]*>(.*?)</body>").matcher(html);
        return m.find() ? m.group(1) : html;
    }
    private String sanitizeHtmlFragment(String html) {
        if (html == null) return "<p></p>";
        String safe = html.replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?is)<iframe[^>]*>.*?</iframe>", "")
                .replaceAll("(?is)<object[^>]*>.*?</object>", "")
                .replaceAll("(?is)<embed[^>]*>", "")
                .replaceAll("(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "")
                .replaceAll("(?i)javascript:", "");
        return safe;
    }
    /** Pembaca PDF berbasis gambar: hanya satu halaman dirender pada satu waktu untuk menghemat RAM. */
    private void showPdfPageViewer() {
        if (lastPdfUri == null) {
            UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("PDF halaman gambar")
                .setMessage("Buka berkas PDF terlebih dahulu melalui tombol Buka berkas. Setelah itu, pilih fitur ini untuk melihat halaman PDF asli satu per satu.")
                .setPositiveButton("Mengerti", null).show();
            return;
        }
        setStatus("Menyiapkan pembaca PDF halaman gambar…");
        new Thread(() -> {
            int count = 0;
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(lastPdfUri, "r");
                 android.graphics.pdf.PdfRenderer renderer = new android.graphics.pdf.PdfRenderer(pfd)) {
                count = renderer.getPageCount();
            } catch (Exception e) {
                final String message = e.getMessage() == null ? "PDF tidak dapat dibuka." : e.getMessage();
                runOnUiThread(() -> UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE)).setTitle("Gagal membuka PDF")
                    .setMessage(message).show());
                return;
            }
            final int pages = count;
            runOnUiThread(() -> {
                if (pages <= 0) { setStatus("PDF tidak memiliki halaman."); return; }
                activePdfPageCount = pages;
                activePdfPage = 0;
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(8), dp(8), dp(8), dp(8));
                pdfPageCounter = new TextView(this);
                pdfPageCounter.setGravity(Gravity.CENTER);
                pdfPageCounter.setTextSize(15);
                box.addView(pdfPageCounter, new LinearLayout.LayoutParams(-1, dp(42)));
                pdfPageImage = new android.widget.ImageView(this);
                pdfPageImage.setAdjustViewBounds(true);
                pdfPageImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                pdfPageImage.setBackgroundColor(Color.rgb(235, 237, 240));
                box.addView(pdfPageImage, new LinearLayout.LayoutParams(-1, 0, 1f));
                LinearLayout buttons = new LinearLayout(this);
                buttons.setOrientation(LinearLayout.HORIZONTAL);
                Button previous = new Button(this); previous.setText("← Halaman sebelumnya"); previous.setAllCaps(false);
                Button next = new Button(this); next.setText("Halaman berikutnya →"); next.setAllCaps(false);
                buttons.addView(previous, new LinearLayout.LayoutParams(0, -2, 1f));
                buttons.addView(next, new LinearLayout.LayoutParams(0, -2, 1f));
                box.addView(buttons);
                AlertDialog dialog = UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE)).setTitle("PDF — halaman gambar")
                    .setView(box).create();
                previous.setOnClickListener(v -> { if (activePdfPage > 0) { activePdfPage--; renderPdfPage(activePdfPage); } });
                next.setOnClickListener(v -> { if (activePdfPage + 1 < activePdfPageCount) { activePdfPage++; renderPdfPage(activePdfPage); } });
                final float[] down = new float[]{0f, 0f};
                pdfPageImage.setOnTouchListener((v, event) -> {
                    if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) { down[0] = event.getX(); down[1] = event.getY(); return true; }
                    if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                        float dx = event.getX() - down[0], dy = event.getY() - down[1];
                        if (Math.abs(dx) > dp(60) && Math.abs(dx) > Math.abs(dy)) {
                            if (dx < 0 && activePdfPage + 1 < activePdfPageCount) activePdfPage++;
                            else if (dx > 0 && activePdfPage > 0) activePdfPage--;
                            renderPdfPage(activePdfPage);
                        }
                        return true;
                    }
                    return true;
                });
                dialog.setOnDismissListener(d -> { pdfPageImage = null; pdfPageCounter = null; });
                dialog.show();
                dialog.getWindow().setLayout(-1, (int)(getResources().getDisplayMetrics().heightPixels * 0.88f));
                renderPdfPage(activePdfPage);
                setStatus("Pembaca PDF siap: " + pages + " halaman. Geser kiri/kanan pada halaman gambar.");
            });
        }).start();
    }

    private void renderPdfPage(int pageIndex) {
        final Uri source = lastPdfUri;
        if (source == null || pageIndex < 0 || pageIndex >= activePdfPageCount) return;
        if (pdfPageCounter != null) pdfPageCounter.setText("Memuat halaman " + (pageIndex + 1) + " dari " + activePdfPageCount + "…");
        new Thread(() -> {
            Bitmap bitmap = null;
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(source, "r");
                 android.graphics.pdf.PdfRenderer renderer = new android.graphics.pdf.PdfRenderer(pfd)) {
                try (android.graphics.pdf.PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                    // A smaller bound and RGB_565 halve bitmap memory on low-RAM phones.
                    float scale = Math.min(1.25f, Math.min(1200f / Math.max(1, page.getWidth()), 1200f / Math.max(1, page.getHeight())));
                    int width = Math.max(1, (int)(page.getWidth() * scale));
                    int height = Math.max(1, (int)(page.getHeight() * scale));
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
                    bitmap.eraseColor(Color.WHITE);
                    android.graphics.Matrix matrix = new android.graphics.Matrix(); matrix.setScale(scale, scale);
                    page.render(bitmap, null, matrix, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                }
            } catch (Exception ignored) {}
            final Bitmap rendered = bitmap;
            runOnUiThread(() -> {
                if (pdfPageImage == null || pageIndex != activePdfPage) { if (rendered != null) rendered.recycle(); return; }
                if (rendered == null) { if (pdfPageCounter != null) pdfPageCounter.setText("Halaman tidak dapat dirender."); return; }
                android.graphics.drawable.Drawable old = pdfPageImage.getDrawable();
                pdfPageImage.setImageBitmap(rendered);
                if (old instanceof android.graphics.drawable.BitmapDrawable) {
                    Bitmap oldBitmap = ((android.graphics.drawable.BitmapDrawable) old).getBitmap();
                    if (oldBitmap != rendered && !oldBitmap.isRecycled()) oldBitmap.recycle();
                }
                if (pdfPageCounter != null) pdfPageCounter.setText("Halaman " + (pageIndex + 1) + " dari " + activePdfPageCount + " • geser kiri/kanan untuk berpindah");
            });
        }).start();
    }

    private String textToHtml(String text) { return textToHtml(text, false); }
    // Each editable page is limited to 5,000 Unicode characters to reduce WebView/accessibility load.
    private String textToHtml(String text, boolean preserveSourcePages) {
        String source = text == null ? "" : text.replace("\r\n", "\n").replace("\r", "\n");
        String[] sourcePages = source.split("\\f", -1);
        ArrayList<String> pages = new ArrayList<>();
        final int maxCharsPerPage = 5000;
        final int maxPages = 1000;
        for (String sourcePage : sourcePages) {
            int start = 0;
            if (sourcePage.isEmpty()) { if (pages.size() < maxPages) pages.add(""); continue; }
            while (start < sourcePage.length() && pages.size() < maxPages) {
                int end = Math.min(sourcePage.length(), start + maxCharsPerPage);
                if (end < sourcePage.length()) {
                    int newline = sourcePage.lastIndexOf('\n', end - 1);
                    if (newline > start) end = newline + 1; // Prefer a paragraph/line boundary.
                }
                if (end <= start) end = Math.min(sourcePage.length(), start + maxCharsPerPage);
                pages.add(sourcePage.substring(start, end));
                start = end;
            }
        }
        if (pages.isEmpty()) pages.add("");
        StringBuilder html = new StringBuilder(Math.min(source.length() + 256, 2 * 1024 * 1024));
        for (int i = 0; i < pages.size(); i++) {
            html.append("<div class=\"anas-page\" data-anas-page=\"").append(i + 1)
                .append("\" aria-label=\"Halaman ").append(i + 1).append("\">");
            String[] paragraphs = pages.get(i).split("\n", -1);
            for (String paragraph : paragraphs) {
                String escaped = htmlEscape(paragraph).replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;");
                html.append("<p>").append(escaped.isEmpty() ? "<br>" : escaped).append("</p>");
            }
            html.append("</div>");
        }
        return html.toString();
    }
    private String htmlEscape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private String decodeJsString(String value) {
        if (value == null || "null".equals(value)) return "";
        try { return new org.json.JSONArray("[" + value + "]").getString(0); } catch (Exception ignored) { return value; }
    }

    private OutputStream openOutputStreamForUri(Uri uri) throws Exception {
        if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) throw new IOException("Lokasi berkas tidak valid");
            return new FileOutputStream(new File(path), false);
        }
        return getContentResolver().openOutputStream(uri, "wt");
    }

    private void saveToUri(Uri uri, String type, String html, String text) throws Exception {
        if ("pdf".equals(type)) { writePdf(uri, text); return; }
        try (OutputStream out = openOutputStreamForUri(uri)) {
            if (out == null) throw new IllegalStateException("Tidak bisa menulis berkas tujuan");
            if ("html".equals(type)) out.write(editorShell((html == null || html.isEmpty()) ? textToHtml(text, true) : html).getBytes(StandardCharsets.UTF_8));
            else if ("txt".equals(type)) out.write(text.getBytes(StandardCharsets.UTF_8));
            else if ("md".equals(type)) out.write(textToMarkdown(text).getBytes(StandardCharsets.UTF_8));
            else if ("rtf".equals(type)) out.write(("{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Liberation Serif;}}\\f0\\fs24 " + rtfEscape(text) + "}").getBytes(StandardCharsets.UTF_8));
            else if ("docx".equals(type)) writeDocx(out, (html == null || html.isEmpty()) ? textToHtml(text, true) : html, text, false);
            else if ("dotx".equals(type)) writeDocx(out, (html == null || html.isEmpty()) ? textToHtml(text, true) : html, text, true);
            else if ("odt".equals(type)) writeOdt(out, text);
        }
    }

    private void writeOdt(OutputStream target, String text) throws Exception {
        ZipOutputStream zip = new ZipOutputStream(target);
        byte[] mimeBytes = "application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.UTF_8);
        ZipEntry mimeEntry = new ZipEntry("mimetype"); mimeEntry.setMethod(ZipEntry.STORED); mimeEntry.setSize(mimeBytes.length); mimeEntry.setCompressedSize(mimeBytes.length); java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(mimeBytes); mimeEntry.setCrc(crc.getValue()); zip.putNextEntry(mimeEntry); zip.write(mimeBytes); zip.closeEntry();
        putZip(zip, "META-INF/manifest.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" manifest:version=\"1.2\"><manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"application/vnd.oasis.opendocument.text\"/><manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/></manifest:manifest>");
        StringBuilder body = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-content xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" office:version=\"1.2\"><office:body><office:text>");
        for (String line : (text == null ? "" : text).split("\n", -1)) body.append("<text:p>").append(xmlEscape(line)).append("</text:p>");
        body.append("</office:text></office:body></office:document-content>"); putZip(zip, "content.xml", body.toString()); zip.finish(); zip.flush();
    }

    private String textToMarkdown(String text) { return text.replaceAll("(?m)^(.{1,100})$", "$1"); }
    private String rtfEscape(String text) { return text.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("\n", "\\par\n"); }

    private void writeDocx(OutputStream target, String html, String text, boolean template) throws Exception {
        android.content.SharedPreferences prefs = getSharedPreferences("document_studio", MODE_PRIVATE);
        WordExporter.Options opt = WordExporter.Options.fromPrefs(prefs, selectedFont, cssFontFamily(), selectedSize);
        WordExporter.writeDocx(this, target, html, text, template, opt, this::appendDocxRuns);
    }

    /**
     * Returns true when the character at index carries the requested Typeface style.
     * Html.fromHtml represents bold/italic formatting as StyleSpan instances; checking
     * them explicitly keeps DOCX export independent of a missing helper implementation.
     */
    private boolean hasStyle(android.text.Spanned sp, int index, int requestedStyle) {
        if (sp == null || index < 0 || index >= sp.length()) return false;
        android.text.style.StyleSpan[] spans = sp.getSpans(index, index + 1, android.text.style.StyleSpan.class);
        for (android.text.style.StyleSpan span : spans) {
            if ((span.getStyle() & requestedStyle) == requestedStyle) return true;
        }
        return false;
    }

    private void appendDocxRuns(StringBuilder xml, android.text.Spanned sp, int start, int end) {
        int i = start;
        while (i < end) {
            boolean bold = hasStyle(sp, i, android.graphics.Typeface.BOLD);
            boolean italic = hasStyle(sp, i, android.graphics.Typeface.ITALIC);
            boolean underline = sp.getSpans(i, i + 1, android.text.style.UnderlineSpan.class).length > 0;
            boolean strike = sp.getSpans(i, i + 1, android.text.style.StrikethroughSpan.class).length > 0;
            boolean superScript = sp.getSpans(i, i + 1, android.text.style.SuperscriptSpan.class).length > 0;
            boolean subScript = sp.getSpans(i, i + 1, android.text.style.SubscriptSpan.class).length > 0;
            int color = foregroundColorAt(sp, i);
            int highlight = backgroundColorAt(sp, i);
            int fontSize = fontSizeAt(sp, i);
            int j = i + 1;
            while (j < end && sameDocxStyle(sp, j, bold, italic, underline, strike, superScript, subScript, color, highlight, fontSize)) j++;
            xml.append("<w:r><w:rPr><w:rFonts w:ascii=\"").append(xmlEscape(selectedFont)).append("\" w:hAnsi=\"").append(xmlEscape(selectedFont)).append("\"/>");
            if (bold) xml.append("<w:b/>"); if (italic) xml.append("<w:i/>"); if (underline) xml.append("<w:u w:val=\"single\"/>"); if (strike) xml.append("<w:strike/>");
            if (superScript) xml.append("<w:vertAlign w:val=\"superscript\"/>"); if (subScript) xml.append("<w:vertAlign w:val=\"subscript\"/>");
            if (color != Color.BLACK) xml.append("<w:color w:val=\"").append(String.format(Locale.ROOT, "%06X", (0xFFFFFF & color))).append("\"/>");
            if (highlight != Color.TRANSPARENT && highlight != Color.WHITE) xml.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(String.format(Locale.ROOT, "%06X", (0xFFFFFF & highlight))).append("\"/>");
            xml.append("<w:sz w:val=\"").append(Math.max(8, fontSize) * 2).append("\"/></w:rPr><w:t xml:space=\"preserve\">").append(xmlEscape(sp.subSequence(i, j).toString())).append("</w:t></w:r>");
            i = j;
        }
    }

    private int foregroundColorAt(android.text.Spanned sp, int index) {
        android.text.style.ForegroundColorSpan[] spans = sp.getSpans(index, index + 1, android.text.style.ForegroundColorSpan.class);
        return spans.length == 0 ? Color.BLACK : spans[spans.length - 1].getForegroundColor();
    }

    private int backgroundColorAt(android.text.Spanned sp, int index) {
        android.text.style.BackgroundColorSpan[] spans = sp.getSpans(index, index + 1, android.text.style.BackgroundColorSpan.class);
        return spans.length == 0 ? Color.TRANSPARENT : spans[spans.length - 1].getBackgroundColor();
    }

    private int fontSizeAt(android.text.Spanned sp, int index) {
        android.text.style.AbsoluteSizeSpan[] absolute = sp.getSpans(index, index + 1, android.text.style.AbsoluteSizeSpan.class);
        if (absolute.length > 0) {
            int size = absolute[absolute.length - 1].getSize();
            if (absolute[absolute.length - 1].getDip()) return Math.max(8, Math.round(size * getResources().getDisplayMetrics().scaledDensity / getResources().getDisplayMetrics().density));
            return Math.max(8, Math.round(size / getResources().getDisplayMetrics().density));
        }
        android.text.style.RelativeSizeSpan[] relative = sp.getSpans(index, index + 1, android.text.style.RelativeSizeSpan.class);
        if (relative.length > 0) return Math.max(8, Math.round(selectedSize * relative[relative.length - 1].getSizeChange()));
        return selectedSize;
    }

    private boolean sameDocxStyle(android.text.Spanned sp, int other, boolean bold, boolean italic, boolean underline, boolean strike, boolean superscript, boolean subscript, int color, int highlight, int fontSize) {
        return hasStyle(sp, other, android.graphics.Typeface.BOLD) == bold
            && hasStyle(sp, other, android.graphics.Typeface.ITALIC) == italic
            && (sp.getSpans(other, other + 1, android.text.style.UnderlineSpan.class).length > 0) == underline
            && (sp.getSpans(other, other + 1, android.text.style.StrikethroughSpan.class).length > 0) == strike
            && (sp.getSpans(other, other + 1, android.text.style.SuperscriptSpan.class).length > 0) == superscript
            && (sp.getSpans(other, other + 1, android.text.style.SubscriptSpan.class).length > 0) == subscript
            && foregroundColorAt(sp, other) == color
            && backgroundColorAt(sp, other) == highlight
            && fontSizeAt(sp, other) == fontSize;
    }
    private void putZip(ZipOutputStream zip, String name, String data) throws Exception { zip.putNextEntry(new ZipEntry(name)); zip.write(data.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
    private String xmlEscape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }

    private void writePdf(Uri uri, String text) throws Exception {
        try (OutputStream out = openOutputStreamForUri(uri)) {
            if (out == null) throw new IllegalStateException("Tidak bisa menulis PDF tujuan");
            android.content.SharedPreferences prefs = getSharedPreferences("document_studio", MODE_PRIVATE);
            WordExporter.Options opt = WordExporter.Options.fromPrefs(prefs, selectedFont, cssFontFamily(), selectedSize);
            WordExporter.writePdf(this, out, text, opt);
        }
    }

    private void setStatus(String text) { if (status != null) status.setText(text); }
    private void showError(String title, Exception e) { String message = e == null ? "Kesalahan tidak diketahui" : e.getMessage(); UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE)).setTitle(title).setMessage((message == null ? "Operasi gagal" : message) + "\n\nBerkas mungkin memakai format, enkripsi, atau elemen yang belum didukung.").show(); setStatus(title); }
    @Override protected void onPause() {
        try {
            if (wordEdit != null && !pdfDocumentActive) {
                wordEdit.removeCallbacks(draftAutosaveRunnable);
                if (documentPagedMode) saveActiveDocumentPage();
                writeDraft(currentName, documentPagedMode ? joinDocumentPages() : (wordEdit.getText() == null ? "" : wordEdit.getText().toString()));
            }
        } catch (Throwable ignored) { }
        super.onPause();
    }

    @Override protected void onDestroy() {
        try { if (readingTts != null) { readingTts.stop(); readingTts.shutdown(); readingTts = null; } } catch (Throwable ignored) {}
        super.onDestroy();
    }

    // ====================== WORD PRO v23.0 — UI KELOMPOK, AKSESIBILITAS & ALAT DESKTOP ======================

    private static final int REQ_MERGE = 8106;
    private LinearLayout accessibleReadingBar;
    private HorizontalScrollView collapsibleToolbarScroll;

    private View rootLayoutOrStatus() {
        return status != null ? status : wordEdit;
    }

    private void toggleQuickToolbar() {
        if (collapsibleToolbarScroll == null) return;
        boolean show = collapsibleToolbarScroll.getVisibility() != View.VISIBLE;
        collapsibleToolbarScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        String msg = show ? "Bilah format cepat ditampilkan." : "Bilah format cepat disembunyikan.";
        setStatus(msg);
        OfficeUtils.announce(rootLayoutOrStatus(), msg);
    }

    private void toggleAccessibleReadingBar() {
        if (accessibleReadingBar == null) return;
        boolean show = accessibleReadingBar.getVisibility() != View.VISIBLE;
        accessibleReadingBar.setVisibility(show ? View.VISIBLE : View.GONE);
        String msg = show ? "Bilah baca aksesibel dibuka. Tersedia tombol lompat paragraf, baca suara, dan info kursor." : "Bilah baca aksesibel ditutup.";
        setStatus(msg);
        OfficeUtils.announce(rootLayoutOrStatus(), msg);
    }

    private void showBerkasGroupMenu() {
        String[] items = {
            "Buka berkas dokumen (DOCX, PDF, ODT, TXT, HTML, RTF)",
            "Pembaca PDF Envision + OCR Multi-Bahasa (15+ Bahasa)",
            "Editor Kode & Teks (Lua, TXT, Java, JSON, HTML)",
            "Ekstrak Teks dari Foto / Gambar Galeri (OCR Offline)",
            "Gabungkan isi berkas lain ke posisi kursor",
            "Simpan / Ekspor (DOCX, PDF, HTML, ODT, RTF, MD, TXT)",
            "Bagikan cepat sebagai DOCX (WhatsApp / Email)",
            "Bagikan cepat sebagai PDF (WhatsApp / Email)",
            "File Manager (Telusuri & Pilih Folder Simpan)",
            "Brankas Pekerjaan (Pulihkan Draf Otomatis)",
            "PDF Halaman Gambar (Tinjau PDF Scan / Asli)"
        };
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Berkas & Alat Ekstraktor").setItems(items, (d, which) -> {
            if (which == 0) openFilePicker();
            else if (which == 1) startActivity(new Intent(this, PdfReaderActivity.class));
            else if (which == 2) startActivity(new Intent(this, CodeTextEditorActivity.class));
            else if (which == 3) launchOcrImagePicker();
            else if (which == 4) openMergeFilePicker();
            else if (which == 5) showSaveOptions();
            else if (which == 6) quickShareWordFile("docx", ".docx");
            else if (which == 7) quickShareWordFile("pdf", ".pdf");
            else if (which == 8) openBuiltInFileManager();
            else if (which == 9) showDraftVault();
            else showPdfPageViewer();
        }).show();
    }

    private void showFormatGroupMenu() {
        String[] items = {
            (collapsibleToolbarScroll != null && collapsibleToolbarScroll.getVisibility() == View.VISIBLE ? "Sembunyikan" : "Tampilkan") + " bilah format cepat (Font, B/I/U)",
            "Tebal (Bold) pada teks terpilih",
            "Miring (Italic) pada teks terpilih",
            "Garis Bawah (Underline)",
            "Coret (Strikethrough)",
            "Gaya Judul & Paragraf (Heading 1–3, Kutipan, Kode)",
            "Perataan Paragraf (Kiri, Tengah, Kanan, Rata Penuh)",
            "Daftar Poin (Bullet) / Daftar Bernomor",
            "Warna Teks",
            "Warna Sorot (Highlight)",
            "Subskrip / Superskrip",
            "Periksa Format & Posisi Kursor (Reveal Formatting)",
            "Bersihkan semua format teks terpilih"
        };
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Format & Gaya Teks").setItems(items, (d, which) -> {
            if (which == 0) toggleQuickToolbar();
            else if (which == 1) applyNativeWordCommand("bold", null);
            else if (which == 2) applyNativeWordCommand("italic", null);
            else if (which == 3) applyNativeWordCommand("underline", null);
            else if (which == 4) applyNativeWordCommand("strikeThrough", null);
            else if (which == 5) showStylePicker();
            else if (which == 6) showAlignPicker();
            else if (which == 7) {
                UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Sisipkan Daftar").setItems(new String[]{"Daftar Poin (•)", "Daftar Nomor (1.)"}, (dd, w) ->
                    applyNativeWordCommand(w == 0 ? "insertUnorderedList" : "insertOrderedList", null)).show();
            } else if (which == 8) showColorPicker(false);
            else if (which == 9) showColorPicker(true);
            else if (which == 10) {
                UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Posisi Huruf").setItems(new String[]{"Subskrip (H₂O)", "Superskrip (x²)"}, (dd, w) ->
                    applyNativeWordCommand(w == 0 ? "subscript" : "superscript", null)).show();
            } else if (which == 11) showCursorFormattingInspector();
            else applyNativeWordCommand("removeFormat", null);
        }).show();
    }

    private void showAlignPicker() {
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Perataan Paragraf")
            .setItems(new String[]{"Rata Kiri", "Rata Tengah", "Rata Kanan", "Rata Kiri-Kanan"}, (d, w) -> {
                String cmd = w == 1 ? "justifyCenter" : w == 2 ? "justifyRight" : w == 3 ? "justifyFull" : "justifyLeft";
                applyNativeWordCommand(cmd, null);
            }).show();
    }

    private void showInsertAndTemplateMenu() {
        String[] items = {
            "Buat atau Sunting dengan Asisten AI (Premium)",
            "Template Dokumen Siap Pakai (10 Kategori Lengkap)",
            "Template Kustom Saya (Simpan & Pakai Ulang)",
            "Profil Pengirim & Kop Surat Tersimpan",
            "Sisipkan Gambar Asli (Tampil di DOCX & PDF)",
            "Sisipkan Tabel Rapi",
            "Sisipkan Catatan Kaki (Footnote)",
            "Sisipkan Komentar Review",
            "Header, Footer & Nomor Halaman Berulang",
            "Daftar Isi Otomatis (dari Judul Bab)",
            "Pemisah Halaman / + Halaman Buku Baru",
            "Sisipkan Tanggal Hari Ini & Tautan URL"
        };
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Sisip, Template & Asisten AI").setItems(items, (d, which) -> {
            if (which == 0) {
                openWordAiAssistant();
            }
            else if (which == 1) showDocumentTemplates();
            else if (which == 2) showCustomTemplatesMenu();
            else if (which == 3) showSenderProfileDialog();
            else if (which == 4) pickImage();
            else if (which == 5) insertTable();
            else if (which == 6) insertFootnoteDialog();
            else if (which == 7) insertCommentNote();
            else if (which == 8) insertHeaderFooter();
            else if (which == 9) insertTableOfContents();
            else if (which == 10) insertPageBreak();
            else {
                UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Tanggal atau Tautan")
                    .setItems(new String[]{"Tanggal Hari Ini (Indonesia)", "Jam & Tanggal Lengkap", "Tautan URL (https://)"}, (dd, w) -> {
                        if (w == 0) insertNativeTextAtCursor(new java.text.SimpleDateFormat("dd MMMM yyyy", new Locale("id", "ID")).format(new java.util.Date()));
                        else if (w == 1) insertNativeTextAtCursor(new java.text.SimpleDateFormat("dd MMMM yyyy HH:mm", new Locale("id", "ID")).format(new java.util.Date()));
                        else insertLink();
                    }).show();
            }
        }).show();
    }

    private void showNavigationAndReadMenu() {
        String[] items = {
            "Tampilkan / Tutup Bilah Baca Aksesibel (Paragraf & Suara)",
            "Mode Baca Penuh Dokumen & PDF (TTS)",
            "Panel Navigasi — Lompat ke Judul / Bab",
            "Kelola Halaman Buku (Tambah, Duplikat, Geser, Hapus)",
            "Cari Teks",
            "Cari & Ganti Semua (Peka Huruf Besar/Kecil)",
            "Statistik Lengkap & Perkiraan Waktu Baca",
            "Pengaturan Kertas (A4/Letter/Legal/F4), Margin, Spasi & 2 Kolom",
            "Toggle Tata Letak 1 Kolom / 2 Kolom Cepat",
            "Mode Fokus Membaca (Perbesar Jarak Baris)",
            "Akses & Panduan Format Berkas"
        };
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Navigasi, Halaman & Pembaca").setItems(items, (d, which) -> {
            if (which == 0) toggleAccessibleReadingBar();
            else if (which == 1) showAccessibleReadingMode();
            else if (which == 2) showNavigationOutline();
            else if (which == 3) showBookPageManager();
            else if (which == 4) findOrReplace(false);
            else if (which == 5) findOrReplace(true);
            else if (which == 6) countWords();
            else if (which == 7) showEditorSettings();
            else if (which == 8) toggleColumns();
            else if (which == 9) showReadingMode();
            else showStorageHelp();
        }).show();
    }

    private void openMergeFilePicker() {
        OfficeUtils.launchFilePicker(this, REQ_MERGE, "office", "Pilih Dokumen untuk Digabung");
    }

    private void quickShareWordFile(String type, String ext) {
        final String saveText;
        final String saveHtml;
        if (pdfDocumentActive) {
            saveActivePdfPageText();
            saveText = joinPdfPages(pdfPageTexts);
            saveHtml = saveText.length() <= 100000 ? pdfPagesToHtml(pdfPageTexts) : "";
        } else {
            if (documentPagedMode) {
                saveActiveDocumentPage();
                saveText = joinDocumentPages();
            } else {
                saveText = wordEdit == null ? cachedText : wordEdit.getText().toString();
            }
            saveHtml = saveText.contains("\f") ? textToHtml(saveText, true) : nativeWordHtml();
        }
        String base = (currentName == null || currentName.trim().isEmpty() ? "Dokumen" : currentName).replaceAll("\\.[^.]+$", "").replaceAll("[\\\\/:*?\"<>|]", "_");
        setStatus("Menyiapkan " + base + ext + " untuk dibagikan…");
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "shared_exports");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, base + ext);
                saveToUri(Uri.fromFile(f), type, saveHtml, saveText);
                runOnUiThread(() -> OfficeUtils.shareLocalFile(this, f, f.getName()));
            } catch (Exception e) {
                runOnUiThread(() -> showError("Gagal menyiapkan dokumen untuk dibagikan", e));
            }
        }).start();
    }

    private void insertFootnoteDialog() {
        EditText fn = new EditText(this);
        fn.setHint("Tulis isi catatan kaki / referensi sumber");
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Sisipkan Catatan Kaki (Footnote)").setView(fn)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Sisipkan", (d, w) -> {
                String txt = fn.getText().toString().trim();
                if (txt.isEmpty()) return;
                String all = wordEdit == null ? "" : wordEdit.getText().toString();
                int idx = 1;
                while (all.contains("[FN-" + idx + "]")) idx++;
                insertNativeTextAtCursor("[" + idx + "]");
                if (wordEdit != null) {
                    wordEdit.getText().append("\n[Catatan Kaki " + idx + ": " + txt + "]\n");
                }
                OfficeUtils.announce(rootLayoutOrStatus(), "Catatan kaki nomor " + idx + " disisipkan.");
            }).show();
    }

    private void showNavigationOutline() {
        if (wordEdit == null) return;
        String text = wordEdit.getText().toString();
        String[] lines = text.split("\n", -1);
        final ArrayList<String> titles = new ArrayList<>();
        final ArrayList<Integer> offsets = new ArrayList<>();
        int running = 0;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            boolean isHeading = !trimmed.isEmpty() && trimmed.length() <= 100 && (
                    trimmed.matches("(?i)^(BAB\\s+.*|PASAL\\s+.*|BAGIAN\\s+.*|PENDAHULUAN|PEMBAHASAN|KESIMPULAN|DAFTAR ISI|DAFTAR PUSTAKA|LAMPIRAN|ABSTRAK|LATAR BELAKANG|TUJUAN|METODE).*")
                    || trimmed.matches("^[IVX]+\\.[ \t]+.*")
                    || trimmed.matches("^[0-9]+(\\.[0-9]+)*\\.[ \t]+.*")
                    || (trimmed.length() >= 4 && trimmed.equals(trimmed.toUpperCase(Locale.ROOT)) && trimmed.matches(".*[A-Z].*"))
            );
            if (isHeading) {
                titles.add("Baris " + (i + 1) + ": " + trimmed);
                offsets.add(running);
            }
            running += lines[i].length() + 1;
        }
        if (titles.isEmpty()) {
            UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE)).setTitle("Panel Navigasi Judul")
                .setMessage("Belum ditemukan judul bab (misal 'BAB I', 'PENDAHULUAN', '1. Judul', atau huruf kapital). Tulis judul di dokumen untuk navigasi cepat.")
                .show();
            return;
        }
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Lompat ke Judul / Bab (" + titles.size() + ")")
            .setItems(titles.toArray(new String[0]), (d, which) -> {
                jumpNativeDocumentToOffset(offsets.get(which));
                String msg = "Lompat ke " + titles.get(which);
                setStatus(msg);
                OfficeUtils.announce(wordEdit, msg);
            }).show();
    }

    private void showCursorFormattingInspector() {
        if (wordEdit == null) return;
        int pos = Math.max(0, Math.min(wordEdit.getSelectionStart(), wordEdit.length()));
        android.text.Editable sp = wordEdit.getText();
        String full = sp.toString();
        int line = 1;
        for (int i = 0; i < pos; i++) if (full.charAt(i) == '\n') line++;
        boolean bold = hasStyle(sp, Math.max(0, pos - 1), Typeface.BOLD);
        boolean italic = hasStyle(sp, Math.max(0, pos - 1), Typeface.ITALIC);
        boolean under = pos > 0 && sp.getSpans(pos - 1, pos, android.text.style.UnderlineSpan.class).length > 0;
        StringBuilder styles = new StringBuilder();
        if (bold) styles.append("Tebal, ");
        if (italic) styles.append("Miring, ");
        if (under) styles.append("Garis Bawah, ");
        String styleDesc = styles.length() == 0 ? "Normal" : styles.substring(0, styles.length() - 2);
        String pageInfo = (!pdfDocumentActive && documentPagedMode && documentPages != null)
                ? "Halaman " + (activeDocumentPage + 1) + " dari " + documentPages.size()
                : (pdfDocumentActive ? "Halaman PDF " + (activePdfTextPage + 1) : "Dokumen 1 bagian");
        String msg = pageInfo + " • Baris ke-" + line + " • Posisi karakter " + pos + " • Font: " + selectedFont + " " + selectedSize + "pt • Gaya: " + styleDesc;
        setStatus(msg);
        OfficeUtils.announce(rootLayoutOrStatus(), msg);
        UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE)).setTitle("Pemeriksa Posisi & Format Kursor").setMessage(msg).show();
    }

    private void jumpParagraphAccessible(int dir) {
        if (wordEdit == null) return;
        String txt = wordEdit.getText().toString();
        int pos = Math.max(0, Math.min(wordEdit.getSelectionStart(), txt.length()));
        int targetStart;
        if (dir < 0) {
            int curParaStart = txt.lastIndexOf('\n', Math.max(0, pos - 1));
            if (curParaStart <= 0) targetStart = 0;
            else {
                int prevBreak = txt.lastIndexOf('\n', curParaStart - 1);
                targetStart = prevBreak < 0 ? 0 : prevBreak + 1;
            }
        } else {
            int nextBreak = txt.indexOf('\n', pos);
            while (nextBreak >= 0 && nextBreak + 1 < txt.length() && txt.charAt(nextBreak + 1) == '\n') nextBreak++;
            targetStart = (nextBreak < 0 || nextBreak + 1 >= txt.length()) ? txt.length() : nextBreak + 1;
        }
        jumpNativeDocumentToOffset(targetStart);
        int end = txt.indexOf('\n', targetStart);
        if (end < 0) end = txt.length();
        String para = txt.substring(targetStart, end).trim();
        String announceStr = para.isEmpty() ? "Paragraf kosong" : para;
        OfficeUtils.announce(wordEdit, announceStr);
        setStatus("Paragraf: " + (announceStr.length() > 70 ? announceStr.substring(0, 70) + "…" : announceStr));
    }

    private void speakFromCursorAccessible() {
        if (wordEdit == null) return;
        String txt = wordEdit.getText().toString();
        int pos = Math.max(0, Math.min(wordEdit.getSelectionStart(), txt.length()));
        String slice = txt.substring(pos).trim();
        if (slice.isEmpty()) slice = txt.trim();
        if (slice.isEmpty()) { Toast.makeText(this, "Dokumen masih kosong.", Toast.LENGTH_SHORT).show(); return; }
        String humanized = HumanSpeechNormalizer.normalizeForDocumentTts(slice);
        final String toSpeak = humanized.length() > 3500 ? humanized.substring(0, 3500) : humanized;
        if (readingTts == null) {
            readingTts = new TextToSpeech(this, st -> {
                readingTtsReady = st == TextToSpeech.SUCCESS;
                if (readingTtsReady && readingTts != null) {
                    try {
                        int res = readingTts.setLanguage(new Locale("id", "ID"));
                        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) readingTts.setLanguage(Locale.getDefault());
                    } catch (Throwable ignored) {}
                    readingTts.speak(toSpeak, TextToSpeech.QUEUE_FLUSH, null, "cursor_read");
                }
            });
        } else if (readingTtsReady) {
            readingTts.speak(toSpeak, TextToSpeech.QUEUE_FLUSH, null, "cursor_read");
        }
    }

    private void stopAccessibleTts() {
        try { if (readingTts != null) readingTts.stop(); } catch (Throwable ignored) {}
    }

    private void showSenderProfileDialog() {
        android.content.SharedPreferences sp = getSharedPreferences("document_sender_profile", MODE_PRIVATE);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(8), dp(14), dp(4));
        EditText nama = new EditText(this); nama.setHint("Nama Pengirim / Pimpinan"); nama.setText(sp.getString("nama", "")); box.addView(nama);
        EditText instansi = new EditText(this); instansi.setHint("Nama Instansi / Sekolah / Perusahaan"); instansi.setText(sp.getString("instansi", "")); box.addView(instansi);
        EditText alamat = new EditText(this); alamat.setHint("Alamat Lengkap"); alamat.setText(sp.getString("alamat", "")); box.addView(alamat);
        EditText kontak = new EditText(this); kontak.setHint("No. HP & Email"); kontak.setText(sp.getString("kontak", "")); box.addView(kontak);
        UiKit.dialog(this, getSharedPreferences("AnnasKeyboardPrefs", MODE_PRIVATE)).setTitle("Profil Pengirim & Kop Surat")
            .setMessage("Simpan identitas Anda sekali agar bisa langsung disisipkan menjadi Kop Surat atau Penutup Surat.")
            .setView(box)
            .setNeutralButton("Sisip Kop Surat", (d, w) -> {
                String n = nama.getText().toString().trim(), ins = instansi.getText().toString().trim(), al = alamat.getText().toString().trim(), kt = kontak.getText().toString().trim();
                sp.edit().putString("nama", n).putString("instansi", ins).putString("alamat", al).putString("kontak", kt).apply();
                StringBuilder kop = new StringBuilder();
                if (!ins.isEmpty()) kop.append(ins.toUpperCase(Locale.ROOT)).append("\n");
                if (!al.isEmpty()) kop.append(al).append("\n");
                if (!kt.isEmpty()) kop.append("Kontak: ").append(kt).append("\n");
                if (!n.isEmpty() && ins.isEmpty()) kop.append(n).append("\n");
                kop.append("========================================\n\n");
                insertNativeTextAtCursor(kop.toString());
            })
            .setPositiveButton("Simpan Profil", (d, w) -> {
                sp.edit().putString("nama", nama.getText().toString().trim())
                  .putString("instansi", instansi.getText().toString().trim())
                  .putString("alamat", alamat.getText().toString().trim())
                  .putString("kontak", kontak.getText().toString().trim()).apply();
                Toast.makeText(this, "Profil pengirim tersimpan.", Toast.LENGTH_SHORT).show();
            }).show();
    }

    private void showCustomTemplatesMenu() {
        File dir = new File(getFilesDir(), "custom_word_templates");
        if (!dir.exists()) dir.mkdirs();
        File[] files = dir.listFiles();
        ArrayList<File> list = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        names.add("+ Simpan dokumen aktif sebagai Template Kustom");
        if (files != null) {
            for (File f : files) if (f.isFile()) { list.add(f); names.add("Pakai: " + f.getName().replaceAll("\\.txt$", "")); }
        }
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Template Kustom Saya").setItems(names.toArray(new String[0]), (d, which) -> {
            if (which == 0) {
                EditText nm = new EditText(this);
                nm.setHint("Nama template (mis. Surat Tugas RT)");
                UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Simpan Sebagai Template").setView(nm).setNegativeButton("Batal", null)
                    .setPositiveButton("Simpan", (dd, ww) -> {
                        String tName = nm.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
                        if (tName.isEmpty()) return;
                        try {
                            String txt = wordEdit == null ? "" : wordEdit.getText().toString();
                            try (FileOutputStream out = new FileOutputStream(new File(dir, tName + ".txt"), false)) {
                                out.write(txt.getBytes(StandardCharsets.UTF_8));
                            }
                            Toast.makeText(this, "Template \"" + tName + "\" disimpan!", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) { showError("Gagal menyimpan template", e); }
                    }).show();
            } else {
                File chosen = list.get(which - 1);
                try {
                    String content = readAll(new java.io.FileInputStream(chosen));
                    setWordEditorContent(content);
                    currentName = chosen.getName().replaceAll("\\.txt$", "");
                    setStatus("Template kustom \"" + currentName + "\" dimuat.");
                } catch (Exception e) { showError("Gagal membuka template", e); }
            }
        }).show();
    }

    private void showBookPageManager() {
        if (pdfDocumentActive) {
            Toast.makeText(this, "Sedang membuka PDF baca. Manajemen halaman buku aktif pada dokumen Word.", Toast.LENGTH_SHORT).show();
            return;
        }
        ensureDocumentPagesInitialized();
        saveActiveDocumentPage();
        String[] items = {
            "Lompat ke halaman tertentu (" + (activeDocumentPage + 1) + "/" + documentPages.size() + ")",
            "+ Tambah halaman baru di akhir",
            "Duplikat halaman aktif ini",
            "Geser halaman ini ke Sebelumnya (Naik)",
            "Geser halaman ini ke Berikutnya (Turun)",
            "Hapus halaman aktif ini"
        };
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this)).setTitle("Kelola Halaman Buku").setItems(items, (d, which) -> {
            if (which == 0) showPageNavigation();
            else if (which == 1) addNewDocumentPage();
            else if (which == 2) {
                String copy = documentPages.get(activeDocumentPage);
                documentPages.add(activeDocumentPage + 1, copy == null ? "" : copy);
                activeDocumentPage++;
                loadDocumentPageIntoEditor(activeDocumentPage);
                setStatus("Halaman diduplikat menjadi halaman " + (activeDocumentPage + 1));
            } else if (which == 3) {
                if (activeDocumentPage <= 0) { Toast.makeText(this, "Sudah di halaman pertama.", Toast.LENGTH_SHORT).show(); return; }
                String cur = documentPages.remove(activeDocumentPage);
                activeDocumentPage--;
                documentPages.add(activeDocumentPage, cur);
                loadDocumentPageIntoEditor(activeDocumentPage);
                setStatus("Halaman dipindah ke nomor " + (activeDocumentPage + 1));
            } else if (which == 4) {
                if (activeDocumentPage >= documentPages.size() - 1) { Toast.makeText(this, "Sudah di halaman terakhir.", Toast.LENGTH_SHORT).show(); return; }
                String cur = documentPages.remove(activeDocumentPage);
                activeDocumentPage++;
                documentPages.add(activeDocumentPage, cur);
                loadDocumentPageIntoEditor(activeDocumentPage);
                setStatus("Halaman dipindah ke nomor " + (activeDocumentPage + 1));
            } else {
                if (documentPages.size() <= 1) { Toast.makeText(this, "Minimal harus tersisa 1 halaman.", Toast.LENGTH_SHORT).show(); return; }
                documentPages.remove(activeDocumentPage);
                activeDocumentPage = Math.max(0, Math.min(activeDocumentPage, documentPages.size() - 1));
                loadDocumentPageIntoEditor(activeDocumentPage);
                setStatus("Halaman dihapus. Kini di halaman " + (activeDocumentPage + 1) + " dari " + documentPages.size());
            }
        }).show();
    }

    // ==================== MENU OPSI LAINNYA (PANDUAN LENGKAP, DOWNLOAD BAHASA, LISENSI) ====================

    private void showMoreOptionsMenu() {
        String[] items = {
            "Panduan Lengkap (Pilih Mode Panduan + Daftar Bahasa OCR)",
            "Download Paket Bahasa OCR (GitHub & Muat Manual)",
            "Pilih Bahasa OCR Aktif Saat Ini (" + OcrEngineManager.getActiveLanguageCombo(this) + ")",
            "Lisensi & Hak Cipta (Anugerah Anas)"
        };
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this))
            .setTitle("Opsi Lainnya — Word & PDF Studio")
            .setItems(items, (d, which) -> {
                if (which == 0) showPanduanModePicker();
                else if (which == 1) OcrEngineManager.showLanguageDownloaderDialog(this, this::launchManualOcrLangPicker, null);
                else if (which == 2) OcrEngineManager.showActiveLanguagePickerDialog(this, null);
                else showLicenseAndCopyrightDialog();
            })
            .show();
    }

    private void showPanduanModePicker() {
        String[] modes = {
            "1. Panduan Microsoft Word & Dokumen Lengkap",
            "2. Panduan Pembaca PDF, Gestur 1 Jari & Edit Seluruh PDF",
            "3. Daftar 15 Bahasa OCR Tertanam + Cara Unduh / Muat Manual",
            "4. Panduan Editor Kode & Teks (Keyboard Editor Anas)",
            "5. Panduan Excel & PowerPoint Aksesibel"
        };
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this))
            .setTitle("Pilih Mode Panduan Lengkap")
            .setItems(modes, (d, which) -> {
                if (which == 0) {
                    showGuideReaderDialog("Panduan Word & Dokumen",
                        "MICROSOFT WORD PRO — KEYBOARD AI BY ANAS\n"
                        + "Dirancang untuk mahasiswa, pekerja kantoran, penulis, hingga pengguna tunanetra (TalkBack/Jieshuo).\n\n"
                        + "1. MENULIS PER HALAMAN (MODE BUKU)\n"
                        + "• Ketik langsung di kotak teks utama. Tiap halaman tersimpan otomatis ke Brankas Pekerjaan.\n"
                        + "• Gunakan tombol '◀ Hal.' / 'Hal. ▶' / '+ Halaman' untuk membuat naskah panjang atau buku berhalaman banyak.\n\n"
                        + "2. FORMAT & TEMPLATE CEPAT\n"
                        + "• Ketuk 'Format' atau 'B/I/U Format' untuk menebalkan, memiringkan, membuat daftar poin/nomor, atau meratakan paragraf.\n"
                        + "• Ketuk 'Template' untuk memilih 10 kategori dokumen siap pakai (Surat resmi, Makalah BAB I–V, Skripsi, CV, Invoice, Nota, Notulen, dll) lengkap dengan form terstruktur.\n"
                        + "• Bisa menyisipkan gambar asli dari galeri, tabel rapi, catatan kaki, header/footer, dan daftar isi otomatis.\n\n"
                        + "3. SIMPAN & BAGIKAN\n"
                        + "• Ketuk 'Simpan' untuk mengekspor dokumen ke DOCX (Word), PDF, HTML, ODT, RTF, Markdown, atau TXT.\n"
                        + "• Bisa langsung dibagikan ke WhatsApp atau Email dalam 1 ketukan.\n\n"
                        + "4. EKSTRAK TEKS DARI FOTO (OCR OFFLINE)\n"
                        + "• Ketuk tombol '📷 OCR Foto' untuk memotret atau memilih gambar berisi teks (catatan, buku, Arab, Inggris, dll) agar langsung diubah menjadi teks di kotak editor tanpa kuota internet.");
                } else if (which == 1) {
                    showGuideReaderDialog("Panduan Pembaca PDF & Kotak Edit Cerdas",
                        "PEMBACA PDF & OCR ALA ENVISION ('Keyboard PDF Anas')\n\n"
                        + "1. BUKA DARI FILE MANAGER ATAU WORD\n"
                        + "• Saat mengetuk file PDF di File Manager HP, pilih 'Keyboard PDF Anas' agar langsung terbuka di Mode Pembaca.\n"
                        + "• PDF ratusan hingga ribuan halaman terbuka seketika tanpa lag.\n"
                        + "• Jika halaman PDF berisi gambar/scan, aplikasi otomatis memindai teksnya memakai mesin OCR offline 15+ bahasa.\n\n"
                        + "2. NAVIGASI HALAMAN & GESTUR 1 JARI\n"
                        + "• Geser 1 Jari ke Kiri: Lompat ke Halaman Berikutnya.\n"
                        + "• Geser 1 Jari ke Kanan: Kembali ke Halaman Sebelumnya.\n"
                        + "• Ketuk 'Hal. X / Y' untuk langsung mengetik nomor halaman tujuan, atau ketuk '🔍 Cari' untuk mencari kata di dokumen.\n\n"
                        + "3. MODE BACA NOVEL TTS BERKELANJUTAN\n"
                        + "• Ketuk '▶ Baca Novel TTS' untuk mendengarkan seluruh buku. Begitu 1 halaman selesai dibaca, suara otomatis lanjut ke halaman berikutnya terus menerus tanpa terputus!\n"
                        + "• Angka Rupiah, persen, dan tanggal otomatis dibacakan secara wajar dalam bahasa manusia.\n\n"
                        + "4. EDIT PDF (PER HALAMAN VS SELURUH PDF)\n"
                        + "• Ketuk '✏️ Edit PDF' lalu pilih:\n"
                        + "  - 'Edit Halaman Ini': hanya mengubah halaman yang sedang dibaca, lalu tekan Simpan / OK.\n"
                        + "  - 'Edit Seluruh PDF (Kotak Edit Cerdas)': Halaman 1 langsung tampil di 1 kotak edit siap sunting. Geser 1 jari kiri/kanan (atau tekan tombol ◀ / ▶) untuk berpindah ke halaman 2, 3, dst. Semua perubahan otomatis tersimpan per halaman di disk sehingga aman walau ribuan halaman!");
                } else if (which == 2) {
                    showGuideReaderDialog("Daftar Bahasa OCR Offline",
                        OcrEngineManager.getBundledLanguagesGuideText()
                        + "\n\nCARANYA MENAMBAH BAHASA LAIN (100+ BAHASA):\n"
                        + "1. Buka Opsi Lainnya → Download Paket Bahasa OCR.\n"
                        + "2. Cari bahasa yang diinginkan (misal: Jawa, Sunda, Thailand, Hindi, Urdu, Persia, China Tradisional) lalu ketuk '⬇ Unduh' dari repositori resmi GitHub.\n"
                        + "3. Atau ketuk '📂 Muat .traineddata Manual' untuk memuat file bahasa .traineddata satu per satu langsung dari penyimpanan HP kamu.");
                } else if (which == 3) {
                    showGuideReaderDialog("Panduan Editor Kode & Teks",
                        "KEYBOARD EDITOR ANAS (.lua, .txt, .java, .json, .html, dll)\n\n"
                        + "1. BUKA LANGSUNG DARI FILE MANAGER\n"
                        + "• Saat memilih file .txt, .lua, .java, .json, .xml, .py, atau script lainnya dari File Manager, pilih 'Keyboard Editor Anas'.\n"
                        + "• File langsung terbuka instan di 1 kotak edit tunggal tanpa proses scan/OCR (ringan hingga 100.000+ karakter).\n\n"
                        + "2. TOMBOL SIMPAN OTOMATIS\n"
                        + "• Ketuk '💾 Simpan' di kanan atas untuk langsung menimpa dan menyimpan file ke tempat asalnya.\n"
                        + "• Tersedia juga menu 'Alat & Ekspor' untuk Simpan Sebagai (.lua/.txt/dll), atau ekspor jadi PDF & DOCX.\n\n"
                        + "3. FITUR CERDAS NGODING (RAMAH TUNANETRA)\n"
                        + "• '✓ Cek Kode': Memeriksa kurung kurawal { }, ( ), [ ], tanda kutip, serta blok Lua (function / if / for ... end) yang lupa ditutup beserta nomor barisnya.\n"
                        + "• '↷ Ke Baris': Lompat langsung ke nomor baris tertentu dan membacakan isinya.\n"
                        + "• Bilah Simbol Cepat & Auto-Indent: Sisipkan {, }, (, ), --, ==, ~=, Tab dalam 1 ketuk.\n"
                        + "• '🔊 Baris': Membacakan baris kode dengan penjelasan tanda baca (kurung kurawal buka, titik koma, sama dengan).");
                } else {
                    showGuideReaderDialog("Panduan Excel & PowerPoint",
                        "EXCEL & POWERPOINT PRO — RAMAH SEMUA KALANGAN\n\n"
                        + "1. MICROSOFT EXCEL PRO\n"
                        + "• Mendukung banyak sheet, format Rupiah/Persen/Desimal, serta 30+ rumus Excel (SUM, AVERAGE, IF, COUNTIF, SUMIF, dll).\n"
                        + "• Pembacaan Bahasa Manusia: Saat berpindah sel, aplikasi otomatis menyebutkan judul kolom & barisnya sekaligus nilai Rupiah secara alami.\n"
                        + "• Ketuk 'Jelaskan Rumus' di menu Rumus & Format untuk menerjemahkan rumus sel aktif ke kalimat bahasa Indonesia yang mudah dipahami.\n\n"
                        + "2. MICROSOFT POWERPOINT PRO\n"
                        + "• Buat presentasi multi-slide lengkap dengan Catatan Pembicara (Speaker Notes), ekspor ke PPTX, PDF Handout, atau HTML.\n"
                        + "• Di Mode Presentasi, gunakan tombol Volume Naik/Turun HP untuk berpindah slide tanpa perlu meraba layar!");
                }
            })
            .show();
    }

    private void showGuideReaderDialog(String title, final String bodyText) {
        ScrollView sc = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(bodyText);
        tv.setTextSize(14);
        tv.setLineSpacing(dp(3), 1.15f);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        tv.setTextIsSelectable(true);
        sc.addView(tv);
        UiKit.dialog(DocumentStudioActivity.this, OfficeUtils.mainPrefs(DocumentStudioActivity.this))
            .setTitle(title)
            .setView(sc)
            .setNeutralButton("Bacakan Panduan dengan Suara", (d, w) -> {
                String clean = HumanSpeechNormalizer.normalizeForDocumentTts(bodyText);
                final String speakStr = clean.length() > 3500 ? clean.substring(0, 3500) : clean;
                if (readingTts != null && readingTtsReady) {
                    readingTts.speak(speakStr, TextToSpeech.QUEUE_FLUSH, null, "guide_tts");
                } else {
                    readingTts = new TextToSpeech(this, st -> {
                        readingTtsReady = st == TextToSpeech.SUCCESS;
                        if (readingTtsReady && readingTts != null) {
                            try { readingTts.setLanguage(new Locale("id", "ID")); } catch (Throwable ignored) {}
                            readingTts.speak(speakStr, TextToSpeech.QUEUE_FLUSH, null, "guide_tts");
                        }
                    });
                }
            })
            .setPositiveButton("Mengerti", null)
            .show();
    }

    private void showLicenseAndCopyrightDialog() {
        String text = "LISENSI RESMI & HAK CIPTA\n"
                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n"
                + "Aplikasi: Keyboard AI by Anas (Suite Dokumen, Pembaca PDF OCR & Editor Kode)\n"
                + "Pengembang & Pemilik Hak Cipta:\n"
                + "• Nama: Anugerah Anas\n"
                + "• Asal: Aceh, Indonesia (SonarLab Indonesia)\n"
                + "• GitHub: https://github.com/anugrahai/Keyboard-AI-by-Anas\n\n"
                + "KETENTUAN HAK CIPTA:\n"
                + "1. Seluruh rancangan sistem, kode aplikasi, antarmuka aksesibilitas Braille/TTS, "
                + "alur Pembaca PDF Envision, dan Editor Kode dalam paket aplikasi ini merupakan karya cipta atas nama Anugerah Anas.\n"
                + "2. DILARANG KERAS mengklaim hak cipta, menghapus atau mengganti nama pembuat asli, "
                + "membajak, membongkar ulang untuk diakui sebagai karya pihak lain, maupun memperjualbelikan ulang tanpa izin tertulis dari Anugerah Anas.\n"
                + "3. Aplikasi ini bebas digunakan untuk belajar, bekerja, mahasiswa, perkantoran, dan komunitas tunanetra sesuai ketentuan resmi pengembang.\n\n"
                + "KOMPONEN & PUSTAKA TERBUKA (OPEN-SOURCE) YANG DIGUNAKAN:\n"
                + "• Tesseract OCR & Tesseract4Android (Lisensi Apache License 2.0)\n"
                + "• Data Bahasa OCR tessdata_fast (Lisensi Apache License 2.0)\n"
                + "• Apache PDFBox-Android (Lisensi Apache License 2.0)\n"
                + "• AndroidX CustomView (Lisensi Apache License 2.0)\n"
                + "• Font Liberation & Font DejaVu (SIL Open Font License / Free Font License)\n\n"
                + "Semua komponen pihak ketiga digunakan secara sah sesuai ketentuan lisensi masing-masing.";
        showGuideReaderDialog("Lisensi & Hak Cipta — Anugerah Anas", text);
    }

}
