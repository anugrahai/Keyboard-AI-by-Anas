package com.annas;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.content.ClipDescription;
import android.inputmethodservice.InputMethodService;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.os.Build;

import android.os.Bundle;

import android.os.Handler;

import android.os.Looper;




import android.os.Vibrator;

import android.speech.RecognitionListener;

import android.speech.RecognizerIntent;

import android.speech.SpeechRecognizer;
import android.util.Base64;

import android.view.KeyEvent;

import android.view.MotionEvent;

import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import android.view.ViewGroup;

import android.view.accessibility.AccessibilityEvent;

import android.view.accessibility.AccessibilityManager;

import android.view.accessibility.AccessibilityNodeInfo;

import android.view.inputmethod.EditorInfo;

import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;

import android.view.inputmethod.InputMethod;

import android.widget.Button;

import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

public class KeyboardService extends InputMethodService implements CustomKeyboardView.OnKeyboardActionListener {
    private CustomKeyboardView customKeyboardView;
    private long lastSpaceTime = 0;


    private SpeechRecognizer speechRecognizer;
    private MediaRecorder mediaRecorder;
    private MicHelper.PcmWhisperRecorder offlinePcmRecorder = null;
    private File offlinePcmFile = null;
    private boolean recordingOfflineWhisper = false;
    private boolean isRecordingWhisper = false;
    private String googleLastPartial = "";
    private boolean isRecordingGoogle = false;
    private String audioFilePath;
    // --- Mikrofon terpusat (Whisper + Google) ---
    private final Handler micHandler = new Handler(Looper.getMainLooper());
    private Runnable micTertunda = null;
    private MicHelper.PompaGoogle pompaGoogle = null;
    private boolean googlePakaiPompa = false;
    private boolean googleSudahRetry = false;
    private int googleRecognizerAttempt = 0;

    private static final String PREFS_NAME = "VoiceInput_Settings";
    private SharedPreferences prefs;


    private AccessibilityManager accessibilityManager;

    private int shiftState = 0; 

    


//     private LinearLayout layoutQwerty, layoutSym1, layoutSym2;

    private LinearLayout layoutEmoji, layoutFont;

    private LinearLayout containerGridEmoji, containerDaftarFont;

    
    private Button[] qwertyButtons;
    private Button[] micButtons = new Button[3];
    private Button[] tempelButtons = new Button[3];

    private BrailleKeyboardView brailleKeyboardView;
    private BrailleTTSManager brailleTTSManager;
    private android.widget.FrameLayout keyboardContainerLayout;
    private android.app.AlertDialog quickToolsDialog;
    private boolean isBrailleModeActive = false;
    private String lastDeletedUndoText = "";
    private final StringBuilder qwertyWordBuf = new StringBuilder();
    private long sessionCharsTyped = 0L;
    private long sessionWordsTyped = 0L;
    private long sessionMacrosUsed = 0L;
    private boolean qwertyWindowAnnouncementShown = false;
    private String lastQwertyAnnouncement = "";
    private long lastQwertyAnnouncementUptime = 0L;
    private long lastBrailleActiveAnnounceUptime = 0L;
    /**
     * Guard anti-reopen otomatis setelah user SENGAJA menutup keyboard
     * (gesture 2 jari kiri / onHideKeyboard). Hanya menahan SHOW_FORCED /
     * implicit dalam jendela singkat — setelah itu keyboard bebas muncul lagi
     * (WhatsApp Search ke-2+, Gemini, dll).
     *
     * Pola Advanced Braille: keyboard tetap tersedia selama field aktif;
     * hanya hide eksplisit user yang menahan, dan itupun sementara.
     */
    private long userHideUptimeMs = 0L;
    private static final long USER_HIDE_GUARD_MS = 900L;
    /**
     * Gemini: setelah user hide, tolak show otomatis.
     * SHOW_EXPLICIT dari TalkBack (baca kotak edit) sering datang segera —
     * debounce supaya tidak dianggap "klik user".
     */
    private boolean stickyHideForGemini = false;
    /** Setelah user hide di Gemini, tolak show otomatis lebih lama (cegah loop buka-tutup). */
    private static final long GEMINI_EXPLICIT_UNLOCK_MS = 4500L;
    /**
     * Buffer teks sesi Gemini. Field Gemini/WebView sering hanya menerima
     * commit pertama; strategi: tiap ketikan tulis ULANG seluruh buffer
     * (hapus semua lalu commitText penuh), sama seperti IME yang andal di Chrome.
     */
    private final StringBuilder geminiTypeBuffer = new StringBuilder();
    private int geminiBufferFieldKey = 0;
    /**
     * WhatsApp Search: pola terbalik dari sticky Gemini.
     * Setelah keyboard Search tampil, TAHAN state lewat restart kotak edit
     * (hasil "15 ditemukan" / "7 ditemukan" sering me-restart IME).
     * Baru lepas saat user hide (2 jari) atau keluar dari Search/WhatsApp.
     */
    private boolean stickyShowForWaSearch = false;
    private Runnable pendingWaSearchClear = null;
    /** Grace agar restart hasil pencarian WA tidak mematikan keyboard/passthrough */
    private static final long WA_SEARCH_HIDE_GRACE_MS = 600L;
    /**
     * Passthrough diaktifkan SEGERA saat window Braille tampil (semua field:
     * chat, search, PIN, password, YouTube, game). Delay lama dulu menyebabkan
     * "kadang harus tangguhkan TalkBack" — sekarang hanya debounce sangat singkat
     * agar layout/fullscreen settle, lalu passthrough ON tanpa clear dulu.
     */
    private static final long BRAILLE_PASSTHROUGH_SETTLE_MS = 30L;
    private final android.os.Handler imeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingSearchPassthrough = null;
    private int passthroughScheduleGen = 0;
    // Identitas editor terakhir (untuk log / debug, dan reset guard saat field beda)
    private String lastEditorPackage = null;
    private int lastEditorFieldId = Integer.MIN_VALUE;
    private int lastEditorInputType = Integer.MIN_VALUE;
    private int lastEditorImeOptions = Integer.MIN_VALUE;
    private String lastEditorHint = null;

    private SoundPool soundPool;
    private MediaPlayer mediaPlayerNempel;
    private int soundKetikId = -1;
    private ToneGenerator toneGen;

    private long lastModKetik = 0;

    private long lastModNempel = 0;



    private volatile Thread aiThread = null; 

    private int currentFontIndex = 0; 



    // variabel buat nyimpen kordinat tombol

    private Button currentlyHoveredBtn = null;

    private Button targetDoubleTapBtn = null;

    private boolean isRestingOnBackspace = false;

    private Handler hapusHandler = new Handler(Looper.getMainLooper());





    private View rootKeyboardView = null; 
    private View.OnClickListener legacyRootClick = null;
    private View.OnTouchListener legacyRootTouch = null;



    private static class ButtonAction {

        Runnable action;

        boolean requiresDoubleTap;

        ButtonAction(Runnable action, boolean requiresDoubleTap) {

            this.action = action;

            this.requiresDoubleTap = requiresDoubleTap;

        }

    }



    @Override

    public void onCreate() {

        super.onCreate();
        AuthManager.ensureFreshPreferences(this);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadClipboardHistory();
        accessibilityManager = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        
        audioFilePath = getFilesDir().getAbsolutePath() + "/VoiceAI_Whisper.m4a";

        try {
            int vol = Math.max(0, Math.min(100, UiKit.readIntPref(prefs, "sound_effect_volume", 70)));
            toneGen = new ToneGenerator(AudioManager.STREAM_SYSTEM, vol);
        } catch (Throwable ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder().setMaxStreams(10).setAudioAttributes(audioAttributes).build();
        } else {
            soundPool = new SoundPool(10, AudioManager.STREAM_MUSIC, 0);
        }

        cekUpdateAudioInstan(true);
    }


    /** Deteksi field search (WhatsApp Search kontak, SearchView, filter, hint cari) */
    private boolean isSearchEditor(EditorInfo info) {
        if (info == null) return false;
        try {
            int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
            if (action == EditorInfo.IME_ACTION_SEARCH) return true;
            if ((info.inputType & EditorInfo.TYPE_TEXT_VARIATION_FILTER)
                    == EditorInfo.TYPE_TEXT_VARIATION_FILTER) return true;
            if (info.hintText != null) {
                String h = info.hintText.toString().toLowerCase();
                if (h.contains("cari") || h.contains("search") || h.contains("find")
                        || h.contains("kontak") || h.contains("contact")) return true;
            }
            if (info.packageName != null) {
                String pkg = info.packageName.toString().toLowerCase();
                boolean wa = pkg.contains("whatsapp") || pkg.contains("w4b") || pkg.contains("gbwhatsapp");
                boolean multi = (info.inputType & EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
                // WA search: single-line di package WhatsApp (bukan bubble chat multi-line)
                if (wa && !multi && action != EditorInfo.IME_ACTION_SEND) {
                    // ACTION_SEARCH / GO / NONE sering dipakai SearchView WA
                    if (action == EditorInfo.IME_ACTION_SEARCH
                            || action == EditorInfo.IME_ACTION_GO
                            || action == EditorInfo.IME_ACTION_NONE
                            || action == EditorInfo.IME_ACTION_DONE) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isGeminiPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        return p.contains("gemini")
                || p.contains("google.android.apps.bard")
                || p.contains("com.google.android.apps.googleassistant")
                || p.equals("com.google.android.googlequicksearchbox");
    }

    private boolean isGeminiEditor(EditorInfo info) {
        if (info == null) return false;
        try {
            return isGeminiPackage(info.packageName != null ? info.packageName.toString() : null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** TeamTalk (BearWare) — password join channel sering sensitif terhadap fullscreen IME. */
    private boolean isTeamTalkPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        return p.contains("bearware") || p.contains("teamtalk") || p.equals("dk.bearware.gui");
    }

    private boolean isPasswordOrSensitiveEditor(EditorInfo info) {
        if (info == null) return false;
        try {
            int type = info.inputType;
            int variation = type & EditorInfo.TYPE_MASK_VARIATION;
            int cls = type & EditorInfo.TYPE_MASK_CLASS;
            if (cls == EditorInfo.TYPE_CLASS_TEXT) {
                if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                        || variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        || variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD) {
                    return true;
                }
            }
            if (cls == EditorInfo.TYPE_CLASS_NUMBER) {
                // PIN DANA / bank sering TYPE_CLASS_NUMBER + PASSWORD variation
                if (variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD) {
                    return true;
                }
            }
            // Beberapa app bank set flag password tanpa variation standar
            if ((type & EditorInfo.TYPE_TEXT_VARIATION_PASSWORD) == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD) {
                return true;
            }
            if (info.hintText != null) {
                String h = info.hintText.toString().toLowerCase();
                if (h.contains("pin") || h.contains("password") || h.contains("kata sandi")
                        || h.contains("sandi") || h.contains("otp") || h.contains("passcode")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Field yang harus selalu mulai dan tetap huruf kecil otomatis (password, email, URL, username, angka, filter). */
    private boolean shouldDisableAutoCap(EditorInfo info) {
        if (info == null) return false;
        if (isPasswordOrSensitiveEditor(info)) return true;
        try {
            int type = info.inputType;
            int cls = type & EditorInfo.TYPE_MASK_CLASS;
            int variation = type & EditorInfo.TYPE_MASK_VARIATION;
            if (cls == EditorInfo.TYPE_CLASS_NUMBER
                    || cls == EditorInfo.TYPE_CLASS_PHONE
                    || cls == EditorInfo.TYPE_CLASS_DATETIME) {
                return true;
            }
            if (cls == EditorInfo.TYPE_CLASS_TEXT) {
                if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                        || variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        || variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
                        || variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        || variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                        || variation == EditorInfo.TYPE_TEXT_VARIATION_URI
                        || variation == EditorInfo.TYPE_TEXT_VARIATION_FILTER) {
                    return true;
                }
            }
            if (info.hintText != null) {
                String h = info.hintText.toString().toLowerCase(java.util.Locale.US);
                if (h.contains("email") || h.contains("e-mail") || h.contains("surel")
                        || h.contains("username") || h.contains("nama pengguna")
                        || h.contains("url") || h.contains("website") || h.contains("domain")
                        || h.contains("tautan") || h.contains("link")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Non-fullscreen (stabil fokus, pola search WA yang sudah terbukti aman):
     * - WhatsApp Search
     * - TeamTalk
     * - Gemini
     * - PIN/password
     * - App sendiri (com.annas) → chat AI / dialog di MainActivity jangan ditutup Braille fullscreen
     * Chat WA biasa tetap fullscreen (sepi & nyaman).
     */
    private boolean shouldAvoidFullscreen(EditorInfo info) {
        try {
            if (info != null) {
                if (isSearchEditor(info) || isWhatsAppSearchEditor(info)) return true;
                if (isGeminiEditor(info)) return true;
                if (isPasswordOrSensitiveEditor(info)) return true;
                if (info.packageName != null) {
                    String pkg = info.packageName.toString();
                    if (isTeamTalkPackage(pkg)) return true;
                    // Chat AI & dialog di MainActivity: jangan fullscreen biar dialog tetap kelihatan
                    if (pkg.equals(getPackageName()) || pkg.startsWith("com.annas")) return true;
                }
            }
            if (isTeamTalkPackage(lastEditorPackage)) return true;
            if (isGeminiPackage(lastEditorPackage)) return true;
            if (lastEditorPackage != null
                    && (lastEditorPackage.equals(getPackageName()) || lastEditorPackage.startsWith("com.annas"))) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

                /**
     * Commit teks Braille.
     * Gemini: strategi buffer penuh (hapus semua isi field lalu tulis ulang
     * seluruh buffer). Ini yang biasa dipakai IME agar WebView/Chrome tidak
     * hanya menahan huruf pertama.
     */
    private void commitBrailleTextSafe(String text) {
        if (text == null || text.isEmpty()) return;
        if (isGeminiContext()) {
            final String piece = text;
            // Langsung di thread IME (seperti tempel), tanpa baca kursor dulu
            try {
                geminiTypeBuffer.append(piece);
                geminiRewriteFullBuffer();
            } catch (Throwable ignored) {
                imeHandler.post(new Runnable() {
                    @Override public void run() {
                        try {
                            geminiRewriteFullBuffer();
                        } catch (Throwable ignored2) {}
                    }
                });
            }
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        try {
            ic.commitText(text, 1);
        } catch (Throwable t) {
            try {
                InputConnection ic2 = getCurrentInputConnection();
                if (ic2 != null) ic2.commitText(text, 1);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Tulis ulang seluruh buffer ke field Gemini:
     * deleteSurroundingText kiri+kanan besar, lalu commitText(buffer).
     */
    private void geminiRewriteFullBuffer() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String full = geminiTypeBuffer.toString();
        try {
            try { ic.beginBatchEdit(); } catch (Throwable ignored) {}
            try {
                // Hapus sebanyak mungkin di kiri dan kanan kursor
                try {
                    ic.deleteSurroundingText(Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2);
                } catch (Throwable ignored) {}
                // Beberapa WebView perlu penghapusan berulang
                try {
                    ic.deleteSurroundingText(10000, 10000);
                } catch (Throwable ignored) {}
                if (full.length() > 0) {
                    ic.commitText(full, 1);
                }
            } finally {
                try { ic.endBatchEdit(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            try {
                InputConnection ic2 = getCurrentInputConnection();
                if (ic2 != null && full.length() > 0) {
                    ic2.commitText(full, 1);
                }
            } catch (Throwable ignored) {}
        }
    }

    private boolean isGeminiContext() {
        try {
            if (isGeminiPackage(lastEditorPackage)) return true;
            return isGeminiEditor(getCurrentInputEditorInfo());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Hapus 1 karakter. Gemini: kurangi buffer lalu tulis ulang penuh.
     */
    private void deleteBrailleCharSafe() {
        if (isGeminiContext()) {
            try {
                if (geminiTypeBuffer.length() > 0) {
                    geminiTypeBuffer.deleteCharAt(geminiTypeBuffer.length() - 1);
                }
                geminiRewriteFullBuffer();
            } catch (Throwable ignored) {
                imeHandler.post(new Runnable() {
                    @Override public void run() {
                        try { geminiRewriteFullBuffer(); } catch (Throwable ignored2) {}
                    }
                });
            }
            return;
        }
        try {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.deleteSurroundingText(1, 0);
        } catch (Throwable ignored) {}
    }

    private boolean isWhatsAppPackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        return p.contains("whatsapp") || p.contains("w4b") || p.contains("gbwhatsapp");
    }

    private boolean isWhatsAppSearchEditor(EditorInfo info) {
        if (info == null) return false;
        try {
            String pkg = info.packageName != null ? info.packageName.toString() : null;
            if (!isWhatsAppPackage(pkg)) return false;
            return isSearchEditor(info);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Clear passthrough a11y — wajib sebelum fokus ke kotak edit host */
    private void clearA11yPassthrough() {
        // Jangan clear saat sticky WhatsApp Search — itu bikin harus tangguhkan TalkBack lagi
        if (stickyShowForWaSearch) {
            return;
        }
        // Jangan clear saat di TeamTalk (crash proven)
        if (isPassthroughClearForbiddenNow()) {
            return;
        }
        try {
            SmartReplyAccessibilityService.forceClearPassthroughStatic();
        } catch (Throwable ignored) {}
        try {
            SmartReplyAccessibilityService.notifyBrailleMode(false);
        } catch (Throwable ignored) {}
    }

    /**
     * Dulu TeamTalk di-blacklist total karena crash saat CLEAR region di hide.
     * Sekarang: ENABLE passthrough (seperti WhatsApp Search) supaya tidak perlu
     * tangguhkan TalkBack, tapi JANGAN clear region selama masih di TeamTalk.
     * (Clear hanya saat pindah app / service destroy.)
     */
    private boolean isPassthroughClearForbiddenPackage(String pkg) {
        return isTeamTalkPackage(pkg);
    }

    private boolean isPassthroughClearForbiddenNow() {
        try {
            if (isPassthroughClearForbiddenPackage(lastEditorPackage)) return true;
            EditorInfo ei = getCurrentInputEditorInfo();
            if (ei != null && ei.packageName != null
                    && isPassthroughClearForbiddenPackage(ei.packageName.toString())) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Aktifkan passthrough — termasuk TeamTalk (pola WA Search, ringan). */
    private void safeEnableBraillePassthrough() {
        try {
            SmartReplyAccessibilityService.notifyBrailleMode(true);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        // Catat identitas editor. Field beda → clear hide-guard.
        String pkg = attribute != null ? String.valueOf(attribute.packageName) : null;
        int fieldId = attribute != null ? attribute.fieldId : Integer.MIN_VALUE;
        int inputType = attribute != null ? attribute.inputType : Integer.MIN_VALUE;
        int imeOptions = attribute != null ? attribute.imeOptions : Integer.MIN_VALUE;
        String hint = null;
        if (attribute != null && attribute.hintText != null) {
            hint = attribute.hintText.toString();
        }
        boolean editorChanged = !java.util.Objects.equals(lastEditorPackage, pkg)
                || lastEditorFieldId != fieldId
                || lastEditorInputType != inputType
                || lastEditorImeOptions != imeOptions
                || !java.util.Objects.equals(lastEditorHint, hint);
        // Clear hide-guard non-Gemini ditangani di bawah (setelah last* di-set)
        if (!isGeminiPackage(pkg) && (!restarting || editorChanged)) {
            userHideUptimeMs = 0L;
        }
        lastEditorPackage = pkg;
        lastEditorFieldId = fieldId;
        lastEditorInputType = inputType;
        lastEditorImeOptions = imeOptions;
        lastEditorHint = hint;
        // Ganti field = reset buffer Gemini
        try {
            int key = (pkg != null ? pkg.hashCode() : 0) ^ fieldId ^ inputType;
            if (key != geminiBufferFieldKey) {
                geminiBufferFieldKey = key;
                geminiTypeBuffer.setLength(0);
            }
        } catch (Throwable ignored) {}

        // Gemini: JANGAN hapus sticky hide hanya karena restart IME.
        // Hanya hapus jika field benar-benar beda (bukan restart).
        if (isGeminiPackage(pkg)) {
            if (editorChanged && !restarting) {
                stickyHideForGemini = false;
                userHideUptimeMs = 0L;
            }
            // restarting di field sama → biarkan sticky tetap
        } else {
            if (!restarting || editorChanged) {
                stickyHideForGemini = false;
                userHideUptimeMs = 0L;
            }
        }

        boolean waSearch = isWhatsAppSearchEditor(attribute) || (isWhatsAppPackage(pkg) && isSearchEditor(attribute));

        // --- WhatsApp Search: sticky ON + passthrough (pola perbaikan pertama) ---
        // TeamTalk: JANGAN clear passthrough di sini (clear saat di TeamTalk = crash).
        if (waSearch) {
            userHideUptimeMs = 0L;
            stickyShowForWaSearch = true;
            cancelPendingWaSearchClear();
            // Jangan clear passthrough di sini — itu yang bikin search "bolak-balik"
            if (isBrailleModeActive) {
                scheduleBraillePassthrough();
            }
        } else {
            if (stickyShowForWaSearch && !isWhatsAppPackage(pkg)) {
                stickyShowForWaSearch = false;
            }
            if (editorChanged && !restarting && !isWhatsAppPackage(pkg)) {
                stickyShowForWaSearch = false;
            }
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        AuthManager.startRealtimePremiumGuard(this, "ime", this::onRealtimePremiumStateChanged);
        refreshMicPremiumVisual();
        boolean searchField = isSearchEditor(info);
        boolean waSearch = isWhatsAppSearchEditor(info);
        boolean isBrailleDefault = prefs != null && prefs.getBoolean("braille_default_mode", false);

        // Selalu matikan extract view di mode Braille — mencegah kotak edit host
        // (WhatsApp chat/search, YouTube, PIN, TeamTalk password) "muncul".
        try { setExtractViewShown(false); } catch (Throwable ignored) {}

        // TeamTalk / password: jangan biarkan sticky hide Gemini/guard menahan keyboard
        String pkgNow = null;
        try {
            pkgNow = info != null && info.packageName != null ? info.packageName.toString() : lastEditorPackage;
        } catch (Throwable ignored) {}
        if (isTeamTalkPackage(pkgNow) || isPasswordOrSensitiveEditor(info)) {
            userHideUptimeMs = 0L;
            // Sticky Gemini tidak relevan di app lain
            if (!isGeminiPackage(pkgNow)) stickyHideForGemini = false;
        }

        if (isBrailleDefault) {
            if (!isBrailleModeActive) {
                switchToBrailleKeyboard();
            } else {
                // Samakan search/Gemini dengan chat WA: fullscreen kecuali TeamTalk
                boolean avoidFs = shouldAvoidFullscreen(info);
                applyFullscreenWindow(!avoidFs);
                try { setExtractViewShown(false); } catch (Throwable ignored) {}
                if (waSearch) {
                    stickyShowForWaSearch = true;
                }
                scheduleBraillePassthrough();
                announceBrailleActiveNow();
            }
        } else {
            if (isBrailleModeActive) {
                switchToQwertyKeyboard();
            } else {
                applyFullscreenWindow(false);
                if (!stickyShowForWaSearch) {
                    clearA11yPassthrough();
                }
            }
        }

        if (brailleTTSManager != null && prefs != null) {
            // Muat ulang engine TTS jika user mengganti di settings (tanpa restart app)
            brailleTTSManager.ensureEngineUpToDate();
            float rate = prefs.getFloat("braille_tts_rate", 1.0f);
            brailleTTSManager.setSpeechRate(rate);
        }

        if (customKeyboardView != null && !isBrailleModeActive) {
            customKeyboardView.reloadLayoutConfig();
            applyTypingModeToRoot();
            customKeyboardView.reloadCustomSound();
            int inputType = info != null ? (info.inputType & android.text.InputType.TYPE_MASK_CLASS) : 0;
            if (inputType == android.text.InputType.TYPE_CLASS_NUMBER || inputType == android.text.InputType.TYPE_CLASS_PHONE) {
                customKeyboardView.switchToNumpad();
            } else {
                customKeyboardView.resetToQwerty();
            }
        }
        cekUpdateAudioInstan(false);
        setMicButtonState(false);
        stopHapusBeruntun();

        sembunyikanSemuaLayer();
        qwertyWordBuf.setLength(0);

        // Handle InputType Context (hanya relevan untuk QWERTY & Braille)
        if (shouldDisableAutoCap(info)) {
            shiftState = 0;
            if (customKeyboardView != null) customKeyboardView.resetShiftState();
            if (brailleKeyboardView != null) brailleKeyboardView.resetShiftState();
            updateKeyboardCase();
            if (prefs != null && prefs.getBoolean("tts_field_type_announce", true)
                    && isPasswordOrSensitiveEditor(info)
                    && accessibilityManager != null && accessibilityManager.isEnabled()) {
                announceForAccessibility("Kolom kata sandi, huruf kecil aktif");
            }
            if (info != null && !isBrailleModeActive) {
                int type = info.inputType & EditorInfo.TYPE_MASK_CLASS;
                if (type == EditorInfo.TYPE_CLASS_NUMBER || type == EditorInfo.TYPE_CLASS_PHONE) {
                    sembunyikanSemuaLayer();
                }
            }
            return;
        }

        if (info != null && !isBrailleModeActive) {
            int type = info.inputType & EditorInfo.TYPE_MASK_CLASS;
            if (type == EditorInfo.TYPE_CLASS_NUMBER || type == EditorInfo.TYPE_CLASS_PHONE) {
                sembunyikanSemuaLayer();
            }
        }

        shiftState = 0;
        if (customKeyboardView != null) customKeyboardView.resetShiftState();
        checkAutoCapitalization();
    }



    private void sembunyikanSemuaLayer() {

//         if (layoutQwerty != null) layoutQwerty.setVisibility(View.GONE);
//         if (layoutSym1 != null) layoutSym1.setVisibility(View.GONE);
//         if (layoutSym2 != null) layoutSym2.setVisibility(View.GONE);
//         if (layoutSym3 != null) layoutSym3.setVisibility(View.GONE);
        if (layoutEmoji != null) layoutEmoji.setVisibility(View.GONE);
        if (layoutFont != null) layoutFont.setVisibility(View.GONE);
        hideQuickToolsPanel();
    }

    private boolean isAksesibilitasAktif() {
        return accessibilityManager != null && accessibilityManager.isTouchExplorationEnabled();
    }

    private void announceForAccessibility(String text) {
        if (accessibilityManager != null && accessibilityManager.isEnabled()) {
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.getText().add(text);
            accessibilityManager.sendAccessibilityEvent(event);

        }

    }






    private String getMappedChar(String baseChar, boolean isUpper, int fontIndex) {

        if (fontIndex == 0) return isUpper ? baseChar.toUpperCase() : baseChar.toLowerCase();


        int alphabetIndex = baseChar.toLowerCase().charAt(0) - 'a';

        if (alphabetIndex >= 0 && alphabetIndex < 26) {

            if (isUpper) {




                if (fontIndex < KeyboardData.FONT_MAP_UPPER.length) return KeyboardData.FONT_MAP_UPPER[fontIndex][alphabetIndex];

            } else {




                if (fontIndex < KeyboardData.FONT_MAP_LOWER.length) return KeyboardData.FONT_MAP_LOWER[fontIndex][alphabetIndex];

            }

        }

        return isUpper ? baseChar.toUpperCase() : baseChar.toLowerCase();

    }



    private void checkAutoCapitalization() {
        EditorInfo ei = null;
        try { ei = getCurrentInputEditorInfo(); } catch (Throwable ignored) {}

        // Password, email, URL, nomor, atau setting mati -> wajib huruf kecil otomatis.
        if (shouldDisableAutoCap(ei) || !prefs.getBoolean("auto_capitalization", true)) {
            if (shiftState == 1) shiftState = 0;
            if (customKeyboardView != null && !customKeyboardView.isCapsLocked()) {
                customKeyboardView.setAutoCapitalize(false);
            }
            updateKeyboardCase();
            return;
        }

        if (shiftState == 2 || (customKeyboardView != null && customKeyboardView.isCapsLocked())) {
            shiftState = 2;
            return;
        }

        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        int nextShift = 0;
        if (ei != null) {
            int capFlags = ei.inputType & (EditorInfo.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    | EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS
                    | EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES);
            if (capFlags != 0) {
                try {
                    nextShift = (ic.getCursorCapsMode(ei.inputType) != 0) ? 1 : 0;
                } catch (Throwable ignored) {
                    // Fallback aman jika app tidak mendukung getCursorCapsMode: hanya setelah ". ", "? ", "! ", "\n", atau teks kosong
                    CharSequence textBefore = ic.getTextBeforeCursor(5, 0);
                    if (textBefore == null || textBefore.length() == 0) {
                        nextShift = 1;
                    } else if ((capFlags & EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0) {
                        String txt = textBefore.toString();
                        if (txt.endsWith("\n") || txt.endsWith(". ") || txt.endsWith("? ") || txt.endsWith("! ")) {
                            nextShift = 1;
                        }
                    }
                }
            }
        }
        shiftState = nextShift;
        if (customKeyboardView != null) { customKeyboardView.setAutoCapitalize(shiftState > 0); }
        updateKeyboardCase();
    }

    private void updateKeyboardCase() {
        if (qwertyButtons == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (qwertyButtons != null) {
                    String[] baseLetters = {"q","w","e","r","t","y","u","i","o","p","a","s","d","f","g","h","j","k","l","z","x","c","v","b","n","m"};
                    for (int i = 0; i < qwertyButtons.length; i++) {
                        Button b = qwertyButtons[i];
                        if (b != null && i < baseLetters.length) {
                            String mappedChar = getMappedChar(baseLetters[i], shiftState > 0, currentFontIndex);
                            b.setText(mappedChar);
                            b.setContentDescription(shiftState > 0 ? "Huruf besar " + mappedChar : mappedChar);
                        }
                    }
                }
            }
        });
    }






    /**
     * Selalu siap tampil selama ada editor aktif.
     * Guard anti-reopen singkat hanya di onShowInputRequested (setelah gesture 2 jari).
     */
    @Override
    public boolean onEvaluateInputViewShown() {
        try {
            if (getCurrentInputEditorInfo() != null) {
                return true;
            }
        } catch (Throwable ignored) {}
        return super.onEvaluateInputViewShown();
    }

    @Override
    public boolean onShowInputRequested(int flags, boolean configChange) {
        long now = android.os.SystemClock.uptimeMillis();
        boolean inGuard = userHideUptimeMs > 0L
                && (now - userHideUptimeMs) < USER_HIDE_GUARD_MS;

        EditorInfo ei = null;
        try { ei = getCurrentInputEditorInfo(); } catch (Throwable ignored) {}
        boolean gemini = isGeminiEditor(ei);
        boolean search = isSearchEditor(ei);
        String pkg = null;
        try {
            pkg = ei != null && ei.packageName != null ? ei.packageName.toString() : lastEditorPackage;
        } catch (Throwable ignored) {}
        boolean teamTalk = isTeamTalkPackage(pkg);
        boolean passwordField = isPasswordOrSensitiveEditor(ei);

        // WhatsApp Search / SearchView: SELALU izinkan tampil
        if (search) {
            userHideUptimeMs = 0L;
            if (isWhatsAppSearchEditor(ei) || isWhatsAppPackage(lastEditorPackage)) {
                stickyShowForWaSearch = true;
                cancelPendingWaSearchClear();
            }
            return true;
        }

        // TeamTalk password / PIN / password umum: SELALU izinkan tampil
        // (tanpa harus tangguhkan TalkBack — sama stabilnya dengan WhatsApp).
        if (teamTalk || passwordField) {
            userHideUptimeMs = 0L;
            return true;
        }

        // --- GEMINI STICKY ---
        // Setelah 2 jari kiri: TOLAK semua show (termasuk EXPLICIT/FORCED)
        // sampai lewat jeda panjang — cegah loop sembunyi↔tampil.
        if (stickyHideForGemini && gemini) {
            long sinceHide = (userHideUptimeMs > 0L) ? (now - userHideUptimeMs) : 0L;
            if (sinceHide >= GEMINI_EXPLICIT_UNLOCK_MS
                    && (flags & InputMethod.SHOW_EXPLICIT) != 0) {
                stickyHideForGemini = false;
                userHideUptimeMs = 0L;
                return true;
            }
            return false;
        }

        if (inGuard) {
            // Guard umum: hanya EXPLICIT setelah jeda singkat
            if ((flags & InputMethod.SHOW_EXPLICIT) != 0
                    && (now - userHideUptimeMs) >= USER_HIDE_GUARD_MS) {
                userHideUptimeMs = 0L;
                return true;
            }
            return false;
        }

        if (!gemini) {
            userHideUptimeMs = 0L;
        }
        if ((flags & InputMethod.SHOW_FORCED) != 0 || (flags & InputMethod.SHOW_EXPLICIT) != 0) {
            return true;
        }
        try {
            if (ei != null || getCurrentInputEditorInfo() != null) {
                return true;
            }
        } catch (Throwable ignored) {}
        return super.onShowInputRequested(flags, configChange);
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        // Fullscreen Braille default ON untuk chat biasa / game.
        // OFF (pola WhatsApp Search yang sudah aman) untuk:
        // search, TeamTalk, Gemini, PIN/password (DANA, bank).
        // WhatsApp Search: JANGAN diubah — sudah stabil.
        if (!isBrailleModeActive) return false;
        try {
            EditorInfo ei = null;
            try { ei = getCurrentInputEditorInfo(); } catch (Throwable ignored) {}
            if (shouldAvoidFullscreen(ei)) return false;
        } catch (Throwable ignored) {}
        return true;
    }

    @Override
    public void updateFullscreenMode() {
        super.updateFullscreenMode();
        if (isBrailleModeActive) {
            try { setExtractViewShown(false); } catch (Throwable ignored) {}
            // Beberapa OEM tetap menampilkan candidates/extract singkat —
            // pastikan lagi setelah layout settle.
            imeHandler.post(new Runnable() {
                @Override public void run() {
                    if (isBrailleModeActive) {
                        try { setExtractViewShown(false); } catch (Throwable ignored) {}
                    }
                }
            });
        }
    }

    @Override
    public void onComputeInsets(InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (isBrailleModeActive) {
            // Keyboard Braille 100% Layar Penuh menutupi layar dari y=0 hingga bawah
            outInsets.contentTopInsets = 0;
            outInsets.visibleTopInsets = 0;
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_FRAME;
        }
    }

    @Override
    public void onConfigureWindow(android.view.Window win, boolean isFullscreen, boolean isCandidatesOnly) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly);
        if (isBrailleModeActive) {
            applyFullscreenWindow(true);
        }
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        if (isInputViewShown()) {
            AuthManager.startRealtimePremiumGuard(this, "ime", this::onRealtimePremiumStateChanged);
            refreshMicPremiumVisual();
        }
        EditorInfo ei = null;
        try { ei = getCurrentInputEditorInfo(); } catch (Throwable ignored) {}
        boolean waSearch = isWhatsAppSearchEditor(ei) || isSearchEditor(ei);

        // Batalkan clear tertunda (restart WA Search sering hide→show cepat)
        cancelPendingWaSearchClear();

        if (isBrailleModeActive) {
            boolean avoidFs = shouldAvoidFullscreen(ei);
            applyFullscreenWindow(!avoidFs);
            try { setExtractViewShown(false); } catch (Throwable ignored) {}
            if (waSearch || isWhatsAppSearchEditor(ei) || isSearchEditor(ei)) {
                stickyShowForWaSearch = true;
            }
            scheduleBraillePassthrough();
        } else {
            cancelPendingSearchPassthrough();
            if (!stickyShowForWaSearch) {
                // Jangan clear sinkron — bisa ganggu app host
                imeHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!isBrailleModeActive && !stickyShowForWaSearch) {
                            try { clearA11yPassthrough(); } catch (Throwable ignored) {}
                        }
                    }
                }, 200L);
            }
        }
        if (!isBrailleModeActive) {
            announceQwertyWindowState("Keyboard AI by Anas ditampilkan");
            qwertyWindowAnnouncementShown = true;
        } else {
            qwertyWindowAnnouncementShown = false;
        }
    }

    /**
     * Mengirim pengumuman aksesibilitas untuk perubahan tampil/sembunyi keyboard QWERTY.
     * Dideduplikasi agar restart window oleh aplikasi host tidak membuat ucapan bertumpuk.
     */
    private void announceQwertyWindowState(String message) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            if (message.equals(lastQwertyAnnouncement) && now - lastQwertyAnnouncementUptime < 900L) {
                return;
            }
            lastQwertyAnnouncement = message;
            lastQwertyAnnouncementUptime = now;
            if (accessibilityManager == null) {
                accessibilityManager = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            }
            if (accessibilityManager == null || !accessibilityManager.isEnabled()) return;
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
            event.setPackageName(getPackageName());
            event.setClassName(KeyboardService.class.getName());
            event.setEventTime(android.os.SystemClock.uptimeMillis());
            event.getText().add(message);
            accessibilityManager.sendAccessibilityEvent(event);
        } catch (Throwable ignored) {
            // Pengumuman tidak boleh mengganggu input atau membuat keyboard crash.
        }
    }

    private void cancelPendingSearchPassthrough() {
        if (pendingSearchPassthrough != null) {
            imeHandler.removeCallbacks(pendingSearchPassthrough);
            pendingSearchPassthrough = null;
        }
    }

    /**
     * Aktifkan touch-exploration passthrough untuk mode Braille.
     * Dipakai di semua field (bukan hanya search) agar perilaku seragam.
     */
    private void scheduleBraillePassthrough() {
        cancelPendingSearchPassthrough();
        final int gen = ++passthroughScheduleGen;
        // Beberapa kali enable: buka pertama search/TeamTalk sering belum siap
        // di frame 0 sehingga user merasa harus "kembali dulu".
        final long[] delays = new long[] { 0L, 30L, 100L, 250L, 500L };
        for (int i = 0; i < delays.length; i++) {
            final long d = delays[i];
            imeHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (gen != passthroughScheduleGen) return;
                    if (!isBrailleModeActive) return;
                    try { safeEnableBraillePassthrough(); } catch (Throwable ignored) {}
                }
            }, d);
        }
    }

    /** Alias lama — semua pemanggil dialihkan ke jalur seragam. */
    private void scheduleSearchPassthrough() {
        scheduleBraillePassthrough();
    }

    @Override
    public void hideWindow() {
        // JANGAN set guard di sini. hideWindow() dipanggil juga oleh sistem /
        // host app (WhatsApp SearchView, Gemini restart, TeamTalk dialog, fokus hilang
        // sebentar). Guard hanya di onHideKeyboard (gesture eksplisit user).
        try {
            super.hideWindow();
        } catch (Throwable ignored) {}
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        AuthManager.stopRealtimePremiumGuard("ime");
    }

    @Override
    public void onWindowHidden() {
        AuthManager.stopRealtimePremiumGuard("ime");
        if (customKeyboardView != null) customKeyboardView.cancelTouchInteraction();
        flushTypingStats();
        hideQuickToolsPanel();
        if (!isBrailleModeActive && qwertyWindowAnnouncementShown) {
            announceQwertyWindowState("Keyboard disembunyikan");
            qwertyWindowAnnouncementShown = false;
        }
        try {
            super.onWindowHidden();
        } catch (Throwable ignored) {}
        try {
            cancelPendingSearchPassthrough();
        } catch (Throwable ignored) {}

        // Stop TTS dulu (ringan, aman)
        try {
            if (brailleTTSManager != null) brailleTTSManager.stop();
        } catch (Throwable ignored) {}

        // WhatsApp Search sticky: jangan clear / teardown dulu
        final boolean keepSticky = stickyShowForWaSearch;
        if (keepSticky) {
            cancelPendingWaSearchClear();
            pendingWaSearchClear = new Runnable() {
                @Override public void run() {
                    pendingWaSearchClear = null;
                    try {
                        if (!isInputViewShown()) {
                            // Baru sekarang aman membersihkan
                            softTeardownAfterHide();
                        }
                    } catch (Throwable ignored) {}
                }
            };
            imeHandler.postDelayed(pendingWaSearchClear, WA_SEARCH_HIDE_GRACE_MS);
            return;
        }

        // Non-sticky: teardown & clear a11y JAUH setelah window hilang
        // (500ms+) — ini yang mencegah force-close TeamTalk.
        imeHandler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (!isInputViewShown()) {
                        softTeardownAfterHide();
                    }
                } catch (Throwable ignored) {}
            }
        }, 500L);
    }

    /** Teardown aman setelah IME benar-benar hilang (bukan di frame gesture). */
    private void softTeardownAfterHide() {
        try {
            // TeamTalk: JANGAN clear passthrough (clear = force-close proven).
            if (isPassthroughClearForbiddenNow()) {
                try { setExtractViewShown(false); } catch (Throwable ignored) {}
                return;
            }
        } catch (Throwable ignored) {}
        try { clearA11yPassthrough(); } catch (Throwable ignored) {}
        try { setExtractViewShown(false); } catch (Throwable ignored) {}
        // Gemini: jangan applyFullscreenWindow(false) — sering memicu show ulang (loop).
        try {
            if (stickyHideForGemini || isGeminiPackage(lastEditorPackage)) {
                return;
            }
            applyFullscreenWindow(false);
        } catch (Throwable ignored) {}
    }

    private void cancelPendingWaSearchClear() {
        if (pendingWaSearchClear != null) {
            imeHandler.removeCallbacks(pendingWaSearchClear);
            pendingWaSearchClear = null;
        }
    }

    public void applyFullscreenWindow(boolean fullscreen) {
        try {
            android.app.Dialog dialog = getWindow();
            if (dialog == null) return;
            android.view.Window win = dialog.getWindow();
            if (win == null) return;

            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            // Pastikan window IME tidak di-FLAG_SECURE agar screen recorder bisa merekam
            // (tiru perilaku Advanced Braille Keyboard yang tetap recordable tanpa perlu dimatikan)
            lp.flags &= ~android.view.WindowManager.LayoutParams.FLAG_SECURE;
            if (fullscreen) {
                lp.x = 0;
                lp.y = 0;
                lp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT;
                lp.height = android.view.WindowManager.LayoutParams.MATCH_PARENT;
                lp.gravity = android.view.Gravity.FILL;

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }

                lp.flags |= android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
                         | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                         | android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;

                // Window IME memenuhi layar dan modal: sentuhan di dalam window tidak
                // diteruskan ke aplikasi di belakang. FLAG_LAYOUT_NO_LIMITS sengaja tidak
                // dipakai agar area sentuh tidak meluber keluar batas layar.
                // Android tetap mengendalikan notification shade; IME tidak boleh
                // memblokir paksa panel notifikasi sistem.
                lp.flags &= ~android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                lp.flags &= ~android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

                // Pastikan FLAG_SECURE tetap mati setelah OR flags
                lp.flags &= ~android.view.WindowManager.LayoutParams.FLAG_SECURE;

                win.setAttributes(lp);
                win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                win.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT);

                android.view.View decor = win.getDecorView();
                if (decor != null) {
                    decor.setFitsSystemWindows(false);
                    // Sembunyikan decor dari JOS/TalkBack selama Braille fullscreen
                    try {
                        decor.setImportantForAccessibility(
                            android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                    } catch (Throwable ignored) {}
                    int flags = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
                    decor.setSystemUiVisibility(flags);

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        try {
                            android.view.WindowInsetsController controller = decor.getWindowInsetsController();
                            if (controller != null) {
                                controller.hide(
                                    android.view.WindowInsets.Type.statusBars()
                                    | android.view.WindowInsets.Type.navigationBars()
                                );
                                controller.setSystemBarsBehavior(
                                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                                );
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } else {
                lp.x = 0;
                lp.y = 0;
                lp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT;
                lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
                lp.gravity = android.view.Gravity.BOTTOM;

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
                }

                lp.flags &= ~android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
                lp.flags &= ~android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                lp.flags &= ~android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
                lp.flags |= android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                // Pastikan FLAG_SECURE tetap mati agar screen recorder bisa merekam
                lp.flags &= ~android.view.WindowManager.LayoutParams.FLAG_SECURE;

                win.setAttributes(lp);
                win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
                win.setGravity(android.view.Gravity.BOTTOM);
                win.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);

                android.view.View decor = win.getDecorView();
                if (decor != null) {
                    decor.setFitsSystemWindows(true);
                    decor.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_VISIBLE);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        try {
                            android.view.WindowInsetsController controller = decor.getWindowInsetsController();
                            if (controller != null) {
                                controller.show(
                                    android.view.WindowInsets.Type.statusBars()
                                    | android.view.WindowInsets.Type.navigationBars()
                                );
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.e("KeyboardService", "applyFullscreenWindow error", t);
        }
    }

    public void switchToBrailleKeyboard() {
        if (keyboardContainerLayout == null) return;
        if (brailleKeyboardView == null) {
            initBrailleKeyboardView();
        }
        keyboardContainerLayout.removeAllViews();
        keyboardContainerLayout.addView(brailleKeyboardView, new android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Anti-tabrakan TalkBack/JOS/Jieshuo: sembunyikan pohon view dari screen reader.
        // Hover→touch dikonversi di BrailleKeyboardView agar chord tetap jalan.
        try {
            int noAcc = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;
            brailleKeyboardView.setImportantForAccessibility(noAcc);
            keyboardContainerLayout.setImportantForAccessibility(noAcc);
            if (rootKeyboardView != null) {
                rootKeyboardView.setImportantForAccessibility(noAcc);
            }
            android.app.Dialog dlg = getWindow();
            if (dlg != null && dlg.getWindow() != null) {
                android.view.View decor = dlg.getWindow().getDecorView();
                if (decor != null) {
                    decor.setImportantForAccessibility(noAcc);
                }
            }
        } catch (Throwable ignored) {}

        boolean avoidFullscreen = false;
        try {
            EditorInfo eiNow = null;
            try { eiNow = getCurrentInputEditorInfo(); } catch (Throwable ignored) {}
            avoidFullscreen = shouldAvoidFullscreen(eiNow);
        } catch (Throwable ignored) {}

        int realScreenH = BrailleKeyboardView.getRealScreenHeight(this);
        // TeamTalk / Search: WRAP_CONTENT (stabil fokus + cegah crash teardown)
        int targetH = avoidFullscreen
                ? android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                : android.view.ViewGroup.LayoutParams.MATCH_PARENT;
        int minH = avoidFullscreen ? 0 : realScreenH;

        if (rootKeyboardView != null) {
            rootKeyboardView.setFitsSystemWindows(false);
            rootKeyboardView.setMinimumHeight(minH);
            android.view.ViewGroup.LayoutParams rootLp = rootKeyboardView.getLayoutParams();
            if (rootLp != null) {
                rootLp.height = targetH;
                rootKeyboardView.setLayoutParams(rootLp);
            }
        }
        keyboardContainerLayout.setFitsSystemWindows(false);
        keyboardContainerLayout.setMinimumHeight(minH);
        android.view.ViewGroup.LayoutParams containerLp = keyboardContainerLayout.getLayoutParams();
        if (containerLp != null) {
            containerLp.height = targetH;
            keyboardContainerLayout.setLayoutParams(containerLp);
        }

        isBrailleModeActive = true;
        applyTypingModeToRoot();
        if (prefs != null) {
            prefs.edit().putBoolean("braille_default_mode", true).apply();
        }
        applyFullscreenWindow(!avoidFullscreen);
        try { setExtractViewShown(false); } catch (Throwable ignored) {}

        // Aktifkan passthrough segera. Jangan clear dulu.
        scheduleBraillePassthrough();
        lastBrailleActiveAnnounceUptime = android.os.SystemClock.uptimeMillis();
        boolean queueForCalib = (brailleKeyboardView != null && brailleKeyboardView.isCalibrating());

        if (SmartReplyAccessibilityService.instance != null) {
            if (brailleTTSManager != null) {
                brailleTTSManager.speak("Keyboard Braille aktif. 6 titik siap digunakan.", !queueForCalib);
            }
        } else {
            if (brailleTTSManager != null) {
                brailleTTSManager.speak(
                    "Keyboard Braille aktif. Aktifkan layanan aksesibilitas Keyboard AI agar gesture screen reader tidak mengganggu.",
                    !queueForCalib);
            }
            try {
                android.widget.Toast.makeText(this,
                    "Aktifkan Aksesibilitas Keyboard AI agar gesture screen reader tidak mengganggu mode Braille.",
                    android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        }
    }

    private void announceBrailleActiveNow() {
        if (!isBrailleModeActive) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastBrailleActiveAnnounceUptime < 800L) return;
        lastBrailleActiveAnnounceUptime = now;
        if (brailleTTSManager != null) {
            brailleTTSManager.ensureEngineUpToDate();
            boolean queueForCalib = (brailleKeyboardView != null && brailleKeyboardView.isCalibrating());
            brailleTTSManager.speak("Keyboard Braille aktif", !queueForCalib);
        }
    }

    public void switchToQwertyKeyboard() {
        if (keyboardContainerLayout == null) return;
        keyboardContainerLayout.removeAllViews();
        // Kembalikan aksesibilitas ke default agar QWERTY + TalkBack/JOS tetap jalan
        try {
            keyboardContainerLayout.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            if (rootKeyboardView != null) {
                rootKeyboardView.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            }
            if (customKeyboardView != null) {
                customKeyboardView.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            }
            android.app.Dialog dlg = getWindow();
            if (dlg != null && dlg.getWindow() != null) {
                android.view.View decor = dlg.getWindow().getDecorView();
                if (decor != null) {
                    decor.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
                }
            }
        } catch (Throwable ignored) {}
        if (customKeyboardView != null) {
            android.widget.FrameLayout.LayoutParams cvLp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cvLp.gravity = android.view.Gravity.BOTTOM;
            keyboardContainerLayout.addView(customKeyboardView, cvLp);
        }

        if (rootKeyboardView != null) {
            rootKeyboardView.setFitsSystemWindows(true);
            rootKeyboardView.setMinimumHeight(0);
            android.view.ViewGroup.LayoutParams rootLp = rootKeyboardView.getLayoutParams();
            if (rootLp != null) {
                rootLp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                rootKeyboardView.setLayoutParams(rootLp);
            }
        }
        keyboardContainerLayout.setFitsSystemWindows(true);
        keyboardContainerLayout.setMinimumHeight(0);
        android.view.ViewGroup.LayoutParams containerLp = keyboardContainerLayout.getLayoutParams();
        if (containerLp != null) {
            containerLp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            keyboardContainerLayout.setLayoutParams(containerLp);
        }

        isBrailleModeActive = false;
        applyTypingModeToRoot();
        if (prefs != null) {
            prefs.edit().putBoolean("braille_default_mode", false).apply();
        }
        applyFullscreenWindow(false);
        updateFullscreenMode();

        // Kembalikan gesture screen reader normal (seperti Advanced Braille saat keyboard ditutup)
        SmartReplyAccessibilityService.notifyBrailleMode(false);

        if (brailleTTSManager != null) {
            brailleTTSManager.speak("Kembali ke papan ketik huruf biasa.", true);
        }
    }

    private void initBrailleKeyboardView() {
        if (brailleTTSManager == null) {
            brailleTTSManager = new BrailleTTSManager(this, prefs, null);
        }
        android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(android.content.Context.VIBRATOR_SERVICE);
        brailleKeyboardView = new BrailleKeyboardView(this, new BrailleKeyboardView.OnBrailleActionListener() {
            @Override
            public void onCommitText(String text) {
                // Gemini / password / WebView: batch + finishComposing agar
                // "Halo" tidak hanya menyisakan "o".
                commitBrailleTextSafe(text);
            }

            @Override
            public void onSpace() {
                onBrailleSpace();
            }

            @Override
            public void onDelete() {
                onBrailleDelete();
            }

            @Override
            public void onDeleteWord() {
                onBrailleDeleteWord();
            }

            @Override
            public void onEnter() {
                // Pakai logic Enter universal yang sama (YouTube search, Termux, multi-line, dll)
                KeyboardService.this.onEnter();
            }

            @Override
            public void onSend() {
                // Kirim pesan (WhatsApp dll) — dipicu gesture Braille yang bisa diatur di pengaturan
                KeyboardService.this.performSendAction();
            }

            @Override
            public void onHideKeyboard() {
                // Gesture 2 jari kiri — VERSI PALING AMAN.
                // Crash TeamTalk terbukti saat Braille hide menyentuh:
                //   - setTouchExplorationPassthroughRegion
                //   - applyFullscreenWindow(false) di frame yang sama
                // Jadi di sini HANYA: set flag + requestHideSelf tertunda.
                // Clear a11y & teardown fullscreen hanya di onWindowHidden (delay panjang).
                try {
                    userHideUptimeMs = android.os.SystemClock.uptimeMillis();
                    try {
                        if (isGeminiPackage(lastEditorPackage)
                                || isGeminiEditor(getCurrentInputEditorInfo())) {
                            stickyHideForGemini = true;
                        }
                    } catch (Throwable ignored) {
                        try {
                            if (isGeminiPackage(lastEditorPackage)) stickyHideForGemini = true;
                        } catch (Throwable ignored2) {}
                    }
                    stickyShowForWaSearch = false;
                    try { cancelPendingWaSearchClear(); } catch (Throwable ignored) {}
                    try { cancelPendingSearchPassthrough(); } catch (Throwable ignored) {}

                    // JANGAN clearA11yPassthrough di sini sama sekali.
                    imeHandler.post(new Runnable() {
                        @Override public void run() {
                            try {
                                requestHideSelf(0);
                            } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable t) {
                    try {
                        imeHandler.post(new Runnable() {
                            @Override public void run() {
                                try { requestHideSelf(0); } catch (Throwable ignored) {}
                            }
                        });
                    } catch (Throwable ignored) {}
                }
            }

            @Override
            public void onVoiceInput() {
                mulaiVoiceInputUmum();
            }

            @Override
            public void onSwitchToQwerty() {
                switchToQwertyKeyboard();
            }

            @Override
            public void onSpeakTTS(String speechText, boolean interrupt) {
                if (brailleTTSManager != null) {
                    brailleTTSManager.speak(speechText, interrupt);
                }
            }

            @Override
            public void onReadWordBeforeCursor() {
                try {
                    android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
                    if (ic == null || brailleTTSManager == null) return;
                    CharSequence before = ic.getTextBeforeCursor(500, 0);
                    if (before == null || before.length() == 0) return;
                    String textBefore = before.toString();
                    int end = textBefore.length();
                    while (end > 0 && Character.isWhitespace(textBefore.charAt(end - 1))) end--;
                    int start = end;
                    while (start > 0 && !Character.isWhitespace(textBefore.charAt(start - 1))) start--;
                    if (end > start) brailleTTSManager.speak(textBefore.substring(start, end), true);
                } catch (Throwable ignored) {}
            }

            @Override
            public void onNavigateLeft() {
                onBrailleNavigateLeft();
            }

            @Override
            public void onNavigateRight() {
                onBrailleNavigateRight();
            }

            @Override
            public void onBrailleDeleteAdaptive(int granularity) {
                if (granularity == BrailleKeyboardView.GRANULARITY_CLIPBOARD) {
                    undoLastClipboardPasteOrRemoveItem();
                } else if (granularity == BrailleKeyboardView.GRANULARITY_WORD) {
                    onBrailleDeleteWord();
                } else if (granularity == BrailleKeyboardView.GRANULARITY_LINE) {
                    onBrailleDeleteLine();
                } else if (granularity == BrailleKeyboardView.GRANULARITY_PARAGRAPH) {
                    onBrailleDeleteParagraph();
                } else {
                    onBrailleDelete();
                }
            }

            @Override
            public void onBrailleNavigateAdaptive(int granularity, boolean forward) {
                if (granularity == BrailleKeyboardView.GRANULARITY_CLIPBOARD) {
                    navigateClipboardHistory(forward);
                } else if (granularity == BrailleKeyboardView.GRANULARITY_WORD) {
                    onBrailleNavigateWord(forward);
                } else if (granularity == BrailleKeyboardView.GRANULARITY_LINE) {
                    onBrailleNavigateLine(forward);
                } else if (granularity == BrailleKeyboardView.GRANULARITY_PARAGRAPH) {
                    onBrailleNavigateParagraph(forward);
                } else {
                    if (forward) onBrailleNavigateRight(); else onBrailleNavigateLeft();
                }
            }

            @Override
            public void onOpenSettings() {
                try {
                    android.content.Intent intent = new android.content.Intent(KeyboardService.this, MainActivity.class);
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {}
            }

            @Override
            public void onCopy() {
                brailleCopyToClipboard();
            }

            @Override
            public void onPaste() {
                // Jika ada item dipilih di riwayat papan klip, tempel itu; else primary clip
                pasteFromClipboardHistoryOrSystem();
            }

            @Override
            public void onClearAll() {
                // 3 jari ketuk ganda dari BrailleKeyboardView
                android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    try {
                        ic.beginBatchEdit();
                        try {
                            ic.finishComposingText();
                            ic.deleteSurroundingText(10000, 10000);
                            // Juga coba select-all + delete biar lebih andal di beberapa field
                            ic.performContextMenuAction(android.R.id.selectAll);
                            ic.commitText("", 1);
                        } finally {
                            ic.endBatchEdit();
                        }
                    } catch (Throwable t) {
                        try { ic.deleteSurroundingText(10000, 10000); } catch (Throwable ignored) {}
                    }
                }
            }
        }, prefs, vibrator);
    }

    // Riwayat papan klip (maks 100 item)
    private final java.util.ArrayList<String> clipboardHistory = new java.util.ArrayList<>();
    private int clipboardHistoryIndex = 0;
    private long lastClipboardClearTsSeen = 0L;
    /** Panjang teks terakhir yang ditempel dari papan klip (untuk undo di mode papan klip). */
    private int lastClipboardPasteLength = 0;

    private void pushClipboardHistory(String text) {
        if (text == null) return;
        text = text.trim();
        if (text.isEmpty()) return;
        // Hapus duplikat sama di list
        clipboardHistory.remove(text);
        clipboardHistory.add(0, text);
        while (clipboardHistory.size() > 100) {
            clipboardHistory.remove(clipboardHistory.size() - 1);
        }
        clipboardHistoryIndex = 0;
        saveClipboardHistory();
    }

    private void loadClipboardHistory() {
        try {
            clipboardHistory.clear();
            org.json.JSONArray arr = new org.json.JSONArray(prefs.getString("braille_clipboard_history_json", "[]"));
            for (int i = 0; i < arr.length() && i < 100; i++) {
                String item = arr.optString(i, "");
                if (item != null && !item.trim().isEmpty() && !clipboardHistory.contains(item)) clipboardHistory.add(item);
            }
        } catch (Throwable ignored) { clipboardHistory.clear(); }
    }

    private void saveClipboardHistory() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String item : clipboardHistory) arr.put(item);
            prefs.edit().putString("braille_clipboard_history_json", arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    /** Hapus seluruh riwayat papan klip (dipanggil dari pengaturan). */
    public void clearClipboardHistory() {
        clipboardHistory.clear();
        clipboardHistoryIndex = 0;
        if (prefs != null) {
            prefs.edit().putString("braille_clipboard_history_json", "[]").apply();
        }
    }

    private void syncClipboardFromSystem() {
        try {
            android.content.ClipboardManager cb =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb == null || !cb.hasPrimaryClip()) return;
            android.content.ClipData clip = cb.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence t = clip.getItemAt(0).getText();
            if (t != null && t.length() > 0) {
                pushClipboardHistory(t.toString());
            }
        } catch (Throwable ignored) {}
    }

    private void navigateClipboardHistory(boolean forward) {
        // Hormati perintah hapus dari Pengaturan Keyboard
        if (prefs != null) {
            long ts = prefs.getLong("braille_clipboard_clear_ts", 0L);
            if (ts > 0 && ts != lastClipboardClearTsSeen) {
                lastClipboardClearTsSeen = ts;
                clipboardHistory.clear();
                clipboardHistoryIndex = 0;
            }
        }
        syncClipboardFromSystem();
        if (clipboardHistory.isEmpty()) {
            if (brailleTTSManager != null) brailleTTSManager.speak("Papan klip kosong", true);
            return;
        }
        if (forward) {
            clipboardHistoryIndex = (clipboardHistoryIndex + 1) % clipboardHistory.size();
        } else {
            clipboardHistoryIndex = (clipboardHistoryIndex - 1 + clipboardHistory.size()) % clipboardHistory.size();
        }
        String item = clipboardHistory.get(clipboardHistoryIndex);
        String preview = item.length() > 80 ? item.substring(0, 80) + "…" : item;
        if (brailleTTSManager != null) {
            brailleTTSManager.speak(
                    "Item " + (clipboardHistoryIndex + 1) + " dari " + clipboardHistory.size() + ". " + preview,
                    true);
        }
    }

    private void pasteFromClipboardHistoryOrSystem() {
        try {
            String toPaste = null;
            if (!clipboardHistory.isEmpty()
                    && clipboardHistoryIndex >= 0
                    && clipboardHistoryIndex < clipboardHistory.size()) {
                toPaste = clipboardHistory.get(clipboardHistoryIndex);
            }
            if (toPaste == null || toPaste.isEmpty()) {
                // Fallback primary clip sistem + bacakan isinya
                try {
                    android.content.ClipboardManager cb =
                            (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cb != null && cb.hasPrimaryClip()) {
                        android.content.ClipData clip = cb.getPrimaryClip();
                        if (clip != null && clip.getItemCount() > 0) {
                            CharSequence t = clip.getItemAt(0).getText();
                            if (t != null) toPaste = t.toString();
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (toPaste == null || toPaste.isEmpty()) {
                if (brailleTTSManager != null) brailleTTSManager.speak("Papan klip kosong", true);
                return;
            }
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.commitText(toPaste, 1);
                lastClipboardPasteLength = toPaste.length();
                // Bacakan seutuhnya (chunk otomatis di BrailleTTSManager)
                if (brailleTTSManager != null) brailleTTSManager.speak(toPaste.trim(), true);
            }
        } catch (Throwable t) {
            if (brailleTTSManager != null) brailleTTSManager.speak("Gagal menempel", true);
        }
    }

    /**
     * Mode papan klip + usap atas:
     * 1) Jika baru saja menempel → hapus teks yang baru ditempel di kotak edit.
     * 2) Jika tidak → hapus item yang sedang dipilih dari riwayat papan klip.
     */
    private void undoLastClipboardPasteOrRemoveItem() {
        try {
            if (lastClipboardPasteLength > 0) {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    ic.deleteSurroundingText(lastClipboardPasteLength, 0);
                    if (brailleTTSManager != null) {
                        brailleTTSManager.speak("Tempelan dibatalkan", true);
                    }
                }
                lastClipboardPasteLength = 0;
                return;
            }
            if (!clipboardHistory.isEmpty()
                    && clipboardHistoryIndex >= 0
                    && clipboardHistoryIndex < clipboardHistory.size()) {
                String removed = clipboardHistory.remove(clipboardHistoryIndex);
                if (clipboardHistoryIndex >= clipboardHistory.size()) {
                    clipboardHistoryIndex = Math.max(0, clipboardHistory.size() - 1);
                }
                String preview = removed.length() > 40 ? removed.substring(0, 40) + "…" : removed;
                if (brailleTTSManager != null) {
                    brailleTTSManager.speak("Dihapus dari papan klip. " + preview, true);
                }
            } else {
                if (brailleTTSManager != null) brailleTTSManager.speak("Papan klip kosong", true);
            }
        } catch (Throwable t) {
            if (brailleTTSManager != null) brailleTTSManager.speak("Gagal menghapus", true);
        }
    }

    /** Salin teks terpilih, atau teks sebelum kursor, ke papan klip + riwayat. */
    private void brailleCopyToClipboard() {
        try {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            CharSequence selected = ic.getSelectedText(0);
            String toCopy = null;
            if (selected != null && selected.length() > 0) {
                toCopy = selected.toString();
            } else {
                CharSequence before = ic.getTextBeforeCursor(4000, 0);
                if (before != null && before.length() > 0) {
                    toCopy = before.toString();
                }
            }
            if (toCopy == null || toCopy.isEmpty()) {
                if (brailleTTSManager != null) brailleTTSManager.speak("Tidak ada teks untuk disalin", true);
                return;
            }
            android.content.ClipboardManager cb =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(android.content.ClipData.newPlainText("braille_copy", toCopy));
            }
            pushClipboardHistory(toCopy);
            if (brailleTTSManager != null) {
                String preview = toCopy.length() > 40 ? toCopy.substring(0, 40) + "…" : toCopy;
                brailleTTSManager.speak("Disalin. " + preview, true);
            }
        } catch (Throwable t) {
            if (brailleTTSManager != null) brailleTTSManager.speak("Gagal menyalin", true);
        }
    }

    private void onBrailleSpace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence textBefore = ic.getTextBeforeCursor(100, 0);
        String wordToSpeak = "";
        if (textBefore != null && textBefore.length() > 0) {
            String s = textBefore.toString();
            int lastBreak = -1;
            for (int i = s.length() - 1; i >= 0; i--) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\t') {
                    lastBreak = i;
                    break;
                }
            }
            if (lastBreak >= 0 && lastBreak < s.length() - 1) {
                wordToSpeak = s.substring(lastBreak + 1).trim();
            } else if (lastBreak < 0) {
                wordToSpeak = s.trim();
            }
        }
        commitBrailleTextSafe(" ");
        String spoken = !wordToSpeak.isEmpty() ? wordToSpeak : "Spasi";
        if (brailleTTSManager != null) {
            brailleTTSManager.speak(spoken, true);
        }
    }

    /**
     * Minta ExtractedText dengan kapasitas besar agar navigasi paragraf/baris
     * dan baca teks panjang tidak terpotong di host app (WhatsApp, Docs, dll).
     * hintMaxChars ~100_000 mencakup ribuan paragraf praktis.
     */
    private android.view.inputmethod.ExtractedText getFullExtractedText(InputConnection ic) {
        if (ic == null) return null;
        try {
            android.view.inputmethod.ExtractedTextRequest req =
                    new android.view.inputmethod.ExtractedTextRequest();
            req.hintMaxChars = 100000;
            req.hintMaxLines = 10000;
            req.flags = 0;
            return ic.getExtractedText(req, 0);
        } catch (Throwable t) {
            try {
                return ic.getExtractedText(new android.view.inputmethod.ExtractedTextRequest(), 0);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private String getReadableCharName(String ch) {
        if (ch == null || ch.isEmpty()) return "";
        if (" ".equals(ch)) return "Spasi";
        if ("\n".equals(ch)) return "Baris baru";
        if ("\t".equals(ch)) return "Tab";
        if (".".equals(ch)) return "Titik";
        if (",".equals(ch)) return "Koma";
        if ("?".equals(ch)) return "Tanda tanya";
        if ("!".equals(ch)) return "Tanda seru";
        if ("-".equals(ch)) return "Tanda hubung";
        if ("_".equals(ch)) return "Garis bawah";
        if ("@".equals(ch)) return "Tanda et";
        if (":".equals(ch)) return "Titik dua";
        if (";".equals(ch)) return "Titik koma";
        if ("\"".equals(ch)) return "Tanda petik";
        if ("'".equals(ch)) return "Petik satu";
        if ("/".equals(ch)) return "Garis miring";
        if ("\\".equals(ch)) return "Garis miring terbalik";
        if ("(".equals(ch)) return "Buka kurung";
        if (")".equals(ch)) return "Tutup kurung";
        if ("[".equals(ch)) return "Buka kurung siku";
        if ("]".equals(ch)) return "Tutup kurung siku";
        if ("+".equals(ch)) return "Tambah";
        if ("=".equals(ch)) return "Sama dengan";
        if ("*".equals(ch)) return "Bintang";
        if ("%".equals(ch)) return "Persen";
        if (Character.isUpperCase(ch.charAt(0))) {
            return "Kapital " + ch;
        }
        return ch;
    }

    private void onBrailleNavigateLeft() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        try {
            android.view.inputmethod.ExtractedText et = getFullExtractedText(ic);
            if (et != null && et.text != null) {
                int cur = et.selectionStart;
                if (cur > 0) {
                    int newPos = cur - 1;
                    ic.setSelection(newPos, newPos);
                    CharSequence charBefore = ic.getTextBeforeCursor(1, 0);
                    if (charBefore != null && charBefore.length() > 0) {
                        String spoken = getReadableCharName(charBefore.toString());
                        if (brailleTTSManager != null) brailleTTSManager.speak(spoken, true);
                    } else {
                        if (brailleTTSManager != null) brailleTTSManager.speak("Awal teks", true);
                    }
                    return;
                } else {
                    if (brailleTTSManager != null) brailleTTSManager.speak("Awal teks", true);
                    return;
                }
            }
        } catch (Exception e) {}

        // Fallback jika ExtractedText null
        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT));
        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT));
        CharSequence charBefore = ic.getTextBeforeCursor(1, 0);
        if (charBefore != null && charBefore.length() > 0) {
            String spoken = getReadableCharName(charBefore.toString());
            if (brailleTTSManager != null) brailleTTSManager.speak(spoken, true);
        } else {
            if (brailleTTSManager != null) brailleTTSManager.speak("Awal teks", true);
        }
    }

    private void onBrailleNavigateRight() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        try {
            android.view.inputmethod.ExtractedText et = getFullExtractedText(ic);
            if (et != null && et.text != null) {
                int cur = et.selectionStart;
                int len = et.text.length();
                if (cur < len) {
                    int newPos = cur + 1;
                    ic.setSelection(newPos, newPos);
                    CharSequence charBefore = ic.getTextBeforeCursor(1, 0);
                    if (charBefore != null && charBefore.length() > 0) {
                        String spoken = getReadableCharName(charBefore.toString());
                        if (brailleTTSManager != null) brailleTTSManager.speak(spoken, true);
                    } else {
                        if (brailleTTSManager != null) brailleTTSManager.speak("Akhir teks", true);
                    }
                    return;
                } else {
                    if (brailleTTSManager != null) brailleTTSManager.speak("Akhir teks", true);
                    return;
                }
            }
        } catch (Exception e) {}

        // Fallback jika ExtractedText null
        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
        CharSequence charBefore = ic.getTextBeforeCursor(1, 0);
        if (charBefore != null && charBefore.length() > 0) {
            String spoken = getReadableCharName(charBefore.toString());
            if (brailleTTSManager != null) brailleTTSManager.speak(spoken, true);
        } else {
            if (brailleTTSManager != null) brailleTTSManager.speak("Akhir teks", true);
        }
    }

    private void onBrailleDelete() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String spoken = "Hapus";
        try {
            CharSequence charBefore = ic.getTextBeforeCursor(1, 0);
            if (charBefore != null && charBefore.length() > 0) {
                spoken = getReadableCharName(charBefore.toString()) + " dihapus";
            }
        } catch (Throwable ignored) {}
        deleteBrailleCharSafe();
        if (brailleTTSManager != null) {
            brailleTTSManager.speak(spoken, true);
        }
    }

    private void onBrailleDeleteWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence text = ic.getTextBeforeCursor(100, 0);
        String deletedWord = "";
        int deleteCount = 0;
        if (text != null && text.length() > 0) {
            String s = text.toString();
            int len = s.length();
            if (s.charAt(len - 1) == ' ') {
                while (len - 1 - deleteCount >= 0 && s.charAt(len - 1 - deleteCount) == ' ') {
                    deleteCount++;
                }
            }
            while (len - 1 - deleteCount >= 0 && s.charAt(len - 1 - deleteCount) != ' ' && s.charAt(len - 1 - deleteCount) != '\n') {
                deleteCount++;
            }
            if (deleteCount == 0) deleteCount = 1;
            deletedWord = s.substring(len - deleteCount).trim();
            ic.deleteSurroundingText(deleteCount, 0);
        }
        String spoken = !deletedWord.isEmpty() ? deletedWord + " dihapus" : "Hapus kata";
        if (brailleTTSManager != null) {
            brailleTTSManager.speak(spoken, true);
        }
    }

    private void onBrailleDeleteLine() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence text = ic.getTextBeforeCursor(2000, 0);
        if (text != null && text.length() > 0) {
            String s = text.toString();
            int lastNl = s.lastIndexOf('\n');
            int deleteCount = (lastNl >= 0) ? (s.length() - 1 - lastNl) : s.length();
            if (deleteCount == 0 && lastNl >= 0) {
                deleteCount = 1;
            }
            if (deleteCount > 0) {
                ic.deleteSurroundingText(deleteCount, 0);
                if (brailleTTSManager != null) brailleTTSManager.speak("Baris dihapus", true);
                return;
            }
        }
        if (brailleTTSManager != null) brailleTTSManager.speak("Awal teks", true);
    }

    private void onBrailleDeleteParagraph() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence text = ic.getTextBeforeCursor(5000, 0);
        if (text != null && text.length() > 0) {
            String s = text.toString();
            int lastPara = s.lastIndexOf("\n\n");
            int deleteCount = 0;
            if (lastPara >= 0) {
                deleteCount = s.length() - (lastPara + 2);
            } else {
                int lastNl = s.lastIndexOf('\n');
                deleteCount = (lastNl >= 0) ? (s.length() - 1 - lastNl) : s.length();
            }
            if (deleteCount == 0) deleteCount = 1;
            ic.deleteSurroundingText(deleteCount, 0);
            if (brailleTTSManager != null) brailleTTSManager.speak("Paragraf dihapus", true);
            return;
        }
        if (brailleTTSManager != null) brailleTTSManager.speak("Awal teks", true);
    }

    private void onBrailleNavigateWord(boolean forward) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        try {
            android.view.inputmethod.ExtractedText et = getFullExtractedText(ic);
            if (et != null && et.text != null) {
                String s = et.text.toString();
                int cur = et.selectionStart;
                int len = s.length();
                if (!forward) {
                    if (cur <= 0) {
                        if (brailleTTSManager != null) brailleTTSManager.speak("Awal teks", true);
                        return;
                    }
                    int i = cur - 1;
                    while (i >= 0 && Character.isWhitespace(s.charAt(i))) {
                        i--;
                    }
                    int wordEnd = i + 1;
                    while (i >= 0 && !Character.isWhitespace(s.charAt(i))) {
                        i--;
                    }
                    int wordStart = i + 1;
                    if (wordStart < cur) {
                        ic.setSelection(wordStart, wordStart);
                        String spokenWord = s.substring(wordStart, Math.min(len, wordEnd)).trim();
                        if (brailleTTSManager != null) {
                            brailleTTSManager.speak(!spokenWord.isEmpty() ? spokenWord : "Spasi", true);
                        }
                        return;
                    }
                } else {
                    if (cur >= len) {
                        if (brailleTTSManager != null) brailleTTSManager.speak("Akhir teks", true);
                        return;
                    }
                    int i = cur;
                    while (i < len && Character.isWhitespace(s.charAt(i))) {
                        i++;
                    }
                    int wordStart = i;
                    while (i < len && !Character.isWhitespace(s.charAt(i))) {
                        i++;
                    }
                    int wordEnd = i;
                    if (wordEnd > cur) {
                        ic.setSelection(wordEnd, wordEnd);
                        String spokenWord = s.substring(wordStart, Math.min(len, wordEnd)).trim();
                        if (brailleTTSManager != null) {
                            brailleTTSManager.speak(!spokenWord.isEmpty() ? spokenWord : "Spasi", true);
                        }
                        return;
                    }
                }
            }
        } catch (Exception e) {}

        CharSequence textBefore = ic.getTextBeforeCursor(100, 0);
        CharSequence textAfter = ic.getTextAfterCursor(100, 0);
        if (!forward && textBefore != null && textBefore.length() > 0) {
            String s = textBefore.toString().trim();
            int idx = s.lastIndexOf(' ');
            String word = (idx >= 0) ? s.substring(idx + 1) : s;
            if (brailleTTSManager != null) brailleTTSManager.speak(word, true);
        } else if (forward && textAfter != null && textAfter.length() > 0) {
            String s = textAfter.toString().trim();
            int idx = s.indexOf(' ');
            String word = (idx >= 0) ? s.substring(0, idx) : s;
            if (brailleTTSManager != null) brailleTTSManager.speak(word, true);
        }
    }

    private void onBrailleNavigateLine(boolean forward) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        try {
            android.view.inputmethod.ExtractedText et = getFullExtractedText(ic);
            if (et != null && et.text != null) {
                String s = et.text.toString();
                int cur = et.selectionStart;
                int len = s.length();
                if (!forward) {
                    if (cur <= 0) {
                        if (brailleTTSManager != null) brailleTTSManager.speak("Awal teks", true);
                        return;
                    }
                    int lastNl = s.lastIndexOf('\n', Math.max(0, cur - 1));
                    int prevNl = (lastNl > 0) ? s.lastIndexOf('\n', lastNl - 1) : -1;
                    int lineStart = prevNl + 1;
                    int lineEnd = (lastNl >= 0) ? lastNl : cur;
                    ic.setSelection(lineStart, lineStart);
                    String lineText = (lineEnd > lineStart) ? s.substring(lineStart, lineEnd).trim() : "";
                    if (brailleTTSManager != null) {
                        brailleTTSManager.speak(!lineText.isEmpty() ? lineText : "Baris kosong", true);
                    }
                    return;
                } else {
                    if (cur >= len) {
                        if (brailleTTSManager != null) brailleTTSManager.speak("Akhir teks", true);
                        return;
                    }
                    int nextNl = s.indexOf('\n', cur);
                    if (nextNl >= 0) {
                        int lineStart = nextNl + 1;
                        int nextNextNl = s.indexOf('\n', lineStart);
                        int lineEnd = (nextNextNl >= 0) ? nextNextNl : len;
                        ic.setSelection(lineStart, lineStart);
                        String lineText = (lineEnd > lineStart) ? s.substring(lineStart, lineEnd).trim() : "";
                        if (brailleTTSManager != null) {
                            brailleTTSManager.speak(!lineText.isEmpty() ? lineText : "Baris kosong", true);
                        }
                        return;
                    } else {
                        ic.setSelection(len, len);
                        if (brailleTTSManager != null) brailleTTSManager.speak("Akhir teks", true);
                        return;
                    }
                }
            }
        } catch (Exception e) {}
    }

    private void onBrailleNavigateParagraph(boolean forward) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        try {
            android.view.inputmethod.ExtractedText et = getFullExtractedText(ic);
            if (et != null && et.text != null) {
                String s = et.text.toString();
                int cur = et.selectionStart;
                int len = s.length();
                if (!forward) {
                    if (cur <= 0) {
                        if (brailleTTSManager != null) brailleTTSManager.speak("Awal teks", true);
                        return;
                    }
                    int lastPara = s.lastIndexOf("\n\n", Math.max(0, cur - 1));
                    if (lastPara < 0) lastPara = s.lastIndexOf('\n', Math.max(0, cur - 1));
                    int prevPara = (lastPara > 0) ? s.lastIndexOf("\n\n", lastPara - 1) : -1;
                    if (prevPara < 0 && lastPara > 0) prevPara = s.lastIndexOf('\n', lastPara - 1);
                    int pStart = prevPara >= 0 ? prevPara + (s.startsWith("\n\n", prevPara) ? 2 : 1) : 0;
                    int pEnd = (lastPara >= 0) ? lastPara : cur;
                    ic.setSelection(pStart, pStart);
                    String pText = (pEnd > pStart) ? s.substring(pStart, pEnd).trim() : "";
                    if (brailleTTSManager != null) {
                        brailleTTSManager.speak(!pText.isEmpty() ? pText : "Paragraf kosong", true);
                    }
                    return;
                } else {
                    if (cur >= len) {
                        if (brailleTTSManager != null) brailleTTSManager.speak("Akhir teks", true);
                        return;
                    }
                    int nextPara = s.indexOf("\n\n", cur);
                    if (nextPara < 0) nextPara = s.indexOf('\n', cur);
                    if (nextPara >= 0) {
                        int pStart = nextPara + (s.startsWith("\n\n", nextPara) ? 2 : 1);
                        int nextNextPara = s.indexOf("\n\n", pStart);
                        if (nextNextPara < 0) nextNextPara = s.indexOf('\n', pStart);
                        int pEnd = (nextNextPara >= 0) ? nextNextPara : len;
                        ic.setSelection(pStart, pStart);
                        String pText = (pEnd > pStart) ? s.substring(pStart, pEnd).trim() : "";
                        if (brailleTTSManager != null) {
                            brailleTTSManager.speak(!pText.isEmpty() ? pText : "Paragraf kosong", true);
                        }
                        return;
                    } else {
                        ic.setSelection(len, len);
                        if (brailleTTSManager != null) brailleTTSManager.speak("Akhir teks", true);
                        return;
                    }
                }
            }
        } catch (Exception e) {}
    }

    private void mulaiVoiceInputUmum() {
        vibrate(50);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (prefs.getBoolean("use_whisper", false)) {
                    if (isRecordingWhisper) stopRekamWhisper(); else mulaiRekamWhisper();
                } else {
                    mulaiGoogleSTT();
                }
            }
        });
    }

    private void tempelDariClipboard() {

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        if (clipboard != null && clipboard.hasPrimaryClip()) {

            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence pasteText = clip.getItemAt(0).getText();
                if (pasteText != null) {
                    playCustomSound("nempel"); vibrate(100);
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {

                        ic.commitText(pasteText, 1); checkAutoCapitalization();

                        Toast.makeText(this, "Teks Ditempel", Toast.LENGTH_SHORT).show();

                    }












                }

            }

        }

    }



    // sistem hitung posisi jari lu ada di tombol mana

    private Button findButtonAtLocation(ViewGroup parent, float x, float y) {

        for (int i = parent.getChildCount() - 1; i >= 0; i--) {

            View child = parent.getChildAt(i);

            if (child.getVisibility() == View.VISIBLE) {

                float scrollX = parent.getScrollX();

                float scrollY = parent.getScrollY();

                if (x + scrollX >= child.getLeft() && x + scrollX <= child.getRight() &&

                    y + scrollY >= child.getTop() && y + scrollY <= child.getBottom()) {



                    if (child instanceof Button) {

                        return (Button) child;

                    } else if (child instanceof ViewGroup) {

                        float childX = x + scrollX - child.getLeft();

                        float childY = y + scrollY - child.getTop();

                        Button b = findButtonAtLocation((ViewGroup) child, childX, childY);

                        if (b != null) return b;

                    }

                }

            }

        }

        return null;

    }



    private boolean isDeleteButton(Button btn) {
        if (btn == null) return false;
        int id = btn.getId();
        return id == R.id.btnHapusEmoji || id == R.id.btnHapusFont;
    }

    private boolean isSpaceButton(Button btn) {
        if (btn == null) return false;
        return false;
    }



    private String getTypingMode() {
        if (prefs == null) prefs = getSharedPreferences("VoiceInput_Settings", MODE_PRIVATE);
        return prefs.getString("akses_ngetik_mode", "Angkat Jari (Standar)");
    }

    private boolean isGboardTypingMode() {
        return CustomKeyboardView.MODE_GBOARD.equals(getTypingMode());
    }

    /**
     * Mode Percobaan (Gaya Gboard): pembungkus utama keyboard tidak boleh ikut jadi "tombol"
     * atau merebut fokus pembaca layar, supaya fokus hanya dipegang tombol-tombol keyboard.
     * Mode lain dikembalikan persis seperti semula.
     */
    private void applyTypingModeToRoot() {
        try {
            if (rootKeyboardView == null) return;
            boolean gb = !isBrailleModeActive && isGboardTypingMode();
            if (gb) {
                rootKeyboardView.setOnHoverListener(null);
                rootKeyboardView.setOnTouchListener(null);
                rootKeyboardView.setOnClickListener(null);
                rootKeyboardView.setClickable(false);
                rootKeyboardView.setFocusable(false);
                rootKeyboardView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            } else if (legacyRootClick != null && legacyRootTouch != null) {
                rootKeyboardView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                rootKeyboardView.setOnClickListener(legacyRootClick);
                rootKeyboardView.setOnTouchListener(legacyRootTouch);
                rootKeyboardView.setOnHoverListener(globalHoverListener);
                rootKeyboardView.setClickable(true);
                rootKeyboardView.setFocusable(true);
            }
        } catch (Throwable ignored) {}
    }

    // pusat logika buat nangkep sentuhan lu di layar utama
    private View.OnHoverListener globalHoverListener = new View.OnHoverListener() {

        @Override

        public boolean onHover(View v, MotionEvent event) {

            if (!isAksesibilitasAktif() || !(v instanceof ViewGroup)) return false;



            // ngunci fokus Jieshuo biar gak bablas ke aplikasi lain kayak WA

            if (!v.isAccessibilityFocused()) {

                v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);

            }



            int action = event.getActionMasked();

            Button btn = findButtonAtLocation((ViewGroup) v, event.getX(), event.getY());



            if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {

                if (btn != null && btn != currentlyHoveredBtn) {

                    if (currentlyHoveredBtn != null) currentlyHoveredBtn.setPressed(false);

                    currentlyHoveredBtn = btn;

                    btn.setPressed(true);



                    CharSequence desc = btn.getContentDescription();

                    announceForAccessibility(desc != null ? desc.toString() : btn.getText().toString());



                    stopHapusBeruntun();

                    hapusHandler.removeCallbacks(runnableSpaceLongPress);

                    if (isDeleteButton(btn)) hapusHandler.postDelayed(runnableHapusKata, 2000);
                    if (isSpaceButton(btn)) hapusHandler.postDelayed(runnableSpaceLongPress, 5000);



                    ButtonAction ba = (ButtonAction) btn.getTag();

                    if (ba != null) {
                        if (ba.requiresDoubleTap || !getTypingMode().equals("Angkat Jari (Standar)")) {
                            targetDoubleTapBtn = btn;
                        } else {
                            targetDoubleTapBtn = null;
                        }
                    } else {
                        targetDoubleTapBtn = null;
                    }

                } else if (btn == null && currentlyHoveredBtn != null) {

                    currentlyHoveredBtn.setPressed(false);

                    currentlyHoveredBtn = null;

                    stopHapusBeruntun();

                    hapusHandler.removeCallbacks(runnableSpaceLongPress);

                }

            } else if (action == MotionEvent.ACTION_HOVER_EXIT) {

                stopHapusBeruntun();
                hapusHandler.removeCallbacks(runnableSpaceLongPress);

                if (currentlyHoveredBtn != null) {

                    currentlyHoveredBtn.setPressed(false);

                    ButtonAction ba = (ButtonAction) currentlyHoveredBtn.getTag();



                    // kalau bukan emoji, ngetik pas jari diangkat (JIKA MODE ANGKAT JARI)

                    String mode = getTypingMode();

                    if (ba != null && !ba.requiresDoubleTap && !isRestingOnBackspace) {

                        if (mode.equals("Angkat Jari (Standar)")) {
                            playCustomSound("ketik"); vibrate(40);
                            ba.action.run();
                            targetDoubleTapBtn = null;
                        } else {
                            // Mode Ketuk Ganda, tahan dulu jgn langsung ngetik
                            targetDoubleTapBtn = currentlyHoveredBtn;
                        }

                    } else if (ba != null && ba.requiresDoubleTap) {

                        // kalau emoji, biarin aja biar Jieshuo yg double tap

                        targetDoubleTapBtn = currentlyHoveredBtn;

                    }

                    

                    currentlyHoveredBtn = null;

                    isRestingOnBackspace = false;

                }

            }

            return true; // Mencegah event bocor

        }

    };



    // setup tombol standar (qwerty, spasi, hapus)

    private void setupLiftToType(final Button b, final Runnable actionClick) {

        if (b == null) return;

        b.setTag(new ButtonAction(actionClick, false)); 

        applyAccessibilityLogic(b, actionClick);

    }



    private void applyAccessibilityLogic(final Button b, final Runnable actionClick) {

        b.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        b.setClickable(false);

        b.setFocusable(false);



        b.setOnTouchListener(new View.OnTouchListener() {

            @Override

            public boolean onTouch(View v, MotionEvent event) {


                if (!isAksesibilitasAktif()) {

                    if (event.getAction() == MotionEvent.ACTION_DOWN) {




                        v.setPressed(true);

                        if (isDeleteButton(b)) hapusHandler.postDelayed(runnableHapusKata, 2000); 

                        if (isSpaceButton(b)) {

                            hapusHandler.postDelayed(runnableSpaceLongPress, 5000);

                        }

                        return true;

                    } else if (event.getAction() == MotionEvent.ACTION_UP) {

                        v.setPressed(false);

                        stopHapusBeruntun();

                        hapusHandler.removeCallbacks(runnableSpaceLongPress);

                        

                        // Cek apakah jari masih di area tombol saat diangkat

                        float x = event.getX();

                        float y = event.getY();

                        if (x >= 0 && x <= v.getWidth() && y >= 0 && y <= v.getHeight()) {

                            playCustomSound("ketik"); vibrate(40);

                            actionClick.run();

                        }

                        return true;

                    } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {

                        v.setPressed(false);

                        stopHapusBeruntun();

                        hapusHandler.removeCallbacks(runnableSpaceLongPress);

                        return true;

                    }

                }

                return false;

            }

        });


















    }



    // logika hapus pinter (per kata kalau lu tahan)

    private void deleteLastWord() {

        InputConnection ic = getCurrentInputConnection();

        if (ic == null) return;

        CharSequence text = ic.getTextBeforeCursor(100, 0);

        if (text != null && text.length() > 0) {


            String s = text.toString(); 

            int len = s.length(); 

            int deleteCount = 0;

            

            if (s.charAt(len - 1) == ' ') {





                while (len - 1 - deleteCount >= 0 && s.charAt(len - 1 - deleteCount) == ' ') { deleteCount++; }

            }

            while (len - 1 - deleteCount >= 0 && s.charAt(len - 1 - deleteCount) != ' ') { deleteCount++; }

            

            if (deleteCount == 0) deleteCount = 1;

            ic.deleteSurroundingText(deleteCount, 0);

        }

        syncQwertyWordBufFromCursor(ic);
        checkAutoCapitalization();

    }




    private Runnable runnableHapusKata = new Runnable() {
        @Override
        public void run() {
            if (isAksesibilitasAktif()) isRestingOnBackspace = true;
            playCustomSound("ketik"); vibrate(40);
            deleteLastWord(); 
            hapusHandler.postDelayed(this, prefs.getInt("delay_hapus_ms", 600));
        }
    };

    private Runnable runnableSpaceLongPress = new Runnable() {
        @Override
        public void run() {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showInputMethodPicker();
            }
        }
    };



    private void stopHapusBeruntun() {


        isRestingOnBackspace = false; 

        hapusHandler.removeCallbacks(runnableHapusKata);

    }



    private void setupActionHapusAman(View view, int id) {
        final Button btn = (Button) view.findViewById(id);
        if (btn == null) return;
        btn.setText("\u232b");
        btn.setContentDescription("Hapus teks");
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.deleteSurroundingText(1, 0);
                checkAutoCapitalization();
            }
        });
    }

    // bikin tampilan daftar emoji sama font

    private void populateEmojiPanel(Context ctx, int page) {
        if (containerGridEmoji == null) return;
        containerGridEmoji.removeAllViews();

        int pageIndex = page - 1;
        if (pageIndex < 0) pageIndex = 0;
        if (pageIndex >= KeyboardData.ALL_EMOJI_PAGES.length) pageIndex = KeyboardData.ALL_EMOJI_PAGES.length - 1;

        int baseHeight = prefs.getInt("keyboard_height", 140);
        float screenDensity = getResources().getDisplayMetrics().density;
        int heightInPx = (int) (baseHeight * screenDensity * 0.35f);

        String[][] pageGrid = KeyboardData.ALL_EMOJI_PAGES[pageIndex];
        for (String[] row : pageGrid) {
            LinearLayout barisAktif = new LinearLayout(ctx);
            barisAktif.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lpRow = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightInPx);
            containerGridEmoji.addView(barisAktif, lpRow);

            for (final String emj : row) {
                Button btn = new Button(ctx);
                btn.setText(emj);
                btn.setTextSize(22);
                btn.setBackgroundColor(Color.TRANSPARENT);
                btn.setContentDescription(emj);
                LinearLayout.LayoutParams lpBtn = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
                btn.setLayoutParams(lpBtn);
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) ic.commitText(emj, 1);
                        playCustomSound("ketik");
                        vibrate(20);
                    }
                });
                barisAktif.addView(btn);
            }
        }
    }



    private void populateFontPanel(Context ctx) {
        if (containerDaftarFont == null) return;
        containerDaftarFont.removeAllViews();
        
        for (int i = 0; i < KeyboardData.FONT_NAMES.length; i++) {
            final int fIndex = i;
            final String fNama = KeyboardData.FONT_NAMES[i];
            
            Button btn = new Button(ctx);

            btn.setText(fNama);

            btn.setTextColor(Color.WHITE);

            btn.setBackgroundColor(Color.parseColor("#3B3B3B"));


            btn.setContentDescription(fNama); 

























            btn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    currentFontIndex = fIndex;
                    if (customKeyboardView != null) {
                        customKeyboardView.setFontIndex(currentFontIndex);
                    }
                    updateKeyboardCase();
                    sembunyikanSemuaLayer();
                    Toast.makeText(ctx, "Gaya Huruf: " + fNama + " aktif", Toast.LENGTH_SHORT).show();
                }
            });



            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(350, 150);
            lp.setMargins(10, 10, 10, 10);
            containerDaftarFont.addView(btn, lp);

        }

    }








    @Override
    public View onCreateInputView() {
        View view = getLayoutInflater().inflate(R.layout.layout_keyboard, null);














        android.widget.FrameLayout keyboardContainer = (android.widget.FrameLayout) view.findViewById(R.id.keyboard_container);
        this.keyboardContainerLayout = keyboardContainer;
        this.rootKeyboardView = view;
        android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(android.content.Context.VIBRATOR_SERVICE);
        customKeyboardView = new CustomKeyboardView(this, this, prefs, vibrator);
        initBrailleKeyboardView();

        if (keyboardContainer != null) {
            if (prefs.getBoolean("braille_default_mode", false)) {
                switchToBrailleKeyboard();
            } else {
                switchToQwertyKeyboard();
            }
        }

        

        // layar utama memonopoli pembaca layar biar gak rewel

        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        view.setClickable(true);

        view.setFocusable(true);

        

        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {

            @Override

            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {

                super.onInitializeAccessibilityNodeInfo(host, info);

                info.setClassName(View.class.getName()); 

            }

        });

        

        view.setOnHoverListener(globalHoverListener);

        

        // pas lu double tap pake Jieshuo, eksekusi tombol
        final android.view.GestureDetector doubleTapDetector = new android.view.GestureDetector(KeyboardService.this,
            new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    String mode = getTypingMode();
                    if (!mode.equals("Angkat Jari (Standar)")) {
                        if (customKeyboardView != null) {
                            customKeyboardView.performClick();
                        } else if (targetDoubleTapBtn != null) {
                            ButtonAction ba = (ButtonAction) targetDoubleTapBtn.getTag();
                            if (ba != null) {
                                playCustomSound("ketik"); vibrate(40);
                                ba.action.run();
                            }
                        }
                    }
                    return true;
                }
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    // Ketuk Ganda mode: single tap di luar tombol tidak dilakukan apa-apa
                    return true;
                }
            });
        legacyRootClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Jieshuo accessibility: double tap dikirim sebagai onClick
                String mode = getTypingMode();
                if (!mode.equals("Angkat Jari (Standar)")) {
                    if (customKeyboardView != null) {
                        customKeyboardView.performClick();
                    } else if (isAksesibilitasAktif() && targetDoubleTapBtn != null) {
                        ButtonAction ba = (ButtonAction) targetDoubleTapBtn.getTag();
                        if (ba != null) {
                            playCustomSound("ketik"); vibrate(40);
                            ba.action.run();
                        }
                    }
                }
            }
        };
        view.setOnClickListener(legacyRootClick);
        legacyRootTouch = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // Teruskan ke GestureDetector untuk deteksi double tap manual (tanpa screen reader)
                if (!isAksesibilitasAktif()) {
                    doubleTapDetector.onTouchEvent(event);
                }
                return false; // biar hover listener juga tetap jalan
            }
        };
        view.setOnTouchListener(legacyRootTouch);

        layoutEmoji = (LinearLayout) view.findViewById(R.id.layoutEmoji);
        layoutFont = (LinearLayout) view.findViewById(R.id.layoutFont);
        containerGridEmoji = (LinearLayout) view.findViewById(R.id.containerGridEmoji);
        containerDaftarFont = (LinearLayout) view.findViewById(R.id.containerDaftarFont);



        if (layoutEmoji != null) {
            android.widget.HorizontalScrollView tabScroll = new android.widget.HorizontalScrollView(this);
            tabScroll.setHorizontalScrollBarEnabled(false);
            final LinearLayout tabRow = new LinearLayout(this);
            tabRow.setOrientation(LinearLayout.HORIZONTAL);
            tabRow.setBackgroundColor(Color.parseColor("#1E1E1E"));

            final Button[] tabButtons = new Button[KeyboardData.ALL_EMOJI_PAGES.length];
            for (int p = 0; p < KeyboardData.ALL_EMOJI_PAGES.length; p++) {
                final int pageNum = p + 1;
                final Button tabBtn = new Button(this);
                String title = (pageNum <= KeyboardData.EMOJI_PAGE_NAMES.length) ? KeyboardData.EMOJI_PAGE_NAMES[p] : ("Hal " + pageNum);
                tabBtn.setText(title);
                tabBtn.setTextColor(Color.WHITE);
                tabBtn.setTextSize(11);
                tabBtn.setBackgroundColor(Color.parseColor(p == 0 ? "#424242" : "#2C2C2C"));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(4, 4, 4, 4);
                tabBtn.setLayoutParams(lp);
                tabButtons[p] = tabBtn;

                tabBtn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        populateEmojiPanel(KeyboardService.this, pageNum);
                        for (int k = 0; k < tabButtons.length; k++) {
                            if (tabButtons[k] != null) {
                                tabButtons[k].setBackgroundColor(Color.parseColor(k == (pageNum - 1) ? "#424242" : "#2C2C2C"));
                            }
                        }
                        announceForAccessibility("Halaman Emoji " + pageNum + " Aktif");
                    }
                });
                tabRow.addView(tabBtn);
            }
            tabScroll.addView(tabRow);
            layoutEmoji.addView(tabScroll, 0);
        }



        populateEmojiPanel(this, 1); 

        populateFontPanel(this);





        Button btnTutupEmoji = (Button) view.findViewById(R.id.btnTutupEmoji);

        if (btnTutupEmoji != null) {




            btnTutupEmoji.setContentDescription("Kembali ke huruf");

            btnTutupEmoji.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    sembunyikanSemuaLayer();
                }
            });

        }

        setupActionHapusAman(view, R.id.btnHapusEmoji);





        Button btnTutupFont = (Button) view.findViewById(R.id.btnTutupFont);

        if (btnTutupFont != null) {




            btnTutupFont.setContentDescription("Batal pilih gaya huruf");

            btnTutupFont.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    sembunyikanSemuaLayer();
                }
            });

        }

        setupActionHapusAman(view, R.id.btnHapusFont);

        setupAllSymbolButtons(view);

        // tempelButtons[0]
        if (tempelButtons[0] != null) {
            tempelButtons[0].setContentDescription("Tombol tempel");
            tempelButtons[0].setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    tempelDariClipboard();
                }
            });
        }
        
        // micButtons[0]
        inisialisasiPerekamGboard();
        for (int i=0; i<1; i++) {
            final Button mBtn = micButtons[i];
            if (mBtn != null) {
                mBtn.setContentDescription("Tombol Input Suara");
                mBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                        if (prefs.getBoolean("use_whisper", false)) {
                            if (isRecordingWhisper) stopRekamWhisper(); else mulaiRekamWhisper();
                        } else {
                            mulaiGoogleSTT();
                        }
                    }
                });
            }
        }
        
        updateKeyboardCase();
        if (isAksesibilitasAktif()) view.setOnHoverListener(globalHoverListener);
        applyTypingModeToRoot();
        return view;
    }

    private void setupAllSymbolButtons(android.view.View v) {
        if (v instanceof Button) {
            final Button b = (Button) v;
            try {
                String resName = getResources().getResourceEntryName(b.getId());
                if (resName != null && (resName.startsWith("s1_") || resName.startsWith("s2_") || resName.startsWith("s3_"))) {
                    if (resName.endsWith("_enter") || resName.endsWith("_spasi") || resName.endsWith("_tempel") || resName.endsWith("_mic")) {
                        // Skip
                    } else {
                        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                                android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
                                if (ic != null) {
                                    CharSequence text = b.getText();
                                    if (text != null && text.length() > 0) ic.commitText(text, 1);
                                }
                                checkAutoCapitalization();
                            }
                        });
                    }
                }
            } catch (Exception e) {}
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) setupAllSymbolButtons(vg.getChildAt(i));
        }
    }

    private void cekUpdateAudioInstan(boolean isAwal) {
        try {
            if (soundPool == null) return;
            String effectKey = prefs.getString("sound_effect_key", "key2");
            if ("custom".equals(effectKey)) {
                File fileKetik = new File(getFilesDir(), "custom_ketik.mp3");
                if (fileKetik.exists()) {
                    if (fileKetik.lastModified() > lastModKetik || soundKetikId == -1) {
                        lastModKetik = fileKetik.lastModified();
                        soundKetikId = soundPool.load(fileKetik.getAbsolutePath(), 1);
                    }
                }
            } else {
                // Pack assets: ambil satu file typing buat fallback (QWERTY utama di CustomKeyboardView)
                try {
                    String[] files = getAssets().list("typingsound/" + effectKey);
                    String pick = null;
                    if (files != null) {
                        for (String f : files) {
                            String low = f.toLowerCase();
                            if (low.contains("typing") || low.startsWith("type") || low.contains("letter") || (low.startsWith("s") && low.endsWith(".wav"))) {
                                pick = f; break;
                            }
                        }
                        if (pick == null && files.length > 0) pick = files[0];
                    }
                    if (pick != null) {
                        java.io.InputStream is = getAssets().open("typingsound/" + effectKey + "/" + pick);
                        File cache = new File(getCacheDir(), "ks_sfx_" + effectKey + "_" + pick.replaceAll("[^a-zA-Z0-9._-]", "_"));
                        if (!cache.exists() || cache.length() == 0) {
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(cache);
                            byte[] buf = new byte[8192]; int n;
                            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                            fos.close();
                        }
                        is.close();
                        soundKetikId = soundPool.load(cache.getAbsolutePath(), 1);
                    }
                } catch (Throwable ignored) {}
            }

            if (prefs.getString("sound_effect_key_nempel", "default").equals("custom")) {
                File fileNempel = new File(getFilesDir(), "custom_nempel.mp3");
                if (fileNempel.exists()) {
                    if (mediaPlayerNempel != null) { try { mediaPlayerNempel.release(); } catch (Exception e) {} }
                    mediaPlayerNempel = new MediaPlayer();
                    mediaPlayerNempel.setDataSource(fileNempel.getAbsolutePath());
                    mediaPlayerNempel.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    mediaPlayerNempel.prepare();
                }
            } else if (isAwal) {
                int resId = getResources().getIdentifier("penempelan", "raw", getPackageName());
                if (resId != 0) mediaPlayerNempel = MediaPlayer.create(this, resId);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void playCustomSound(String type) {
        try {
            if (type.equals("ketik") && prefs.getBoolean("sound_ketik_enabled", true)) {
                if (soundKetikId != -1 && soundPool != null) { float volume = prefs.getInt("sound_effect_volume", 70) / 100.0f; soundPool.play(soundKetikId, volume, volume, 0, 0, 1.0f); }
                else if (toneGen != null) toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 30);
            } 
            else if (type.equals("nempel") && prefs.getBoolean("sound_nempel_enabled", true)) {
                if (mediaPlayerNempel != null) {
                    if (mediaPlayerNempel.isPlaying()) mediaPlayerNempel.seekTo(0);
                    mediaPlayerNempel.start();
                } else if (toneGen != null) toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 100);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void vibrate(int duration) {
        if (prefs.getBoolean("vib_enabled", true)) {

            try {

                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

                if (v != null && v.hasVibrator()) {



                    v.vibrate(duration);

                }

            } catch (Exception e) { e.printStackTrace(); }

        }

    }

    private void onRealtimePremiumStateChanged() {
        boolean active = AuthManager.isSignedIn() && AuthManager.isPremiumActive(this);
        if (!active) {
            // Hentikan segera sesi rekam / proses AI bila Premium dicabut atau Device ID dihapus
            try {
                if (micTertunda != null) {
                    micHandler.removeCallbacks(micTertunda);
                    micTertunda = null;
                }
            } catch (Throwable ignored) {}
            if (isRecordingWhisper) {
                isRecordingWhisper = false;
                recordingOfflineWhisper = false;
                sembunyikanIndikatorMic();
                try {
                    if (mediaRecorder != null) {
                        try { mediaRecorder.stop(); } catch (Exception ignored) {}
                        try { mediaRecorder.release(); } catch (Exception ignored) {}
                        mediaRecorder = null;
                    }
                    if (offlinePcmRecorder != null) {
                        offlinePcmRecorder.stopAndDiscard();
                        offlinePcmRecorder = null;
                    }
                } catch (Throwable ignored) {}
            }
            if (aiThread != null && aiThread.isAlive()) {
                try { aiThread.interrupt(); } catch (Throwable ignored) {}
            }
        }
        refreshMicPremiumVisual();
    }

    private void refreshMicPremiumVisual() {
        final boolean allowed = AuthManager.isSignedIn() && AuthManager.isPremiumActive(this);
        final String lockedDesc = allowed ? "Input Suara AI" : ("Input Suara AI Terkunci — " + AuthManager.getAccessDeniedToast(this));
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                for (Button b : micButtons) {
                    if (b == null) continue;
                    // Pertahankan status merah "STOP AI" hanya bila sedang memproses & masih diizinkan
                    if (allowed && "STOP AI".equals(String.valueOf(b.getText()))) continue;
                    if (allowed) {
                        b.setText("Input Suara AI");
                        b.setContentDescription("Input Suara AI");
                        b.setBackgroundColor(Color.parseColor("#00838F"));
                        b.setAlpha(1.0f);
                    } else {
                        b.setText("Input Suara (Terkunci)");
                        b.setContentDescription(lockedDesc);
                        b.setBackgroundColor(Color.parseColor("#546E7A"));
                        b.setAlpha(0.75f);
                    }
                }
            }
        });
    }

    private void setMicButtonState(final boolean isProcessing) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                boolean allowed = AuthManager.isSignedIn() && AuthManager.isPremiumActive(KeyboardService.this);
                for (Button b : micButtons) {
                    if (b != null) {
                        if (isProcessing && allowed) {
                            b.setText("STOP AI"); b.setContentDescription("Batalkan Proses AI"); b.setBackgroundColor(Color.parseColor("#F44336"));
                            b.setAlpha(1.0f);
                        } else if (allowed) {
                            b.setText("Input Suara AI"); b.setContentDescription("Input Suara AI"); b.setBackgroundColor(Color.parseColor("#00838F"));
                            b.setAlpha(1.0f);
                        } else {
                            b.setText("Input Suara (Terkunci)");
                            b.setContentDescription("Input Suara AI Terkunci — " + AuthManager.getAccessDeniedToast(KeyboardService.this));
                            b.setBackgroundColor(Color.parseColor("#546E7A"));
                            b.setAlpha(0.75f);
                        }
                    }
                }
            }
        });




























    }



    // ==================================================================
    // MIKROFON TERPUSAT: satu pilihan mikrofon (MicHelper) dipakai Whisper DAN Google.
    // ==================================================================
    private void tampilkanIndikatorMic(String teks) {
        try { SmartReplyAccessibilityService.tampilkanIndikatorMic(teks); } catch (Throwable ignored) {}
    }

    private void sembunyikanIndikatorMic() {
        try { SmartReplyAccessibilityService.sembunyikanIndikatorMic(); } catch (Throwable ignored) {}
    }

    private void hentikanPompaGoogle() {
        if (pompaGoogle != null) {
            try { pompaGoogle.stop(); } catch (Throwable ignored) {}
            pompaGoogle = null;
        }
    }

    /**
     * Saat mic sibuk (WA call), layanan aksesibilitas WAJIB aktif + overlay tampil
     * supaya Android berbagi mikrofon (pola Si Jubir). Return false = jangan lanjut rekam.
     */
    private boolean pastikanAksesibilitasUntukMicSibuk(boolean sibuk) {
        if (!sibuk) return true;
        if (SmartReplyAccessibilityService.instance == null) {
            Toast.makeText(this,
                    "Saat telepon, aktifkan dulu layanan aksesibilitas Keyboard AI di Pengaturan HP.",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void mulaiGoogleSTT() {
        if (micTertunda != null) return;
        if (isRecordingGoogle) { stopGoogleSTT(); return; }
        if (!cekIzinMikrofon()) {
            Toast.makeText(this, "Izin mikrofon belum diberikan.", Toast.LENGTH_LONG).show();
            return;
        }
        boolean sibuk = MicHelper.micSibuk(this);
        if (!pastikanAksesibilitasUntukMicSibuk(sibuk)) return;

        // Saat telepon / mic sibuk: SELURUH Google STT dijalankan di layanan aksesibilitas
        // (sama Si Jubir — recognizer + AudioRecord dari AccessibilityService, bukan IME).
        if (sibuk) {
            if (Build.VERSION.SDK_INT < 33) {
                Toast.makeText(this,
                        "Pengenal Google saat telepon butuh Android 13+. Nyalakan saklar Whisper di pengaturan audio.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            isRecordingGoogle = true;
            vibrate(100);
            String bahasa = prefs.getString("bahasa_mikrofon", prefs.getString("lang_src", "Otomatis"));
            SmartReplyAccessibilityService.mulaiGoogleSTT(prefs, bahasa,
                    new SmartReplyAccessibilityService.HasilSuara() {
                        @Override public void onHasil(final String teks) {
                            micHandler.post(new Runnable() {
                                @Override public void run() {
                                    isRecordingGoogle = false;
                                    if (teks != null && teks.length() > 0) {
                                        vibrate(100);
                                        mulaiProsesLogika(teks);
                                    }
                                }
                            });
                        }
                        @Override public void onGagal(final String pesan) {
                            micHandler.post(new Runnable() {
                                @Override public void run() {
                                    isRecordingGoogle = false;
                                    if (pesan != null && pesan.length() > 0) {
                                        Toast.makeText(KeyboardService.this, pesan, Toast.LENGTH_LONG).show();
                                    }
                                }
                            });
                        }
                    });
            return;
        }

        // Di luar telepon: jalur standar Google SpeechRecognizer (bunyi kling-kling asli Google aktif)
        googleSudahRetry = false;
        googleRecognizerAttempt = 0;
        if (speechRecognizer == null) inisialisasiPerekamGboard();
        if (speechRecognizer == null) {
            Toast.makeText(this, "Pengenal Google tidak tersedia di HP ini.", Toast.LENGTH_LONG).show();
            return;
        }
        tampilkanIndikatorMic("Merekam…");
        micTertunda = new Runnable() { @Override public void run() { micTertunda = null; jalankanGoogleSTT(false); } };
        micHandler.postDelayed(micTertunda, 80);
    }

    private void jalankanGoogleSTT(boolean izinkanPompa) {
        hentikanPompaGoogle();
        googlePakaiPompa = false;
        googleLastPartial = "";
        try {
            if (speechRecognizer == null) inisialisasiPerekamGboard();
            if (speechRecognizer == null) {
                throw new IllegalStateException("Layanan SpeechRecognizer tidak tersedia");
            }
            android.content.Intent intent = getSpeechIntent();
            boolean sibuk = MicHelper.micSibuk(this);
            // PompaGoogle (EXTRA_AUDIO_SOURCE) HANYA dipakai saat mic sibuk (telepon WA) agar bunyi kling-kling & suara pelan Google normal tidak terblokir di Xiaomi/Android 13+
            boolean perluPompa = Build.VERSION.SDK_INT >= 33 && izinkanPompa && sibuk;
            if (perluPompa) {
                Context ctxPompa = MicHelper.contextRekaman(this);
                try { intent.putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, false); } catch (Throwable ignored) {}
                pompaGoogle = MicHelper.PompaGoogle.mulai(ctxPompa, prefs, intent, 2200, new MicHelper.PompaGoogle.Pendengar() {
                    @Override public void onDiam() {
                        try { if (speechRecognizer != null) speechRecognizer.stopListening(); } catch (Throwable ignored) {}
                    }
                });
                googlePakaiPompa = (pompaGoogle != null);
                if (!googlePakaiPompa) {
                    sembunyiIndikatorSafely();
                    Toast.makeText(this,
                            "Pengenal Google gagal membuka mikrofon saat telepon. Nyalakan saklar Whisper di pengaturan audio.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
            }
            speechRecognizer.startListening(intent);
            isRecordingGoogle = true;
            vibrate(80);
            if (toneGen != null) {
                try { toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 80); } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            hentikanPompaGoogle();
            googlePakaiPompa = false;
            isRecordingGoogle = false;
            sembunyikanIndikatorMic();
            if (MicHelper.micSibuk(this)) {
                Toast.makeText(this, "Pengenal Google gagal saat telepon. Nyalakan saklar Whisper di pengaturan audio.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Gagal memulai Google Speech Recognition: " + (e.getMessage() != null ? e.getMessage() : "Cek izin mikrofon"), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void sembunyiIndikatorSafely() {
        isRecordingGoogle = false;
        sembunyikanIndikatorMic();
    }

    private void stopGoogleSTT() {
        if (micTertunda != null) { micHandler.removeCallbacks(micTertunda); micTertunda = null; }
        try { SmartReplyAccessibilityService.stopGoogleSTT(); } catch (Throwable ignored) {}
        try { if (speechRecognizer != null) speechRecognizer.stopListening(); } catch (Throwable ignored) {}
        hentikanPompaGoogle();
        googlePakaiPompa = false;
        isRecordingGoogle = false;
        sembunyikanIndikatorMic();
        vibrate(50);
    }

    private android.content.Intent getSpeechIntent() {
        android.content.Intent intent = new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        try { intent.putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName()); } catch (Throwable ignored) {}
        try {
            // Jeda diam lebih toleran agar bicara pelan tidak cepat terputus
            intent.putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 2500L);
            intent.putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2800L);
            intent.putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 2200L);
        } catch (Throwable ignored) {}

        String langSrc = prefs.getString("bahasa_mikrofon", prefs.getString("lang_src", "Otomatis"));
        if (langSrc == null || langSrc.equals("Otomatis") || langSrc.equalsIgnoreCase("auto")) {
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "id-ID");
        } else {
            String langTag = "id-ID";
            if (langSrc.contains("en-US") || langSrc.contains("Inggris")) langTag = "en-US";
            else if (langSrc.contains("ar-SA") || langSrc.contains("Arab")) langTag = "ar-SA";
            else if (langSrc.contains("ja-JP") || langSrc.contains("Jepang")) langTag = "ja-JP";
            else if (langSrc.contains("ko-KR") || langSrc.contains("Korea")) langTag = "ko-KR";
            else if (langSrc.contains("Jerman")) langTag = "de-DE";
            else if (langSrc.contains("Prancis")) langTag = "fr-FR";
            else if (langSrc.contains("id-ID") || langSrc.contains("Indonesia")) langTag = "id-ID";
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, langTag);
        }
        return intent;
    }

    /** Context untuk SpeechRecognizer: aksesibilitas saat telepon (sama Si Jubir). */
    private Context contextRecognizer() {
        try {
            if (MicHelper.micSibuk(this) && SmartReplyAccessibilityService.instance != null) {
                return SmartReplyAccessibilityService.instance;
            }
        } catch (Throwable ignored) {}
        return this;
    }

    private void hancurkanRecognizer() {
        try {
            if (speechRecognizer != null) {
                speechRecognizer.setRecognitionListener(null);
                speechRecognizer.destroy();
            }
        } catch (Throwable ignored) {}
        speechRecognizer = null;
    }

    private SpeechRecognizer createRecognizerForAttempt(Context ctx, int attempt) {
        if (attempt == 0) {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(ctx)) {
                    return SpeechRecognizer.createSpeechRecognizer(ctx);
                }
            } catch (Throwable ignored) {}
        }
        ArrayList<android.content.ComponentName> candidates = new ArrayList<>();
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            java.util.List<android.content.pm.ResolveInfo> svcs = pm.queryIntentServices(
                    new android.content.Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0);
            if (svcs != null) {
                for (android.content.pm.ResolveInfo ri : svcs) {
                    if (ri.serviceInfo != null) {
                        android.content.ComponentName cn = new android.content.ComponentName(
                                ri.serviceInfo.packageName, ri.serviceInfo.name);
                        if ("com.google.android.tts".equals(ri.serviceInfo.packageName)
                                || "com.google.android.googlequicksearchbox".equals(ri.serviceInfo.packageName)
                                || "com.google.android.as".equals(ri.serviceInfo.packageName)) {
                            candidates.add(0, cn);
                        } else {
                            candidates.add(cn);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        candidates.add(new android.content.ComponentName(
                "com.google.android.tts",
                "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"));
        candidates.add(new android.content.ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"));

        int startIdx = Math.max(0, attempt - 1);
        for (int i = startIdx; i < candidates.size(); i++) {
            try {
                SpeechRecognizer sr = SpeechRecognizer.createSpeechRecognizer(ctx, candidates.get(i));
                if (sr != null) return sr;
            } catch (Throwable ignored) {}
        }
        try {
            return SpeechRecognizer.createSpeechRecognizer(this);
        } catch (Throwable ignored) {}
        return null;
    }

    private String speechErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "Masalah audio/mikrofon (3)";
            case SpeechRecognizer.ERROR_CLIENT: return "Klien Google Speech tidak merespons (5)";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Izin mikrofon belum aktif (9)";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Koneksi internet untuk suara terganggu";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "Layanan suara sedang sibuk, coba lagi";
            case SpeechRecognizer.ERROR_SERVER: return "Server Google Speech error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "Tidak ada suara terdeteksi";
            case SpeechRecognizer.ERROR_NO_MATCH: return "Suara kurang jelas, coba ulangi";
            default: return "Kode " + error;
        }
    }

    private void inisialisasiPerekamGboard() {
        hancurkanRecognizer();
        try {
            Context ctx = contextRecognizer();
            speechRecognizer = createRecognizerForAttempt(ctx, googleRecognizerAttempt);
            if (speechRecognizer == null) return;

            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    tampilkanIndikatorMic("Bicara sekarang…");
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {
                    isRecordingGoogle = false;
                    hentikanPompaGoogle();
                }
                @Override public void onError(int error) {
                    boolean tadiPompa = googlePakaiPompa;
                    boolean sibuk = MicHelper.micSibuk(KeyboardService.this);
                    isRecordingGoogle = false;
                    hentikanPompaGoogle();
                    String partial = googleLastPartial != null ? googleLastPartial.trim() : "";
                    if (partial.length() > 0) {
                        sembunyikanIndikatorMic();
                        googleLastPartial = "";
                        vibrate(100);
                        mulaiProsesLogika(partial);
                        return;
                    }
                    if (!googleSudahRetry && (tadiPompa || error == SpeechRecognizer.ERROR_CLIENT
                            || error == SpeechRecognizer.ERROR_AUDIO
                            || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                            || error == SpeechRecognizer.ERROR_SERVER)) {
                        googleSudahRetry = true;
                        googleRecognizerAttempt++;
                        try { if (speechRecognizer != null) speechRecognizer.cancel(); } catch (Throwable ignored) {}
                        micTertunda = new Runnable() {
                            @Override public void run() {
                                micTertunda = null;
                                inisialisasiPerekamGboard();
                                if (speechRecognizer == null) {
                                    sembunyikanIndikatorMic();
                                    Toast.makeText(KeyboardService.this,
                                            "Pengenal Google tidak tersedia di HP ini.",
                                            Toast.LENGTH_LONG).show();
                                    return;
                                }
                                tampilkanIndikatorMic("Merekam…");
                                jalankanGoogleSTT(sibuk);
                            }
                        };
                        micHandler.postDelayed(micTertunda, 320);
                        return;
                    }
                    sembunyikanIndikatorMic();
                    googleLastPartial = "";
                    try {
                        hancurkanRecognizer();
                        googleRecognizerAttempt = 0;
                        inisialisasiPerekamGboard();
                    } catch (Throwable ignored) {}
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        Toast.makeText(KeyboardService.this, speechErrorMessage(error), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (sibuk) {
                        Toast.makeText(KeyboardService.this,
                                "Pengenal Google gagal saat telepon (" + speechErrorMessage(error) + "). Nyalakan Whisper di setelan audio.",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(KeyboardService.this, "Google STT: " + speechErrorMessage(error), Toast.LENGTH_LONG).show();
                    }
                }
                @Override public void onResults(Bundle results) {
                    isRecordingGoogle = false;
                    hentikanPompaGoogle();
                    sembunyikanIndikatorMic();
                    try {
                        String teks = null;
                        ArrayList<String> data = results != null ? results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                        if (data != null && !data.isEmpty()) teks = data.get(0);
                        if ((teks == null || teks.trim().isEmpty()) && googleLastPartial != null && googleLastPartial.trim().length() > 0) {
                            teks = googleLastPartial.trim();
                        }
                        googleLastPartial = "";
                        if (teks != null && teks.length() > 0) {
                            vibrate(100);
                            mulaiProsesLogika(teks);
                        }
                    } catch (Throwable t) {
                        Toast.makeText(KeyboardService.this, "Gagal memproses hasil suara.", Toast.LENGTH_SHORT).show();
                    }
                }
                @Override public void onPartialResults(Bundle partialResults) {
                    try {
                        if (partialResults == null) return;
                        ArrayList<String> m = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (m != null && !m.isEmpty() && m.get(0) != null) googleLastPartial = m.get(0);
                    } catch (Throwable ignored) {}
                }
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        } catch (Throwable e) {
            e.printStackTrace();
            speechRecognizer = null;
        }
    }

    private boolean cekIzinMikrofon() {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return true;
        }
    }

    private void mulaiRekamWhisper() {
        if (micTertunda != null || isRecordingWhisper) return;
        if (!AuthManager.isSignedIn() || !AuthManager.isPremiumActive(this)) {
            refreshMicPremiumVisual();
            Toast.makeText(this, AuthManager.getAccessDeniedToast(this), Toast.LENGTH_LONG).show();
            return;
        }
        if (WhisperOfflineManager.isOfflineProvider(prefs)
                && !WhisperOfflineManager.isSelectedModelInstalled(this, prefs)) {
            Toast.makeText(this, "Model Whisper Offline belum didownload. Buka Pengaturan Audio atau API untuk mengunduh.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!cekIzinMikrofon()) {
            Toast.makeText(this, "Izin mikrofon belum diberikan. Buka aplikasi Keyboard AI, izinkan mikrofon di pengaturan, lalu coba lagi.", Toast.LENGTH_LONG).show();
            return;
        }
        boolean sibuk = MicHelper.micSibuk(this);
        if (!pastikanAksesibilitasUntukMicSibuk(sibuk)) return;
        tampilkanIndikatorMic("Merekam…");
        // Overlay aksesibilitas harus tampil dulu sebelum mic dibuka (pola Si Jubir).
        micTertunda = new Runnable() { @Override public void run() { micTertunda = null; mulaiRekamWhisperInti(); } };
        micHandler.postDelayed(micTertunda, sibuk ? 400 : 100);
    }

    private void mulaiRekamWhisperInti() {
        try {
            if (mediaRecorder != null) {
                try { mediaRecorder.reset(); } catch (Exception ignored) {}
                try { mediaRecorder.release(); } catch (Exception ignored) {}
                mediaRecorder = null;
            }
            if (offlinePcmRecorder != null) {
                offlinePcmRecorder.stopAndDiscard();
                offlinePcmRecorder = null;
            }

            if (WhisperOfflineManager.isOfflineProvider(prefs)) {
                offlinePcmFile = new File(getCacheDir(), "whisper_offline_tmp.pcm");
                offlinePcmRecorder = new MicHelper.PcmWhisperRecorder();
                if (!offlinePcmRecorder.start(this, prefs, offlinePcmFile)) {
                    offlinePcmRecorder = null;
                    offlinePcmFile = null;
                    throw new Exception("Android menolak semua sumber mikrofon");
                }
                recordingOfflineWhisper = true;
            } else {
                recordingOfflineWhisper = false;
                int bitRatePref = UiKit.readIntPref(prefs, "audio_bitrate", 128);
                if (bitRatePref < 16) bitRatePref = 128;
                int bitRate = bitRatePref * 1000;

                // Semua STT online dipaksa MPEG-4/AAC supaya tidak ada MIME mismatch / hasil kosong.
                // Mikrofon = pilihan tunggal dari MicHelper (sama dengan jalur Google); otomatis pindah sumber kalau ditolak.
                mediaRecorder = MicHelper.bukaMediaRecorder(this, prefs, audioFilePath, bitRate);
                if (mediaRecorder == null) throw new Exception("Android menolak semua sumber mikrofon");
            }
            if (toneGen != null) toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 100);
            isRecordingWhisper = true;
            Toast.makeText(this, "Merekam… ketuk lagi untuk berhenti.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { 
            String errStr = e.getMessage();
            if (errStr == null) errStr = e.toString();
            sembunyikanIndikatorMic();
            ketikHasil(" [Error Rekam: " + errStr + "] ");
            boolean telepon = MicHelper.sedangTelepon(this);
            boolean adaLain = MicHelper.adaRekamanAktifLain(this);
            if (telepon) {
                Toast.makeText(this, "Mikrofon ditolak karena sedang panggilan. Aktifkan layanan aksesibilitas Keyboard AI, pilih Mikrofon = Otomatis, lalu coba lagi.", Toast.LENGTH_LONG).show();
            } else if (adaLain) {
                Toast.makeText(this, "Ada aplikasi lain yang sedang memakai mikrofon. Tutup dulu, atau aktifkan layanan aksesibilitas Keyboard AI.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Gagal memakai mikrofon. Cek izin mikrofon di Pengaturan.", Toast.LENGTH_LONG).show();
            }
            isRecordingWhisper = false; 
        }
    }

    private void stopRekamWhisper() {
        sembunyikanIndikatorMic();
        final boolean wasOffline = recordingOfflineWhisper;
        recordingOfflineWhisper = false;
        File stoppedPcm = null;
        try {
            if (mediaRecorder != null) {
                try { mediaRecorder.stop(); } catch (Exception ignored) {}
                try { mediaRecorder.reset(); } catch (Exception ignored) {}
                try { mediaRecorder.release(); } catch (Exception ignored) {}
                mediaRecorder = null;
            }
            if (offlinePcmRecorder != null) {
                stoppedPcm = offlinePcmRecorder.stop();
                offlinePcmRecorder = null;
            }
            if (!AuthManager.isSignedIn() || !AuthManager.isPremiumActive(this)) {
                isRecordingWhisper = false;
                if (stoppedPcm != null && stoppedPcm.exists()) {
                    try { stoppedPcm.delete(); } catch (Throwable ignored) {}
                }
                setMicButtonState(false);
                Toast.makeText(this, AuthManager.getAccessDeniedToast(this), Toast.LENGTH_LONG).show();
                return;
            }
            if (toneGen != null) toneGen.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 100);
            isRecordingWhisper = false; vibrate(100);
            Toast.makeText(this, wasOffline ? "Memproses suara offline..." : "Mengirim Audio...", Toast.LENGTH_SHORT).show();
            setMicButtonState(true);
            final File finalPcmFile = stoppedPcm;
            aiThread = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        String hasilTranskrip = "";
                        if (wasOffline) {
                            if (finalPcmFile == null || !finalPcmFile.exists()) {
                                throw new Exception("Berkas audio sementara tidak ditemukan.");
                            }
                            hasilTranskrip = WhisperOfflineManager.transcribePcmFile(
                                    KeyboardService.this, prefs, finalPcmFile.getAbsolutePath());
                            if (Thread.currentThread().isInterrupted()) return;
                            if (hasilTranskrip == null || hasilTranskrip.trim().isEmpty()) {
                                new Handler(Looper.getMainLooper()).post(() -> {
                                    setMicButtonState(false);
                                    Toast.makeText(KeyboardService.this, "Tidak ada ucapan terdeteksi.", Toast.LENGTH_SHORT).show();
                                });
                                return;
                            }
                        } else {
                            String wProv = prefs.getString("whisperProvider", "Google Gemini");
                            String wMod = prefs.getString("whisper_model", "gemini-2.0-flash");
                            String wKey = AiEngine.resolveApiKey(KeyboardService.this, prefs, wProv);
                            if (wProv.equals("Transkripsi Gemini")) {
                                wKey = AiEngine.resolveApiKey(KeyboardService.this, prefs, "Google Gemini");
                            }

                            if (wKey.isEmpty()) throw new Exception("API key suara belum diisi, bro!");

                            if (wProv.equals("Google Gemini") || wProv.equals("Transkripsi Gemini")) {
                                if (wMod == null || wMod.isEmpty() || !wMod.startsWith("gemini")) {
                                    wMod = "gemini-2.0-flash";
                                }
                                hasilTranskrip = transkripGeminiBase64(audioFilePath, wKey, wMod);
                            } else if (wProv.equals("Grok (xAI)")) {
                                hasilTranskrip = transkripGrokSTT(audioFilePath, wKey);
                            } else {
                                String urlStr = "https://api.openai.com/v1/audio/transcriptions";
                                if (wProv.equals("Groq (Cepat)")) {
                                    urlStr = "https://api.groq.com/openai/v1/audio/transcriptions";
                                    if (wMod == null || wMod.isEmpty() || wMod.startsWith("gemini") || wMod.equals("grok-stt")) {
                                        wMod = "whisper-large-v3-turbo";
                                    }
                                } else {
                                    if (wMod == null || wMod.isEmpty() || wMod.startsWith("gemini") || wMod.equals("grok-stt")) {
                                        wMod = "whisper-1";
                                    }
                                }
                                hasilTranskrip = transkripMultipart(audioFilePath, wKey, wMod, urlStr);
                            }
                        }
                        if (Thread.currentThread().isInterrupted()) return;
                        final String finalHasil = hasilTranskrip;
                        new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() { mulaiProsesLogika(finalHasil); } });
                    } catch (final Exception e) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() {
                                setMicButtonState(false);
                                String errStr = e.getMessage();
                                if (errStr == null) errStr = e.toString();
                                ketikHasil(" [Error STT: " + errStr + "] ");
                                if (!Thread.currentThread().isInterrupted()) Toast.makeText(KeyboardService.this, "Gagal Transkrip", Toast.LENGTH_LONG).show();
                            }
                        });
                    } finally {
                        if (finalPcmFile != null && finalPcmFile.exists()) {
                            try { finalPcmFile.delete(); } catch (Throwable ignored) {}
                        }
                    }
                }
            });
            aiThread.start();
        } catch (Exception e) {
            isRecordingWhisper = false;
            if (mediaRecorder != null) { try { mediaRecorder.release(); } catch (Exception ignored) {} mediaRecorder = null; }
            if (offlinePcmRecorder != null) { offlinePcmRecorder.stopAndDiscard(); offlinePcmRecorder = null; }
            if (stoppedPcm != null && stoppedPcm.exists()) { try { stoppedPcm.delete(); } catch (Throwable ignored) {} }
        }
    }

    private void mulaiProsesLogika(String inputTeks) {
        if (prefs.getBoolean("makro_enabled", false)) {
            try {
                JSONArray macros = new JSONArray(prefs.getString("json_macros", "[]"));
                for (int i = 0; i < macros.length(); i++) {
                    JSONObject m = macros.getJSONObject(i);
                    if (inputTeks.trim().equalsIgnoreCase(m.getString("pemicu").trim())) {
                        ketikHasil(m.getString("isi")); setMicButtonState(false); return;
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
        if (!prefs.getBoolean("processing_enabled", true)) {
            ketikHasil(terapkanKamus(inputTeks)); setMicButtonState(false); return;
        }
        Toast.makeText(this, "AI sedang meracik...", Toast.LENGTH_SHORT).show();
        setMicButtonState(true);
        prosesTeksDenganAI(inputTeks, false, true, false);
    }

    /**
     * Proses Revisi Teks (tombol ✍️):
     * 1. Kirim teks di kotak edit ke AI dulu (dengan pipeline lengkap seperti Voice Input:
     *    Gaya Bahasa, Instruksi Khusus, Translator, Kamus, Makro, Logika AI).
     * 2. Setelah respon AI diterima, hapus bersih seluruh teks di kotak edit dulu,
     *    baru tempelkan hasil revisi dari AI.
     */
    private void mulaiProsesRevisiTeks(String inputTeks) {
        if (inputTeks == null || inputTeks.trim().isEmpty()) {
            Toast.makeText(this, "Kotak teks masih kosong!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (aiThread != null && aiThread.isAlive()) {
            try { aiThread.interrupt(); } catch (Throwable ignored) {}
        }
        if (prefs.getBoolean("makro_enabled", false)) {
            try {
                JSONArray macros = new JSONArray(prefs.getString("json_macros", "[]"));
                for (int i = 0; i < macros.length(); i++) {
                    JSONObject m = macros.getJSONObject(i);
                    if (inputTeks.trim().equalsIgnoreCase(m.getString("pemicu").trim())) {
                        InputConnection icMakro = getCurrentInputConnection();
                        if (icMakro != null) clearAllTextInEditor(icMakro);
                        ketikHasil(m.getString("isi"), true); // tempel langsung
                        setMicButtonState(false);
                        return;
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
        Toast.makeText(this, "AI sedang merevisi teks...", Toast.LENGTH_SHORT).show();
        setMicButtonState(true);
        prosesTeksDenganAI(inputTeks, false, false, true);
    }

    private String extractAllEditorText(InputConnection ic) {
        if (ic == null) return "";
        try {
            android.view.inputmethod.ExtractedText extracted = getFullExtractedText(ic);
            if (extracted != null && extracted.text != null && extracted.text.length() > 0) {
                return extracted.text.toString().trim();
            }
        } catch (Throwable ignored) {}
        try {
            StringBuilder sb = new StringBuilder();
            CharSequence before = ic.getTextBeforeCursor(100000, 0);
            CharSequence selected = ic.getSelectedText(0);
            CharSequence after = ic.getTextAfterCursor(100000, 0);
            if (before != null) sb.append(before);
            if (selected != null) sb.append(selected);
            if (after != null) sb.append(after);
            return sb.toString().trim();
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * Bersihin total isi kotak edit. Versi agresif biar nggak ngeres / numpuk
     * sama hasil AI pas revisi teks. Coba beberapa cara biar aman di WA, Chrome,
     * WebView, dll.
     */
    private void clearAllTextInEditor(InputConnection ic) {
        if (ic == null) return;
        try {
            ic.beginBatchEdit();
            try {
                try { ic.finishComposingText(); } catch (Throwable ignored) {}

                // 1) Ambil panjang real dari kursor kiri/kanan, hapus pas
                try {
                    CharSequence before = ic.getTextBeforeCursor(Integer.MAX_VALUE / 4, 0);
                    CharSequence after = ic.getTextAfterCursor(Integer.MAX_VALUE / 4, 0);
                    int beforeLen = (before != null) ? before.length() : 0;
                    int afterLen = (after != null) ? after.length() : 0;
                    if (beforeLen > 0 || afterLen > 0) {
                        ic.deleteSurroundingText(beforeLen, afterLen);
                    }
                } catch (Throwable ignored) {}

                // 2) Pakai ExtractedText + setSelection(0, len) lalu kosongin
                try {
                    android.view.inputmethod.ExtractedText et = getFullExtractedText(ic);
                    if (et != null && et.text != null && et.text.length() > 0) {
                        ic.setSelection(0, et.text.length());
                        ic.commitText("", 1);
                    }
                } catch (Throwable ignored) {}

                // 3) Select-all lewat context menu (beberapa app cuma nurut ini)
                try {
                    ic.performContextMenuAction(android.R.id.selectAll);
                    ic.commitText("", 1);
                } catch (Throwable ignored) {}

                // 4) Sapu sisa pakai angka gede (backup)
                try { ic.deleteSurroundingText(100000, 100000); } catch (Throwable ignored) {}
                try { ic.deleteSurroundingText(Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2); } catch (Throwable ignored) {}
            } finally {
                try { ic.endBatchEdit(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            try { ic.deleteSurroundingText(100000, 100000); } catch (Throwable ignored) {}
        }
    }

    /**
     * Ganti seluruh isi kotak edit dengan teks baru dalam satu aliran.
     * Dipakai khusus revisi teks biar hasil AI nggak numpuk di teks lama.
     */
    private void replaceAllTextInEditor(InputConnection ic, String newText) {
        if (ic == null) return;
        if (newText == null) newText = "";
        try {
            ic.beginBatchEdit();
            try {
                try { ic.finishComposingText(); } catch (Throwable ignored) {}

                // Hapus dulu dengan cara yang paling akurat (panjang kiri+kanan)
                try {
                    CharSequence before = ic.getTextBeforeCursor(Integer.MAX_VALUE / 4, 0);
                    CharSequence after = ic.getTextAfterCursor(Integer.MAX_VALUE / 4, 0);
                    int beforeLen = (before != null) ? before.length() : 0;
                    int afterLen = (after != null) ? after.length() : 0;
                    if (beforeLen > 0 || afterLen > 0) {
                        ic.deleteSurroundingText(beforeLen, afterLen);
                    }
                } catch (Throwable ignored) {}

                // Coba select full lewat ExtractedText
                try {
                    android.view.inputmethod.ExtractedText et = getFullExtractedText(ic);
                    if (et != null && et.text != null && et.text.length() > 0) {
                        ic.setSelection(0, et.text.length());
                        // Langsung commit teks baru = ganti seleksi
                        ic.commitText(newText, 1);
                        return; // sudah berhasil ganti
                    }
                } catch (Throwable ignored) {}

                // Fallback select-all
                try {
                    ic.performContextMenuAction(android.R.id.selectAll);
                    ic.commitText(newText, 1);
                    return;
                } catch (Throwable ignored) {}

                // Last resort: sapu sisa + commit
                try { ic.deleteSurroundingText(100000, 100000); } catch (Throwable ignored) {}
                try { ic.deleteSurroundingText(Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2); } catch (Throwable ignored) {}
                ic.commitText(newText, 1);
            } finally {
                try { ic.endBatchEdit(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            try {
                clearAllTextInEditor(ic);
                ic.commitText(newText, 1);
            } catch (Throwable ignored) {}
        }
    }

    private String terapkanKamus(String teks) {
        if (!prefs.getBoolean("kamus_enabled", false)) return teks;
        try {
            JSONArray kamus = new JSONArray(prefs.getString("json_kamus", "[]"));
            String hasil = teks;
            boolean useRegex = prefs.getBoolean("regex_kamus_enabled", false);
            for (int i = 0; i < kamus.length(); i++) {
                JSONObject k = kamus.getJSONObject(i);
                String origin = k.getString("origin");
                String replacement = k.getString("replacement");
                if (useRegex) {
                    hasil = hasil.replaceAll("(?i)\\b" + origin + "\\b", replacement);
                } else {
                    // pake pelindung biar kata yg ada simbolnya gak bikin error pas nge-replace
                    hasil = hasil.replaceAll("(?i)(?<=^|\\W)" + java.util.regex.Pattern.quote(origin) + "(?=\\W|$)", java.util.regex.Matcher.quoteReplacement(replacement));
                }
            }
            return hasil;
        } catch (Exception e) { return teks; }
    }

    private void triggerSmartReply() {
        String contextText = "";
        
        android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            contextText = extractAllEditorText(ic);
            if (contextText.isEmpty()) {
                android.view.inputmethod.EditorInfo info = getCurrentInputEditorInfo();
                if (info != null && info.hintText != null) contextText = info.hintText.toString().trim();
            }
        }
        
        if (contextText.isEmpty() && SmartReplyAccessibilityService.instance != null) {
            String myName = prefs.getString("user_name", "Aku");
            int maxMsgs = prefs.getInt("smart_reply_max_messages", 4);
            contextText = SmartReplyAccessibilityService.instance.extractChatContext(myName, maxMsgs);
        }
        
        if (!contextText.isEmpty()) {
            Toast.makeText(this, "Meracik balasan pintar...", Toast.LENGTH_SHORT).show();
            prosesTeksDenganAI(contextText, true, false, false);
        } else {
            Toast.makeText(KeyboardService.this, "Gak ada pesan buat dibalas!", Toast.LENGTH_SHORT).show();
        }
    }

    private void prosesTeksDenganAI(final String inputTeks) {
        prosesTeksDenganAI(inputTeks, false, false, false);
    }
    
    private void prosesTeksDenganAI(final String inputTeks, final boolean forceWingman) {
        prosesTeksDenganAI(inputTeks, forceWingman, false, false);
    }

    private void prosesTeksDenganAI(final String inputTeks, final boolean forceWingman, final boolean isVoiceInput) {
        prosesTeksDenganAI(inputTeks, forceWingman, isVoiceInput, false);
    }

    private void prosesTeksDenganAI(final String inputTeks, final boolean forceWingman, final boolean isVoiceInput, final boolean replaceEditorOnSuccess) {
        if (!AuthManager.isSignedIn() || !AuthManager.isPremiumActive(this)) {
            setMicButtonState(false);
            Toast.makeText(this, AuthManager.getAccessDeniedToast(this), Toast.LENGTH_LONG).show();
            return;
        }
        aiThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String provider = prefs.getString("selectedProvider", "Google Gemini");
                    String apiKey = AiEngine.resolveApiKey(KeyboardService.this, prefs, provider);
                    String modelTarget = AiEngine.getActiveModel(KeyboardService.this, prefs, provider);
                    String rawModel = prefs.getString("selectedModel", "");
                    if (!rawModel.isEmpty() && !"default".equals(rawModel)) {
                        modelTarget = AiEngine.getActiveModel(KeyboardService.this, prefs, provider);
                    }

                    if (apiKey.isEmpty()) throw new Exception("API Key Belum Diisi!");

                    boolean modeRevisi = prefs.getBoolean("revision_mode", false);
                    boolean wingmanMode = prefs.getBoolean("wingman_mode", false);
                    boolean transEnabled = prefs.getBoolean("trans_enabled", false);
                    String gayaAktif = prefs.getString("gaya_aktif_nama", "Standar");
                    String gayaPrompt = prefs.getString("gaya_aktif_prompt", "");
                    boolean customInstEnabled = prefs.getBoolean("custom_inst_enabled", false);
                    String customInst = prefs.getString("selected_inst_text", "");
                    boolean hideLinks = prefs.getBoolean("grok_hide_links", true);
                    String aiTense = prefs.getString("ai_tense", "Standard");
                    String aiTone = prefs.getString("ai_tone", "Netral");
                    boolean emojiEnabled = prefs.getBoolean("emoji_enabled", false);
                    boolean cotEnabled = prefs.getBoolean("ai_cot_enabled", false);
                    boolean jsonMode = prefs.getBoolean("ai_json_mode", false);

                    StringBuilder superPrompt = new StringBuilder();
                    superPrompt.append("Tugas Anda memproses teks dengan aturan mutlak di bawah ini. DILARANG KERAS menulis kata pengantar, salam pembuka, atau penutup seperti 'Berikut', 'Tentu', 'Oke', 'Siap', 'Ini hasilnya', atau 'Semoga membantu'. LANGSUNG KELUARKAN HASIL AKHIRNYA SAJA.\n\n");
                    superPrompt.append("=== ATURAN MUTLAK ===\n");

                    final boolean isGeminiTtsStyle = isGeminiTtsCreativeStyle(gayaAktif, gayaPrompt);

                    if (forceWingman || (wingmanMode && !replaceEditorOnSuccess)) {
                        superPrompt.append("- MODE WINGMAN: Buat balasan untuk chat ini. Anda adalah manusia biasa, bukan AI.\n");

                        String srLen = prefs.getString("smart_reply_length", "Otomatis");
                        String userName = prefs.getString("wingman_nama", "");
                        String userOrigin = prefs.getString("wingman_asal", "");
                        String userAge = prefs.getString("wingman_usia", "");
                        String wgInstruksi = prefs.getString("wingman_instruksi", "");
                        String wgStruktur = prefs.getString("wingman_struktur", "Otomatis");
                        String wgGaya = prefs.getString("wingman_gaya", "");
                        String wgGayaPrompt = prefs.getString("wingman_gaya_prompt", "");
                        String srStyle = prefs.getString("smart_reply_style", "Natural Santai");
                        superPrompt.append("- GAYA BALASAN: ").append(srStyle);
                        if (!wgGaya.isEmpty()) superPrompt.append(" / ").append(wgGaya);
                        superPrompt.append("\n");
                        if (!wgGayaPrompt.isEmpty()) {
                            superPrompt.append("- INSTRUKSI GAYA WINGMAN: ").append(wgGayaPrompt).append("\n");
                        }
                        if (!wgStruktur.isEmpty() && !wgStruktur.equals("Otomatis")) {
                            superPrompt.append("- STRUKTUR RESPON: ").append(wgStruktur).append("\n");
                        }
                        if (!wgInstruksi.isEmpty()) {
                            superPrompt.append("- INSTRUKSI TAMBAHAN WINGMAN: ").append(wgInstruksi).append("\n");
                        }

                        if (srLen.equals("Singkat")) superPrompt.append("- PANJANG BALASAN: Sangat Singkat (Maks 1 kalimat pendek).\n");
                        else if (srLen.equals("Sedang")) superPrompt.append("- PANJANG BALASAN: Sedang (1-2 kalimat).\n");
                        else if (srLen.equals("Panjang")) superPrompt.append("- PANJANG BALASAN: Panjang (2-3 kalimat).\n");
                        else superPrompt.append("- PANJANG BALASAN: Otomatis (menyesuaikan panjang pesan lawan).\n");

                        StringBuilder profil = new StringBuilder();
                        if (!userName.isEmpty()) profil.append("Nama saya: ").append(userName).append(". ");
                        if (!userOrigin.isEmpty()) profil.append("Asal saya: ").append(userOrigin).append(". ");
                        if (!userAge.isEmpty()) profil.append("Usia saya: ").append(userAge).append(". ");
                        if (profil.length() > 0) superPrompt.append("- PROFIL SAYA: ").append(profil.toString()).append(" (Gunakan info ini jika relevan/ditanya).\n");

                        if (transEnabled) {
                            String langTrg = prefs.getString("lang_trg", "Inggris");
                            superPrompt.append("- MODE TERJEMAHAN: Buat hasil akhir balasan dalam bahasa ").append(langTrg).append(".\n");
                        }
                    } else {
                        // Alur Terpadu untuk Voice Input & Revisi Teks (atau Mode Kreatif / Gemini TTS):
                        if (isGeminiTtsStyle) {
                            superPrompt.append("- MODE KREATIF & PENGOLAH GEMINI TTS: Pahami niat input pengguna secara cerdas tanpa terpaku pada kalimat perintahnya:\n")
                                    .append("  1) Jika input pengguna berupa PERINTAH/PERMINTAAN/TOPIK (baik diawali kata 'tolong', 'buatkan', 'bikinin', 'coba', ataupun langsung topik/konsep singkat tanpa kata 'tolong'), LANGSUNG buatkan naskah lengkap dari nol sesuai permintaan & gaya di bawah.\n")
                                    .append("  2) Jika input pengguna berupa TEKS/NASKAH/CERITA/DIALOG yang sudah ada, OLAH & KEMBANGKAN teks tersebut sesuai gaya (pertahankan pesan inti user, ubah ke Pembicara1:/Pembicara2: bila diminta dialog, dan sisipkan tag ekspresi Gemini TTS [...] di titik awal/tengah/akhir yang paling hidup).\n")
                                    .append("  3) WAJIB langsung keluarkan naskah siap baca saja tanpa pembuka, tanpa penutup, tanpa tanda bintang (*), dan tanpa markdown (**).\n");
                        } else if (replaceEditorOnSuccess || modeRevisi) {
                            superPrompt.append("- MODE REVISI TEKS: Perbaiki salah ketik (typo), ejaan, tata bahasa, dan susunan kalimat pada teks berikut agar rapi, jelas, dan optimal.\n");
                        } else {
                            superPrompt.append("- TUGAS STANDAR: Rapikan teks suara ini dengan ejaan dan tanda baca yang natural.\n");
                        }

                        if (!gayaAktif.equals("Standar") && !gayaPrompt.isEmpty()) {
                            String cleanGayaPrompt = gayaPrompt.replace("[MODE: GEMINI_TTS]", "").trim();
                            superPrompt.append("- GAYA BAHASA (").append(gayaAktif).append("): ").append(cleanGayaPrompt).append("\n");
                        }

                        if (customInstEnabled && !customInst.isEmpty()) {
                            superPrompt.append("- INSTRUKSI KHUSUS: ").append(customInst).append("\n");
                        }

                        if (transEnabled) {
                            String langSrc = prefs.getString("lang_src", "Otomatis");
                            String langTrg = prefs.getString("lang_trg", "Inggris");
                            if (langSrc != null && !langSrc.isEmpty() && !langSrc.equalsIgnoreCase("Otomatis")) {
                                superPrompt.append("- MODE TERJEMAHAN (WAJIB): Terjemahkan hasil teks dari bahasa ").append(langSrc)
                                        .append(" ke bahasa ").append(langTrg)
                                        .append(" secara akurat, luwes, dan natural (tetap terapkan gaya bahasa/nada bicara yang aktif).\n");
                            } else {
                                superPrompt.append("- MODE TERJEMAHAN (WAJIB): Terjemahkan hasil akhir teks ini ke bahasa ").append(langTrg)
                                        .append(" secara akurat, luwes, dan natural (tetap terapkan gaya bahasa/nada bicara yang aktif).\n");
                            }
                        }
                    }

                    if (!aiTone.equals("Netral")) superPrompt.append("- NADA BICARA: Gunakan nada ").append(aiTone).append(".\n");
                    if (!aiTense.equals("Standard")) superPrompt.append("- GAYA WAKTU: Gunakan tenses ").append(aiTense).append(".\n");
                    if (cotEnabled) superPrompt.append("- PENALARAN: Pikirkan struktur dan konteks secara mendalam sebelum menyusun hasil akhir, namun tetap hanya keluarkan hasil akhirnya saja.\n");
                    if (jsonMode) superPrompt.append("- FORMAT OUTPUT: Keluarkan hasil dalam format JSON yang valid.\n");

                    if (emojiEnabled) {
                        String emojiCat = prefs.getString("emoji_cat", "Netral");
                        String petunjuk = "sopan dan sedikit";
                        if (emojiCat.equals("Gaya Laki-laki")) petunjuk = "gaya laki-laki (cool)";
                        else if (emojiCat.equals("Gaya Perempuan")) petunjuk = "gaya manis, banyak hati";
                        else if (emojiCat.equals("Hujan Emoji (Banyak)")) petunjuk = "BANYAK emoji";
                        superPrompt.append("- EMOJI: WAJIB MENGGUNAKAN EMOJI (").append(petunjuk).append(").\n");
                    } else {
                        superPrompt.append("- EMOJI: DILARANG KERAS menggunakan emoji.\n");
                    }

                    // Cek apakah user sengaja memilih Gaya Bahasa / Tone formal
                    String combinedStyleCheck = (gayaAktif + " " + gayaPrompt + " " + aiTone + " " + (customInstEnabled ? customInst : "")).toLowerCase(java.util.Locale.ROOT);
                    boolean userWantsFormal = combinedStyleCheck.contains("formal")
                            || combinedStyleCheck.contains("baku")
                            || combinedStyleCheck.contains("resmi")
                            || combinedStyleCheck.contains("akademis")
                            || combinedStyleCheck.contains("profesional")
                            || combinedStyleCheck.contains("korporat")
                            || combinedStyleCheck.contains("bisnis")
                            || combinedStyleCheck.contains("sopan");

                    if (!userWantsFormal) {
                        superPrompt.append("- ATURAN MUTLAK TINGKAT FORMALITAS: DILARANG KERAS mengubah bahasa tidak formal/santai/gaul menjadi bahasa formal atau baku! Jika teks menggunakan kata-kata santai, gaul, atau dialek percakapan (seperti 'gak', 'nggak', 'udah', 'gue', 'lu', 'gimana', 'bro', 'banget', 'beneran', 'dong', 'nih', dll), WAJIB PERTAHANKAN nuansa santai tersebut apa adanya. Buat hasil akhir tetap mengalir luwes, santai, dan rapi natural layaknya chat manusia asli.\n");
                    }

                    superPrompt.append("- TANDA BACA: Selalu gunakan tanda baca (koma, titik, tanda tanya, tanda seru) yang benar, pas, dan rapi di setiap akhir kalimat atau jeda.\n");
                    superPrompt.append("\n=== TEKS YANG HARUS DIPROSES ===\n").append(inputTeks);

                    String finalResult = "";
                    if (provider.equals("Google Gemini")) {
                        finalResult = prosesGeminiChat(apiKey, modelTarget, superPrompt.toString());
                    } else if (provider.equals("Copilot External")) {
                        finalResult = prosesCopilotExternalChat(modelTarget, superPrompt.toString(), inputTeks);
                    } else {
                        String urlTarget = "https://api.openai.com/v1/chat/completions";
                        if (provider.equals("Grok (xAI)")) urlTarget = "https://api.x.ai/v1/chat/completions";
                        else if (provider.equals("Groq (Cepat)")) urlTarget = "https://api.groq.com/openai/v1/chat/completions";
                        else if (provider.equals("DeepSeek")) urlTarget = "https://api.deepseek.com/chat/completions";
                        else if (provider.equals("OpenRouter")) urlTarget = "https://openrouter.ai/api/v1/chat/completions";
                        else if (provider.equals("Cohere")) urlTarget = "https://api.cohere.ai/compatibility/v1/chat/completions";
                        finalResult = prosesStandardChat(urlTarget, apiKey, modelTarget, superPrompt.toString());
                    }

                    if (hideLinks) {
                        finalResult = finalResult.replaceAll("\\[\\[?\\d+\\]\\]?\\(.*?\\)", "");
                        finalResult = finalResult.replaceAll("https?://\\S+", "");
                    }
                    finalResult = finalResult.replaceAll("(?s)<think>.*?</think>", "").trim();
                    if (finalResult.startsWith("<think>")) finalResult = "";
                    finalResult = stripCommonAiPreamble(finalResult);
                    if (isGeminiTtsStyle) {
                        finalResult = cleanGeminiTtsScriptOutput(finalResult);
                    }

                    final String teksJadi = terapkanKamus(finalResult).trim();
                    if (teksJadi.isEmpty()) throw new Exception("Hasil respon AI kosong");
                    if (Thread.currentThread().isInterrupted()) return;

                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() {
                            setMicButtonState(false);
                            if (replaceEditorOnSuccess) {
                                // Revisi teks: bersihin dulu, langsung tempel hasil AI
                                InputConnection icNow = getCurrentInputConnection();
                                if (icNow != null) {
                                    clearAllTextInEditor(icNow);
                                }
                                ketikHasil(teksJadi, true);
                            } else {
                                ketikHasil(teksJadi, false);
                            }

                            if (prefs.getBoolean("auto_copy_mode", false)) {
                                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("Voice Input by Anas", teksJadi));
                            }

                            if (prefs.getBoolean("auto_send", false)) {
                                try {
                                    micHandler.postDelayed(new Runnable() {
                                        @Override public void run() {
                                            try { performSendAction(); } catch (Throwable ignored) {}
                                        }
                                    }, 180);
                                } catch (Throwable ignored) {}
                            }
                        }
                    });
                } catch (final Exception e) {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() { 
                            setMicButtonState(false);
                            if (!Thread.currentThread().isInterrupted()) {
                                Toast.makeText(KeyboardService.this, "Gangguan AI: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                // Jika dari Voice Input, tetap ketik teks mentah hasil suara agar tidak hilang.
                                // Jika dari Revisi Teks, JANGAN timpa/duplikasi karena teks asli masih utuh di kotak edit.
                                if (!replaceEditorOnSuccess && isVoiceInput) {
                                    ketikHasil(terapkanKamus(inputTeks), false);
                                }
                            }
                        }
                    });
                }
            }
        });
        aiThread.start();
    }

    private static boolean isGeminiTtsCreativeStyle(String gayaNama, String gayaPrompt) {
        String p = gayaPrompt == null ? "" : gayaPrompt;
        String n = gayaNama == null ? "" : gayaNama.toLowerCase(java.util.Locale.ROOT);
        return p.contains("[MODE: GEMINI_TTS]")
                || p.contains("Pembicara1:")
                || n.contains("gemini tts")
                || n.contains("gaya bebas")
                || n.contains("dialog podcast")
                || n.contains("bumbu ekspresi");
    }

    /** Hapus baris pengantar basa-basi AI ("Berikut...", "Tentu...") dan pagar markdown ``` bila muncul. */
    private static String stripCommonAiPreamble(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        t = t.replaceAll("(?s)^```[a-zA-Z0-9_-]*\\s*", "");
        t = t.replaceAll("(?s)\\s*```$", "").trim();
        String[] lines = t.split("\\r?\\n", -1);
        if (lines.length > 1) {
            String first = lines[0].trim().toLowerCase(java.util.Locale.ROOT);
            if (first.matches("^(berikut|tentu|baik|oke|siap|ini (dia|adalah|hasil|naskah|skrip)|hasil|naskah|skrip)[^\\n]{0,80}:\\s*$")
                    || first.startsWith("tentu, ini")
                    || first.startsWith("berikut adalah")
                    || first.startsWith("berikut ini")) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < lines.length; i++) {
                    if (sb.length() > 0 || !lines[i].trim().isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(lines[i]);
                    }
                }
                t = sb.toString().trim();
            }
        }
        return t;
    }

    /** Rapikan output skrip Gemini TTS agar bebas markdown ** / * dan memakai label Pembicara1:/Pembicara2: yang bersih. */
    private static String cleanGeminiTtsScriptOutput(String raw) {
        if (raw == null) return "";
        String t = raw.replace("**", "").replace("__", "");
        // Ubah *tertawa* -> [tertawa] bila ada model AI membandel memakai tanda bintang
        t = t.replaceAll("\\*([^\\*\\n]{2,30})\\*", "[$1]");
        t = t.replace("*", "");
        t = t.replaceAll("(?im)^\\s*(Speaker\\s*1|Pembicara\\s*1)\\s*:", "Pembicara1:");
        t = t.replaceAll("(?im)^\\s*(Speaker\\s*2|Pembicara\\s*2)\\s*:", "Pembicara2:");
        // Hapus catatan penutup singkat jika model menambahkan "Catatan:" atau "Semoga..." di baris terakhir
        String[] lines = t.split("\\r?\\n");
        if (lines.length > 1) {
            String last = lines[lines.length - 1].trim().toLowerCase(java.util.Locale.ROOT);
            if (last.startsWith("catatan:") || last.startsWith("semoga membantu") || last.startsWith("selamat mencoba")) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < lines.length - 1; i++) {
                    if (i > 0) sb.append("\n");
                    sb.append(lines[i]);
                }
                t = sb.toString().trim();
            }
        }
        return t.trim();
    }

    private void ketikHasil(String teks) {
        ketikHasil(teks, false);
    }

    private void ketikHasil(String teks, boolean isReplacedField) {
        try {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null && teks != null) {
                String toCommit = teks;
                if (!isReplacedField) {
                    String append = prefs.getString("append_mode", "space");
                    if (append.equals("space")) toCommit = " " + teks + " ";
                    else if (append.equals("newline")) toCommit = "\n" + teks;
                }
                // Langsung tempel — tanpa animasi ngetik karakter per karakter
                ic.commitText(toCommit, 1);
                checkAutoCapitalization();
                if (isBrailleModeActive && brailleTTSManager != null && teks.trim().length() > 0) {
                    brailleTTSManager.speak(teks.trim(), true);
                }
                pushClipboardHistory(teks.trim());
                playCustomSound("nempel");
                vibrate(120);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private double getConfiguredTemperature() {
        return AiEngine.resolveConfiguredTemperature(prefs, 0.7);
    }

    private String prosesCopilotExternalChat(String model, String instructionPrompt, String userText) throws Exception {
        URL url = new URL("https://apis.yogga.my.id/copilot");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(25000);
        conn.setReadTimeout(45000);

        JSONObject root = new JSONObject();
        root.put("instruction", instructionPrompt);
        root.put("text", (userText != null && !userText.isEmpty()) ? userText : instructionPrompt);
        root.put("model", (model != null && !model.isEmpty()) ? model : "chat");

        OutputStream os = conn.getOutputStream();
        os.write(root.toString().getBytes("UTF-8"));
        os.close();

        int responseCode = conn.getResponseCode();
        java.io.BufferedReader br;
        if (responseCode >= 200 && responseCode < 300) {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
        } else {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getErrorStream(), "UTF-8"));
        }
        StringBuilder res = new StringBuilder();
        String ln;
        while ((ln = br.readLine()) != null) res.append(ln);
        br.close();

        if (responseCode != 200) throw new Exception("HTTP " + responseCode + ": " + res.toString());

        JSONObject respJson = new JSONObject(res.toString());
        if (respJson.has("data")) {
            JSONObject data = respJson.getJSONObject("data");
            if (data.has("reply")) {
                return data.getString("reply");
            }
        }
        return res.toString();
    }

    private String prosesGeminiChat(String key, String model, String superPrompt) throws Exception {
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + key);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(); 
        conn.setRequestMethod("POST"); conn.setRequestProperty("Content-Type", "application/json"); conn.setDoOutput(true);
        conn.setConnectTimeout(20000); conn.setReadTimeout(60000); 

        JSONObject root = new JSONObject();
        root.put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", superPrompt)))));

        try {
            JSONObject genConfig = new JSONObject();
            genConfig.put("temperature", getConfiguredTemperature());
            double topP = AiEngine.resolveConfiguredTopP(prefs);
            if (topP > 0 && topP <= 1.0) genConfig.put("topP", topP);
            int maxTok = AiEngine.resolveMaxOutputTokens(prefs, "Google Gemini", model, false);
            if (maxTok > 0) genConfig.put("maxOutputTokens", maxTok);
            if (prefs.getBoolean("ai_json_mode", false)) {
                genConfig.put("responseMimeType", "application/json");
            }
            root.put("generationConfig", genConfig);
        } catch (Throwable ignored) {}
        
        OutputStream os = conn.getOutputStream(); os.write(root.toString().getBytes("UTF-8")); os.close();
        if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
        
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8")); 
        StringBuilder res = new StringBuilder(); String line; 
        while ((line = br.readLine()) != null) res.append(line); br.close();
        
        return new JSONObject(res.toString()).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
    }

    private String prosesStandardChat(String targetUrl, String key, String model, String superPrompt) throws Exception {
        URL url = new URL(targetUrl); HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST"); conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + key); conn.setDoOutput(true);
        conn.setConnectTimeout(20000); conn.setReadTimeout(60000);

        JSONObject root = new JSONObject();
        root.put("model", model);
        root.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", superPrompt)));

        try {
            root.put("temperature", getConfiguredTemperature());
            double topP = AiEngine.resolveConfiguredTopP(prefs);
            if (topP > 0 && topP <= 1.0) root.put("top_p", topP);
            String provNow = prefs.getString("selectedProvider", "Google Gemini");
            int maxTok = AiEngine.resolveMaxOutputTokens(prefs, provNow, model, false);
            if (maxTok > 0) root.put("max_tokens", maxTok);
            int seedVal = UiKit.readIntPref(prefs, "ai_seed", 0);
            if (seedVal != 0) {
                root.put("seed", seedVal);
            }
            if (prefs.getBoolean("ai_json_mode", false)) {
                root.put("response_format", new JSONObject().put("type", "json_object"));
            }
        } catch (Throwable ignored) {}
        
        String provider = prefs.getString("selectedProvider", "Google Gemini");
        if (provider.equals("Grok (xAI)")) {
            boolean grokSearch = prefs.getBoolean("grok_search_enabled", false);
            boolean grokXSearch = prefs.getBoolean("grok_x_search_enabled", false) || prefs.getBoolean("grok_x_search", false);
            
            // JANGAN aktifkan web search jika ini hanya untuk merapikan teks suara (STT), revisi, atau translasi
            boolean isSTTFormatting = superPrompt.contains("TUGAS STANDAR:")
                    || superPrompt.contains("MODE REVISI")
                    || superPrompt.contains("MODE TERJEMAHAN");
            if (isSTTFormatting) {
                grokSearch = false;
                grokXSearch = false;
            }

            if (grokSearch || grokXSearch) {
                root.put("tools", new JSONArray().put(new JSONObject().put("type", "live_search")));
            }
        }

        OutputStream os = conn.getOutputStream(); os.write(root.toString().getBytes("UTF-8")); os.close();
        
        int responseCode = conn.getResponseCode();

        // Perlindungan anti-error: jika server menolak parameter tambahan/tools (HTTP 422 / 400), otomatis coba lagi dengan payload dasar
        if (responseCode == 422 || responseCode == 400) {
            try { conn.disconnect(); } catch (Exception ignored) {}
            JSONObject fallbackRoot = new JSONObject();
            fallbackRoot.put("model", model);
            fallbackRoot.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", superPrompt)));
            conn = (HttpURLConnection) new URL(targetUrl).openConnection();
            conn.setRequestMethod("POST"); conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key); conn.setDoOutput(true);
            conn.setConnectTimeout(20000); conn.setReadTimeout(60000);
            os = conn.getOutputStream(); os.write(fallbackRoot.toString().getBytes("UTF-8")); os.close();
            responseCode = conn.getResponseCode();
        }

        java.io.BufferedReader br;
        if (responseCode >= 200 && responseCode < 300) {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
        } else {
            br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getErrorStream(), "UTF-8"));
        }
        StringBuilder res = new StringBuilder(); String ln;
        while ((ln = br.readLine()) != null) res.append(ln); br.close();
        
        if (responseCode != 200) throw new Exception("HTTP " + responseCode + ": " + res.toString());

        JSONObject respJson = new JSONObject(res.toString());
        JSONArray choices = respJson.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject choice0 = choices.getJSONObject(0);
            JSONObject message = choice0.optJSONObject("message");
            if (message != null) {
                String content = message.optString("content", "");
                if (!content.isEmpty()) return content;
            }
        }
        return res.toString();
    }

    private String transkripGeminiBase64(String audioPath, String key, String model) throws Exception {
        File file = new File(audioPath); FileInputStream fis = new FileInputStream(file); ByteArrayOutputStream bos = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int read; while ((read = fis.read(buf)) != -1) bos.write(buf, 0, read); fis.close();
        String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + key);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(); conn.setRequestMethod("POST"); conn.setRequestProperty("Content-Type", "application/json"); conn.setDoOutput(true);
        conn.setConnectTimeout(20000); conn.setReadTimeout(60000);
        
        String promptStt = "Transkripsikan audio ini secara akurat apa adanya (verbatim) sesuai bahasa pembicara secara otomatis. JANGAN MENGUBAH kata-kata santai/tidak formal menjadi baku atau formal (contoh: jangan ubah 'gak' jadi 'tidak', 'udah' jadi 'sudah'). Keluarkan teks ucapan saja tanpa pengantar.";
        JSONObject root = new JSONObject(); root.put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", promptStt)).put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", "audio/mp4").put("data", b64))))));
        OutputStream os = conn.getOutputStream(); os.write(root.toString().getBytes("UTF-8")); os.close();
        if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8")); StringBuilder res = new StringBuilder(); String line; while ((line = br.readLine()) != null) res.append(line); br.close();
        return new JSONObject(res.toString()).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
    }

    private String transkripGrokSTT(String audioPath, String key) throws Exception {
        final String[] holder = new String[2];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        JaringanAPI.kirimAudioKeGrokSTT(this, prefs, audioPath, key, new JaringanAPI.AudioCallback() {
            @Override public void onSuccess(String text) {
                holder[0] = text;
                latch.countDown();
            }
            @Override public void onError(String errorMsg) {
                holder[1] = errorMsg;
                latch.countDown();
            }
        });
        latch.await(90, java.util.concurrent.TimeUnit.SECONDS);
        if (holder[1] != null) throw new Exception(holder[1]);
        if (holder[0] == null || holder[0].trim().isEmpty()) throw new Exception("Teks kosong dari Grok STT");
        return holder[0];
    }

    private String transkripMultipart(String audioPath, String key, String model, String urlStr) throws Exception {
        java.io.File file = new java.io.File(audioPath); String boundary = "---VoiceAIBoundary" + System.currentTimeMillis();
        java.net.URL url = new java.net.URL(urlStr); java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection(); conn.setDoOutput(true); conn.setRequestMethod("POST"); conn.setRequestProperty("Authorization", "Bearer " + key); conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setConnectTimeout(20000); conn.setReadTimeout(60000);
        
        java.io.DataOutputStream dos = new java.io.DataOutputStream(conn.getOutputStream());
        dos.writeBytes("--" + boundary + "\r\n"); dos.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n");
        String langSrc = prefs.getString("lang_src", "Otomatis");
        if (!langSrc.equals("Otomatis") && !langSrc.equalsIgnoreCase("auto")) {
            String shortCode = "id";
            if (langSrc.contains("en-US") || langSrc.contains("Inggris")) shortCode = "en";
            else if (langSrc.contains("ar-SA") || langSrc.contains("Arab")) shortCode = "ar";
            else if (langSrc.contains("ja-JP") || langSrc.contains("Jepang")) shortCode = "ja";
            else if (langSrc.contains("ko-KR") || langSrc.contains("Korea")) shortCode = "ko";
            else if (langSrc.contains("Jerman")) shortCode = "de";
            else if (langSrc.contains("Prancis")) shortCode = "fr";
            dos.writeBytes("--" + boundary + "\r\n"); dos.writeBytes("Content-Disposition: form-data; name=\"language\"\r\n\r\n" + shortCode + "\r\n");
        }
        dos.writeBytes("--" + boundary + "\r\n"); dos.writeBytes("Content-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n"); dos.writeBytes("--" + boundary + "\r\n"); dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"\r\n"); dos.writeBytes("Content-Type: audio/mp4\r\n\r\n");
        java.io.FileInputStream fis = new java.io.FileInputStream(file); byte[] buf = new byte[4096]; int read; while ((read = fis.read(buf)) != -1) dos.write(buf, 0, read); fis.close();
        dos.writeBytes("\r\n--" + boundary + "--\r\n"); dos.flush(); dos.close();
        if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8")); StringBuilder res = new StringBuilder(); String line; while ((line = br.readLine()) != null) res.append(line); br.close();
        return new org.json.JSONObject(res.toString()).getString("text");
    }

    @Override
    public void onDestroy() {
        AuthManager.stopRealtimePremiumGuard("ime");
        super.onDestroy();
        try {
            hentikanPompaGoogle();
            sembunyikanIndikatorMic();
            micHandler.removeCallbacksAndMessages(null);
            if (speechRecognizer != null) speechRecognizer.destroy();
            if (mediaRecorder != null) { mediaRecorder.release(); mediaRecorder = null; }
            if (offlinePcmRecorder != null) { offlinePcmRecorder.stopAndDiscard(); offlinePcmRecorder = null; }
            if (soundPool != null) { soundPool.release(); soundPool = null; } 
            if (mediaPlayerNempel != null) { mediaPlayerNempel.release(); mediaPlayerNempel = null; }
            if (toneGen != null) { toneGen.release(); toneGen = null; }
            if (brailleTTSManager != null) { brailleTTSManager.shutdown(); brailleTTSManager = null; }
            hapusHandler.removeCallbacksAndMessages(null);

            if (aiThread != null && aiThread.isAlive()) aiThread.interrupt();

        } catch (Exception e) { e.printStackTrace(); }

    }


    
    private void showStickerPicker() { showPicker(false); }

    private void showEmojiPicker() { showPicker(true); }

    /** Satu panel pemilih untuk stiker dan emoji: grid 5 kolom, 40 per halaman, tombol Sebelumnya/Berikutnya. */
    private void showPicker(final boolean emojiMode) {
        try {
            final int pageSize = 40;
            final java.util.ArrayList<JSONObject> catalog = new java.util.ArrayList<>();
            final java.util.HashSet<String> seen = new java.util.HashSet<>();
            if (emojiMode) {
                for (String em : KeyboardData.EMOJI_LIST) {
                    if (em == null || em.isEmpty()) continue;
                    if (seen.add("text::" + em)) {
                        JSONObject o = new JSONObject();
                        o.put("type", "text"); o.put("label", em); o.put("value", em);
                        catalog.add(o);
                    }
                }
            }
            JSONArray recent = emojiMode ? new JSONArray() : loadRecentStickers();
            JSONArray saved = emojiMode ? new JSONArray() : loadStickerItems();
            // Recent and saved items first, deduplicated; do not build views for the whole collection.
            for (int pass = 0; pass < 2; pass++) {
                JSONArray source = pass == 0 ? recent : saved;
                for (int j = pass == 0 ? 0 : source.length() - 1;
                     pass == 0 ? j < source.length() : j >= 0;
                     j += pass == 0 ? 1 : -1) {
                    JSONObject o = source.optJSONObject(j);
                    if (o == null) continue;
                    String value = o.optString("value", "");
                    if (value.isEmpty()) continue;
                    String key = o.optString("type", "text") + "::" + value;
                    if (seen.add(key)) catalog.add(o);
                }
            }
            // Gboard-like compact emoji panel fallback, paginated in groups of 40.
            if (!emojiMode) {
                String[] emoji = {"😀","😃","😄","😁","😆","🥹","😂","🤣","😊","😍","🥰","😘","😎","🤗","🤔","😴","😭","😅","😇","🙌","👏","👍","👎","🙏","💪","❤️","💖","💯","🔥","✨","🎉","🎊","🌹","☕","🍰","🌈","⭐","🌟","💐","🤝","💡","📚","✍️","🎯","🚀","🌻","🐱","🐼","🦋","🍀","🤩","😋","😜","🤪","😌","🥳","🤝","🫶","💐","🌷","🌸","🌺","🌼","🌞","🌙","⚡","☀️","🌈","🍓","🍉","🍕","🍔","🍟","🎂","🧁","🐶","🐻","🐸","🐰","🦄","🐝","🐬","🦁","🐨","🐧","🦊","🌍","🎁","🎈","🎵","🎶","🏆","💌","💬","✅","❌","💤","💫","🧡","💛","💚","💙","💜","🤍","🖤","🤎","💞","💕","💗","💓","💘","💝","💟","🩷","🩵","🩶"};
                for (String em : emoji) {
                    JSONObject o = new JSONObject();
                    o.put("type", "text"); o.put("label", "Stiker " + em); o.put("value", em);
                    if (seen.add("text::" + em)) catalog.add(o);
                }
                String[] phrases = {"Halo", "Terima kasih", "Siap", "Mantap", "Semangat", "Maaf", "Setuju", "Oke", "Selamat", "Sukses", "Aamiin", "Keren", "Lucu", "Sabar", "Gas", "Ayo", "Sampai jumpa", "Selamat pagi", "Selamat malam", "Alhamdulillah"};
                String[] suffixes = {"", "!", "!!", " ❤️", " ✨", " 🙏", " 👍", " 😄", " 🌹", " 💯"};
                int comboCount = 0;
                for (String phrase : phrases) {
                    for (String em : emoji) {
                        for (String suffix : suffixes) {
                            String value = em + " " + phrase + suffix;
                            if (seen.add("text::" + value)) {
                                JSONObject o = new JSONObject();
                                o.put("type", "text"); o.put("label", value); o.put("value", value);
                                catalog.add(o);
                            }
                            if (++comboCount >= 1000) break;
                        }
                        if (comboCount >= 1000) break;
                    }
                    if (comboCount >= 1000) break;
                }
            }
            final android.widget.LinearLayout box = new android.widget.LinearLayout(this);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int)(8 * getResources().getDisplayMetrics().density + .5f);
            box.setPadding(pad, pad, pad, pad);
            final android.widget.TextView counter = new android.widget.TextView(this);
            counter.setGravity(android.view.Gravity.CENTER);
            counter.setPadding(0, pad / 2, 0, pad / 2);
            box.addView(counter, new android.widget.LinearLayout.LayoutParams(-1, -2));
            final android.widget.GridLayout grid = new android.widget.GridLayout(this);
            grid.setColumnCount(5);
            box.addView(grid, new android.widget.LinearLayout.LayoutParams(-1, (int)(350 * getResources().getDisplayMetrics().density + .5f)));
            final android.widget.LinearLayout nav = new android.widget.LinearLayout(this);
            nav.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            box.addView(nav, new android.widget.LinearLayout.LayoutParams(-1, -2));
            final int[] page = new int[]{0};
            final android.app.AlertDialog dialog = UiKit.dialog(this, prefs)
                .setTitle(emojiMode ? "Emoji" : "Stiker & emoji")
                .setView(box)
                .create();
            final android.widget.Button prev = new android.widget.Button(this);
            prev.setText("‹ Sebelumnya"); prev.setAllCaps(false);
            final android.widget.Button next = new android.widget.Button(this);
            next.setText("Berikutnya ›"); next.setAllCaps(false);
            final android.widget.Button manage = new android.widget.Button(this);
            manage.setText(emojiMode ? "Hapus" : "Kelola"); manage.setAllCaps(false);
            if (emojiMode) manage.setContentDescription("Hapus karakter sebelum kursor");
            nav.addView(prev, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            nav.addView(next, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            nav.addView(manage, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            Runnable[] render = new Runnable[1];
            render[0] = () -> {
                grid.removeAllViews();
                int pages = Math.max(1, (catalog.size() + pageSize - 1) / pageSize);
                int from = page[0] * pageSize, to = Math.min(catalog.size(), from + pageSize);
                counter.setText("Halaman " + (page[0] + 1) + " dari " + pages + " • " + catalog.size() + (emojiMode ? " emoji" : " stiker"));
                prev.setEnabled(page[0] > 0); next.setEnabled(page[0] + 1 < pages);
                for (int i = from; i < to; i++) {
                    final JSONObject item = catalog.get(i);
                    String value = item.optString("value", "");
                    android.widget.Button b = new android.widget.Button(this);
                    b.setAllCaps(false); b.setMinHeight((int)(42 * getResources().getDisplayMetrics().density + .5f));
                    b.setPadding(0, 0, 0, 0); b.setTextSize(20);
                    String label = value;
                    if (label.length() > 5) label = label.substring(0, 5) + "…";
                    b.setText(label);
                    b.setContentDescription(emojiMode ? value : (item.optString("label", "Stiker") + ": " + value));
                    android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
                    lp.width = 0; lp.height = (int)(42 * getResources().getDisplayMetrics().density + .5f);
                    lp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
                    lp.setMargins(2, 2, 2, 2);
                    grid.addView(b, lp);
                    b.setOnClickListener(v -> {
                        try {
                            if (emojiMode) {
                                // Emoji disisipkan sebagai teks; panel tetap terbuka supaya bisa memilih beberapa emoji.
                                String emojiChosen = item.optString("value", "");
                                if (!emojiChosen.isEmpty()) {
                                    InputConnection emojiIc = getCurrentInputConnection();
                                    if (emojiIc != null) emojiIc.commitText(emojiChosen, 1);
                                    playCustomSound("ketik"); vibrate(20);
                                }
                                return;
                            }
                            rememberSticker(item);
                            String type = item.optString("type", "text");
                            String chosen = item.optString("value", "");
                            if ("image".equals(type)) {
                                commitStickerImage(chosen, item.optString("label", "Stiker gambar"));
                            } else if (!chosen.isEmpty()) {
                                // Kirim katalog emoji/teks sebagai gambar WebP, bukan mengetikkan teks ke kotak pesan.
                                String stickerUri = createTextStickerImage(chosen);
                                if (stickerUri != null) commitStickerImage(stickerUri, item.optString("label", "Stiker"));
                                else Toast.makeText(this, "Gambar stiker gagal dibuat.", Toast.LENGTH_SHORT).show();
                            }
                            dialog.dismiss();
                        } catch (Exception ignored) { Toast.makeText(this, "Stiker tidak dapat dipilih.", Toast.LENGTH_SHORT).show(); }
                    });
                }
            };
            prev.setOnClickListener(v -> { if (page[0] > 0) { page[0]--; render[0].run(); } });
            next.setOnClickListener(v -> { int pages = Math.max(1, (catalog.size() + pageSize - 1) / pageSize); if (page[0] + 1 < pages) { page[0]++; render[0].run(); } });
            manage.setOnClickListener(v -> {
                if (emojiMode) {
                    InputConnection delIc = getCurrentInputConnection();
                    if (delIc != null) {
                        delIc.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                        delIc.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
                    }
                    playCustomSound("ketik"); vibrate(20);
                } else {
                    dialog.dismiss(); openStickerStudio();
                }
            });
            render[0].run();
            // IME is a service window, not an Activity window. Attach the dialog to the live IME token.
            android.view.Window w = dialog.getWindow();
            if (w != null) {
                w.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG);
                android.view.WindowManager.LayoutParams attrs = w.getAttributes();
                try { attrs.token = getWindow().getWindow().getDecorView().getWindowToken(); } catch (Exception ignored) {}
                w.setAttributes(attrs);
            }
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout((int)(Math.min(420, getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density - 24) * getResources().getDisplayMetrics().density), -2);
            }
        } catch (Throwable e) {
            // Never let a picker failure terminate the keyboard service.
            try { Toast.makeText(this, emojiMode ? "Panel emoji tidak dapat dibuka. Keyboard tetap aktif." : "Panel stiker tidak dapat dibuka. Keyboard tetap aktif.", Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
        }
    }

    private JSONArray loadStickerItems() {
        try { return new JSONArray(prefs.getString("sticker_items_json", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    private JSONArray loadRecentStickers() {
        try { return new JSONArray(prefs.getString("sticker_recent_json", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
    private void rememberSticker(JSONObject source) {
        try {
            JSONArray old = loadRecentStickers(), next = new JSONArray();
            JSONObject copy = new JSONObject();
            copy.put("type", source.optString("type", "text"));
            copy.put("label", source.optString("label", "Stiker"));
            copy.put("value", source.optString("value", ""));
            next.put(copy);
            for (int i = 0; i < old.length() && next.length() < 20; i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && !copy.optString("value", "").equals(item.optString("value", ""))) next.put(item);
            }
            prefs.edit().putString("sticker_recent_json", next.toString()).apply();
        } catch (Exception ignored) {}
    }
    private void openStickerStudio() {
        try {
            Intent intent = new Intent(KeyboardService.this, StickerStudioActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) { Toast.makeText(this, "Pengelola stiker tidak dapat dibuka.", Toast.LENGTH_SHORT).show(); }
    }
    private void commitTextSafely(String text) {
        try {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText(text, 1);
            else Toast.makeText(this, "Kolom teks tidak sedang aktif.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "Stiker teks tidak dapat disisipkan di kolom ini.", Toast.LENGTH_SHORT).show(); }
    }
    private String createTextStickerImage(String text) {
        android.graphics.Bitmap bitmap = null;
        java.io.FileOutputStream out = null;
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "keyboard_stickers");
            if (!dir.exists() && !dir.mkdirs()) return null;
            String safeName = "sticker_" + java.util.UUID.randomUUID().toString().replace("-", "") + ".png";
            java.io.File file = new java.io.File(dir, safeName);
            final int size = 512;
            bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            // Transparent outside, with a white sticker border and a colored rounded card.
            android.graphics.RectF card = new android.graphics.RectF(24, 54, size - 24, size - 54);
            paint.setColor(android.graphics.Color.WHITE); canvas.drawRoundRect(card, 64, 64, paint);
            android.graphics.RectF inner = new android.graphics.RectF(36, 66, size - 36, size - 66);
            paint.setColor(0xFFFFF4D6); canvas.drawRoundRect(inner, 56, 56, paint);
            String value = text == null ? "" : text.trim();
            java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            android.text.TextPaint tp = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            tp.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            tp.setTextSize(value.codePointCount(0, value.length()) <= 4 ? 116 : 54);
            android.text.StaticLayout layout = android.text.StaticLayout.Builder.obtain(value, 0, value.length(), tp, 400)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(true).setMaxLines(5).setEllipsize(android.text.TextUtils.TruncateAt.END).build();
            canvas.save(); canvas.translate(56, (size - layout.getHeight()) / 2f); layout.draw(canvas); canvas.restore();
            out = new java.io.FileOutputStream(file);
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            out.flush(); out.close(); out = null;
            return Uri.parse("content://com.annas.stickerprovider/sticker/" + safeName).toString();
        } catch (Exception e) {
            return null;
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private void commitStickerImage(String uriString, String label) {
        try {
            Uri uri = Uri.parse(uriString);
            InputConnection ic = getCurrentInputConnection();
            EditorInfo info = getCurrentInputEditorInfo();
            if (Build.VERSION.SDK_INT >= 25 && ic != null && info != null) {
                String mime = "com.annas.stickerprovider".equals(uri.getAuthority()) ? "image/png" : getContentResolver().getType(uri);
                if (mime == null || !mime.startsWith("image/")) mime = "image/png";
                // Offer both the exact raster MIME type and image/* for apps with broader IME support.
                ClipDescription description = new ClipDescription(label, new String[]{mime, "image/*"});
                InputContentInfo content = new InputContentInfo(uri, description, null);
                try { if (info.packageName != null) grantUriPermission(info.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                if (ic.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, new Bundle())) {
                    Toast.makeText(this, "Gambar dikirim ke kolom aplikasi. Tampilan sebagai stiker bergantung pada aplikasi tujuan.", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            // Some fields (notably certain Status/Story composers) reject IME content.
            // Offer Android's standard image-share flow as a fallback instead of silently failing.
            launchStickerShareFallback(uri, label);
        } catch (Exception e) {
            try { launchStickerShareFallback(Uri.parse(uriString), label); }
            catch (Exception ignored) { Toast.makeText(this, "Gambar stiker tidak dapat dibagikan dari kolom ini.", Toast.LENGTH_LONG).show(); }
        }
    }

    private void launchStickerShareFallback(Uri uri, String label) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            String shareMime = getContentResolver().getType(uri);
            if (shareMime == null || !shareMime.startsWith("image/")) shareMime = "image/png";
            send.setType(shareMime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(android.content.ClipData.newUri(getContentResolver(), label == null ? "Stiker" : label, uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent chooser = Intent.createChooser(send, "Bagikan stiker gambar");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
            Toast.makeText(this, "Kolom menolak sisipan langsung. Pilih WhatsApp pada menu bagikan untuk melanjutkan.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Aplikasi tujuan menolak sisipan dan menu bagikan tidak tersedia.", Toast.LENGTH_LONG).show();
        }
    }


@Override
    
public void onCommitText(String str) {
        
        if ("🧩".equals(str)) {
            showStickerPicker();
            return;
        }
        if (str.equals("LONG_SPACE")) {
            announceQwertyWindowState("Pilih keyboard");
            if (customKeyboardView != null) customKeyboardView.announceDirect("Pilih keyboard");
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
            return;
        } else if (str.equals("LONG_DELETE")) {
            android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                // Prioritas: kalau ada seleksi (Select All / TalkBack), hapus dulu semua yang terpilih
                CharSequence selected = null;
                try {
                    selected = ic.getSelectedText(0);
                } catch (Throwable ignored) {}
                if (selected != null && selected.length() > 0) {
                    try {
                        ic.commitText("", 1);
                    } catch (Throwable ignored) {}
                    syncQwertyWordBufFromCursor(ic);
                    checkAutoCapitalization();
                    return;
                }

                boolean advancedEdit = prefs != null && prefs.getBoolean("advanced_edit_mode_enabled", false);
                String deleteMode = advancedEdit && prefs != null
                    ? prefs.getString("advanced_long_delete_mode", "word") : "word";
                // Ketukan singkat tetap menggunakan onDelete() dan selalu menghapus satu karakter.
                // Mode tingkat lanjut hanya mengubah aksi tekan lama; mode nonaktif mempertahankan aksi lama.
                if ("character".equals(deleteMode)) {
                    CharSequence one = ic.getTextBeforeCursor(1, 0);
                    if (one != null && one.length() > 0) lastDeletedUndoText = one.toString();
                    ic.deleteSurroundingText(1, 0);
                } else if ("paragraph".equals(deleteMode)) {
                    CharSequence before = ic.getTextBeforeCursor(5000, 0);
                    if (before != null && before.length() > 0) {
                        String textBefore = before.toString();
                        int lastNewline = Math.max(textBefore.lastIndexOf("\n"), textBefore.lastIndexOf("\r"));
                        int count = lastNewline >= 0 ? textBefore.length() - lastNewline : textBefore.length();
                        int delLen = Math.max(1, count);
                        lastDeletedUndoText = textBefore.substring(Math.max(0, textBefore.length() - delLen));
                        ic.deleteSurroundingText(delLen, 0);
                    }
                } else {
                    CharSequence textBefore = ic.getTextBeforeCursor(500, 0);
                    if (textBefore != null && textBefore.length() > 0) {
                        String tb = textBefore.toString();
                        int end = tb.length();
                        while (end > 0 && Character.isWhitespace(tb.charAt(end - 1))) end--;
                        int start = end;
                        while (start > 0 && !Character.isWhitespace(tb.charAt(start - 1))) start--;
                        int count = tb.length() - start;
                        if (count <= 0) count = 1;
                        int delLen = Math.min(count, tb.length());
                        lastDeletedUndoText = tb.substring(tb.length() - delLen);
                        ic.deleteSurroundingText(delLen, 0);
                    }
                }
                syncQwertyWordBufFromCursor(ic);
                checkAutoCapitalization();
            }
            return;
        } else if (str.equals("⚙️")) {
            Intent intent = new Intent(KeyboardService.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        } else if (str.equals("😊")) {
            showEmojiPicker();
            return;
        } else if (str.equals("𝔽")) {
            if (layoutFont != null) {
                if (layoutFont.getVisibility() == View.VISIBLE) {
                    sembunyikanSemuaLayer();
                } else {
                    sembunyikanSemuaLayer();
                    populateFontPanel(KeyboardService.this);
                    layoutFont.setVisibility(View.VISIBLE);
                }
            }
            return;
        } else if (str.equals("📝")) {
            try {
                Intent documentIntent = new Intent(KeyboardService.this, DocumentStudioActivity.class);
                documentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(documentIntent);
            } catch (Exception e) {
                android.widget.Toast.makeText(KeyboardService.this, "Editor Word & Dokumen tidak dapat dibuka.", android.widget.Toast.LENGTH_SHORT).show();
            }
            return;
        } else if (str.equals("⚡")) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() { toggleQuickToolsPanel(); }
            });
            return;
        } else if (str.equals("✍️")) {
            String contextText = extractAllEditorText(getCurrentInputConnection());
            mulaiProsesRevisiTeks(contextText);
            return;
        } else if (str.equals("🤖")) {
            triggerSmartReply();
            return;
        } else if (str.equals("📋")) {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                String text = clip.getItemAt(0).getText().toString();
                android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.commitText(text, 1);
            }
            return;
        } else if (str.equals("🎤")) {
            vibrate(50);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    if (prefs.getBoolean("use_whisper", false)) {
                        if (isRecordingWhisper) stopRekamWhisper(); else mulaiRekamWhisper();
                    } else {
                        mulaiGoogleSTT();
                    }
                }
            });
            return;
        }

        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            EditorInfo ei = null;
            try { ei = getCurrentInputEditorInfo(); } catch (Throwable ignored) {}
            if (str.equals(" ") && prefs.getBoolean("double_space_period", true) && !shouldDisableAutoCap(ei)) {
                long now = System.currentTimeMillis();
                if (now - lastSpaceTime < 500) {
                    CharSequence lastChar = ic.getTextBeforeCursor(1, 0);
                    if (lastChar != null && lastChar.toString().equals(" ")) {
                        ic.deleteSurroundingText(1, 0);
                        ic.commitText(". ", 1);
                        lastSpaceTime = 0L;
                        checkAutoCapitalization();
                        return;
                    }
                }
                lastSpaceTime = now;
            }
            if (str.length() == 1 && Character.isLetter(str.charAt(0)) && currentFontIndex > 0) {
                boolean isUpper = Character.isUpperCase(str.charAt(0));
                str = getMappedChar(str, isUpper, currentFontIndex);
            }

            boolean isSensitive = isPasswordOrSensitiveEditor(ei);
            boolean blockSmartTransform = shouldDisableAutoCap(ei);
            String wordBeforeCommit = "";
            String actualWordForSpace = "";
            if (!isSensitive) {
                if (str.equals(" ") || str.equals("\n")) {
                    actualWordForSpace = resolveActualWordAtCursor(ic, qwertyWordBuf);
                } else if (str.length() == 1 && Character.isLetterOrDigit(str.charAt(0))) {
                    CharSequence rawBefore = null;
                    try { rawBefore = ic.getTextBeforeCursor(64, 0); } catch (Throwable ignored) {}
                    if (rawBefore != null) {
                        wordBeforeCommit = extractTrailingWord(rawBefore);
                    }
                }
            }

            ic.commitText(str, 1);

            if (!isSensitive) {
                sessionCharsTyped += str.length();
            }

            if (str.equals(" ") && !blockSmartTransform) {
                boolean transformed = false;
                if (prefs != null && prefs.getBoolean("inline_calculator_enabled", true)) {
                    transformed = tryInlineCalculateAfterSpace(ic);
                }
                if (!transformed && prefs != null && prefs.getBoolean("macro_auto_expand_enabled", true)) {
                    transformed = tryAutoExpandMacroAfterSpace(ic);
                    if (transformed) sessionMacrosUsed++;
                }
            } else if (str.length() == 1 && !blockSmartTransform && prefs != null && prefs.getBoolean("auto_space_after_punct", false)) {
                char pc = str.charAt(0);
                if (pc == ',' || pc == '?' || pc == '!' || pc == ';') {
                    ic.commitText(" ", 1);
                }
            }

            if (!isSensitive) {
                if (str.equals(" ") || str.equals("\n")) {
                    if (!actualWordForSpace.isEmpty()) {
                        sessionWordsTyped++;
                        maybeSpeakQwertyWord(actualWordForSpace);
                    }
                    qwertyWordBuf.setLength(0);
                } else if (str.length() == 1) {
                    char ch = str.charAt(0);
                    if (Character.isLetterOrDigit(ch)) {
                        if (!wordBeforeCommit.isEmpty() || ic != null) {
                            // Sinkronkan buffer dari isi aktual sebelum kursor + huruf baru agar bebas efek huruf terhapus
                            String synced = extractWordBeforeCursor(ic);
                            if (!synced.isEmpty()) {
                                qwertyWordBuf.setLength(0);
                                qwertyWordBuf.append(synced);
                            } else if (!wordBeforeCommit.isEmpty()) {
                                qwertyWordBuf.setLength(0);
                                qwertyWordBuf.append(wordBeforeCommit).append(ch);
                            } else {
                                qwertyWordBuf.append(ch);
                            }
                        } else {
                            qwertyWordBuf.append(ch);
                        }
                        maybeSpeakQwertyChar(str);
                    } else {
                        qwertyWordBuf.setLength(0);
                    }
                } else {
                    syncQwertyWordBufFromCursor(ic);
                }
            }

            // Hanya spasi/newline atau teks panjang yang cek auto-kapital; titik/tanda baca saja tidak memicu huruf besar.
            if (str.length() == 1) {
                char c = str.charAt(0);
                if (c == ' ' || c == '\n') {
                    checkAutoCapitalization();
                } else if (shiftState == 1) {
                    shiftState = 0;
                    if (customKeyboardView != null) customKeyboardView.setAutoCapitalize(false);
                }
            } else {
                checkAutoCapitalization();
            }
        }
    }

    @Override
    public void onMoveCursor(int delta) {
        if (delta == 0) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        int keyCode = delta < 0
                ? android.view.KeyEvent.KEYCODE_DPAD_LEFT
                : android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
        int steps = Math.min(Math.abs(delta), 8);
        try {
            for (int i = 0; i < steps; i++) {
                ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode));
                ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode));
            }
        } catch (Throwable ignored) {}
        syncQwertyWordBufFromCursor(ic);
    }

    @Override
    public void onClearAll() {
        android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            try {
                CharSequence prev = ic.getTextBeforeCursor(5000, 0);
                if (prev != null && prev.length() > 0) lastDeletedUndoText = prev.toString();
            } catch (Throwable ignored) {}
            ic.deleteSurroundingText(10000, 10000);
        }
        qwertyWordBuf.setLength(0);
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        if (!isBrailleModeActive) {
            EditorInfo ei = null;
            try { ei = getCurrentInputEditorInfo(); } catch (Throwable ignored) {}
            if (!isPasswordOrSensitiveEditor(ei)) {
                syncQwertyWordBufFromCursor(getCurrentInputConnection());
            } else {
                qwertyWordBuf.setLength(0);
            }
        }
    }

    private static String extractTrailingWord(CharSequence before) {
        if (before == null || before.length() == 0) return "";
        int end = before.length();
        int start = end;
        while (start > 0 && Character.isLetterOrDigit(before.charAt(start - 1))) {
            start--;
        }
        return before.subSequence(start, end).toString();
    }

    private String extractWordBeforeCursor(InputConnection ic) {
        if (ic == null) return "";
        try {
            return extractTrailingWord(ic.getTextBeforeCursor(64, 0));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String resolveActualWordAtCursor(InputConnection ic, StringBuilder fallback) {
        if (ic != null) {
            try {
                CharSequence before = ic.getTextBeforeCursor(64, 0);
                if (before != null) {
                    return extractTrailingWord(before);
                }
            } catch (Throwable ignored) {}
        }
        return fallback != null ? fallback.toString() : "";
    }

    private void syncQwertyWordBufFromCursor(InputConnection ic) {
        if (ic == null) return;
        try {
            CharSequence before = ic.getTextBeforeCursor(64, 0);
            if (before == null) return;
            String w = extractTrailingWord(before);
            qwertyWordBuf.setLength(0);
            if (!w.isEmpty()) qwertyWordBuf.append(w);
        } catch (Throwable ignored) {}
    }

    private void flushTypingStats() {
        if (prefs == null) return;
        if (sessionCharsTyped == 0 && sessionWordsTyped == 0 && sessionMacrosUsed == 0) return;
        try {
            prefs.edit()
                .putLong("stat_chars_typed", prefs.getLong("stat_chars_typed", 0L) + sessionCharsTyped)
                .putLong("stat_words_typed", prefs.getLong("stat_words_typed", 0L) + sessionWordsTyped)
                .putLong("stat_macros_used", prefs.getLong("stat_macros_used", 0L) + sessionMacrosUsed)
                .apply();
            sessionCharsTyped = 0L;
            sessionWordsTyped = 0L;
            sessionMacrosUsed = 0L;
        } catch (Throwable ignored) {}
    }

    private void maybeSpeakQwertyChar(String chStr) {
        if (isBrailleModeActive || prefs == null) return;
        String mode = prefs.getString("qwerty_tts_echo", "off");
        if (!"char".equals(mode) && !"char_word".equals(mode)) return;
        if (prefs.getBoolean("qwerty_echo_use_screen_reader", false)) {
            if (brailleTTSManager != null) brailleTTSManager.stop();
            announceForAccessibility(chStr);
            return;
        }
        if (brailleTTSManager == null) brailleTTSManager = new BrailleTTSManager(this, prefs, null);
        brailleTTSManager.speak(chStr, true);
    }

    private void maybeSpeakQwertyWord(String word) {
        if (isBrailleModeActive || prefs == null || word == null || word.isEmpty()) return;
        String mode = prefs.getString("qwerty_tts_echo", "off");
        if (!"word".equals(mode) && !"char_word".equals(mode)) return;
        if (prefs.getBoolean("qwerty_echo_use_screen_reader", false)) {
            if (brailleTTSManager != null) brailleTTSManager.stop();
            announceForAccessibility(word);
            return;
        }
        if (brailleTTSManager == null) brailleTTSManager = new BrailleTTSManager(this, prefs, null);
        brailleTTSManager.speak(word, true);
    }

    private boolean tryInlineCalculateAfterSpace(InputConnection ic) {
        try {
            CharSequence before = ic.getTextBeforeCursor(40, 0);
            if (before == null) return false;
            String s = before.toString();
            if (!s.endsWith("= ")) return false;
            String body = s.substring(0, s.length() - 2);
            int start = body.length();
            while (start > 0) {
                char c = body.charAt(start - 1);
                if (Character.isDigit(c) || "+-*/%().x×÷,".indexOf(c) >= 0) start--;
                else break;
            }
            if (start == body.length()) return false;
            String expr = body.substring(start);
            String res = KeyboardQuickPanelHelper.evaluateMathExpression(expr);
            if (res == null) return false;
            ic.deleteSurroundingText(1, 0); // hapus spasi setelah '='
            ic.commitText(res + " ", 1);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean tryAutoExpandMacroAfterSpace(InputConnection ic) {
        try {
            String raw = prefs != null ? prefs.getString("json_macros", "[]") : "[]";
            if (raw == null || raw.length() <= 2) return false;
            CharSequence before = ic.getTextBeforeCursor(32, 0);
            if (before == null || before.length() < 2) return false;
            String s = before.toString();
            if (!s.endsWith(" ")) return false;
            String withoutSpace = s.substring(0, s.length() - 1);
            int start = withoutSpace.length();
            while (start > 0 && !Character.isWhitespace(withoutSpace.charAt(start - 1))) start--;
            String token = withoutSpace.substring(start);
            if (token.isEmpty()) return false;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String trigger = obj.optString("pemicu", "");
                String repl = obj.optString("isi", "");
                if (!trigger.isEmpty() && token.equalsIgnoreCase(trigger)) {
                    ic.deleteSurroundingText(token.length() + 1, 0);
                    ic.commitText(repl + " ", 1);
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void hideQuickToolsPanel() {
        if (quickToolsDialog != null) {
            try {
                if (quickToolsDialog.isShowing()) quickToolsDialog.dismiss();
            } catch (Throwable ignored) {}
            quickToolsDialog = null;
        }
    }

    private void toggleQuickToolsPanel() {
        if (quickToolsDialog != null && quickToolsDialog.isShowing()) {
            hideQuickToolsPanel();
            return;
        }
        syncClipboardFromSystem();
        android.os.IBinder token = null;
        try {
            if (getWindow() != null && getWindow().getWindow() != null) {
                token = getWindow().getWindow().getDecorView().getWindowToken();
            }
        } catch (Throwable ignored) {}
        if (token == null && rootKeyboardView != null) {
            token = rootKeyboardView.getWindowToken();
        }
        try {
            quickToolsDialog = KeyboardQuickPanelHelper.showDialog(
                    this, token, prefs, clipboardHistory, new KeyboardQuickPanelHelper.Actions() {
                @Override public String getLastDeletedText() { return lastDeletedUndoText; }
                @Override public void restoreLastDeletedText() {
                    if (lastDeletedUndoText == null || lastDeletedUndoText.isEmpty()) {
                        Toast.makeText(KeyboardService.this, "Belum ada teks terhapus untuk dikembalikan", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(lastDeletedUndoText, 1);
                        lastDeletedUndoText = "";
                    }
                }
                @Override public void commitSnippet(String text) {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null && text != null) ic.commitText(text, 1);
                }
                @Override public void sendKeyCode(int keyCode) {
                    sendDownUpKeyEvents(keyCode);
                }
                @Override public void selectAllText() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) ic.performContextMenuAction(android.R.id.selectAll);
                }
                @Override public void deleteLastWord() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic == null) return;
                    CharSequence before = ic.getTextBeforeCursor(80, 0);
                    if (before == null || before.length() == 0) return;
                    int i = before.length() - 1;
                    while (i >= 0 && Character.isWhitespace(before.charAt(i))) i--;
                    while (i >= 0 && !Character.isWhitespace(before.charAt(i))) i--;
                    int delLen = before.length() - 1 - i;
                    if (delLen > 0) {
                        lastDeletedUndoText = before.subSequence(before.length() - delLen, before.length()).toString();
                        ic.deleteSurroundingText(delLen, 0);
                    }
                }
                @Override public void transformCase(int mode) {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic == null) return;
                    CharSequence sel = ic.getSelectedText(0);
                    if (sel != null && sel.length() > 0) {
                        ic.commitText(ExtraToolsUI.applyTextOperation(sel.toString(), mode), 1);
                        return;
                    }
                    CharSequence before = ic.getTextBeforeCursor(80, 0);
                    if (before == null || before.length() == 0) return;
                    int end = before.length();
                    int i = end - 1;
                    while (i >= 0 && Character.isWhitespace(before.charAt(i))) i--;
                    int wEnd = i + 1;
                    while (i >= 0 && !Character.isWhitespace(before.charAt(i))) i--;
                    int wStart = i + 1;
                    if (wEnd > wStart) {
                        String word = before.subSequence(wStart, wEnd).toString();
                        String tail = before.subSequence(wEnd, end).toString();
                        ic.deleteSurroundingText(end - wStart, 0);
                        ic.commitText(ExtraToolsUI.applyTextOperation(word, mode) + tail, 1);
                    }
                }
                @Override public void speakFieldText() {
                    InputConnection ic = getCurrentInputConnection();
                    String txt = extractAllEditorText(ic);
                    if (txt == null || txt.trim().isEmpty()) {
                        brailleTTSManager.speak("Kolom teks masih kosong", true);
                    } else {
                        brailleTTSManager.speak(txt, true);
                    }
                }
                @Override public void speakText(String text) {
                    if (brailleTTSManager != null && text != null && !text.trim().isEmpty()) {
                        brailleTTSManager.speak(text, true);
                    }
                }
                @Override public void openMainApp() {
                    hideQuickToolsPanel();
                    try {
                        Intent it = new Intent(KeyboardService.this, MainActivity.class);
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(it);
                    } catch (Throwable ignored) {}
                }
                @Override public void openBusinessOrderForm() {
                    hideQuickToolsPanel();
                    try {
                        Intent it = new Intent(KeyboardService.this, MainActivity.class);
                        it.putExtra("open_tool", "business_add");
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(it);
                    } catch (Throwable ignored) {}
                }
                @Override public void clearNonPinnedClips() {
                    clearClipboardHistory();
                    Toast.makeText(KeyboardService.this, "Riwayat klip dibersihkan", Toast.LENGTH_SHORT).show();
                }
                @Override public void toggleOneHandedMode() {
                    String cur = prefs != null ? prefs.getString("one_handed_mode", "off") : "off";
                    String next = "off".equals(cur) ? "right" : ("right".equals(cur) ? "left" : "off");
                    if (prefs != null) prefs.edit().putString("one_handed_mode", next).apply();
                    if (customKeyboardView != null) customKeyboardView.reloadLayoutConfig();
                }
                @Override public void switchToBraille() { switchToBrailleKeyboard(); }
                @Override public void toggleScriptLanguage() {
                    String cur = prefs != null ? prefs.getString("keyboard_script", "latin") : "latin";
                    String next = "arabic".equals(cur) ? "latin" : "arabic";
                    if (prefs != null) prefs.edit().putString("keyboard_script", next).apply();
                    if (customKeyboardView != null) customKeyboardView.reloadLayoutConfig();
                }
            });
            if (quickToolsDialog != null) {
                quickToolsDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(android.content.DialogInterface d) {
                        quickToolsDialog = null;
                    }
                });
            }
        } catch (Throwable e) {
            Toast.makeText(KeyboardService.this, "Gagal membuka Alat Cepat.", Toast.LENGTH_SHORT).show();
        }
    }

    public void onDelete() {
        android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        try {
            // Kalau ada teks terpilih (Select All / long-press / TalkBack select),
            // hapus seluruh seleksi dulu — sama seperti keyboard sistem & Gboard.
            CharSequence selected = null;
            try {
                selected = ic.getSelectedText(0);
            } catch (Throwable ignored) {}
            if (selected != null && selected.length() > 0) {
                // commitText kosong mengganti seleksi dengan kosong = hapus semua yang terpilih
                ic.commitText("", 1);
                syncQwertyWordBufFromCursor(ic);
            } else {
                // Ambil karakter yang akan dihapus sebelum backspace agar buffer kata tetap akurat
                CharSequence beforeOne = null;
                try { beforeOne = ic.getTextBeforeCursor(1, 0); } catch (Throwable ignored) {}
                boolean deleted = false;
                try {
                    deleted = ic.deleteSurroundingText(1, 0);
                } catch (Throwable ignored) {}
                if (!deleted) {
                    try {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
                    } catch (Throwable ignored) {}
                }
                // Sinkronkan qwertyWordBuf setelah hapus (fallback pangkas 1 huruf terakhir jika getTextBeforeCursor null)
                if (qwertyWordBuf.length() > 0) {
                    qwertyWordBuf.deleteCharAt(qwertyWordBuf.length() - 1);
                }
                syncQwertyWordBufFromCursor(ic);
                if (beforeOne != null && beforeOne.length() > 0 && !isBrailleModeActive) {
                    EditorInfo eiDel = null;
                    try { eiDel = getCurrentInputEditorInfo(); } catch (Throwable ignored) {}
                    if (!isPasswordOrSensitiveEditor(eiDel)) {
                        maybeSpeakQwertyChar("Hapus " + beforeOne);
                    }
                }
            }
            checkAutoCapitalization();
        } catch (Throwable ignored) {}
    }

    /**
     * Kirim pesan (dipakai gesture Braille yang bisa diatur di pengaturan).
     * Prioritas: IME_ACTION_SEND → KEYCODE_ENTER → performEditorAction fallback.
     */
    public void performSendAction() {
        try {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            EditorInfo ei = null;
            try { ei = getCurrentInputEditorInfo(); } catch (Throwable ignored) {}
            int action = EditorInfo.IME_ACTION_UNSPECIFIED;
            try {
                if (ei != null) action = ei.imeOptions & EditorInfo.IME_MASK_ACTION;
            } catch (Throwable ignored) {}
            // 1) Kalau field mendukung SEND, pakai itu
            try {
                if (action == EditorInfo.IME_ACTION_SEND) {
                    if (ic.performEditorAction(EditorInfo.IME_ACTION_SEND)) return;
                }
            } catch (Throwable ignored) {}
            // 2) Coba paksa SEND dulu (banyak chat app)
            try {
                if (ic.performEditorAction(EditorInfo.IME_ACTION_SEND)) return;
            } catch (Throwable ignored) {}
            // 3) Fallback KEYCODE_ENTER
            try {
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                return;
            } catch (Throwable ignored) {}
            // 4) Terakhir: action asli field kalau ada
            try {
                if (action != EditorInfo.IME_ACTION_UNSPECIFIED && action != EditorInfo.IME_ACTION_NONE) {
                    ic.performEditorAction(action);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    @Override
    public void onEnter() {
        // Enter universal: YouTube Search, Termux, WA search, chat multi-line, form GO/DONE, dll.
        // Prioritas: performEditorAction (SEARCH/GO/SEND/DONE/NEXT) → KEYCODE_ENTER → "\n"
        try {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) {
                try { sendKeyChar('\n'); } catch (Throwable ignored) {}
                return;
            }

            EditorInfo ei = null;
            try { ei = getCurrentInputEditorInfo(); } catch (Throwable ignored) {}

            String pkg = null;
            try {
                if (ei != null && ei.packageName != null) pkg = ei.packageName.toString().toLowerCase();
                else if (lastEditorPackage != null) pkg = lastEditorPackage.toLowerCase();
            } catch (Throwable ignored) {}

            boolean isTermuxOrTerminal = pkg != null && (
                    pkg.contains("termux")
                    || pkg.contains("terminal")
                    || pkg.contains("jackpal.androidterm")
                    || pkg.contains("com.termoneplus")
                    || pkg.contains("org.pocketworkstation.pckeyboard")
            );

            boolean isYoutube = pkg != null && (
                    pkg.contains("youtube")
                    || pkg.contains("com.google.android.youtube")
                    || pkg.contains("com.google.android.apps.youtube")
            );

            int action = EditorInfo.IME_ACTION_UNSPECIFIED;
            boolean noEnterAction = false;
            boolean multiLine = false;
            try {
                if (ei != null) {
                    action = ei.imeOptions & EditorInfo.IME_MASK_ACTION;
                    noEnterAction = (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
                    multiLine = (ei.inputType & EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
                }
            } catch (Throwable ignored) {}

            // 1) Kalau app minta action khusus (Search YouTube, GO, SEND, DONE, NEXT) → performEditorAction
            boolean hasRealAction = action == EditorInfo.IME_ACTION_SEARCH
                    || action == EditorInfo.IME_ACTION_GO
                    || action == EditorInfo.IME_ACTION_SEND
                    || action == EditorInfo.IME_ACTION_DONE
                    || action == EditorInfo.IME_ACTION_NEXT
                    || action == EditorInfo.IME_ACTION_PREVIOUS;

            if (hasRealAction && !noEnterAction) {
                try {
                    if (ic.performEditorAction(action)) {
                        return;
                    }
                } catch (Throwable ignored) {}
            }

            // 2) Search field deteksi manual (YouTube, WA search, dll) meski action NONE
            if (!noEnterAction && (isYoutube || isSearchEditor(ei))) {
                try {
                    // Coba SEARCH dulu, lalu GO, lalu DONE
                    if (ic.performEditorAction(EditorInfo.IME_ACTION_SEARCH)) return;
                    if (ic.performEditorAction(EditorInfo.IME_ACTION_GO)) return;
                    if (ic.performEditorAction(EditorInfo.IME_ACTION_DONE)) return;
                } catch (Throwable ignored) {}
            }

            // 3) Termux / terminal → KEYCODE_ENTER biar nge-execute command
            if (isTermuxOrTerminal) {
                try {
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                    return;
                } catch (Throwable ignored) {}
            }

            // 4) Multi-line chat / editor → newline
            if (multiLine || noEnterAction) {
                try {
                    ic.beginBatchEdit();
                    try {
                        try { ic.finishComposingText(); } catch (Throwable ignored) {}
                        ic.commitText("\n", 1);
                    } finally {
                        try { ic.endBatchEdit(); } catch (Throwable ignored) {}
                    }
                    return;
                } catch (Throwable ignored) {}
            }

            // 5) Fallback universal: KEYCODE_ENTER dulu, kalau gagal baru "\n"
            try {
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                return;
            } catch (Throwable ignored) {}

            try {
                ic.commitText("\n", 1);
            } catch (Throwable ignored) {
                try { sendKeyChar('\n'); } catch (Throwable ignored2) {}
            }
        } catch (Throwable t) {
            try {
                InputConnection ic2 = getCurrentInputConnection();
                if (ic2 != null) {
                    ic2.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    ic2.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                } else {
                    sendKeyChar('\n');
                }
            } catch (Throwable ignored) {}
        }
    }
}


