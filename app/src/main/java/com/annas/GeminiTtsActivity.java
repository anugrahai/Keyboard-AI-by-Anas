package com.annas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Gemini Text-to-Speech Studio (Antarmuka 3 Tab Rapi tanpa Emoji).
 * - Khusus menggunakan API Key Gemini pribadi (`api_key_gemini`), tidak mengganggu Grok/OpenAI/DeepSeek.
 * - Mendukung preset kaya ekspresi (Novel Multi-Ekspresi, Bisnis, Marah-Marah, Multi-Ekspresi Adaptif)
 *   serta Gaya Bebas (+ Gabung beberapa ekspresi dan mengikuti ratusan nuansa ekspresi kalimat / tag).
 * - Memisahkan secara tegas antara:
 *   Mode 1: Suara Bawaan Skrip (30 Suara Default Gemini Native TTS)
 *   Mode 2: Suara Hasil Update Endpoint (difilter per bahasa sebelum update agar tidak double/tumpang tindih).
 */
public class GeminiTtsActivity extends Activity {

    private static final int REQ_IMPORT_TXT = 9501;
    private static final int REQ_SAVE_WAV = 9502;
    private static final int REQ_PICK_MUSIC = 9503;
    private static final int REQ_SAVE_VIDEO_DIR = 9504;
    private static final int REQ_SAVE_TXT = 9505;
    private static final int SAMPLE_RATE = 24000;
    private static final int VOICE_MODE_BUILTIN = 0;
    private static final int VOICE_MODE_UPDATED = 1;

    private static final String[] AUDIO_FORMAT_EXTS = {"m4a", "opus", "mp3", "wav"};
    private static final String[] AUDIO_FORMAT_LABELS = {
            "M4A / AAC Studio (64–96 kbps • < 1 MB/menit, Jernih Seperti Ori)",
            "OPUS Super Hemat (48–64 kbps • Ukuran Terkecil & Tajam)",
            "MP3 Universal (96–128 kbps • Kompatibel Semua HP/Player)",
            "WAV PCM Mentah (Tanpa Kompresi • ~3–10 MB/menit)"
    };

    private static final String[] DEFAULT_TTS_MODELS = {
            "gemini-3.8-flash-tts",
            "gemini-3.8-flash-lite-tts",
            "gemini-3.1-flash-tts-preview",
            "gemini-2.5-flash-preview-tts",
            "gemini-2.5-pro-preview-tts"
    };

    private static final String[] TTS_LANGUAGES = {
            "Bahasa Indonesia",
            "Otomatis (Sesuai Teks)",
            "Bahasa Inggris (US)",
            "Bahasa Inggris (UK)",
            "Bahasa Arab",
            "Bahasa Jepang",
            "Bahasa Korea",
            "Bahasa Mandarin",
            "Bahasa Jawa",
            "Bahasa Sunda",
            "Bahasa Jerman",
            "Bahasa Prancis",
            "Bahasa Spanyol"
    };

    // Bahasa untuk filter sebelum memanggil endpoint update suara (Indonesia default di index 0)
    private static final String[][] UPDATE_VOICE_LANG_CODES = {
            {"id-ID", "Bahasa Indonesia (Default)"},
            {"en-US", "Bahasa Inggris (US)"},
            {"en-GB", "Bahasa Inggris (UK)"},
            {"ar-XA", "Bahasa Arab"},
            {"ja-JP", "Bahasa Jepang"},
            {"ko-KR", "Bahasa Korea"},
            {"cmn-CN", "Bahasa Mandarin"},
            {"jv-ID", "Bahasa Jawa"},
            {"su-ID", "Bahasa Sunda"},
            {"de-DE", "Bahasa Jerman"},
            {"fr-FR", "Bahasa Prancis"},
            {"es-ES", "Bahasa Spanyol"}
    };

    // 30 Suara Bawaan Skrip (Gemini Live / Native TTS) — selalu terpisah dari suara hasil update
    private static final String[] BUILTIN_GEMINI_VOICES = {
            "Kore (Wanita - Tegas dan Jelas)",
            "Zephyr (Wanita - Cerah dan Jernih)",
            "Aoede (Wanita - Natural dan Santai)",
            "Puck (Pria - Semangat dan Ceria)",
            "Charon (Pria - Tenang dan Profesional)",
            "Fenrir (Pria - Hangat dan Bertenaga)",
            "Leda (Wanita - Muda dan Ringan)",
            "Orus (Pria - Tegas dan Stabil)",
            "Callirrhoe (Wanita - Lembut)",
            "Autonoe (Wanita - Cerah)",
            "Enceladus (Pria - Halus)",
            "Iapetus (Pria - Jernih)",
            "Umbriel (Pria - Santai)",
            "Algieba (Pria - Halus)",
            "Despina (Wanita - Halus)",
            "Erinome (Wanita - Jelas)",
            "Algenib (Pria - Berat)",
            "Rasalgethi (Pria - Informatif)",
            "Laomedeia (Wanita - Ceria)",
            "Achernar (Wanita - Lembut)",
            "Alnilam (Pria - Tegas)",
            "Schedar (Pria - Seimbang)",
            "Gacrux (Wanita - Dewasa)",
            "Pulcherrima (Wanita - Ekspresif)",
            "Achird (Pria - Ramah)",
            "Zubenelgenubi (Pria - Kasual)",
            "Vindemiatrix (Wanita - Halus)",
            "Sadachbia (Pria - Lincah)",
            "Sadaltager (Pria - Berwibawa)",
            "Sulafat (Wanita - Hangat)"
    };

    // Preset Instruksi Ringkas namun Lengkap Multi-Ekspresi
    private static final String[][] STYLE_PRESETS = {
            {
                    "Novel & Cerita (Semua Ekspresi Lengkap)",
                    "Bacakan naskah novel ini sebagai pengisi suara profesional dengan dinamika emosi penuh: ubah nada narasi dan dialog secara otomatis mengikuti isi tiap kalimat (sedih, haru, marah, takut, berbisik, terkejut, tertawa, tegang, romantis, atau tenang) sesuai tanda baca dan konteks cerita."
            },
            {
                    "Bisnis & Presentasi (Lengkap & Persuasif)",
                    "Bacakan untuk kebutuhan bisnis dan presentasi profesional dengan ekspresi lengkap: berwibawa saat membuka, persuasif dan antusias saat memaparkan inti penawaran, ramah menyapa pendengar, serta tegas meyakinkan di penutup."
            },
            {
                    "Marah & Emosional (Marah-Marah)",
                    "Bacakan dengan ekspresi marah-marah yang kuat, nada tinggi, geram, tegas, konfrontatif, dan penekanan emosional tajam pada setiap kalimat kemarahan."
            },
            {
                    "Multi-Ekspresi Adaptif (Ikuti Kalimat & Tag)",
                    "Ikuti secara otomatis puluhan hingga ratusan variasi ekspresi sesuai nuansa tiap kalimat maupun tag emosi di dalam teks (seperti [marah], [sedih], [tertawa], [bisik], [terkejut], [takut], [semangat], [sinis], [menangis], [haru], [tegas]). Ubah suara sesuai ekspresi tersebut dan JANGAN membacakan tulisan tag di dalam kurung siku."
            },
            {
                    "Gaya Bebas (Tulis & Gabung Sendiri)",
                    ""
            }
    };

    // Daftar Tambahan Ekspresi (+) berbasis kotak centang (bisa dicentang banyak sekaligus)
    private static final String[][] EXPRESSION_MIXINS = {
            {"Novel", "Bergaya penceritaan novel penuh penghayatan dengan pergantian emosi narator dan dialog."},
            {"Bisnis", "Bergaya bisnis profesional yang berwibawa, persuasif, dan berartikulasi rapi."},
            {"Marah-Marah", "Dengan ekspresi marah-marah yang kuat, geram, nada naik, dan penekanan tegas."},
            {"Sedih / Haru", "Bernuansa sedih, lirih, sedikit bergetar, dan penuh keharuan."},
            {"Berbisik", "Dengan suara berbisik pelan, intim, dan rahasia pada bagian menegangkan."},
            {"Semangat", "Penuh semangat membara, antusias tinggi, dan energik."},
            {"Ceria / Tertawa", "Ekspresi ceria, ramah tersenyum, dan tawa ringan natural."},
            {"Tegang / Horor", "Suasana mencekam, jeda dramatis, dan penuh ketegangan."},
            {"Sinis / Sarkas", "Nada menyindir tajam, datar, dan sarkastis."},
            {"Ratusan Ekspresi Teks", "Otomatis ikuti seluruh ekspresi yang tertulis di kalimat maupun tag [ekspresi] tanpa melafalkan tanda kurungnya."}
    };

    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean cancelRequested = false;
    private volatile boolean generating = false;

    private String selectedModel = "gemini-3.8-flash-tts";
    private String selectedLanguage = TTS_LANGUAGES[0];
    private int voiceSourceMode = VOICE_MODE_BUILTIN;
    private String voice1Builtin = "Kore";
    private String voice2Builtin = "Puck";
    private String voice1Updated = "";
    private String voice2Updated = "";
    private String updatedVoicesLang = "id-ID";
    private String speaker1Name = "Pembicara1";
    private String speaker2Name = "Pembicara2";
    private boolean multiSpeakerMode = false;
    private int chunkCharLimit = 1500;
    private int maxChunksGuard = 8;
    private int chunkDelayMs = 1500;
    private int activeTab = 0;

    private int selectedPresetIdx = 0;
    private final boolean[] checkedMixins = new boolean[EXPRESSION_MIXINS.length];
    private final CheckBox[] mixinCheckBoxes = new CheckBox[EXPRESSION_MIXINS.length];
    private boolean updatingStyleUi = false;

    private LinearLayout tabPanel0, tabPanel1, tabPanel2;
    private Button tabBtn0, tabBtn1, tabBtn2;
    private TextView metaLabel, statusLabel, keyBadge, modelInfoLabel, voiceInfoLabel, activeStyleBadge;
    private android.widget.ProgressBar topProgressBar;
    private Button btnModel, btnLanguage, btnVoiceSourceMode, btnVoice1, btnVoice2, btnOutputFormat;
    private Button btnGenerate, btnPlay, btnSave, btnShare, btnMixBacksoundWav, btnExportFullVideo, btnWaStatusVideo, btnUpdateModels, btnUpdateVoices;
    private String selectedAudioFmt = "m4a";
    private boolean pickMusicForWavMix = false;
    private EditText etStylePrompt, etInputText;
    private CheckBox cbMultiSpeaker, cbUseAppLogic, cbDynamicExpr;

    private File generatedWavFile;
    private MediaPlayer mediaPlayer;
    private float swipeStartX = 0f, swipeStartY = 0f;
    private boolean swipeTracking = false, swipeTriggered = false;

    // Status WA Video + Musik Latar + Antrean Upload Berurutan
    private Uri bgMusicUri;
    private String bgMusicName = "";
    private double bgMusicDurationSec = 0;
    private int bgMusicStartSec = 0;
    private int bgMusicVolumePct = 18;
    private TextView activeWaMusicInfoLabel;
    private final List<File> pendingWaVideos = new ArrayList<>();
    private int pendingWaIndex = 0;
    private boolean waSendInFlight = false;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (mediaPlayer != null && activeTab == 0 && ev.getPointerCount() == 1) {
            int action = ev.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                swipeStartX = ev.getX();
                swipeStartY = ev.getY();
                swipeTracking = true;
                swipeTriggered = false;
            } else if (action == android.view.MotionEvent.ACTION_MOVE && swipeTracking && !swipeTriggered) {
                float dx = ev.getX() - swipeStartX;
                float dy = ev.getY() - swipeStartY;
                float thresh = UiKit.dp(this, 75);
                if (Math.abs(dx) > thresh && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                    swipeTriggered = true;
                    android.view.MotionEvent cancelEv = android.view.MotionEvent.obtain(ev);
                    cancelEv.setAction(android.view.MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancelEv);
                    cancelEv.recycle();
                    try {
                        int dur = Math.max(0, mediaPlayer.getDuration());
                        int cur = Math.max(0, mediaPlayer.getCurrentPosition());
                        int next = Math.max(0, Math.min(dur, cur + (dx < 0 ? -5000 : 5000)));
                        mediaPlayer.seekTo(next);
                        OfficeUtils.announce(statusLabel, (dx < 0 ? "Mundur ke " : "Maju ke ") + (next / 1000) + " detik");
                    } catch (Throwable ignored) {}
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
        if (activeTab != 0) {
            switchTab(0);
            return;
        }
        releaseMediaPlayer();
        super.onBackPressed();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = OfficeUtils.mainPrefs(this);
        UiKit.applySystemBars(this, prefs);
        if (!AuthManager.isSignedIn()) {
            AuthManager.openLoginScreen(this);
            finish();
            return;
        }
        if (!AuthManager.isPremiumActive(this)) {
            AuthManager.showPremiumRequiredDialog(this, "Gemini Text-to-Speech Studio", this::buildUi);
        }
        loadSettings();
        buildUi();
    }

    private void loadSettings() {
        selectedModel = prefs.getString("gtts_model", "gemini-3.8-flash-tts");
        selectedLanguage = prefs.getString("gtts_lang", TTS_LANGUAGES[0]);
        voiceSourceMode = prefs.getInt("gtts_voice_mode", VOICE_MODE_BUILTIN);
        voice1Builtin = prefs.getString("gtts_voice1", "Kore");
        voice2Builtin = prefs.getString("gtts_voice2", "Puck");
        voice1Updated = prefs.getString("gtts_voice1_upd", "");
        voice2Updated = prefs.getString("gtts_voice2_upd", "");
        updatedVoicesLang = prefs.getString("gtts_updated_voices_lang", "id-ID");
        speaker1Name = prefs.getString("gtts_sp1", "Pembicara1");
        speaker2Name = prefs.getString("gtts_sp2", "Pembicara2");
        multiSpeakerMode = prefs.getBoolean("gtts_multi", false);
        chunkCharLimit = prefs.getInt("gtts_chunk_chars", 1500);
        maxChunksGuard = prefs.getInt("gtts_max_chunks", 8);
        chunkDelayMs = prefs.getInt("gtts_delay_ms", 1500);
        selectedAudioFmt = normalizeAudioFmt(prefs.getString("gtts_audio_fmt", "m4a"));
        selectedPresetIdx = Math.max(0, Math.min(STYLE_PRESETS.length - 1, prefs.getInt("gtts_preset_idx", 0)));
        try {
            JSONArray mArr = new JSONArray(prefs.getString("gtts_mixins_json", "[]"));
            for (int i = 0; i < EXPRESSION_MIXINS.length; i++) checkedMixins[i] = false;
            for (int i = 0; i < mArr.length(); i++) {
                String k = mArr.optString(i, "");
                for (int j = 0; j < EXPRESSION_MIXINS.length; j++) {
                    if (EXPRESSION_MIXINS[j][0].equals(k)) checkedMixins[j] = true;
                }
            }
        } catch (Exception ignored) {}

        List<String> updated = loadUpdatedVoices();
        if (voiceSourceMode == VOICE_MODE_UPDATED && updated.isEmpty()) {
            voiceSourceMode = VOICE_MODE_BUILTIN;
        }
        if (!updated.isEmpty()) {
            if (voice1Updated.isEmpty()) voice1Updated = extractVoiceId(updated.get(0));
            if (voice2Updated.isEmpty()) voice2Updated = extractVoiceId(updated.get(updated.size() > 1 ? 1 : 0));
        }
    }

    private void saveSettings() {
        prefs.edit()
                .putString("gtts_model", selectedModel)
                .putString("gtts_lang", selectedLanguage)
                .putInt("gtts_voice_mode", voiceSourceMode)
                .putString("gtts_voice1", voice1Builtin)
                .putString("gtts_voice2", voice2Builtin)
                .putString("gtts_voice1_upd", voice1Updated)
                .putString("gtts_voice2_upd", voice2Updated)
                .putString("gtts_updated_voices_lang", updatedVoicesLang)
                .putString("gtts_sp1", speaker1Name)
                .putString("gtts_sp2", speaker2Name)
                .putBoolean("gtts_multi", multiSpeakerMode)
                .putInt("gtts_chunk_chars", chunkCharLimit)
                .putInt("gtts_max_chunks", maxChunksGuard)
                .putInt("gtts_delay_ms", chunkDelayMs)
                .putString("gtts_audio_fmt", selectedAudioFmt)
                .putInt("gtts_preset_idx", selectedPresetIdx)
                .putString("gtts_mixins_json", buildCheckedMixinsJson())
                .apply();
    }

    private String buildCheckedMixinsJson() {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < EXPRESSION_MIXINS.length; i++) {
            if (checkedMixins[i]) arr.put(EXPRESSION_MIXINS[i][0]);
        }
        return arr.toString();
    }

    private String getActiveVoice(int slot) {
        if (voiceSourceMode == VOICE_MODE_UPDATED) {
            String v = (slot == 1) ? voice1Updated : voice2Updated;
            if (!v.isEmpty()) return v;
        }
        return (slot == 1) ? voice1Builtin : voice2Builtin;
    }

    private void buildUi() {
        int bg = UiKit.bg(prefs);
        int surface = UiKit.surface(prefs);
        int surfaceAlt = UiKit.surfaceAlt(prefs);
        int txt = UiKit.textPrimary(prefs);
        int muted = UiKit.textMuted(prefs);
        int border = UiKit.border(prefs);
        int p = UiKit.dp(this, 12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("Gemini Text-to-Speech Studio");
        title.setTextColor(txt);
        title.setTextSize(19);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        keyBadge = new TextView(this);
        keyBadge.setTextSize(12);
        keyBadge.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 6));
        updateKeyBadge();
        root.addView(keyBadge);

        // Kartu Ringkasan Gaya Aktif & Progres Berjalan selalu tampil di bagian paling atas
        LinearLayout topSummaryCard = UiKit.makeCard(this, prefs);
        topSummaryCard.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10), UiKit.dp(this, 12), UiKit.dp(this, 10));

        activeStyleBadge = new TextView(this);
        activeStyleBadge.setTextColor(txt);
        activeStyleBadge.setTextSize(13);
        activeStyleBadge.setLineSpacing(UiKit.dp(this, 2), 1.08f);
        activeStyleBadge.setPadding(0, 0, 0, UiKit.dp(this, 4));
        topSummaryCard.addView(activeStyleBadge);

        statusLabel = new TextView(this);
        statusLabel.setText("Status: Siap (0%) • Menggunakan API Key Gemini khusus TTS.");
        statusLabel.setTextColor(UiKit.ACCENT_KB_ALT);
        statusLabel.setTextSize(12);
        statusLabel.setTypeface(null, Typeface.BOLD);
        statusLabel.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 4));
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            statusLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        }
        topSummaryCard.addView(statusLabel);

        topProgressBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        topProgressBar.setMax(100);
        topProgressBar.setProgress(0);
        topProgressBar.setVisibility(View.GONE);
        topSummaryCard.addView(topProgressBar, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 6)));

        root.addView(topSummaryCard);

        // Baris 3 Tab Rapi
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(0, 0, 0, UiKit.dp(this, 8));
        tabBtn0 = OfficeUtils.makeRowButton(this, "1. Teks & Audio", "Tab input teks dan pemutar audio", UiKit.ACCENT_AI, Color.WHITE, v -> switchTab(0));
        tabBtn1 = OfficeUtils.makeRowButton(this, "2. Suara & Model", "Tab model API, bahasa, dan sumber suara", surfaceAlt, txt, v -> switchTab(1));
        tabBtn2 = OfficeUtils.makeRowButton(this, "3. Ekspresi & Gaya", "Tab pilih gaya utama, centang ekspresi tambahan, kunci Gemini, dan limit", surfaceAlt, txt, v -> switchTab(2));
        tabBar.addView(tabBtn0);
        tabBar.addView(tabBtn1);
        tabBar.addView(tabBtn2);
        root.addView(tabBar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout bodyHost = new LinearLayout(this);
        bodyHost.setOrientation(LinearLayout.VERTICAL);

        // ================= TAB 0: TEKS & AUDIO =================
        tabPanel0 = new LinearLayout(this);
        tabPanel0.setOrientation(LinearLayout.VERTICAL);

        LinearLayout inputCard = UiKit.makeCard(this, prefs);

        LinearLayout topQuickRow = new LinearLayout(this);
        topQuickRow.setOrientation(LinearLayout.HORIZONTAL);
        topQuickRow.setPadding(0, 0, 0, UiKit.dp(this, 8));
        Button btnImport = OfficeUtils.makeRowButton(this, "Impor (.txt)", "Impor teks dari folder Gemini Teks",
                UiKit.ACCENT_SYSTEM, Color.WHITE, v ->
                        OfficeUtils.launchFilePicker(this, REQ_IMPORT_TXT, "text", "Pilih Naskah (.txt)", "Gemini TTS/Gemini Teks"));
        Button btnSaveTxt = OfficeUtils.makeRowButton(this, "Simpan (.txt)", "Simpan naskah ke folder Gemini Teks",
                UiKit.ACCENT_KB_ALT, Color.WHITE, v -> saveInputTextAs());
        Button btnQuickStyle = OfficeUtils.makeRowButton(this, "Ekspresi & Gaya", "Pilih gaya utama lalu centang tambahan ekspresi (+)",
                UiKit.ACCENT_KB, Color.WHITE, v -> pickStylePreset());
        topQuickRow.addView(btnImport);
        topQuickRow.addView(btnSaveTxt);
        topQuickRow.addView(btnQuickStyle);
        inputCard.addView(topQuickRow);

        etInputText = new EditText(this);
        etInputText.setHint("Tulis atau tempel naskah di sini...\nBisa sisipkan tag ekspresi seperti [marah], [sedih], [bisik], [tertawa], [terkejut] atau cukup tulis kalimat ekspresif.\n\nMode Multi-Speaker:\n"
                + speaker1Name + ": Halo apa kabar?\n" + speaker2Name + ": Baik sekali.");
        etInputText.setTextColor(txt);
        etInputText.setHintTextColor(muted);
        etInputText.setBackground(UiKit.roundedBox(bg, border, UiKit.dp(this, 10), UiKit.dp(this, 1)));
        etInputText.setPadding(p, p, p, p);
        etInputText.setMinLines(7);
        etInputText.setGravity(Gravity.TOP | Gravity.START);
        inputCard.addView(etInputText);

        metaLabel = new TextView(this);
        metaLabel.setTextColor(muted);
        metaLabel.setTextSize(12);
        metaLabel.setPadding(0, UiKit.dp(this, 6), 0, 0);
        inputCard.addView(metaLabel);
        etInputText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateMeta(); }
        });
        tabPanel0.addView(inputCard);

        LinearLayout actionCard = UiKit.makeCard(this, prefs);
        btnOutputFormat = new Button(this);
        btnOutputFormat.setText("Format Output Audio: " + selectedAudioFmt.toUpperCase(Locale.ROOT) + " (Hemat & Jernih)");
        btnOutputFormat.setContentDescription("Pilih format output audio Gemini TTS: M4A AAC hemat jernih, OPUS super ringan, MP3 universal, atau WAV mentah");
        UiKit.styleButton(this, btnOutputFormat, UiKit.ACCENT_SYSTEM);
        btnOutputFormat.setOnClickListener(v -> showOutputFormatDialog());
        actionCard.addView(btnOutputFormat);

        btnGenerate = new Button(this);
        btnGenerate.setText("Konversi ke Audio (" + selectedAudioFmt.toUpperCase(Locale.ROOT) + ")");
        UiKit.styleButton(this, btnGenerate, UiKit.ACCENT_AI);
        btnGenerate.setOnClickListener(v -> {
            if (generating) {
                cancelRequested = true;
                updateProgressStatus(0, "Membatalkan proses Gemini TTS...", false);
            } else {
                startGenerateAudio();
            }
        });
        actionCard.addView(btnGenerate);

        LinearLayout audioRow = new LinearLayout(this);
        audioRow.setOrientation(LinearLayout.HORIZONTAL);
        btnPlay = OfficeUtils.makeRowButton(this, "Putar", "Putar atau jeda audio hasil TTS", UiKit.ACCENT_OFFICE, Color.WHITE, v -> togglePlay());
        btnSave = OfficeUtils.makeRowButton(this, "Simpan", "Simpan berkas audio ke penyimpanan", UiKit.ACCENT_KB, Color.WHITE, v -> saveWavAs());
        btnShare = OfficeUtils.makeRowButton(this, "Bagikan", "Bagikan berkas audio ke aplikasi lain", UiKit.ACCENT_WARM, Color.WHITE, v -> shareWav());
        btnPlay.setEnabled(false);
        btnSave.setEnabled(false);
        btnShare.setEnabled(false);
        audioRow.addView(btnPlay);
        audioRow.addView(btnSave);
        audioRow.addView(btnShare);
        actionCard.addView(audioRow);

        btnMixBacksoundWav = new Button(this);
        btnMixBacksoundWav.setText("Tambah Backsound Musik ke Audio (Aplikasi / HP)");
        btnMixBacksoundWav.setContentDescription("Campur backsound musik dari kategori aplikasi atau memori HP ke audio suara Gemini TTS");
        UiKit.styleButton(this, btnMixBacksoundWav, UiKit.ACCENT_KB_ALT);
        btnMixBacksoundWav.setEnabled(false);
        btnMixBacksoundWav.setOnClickListener(v -> openMixBacksoundToWavDialog());
        actionCard.addView(btnMixBacksoundWav);

        btnExportFullVideo = new Button(this);
        btnExportFullVideo.setText("Ekspor ke Video MP4 Utuh (+ Backsound Aplikasi / HP)");
        btnExportFullVideo.setContentDescription("Ekspor seluruh audio menjadi 1 video MP4 utuh di folder Gemini Video");
        UiKit.styleButton(this, btnExportFullVideo, UiKit.ACCENT_AI_ALT);
        btnExportFullVideo.setEnabled(false);
        btnExportFullVideo.setOnClickListener(v -> openExportVideoDialog(false));
        actionCard.addView(btnExportFullVideo);

        btnWaStatusVideo = new Button(this);
        btnWaStatusVideo.setText("Bagikan ke Status WhatsApp (Potong 30dtk + Backsound)");
        btnWaStatusVideo.setContentDescription("Konversi audio WAV menjadi video MP4 untuk Status WhatsApp, bisa tambah musik dan potong otomatis per 30 detik berurutan");
        UiKit.styleButton(this, btnWaStatusVideo, UiKit.ACCENT_OFFICE);
        btnWaStatusVideo.setEnabled(false);
        btnWaStatusVideo.setOnClickListener(v -> openExportVideoDialog(true));
        actionCard.addView(btnWaStatusVideo);

        Button btnOpenGudang = new Button(this);
        btnOpenGudang.setText("Studio Gudang & Laboratorium Backsound / Efek Film");
        btnOpenGudang.setContentDescription("Buka Studio Gudang untuk memilih, menguji di laboratorium, atau menghapus audio backsound dan efek film yang tidak diperlukan");
        UiKit.styleButton(this, btnOpenGudang, UiKit.ACCENT_SYSTEM);
        btnOpenGudang.setOnClickListener(v -> StudioGudangAudioManager.showStudioGudangMainDialog(this, prefs, null));
        actionCard.addView(btnOpenGudang);
        tabPanel0.addView(actionCard);

        // ================= TAB 1: SUARA, BAHASA & MODEL =================
        tabPanel1 = new LinearLayout(this);
        tabPanel1.setOrientation(LinearLayout.VERTICAL);

        LinearLayout modelCard = UiKit.makeCard(this, prefs);
        modelCard.addView(UiKit.makeSectionLabel(this, "MODEL & BAHASA", UiKit.ACCENT_AI));

        btnUpdateModels = new Button(this);
        btnUpdateModels.setText("Perbarui Daftar Model dari API Gemini");
        UiKit.styleButton(this, btnUpdateModels, UiKit.ACCENT_KB);
        btnUpdateModels.setOnClickListener(v -> fetchGeminiModelsFromEndpoint());
        modelCard.addView(btnUpdateModels);

        modelInfoLabel = new TextView(this);
        modelInfoLabel.setTextColor(muted);
        modelInfoLabel.setTextSize(12);
        modelInfoLabel.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 8));
        updateModelCountInfo();
        modelCard.addView(modelInfoLabel);

        btnModel = new Button(this);
        btnModel.setText("Model Aktif: " + selectedModel);
        UiKit.styleButton(this, btnModel, UiKit.ACCENT_AI);
        btnModel.setOnClickListener(v -> pickModel());
        modelCard.addView(btnModel);

        btnLanguage = new Button(this);
        btnLanguage.setText("Bahasa Pengucapan: " + selectedLanguage);
        UiKit.styleButton(this, btnLanguage, UiKit.ACCENT_AI_ALT);
        btnLanguage.setOnClickListener(v -> pickLanguage());
        modelCard.addView(btnLanguage);
        tabPanel1.addView(modelCard);

        LinearLayout voiceCard = UiKit.makeCard(this, prefs);
        voiceCard.addView(UiKit.makeSectionLabel(this, "SUMBER SUARA & PEMBICARA", UiKit.ACCENT_OFFICE));

        // Pemisahan tegas Mode 1 (Bawaan 30 Suara) vs Mode 2 (Update Endpoint per Bahasa)
        btnVoiceSourceMode = new Button(this);
        UiKit.styleButton(this, btnVoiceSourceMode, UiKit.ACCENT_SYSTEM);
        btnVoiceSourceMode.setOnClickListener(v -> pickVoiceSourceMode());
        voiceCard.addView(btnVoiceSourceMode);

        btnUpdateVoices = new Button(this);
        btnUpdateVoices.setText("Perbarui Data Suara dari Endpoint (Pilih Bahasa)");
        UiKit.styleButton(this, btnUpdateVoices, UiKit.ACCENT_WARM);
        btnUpdateVoices.setOnClickListener(v -> promptLanguageAndFetchVoices());
        voiceCard.addView(btnUpdateVoices);

        voiceInfoLabel = new TextView(this);
        voiceInfoLabel.setTextColor(muted);
        voiceInfoLabel.setTextSize(12);
        voiceInfoLabel.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 8));
        voiceCard.addView(voiceInfoLabel);

        cbMultiSpeaker = new CheckBox(this);
        cbMultiSpeaker.setText("Mode Multi-Speaker (2 Pembicara: " + speaker1Name + " dan " + speaker2Name + ")");
        cbMultiSpeaker.setTextColor(txt);
        cbMultiSpeaker.setChecked(multiSpeakerMode);
        cbMultiSpeaker.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6));
        cbMultiSpeaker.setOnCheckedChangeListener((b, isChecked) -> {
            multiSpeakerMode = isChecked;
            btnVoice2.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            saveSettings();
            updateActiveStyleBadge();
        });
        voiceCard.addView(cbMultiSpeaker);

        btnVoice1 = new Button(this);
        UiKit.styleButton(this, btnVoice1, UiKit.ACCENT_OFFICE);
        btnVoice1.setOnClickListener(v -> pickVoice(1));
        voiceCard.addView(btnVoice1);

        btnVoice2 = new Button(this);
        UiKit.styleButton(this, btnVoice2, UiKit.ACCENT_KB_ALT);
        btnVoice2.setOnClickListener(v -> pickVoice(2));
        btnVoice2.setVisibility(multiSpeakerMode ? View.VISIBLE : View.GONE);
        voiceCard.addView(btnVoice2);

        Button btnSpeakerNames = new Button(this);
        btnSpeakerNames.setText("Ubah Nama Pembicara Dialog");
        UiKit.styleButton(this, btnSpeakerNames, UiKit.ACCENT_SYSTEM);
        btnSpeakerNames.setOnClickListener(v -> editSpeakerNames());
        voiceCard.addView(btnSpeakerNames);
        tabPanel1.addView(voiceCard);

        // ================= TAB 2: GAYA, EKSPRESI CENTANG (+) & LIMIT =================
        tabPanel2 = new LinearLayout(this);
        tabPanel2.setOrientation(LinearLayout.VERTICAL);

        LinearLayout styleHeadCard = UiKit.makeCard(this, prefs);
        Button btnSetGeminiKey = new Button(this);
        btnSetGeminiKey.setText("Atur Kunci API Gemini (Khusus TTS)");
        UiKit.styleButton(this, btnSetGeminiKey, UiKit.ACCENT_AI);
        btnSetGeminiKey.setOnClickListener(v -> showGeminiKeyDialog());
        styleHeadCard.addView(btnSetGeminiKey);

        Button btnPresetStyle = new Button(this);
        btnPresetStyle.setText("1. Pilih Gaya Utama (Novel, Bisnis, Marah, Adaptif, Bebas)");
        UiKit.styleButton(this, btnPresetStyle, UiKit.ACCENT_KB);
        btnPresetStyle.setOnClickListener(v -> pickStylePreset());
        styleHeadCard.addView(btnPresetStyle);

        Button btnChecklistPopup = new Button(this);
        btnChecklistPopup.setText("2. Pilih & Centang Ekspresi Tambahan (+)");
        UiKit.styleButton(this, btnChecklistPopup, UiKit.ACCENT_AI_ALT);
        btnChecklistPopup.setOnClickListener(v -> openMixinChecklistDialog(selectedPresetIdx == STYLE_PRESETS.length - 1));
        styleHeadCard.addView(btnChecklistPopup);
        tabPanel2.addView(styleHeadCard);

        LinearLayout mixinCard = UiKit.makeCard(this, prefs);
        mixinCard.addView(UiKit.makeSectionLabel(this, "KOTAK CENTANG EKPRESI TAMBAHAN (+)", UiKit.ACCENT_KB_ALT));
        TextView mixinHint = new TextView(this);
        mixinHint.setText("Centang satu atau beberapa ekspresi di bawah untuk digabungkan ke gaya yang dipilih (khususnya saat memakai Gaya Bebas):");
        mixinHint.setTextColor(muted);
        mixinHint.setTextSize(12);
        mixinHint.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 8));
        mixinCard.addView(mixinHint);

        for (int i = 0; i < EXPRESSION_MIXINS.length; i++) {
            final int idx = i;
            CheckBox cb = new CheckBox(this);
            cb.setText("+ " + EXPRESSION_MIXINS[i][0]);
            cb.setTextColor(txt);
            cb.setChecked(checkedMixins[i]);
            cb.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 6), UiKit.dp(this, 4), UiKit.dp(this, 6));
            cb.setOnCheckedChangeListener((btn, isChecked) -> {
                if (updatingStyleUi) return;
                onMixinToggled(idx, isChecked);
            });
            mixinCheckBoxes[i] = cb;
            mixinCard.addView(cb);
        }

        Button btnClearMixins = new Button(this);
        btnClearMixins.setText("Hapus Semua Centang & Kosongkan Gaya");
        UiKit.styleButton(this, btnClearMixins, UiKit.ACCENT_DANGER);
        btnClearMixins.setOnClickListener(v -> {
            updatingStyleUi = true;
            for (int i = 0; i < checkedMixins.length; i++) {
                checkedMixins[i] = false;
                if (mixinCheckBoxes[i] != null) mixinCheckBoxes[i].setChecked(false);
            }
            if (etStylePrompt != null) etStylePrompt.setText("");
            updatingStyleUi = false;
            prefs.edit().putString("gtts_style_prompt", "").apply();
            saveSettings();
            updateActiveStyleBadge();
        });
        mixinCard.addView(btnClearMixins);
        tabPanel2.addView(mixinCard);

        LinearLayout freeStyleCard = UiKit.makeCard(this, prefs);
        freeStyleCard.addView(UiKit.makeSectionLabel(this, "INSTRUKSI GAYA BEBAS & PENGATURAN", UiKit.ACCENT_SYSTEM));

        etStylePrompt = new EditText(this);
        etStylePrompt.setHint("Tulis instruksi Gaya Bebas tambahan di sini (otomatis tergabung dengan kotak centang di atas)...");
        etStylePrompt.setText(prefs.getString("gtts_style_prompt", STYLE_PRESETS[0][1]));
        etStylePrompt.setTextColor(txt);
        etStylePrompt.setHintTextColor(muted);
        etStylePrompt.setBackground(UiKit.roundedBox(bg, border, UiKit.dp(this, 10), UiKit.dp(this, 1)));
        etStylePrompt.setPadding(p, p, p, p);
        etStylePrompt.setMinLines(4);
        etStylePrompt.setGravity(Gravity.TOP | Gravity.START);
        etStylePrompt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!updatingStyleUi) updateActiveStyleBadge();
            }
        });
        freeStyleCard.addView(etStylePrompt);

        cbDynamicExpr = new CheckBox(this);
        cbDynamicExpr.setText("Otomatis ikuti ratusan ekspresi kalimat & tag [ekspresi] tanpa membaca tanda kurung");
        cbDynamicExpr.setTextColor(txt);
        cbDynamicExpr.setChecked(prefs.getBoolean("gtts_dynamic_expr", true));
        cbDynamicExpr.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 4));
        cbDynamicExpr.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("gtts_dynamic_expr", c).apply());
        freeStyleCard.addView(cbDynamicExpr);

        cbUseAppLogic = new CheckBox(this);
        cbUseAppLogic.setText("Aktifkan Logika Normalisasi Pengucapan (angka, singkatan, simbol)");
        cbUseAppLogic.setTextColor(txt);
        cbUseAppLogic.setChecked(prefs.getBoolean("gtts_use_logic", true));
        cbUseAppLogic.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 8));
        cbUseAppLogic.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("gtts_use_logic", c).apply());
        freeStyleCard.addView(cbUseAppLogic);

        Button btnLimits = new Button(this);
        btnLimits.setText("Atur Batas Limit (Anti Kebobolan Kuota)");
        UiKit.styleButton(this, btnLimits, UiKit.ACCENT_WARM);
        btnLimits.setOnClickListener(v -> showLimitGuardDialog());
        freeStyleCard.addView(btnLimits);
        tabPanel2.addView(freeStyleCard);

        bodyHost.addView(tabPanel0);
        bodyHost.addView(tabPanel1);
        bodyHost.addView(tabPanel2);
        scroll.addView(bodyHost);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        syncCheckboxesFromPrompt();
        refreshVoiceModeUi();
        updateActiveStyleBadge();
        updateMeta();
        switchTab(activeTab);
    }

    private void syncCheckboxesFromPrompt() {
        String cur = etStylePrompt != null && etStylePrompt.getText() != null ? etStylePrompt.getText().toString() : "";
        updatingStyleUi = true;
        for (int i = 0; i < EXPRESSION_MIXINS.length; i++) {
            if (!checkedMixins[i] && cur.contains(EXPRESSION_MIXINS[i][1])) {
                checkedMixins[i] = true;
            }
            if (mixinCheckBoxes[i] != null) mixinCheckBoxes[i].setChecked(checkedMixins[i]);
        }
        updatingStyleUi = false;
    }

    private void onMixinToggled(int idx, boolean isChecked) {
        if (idx < 0 || idx >= EXPRESSION_MIXINS.length) return;
        checkedMixins[idx] = isChecked;
        updatingStyleUi = true;
        if (mixinCheckBoxes[idx] != null) mixinCheckBoxes[idx].setChecked(isChecked);
        if (etStylePrompt != null) {
            String line = EXPRESSION_MIXINS[idx][1];
            String cur = etStylePrompt.getText() == null ? "" : etStylePrompt.getText().toString();
            if (isChecked) {
                if (!cur.contains(line)) {
                    String next = cur.trim().isEmpty() ? line : (cur.trim() + "\n" + line);
                    etStylePrompt.setText(next);
                    etStylePrompt.setSelection(next.length());
                }
            } else {
                String next = cur.replace("\n" + line, "").replace(line + "\n", "").replace(line, "").trim();
                etStylePrompt.setText(next);
            }
            prefs.edit().putString("gtts_style_prompt", etStylePrompt.getText().toString()).apply();
        }
        updatingStyleUi = false;
        saveSettings();
        updateActiveStyleBadge();
        OfficeUtils.announce(activeStyleBadge, EXPRESSION_MIXINS[idx][0] + (isChecked ? " dicentang" : " dibatalkan"));
    }

    private String getCheckedMixinsSummary() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < EXPRESSION_MIXINS.length; i++) {
            if (checkedMixins[i]) names.add(EXPRESSION_MIXINS[i][0]);
        }
        return names.isEmpty() ? "Tidak ada" : android.text.TextUtils.join(", ", names);
    }

    private void updateProgressStatus(int percent, String text, boolean showBar) {
        if (statusLabel != null) {
            statusLabel.setText(text);
            statusLabel.setContentDescription(text);
        }
        if (topProgressBar != null) {
            topProgressBar.setVisibility(showBar ? View.VISIBLE : View.GONE);
            topProgressBar.setProgress(Math.max(0, Math.min(100, percent)));
        }
    }

    private void updateActiveStyleBadge() {
        if (activeStyleBadge == null || etStylePrompt == null) return;
        String presetName = (selectedPresetIdx >= 0 && selectedPresetIdx < STYLE_PRESETS.length)
                ? STYLE_PRESETS[selectedPresetIdx][0] : "Gaya Bebas";
        String cur = etStylePrompt.getText() == null ? "" : etStylePrompt.getText().toString().trim();
        if (cur.isEmpty() && "Tidak ada".equals(getCheckedMixinsSummary())) {
            presetName = "Natural Polos";
        }
        String extras = getCheckedMixinsSummary();
        String multiInfo = multiSpeakerMode ? (" + " + getActiveVoice(2)) : "";
        String summary = "Gaya Utama: " + presetName
                + "\nTambahan Ekspresi (+): " + extras
                + "\nSuara: " + getActiveVoice(1) + multiInfo + " • " + selectedLanguage + " • " + selectedModel;
        activeStyleBadge.setText(summary);
        activeStyleBadge.setContentDescription(summary.replace("\n", ". "));
    }

    private void switchTab(int idx) {
        activeTab = idx;
        tabPanel0.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        tabPanel1.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        tabPanel2.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);

        int inactiveBg = UiKit.surfaceAlt(prefs);
        int inactiveTxt = UiKit.textPrimary(prefs);
        styleTabButton(tabBtn0, idx == 0, inactiveBg, inactiveTxt);
        styleTabButton(tabBtn1, idx == 1, inactiveBg, inactiveTxt);
        styleTabButton(tabBtn2, idx == 2, inactiveBg, inactiveTxt);
    }

    private void styleTabButton(Button b, boolean active, int inactiveBg, int inactiveTxt) {
        b.setBackground(UiKit.roundedBox(active ? UiKit.ACCENT_AI : inactiveBg, Color.TRANSPARENT, UiKit.dp(this, 10), 0));
        b.setTextColor(active ? Color.WHITE : inactiveTxt);
    }

    private void updateKeyBadge() {
        String personal = prefs.getString("api_key_gemini", "").trim();
        String effective = AiEngine.resolveApiKey(this, prefs, "Google Gemini");
        String activeGlobal = AiEngine.getActiveProvider(prefs);
        if (personal.isEmpty() && !effective.isEmpty()) {
            keyBadge.setText("Bonus Admin Gemini AKTIF (otomatis siap pakai) | Otak AI: " + activeGlobal);
            keyBadge.setTextColor(Color.parseColor("#34D399"));
        } else if (effective.isEmpty()) {
            keyBadge.setText("Kunci API Gemini belum diisi (Otak AI utama: " + activeGlobal + ")");
            keyBadge.setTextColor(Color.parseColor("#F59E0B"));
        } else {
            String tail = personal.length() > 6 ? personal.substring(personal.length() - 6) : "***";
            keyBadge.setText("Kunci Gemini aktif (..." + tail + ") | Otak AI utama: " + activeGlobal);
            keyBadge.setTextColor(Color.parseColor("#34D399"));
        }
    }

    private void updateModelCountInfo() {
        List<String> list = loadSavedModels();
        modelInfoLabel.setText("Tersimpan " + list.size() + " model Gemini TTS untuk akun Anda.");
    }

    private List<String> loadSavedModels() {
        List<String> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("gtts_models_json", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String m = arr.optString(i, "").trim();
                if (!m.isEmpty() && !list.contains(m)) list.add(m);
            }
        } catch (Exception ignored) {}
        for (String d : DEFAULT_TTS_MODELS) {
            if (!list.contains(d)) list.add(d);
        }
        return list;
    }

    /** Memuat daftar suara hasil update endpoint (terpisah dari 30 suara bawaan skrip, bebas duplikat). */
    private List<String> loadUpdatedVoices() {
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("gtts_updated_voices_json", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String label = arr.optString(i, "").trim();
                if (!label.isEmpty()) {
                    String id = extractVoiceId(label);
                    if (!unique.containsKey(id)) unique.put(id, label);
                }
            }
        } catch (Exception ignored) {}
        return new ArrayList<>(unique.values());
    }

    private static String extractVoiceId(String label) {
        if (label == null) return "";
        int sp = label.indexOf(' ');
        return sp > 0 ? label.substring(0, sp).trim() : label.trim();
    }

    private void refreshVoiceModeUi() {
        List<String> updated = loadUpdatedVoices();
        if (voiceSourceMode == VOICE_MODE_UPDATED && updated.isEmpty()) {
            voiceSourceMode = VOICE_MODE_BUILTIN;
        }
        String modeTitle = (voiceSourceMode == VOICE_MODE_UPDATED)
                ? ("Sumber Suara: Mode 2 — Hasil Update (" + updatedVoicesLang + ")")
                : "Sumber Suara: Mode 1 — Bawaan Skrip (30 Suara)";
        if (btnVoiceSourceMode != null) btnVoiceSourceMode.setText(modeTitle);
        if (voiceInfoLabel != null) {
            if (updated.isEmpty()) {
                voiceInfoLabel.setText("Mode 1 aktif (30 suara bawaan Gemini). Tekan tombol di atas jika ingin update suara khusus 1 bahasa dari endpoint.");
            } else {
                voiceInfoLabel.setText("Tersedia " + BUILTIN_GEMINI_VOICES.length + " suara bawaan skrip & "
                        + updated.size() + " suara update khusus bahasa " + updatedVoicesLang + " (terpisah, bebas double).");
            }
        }
        if (btnVoice1 != null) btnVoice1.setText("Suara Pembicara 1: " + getActiveVoice(1));
        if (btnVoice2 != null) btnVoice2.setText("Suara Pembicara 2: " + getActiveVoice(2));
        updateActiveStyleBadge();
    }

    private void pickVoiceSourceMode() {
        final List<String> updated = loadUpdatedVoices();
        String mode2Label = updated.isEmpty()
                ? "Mode 2: Suara Hasil Update Endpoint (Belum di-update — klik untuk update)"
                : ("Mode 2: Suara Hasil Update Endpoint — " + updatedVoicesLang + " (" + updated.size() + " suara)");
        String[] opts = {
                "Mode 1: Suara Bawaan Skrip (" + BUILTIN_GEMINI_VOICES.length + " Suara Resmi Gemini)",
                mode2Label
        };
        UiKit.dialog(this, prefs)
                .setTitle("Pilih Mode Sumber Suara (Terpisah)")
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        voiceSourceMode = VOICE_MODE_BUILTIN;
                        saveSettings();
                        refreshVoiceModeUi();
                    } else {
                        if (updated.isEmpty()) {
                            promptLanguageAndFetchVoices();
                        } else {
                            voiceSourceMode = VOICE_MODE_UPDATED;
                            saveSettings();
                            refreshVoiceModeUi();
                        }
                    }
                })
                .show();
    }

    private void promptLanguageAndFetchVoices() {
        final String key = AiEngine.resolveApiKey(this, prefs, "Google Gemini");
        if (key.isEmpty()) {
            Toast.makeText(this, "Isi Kunci API Gemini terlebih dahulu di Tab 3.", Toast.LENGTH_LONG).show();
            switchTab(2);
            showGeminiKeyDialog();
            return;
        }
        String[] items = new String[UPDATE_VOICE_LANG_CODES.length];
        for (int i = 0; i < UPDATE_VOICE_LANG_CODES.length; i++) {
            items[i] = UPDATE_VOICE_LANG_CODES[i][1] + " [" + UPDATE_VOICE_LANG_CODES[i][0] + "]";
        }
        UiKit.dialog(this, prefs)
                .setTitle("Pilih 1 Bahasa Sebelum Update Suara")
                .setItems(items, (d, which) -> {
                    String langCode = UPDATE_VOICE_LANG_CODES[which][0];
                    fetchVoicesFromEndpoint(key, langCode);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private static String httpGetJson(String urlStr, String apiKey) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("x-goog-api-key", apiKey);
            }
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (stream != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
            }
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + (sb.length() > 0 ? (": " + sb.substring(0, Math.min(220, sb.length()))) : ""));
            }
            return sb.toString();
        } finally {
            try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String httpPostGeminiJson(String urlStr, String apiKey, String bodyJson) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(25000);
            conn.setReadTimeout(180000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("x-goog-api-key", apiKey);
            }
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyJson.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (stream != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
            }
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + ": " + sb.toString());
            }
            return sb.toString();
        } finally {
            try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String formatAiStudioVoiceLabel(JSONObject v) {
        String id = v.optString("id", v.optString("voice_id", v.optString("name", ""))).replace("voices/", "").trim();
        if (id.isEmpty()) return "";
        String disp = v.optString("display_name", v.optString("displayName", id)).trim();
        String gRaw = v.optString("gender", v.optString("ssmlGender", "neutral")).trim();
        String genderId = "female".equalsIgnoreCase(gRaw) ? "Wanita"
                : ("male".equalsIgnoreCase(gRaw) ? "Pria" : "Netral");
        String vLang = v.optString("language_code", v.optString("languageCode", "")).trim();
        if (vLang.isEmpty()) {
            JSONArray lcArr = v.optJSONArray("languageCodes");
            if (lcArr != null && lcArr.length() > 0) vLang = lcArr.optString(0, "").trim();
        }
        String accent = v.optString("accent", "").trim();
        String pitch = v.optString("pitch", "").trim();
        String pitchId = "low".equalsIgnoreCase(pitch) ? "Nada Rendah"
                : ("high".equalsIgnoreCase(pitch) ? "Nada Tinggi"
                : ("medium".equalsIgnoreCase(pitch) ? "Nada Sedang" : ""));
        String vType = v.optString("type", "prebuilt").trim();
        String typeTag = "prompted".equalsIgnoreCase(vType) ? " [Voice Design]"
                : ("replicated".equalsIgnoreCase(vType) ? " [Voice Clone]" : "");
        String persona = v.optString("persona", v.optString("description", "")).trim();
        if (persona.length() > 48) persona = persona.substring(0, 48).trim() + "...";

        StringBuilder meta = new StringBuilder();
        meta.append(disp).append(typeTag).append(" | ").append(genderId);
        if (!pitchId.isEmpty()) meta.append(" • ").append(pitchId);
        if (!accent.isEmpty()) meta.append(" • ").append(accent);
        if (!vLang.isEmpty()) meta.append(" • ").append(vLang);
        if (!persona.isEmpty() && !persona.equalsIgnoreCase(disp)) meta.append(" — ").append(persona);
        return id + " (" + meta + ")";
    }

    private void fetchVoicesFromEndpoint(final String key, final String langCode) {
        btnUpdateVoices.setEnabled(false);
        voiceInfoLabel.setText("Menghubungi Google AI Studio Extended Voice Library (v1beta/voices) untuk " + langCode + "...");
        new Thread(() -> {
            try {
                LinkedHashMap<String, String> langMatched = new LinkedHashMap<>();
                LinkedHashMap<String, String> customVoices = new LinkedHashMap<>();
                LinkedHashMap<String, String> allUnique = new LinkedHashMap<>();
                String shortLang = langCode.split("-")[0].toLowerCase(Locale.ROOT);
                String sourceUsed = "Google AI Studio Extended Voice Library (v1beta/voices)";
                String voiceProviderTag = "ai_studio";

                boolean aiStudioOk = false;
                String aiStudioErr = "";

                // Tahap 1: Panggil endpoint resmi GET /v1beta/voices dengan filter language_code sesuai dokumentasi AI Studio
                try {
                    String nextPage = "";
                    for (int page = 0; page < 8; page++) {
                        String reqUrl = "https://generativelanguage.googleapis.com/v1beta/voices?page_size=200"
                                + "&language_code=" + java.net.URLEncoder.encode(langCode, "UTF-8")
                                + "&key=" + java.net.URLEncoder.encode(key, "UTF-8")
                                + (nextPage.isEmpty() ? "" : "&page_token=" + java.net.URLEncoder.encode(nextPage, "UTF-8"));
                        JSONObject rootObj = new JSONObject(httpGetJson(reqUrl, key));
                        JSONArray arr = rootObj.optJSONArray("voices");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject v = arr.getJSONObject(i);
                                String label = formatAiStudioVoiceLabel(v);
                                if (label.isEmpty()) continue;
                                String id = extractVoiceId(label);
                                if (!langMatched.containsKey(id)) langMatched.put(id, label);
                                if (!allUnique.containsKey(id)) allUnique.put(id, label);
                            }
                        }
                        aiStudioOk = true;
                        nextPage = rootObj.optString("next_page_token", rootObj.optString("nextPageToken", "")).trim();
                        if (nextPage.isEmpty()) break;
                    }
                } catch (Exception e) {
                    aiStudioErr = e.getMessage() != null ? e.getMessage() : "gagal";
                }

                // Tahap 2: Jika filter spesifik language_code kosong (mis. bahasa daerah jv-ID/su-ID atau suara custom/multilingual),
                // ambil katalog lengkap /v1beta/voices (page_size=200) lalu prioritaskan yang cocok bahasa + suara custom Voice Design/Clone
                if (langMatched.isEmpty()) {
                    try {
                        String nextPage = "";
                        for (int page = 0; page < 10; page++) {
                            String reqUrl = "https://generativelanguage.googleapis.com/v1beta/voices?page_size=200"
                                    + "&key=" + java.net.URLEncoder.encode(key, "UTF-8")
                                    + (nextPage.isEmpty() ? "" : "&page_token=" + java.net.URLEncoder.encode(nextPage, "UTF-8"));
                            JSONObject rootObj = new JSONObject(httpGetJson(reqUrl, key));
                            JSONArray arr = rootObj.optJSONArray("voices");
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject v = arr.getJSONObject(i);
                                    String label = formatAiStudioVoiceLabel(v);
                                    if (label.isEmpty()) continue;
                                    String id = extractVoiceId(label);
                                    String vLang = v.optString("language_code", v.optString("languageCode", "")).trim().toLowerCase(Locale.ROOT);
                                    String vType = v.optString("type", "prebuilt").trim().toLowerCase(Locale.ROOT);
                                    if (!allUnique.containsKey(id)) allUnique.put(id, label);
                                    if (("prompted".equals(vType) || "replicated".equals(vType)) && !customVoices.containsKey(id)) {
                                        customVoices.put(id, label);
                                    }
                                    if ((vLang.equals(langCode.toLowerCase(Locale.ROOT)) || vLang.startsWith(shortLang))
                                            && !langMatched.containsKey(id)) {
                                        langMatched.put(id, label);
                                    }
                                }
                            }
                            aiStudioOk = true;
                            nextPage = rootObj.optString("next_page_token", rootObj.optString("nextPageToken", "")).trim();
                            if (nextPage.isEmpty()) break;
                        }
                    } catch (Exception e) {
                        if (aiStudioErr.isEmpty()) aiStudioErr = e.getMessage() != null ? e.getMessage() : "gagal";
                    }
                }

                // Gabungkan suara custom (Voice Design/Clone) di urutan teratas bersama suara yang cocok bahasa
                LinkedHashMap<String, String> combinedAiStudio = new LinkedHashMap<>();
                for (java.util.Map.Entry<String, String> e : customVoices.entrySet()) {
                    combinedAiStudio.put(e.getKey(), e.getValue());
                }
                for (java.util.Map.Entry<String, String> e : langMatched.entrySet()) {
                    if (!combinedAiStudio.containsKey(e.getKey())) combinedAiStudio.put(e.getKey(), e.getValue());
                }

                // Tahap 3: Fallback ke Google Cloud TTS Voices hanya jika endpoint v1beta/voices AI Studio tidak aktif / kosong
                if (!aiStudioOk || (combinedAiStudio.isEmpty() && allUnique.isEmpty())) {
                    try {
                        String cloudRaw = httpGetJson("https://texttospeech.googleapis.com/v1/voices?languageCode=" + langCode + "&key=" + key, null);
                        JSONArray rawVoices = new JSONObject(cloudRaw).optJSONArray("voices");
                        if (rawVoices != null && rawVoices.length() > 0) {
                            sourceUsed = "Google Cloud TTS Voices";
                            voiceProviderTag = "cloud_tts";
                            for (int i = 0; i < rawVoices.length(); i++) {
                                JSONObject v = rawVoices.getJSONObject(i);
                                String name = v.optString("name", "").trim();
                                if (name.isEmpty() || combinedAiStudio.containsKey(name)) continue;
                                String gRaw = v.optString("ssmlGender", "NEUTRAL");
                                String genderId = "FEMALE".equalsIgnoreCase(gRaw) ? "Wanita"
                                        : ("MALE".equalsIgnoreCase(gRaw) ? "Pria" : "Netral");
                                combinedAiStudio.put(name, name + " (" + genderId + " - " + langCode + ")");
                            }
                        }
                    } catch (Exception cloudErr) {
                        if (!aiStudioOk) {
                            throw new Exception("Google AI Studio (v1beta/voices): " + aiStudioErr
                                    + "\nFallback Cloud TTS: " + cloudErr.getMessage());
                        }
                    }
                }

                final boolean usedFallbackMultilingual = combinedAiStudio.isEmpty() && !allUnique.isEmpty();
                LinkedHashMap<String, String> chosen = combinedAiStudio.isEmpty() ? allUnique : combinedAiStudio;
                if (chosen.isEmpty()) {
                    throw new Exception("Endpoint tidak mengembalikan suara apa pun untuk " + langCode);
                }

                JSONArray savedArr = new JSONArray();
                String firstId = null;
                String secondId = null;
                for (String label : chosen.values()) {
                    savedArr.put(label);
                    String id = extractVoiceId(label);
                    if (firstId == null) firstId = id;
                    else if (secondId == null) secondId = id;
                }
                if (secondId == null) secondId = firstId;

                updatedVoicesLang = usedFallbackMultilingual ? (langCode + " + Katalog Multilingual AI Studio") : langCode;
                voice1Updated = firstId != null ? firstId : "";
                voice2Updated = secondId != null ? secondId : "";
                voiceSourceMode = VOICE_MODE_UPDATED;

                prefs.edit()
                        .putString("gtts_updated_voices_json", savedArr.toString())
                        .putString("gtts_updated_voices_provider", voiceProviderTag)
                        .apply();
                saveSettings();

                final int count = chosen.size();
                final String finalSrc = sourceUsed;
                mainHandler.post(() -> {
                    btnUpdateVoices.setEnabled(true);
                    refreshVoiceModeUi();
                    Toast.makeText(this, "Berhasil memperbarui " + count + " suara resmi dari " + finalSrc + " (Mode 2 aktif).", Toast.LENGTH_LONG).show();
                });
            } catch (final Exception e) {
                mainHandler.post(() -> {
                    btnUpdateVoices.setEnabled(true);
                    refreshVoiceModeUi();
                    if (UiKit.isActivityAlive(this)) {
                        UiKit.dialog(this, prefs)
                                .setTitle("Info Update Suara Google AI Studio")
                                .setMessage("Gagal mengambil daftar suara dari endpoint:\n" + e.getMessage()
                                        + "\n\nPastikan kunci API Gemini Google AI Studio valid & koneksi internet aktif. Mode 1 (30 Suara Bawaan Skrip) tetap bisa langsung digunakan.")
                                .show();
                    }
                });
            }
        }).start();
    }

    private void fetchGeminiModelsFromEndpoint() {
        final String key = AiEngine.resolveApiKey(this, prefs, "Google Gemini");
        if (key.isEmpty()) {
            Toast.makeText(this, "Isi Kunci API Gemini terlebih dahulu di Tab 3.", Toast.LENGTH_LONG).show();
            showGeminiKeyDialog();
            return;
        }
        btnUpdateModels.setEnabled(false);
        modelInfoLabel.setText("Menghubungi Google AI Studio (v1beta/models) untuk memperbarui model TTS...");
        new Thread(() -> {
            try {
                LinkedHashMap<String, Boolean> uniqueModels = new LinkedHashMap<>();
                for (String def : DEFAULT_TTS_MODELS) {
                    uniqueModels.put(def, Boolean.TRUE);
                }
                String nextPage = "";
                for (int page = 0; page < 8; page++) {
                    String reqUrl = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100&key=" + key
                            + (nextPage.isEmpty() ? "" : "&pageToken=" + java.net.URLEncoder.encode(nextPage, "UTF-8"));
                    JSONObject rootObj = new JSONObject(httpGetJson(reqUrl, key));
                    JSONArray modelsArr = rootObj.optJSONArray("models");
                    if (modelsArr != null) {
                        for (int i = 0; i < modelsArr.length(); i++) {
                            JSONObject m = modelsArr.getJSONObject(i);
                            String name = m.optString("name", "").replace("models/", "").trim();
                            if (name.isEmpty()) continue;
                            String lower = name.toLowerCase(Locale.ROOT);
                            String desc = m.optString("description", "").toLowerCase(Locale.ROOT);
                            if (lower.contains("tts") || desc.contains("text-to-speech") || desc.contains("speech synthesis")) {
                                uniqueModels.put(name, Boolean.TRUE);
                            }
                        }
                    }
                    nextPage = rootObj.optString("nextPageToken", rootObj.optString("next_page_token", "")).trim();
                    if (nextPage.isEmpty()) break;
                }
                JSONArray ttsModels = new JSONArray();
                for (String mName : uniqueModels.keySet()) {
                    ttsModels.put(mName);
                }
                prefs.edit().putString("gtts_models_json", ttsModels.toString()).apply();
                final int count = ttsModels.length();
                mainHandler.post(() -> {
                    btnUpdateModels.setEnabled(true);
                    updateModelCountInfo();
                    Toast.makeText(this, "Daftar model Google AI Studio TTS diperbarui (" + count + " model).", Toast.LENGTH_LONG).show();
                });
            } catch (final Exception e) {
                mainHandler.post(() -> {
                    btnUpdateModels.setEnabled(true);
                    modelInfoLabel.setText("Gagal memperbarui model: " + e.getMessage());
                    Toast.makeText(this, "Gagal ambil model: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void updateMeta() {
        String raw = etInputText.getText() == null ? "" : etInputText.getText().toString();
        List<String> chunks = splitIntoSafeChunks(raw, chunkCharLimit);
        int count = chunks.size();
        String warn = count > maxChunksGuard ? (" | Melebihi batas " + maxChunksGuard + " potongan (dipotong aman)") : "";
        String info = "Panjang: " + raw.length() + " kar. | Estimasi: " + count + " potongan (maks " + maxChunksGuard + ")" + warn;
        metaLabel.setText(info);
        if (!generating) {
            if (raw.trim().isEmpty()) {
                updateProgressStatus(0, "Status: Siap (0%) • Ketik atau tempel naskah untuk memulai.", false);
            } else {
                updateProgressStatus(0, "Siap diproses (0%) • " + count + " potongan (" + raw.length() + " karakter).", false);
            }
        }
    }

    private void pickModel() {
        List<String> list = loadSavedModels();
        final String[] opts = new String[list.size() + 1];
        for (int i = 0; i < list.size(); i++) opts[i] = list.get(i);
        opts[list.size()] = "Isi Nama Model Custom...";

        UiKit.dialog(this, prefs)
                .setTitle("Pilih Model Gemini TTS")
                .setItems(opts, (d, which) -> {
                    if (which < list.size()) {
                        selectedModel = list.get(which);
                        btnModel.setText("Model Aktif: " + selectedModel);
                        saveSettings();
                        updateActiveStyleBadge();
                    } else {
                        EditText ed = new EditText(this);
                        ed.setText(selectedModel);
                        UiKit.styleEditText(this, ed, prefs);
                        UiKit.dialog(this, prefs)
                                .setTitle("Model Custom Gemini TTS")
                                .setView(ed)
                                .setNegativeButton("Batal", null)
                                .setPositiveButton("Pakai", (d2, w2) -> {
                                    String m = ed.getText().toString().trim();
                                    if (!m.isEmpty()) {
                                        selectedModel = m;
                                        btnModel.setText("Model Aktif: " + selectedModel);
                                        saveSettings();
                                        updateActiveStyleBadge();
                                    }
                                }).show();
                    }
                }).show();
    }

    private void pickLanguage() {
        UiKit.dialog(this, prefs)
                .setTitle("Pilih Bahasa Pengucapan")
                .setItems(TTS_LANGUAGES, (d, which) -> {
                    selectedLanguage = TTS_LANGUAGES[which];
                    btnLanguage.setText("Bahasa Pengucapan: " + selectedLanguage);
                    saveSettings();
                    updateActiveStyleBadge();
                }).show();
    }

    private void pickStylePreset() {
        String[] names = new String[STYLE_PRESETS.length];
        for (int i = 0; i < STYLE_PRESETS.length; i++) {
            names[i] = (i == selectedPresetIdx ? "[AKTIF] " : "") + STYLE_PRESETS[i][0];
        }
        UiKit.dialog(this, prefs)
                .setTitle("1. Pilih Gaya Utama")
                .setItems(names, (d, which) -> {
                    selectedPresetIdx = which;
                    rebuildPromptFromPresetAndMixins();
                    saveSettings();
                    updateActiveStyleBadge();
                    boolean isFreeStyle = (which == STYLE_PRESETS.length - 1);
                    openMixinChecklistDialog(isFreeStyle);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void openMixinChecklistDialog(final boolean isFreeStyle) {
        final String[] labels = new String[EXPRESSION_MIXINS.length];
        final boolean[] tempChecked = new boolean[EXPRESSION_MIXINS.length];
        for (int i = 0; i < EXPRESSION_MIXINS.length; i++) {
            labels[i] = "+ " + EXPRESSION_MIXINS[i][0];
            tempChecked[i] = checkedMixins[i];
        }
        String presetName = STYLE_PRESETS[Math.max(0, Math.min(selectedPresetIdx, STYLE_PRESETS.length - 1))][0];
        UiKit.dialog(this, prefs)
                .setTitle("2. Centang Tambahan Ekspresi (+ " + presetName + ")")
                .setMultiChoiceItems(labels, tempChecked, (d, idx, isC) -> tempChecked[idx] = isC)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan Pilihan", (d, w) -> {
                    updatingStyleUi = true;
                    int addedCount = 0;
                    for (int i = 0; i < EXPRESSION_MIXINS.length; i++) {
                        checkedMixins[i] = tempChecked[i];
                        if (tempChecked[i]) addedCount++;
                        if (mixinCheckBoxes[i] != null) mixinCheckBoxes[i].setChecked(tempChecked[i]);
                    }
                    updatingStyleUi = false;
                    rebuildPromptFromPresetAndMixins();
                    saveSettings();
                    updateActiveStyleBadge();
                    if (isFreeStyle) {
                        switchTab(2);
                        Toast.makeText(this, "Gaya Bebas + " + addedCount + " ekspresi tambahan aktif.", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Gaya: " + presetName + " (" + addedCount + " ekspresi dicentang).", Toast.LENGTH_SHORT).show();
                    }
                    OfficeUtils.announce(activeStyleBadge, activeStyleBadge.getContentDescription() != null
                            ? activeStyleBadge.getContentDescription().toString() : "Gaya diperbarui");
                })
                .show();
    }

    private void rebuildPromptFromPresetAndMixins() {
        StringBuilder sb = new StringBuilder();
        String base = (selectedPresetIdx >= 0 && selectedPresetIdx < STYLE_PRESETS.length)
                ? STYLE_PRESETS[selectedPresetIdx][1] : "";
        if (!base.isEmpty()) sb.append(base);
        for (int i = 0; i < EXPRESSION_MIXINS.length; i++) {
            if (checkedMixins[i]) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(EXPRESSION_MIXINS[i][1]);
            }
        }
        updatingStyleUi = true;
        String finalPrompt = sb.toString().trim();
        if (etStylePrompt != null) {
            etStylePrompt.setText(finalPrompt);
        }
        prefs.edit().putString("gtts_style_prompt", finalPrompt).apply();
        updatingStyleUi = false;
    }

    private void pickVoice(final int slot) {
        final boolean isUpdatedMode = (voiceSourceMode == VOICE_MODE_UPDATED);
        final String[] list;
        if (isUpdatedMode) {
            List<String> upd = loadUpdatedVoices();
            if (upd.isEmpty()) {
                Toast.makeText(this, "Belum ada suara update. Silakan perbarui dari endpoint dulu.", Toast.LENGTH_SHORT).show();
                promptLanguageAndFetchVoices();
                return;
            }
            list = upd.toArray(new String[0]);
        } else {
            list = BUILTIN_GEMINI_VOICES;
        }
        String title = (isUpdatedMode ? "Mode 2 — Suara Update " + updatedVoicesLang : "Mode 1 — Suara Bawaan (30)")
                + " | Pembicara " + slot;
        UiKit.dialog(this, prefs)
                .setTitle(title)
                .setItems(list, (d, which) -> {
                    String picked = extractVoiceId(list[which]);
                    if (isUpdatedMode) {
                        if (slot == 1) voice1Updated = picked; else voice2Updated = picked;
                    } else {
                        if (slot == 1) voice1Builtin = picked; else voice2Builtin = picked;
                    }
                    saveSettings();
                    refreshVoiceModeUi();
                }).show();
    }

    private void editSpeakerNames() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 16);
        box.setPadding(p, p, p, p);
        EditText e1 = new EditText(this); e1.setHint("Nama Pembicara 1"); e1.setText(speaker1Name); UiKit.styleEditText(this, e1, prefs); box.addView(e1);
        EditText e2 = new EditText(this); e2.setHint("Nama Pembicara 2"); e2.setText(speaker2Name); UiKit.styleEditText(this, e2, prefs); box.addView(e2);
        UiKit.dialog(this, prefs)
                .setTitle("Nama Pembicara Multi-Speaker")
                .setView(box)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (d, w) -> {
                    String s1 = e1.getText().toString().trim();
                    String s2 = e2.getText().toString().trim();
                    if (!s1.isEmpty()) speaker1Name = s1;
                    if (!s2.isEmpty()) speaker2Name = s2;
                    cbMultiSpeaker.setText("Mode Multi-Speaker (2 Pembicara: " + speaker1Name + " dan " + speaker2Name + ")");
                    saveSettings();
                }).show();
    }

    private void showLimitGuardDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 16);
        box.setPadding(p, p, p, p);
        int txt = UiKit.textPrimary(prefs);

        TextView l1 = new TextView(this); l1.setText("Maks karakter per potongan (disarankan 1200–2000):"); l1.setTextColor(txt); box.addView(l1);
        EditText eChars = new EditText(this);
        eChars.setInputType(InputType.TYPE_CLASS_NUMBER);
        eChars.setText(String.valueOf(chunkCharLimit));
        UiKit.styleEditText(this, eChars, prefs);
        box.addView(eChars);

        TextView l2 = new TextView(this); l2.setText("Batas maks potongan per generate (pelindung limit):"); l2.setTextColor(txt); box.addView(l2);
        EditText eMax = new EditText(this);
        eMax.setInputType(InputType.TYPE_CLASS_NUMBER);
        eMax.setText(String.valueOf(maxChunksGuard));
        UiKit.styleEditText(this, eMax, prefs);
        box.addView(eMax);

        TextView l3 = new TextView(this); l3.setText("Jeda antar request (ms, contoh 1500 = 1,5 detik):"); l3.setTextColor(txt); box.addView(l3);
        EditText eDelay = new EditText(this);
        eDelay.setInputType(InputType.TYPE_CLASS_NUMBER);
        eDelay.setText(String.valueOf(chunkDelayMs));
        UiKit.styleEditText(this, eDelay, prefs);
        box.addView(eDelay);

        UiKit.dialog(this, prefs)
                .setTitle("Logika Pelindung Limit Gemini TTS")
                .setView(box)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (d, w) -> {
                    try {
                        chunkCharLimit = Math.max(300, Math.min(4000, Integer.parseInt(eChars.getText().toString().trim())));
                        maxChunksGuard = Math.max(1, Math.min(50, Integer.parseInt(eMax.getText().toString().trim())));
                        chunkDelayMs = Math.max(500, Math.min(10000, Integer.parseInt(eDelay.getText().toString().trim())));
                        saveSettings();
                        updateMeta();
                        Toast.makeText(this, "Pengaturan batas limit disimpan.", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Input angka tidak valid.", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void showGeminiKeyDialog() {
        EditText ed = new EditText(this);
        ed.setHint("Tempel API Key Gemini (AIza...) khusus TTS");
        ed.setText(prefs.getString("api_key_gemini", ""));
        UiKit.styleEditText(this, ed, prefs);
        UiKit.dialog(this, prefs)
                .setTitle("Kunci API Gemini (Khusus TTS)")
                .setMessage("Mengubah kunci ini tidak memengaruhi kunci Grok/OpenAI/DeepSeek Anda di Otak AI.")
                .setView(ed)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (d, w) -> {
                    prefs.edit().putString("api_key_gemini", ed.getText().toString().trim()).apply();
                    updateKeyBadge();
                    Toast.makeText(this, "Kunci API Gemini disimpan.", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void startGenerateAudio() {
        if (!AuthManager.isPremiumActive(this)) {
            AuthManager.showPremiumRequiredDialog(this, "Gemini Text-to-Speech Studio", null);
            return;
        }
        final String geminiKey = AiEngine.resolveApiKey(this, prefs, "Google Gemini");
        if (geminiKey.isEmpty()) {
            Toast.makeText(this, "Kunci API Gemini masih kosong. Isi dulu di Tab 3.", Toast.LENGTH_LONG).show();
            switchTab(2);
            showGeminiKeyDialog();
            return;
        }
        String rawText = etInputText.getText() == null ? "" : etInputText.getText().toString().trim();
        if (rawText.isEmpty()) {
            Toast.makeText(this, "Masukkan teks terlebih dahulu.", Toast.LENGTH_SHORT).show();
            return;
        }

        final String stylePrompt = etStylePrompt.getText() == null ? "" : etStylePrompt.getText().toString().trim();
        prefs.edit().putString("gtts_style_prompt", stylePrompt).apply();

        final String langDirective = "Otomatis (Sesuai Teks)".equals(selectedLanguage)
                ? "" : ("Gunakan pelafalan " + selectedLanguage + ". ");
        final String exprRule = (cbDynamicExpr != null && cbDynamicExpr.isChecked())
                ? "Sesuaikan intonasi emosi suara secara alami di setiap kalimat (sedih, marah, senang, berbisik, tegang, terkejut, dll) serta ikuti arahan dalam kurung siku [...] TANPA melafalkan teks di dalam kurung siku."
                : "";
        final String fullDirective = (langDirective + stylePrompt + (exprRule.isEmpty() ? "" : "\n" + exprRule)).trim();

        if (cbUseAppLogic.isChecked() && !multiSpeakerMode) {
            rawText = HumanSpeechNormalizer.normalizeForDocumentTts(rawText);
        }

        final List<String> allChunks = splitIntoSafeChunks(rawText, chunkCharLimit);
        final int totalToRun = Math.min(allChunks.size(), maxChunksGuard);
        final int totalChars = rawText.length();
        final String activeV1 = getActiveVoice(1);
        final String activeV2 = getActiveVoice(2);

        generating = true;
        cancelRequested = false;
        releaseMediaPlayer();
        btnGenerate.setText("Batalkan Proses Gemini TTS");
        btnGenerate.setBackground(UiKit.roundedBox(UiKit.ACCENT_DANGER, Color.TRANSPARENT, UiKit.dp(this, 12), 0));
        btnPlay.setEnabled(false);
        btnSave.setEnabled(false);
        btnShare.setEnabled(false);
        if (btnMixBacksoundWav != null) btnMixBacksoundWav.setEnabled(false);
        if (btnExportFullVideo != null) btnExportFullVideo.setEnabled(false);
        if (btnWaStatusVideo != null) btnWaStatusVideo.setEnabled(false);
        updateProgressStatus(2, "Progres: 2% • Memulai konversi 0 dari " + totalToRun + " potongan (" + totalChars + " kar.)...", true);

        new Thread(() -> {
            try {
                ByteArrayOutputStream pcmAll = new ByteArrayOutputStream();
                int processedChars = 0;
                for (int i = 0; i < totalToRun; i++) {
                    if (cancelRequested) throw new InterruptedException("Dibatalkan pengguna.");
                    final int idx = i + 1;
                    final String chunk = allChunks.get(i);
                    final int startPct = Math.max(5, Math.min(95, Math.round((i * 100f) / Math.max(1, totalToRun))));
                    final int curCharsDone = processedChars;
                    mainHandler.post(() -> {
                        String st = "Progres: " + startPct + "% • Bagian " + idx + " dari " + totalToRun
                                + " (" + curCharsDone + "/" + totalChars + " kar.) • Suara: " + activeV1;
                        updateProgressStatus(startPct, st, true);
                        OfficeUtils.announce(statusLabel, "Progres " + startPct + " persen. Memproses bagian " + idx + " dari " + totalToRun);
                    });

                    byte[] chunkPcm = isCloudTtsVoice(activeV1)
                            ? requestCloudTtsChunk(geminiKey, activeV1, chunk)
                            : requestGeminiTtsChunk(geminiKey, fullDirective, activeV1, activeV2, chunk);
                    pcmAll.write(chunkPcm);
                    processedChars += chunk.length();

                    final int donePct = Math.min(99, Math.round((idx * 100f) / Math.max(1, totalToRun)));
                    final int charsAfter = Math.min(totalChars, processedChars);
                    mainHandler.post(() -> {
                        String st = "Progres: " + donePct + "% • Selesai bagian " + idx + " dari " + totalToRun
                                + " (" + charsAfter + "/" + totalChars + " kar.)";
                        updateProgressStatus(donePct, st, true);
                    });

                    if (i + 1 < totalToRun && !cancelRequested) {
                        Thread.sleep(chunkDelayMs);
                    }
                }

                byte[] totalPcm = pcmAll.toByteArray();
                if (totalPcm.length == 0) throw new IllegalStateException("Server mengembalikan audio kosong.");

                File cacheTtsDir = new File(getCacheDir(), "gemini_tts");
                OfficeUtils.clearDirectoryContents(cacheTtsDir);
                if (!cacheTtsDir.exists()) cacheTtsDir.mkdirs();

                File dir = OfficeUtils.resolveBackupSubfolder(this, "Gemini TTS/Gemini Audio");
                String fmt = normalizeAudioFmt(selectedAudioFmt);
                long ts = System.currentTimeMillis();
                File outAudio = new File(dir, "GeminiTTS_" + ts + "." + fmt);
                if ("wav".equals(fmt)) {
                    writeWavFile(outAudio, totalPcm, SAMPLE_RATE, 1, 16);
                } else {
                    mainHandler.post(() -> updateProgressStatus(97, "Mengompres audio ke " + fmt.toUpperCase(Locale.ROOT) + " hemat jernih...", true));
                    File tmpRawWav = new File(cacheTtsDir, "raw_" + ts + ".wav");
                    try {
                        writeWavFile(tmpRawWav, totalPcm, SAMPLE_RATE, 1, 16);
                        int br = bitrateForVoice(fmt);
                        int sr = "opus".equals(fmt) ? 48000 : SAMPLE_RATE;
                        AudioDspEngine.encodeAudioClean(tmpRawWav, outAudio, fmt, br, sr, 1, true);
                    } finally {
                        try { tmpRawWav.delete(); } catch (Throwable ignored) {}
                    }
                }
                generatedWavFile = outAudio;

                double sec = totalPcm.length / (double) (SAMPLE_RATE * 2);
                final String msg = String.format(Locale.US,
                        "Progres: 100%% (Selesai) • %d dari %d bagian -> %s (%.1f dtk, %d KB).",
                        totalToRun, totalToRun, outAudio.getAbsolutePath(), sec, Math.max(1L, outAudio.length() / 1024));

                mainHandler.post(() -> {
                    generating = false;
                    resetGenerateBtn();
                    updateProgressStatus(100, msg, false);
                    btnPlay.setEnabled(true);
                    btnSave.setEnabled(true);
                    btnShare.setEnabled(true);
                    if (btnMixBacksoundWav != null) btnMixBacksoundWav.setEnabled(true);
                    if (btnExportFullVideo != null) btnExportFullVideo.setEnabled(true);
                    if (btnWaStatusVideo != null) btnWaStatusVideo.setEnabled(true);
                    OfficeUtils.announce(statusLabel, "Audio Gemini TTS 100 persen selesai dibuat");
                    togglePlay();
                });
            } catch (final Exception e) {
                mainHandler.post(() -> {
                    generating = false;
                    resetGenerateBtn();
                    updateProgressStatus(0, "Gagal Gemini TTS: " + e.getMessage(), false);
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try { if (mediaPlayer.isPlaying()) mediaPlayer.stop(); } catch (Throwable ignored) {}
            try { mediaPlayer.release(); } catch (Throwable ignored) {}
            mediaPlayer = null;
        }
    }

    private boolean isCloudTtsVoice(String voiceName) {
        if (voiceName == null) return false;
        String prov = prefs != null ? prefs.getString("gtts_updated_voices_provider", "ai_studio") : "ai_studio";
        if (voiceSourceMode == VOICE_MODE_UPDATED && "cloud_tts".equals(prov)) {
            return voiceName.matches("^[a-z]{2,3}-[A-Z]{2}-(Standard|Wavenet|Neural2|Studio|Polyglot|Journey|Chirp|Casual|News)-.*");
        }
        return false;
    }

    private void resetGenerateBtn() {
        btnGenerate.setText("Konversi ke Audio (" + normalizeAudioFmt(selectedAudioFmt).toUpperCase(Locale.ROOT) + ")");
        btnGenerate.setBackground(UiKit.roundedBox(UiKit.ACCENT_AI, Color.TRANSPARENT, UiKit.dp(this, 12), 0));
    }

    private static String normalizeAudioFmt(String fmt) {
        if (fmt == null) return "m4a";
        String low = fmt.trim().toLowerCase(Locale.ROOT);
        for (String ext : AUDIO_FORMAT_EXTS) {
            if (ext.equals(low)) return ext;
        }
        return "m4a";
    }

    private static int bitrateForVoice(String fmt) {
        if ("opus".equals(fmt)) return 48;
        if ("mp3".equals(fmt)) return 96;
        return 64; // m4a / aac_low mono sangat bening & hemat (~480 KB/menit)
    }

    private static int bitrateForMixed(String fmt) {
        if ("opus".equals(fmt)) return 64;
        if ("mp3".equals(fmt)) return 128;
        return 96; // m4a / aac_low stereo (< 1 MB/menit)
    }

    private void showOutputFormatDialog() {
        int curIdx = 0;
        String cur = normalizeAudioFmt(selectedAudioFmt);
        for (int i = 0; i < AUDIO_FORMAT_EXTS.length; i++) {
            if (AUDIO_FORMAT_EXTS[i].equals(cur)) { curIdx = i; break; }
        }
        UiKit.dialog(this, prefs)
                .setTitle("Pilih Format Output Audio TTS")
                .setSingleChoiceItems(AUDIO_FORMAT_LABELS, curIdx, (d, which) -> {
                    selectedAudioFmt = AUDIO_FORMAT_EXTS[which];
                    saveSettings();
                    if (btnOutputFormat != null) {
                        btnOutputFormat.setText("Format Output Audio: " + selectedAudioFmt.toUpperCase(Locale.ROOT) + " (Hemat & Jernih)");
                    }
                    if (!generating && btnGenerate != null) resetGenerateBtn();
                    d.dismiss();
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    private byte[] requestCloudTtsChunk(String apiKey, String voiceName, String textChunk) throws Exception {
        String cleanText = stripAllStageDirectionTags(textChunk, false);
        String[] parts = voiceName.split("-");
        String langCode = (parts.length >= 2) ? (parts[0] + "-" + parts[1]) : updatedVoicesLang;

        URL url = new URL("https://texttospeech.googleapis.com/v1/text:synthesize?key=" + apiKey);
        JSONObject req = new JSONObject();
        req.put("input", new JSONObject().put("text", cleanText));
        req.put("voice", new JSONObject().put("languageCode", langCode).put("name", voiceName));
        req.put("audioConfig", new JSONObject().put("audioEncoding", "LINEAR16").put("sampleRateHertz", SAMPLE_RATE));

        String raw = AiEngine.httpPostJson(url, null, req.toString());
        String b64 = new JSONObject(raw).optString("audioContent", "");
        if (b64.isEmpty()) throw new IllegalStateException("Cloud TTS tidak mengembalikan audioContent.");
        byte[] decoded = Base64.decode(b64, Base64.DEFAULT);
        return stripWavHeaderIfPresent(decoded);
    }

    /**
     * Membersihkan teks dari instruksi atau tag kurung siku [...] agar TIDAK PERNAH dibaca keras-keras oleh model TTS,
     * serta mengonversi tag vokal sesaat ke format resmi Gemini 3.8 (<laugh>, <sigh>, <short pause>, <gasp>, <cough>, <breath>)
     * bila model mendukung inline vocal tags.
     */
    private static String stripAllStageDirectionTags(String text, boolean allowAngleVocalTags) {
        if (text == null) return "";
        String t = text;
        if (allowAngleVocalTags) {
            t = t.replaceAll("(?i)\\[(tertawa|tawa|ketawa|laugh|laughs|chuckle|chuckles)\\]", " <laugh> ");
            t = t.replaceAll("(?i)\\[(hela napas|menghela napas|napas berat|sigh|sighs)\\]", " <sigh> ");
            t = t.replaceAll("(?i)\\[(terkejut|kaget|tersentak|gasp|gasps)\\]", " <gasp> ");
            t = t.replaceAll("(?i)\\[(batuk|cough|coughs)\\]", " <cough> ");
            t = t.replaceAll("(?i)\\[(dehem|berdehem|throat-clearing|clear throat)\\]", " <throat-clearing> ");
            t = t.replaceAll("(?i)\\[(napas|tarik napas|breath|breathes)\\]", " <breath> ");
            t = t.replaceAll("(?i)\\[(jeda panjang|diam panjang|long pause)\\]", " <long pause> ");
            t = t.replaceAll("(?i)\\[(jeda|diam sejenak|pause|short pause)\\]", " <short pause> ");
        } else {
            t = t.replaceAll("(?i)<(laugh|sigh|gasp|cough|throat-clearing|breath|short pause|long pause|whisper)>", " ");
        }
        // Hapus semua tag kurung siku lainnya seperti [marah], [sedih], [bisik], [tegas] dari naskah agar tidak ikut dieja
        t = t.replaceAll("\\[[^\\]\\n]{1,60}\\]", " ");
        t = t.replaceAll("[ \\t]{2,}", " ").trim();
        return t;
    }

    /** Mengekstrak petunjuk emosi dari tag kurung siku [...] di dalam potongan naskah untuk dipindahkan ke speech_metadata.style. */
    private static String extractInlineBracketEmotions(String text) {
        if (text == null || !text.contains("[")) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[([^\\]\\n]{1,50})\\]").matcher(text);
        List<String> found = new ArrayList<>();
        while (m.find()) {
            String tag = m.group(1).trim();
            if (!tag.isEmpty() && !found.contains(tag)) found.add(tag);
        }
        return found.isEmpty() ? "" : android.text.TextUtils.join(", ", found);
    }

    /** Merangkum instruksi gaya menjadi format speech_metadata.style yang bersih (tanpa meta-prompt yang membingungkan TTS). */
    private String buildCleanStyleMetadata(String rawStylePrompt, String inlineEmotions) {
        StringBuilder sb = new StringBuilder();
        if (!"Otomatis (Sesuai Teks)".equals(selectedLanguage)) {
            sb.append("Natural ").append(selectedLanguage).append(" pronunciation");
        }
        if (rawStylePrompt != null && !rawStylePrompt.trim().isEmpty()) {
            String cleaned = rawStylePrompt
                    .replaceAll("(?i)JANGAN membacakan[^.\\n]*[.\\n]?", "")
                    .replaceAll("(?i)tanpa melafalkan[^.\\n]*[.\\n]?", "")
                    .replaceAll("(?i)Teks yang harus dibacakan\\s*:", "")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (!cleaned.isEmpty()) {
                if (sb.length() > 0) sb.append(". ");
                sb.append(cleaned);
            }
        }
        if (inlineEmotions != null && !inlineEmotions.isEmpty()) {
            if (sb.length() > 0) sb.append(". ");
            sb.append("Emotional tone: ").append(inlineEmotions);
        }
        return sb.toString().trim();
    }

    private static final class DialogueTurn {
        final String speaker;
        final String text;
        DialogueTurn(String speaker, String text) {
            this.speaker = speaker;
            this.text = text;
        }
    }

    private List<DialogueTurn> parseMultiSpeakerTurns(String rawChunk, boolean allowAngleTags) {
        List<DialogueTurn> turns = new ArrayList<>();
        String[] lines = rawChunk.split("\\r?\\n");
        String currentSpeaker = speaker1Name;
        StringBuilder currentText = new StringBuilder();
        String sp1Prefix = speaker1Name.toLowerCase(Locale.ROOT) + ":";
        String sp2Prefix = speaker2Name.toLowerCase(Locale.ROOT) + ":";

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith(sp1Prefix)) {
                if (currentText.length() > 0) {
                    String clean = stripAllStageDirectionTags(currentText.toString(), allowAngleTags);
                    if (!clean.isEmpty()) turns.add(new DialogueTurn(currentSpeaker, clean));
                    currentText.setLength(0);
                }
                currentSpeaker = speaker1Name;
                currentText.append(trimmed.substring(sp1Prefix.length()).trim());
            } else if (lower.startsWith(sp2Prefix)) {
                if (currentText.length() > 0) {
                    String clean = stripAllStageDirectionTags(currentText.toString(), allowAngleTags);
                    if (!clean.isEmpty()) turns.add(new DialogueTurn(currentSpeaker, clean));
                    currentText.setLength(0);
                }
                currentSpeaker = speaker2Name;
                currentText.append(trimmed.substring(sp2Prefix.length()).trim());
            } else {
                if (currentText.length() > 0) currentText.append(" ");
                currentText.append(trimmed);
            }
        }
        if (currentText.length() > 0) {
            String clean = stripAllStageDirectionTags(currentText.toString(), allowAngleTags);
            if (!clean.isEmpty()) turns.add(new DialogueTurn(currentSpeaker, clean));
        }
        if (turns.isEmpty()) {
            String clean = stripAllStageDirectionTags(rawChunk, allowAngleTags);
            if (!clean.isEmpty()) turns.add(new DialogueTurn(speaker1Name, clean));
        }
        return turns;
    }

    private byte[] requestGeminiTtsChunk(String geminiKey, String rawDirective, String v1, String v2, String textChunk) throws Exception {
        String modelLower = selectedModel == null ? "" : selectedModel.toLowerCase(Locale.ROOT);
        boolean isGemini38OrNewer = modelLower.contains("3.8") || modelLower.contains("gemini-4");
        boolean supportsAngleTags = isGemini38OrNewer || modelLower.contains("3.1");

        String inlineEmotions = extractInlineBracketEmotions(textChunk);
        String stylePromptOnly = etStylePrompt != null && etStylePrompt.getText() != null
                ? etStylePrompt.getText().toString().trim() : "";
        String cleanStyleMeta = buildCleanStyleMetadata(stylePromptOnly, inlineEmotions);

        // Naskah murni yang HANYA boleh diucapkan (instruksi custom TIDAK PERNAH dimasukkan ke dalam text!)
        String pureVerbatimText = stripAllStageDirectionTags(textChunk, supportsAngleTags);
        if (pureVerbatimText.isEmpty()) {
            pureVerbatimText = textChunk.replaceAll("[\\[\\]]", "").trim();
        }

        // 1. Untuk model Gemini 3.8 TTS (gemini-3.8-flash-tts / gemini-3.8-flash-lite-tts):
        //    Gunakan skema resmi Google AI Studio (Interactions API v1beta/interactions dengan speech_metadata,
        //    serta fallback otomatis ke generateContent dengan speech_metadata / verbatim text).
        if (isGemini38OrNewer) {
            try {
                return requestGemini38InteractionsEndpoint(geminiKey, v1, v2, textChunk, pureVerbatimText, cleanStyleMeta, supportsAngleTags);
            } catch (Exception interactionsErr) {
                try {
                    return requestGenerateContentEndpoint(geminiKey, v1, v2, textChunk, pureVerbatimText, cleanStyleMeta, true, supportsAngleTags);
                } catch (Exception genMetaErr) {
                    return requestGenerateContentEndpoint(geminiKey, v1, v2, textChunk, pureVerbatimText, "", false, supportsAngleTags);
                }
            }
        }

        // 2. Untuk model Gemini 2.5 / 3.1 Preview TTS (gemini-2.5-flash-preview-tts, gemini-2.5-pro-preview-tts, gemini-3.1-flash-tts-preview):
        //    Kirim ke generateContent dengan naskah murni (TANPA mencampur instruksi custom ke dalam field text agar instruksi tidak pernah ikut terbaca!).
        return requestGenerateContentEndpoint(geminiKey, v1, v2, textChunk, pureVerbatimText, cleanStyleMeta, false, supportsAngleTags);
    }

    private byte[] requestGemini38InteractionsEndpoint(
            String geminiKey, String v1, String v2,
            String rawChunk, String pureVerbatimText, String cleanStyleMeta, boolean allowAngleTags
    ) throws Exception {
        String urlStr = "https://generativelanguage.googleapis.com/v1beta/interactions?key=" + java.net.URLEncoder.encode(geminiKey, "UTF-8");
        JSONArray contentArr = new JSONArray();

        if (multiSpeakerMode) {
            List<DialogueTurn> turns = parseMultiSpeakerTurns(rawChunk, allowAngleTags);
            for (DialogueTurn turn : turns) {
                JSONObject item = new JSONObject();
                item.put("type", "text");
                item.put("text", turn.text);
                JSONObject meta = new JSONObject();
                meta.put("type", "speech_metadata");
                meta.put("speaker", turn.speaker);
                if (!cleanStyleMeta.isEmpty()) meta.put("style", cleanStyleMeta);
                item.put("annotations", new JSONArray().put(meta));
                contentArr.put(item);
            }
        } else {
            JSONObject item = new JSONObject();
            item.put("type", "text");
            item.put("text", pureVerbatimText);
            if (!cleanStyleMeta.isEmpty()) {
                JSONObject meta = new JSONObject();
                meta.put("type", "speech_metadata");
                meta.put("style", cleanStyleMeta);
                item.put("annotations", new JSONArray().put(meta));
            }
            contentArr.put(item);
        }

        JSONObject genConfig = new JSONObject();
        if (multiSpeakerMode) {
            JSONArray speakers = new JSONArray();
            speakers.put(new JSONObject().put("speaker", speaker1Name).put("voice", v1));
            speakers.put(new JSONObject().put("speaker", speaker2Name).put("voice", v2));
            genConfig.put("speech_config", new JSONObject().put("speakers", speakers));
        } else {
            genConfig.put("speech_config", new JSONArray().put(new JSONObject().put("voice", v1)));
        }

        JSONObject req = new JSONObject();
        req.put("model", selectedModel);
        req.put("input", new JSONArray().put(new JSONObject()
                .put("type", "user_input")
                .put("content", contentArr)));
        req.put("response_format", new JSONObject()
                .put("type", "audio")
                .put("mime_type", "audio/l16")
                .put("sample_rate", SAMPLE_RATE));
        req.put("generation_config", genConfig);

        String raw = httpPostGeminiJson(urlStr, geminiKey, req.toString());
        return extractAudioPcmFromGeminiResponse(raw);
    }

    private byte[] requestGenerateContentEndpoint(
            String geminiKey, String v1, String v2,
            String rawChunk, String pureVerbatimText, String cleanStyleMeta,
            boolean attachSpeechMetadataOnParts, boolean allowAngleTags
    ) throws Exception {
        String urlStr = "https://generativelanguage.googleapis.com/v1beta/models/"
                + selectedModel + ":generateContent?key=" + java.net.URLEncoder.encode(geminiKey, "UTF-8");

        JSONArray partsArr = new JSONArray();
        if (multiSpeakerMode && attachSpeechMetadataOnParts) {
            List<DialogueTurn> turns = parseMultiSpeakerTurns(rawChunk, allowAngleTags);
            for (DialogueTurn turn : turns) {
                JSONObject part = new JSONObject();
                part.put("text", turn.text);
                JSONObject meta = new JSONObject();
                meta.put("speaker", turn.speaker);
                if (!cleanStyleMeta.isEmpty()) meta.put("style", cleanStyleMeta);
                part.put("speech_metadata", meta);
                partsArr.put(part);
            }
        } else if (multiSpeakerMode) {
            // Untuk model 2.5/3.1 multi-speaker: hanya kirim dialog bersih (Pembicara1: ... \n Pembicara2: ...) tanpa instruksi custom
            List<DialogueTurn> turns = parseMultiSpeakerTurns(rawChunk, allowAngleTags);
            StringBuilder dialogOnly = new StringBuilder();
            for (DialogueTurn turn : turns) {
                if (dialogOnly.length() > 0) dialogOnly.append("\n");
                dialogOnly.append(turn.speaker).append(": ").append(turn.text);
            }
            partsArr.put(new JSONObject().put("text", dialogOnly.toString()));
        } else {
            JSONObject part = new JSONObject();
            part.put("text", pureVerbatimText);
            if (attachSpeechMetadataOnParts && !cleanStyleMeta.isEmpty()) {
                part.put("speech_metadata", new JSONObject().put("style", cleanStyleMeta));
            }
            partsArr.put(part);
        }

        JSONObject speechConfig = new JSONObject();
        if (multiSpeakerMode) {
            JSONArray spArr = new JSONArray();
            spArr.put(new JSONObject().put("speaker", speaker1Name)
                    .put("voiceConfig", new JSONObject().put("prebuiltVoiceConfig",
                            new JSONObject().put("voiceName", v1))));
            spArr.put(new JSONObject().put("speaker", speaker2Name)
                    .put("voiceConfig", new JSONObject().put("prebuiltVoiceConfig",
                            new JSONObject().put("voiceName", v2))));
            speechConfig.put("multiSpeakerVoiceConfig", new JSONObject().put("speakerVoiceConfigs", spArr));
        } else {
            speechConfig.put("voiceConfig", new JSONObject().put("prebuiltVoiceConfig",
                    new JSONObject().put("voiceName", v1)));
        }

        JSONObject genConfig = new JSONObject();
        genConfig.put("responseModalities", new JSONArray().put("AUDIO"));
        genConfig.put("speechConfig", speechConfig);

        JSONObject req = new JSONObject();
        req.put("contents", new JSONArray().put(new JSONObject()
                .put("role", "user")
                .put("parts", partsArr)));
        req.put("generationConfig", genConfig);

        String raw = httpPostGeminiJson(urlStr, geminiKey, req.toString());
        return extractAudioPcmFromGeminiResponse(raw);
    }

    private static byte[] extractAudioPcmFromGeminiResponse(String rawJson) throws Exception {
        JSONObject resp = new JSONObject(rawJson);

        // 1. Cek skema Interactions API (steps[].content[].data atau output_audio.data)
        JSONObject outAudio = resp.optJSONObject("output_audio");
        if (outAudio == null) outAudio = resp.optJSONObject("outputAudio");
        if (outAudio != null) {
            String b64 = outAudio.optString("data", "");
            if (!b64.isEmpty()) return stripWavHeaderIfPresent(Base64.decode(b64, Base64.DEFAULT));
        }
        JSONArray steps = resp.optJSONArray("steps");
        if (steps != null) {
            ByteArrayOutputStream stepPcm = new ByteArrayOutputStream();
            for (int i = 0; i < steps.length(); i++) {
                JSONObject st = steps.optJSONObject(i);
                if (st == null) continue;
                JSONArray content = st.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject c = content.optJSONObject(j);
                    if (c == null) continue;
                    if ("audio".equalsIgnoreCase(c.optString("type", "")) || c.has("data")) {
                        String b64 = c.optString("data", "");
                        if (!b64.isEmpty()) {
                            stepPcm.write(stripWavHeaderIfPresent(Base64.decode(b64, Base64.DEFAULT)));
                        }
                    }
                }
            }
            if (stepPcm.size() > 0) return stepPcm.toByteArray();
        }

        // 2. Cek skema GenerateContent API (candidates[0].content.parts[].inlineData.data)
        JSONArray candidates = resp.optJSONArray("candidates");
        if (candidates != null && candidates.length() > 0) {
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            JSONArray parts = content != null ? content.optJSONArray("parts") : null;
            if (parts != null) {
                ByteArrayOutputStream partPcm = new ByteArrayOutputStream();
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.getJSONObject(i);
                    JSONObject inline = part.optJSONObject("inlineData");
                    if (inline == null) inline = part.optJSONObject("inline_data");
                    if (inline != null) {
                        String b64 = inline.optString("data", "");
                        if (!b64.isEmpty()) {
                            partPcm.write(stripWavHeaderIfPresent(Base64.decode(b64, Base64.DEFAULT)));
                        }
                    }
                }
                if (partPcm.size() > 0) return partPcm.toByteArray();
            }
        }
        throw new IllegalStateException("Respons Gemini tidak berisi data audio.");
    }

    /**
     * Memotong header WAV RIFF secara presisi dengan memindai posisi chunk 'data'
     * (mendukung baik header 44-byte standar maupun WAV dari Gemini 3.8 TTS yang memiliki subchunk tambahan).
     */
    private static byte[] stripWavHeaderIfPresent(byte[] decoded) {
        if (decoded == null || decoded.length <= 44) return decoded;
        if (decoded[0] == 'R' && decoded[1] == 'I' && decoded[2] == 'F' && decoded[3] == 'F'
                && decoded[8] == 'W' && decoded[9] == 'A' && decoded[10] == 'V' && decoded[11] == 'E') {
            int offset = 12;
            while (offset + 8 <= decoded.length) {
                boolean isData = decoded[offset] == 'd' && decoded[offset + 1] == 'a'
                        && decoded[offset + 2] == 't' && decoded[offset + 3] == 'a';
                int chunkSize = (decoded[offset + 4] & 0xff)
                        | ((decoded[offset + 5] & 0xff) << 8)
                        | ((decoded[offset + 6] & 0xff) << 16)
                        | ((decoded[offset + 7] & 0xff) << 24);
                int dataStart = offset + 8;
                if (isData && dataStart <= decoded.length) {
                    int actualLen = chunkSize > 0 && dataStart + chunkSize <= decoded.length
                            ? chunkSize : (decoded.length - dataStart);
                    byte[] pcmOnly = new byte[actualLen];
                    System.arraycopy(decoded, dataStart, pcmOnly, 0, actualLen);
                    return pcmOnly;
                }
                if (chunkSize < 0 || dataStart + chunkSize > decoded.length) break;
                offset = dataStart + chunkSize + (chunkSize % 2);
            }
            byte[] pcmOnly = new byte[decoded.length - 44];
            System.arraycopy(decoded, 44, pcmOnly, 0, pcmOnly.length);
            return pcmOnly;
        }
        return decoded;
    }

    private static List<String> splitIntoSafeChunks(String text, int maxLen) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String clean = text.trim();
        if (clean.isEmpty()) return out;
        int pos = 0;
        while (pos < clean.length()) {
            int end = Math.min(clean.length(), pos + maxLen);
            if (end < clean.length()) {
                int nl = clean.lastIndexOf('\n', end);
                int dot = clean.lastIndexOf('.', end);
                int cut = Math.max(nl, dot);
                if (cut > pos + maxLen / 3) end = cut + 1;
            }
            String sub = clean.substring(pos, end).trim();
            if (!sub.isEmpty()) out.add(sub);
            pos = end;
        }
        return out;
    }

    private static void writeWavFile(File file, byte[] pcmData, int sampleRate, int channels, int bitsPerSample) throws Exception {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int totalDataLen = pcmData.length + 36;
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * bitsPerSample / 8); header[33] = 0;
        header[34] = (byte) bitsPerSample; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (pcmData.length & 0xff);
        header[41] = (byte) ((pcmData.length >> 8) & 0xff);
        header[42] = (byte) ((pcmData.length >> 16) & 0xff);
        header[43] = (byte) ((pcmData.length >> 24) & 0xff);

        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(header);
            fos.write(pcmData);
        }
    }

    private void togglePlay() {
        if (generatedWavFile == null || !generatedWavFile.exists()) return;
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                btnPlay.setText("Lanjut Putar");
                return;
            }
            if (mediaPlayer != null) {
                mediaPlayer.start();
                btnPlay.setText("Jeda");
                return;
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(generatedWavFile.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> {
                btnPlay.setText("Putar Ulang");
                try { mp.seekTo(0); } catch (Throwable ignored) {}
            });
            mediaPlayer.start();
            btnPlay.setText("Jeda");
        } catch (Exception e) {
            Toast.makeText(this, "Gagal memutar audio: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveWavAs() {
        if (generatedWavFile == null || !generatedWavFile.exists()) return;
        OfficeUtils.launchFileSaver(this, REQ_SAVE_WAV, generatedWavFile.getName(), "Simpan Audio Gemini TTS", "Gemini TTS/Gemini Audio");
    }

    private void saveInputTextAs() {
        String txtVal = etInputText != null && etInputText.getText() != null ? etInputText.getText().toString() : "";
        if (txtVal.trim().isEmpty()) {
            Toast.makeText(this, "Naskah masih kosong.", Toast.LENGTH_SHORT).show();
            return;
        }
        OfficeUtils.launchFileSaver(this, REQ_SAVE_TXT, "Naskah_GeminiTTS_" + System.currentTimeMillis() + ".txt",
                "Simpan Naskah Gemini TTS", "Gemini TTS/Gemini Teks");
    }

    private void shareWav() {
        if (generatedWavFile == null || !generatedWavFile.exists()) return;
        OfficeUtils.shareLocalFile(this, generatedWavFile, generatedWavFile.getName());
    }

    // =========================================================================
    // MESIN KONVERSI VIDEO STATUS WA + MUSIK LATAR + ANTREAN URUT 1 -> N
    // =========================================================================

    private void openExportVideoDialog(final boolean forWhatsAppStatus) {
        if (generatedWavFile == null || !generatedWavFile.exists()) {
            Toast.makeText(this, "Buat audio WAV Gemini TTS terlebih dahulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        final double wavSec = AudioToVideoConverter.getWavDurationSeconds(generatedWavFile);
        final int totalSecInt = Math.max(1, (int) Math.ceil(wavSec));
        final boolean needSplitConfirm = forWhatsAppStatus && totalSecInt > 60;

        int p = UiKit.dp(this, 14);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, p, p, p);

        TextView tvDur = new TextView(this);
        tvDur.setText("Deteksi Durasi Audio Suara (TTS): "
                + AudioToVideoConverter.formatDuration(wavSec) + " (" + totalSecInt + " detik)"
                + (forWhatsAppStatus
                        ? (needSplitConfirm
                                ? "\nAudio lebih dari 1 menit — pilih durasi pemotongan per Status WA (kelipatan 30 detik)."
                                : "\nDurasi <= 1 menit: langsung dijadikan 1 video Status WA.")
                        : "\nMode Ekspor Video Utuh: seluruh audio dijadikan 1 video MP4 penuh tanpa dipotong di folder Gemini Video."));
        tvDur.setTextColor(UiKit.textPrimary(prefs));
        tvDur.setTextSize(14);
        tvDur.setPadding(0, 0, 0, UiKit.dp(this, 10));
        box.addView(tvDur);

        // Pemilih durasi potong (kelipatan 30 detik) hanya untuk Status WA jika > 60 detik
        final int[] chosenSplitSec = {forWhatsAppStatus ? (needSplitConfirm ? 60 : Math.max(30, totalSecInt)) : 0};
        if (needSplitConfirm) {
            final Button btnSplitDur = new Button(this);
            int estParts = (int) Math.ceil((double) totalSecInt / chosenSplitSec[0]);
            btnSplitDur.setText("Durasi per Video SW: " + chosenSplitSec[0] + " detik (" + estParts + " bagian urut)");
            UiKit.styleButton(this, btnSplitDur, UiKit.ACCENT_AI);
            btnSplitDur.setOnClickListener(v -> {
                List<String> labels = new ArrayList<>();
                final List<Integer> values = new ArrayList<>();
                int maxStep = Math.max(60, ((totalSecInt + 29) / 30) * 30);
                maxStep = Math.min(maxStep, 300);
                for (int sec = 30; sec <= maxStep; sec += 30) {
                    int parts = (int) Math.ceil((double) totalSecInt / sec);
                    labels.add(sec + " detik (" + (sec >= 60 ? (sec / 60.0 == (int)(sec / 60) ? (sec / 60) + " menit" : sec + " dtk") : sec + " dtk") + ") -> " + parts + " video urut");
                    values.add(sec);
                }
                UiKit.dialog(this, prefs)
                        .setTitle("Pilih Potongan Kelipatan 30 Detik")
                        .setItems(labels.toArray(new String[0]), (d2, w2) -> {
                            chosenSplitSec[0] = values.get(w2);
                            int parts = (int) Math.ceil((double) totalSecInt / chosenSplitSec[0]);
                            btnSplitDur.setText("Durasi per Video SW: " + chosenSplitSec[0] + " detik (" + parts + " bagian urut)");
                        }).show();
            });
            box.addView(btnSplitDur);
        }

        // Bagian Musik Latar
        TextView tvMusicTitle = UiKit.makeHeader(this, "MUSIK LATAR (OPSIONAL)", UiKit.ACCENT_KB_ALT, prefs);
        box.addView(tvMusicTitle);

        activeWaMusicInfoLabel = new TextView(this);
        activeWaMusicInfoLabel.setTextColor(UiKit.textMuted(prefs));
        activeWaMusicInfoLabel.setTextSize(13);
        updateWaMusicInfoLabel(wavSec);
        box.addView(activeWaMusicInfoLabel);

        LinearLayout musicBtnRow = new LinearLayout(this);
        musicBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnPickMusic = OfficeUtils.makeRowButton(this, "Pilih Backsound (Aplikasi / HP)",
                "Pilih musik latar dari koleksi kategori aplikasi atau memori HP sendiri", UiKit.ACCENT_KB, Color.WHITE,
                v -> StudioGudangAudioManager.showUniversalAudioSourceChooser(
                        this, prefs, "Pilih Backsound Musik Latar Video", true, false,
                        (uri, title, durSec, isBgm) -> {
                            bgMusicUri = uri;
                            bgMusicName = title;
                            bgMusicDurationSec = durSec > 0 ? durSec : AudioToVideoConverter.getMediaDurationSeconds(this, uri);
                            bgMusicStartSec = 0;
                            updateWaMusicInfoLabel(wavSec);
                            OfficeUtils.announce(activeWaMusicInfoLabel, "Backsound aplikasi dipilih: " + bgMusicName);
                        },
                        () -> {
                            pickMusicForWavMix = false;
                            OfficeUtils.launchFilePicker(GeminiTtsActivity.this, REQ_PICK_MUSIC, "audio", "Pilih Musik Latar Video");
                        },
                        null));
        Button btnClearMusic = OfficeUtils.makeRowButton(this, "Tanpa Musik",
                "Hapus musik latar", UiKit.ACCENT_SYSTEM, Color.WHITE,
                v -> {
                    bgMusicUri = null;
                    bgMusicName = "";
                    bgMusicDurationSec = 0;
                    bgMusicStartSec = 0;
                    updateWaMusicInfoLabel(wavSec);
                });
        musicBtnRow.addView(btnPickMusic);
        musicBtnRow.addView(btnClearMusic);
        box.addView(musicBtnRow);

        TextView tvStartLbl = new TextView(this);
        tvStartLbl.setText("Detik awal mulai musik (mis. 0 = dari awal, 30 = mulai detik ke-30):");
        tvStartLbl.setTextColor(UiKit.textMuted(prefs));
        tvStartLbl.setTextSize(12);
        tvStartLbl.setPadding(0, UiKit.dp(this, 8), 0, 0);
        box.addView(tvStartLbl);

        final EditText etMusicStart = new EditText(this);
        etMusicStart.setInputType(InputType.TYPE_CLASS_NUMBER);
        etMusicStart.setText(String.valueOf(bgMusicStartSec));
        etMusicStart.setHint("Detik awal (0)");
        UiKit.styleEditText(this, etMusicStart, prefs);
        box.addView(etMusicStart);

        final TextView tvVolLbl = new TextView(this);
        tvVolLbl.setText("Volume Musik Latar: " + bgMusicVolumePct + "% (suara TTS tetap utama)");
        tvVolLbl.setTextColor(UiKit.textPrimary(prefs));
        tvVolLbl.setTextSize(13);
        box.addView(tvVolLbl);

        final android.widget.SeekBar sbVol = new android.widget.SeekBar(this);
        sbVol.setMax(50);
        sbVol.setProgress(bgMusicVolumePct);
        sbVol.setContentDescription("Volume musik latar, saat ini " + bgMusicVolumePct + " persen");
        sbVol.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                bgMusicVolumePct = Math.max(5, progress);
                tvVolLbl.setText("Volume Musik Latar: " + bgMusicVolumePct + "% (suara TTS tetap utama)");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        box.addView(sbVol);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);

        UiKit.dialog(this, prefs)
                .setTitle(forWhatsAppStatus ? "Konversi ke Video Status WhatsApp" : "Ekspor ke Video MP4 (Utuh)")
                .setView(sc)
                .setNegativeButton("Batal", null)
                .setPositiveButton(forWhatsAppStatus ? "Buat Video Status WA" : "Ekspor Video Utuh", (d, w) -> {
                    int startSec = 0;
                    try { startSec = Math.max(0, Integer.parseInt(etMusicStart.getText().toString().trim())); }
                    catch (Exception ignored) {}
                    if (bgMusicDurationSec > 1 && startSec >= bgMusicDurationSec) {
                        startSec = 0;
                        Toast.makeText(this, "Detik awal melebihi durasi lagu, otomatis mulai dari 0.", Toast.LENGTH_SHORT).show();
                    }
                    bgMusicStartSec = startSec;
                    if (forWhatsAppStatus) {
                        renderAndQueueStatusVideos(chosenSplitSec[0]);
                    } else {
                        renderAndExportFullVideo();
                    }
                })
                .show();
    }

    private void renderAndExportFullVideo() {
        final boolean[] cancelled = {false};
        final AlertDialog prog = UiKit.dialog(this, prefs)
                .setTitle("Mengekspor Video MP4 Utuh...")
                .setMessage("Menyiapkan encoder video & audio...")
                .setCancelable(false)
                .setNegativeButton("Batal", (d, w) -> cancelled[0] = true)
                .create();
        prog.setCanceledOnTouchOutside(false);
        prog.show();

        final String previewText = etInputText != null && etInputText.getText() != null
                ? etInputText.getText().toString() : "";
        final Uri musicTarget = bgMusicUri;
        final int musicOffset = bgMusicStartSec;
        final float musicVol = bgMusicVolumePct / 100f;

        new Thread(() -> {
            try {
                OfficeUtils.clearDirectoryContents(new File(getCacheDir(), "wa_status_videos"));
                OfficeUtils.clearDirectoryContents(new File(getCacheDir(), "shared_exports"));
                File outDir = OfficeUtils.resolveBackupSubfolder(this, "Gemini TTS/Gemini Video");
                final File mp4File = AudioToVideoConverter.convertWavToFullVideo(
                        this, generatedWavFile, outDir, previewText,
                        musicTarget, musicOffset, musicVol,
                        new AudioToVideoConverter.ProgressListener() {
                            @Override public void onProgress(int partIndex, int totalParts, int percentInPart, String status) {
                                final int pct = Math.max(1, Math.min(100, percentInPart));
                                mainHandler.post(() -> {
                                    if (UiKit.isActivityAlive(GeminiTtsActivity.this)) {
                                        updateProgressStatus(pct, "Progres Video Utuh " + pct + "%: " + status, true);
                                        if (prog.isShowing()) prog.setMessage("Progres " + pct + "%\n" + status);
                                    }
                                });
                            }
                            @Override public boolean isCancelled() {
                                return cancelled[0];
                            }
                        });

                mainHandler.post(() -> {
                    try { if (prog.isShowing()) prog.dismiss(); } catch (Throwable ignored) {}
                    if (cancelled[0] || !UiKit.isActivityAlive(this)) {
                        updateProgressStatus(0, "Ekspor video dibatalkan.", false);
                        return;
                    }
                    updateProgressStatus(100, "Ekspor Video Utuh 100% -> " + mp4File.getAbsolutePath(), false);
                    OfficeUtils.announce(statusLabel, "Video MP4 utuh selesai disimpan ke folder Gemini Video.");
                    UiKit.dialog(this, prefs)
                            .setTitle("Video MP4 Utuh Selesai!")
                            .setMessage("Video berhasil disimpan ke:\n" + mp4File.getAbsolutePath()
                                    + "\n\nUkuran: " + Math.max(1, mp4File.length() / 1024) + " KB")
                            .setNeutralButton("Bagikan Video", (d, w) -> {
                                try {
                                    File exportDir = new File(getCacheDir(), "shared_exports");
                                    if (!exportDir.exists()) exportDir.mkdirs();
                                    File staged = new File(exportDir, mp4File.getName());
                                    try (InputStream in = new FileInputStream(mp4File);
                                         OutputStream out = new FileOutputStream(staged, false)) {
                                        byte[] buf = new byte[8192];
                                        int n;
                                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                                    }
                                    Uri uri = Uri.parse("content://" + ExportFileProvider.AUTHORITY + "/" + Uri.encode(mp4File.getName()));
                                    Intent send = new Intent(Intent.ACTION_SEND);
                                    send.setType("video/mp4");
                                    send.putExtra(Intent.EXTRA_STREAM, uri);
                                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    startActivity(Intent.createChooser(send, "Bagikan " + mp4File.getName()));
                                } catch (Exception ex) {
                                    Toast.makeText(this, "Gagal bagikan: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setPositiveButton("Putar Video", (d, w) -> {
                                try {
                                    File exportDir = new File(getCacheDir(), "shared_exports");
                                    if (!exportDir.exists()) exportDir.mkdirs();
                                    File staged = new File(exportDir, mp4File.getName());
                                    try (InputStream in = new FileInputStream(mp4File);
                                         OutputStream out = new FileOutputStream(staged, false)) {
                                        byte[] buf = new byte[8192];
                                        int n;
                                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                                    }
                                    Uri uri = Uri.parse("content://" + ExportFileProvider.AUTHORITY + "/" + Uri.encode(mp4File.getName()));
                                    Intent view = new Intent(Intent.ACTION_VIEW);
                                    view.setDataAndType(uri, "video/mp4");
                                    view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    startActivity(view);
                                } catch (Exception ex) {
                                    Toast.makeText(this, "Tidak ada pemutar video atau error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            })
                            .show();
                });
            } catch (final Exception e) {
                mainHandler.post(() -> {
                    try { if (prog.isShowing()) prog.dismiss(); } catch (Throwable ignored) {}
                    if (!cancelled[0] && UiKit.isActivityAlive(this)) {
                        updateProgressStatus(0, "Gagal ekspor video: " + e.getMessage(), false);
                        UiKit.dialog(this, prefs)
                                .setTitle("Gagal Ekspor Video")
                                .setMessage("Error: " + e.getMessage())
                                .show();
                    }
                });
            }
        }).start();
    }

    private void updateWaMusicInfoLabel(double ttsSec) {
        if (activeWaMusicInfoLabel == null) return;
        if (bgMusicUri == null) {
            activeWaMusicInfoLabel.setText("Belum ada musik dipilih (hanya suara Gemini TTS).");
        } else {
            String durM = bgMusicDurationSec > 0 ? AudioToVideoConverter.formatDuration(bgMusicDurationSec) : "?";
            String durT = AudioToVideoConverter.formatDuration(ttsSec);
            activeWaMusicInfoLabel.setText("Lagu: " + bgMusicName + "\nDurasi lagu: " + durM
                    + " -> otomatis diselaraskan ke durasi suara TTS (" + durT + ").");
        }
    }

    private void renderAndQueueStatusVideos(final int splitSec) {
        final boolean[] cancelled = {false};
        final AlertDialog prog = UiKit.dialog(this, prefs)
                .setTitle("Membuat Video Status WA...")
                .setMessage("Menyiapkan encoder video & audio...")
                .setCancelable(false)
                .setNegativeButton("Batal", (d, w) -> cancelled[0] = true)
                .create();
        prog.setCanceledOnTouchOutside(false);
        prog.show();

        final String previewText = etInputText != null && etInputText.getText() != null
                ? etInputText.getText().toString() : "";
        final Uri musicTarget = bgMusicUri;
        final int musicOffset = bgMusicStartSec;
        final float musicVol = bgMusicVolumePct / 100f;

        new Thread(() -> {
            try {
                // Hapus cache lama pembuatan video WA agar ruang penyimpanan bersih
                OfficeUtils.clearDirectoryContents(new File(getCacheDir(), "wa_status_videos"));
                OfficeUtils.clearDirectoryContents(new File(getCacheDir(), "shared_exports"));
                File outDir = OfficeUtils.resolveBackupSubfolder(this, "Gemini TTS/Gemini Status WA");
                List<File> videos = AudioToVideoConverter.convertWavToStatusVideos(
                        this, generatedWavFile, outDir, previewText,
                        splitSec, musicTarget, musicOffset, musicVol,
                        new AudioToVideoConverter.ProgressListener() {
                            @Override public void onProgress(int partIndex, int totalParts, int percentInPart, String status) {
                                final int overallPct = Math.max(1, Math.min(100,
                                        Math.round(((partIndex - 1) * 100f + percentInPart) / Math.max(1, totalParts))));
                                mainHandler.post(() -> {
                                    if (UiKit.isActivityAlive(GeminiTtsActivity.this)) {
                                        updateProgressStatus(overallPct, "Progres Video " + overallPct + "% (" + partIndex + "/" + totalParts + "): " + status, true);
                                        if (prog.isShowing()) {
                                            prog.setMessage("Progres " + overallPct + "%\n" + status);
                                        }
                                    }
                                });
                            }
                            @Override public boolean isCancelled() {
                                return cancelled[0];
                            }
                        });

                mainHandler.post(() -> {
                    try { if (prog.isShowing()) prog.dismiss(); } catch (Throwable ignored) {}
                    if (cancelled[0] || !UiKit.isActivityAlive(this)) {
                        updateProgressStatus(0, "Konversi video Status WA dibatalkan.", false);
                        return;
                    }
                    if (videos.isEmpty()) {
                        updateProgressStatus(0, "Tidak ada video yang dihasilkan.", false);
                        Toast.makeText(this, "Tidak ada video yang dihasilkan.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    pendingWaVideos.clear();
                    pendingWaVideos.addAll(videos);
                    pendingWaIndex = 0;
                    waSendInFlight = false;
                    updateProgressStatus(100, "Video Status WA selesai 100% (" + videos.size() + " bagian siap kirim).", false);
                    OfficeUtils.announce(statusLabel, "Selesai 100 persen membuat " + videos.size() + " video Status WhatsApp.");
                    showWaSequentialQueueDialog();
                });
            } catch (final Exception e) {
                mainHandler.post(() -> {
                    try { if (prog.isShowing()) prog.dismiss(); } catch (Throwable ignored) {}
                    if (!cancelled[0] && UiKit.isActivityAlive(this)) {
                        UiKit.dialog(this, prefs)
                                .setTitle("Gagal Konversi Video")
                                .setMessage("Error: " + e.getMessage())
                                .show();
                    }
                });
            }
        }).start();
    }

    private void showWaSequentialQueueDialog() {
        if (!UiKit.isActivityAlive(this) || pendingWaVideos.isEmpty()) return;
        int total = pendingWaVideos.size();
        String saveFolder = pendingWaVideos.get(0).getParent() != null ? pendingWaVideos.get(0).getParent() : "";
        StringBuilder sb = new StringBuilder();
        sb.append("Tersimpan di: ").append(saveFolder).append("\n\n");
        sb.append("Antrean Upload Berurutan (Dijamin Urut 1 -> ").append(total).append("):\n\n");
        for (int i = 0; i < total; i++) {
            File vf = pendingWaVideos.get(i);
            String state = (i < pendingWaIndex) ? "[Terkirim]"
                    : (i == pendingWaIndex ? "[SIAP KIRIM SEKARANG]" : "[Menunggu]");
            sb.append(i + 1).append(". ").append(vf.getName()).append(" ").append(state).append("\n");
        }
        if (pendingWaIndex >= total) {
            sb.append("\nSemua ").append(total).append(" bagian video sudah dikirim berurutan!");
        } else {
            sb.append("\nKirim Bagian ").append(pendingWaIndex + 1).append(" dulu ke Status WhatsApp. Saat kembali ke sini, Bagian berikutnya langsung disiapkan otomatis.");
        }

        AlertDialog.Builder b = UiKit.dialog(this, prefs)
                .setTitle("Status WA (" + Math.min(pendingWaIndex + 1, total) + "/" + total + ")")
                .setMessage(sb.toString())
                .setNeutralButton("Simpan Semua ke Folder", (d, w) ->
                        OfficeUtils.launchFolderPicker(this, REQ_SAVE_VIDEO_DIR, "Pilih Folder Simpan Video Status WA"));

        if (pendingWaIndex < total) {
            b.setPositiveButton("Kirim Bagian " + (pendingWaIndex + 1) + " ke WhatsApp", (d, w) -> {
                waSendInFlight = true;
                sendSingleVideoToWhatsApp(pendingWaVideos.get(pendingWaIndex));
            });
            b.setNegativeButton("Batal Antrean", (d, w) -> {
                pendingWaVideos.clear();
                pendingWaIndex = 0;
                waSendInFlight = false;
            });
        }
        b.show();
    }

    private void sendSingleVideoToWhatsApp(File videoFile) {
        try {
            File exportDir = new File(getCacheDir(), "shared_exports");
            if (!exportDir.exists()) exportDir.mkdirs();
            String safeName = videoFile.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
            File staged = new File(exportDir, safeName);
            try (java.io.InputStream in = new FileInputStream(videoFile);
                 java.io.OutputStream out = new FileOutputStream(staged, false)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            Uri uri = Uri.parse("content://" + ExportFileProvider.AUTHORITY + "/" + Uri.encode(safeName));
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("video/mp4");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // Prioritaskan WhatsApp langsung jika terpasang agar cepat ke Status WA
            if (isPackageInstalled("com.whatsapp")) {
                send.setPackage("com.whatsapp");
                startActivity(send);
            } else if (isPackageInstalled("com.whatsapp.w4b")) {
                send.setPackage("com.whatsapp.w4b");
                startActivity(send);
            } else {
                startActivity(Intent.createChooser(send, "Bagikan " + safeName + " ke Status WA"));
            }
        } catch (Exception e) {
            waSendInFlight = false;
            Toast.makeText(this, "Gagal membuka WhatsApp: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waSendInFlight) {
            waSendInFlight = false;
            pendingWaIndex++;
            if (pendingWaIndex < pendingWaVideos.size()) {
                mainHandler.postDelayed(this::showWaSequentialQueueDialog, 250L);
            } else if (!pendingWaVideos.isEmpty()) {
                Toast.makeText(this, "Semua " + pendingWaVideos.size() + " bagian Status WA selesai dikirim urut!", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_SAVE_VIDEO_DIR) {
            String folderPath = data.getStringExtra("selected_folder");
            if (folderPath != null && !folderPath.isEmpty() && !pendingWaVideos.isEmpty()) {
                File dir = new File(folderPath);
                int copied = 0;
                for (File src : pendingWaVideos) {
                    File dst = new File(dir, src.getName());
                    try (InputStream in = new FileInputStream(src);
                         OutputStream out = new FileOutputStream(dst, false)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        copied++;
                    } catch (Exception ignored) {}
                }
                Toast.makeText(this, "Tersimpan " + copied + " video ke " + dir.getName(), Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_TXT) {
            try (InputStream in = OfficeUtils.openRead(this, uri)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1 && bos.size() < 200_000) bos.write(buf, 0, n);
                etInputText.setText(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                Toast.makeText(this, "Teks diimpor.", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Gagal impor: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_SAVE_TXT) {
            try (OutputStream out = OfficeUtils.openWrite(this, uri)) {
                String txtVal = etInputText != null && etInputText.getText() != null ? etInputText.getText().toString() : "";
                out.write(txtVal.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "Naskah .txt disimpan ke folder Gemini Teks!", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Gagal simpan naskah: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_SAVE_WAV && generatedWavFile != null) {
            try (InputStream in = new FileInputStream(generatedWavFile);
                 OutputStream out = OfficeUtils.openWrite(this, uri)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                Toast.makeText(this, "Audio berhasil disimpan.", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Gagal simpan audio: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_PICK_MUSIC) {
            bgMusicUri = uri;
            String selName = data.getStringExtra("selected_name");
            bgMusicName = (selName != null && !selName.isEmpty()) ? selName : (uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "Musik");
            bgMusicDurationSec = AudioToVideoConverter.getMediaDurationSeconds(this, uri);
            bgMusicStartSec = 0;
            double ttsSec = generatedWavFile != null ? AudioToVideoConverter.getWavDurationSeconds(generatedWavFile) : 0;
            updateWaMusicInfoLabel(ttsSec);
            OfficeUtils.announce(activeWaMusicInfoLabel, "Musik dipilih: " + bgMusicName + ", durasi " + AudioToVideoConverter.formatDuration(bgMusicDurationSec));
            if (pickMusicForWavMix) {
                pickMusicForWavMix = false;
                openMixBacksoundToWavDialog();
            }
        }
    }

    private void openMixBacksoundToWavDialog() {
        if (generatedWavFile == null || !generatedWavFile.exists()) {
            Toast.makeText(this, "Buat audio WAV Gemini TTS terlebih dahulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        final double wavSec = AudioToVideoConverter.getWavDurationSeconds(generatedWavFile);
        int p = UiKit.dp(this, 14);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, p, p, p);

        final TextView tvPicked = new TextView(this);
        tvPicked.setTextColor(UiKit.textPrimary(prefs));
        tvPicked.setTextSize(13);
        tvPicked.setPadding(0, 0, 0, UiKit.dp(this, 8));
        tvPicked.setText(bgMusicUri == null
                ? "Belum ada backsound dipilih.\nKetuk tombol di bawah untuk memilih Backsound dari Koleksi Kategori Aplikasi (Islami, Piano Santai, Jazz, Rock, Jedag-Jedug, Tegang, Sinematik, Relaksasi) atau dari Memori HP."
                : ("Backsound terpilih: " + bgMusicName + "\nDurasi Suara TTS: " + AudioToVideoConverter.formatDuration(wavSec)));
        box.addView(tvPicked);

        Button btnChooseBgm = new Button(this);
        btnChooseBgm.setText("Pilih Backsound Musik (Kategori Aplikasi / Memori HP)");
        UiKit.styleButton(this, btnChooseBgm, UiKit.ACCENT_KB);
        box.addView(btnChooseBgm);

        final TextView tvVol = new TextView(this);
        tvVol.setText("Volume Backsound Musik: " + bgMusicVolumePct + "% (Suara TTS tetap jernih)");
        tvVol.setTextColor(UiKit.textPrimary(prefs));
        tvVol.setPadding(0, UiKit.dp(this, 10), 0, UiKit.dp(this, 4));
        box.addView(tvVol);

        final android.widget.SeekBar sbVol = new android.widget.SeekBar(this);
        sbVol.setMax(60);
        sbVol.setProgress(bgMusicVolumePct);
        sbVol.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                bgMusicVolumePct = Math.max(5, progress);
                tvVol.setText("Volume Backsound Musik: " + bgMusicVolumePct + "% (Suara TTS tetap jernih)");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        box.addView(sbVol);

        final CheckBox cbAutoDuck = new CheckBox(this);
        cbAutoDuck.setText("Aktifkan Auto-Ducking (Backsound otomatis pelan saat suara TTS berbicara)");
        cbAutoDuck.setTextColor(UiKit.textPrimary(prefs));
        cbAutoDuck.setChecked(true);
        cbAutoDuck.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 4));
        box.addView(cbAutoDuck);

        final AlertDialog dlg = UiKit.dialog(this, prefs)
                .setTitle("Tambah Backsound Musik ke Suara Gemini TTS")
                .setView(box)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Campur Backsound Sekarang", null)
                .create();

        btnChooseBgm.setOnClickListener(v -> StudioGudangAudioManager.showUniversalAudioSourceChooser(
                this, prefs, "Pilih Backsound Musik untuk Audio TTS", true, false,
                (uri, title, durSec, isBgm) -> {
                    bgMusicUri = uri;
                    bgMusicName = title;
                    bgMusicDurationSec = durSec > 0 ? durSec : AudioToVideoConverter.getMediaDurationSeconds(this, uri);
                    bgMusicStartSec = 0;
                    tvPicked.setText("Backsound terpilih: " + bgMusicName + "\nDurasi Suara TTS: " + AudioToVideoConverter.formatDuration(wavSec));
                },
                () -> {
                    pickMusicForWavMix = true;
                    dlg.dismiss();
                    OfficeUtils.launchFilePicker(GeminiTtsActivity.this, REQ_PICK_MUSIC, "audio", "Pilih Backsound Musik dari HP");
                },
                null));

        dlg.setOnShowListener(d -> {
            Button pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            if (pos != null) {
                pos.setOnClickListener(v -> {
                    if (bgMusicUri == null) {
                        Toast.makeText(this, "Pilih backsound musik terlebih dahulu.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dlg.dismiss();
                    mixBacksoundIntoCurrentTtsWav(bgMusicUri, bgMusicName, bgMusicVolumePct / 100f, cbAutoDuck.isChecked());
                });
            }
        });
        dlg.show();
    }

    private void mixBacksoundIntoCurrentTtsWav(final Uri bgUri, final String bgName, final float bgVol, final boolean autoDuck) {
        if (generatedWavFile == null || !generatedWavFile.exists()) return;
        releaseMediaPlayer();
        updateProgressStatus(20, "Mencampur backsound musik (" + bgName + ") ke suara TTS...", true);
        new Thread(() -> {
            try {
                List<AudioDspEngine.MixTrackSpec> tracks = new ArrayList<>();
                AudioDspEngine.MixTrackSpec tVoice = new AudioDspEngine.MixTrackSpec();
                tVoice.uri = Uri.fromFile(generatedWavFile);
                tVoice.title = "Suara Gemini TTS";
                tVoice.volume = 1.0f;
                tVoice.loop = false;
                tracks.add(tVoice);

                AudioDspEngine.MixTrackSpec tBgm = new AudioDspEngine.MixTrackSpec();
                tBgm.uri = bgUri;
                tBgm.title = bgName;
                tBgm.volume = bgVol;
                tBgm.loop = true;
                tracks.add(tBgm);

                String fmt = normalizeAudioFmt(selectedAudioFmt);
                int br = bitrateForMixed(fmt);
                int sr = "opus".equals(fmt) ? 48000 : ("wav".equals(fmt) ? SAMPLE_RATE : 44100);
                File dir = OfficeUtils.resolveBackupSubfolder(this, "Gemini TTS/Gemini Audio");
                File outMixed = new File(dir, "GeminiTTS_Backsound_" + System.currentTimeMillis() + "." + fmt);
                AudioDspEngine.mixMultiTracks(this, tracks, getCacheDir(), outMixed, autoDuck, fmt, br, sr, 2);
                generatedWavFile = outMixed;
                mainHandler.post(() -> {
                    updateProgressStatus(100, "Selesai mencampur backsound (" + bgName + ") -> " + outMixed.getName()
                            + " (" + Math.max(1L, outMixed.length() / 1024) + " KB)", false);
                    Toast.makeText(this, "Backsound berhasil dicampur ke audio TTS (" + Math.max(1L, outMixed.length() / 1024) + " KB)!", Toast.LENGTH_LONG).show();
                    togglePlay();
                });
            } catch (final Exception e) {
                mainHandler.post(() -> {
                    updateProgressStatus(0, "Gagal mencampur backsound: " + e.getMessage(), false);
                    Toast.makeText(this, "Gagal campur backsound: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        cancelRequested = true;
        OfficeUtils.clearDirectoryContents(new File(getCacheDir(), "wa_status_videos"));
        OfficeUtils.clearDirectoryContents(new File(getCacheDir(), "shared_exports"));
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); mediaPlayer.release(); } catch (Throwable ignored) {}
            mediaPlayer = null;
        }
        super.onDestroy();
    }
}
