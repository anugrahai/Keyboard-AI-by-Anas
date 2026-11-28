package com.annas;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/* Custom Keyboard View with Sleek Modern Material UI & Rich Tactile Feedback */
public class CustomKeyboardView extends View {
    public static final int KEY_CHAR = 1;
    public static final int KEY_DEL = 3;
    public static final int KEY_ENTER = 5;
    public static final int KEY_SHIFT = 2;
    public static final int KEY_SPACE = 4;
    public static final int KEY_TO_ABC = 8;
    public static final int KEY_TO_EMOJI = 9;
    public static final int KEY_TO_SYM1 = 6;
    public static final int KEY_TO_SYM2 = 7;
    public static final int MODE_EMOJI = 3;
    public static final int MODE_NUMPAD = 4;
    public static final int MODE_QWERTY = 0;
    public static final int MODE_SYM1 = 1;
    public static final int MODE_SYM2 = 2;
    public static final int KEY_EMOJI_NEXT = 11;
    public static final int KEY_EMOJI_PREV = 12;

    private AccessibilityManager accessibilityManager;
    private GboardAccessibilityProvider accessibilityProvider;
    private ArrayList<KeyDef> allKeys;
    private AudioManager audioManager;
    private Paint bgPaint;
    private Paint keyPaint;
    private Paint keyShadowPaint;
    private Paint keySpecialPaint;
    private Paint keySpecialShadowPaint;
    private Paint actionKeyPaint;
    private Paint actionKeyShadowPaint;
    private Paint keyHighlightPaint;
    private Paint topBarPillPaint;
    private Paint topBarPillShadowPaint;
    private Paint textPaint;
    private Paint specTextPaint;
    private Paint topBarTextPaint;
    private Paint popupBgPaint;
    private Paint popupTextPaint;

    private int currentMode;
    /** Legacy single-id (custom file). Packs pakai array di bawah. */
    private int customSoundId;
    /** Suara ketik (bisa banyak, di-random). */
    private int[] typingSoundIds = new int[0];
    private int spaceSoundId = -1;
    private int deleteSoundId = -1;
    private final java.util.Random soundRnd = new java.util.Random();
    private Handler deleteHandler;
    private Runnable deleteLongPressRunnable;
    private int focusedVirtualKeyId;
    private int hoveredVirtualKeyId;
    private boolean isCaps;
    private boolean isCapsLocked;
    private long lastShiftTime;
    private boolean isDeleteLongPressed;
        private boolean isSpaceLongPressed;
        private Runnable spaceLongPressRunnable;
        private int delSwipePointerId = -1;
        private float delSwipeStartX = 0f;
        private boolean delSwipeTriggered = false;
        private int emojiPage = 0;
        private int currentFontIndex = 0;
        private float cachedDensity = -1f;
        private int hoverLongPressSerial = 0;
        private float lastHoverX = 0f;
        private float lastHoverY = 0f;
        private long lastHoverMoveTime = 0L;

        public void setFontIndex(int index) {
            this.currentFontIndex = index;
            invalidate();
        }

        private float density() {
            if (this.cachedDensity <= 0f) {
                this.cachedDensity = getResources().getDisplayMetrics().density;
            }
            return this.cachedDensity;
        }
        private boolean isSoundLoaded;
        private int keyHeightPx;
        private long lastActionTime;
        private long lastTapTime;
        private int lastTappedKeyIndex;
        private long lastTypingTime = 0L;
        private long lastTypedAnnounceAt = 0L;
        private int lastHoverExecutedKey = -1;
        private long lastHoverExecutedAt = 0L;
        private ArrayList<ArrayList<KeyDef>> layoutRows;
        private OnKeyboardActionListener listener;
        private static final int MAX_POINTERS = 16;
        private static final long SPACE_LONG_PRESS_MS = 3000L;
        private static final int MIN_DELETE_INITIAL_DELAY_MS = 400;

    private final int[] pointerIds = new int[MAX_POINTERS];
    private final int[] pointerKeys = new int[MAX_POINTERS];
    private final int[] pointerDownKeys = new int[MAX_POINTERS];
    private final float[] pointerDownX = new float[MAX_POINTERS];
    private final float[] pointerDownY = new float[MAX_POINTERS];
    private final boolean[] pointerMovedFar = new boolean[MAX_POINTERS];
    private final int[] pointerLongPressSerial = new int[MAX_POINTERS];

    // Konstanta kompatibilitas (mode percobaan sudah disatukan ke mode standar)
    public static final String MODE_GBOARD = "Percobaan (Gaya Gboard)";
    private int longPressSerial = 0;

    // Cache warna tema & RectF sementara biar onDraw zero-allocation
    private String cachedThemeName = null;
    private int cachedBgCol;
    private int cachedKeyCol;
    private int cachedKeyShadowCol;
    private int cachedSpecCol;
    private int cachedSpecShadowCol;
    private int cachedActionCol;
    private int cachedActionShadowCol;
    private int cachedTextCol;
    private int cachedSpecTextCol;
    private int cachedHlCol;
    private int cachedTopBarPillCol;
    private int cachedTopBarShadowCol;
    private final RectF tmpKeyRect = new RectF();
    private final RectF tmpShadowRect = new RectF();
    private final RectF tmpPopupRect = new RectF();
    private final RectF tmpPopupShadowRect = new RectF();

    private SharedPreferences prefs;
    private SoundPool soundPool;
    private Vibrator vibrator;
    private boolean hasVibratorCached;
    private final ExecutorService hapticExecutor = Executors.newSingleThreadExecutor();

    private int spaceGlidePointerId = -1;
    private float spaceGlideLastX = 0f;
    private boolean spaceGlideMoved = false;

    public interface OnKeyboardActionListener {
        void onClearAll();
        void onCommitText(String str);
        void onDelete();
        void onEnter();
        void onMoveCursor(int delta);
    }

    public static class KeyDef {
        private static final Map<String, String[]> SYMBOL_LABELS = new HashMap<>();
        static {
            SYMBOL_LABELS.put("🧩", new String[]{"Stickers", "Stiker"});
            SYMBOL_LABELS.put("⚙️", new String[]{"Pengaturan", "Pengaturan"});
            SYMBOL_LABELS.put("📋", new String[]{"Tempel", "Tempel"});
            SYMBOL_LABELS.put("🎤", new String[]{"Input Suara AI", "Input Suara AI"});
            SYMBOL_LABELS.put("🤖", new String[]{"Smart Reply", "Smart Reply"});
            SYMBOL_LABELS.put("✍️", new String[]{"Revisi Teks", "Revisi Teks"});
            SYMBOL_LABELS.put("📝", new String[]{"Word dan Editor Dokumen", "Word dan Editor Dokumen"});
            SYMBOL_LABELS.put("⚡", new String[]{"Quick Tools", "Alat Cepat Keyboard"});
            SYMBOL_LABELS.put("𝔽", new String[]{"Font", "Font"});
            SYMBOL_LABELS.put("⠃", new String[]{"Papan Ketik Braille", "Papan Ketik Braille"});
            SYMBOL_LABELS.put(".", new String[]{"Dot", "Titik"});
            SYMBOL_LABELS.put(",", new String[]{"Comma", "Koma"});
            SYMBOL_LABELS.put("?", new String[]{"Question mark", "Tanda tanya"});
            SYMBOL_LABELS.put("!", new String[]{"Exclamation mark", "Tanda seru"});
            SYMBOL_LABELS.put("@", new String[]{"At", "At"});
            SYMBOL_LABELS.put("#", new String[]{"Hash", "Pagar"});
            SYMBOL_LABELS.put("-", new String[]{"Hyphen", "Tanda hubung"});
            SYMBOL_LABELS.put("+", new String[]{"Plus", "Plus"});
            SYMBOL_LABELS.put("=", new String[]{"Equals", "Sama dengan"});
            SYMBOL_LABELS.put("/", new String[]{"Slash", "Garis miring"});
            SYMBOL_LABELS.put("\\", new String[]{"Backslash", "Garis miring terbalik"});
            SYMBOL_LABELS.put(":", new String[]{"Colon", "Titik dua"});
            SYMBOL_LABELS.put(";", new String[]{"Semicolon", "Titik koma"});
            SYMBOL_LABELS.put("\"", new String[]{"Quote", "Tanda petik"});
            SYMBOL_LABELS.put("'", new String[]{"Apostrophe", "Apostrophe"});
            SYMBOL_LABELS.put("(", new String[]{"Open bracket", "Buka kurung"});
            SYMBOL_LABELS.put(")", new String[]{"Close bracket", "Tutup kurung"});
            SYMBOL_LABELS.put("[", new String[]{"Open square bracket", "Buka kurung siku"});
            SYMBOL_LABELS.put("]", new String[]{"Close square bracket", "Tutup kurung siku"});
            SYMBOL_LABELS.put("{", new String[]{"Open brace", "Buka kurung kurawal"});
            SYMBOL_LABELS.put("}", new String[]{"Close brace", "Tutup kurung kurawal"});
            SYMBOL_LABELS.put("<", new String[]{"Less than", "Kurang dari"});
            SYMBOL_LABELS.put(">", new String[]{"Greater than", "Lebih dari"});
            SYMBOL_LABELS.put("*", new String[]{"Asterisk", "Bintang"});
            SYMBOL_LABELS.put("%", new String[]{"Percent", "Persen"});
            SYMBOL_LABELS.put("&", new String[]{"Ampersand", "Dan"});
            SYMBOL_LABELS.put("$", new String[]{"Dollar", "Dolar"});
            SYMBOL_LABELS.put("~", new String[]{"Tilde", "Tilde"});
            SYMBOL_LABELS.put("`", new String[]{"Grave accent", "Aksen berat"});
            SYMBOL_LABELS.put("|", new String[]{"Vertical bar", "Garis tegak"});
            SYMBOL_LABELS.put("_", new String[]{"Underscore", "Garis bawah"});
            SYMBOL_LABELS.put("^", new String[]{"Caret", "Karet"});
            // Simbol Khusus & Lanjutan (Mode 2 & Mode 5)
            SYMBOL_LABELS.put("✓", new String[]{"Check mark", "Tanda centang"});
            SYMBOL_LABELS.put("√", new String[]{"Square root", "Akar kuadrat"});
            SYMBOL_LABELS.put("π", new String[]{"Pi", "Simbol Pi"});
            SYMBOL_LABELS.put("¿", new String[]{"Inverted question mark", "Tanda tanya terbalik"});
            SYMBOL_LABELS.put("¡", new String[]{"Inverted exclamation mark", "Tanda seru terbalik"});
            SYMBOL_LABELS.put("§", new String[]{"Section sign", "Tanda pasal"});
            SYMBOL_LABELS.put("≈", new String[]{"Approximately equal", "Kira-kira sama dengan"});
            SYMBOL_LABELS.put("≠", new String[]{"Not equal", "Tidak sama dengan"});
            SYMBOL_LABELS.put("±", new String[]{"Plus minus", "Plus minus"});
            SYMBOL_LABELS.put("∞", new String[]{"Infinity", "Tak terhingga"});
            SYMBOL_LABELS.put("½", new String[]{"One half", "Setengah"});
            SYMBOL_LABELS.put("¼", new String[]{"One quarter", "Seperempat"});
            SYMBOL_LABELS.put("¾", new String[]{"Three quarters", "Tiga perempat"});
            SYMBOL_LABELS.put("²", new String[]{"Superscript two", "Pangkat dua"});
            SYMBOL_LABELS.put("³", new String[]{"Superscript three", "Pangkat tiga"});
            SYMBOL_LABELS.put("«", new String[]{"Left guillemet", "Tanda kutip sudut kiri"});
            SYMBOL_LABELS.put("»", new String[]{"Right guillemet", "Tanda kutip sudut kanan"});
            SYMBOL_LABELS.put("‹", new String[]{"Single left angle quotation", "Kutip sudut kiri tunggal"});
            SYMBOL_LABELS.put("›", new String[]{"Single right angle quotation", "Kutip sudut kanan tunggal"});
            SYMBOL_LABELS.put("µ", new String[]{"Micro sign", "Mikro"});
            SYMBOL_LABELS.put("†", new String[]{"Dagger", "Tanda salib"});
            SYMBOL_LABELS.put("‡", new String[]{"Double dagger", "Tanda salib ganda"});
            SYMBOL_LABELS.put("♠", new String[]{"Spade suit", "Simbol sekop"});
            SYMBOL_LABELS.put("♣", new String[]{"Club suit", "Simbol keriting"});
            SYMBOL_LABELS.put("♥", new String[]{"Heart suit", "Simbol hati"});
            SYMBOL_LABELS.put("♦", new String[]{"Diamond suit", "Simbol wajik"});
            SYMBOL_LABELS.put("°", new String[]{"Degree", "Derajat"});
            SYMBOL_LABELS.put("•", new String[]{"Bullet point", "Poin peluru"});
            SYMBOL_LABELS.put("÷", new String[]{"Division sign", "Tanda bagi"});
            SYMBOL_LABELS.put("×", new String[]{"Multiplication sign", "Tanda kali"});
            SYMBOL_LABELS.put("¶", new String[]{"Pilcrow paragraph sign", "Tanda paragraf"});
            SYMBOL_LABELS.put("…", new String[]{"Ellipsis", "Titik tiga elipsis"});
            SYMBOL_LABELS.put("£", new String[]{"Pound sterling", "Pound sterling"});
            SYMBOL_LABELS.put("¢", new String[]{"Cent sign", "Sen"});
            SYMBOL_LABELS.put("€", new String[]{"Euro sign", "Euro"});
            SYMBOL_LABELS.put("¥", new String[]{"Yen sign", "Yen"});
            SYMBOL_LABELS.put("©", new String[]{"Copyright", "Hak cipta"});
            SYMBOL_LABELS.put("®", new String[]{"Registered trademark", "Merek terdaftar"});
            SYMBOL_LABELS.put("™", new String[]{"Trademark", "Merek dagang"});
            SYMBOL_LABELS.put("~^|", new String[]{"Special symbols", "Simbol khusus"});
            SYMBOL_LABELS.put("ع+", new String[]{"Arabic diacritics and numerals", "Harakat dan angka Arab"});
            SYMBOL_LABELS.put("◀️", new String[]{"Previous page", "Halaman sebelumnya"});
            SYMBOL_LABELS.put("▶️", new String[]{"Next page", "Halaman selanjutnya"});
            SYMBOL_LABELS.put("◀", new String[]{"Previous page", "Halaman sebelumnya"});
            SYMBOL_LABELS.put("▶", new String[]{"Next page", "Halaman selanjutnya"});
            // Harakat, tanda baca, dan angka Arab
            SYMBOL_LABELS.put("َ", new String[]{"Fathah", "Fathah"});
            SYMBOL_LABELS.put("ِ", new String[]{"Kasrah", "Kasrah"});
            SYMBOL_LABELS.put("ُ", new String[]{"Dhammah", "Dhammah"});
            SYMBOL_LABELS.put("ً", new String[]{"Fathatain", "Fathatain"});
            SYMBOL_LABELS.put("ٍ", new String[]{"Kasratain", "Kasratain"});
            SYMBOL_LABELS.put("ٌ", new String[]{"Dhammatain", "Dhammatain"});
            SYMBOL_LABELS.put("ْ", new String[]{"Sukun", "Sukun"});
            SYMBOL_LABELS.put("ّ", new String[]{"Tasydid", "Tasydid"});
            SYMBOL_LABELS.put("ٰ", new String[]{"Alif khanjariyah", "Alif tegak"});
            SYMBOL_LABELS.put("ٓ", new String[]{"Maddah", "Maddah"});
            SYMBOL_LABELS.put("،", new String[]{"Arabic comma", "Koma Arab"});
            SYMBOL_LABELS.put("؛", new String[]{"Arabic semicolon", "Titik koma Arab"});
            SYMBOL_LABELS.put("؟", new String[]{"Arabic question mark", "Tanda tanya Arab"});
            SYMBOL_LABELS.put("٪", new String[]{"Arabic percent", "Persen Arab"});
            SYMBOL_LABELS.put("١", new String[]{"Arabic digit one", "Angka Arab satu"});
            SYMBOL_LABELS.put("٢", new String[]{"Arabic digit two", "Angka Arab dua"});
            SYMBOL_LABELS.put("٣", new String[]{"Arabic digit three", "Angka Arab tiga"});
            SYMBOL_LABELS.put("٤", new String[]{"Arabic digit four", "Angka Arab empat"});
            SYMBOL_LABELS.put("٥", new String[]{"Arabic digit five", "Angka Arab lima"});
            SYMBOL_LABELS.put("٦", new String[]{"Arabic digit six", "Angka Arab enam"});
            SYMBOL_LABELS.put("٧", new String[]{"Arabic digit seven", "Angka Arab tujuh"});
            SYMBOL_LABELS.put("٨", new String[]{"Arabic digit eight", "Angka Arab delapan"});
            SYMBOL_LABELS.put("٩", new String[]{"Arabic digit nine", "Angka Arab sembilan"});
            SYMBOL_LABELS.put("٠", new String[]{"Arabic digit zero", "Angka Arab nol"});
            SYMBOL_LABELS.put("لا", new String[]{"Lam Alif", "Lam Alif"});
        }

        RectF bounds = new RectF();
        String commit;
        String label;
        int type;
        float weight;
        boolean isTopBar = false;

        KeyDef(String str, String str2, int i, float f) {
            this.label = str;
            this.commit = str2;
            this.type = i;
            this.weight = f;
        }

        String getSpokenText(boolean isUpper, boolean isEnglish) {
            switch (this.type) {
                case KEY_DEL: return isEnglish ? "Delete" : "Hapus";
                case KEY_SPACE: return isEnglish ? "Space" : "Spasi";
                case KEY_ENTER: return "Enter";
                case KEY_SHIFT:
                    return isUpper
                        ? (isEnglish ? "Lowercase" : "Huruf kecil")
                        : (isEnglish ? "Uppercase shift" : "Huruf besar shift");
                case KEY_TO_SYM1: return isEnglish ? "Symbols and numbers" : "Simbol dan angka";
                case KEY_TO_SYM2: return isEnglish ? "Other symbols" : "Simbol lainnya";
                case KEY_TO_ABC: return isEnglish ? "Back to letters" : "Kembali ke huruf";
                case KEY_TO_EMOJI: return isEnglish ? "Emoji list" : "Daftar emoji";
                case KEY_EMOJI_NEXT: return isEnglish ? "Next page" : "Halaman selanjutnya";
                case KEY_EMOJI_PREV: return isEnglish ? "Previous page" : "Halaman sebelumnya";
                case 10:
                    if ("ع+".equals(this.label)) {
                        return isEnglish ? "Arabic diacritics and numerals" : "Harakat dan angka Arab";
                    }
                    return isEnglish ? "Special symbols" : "Simbol khusus";
                default: break;
            }
            String[] mapped = SYMBOL_LABELS.get(this.label);
            if (mapped != null) {
                return isEnglish ? mapped[0] : mapped[1];
            }
            if (!isEnglish && this.label != null && !this.label.isEmpty()) {
                String arSpoken = BrailleHelper.getArabicSymbolSpokenName(this.label);
                if (!arSpoken.equals(this.label)) {
                    return arSpoken;
                }
            }
            return isUpper ? this.label.toUpperCase() : this.label.toLowerCase();
        }
    }

    public CustomKeyboardView(Context context, OnKeyboardActionListener onKeyboardActionListener, SharedPreferences sharedPreferences, Vibrator vibrator) {
        super(context);
        this.currentMode = 0;
        this.isCaps = false;
        this.layoutRows = new ArrayList<>();
        this.allKeys = new ArrayList<>();
        this.hoveredVirtualKeyId = -1;
        this.focusedVirtualKeyId = -1;
        clearAllPointers();
        this.lastTappedKeyIndex = -1;
        this.lastTapTime = 0L;
        this.lastActionTime = 0L;
        this.deleteHandler = new Handler(Looper.getMainLooper());
        this.isDeleteLongPressed = false;

        this.spaceLongPressRunnable = new Runnable() {
            @Override
            public void run() {
                isSpaceLongPressed = true;
                longPressSerial++;
                vibrate(true);
                if (listener != null) {
                    listener.onCommitText("LONG_SPACE");
                }
            }
        };

        this.deleteLongPressRunnable = new Runnable() {
            private int deleteRepeatCount = 0;
            @Override
            public void run() {
                if (isDeleteLongPressed && listener != null) {
                    longPressSerial++;
                    vibrate(true);
                    listener.onCommitText("LONG_DELETE");
                    deleteRepeatCount++;
                    int baseDelay = prefs != null ? prefs.getInt("delay_hapus_ms", 600) : 600;
                    int nextDelay = deleteRepeatCount <= 1
                            ? Math.max(baseDelay, 240)
                            : Math.max(baseDelay, 50);
                    deleteHandler.postDelayed(this, nextDelay);
                } else {
                    deleteRepeatCount = 0;
                }
            }
        };

        this.bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.keyShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.keySpecialPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.keySpecialShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.actionKeyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.actionKeyShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.keyHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.topBarPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.topBarPillShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.popupBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        this.textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.textPaint.setTextAlign(Paint.Align.CENTER);
        this.textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

        this.specTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.specTextPaint.setTextAlign(Paint.Align.CENTER);
        this.specTextPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));

        this.topBarTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.topBarTextPaint.setTextAlign(Paint.Align.CENTER);
        this.topBarTextPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));

        this.popupTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.popupTextPaint.setTextAlign(Paint.Align.CENTER);
        this.popupTextPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

        this.customSoundId = -1;
        this.isSoundLoaded = false;
        this.listener = onKeyboardActionListener;
        this.prefs = sharedPreferences;
        this.vibrator = vibrator;
        try {
            this.hasVibratorCached = vibrator != null && vibrator.hasVibrator();
        } catch (Throwable ignored) {
            this.hasVibratorCached = false;
        }
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.accessibilityManager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);

        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.parseColor("#1A1B1F"));

        updateKeyHeight();
        this.accessibilityProvider = new GboardAccessibilityProvider();
        initSoundPool();
        buildKeyboardStructure();
        refreshTypingMode();
    }

    public void updateKeyHeight() {
        this.keyHeightPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.prefs.getInt("key_height", 58), getResources().getDisplayMetrics());
    }

    public boolean isDoubleTapMode() {
        if (this.prefs == null) return false;
        String mode = this.prefs.getString("akses_ngetik_mode", "Angkat Jari (Standar)");
        if (MODE_GBOARD.equals(mode)) {
            this.prefs.edit().putString("akses_ngetik_mode", "Angkat Jari (Standar)")
                .putBoolean("double_tap_mode", false).apply();
            return false;
        }
        return mode.contains("Ketuk Ganda") || this.prefs.getBoolean("double_tap_mode", false);
    }

    private boolean isScreenReaderExploring() {
        try {
            return this.accessibilityManager != null
                    && this.accessibilityManager.isEnabled()
                    && this.accessibilityManager.isTouchExplorationEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String getBlindDoubleTapPrefKey(KeyDef keyDef) {
        if (keyDef == null) return null;
        if (keyDef.isTopBar) {
            if ("⚙️".equals(keyDef.label)) return "blind_dt_key_settings";
            if ("🎤".equals(keyDef.label)) return "blind_dt_key_mic";
            if ("📋".equals(keyDef.label)) return "blind_dt_key_paste";
            if ("⚡".equals(keyDef.label)) return "blind_dt_key_quick_tools";
            if ("🤖".equals(keyDef.label)) return "blind_dt_key_smart_reply";
            if ("✍️".equals(keyDef.label)) return "blind_dt_key_revise";
            if ("📝".equals(keyDef.label)) return "blind_dt_key_docs";
            if ("𝔽".equals(keyDef.label)) return "blind_dt_key_font";
        }
        if ("🧩".equals(keyDef.commit)) return "blind_dt_key_sticker";
        switch (keyDef.type) {
            case KEY_TO_EMOJI:
                return "blind_dt_key_emoji";
            case KEY_TO_SYM1:
            case KEY_TO_SYM2:
                return "blind_dt_key_sym_switch";
            case KEY_TO_ABC:
            case 10:
                return "blind_dt_key_abc_special";
            case KEY_EMOJI_NEXT:
            case KEY_EMOJI_PREV:
                return "blind_dt_key_page_nav";
            default:
                return null;
        }
    }

    /**
     * Untuk tunanetra (Touch Exploration aktif), tombol kontrol/baris atas selain huruf
     * mengikuti toggle induk "blind_action_double_tap_enabled" dan centang per tombol.
     * Untuk pengguna awas (tanpa pembaca layar), semua tombol tetap sekali ketuk.
     */
    private boolean requiresDoubleTap(KeyDef keyDef) {
        if (isDoubleTapMode()) return true;
        if (keyDef == null || !isScreenReaderExploring()) return false;
        if (this.prefs != null && !this.prefs.getBoolean("blind_action_double_tap_enabled", true)) {
            return false;
        }
        String prefKey = getBlindDoubleTapPrefKey(keyDef);
        if (prefKey == null) return false;
        return this.prefs == null || this.prefs.getBoolean(prefKey, true);
    }

    private void initSoundPool() {
        try {
            this.soundPool = new SoundPool.Builder()
                    .setMaxStreams(8)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .build();
            this.soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                @Override
                public void onLoadComplete(SoundPool soundPool, int i, int i2) {
                    if (i2 == 0) {
                        CustomKeyboardView.this.isSoundLoaded = true;
                    }
                }
            });
            reloadCustomSound();
        } catch (Throwable th) {
        }
    }

    public void reloadCustomSound() {
        this.isSoundLoaded = false;
        this.customSoundId = -1;
        this.typingSoundIds = new int[0];
        this.spaceSoundId = -1;
        this.deleteSoundId = -1;
        try {
            if (this.soundPool == null) return;
            String effectKey = this.prefs != null ? this.prefs.getString("sound_effect_key", "key2") : "key2";
            if ("custom".equals(effectKey)) {
                File file = new File(getContext().getFilesDir(), "custom_ketik.mp3");
                if (file.exists() && file.canRead()) {
                    this.customSoundId = this.soundPool.load(file.getAbsolutePath(), 1);
                    this.isSoundLoaded = true;
                }
                return;
            }
            // Pack dari assets/typingsound/<key>/
            java.util.ArrayList<Integer> typingList = new java.util.ArrayList<>();
            String[] files;
            try {
                files = getContext().getAssets().list("typingsound/" + effectKey);
            } catch (Exception e) {
                files = null;
            }
            if (files == null || files.length == 0) {
                // fallback pack
                effectKey = "key2";
                try { files = getContext().getAssets().list("typingsound/" + effectKey); } catch (Exception e2) { files = new String[0]; }
            }
            if (files != null) {
                for (String fn : files) {
                    if (fn == null) continue;
                    String low = fn.toLowerCase(java.util.Locale.US);
                    if (!(low.endsWith(".wav") || low.endsWith(".mp3") || low.endsWith(".ogg"))) continue;
                    String path = "typingsound/" + effectKey + "/" + fn;
                    int id = -1;
                    try {
                        java.io.InputStream is = getContext().getAssets().open(path);
                        // SoundPool needs FD — copy to cache then load
                        File cache = new File(getContext().getCacheDir(), "sfx_" + effectKey + "_" + fn.replaceAll("[^a-zA-Z0-9._-]", "_"));
                        if (!cache.exists() || cache.length() == 0) {
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(cache);
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                            fos.close();
                        }
                        is.close();
                        id = this.soundPool.load(cache.getAbsolutePath(), 1);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (id <= 0) continue;
                    if (low.contains("delete") || low.contains("backspace") || low.contains("hapus") || low.equals("bill.wav")) {
                        this.deleteSoundId = id;
                    } else if (low.contains("space") || low.contains("spasi")) {
                        this.spaceSoundId = id;
                    } else {
                        // typing / letter / number / type / s1 / s.wav dll
                        typingList.add(id);
                    }
                }
            }
            if (!typingList.isEmpty()) {
                this.typingSoundIds = new int[typingList.size()];
                for (int i = 0; i < typingList.size(); i++) this.typingSoundIds[i] = typingList.get(i);
                this.isSoundLoaded = true;
            } else if (this.spaceSoundId != -1 || this.deleteSoundId != -1) {
                this.isSoundLoaded = true;
            }
            // Kalau delete/space kosong, pakai salah satu typing
            if (this.deleteSoundId == -1 && this.typingSoundIds.length > 0) this.deleteSoundId = this.typingSoundIds[0];
            if (this.spaceSoundId == -1 && this.typingSoundIds.length > 0) this.spaceSoundId = this.typingSoundIds[this.typingSoundIds.length - 1];
        } catch (Throwable th) {
        }
    }


    public boolean isCapsLocked() {
        return this.isCapsLocked;
    }

    public void setAutoCapitalize(boolean caps) {
        if (!this.isCapsLocked && this.isCaps != caps) {
            this.isCaps = caps;
            invalidate();
        }
    }

    public void resetShiftState() {
        if (this.isCaps || this.isCapsLocked) {
            this.isCaps = false;
            this.isCapsLocked = false;
            invalidate();
        }
    }

    public void reloadLayoutConfig() {
        refreshTypingMode();
        updateKeyHeight();
        buildKeyboardStructure();
        requestLayout();
        invalidate();
    }

    public void resetToQwerty() {
        if (this.currentMode != 0 || this.isCaps) {
            this.currentMode = 0;
            this.isCaps = false;
            reloadLayoutConfig();
        }
    }

    public void switchToNumpad() {
        this.currentMode = 4;
        reloadLayoutConfig();
    }

    public void switchToEmoji() {
        // Panel emoji sekarang memakai tampilan pemilih yang sama dengan stiker (dibuka oleh KeyboardService).
        if (this.listener != null) {
            this.listener.onCommitText("😊");
        }
    }

    private void buildKeyboardStructure() {
        this.layoutRows.clear();
        this.allKeys.clear();

        // Top Executive Bar di semua mode kecuali emoji
        if (this.currentMode != 3) {
            ArrayList<KeyDef> topRow = new ArrayList<>();
            topRow.add(new KeyDef("⚙️", "⚙️", 1, 1.0f));
            topRow.add(new KeyDef("⚡", "⚡", 1, 1.0f));
            topRow.add(new KeyDef("📋", "📋", 1, 1.0f));
            topRow.add(new KeyDef("🎤", "🎤", 1, 1.0f));
            topRow.add(new KeyDef("🤖", "🤖", 1, 1.0f));
            topRow.add(new KeyDef("✍️", "✍️", 1, 1.0f));
            topRow.add(new KeyDef("📝", "📝", 1, 1.0f));
            if (this.currentMode == 0) {
                topRow.add(new KeyDef("𝔽", "𝔽", 1, 1.0f));
            } else {
                topRow.add(new KeyDef("😊", "😊", 1, 1.0f));
            }
            for (KeyDef tk : topRow) {
                tk.isTopBar = true;
            }
            this.layoutRows.add(topRow);
        }

        boolean zNum = this.prefs.getBoolean("number_row_enabled", false);
        boolean zEng = this.prefs.getBoolean("interface_english", false);

        boolean isArabicScript = "arabic".equals(this.prefs.getString("keyboard_script", "latin"));

        if (this.currentMode == 0) {
            if (isArabicScript) {
                // ===== KEYBOARD ARAB STANDAR AWAS (Arabic 101 + Harakat + Shift Tajwid/Hamzah/Waqaf) =====
                if (zNum) {
                    ArrayList<KeyDef> numRow = new ArrayList<>();
                    for (String str : new String[]{"١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩", "٠"}) {
                        numRow.add(new KeyDef(str, str, 1, 1.0f));
                    }
                    this.layoutRows.add(numRow);
                }

                if (this.isCaps) {
                    // MODE SHIFT ARAB: Hamzah, Alif Wasal, Lam-Alif Hamzah, Tajwid Mushaf & Semua Tanda Waqaf
                    ArrayList<KeyDef> sh1 = new ArrayList<>();
                    for (String s : new String[]{"آ", "أ", "إ", "ٱ", "لآ", "لأ", "لإ", "ۢ", "ۡ", "۟", "ـ"}) {
                        sh1.add(new KeyDef(s, s, 1, 1.0f));
                    }
                    this.layoutRows.add(sh1);

                    ArrayList<KeyDef> sh2 = new ArrayList<>();
                    for (String s : new String[]{"ۖ", "ۗ", "ۘ", "ۙ", "ۚ", "ۛ", "ۜ", "۞", "۩", "۝", "ۤ"}) {
                        sh2.add(new KeyDef(s, s, 1, 1.0f));
                    }
                    this.layoutRows.add(sh2);

                    ArrayList<KeyDef> sh3 = new ArrayList<>();
                    for (String s : new String[]{"َ", "ً", "ِ", "ٍ", "ُ", "ٌ", "ْ", "ّ", "ٰ", "ٓ"}) {
                        sh3.add(new KeyDef(s, s, 1, 1.0f));
                    }
                    this.layoutRows.add(sh3);

                    ArrayList<KeyDef> sh4 = new ArrayList<>();
                    sh4.add(new KeyDef("⇧", "", 2, 1.2f));
                    for (String s : new String[]{"﴾", "﴿", "«", "»", "ﷲ", "ﷺ", "ﷻ", "﷽", "﷼", "؟"}) {
                        sh4.add(new KeyDef(s, s, 1, 1.0f));
                    }
                    sh4.add(new KeyDef("⌫", "", 3, 1.2f));
                    this.layoutRows.add(sh4);
                } else {
                    // BARIS 1 STANDAR ARABIC 101: ض ص ث ق ف غ ع ه خ ح ج د (lengkap dengan د!)
                    ArrayList<KeyDef> ar1 = new ArrayList<>();
                    for (String s : new String[]{"ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج", "د"}) {
                        ar1.add(new KeyDef(s, s, 1, 1.0f));
                    }
                    this.layoutRows.add(ar1);

                    // BARIS 2 STANDAR ARABIC 101: ش س ي ب ل ا ت ن م ك ط
                    ArrayList<KeyDef> ar2 = new ArrayList<>();
                    for (String s : new String[]{"ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"}) {
                        ar2.add(new KeyDef(s, s, 1, 1.0f));
                    }
                    this.layoutRows.add(ar2);

                    // BARIS 3: SYAKAL / HARAKAT BERTAJWID LENGKAP di keyboard utama
                    ArrayList<KeyDef> arHarakat = new ArrayList<>();
                    for (String s : new String[]{"َ", "ِ", "ُ", "ً", "ٍ", "ٌ", "ْ", "ّ", "ٰ", "ٓ"}) {
                        arHarakat.add(new KeyDef(s, s, 1, 1.0f));
                    }
                    this.layoutRows.add(arHarakat);

                    // BARIS 4 STANDAR ARABIC 101: [⇧] ئ ء ؤ ر لا ى ة و ز ظ ذ [⌫] (lengkap dengan ذ!)
                    ArrayList<KeyDef> ar3 = new ArrayList<>();
                    ar3.add(new KeyDef("⇧", "", 2, 1.2f));
                    for (String s : new String[]{"ئ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ", "ذ"}) {
                        ar3.add(new KeyDef(s, s, 1, 1.0f));
                    }
                    ar3.add(new KeyDef("⌫", "", 3, 1.2f));
                    this.layoutRows.add(ar3);
                }

                // BARIS 5 kontrol + tanda baca Arab resmi
                ArrayList<KeyDef> ar4 = new ArrayList<>();
                ar4.add(new KeyDef("?123", "", 6, 1.3f));
                ar4.add(new KeyDef("ع+", "", 10, 1.2f)); // 34 halaman Tajwid, Mushaf & Unicode Arab
                ar4.add(new KeyDef("،", "،", 1, 1.0f));
                ar4.add(new KeyDef("؛", "؛", 1, 1.0f));
                ar4.add(new KeyDef(zEng ? "Space" : "Spasi", " ", 4, 3.0f));
                ar4.add(new KeyDef(".", ".", 1, 1.0f));
                ar4.add(new KeyDef("؟", "؟", 1, 1.0f));
                ar4.add(new KeyDef("⏎", "", 5, 1.3f));
                this.layoutRows.add(ar4);

            } else {
                // ===== KEYBOARD LATIN QWERTY STANDAR =====
                if (zNum) {
                    ArrayList<KeyDef> numRow = new ArrayList<>();
                    for (String str : new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"}) {
                        numRow.add(new KeyDef(str, str, 1, 1.0f));
                    }
                    this.layoutRows.add(numRow);
                }

                ArrayList<KeyDef> row1 = new ArrayList<>();
                for (String str2 : new String[]{"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"}) {
                    row1.add(new KeyDef(str2, str2, 1, 1.0f));
                }
                this.layoutRows.add(row1);

                ArrayList<KeyDef> row2 = new ArrayList<>();
                for (String str3 : new String[]{"a", "s", "d", "f", "g", "h", "j", "k", "l"}) {
                    row2.add(new KeyDef(str3, str3, 1, 1.0f));
                }
                this.layoutRows.add(row2);

                ArrayList<KeyDef> row3 = new ArrayList<>();
                row3.add(new KeyDef("⇧", "", 2, 1.4f));
                for (String str4 : new String[]{"z", "x", "c", "v", "b", "n", "m"}) {
                    row3.add(new KeyDef(str4, str4, 1, 1.0f));
                }
                row3.add(new KeyDef("⌫", "", 3, 1.4f));
                this.layoutRows.add(row3);

                ArrayList<KeyDef> row4 = new ArrayList<>();
                row4.add(new KeyDef("?123", "", 6, 1.4f));
                if (this.prefs.getBoolean("qwerty_emoji_button_enabled", true)) {
                    row4.add(new KeyDef("😊", "", 9, 1.1f));
                }
                row4.add(new KeyDef(",", ",", 1, 1.1f));
                row4.add(new KeyDef(zEng ? "Space" : "Spasi", " ", 4, 4.0f));
                if (this.prefs.getBoolean("sticker_button_enabled", true)) {
                    row4.add(new KeyDef("🧩", "🧩", 1, 1.1f));
                }
                row4.add(new KeyDef(".", ".", 1, 1.1f));
                row4.add(new KeyDef("⏎", "", 5, 1.4f));
                this.layoutRows.add(row4);
            }

        } else if (this.currentMode == 4) {
            ArrayList<KeyDef> n1 = new ArrayList<>();
            n1.add(new KeyDef("1", "1", 1, 1.0f));
            n1.add(new KeyDef("2", "2", 1, 1.0f));
            n1.add(new KeyDef("3", "3", 1, 1.0f));
            n1.add(new KeyDef("⌫", "", 3, 1.0f));
            this.layoutRows.add(n1);

            ArrayList<KeyDef> n2 = new ArrayList<>();
            n2.add(new KeyDef("4", "4", 1, 1.0f));
            n2.add(new KeyDef("5", "5", 1, 1.0f));
            n2.add(new KeyDef("6", "6", 1, 1.0f));
            n2.add(new KeyDef("-", "-", 1, 1.0f));
            this.layoutRows.add(n2);

            ArrayList<KeyDef> n3 = new ArrayList<>();
            n3.add(new KeyDef("7", "7", 1, 1.0f));
            n3.add(new KeyDef("8", "8", 1, 1.0f));
            n3.add(new KeyDef("9", "9", 1, 1.0f));
            n3.add(new KeyDef("+", "+", 1, 1.0f));
            this.layoutRows.add(n3);

            ArrayList<KeyDef> n4 = new ArrayList<>();
            n4.add(new KeyDef("ABC", "", 8, 1.0f));
            n4.add(new KeyDef("0", "0", 1, 1.0f));
            n4.add(new KeyDef(".", ".", 1, 1.0f));
            n4.add(new KeyDef("⏎", "", 5, 1.0f));
            this.layoutRows.add(n4);

        } else if (this.currentMode == 1) {
            if (isArabicScript) {
                // Simbol 1 Khusus Arab: angka Arab Timur, tanda baca Arab, kurung ayat, & operasi hitung
                ArrayList<KeyDef> s1 = new ArrayList<>();
                for (String str5 : new String[]{"١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩", "٠"}) {
                    s1.add(new KeyDef(str5, str5, 1, 1.0f));
                }
                this.layoutRows.add(s1);

                ArrayList<KeyDef> s2 = new ArrayList<>();
                for (String str6 : new String[]{"،", "؛", "؟", "٪", "٫", "٬", "«", "»", "﴾", "﴿"}) {
                    s2.add(new KeyDef(str6, str6, 1, 1.0f));
                }
                this.layoutRows.add(s2);

                ArrayList<KeyDef> s3 = new ArrayList<>();
                s3.add(new KeyDef("=\\<", "", 7, 1.4f));
                for (String str7 : new String[]{"+", "-", "×", "÷", "=", "(", ")", ":", "!"}) {
                    s3.add(new KeyDef(str7, str7, 1, 1.0f));
                }
                s3.add(new KeyDef("⌫", "", 3, 1.4f));
                this.layoutRows.add(s3);

                ArrayList<KeyDef> s4 = new ArrayList<>();
                s4.add(new KeyDef("عرب", "", 8, 1.4f));
                s4.add(new KeyDef("،", "،", 1, 1.1f));
                s4.add(new KeyDef(zEng ? "Space" : "Spasi", " ", 4, 5.1f));
                s4.add(new KeyDef(".", ".", 1, 1.1f));
                s4.add(new KeyDef("⏎", "", 5, 1.4f));
                this.layoutRows.add(s4);
            } else {
                ArrayList<KeyDef> s1 = new ArrayList<>();
                for (String str5 : new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"}) {
                    s1.add(new KeyDef(str5, str5, 1, 1.0f));
                }
                this.layoutRows.add(s1);

                ArrayList<KeyDef> s2 = new ArrayList<>();
                for (String str6 : new String[]{"@", "#", "$", "%", "&", "-", "+", "=", "*", "/"}) {
                    s2.add(new KeyDef(str6, str6, 1, 1.0f));
                }
                this.layoutRows.add(s2);

                ArrayList<KeyDef> s3 = new ArrayList<>();
                s3.add(new KeyDef("=\\<", "", 7, 1.4f));
                for (String str7 : new String[]{"\"", "'", ":", ";", "!", "?"}) {
                    s3.add(new KeyDef(str7, str7, 1, 1.0f));
                }
                s3.add(new KeyDef("⌫", "", 3, 1.4f));
                this.layoutRows.add(s3);

                ArrayList<KeyDef> s4 = new ArrayList<>();
                s4.add(new KeyDef("ABC", "", 8, 1.4f));
                s4.add(new KeyDef(",", ",", 1, 1.1f));
                s4.add(new KeyDef(zEng ? "Space" : "Spasi", " ", 4, 5.1f));
                s4.add(new KeyDef(".", ".", 1, 1.1f));
                s4.add(new KeyDef("⏎", "", 5, 1.4f));
                this.layoutRows.add(s4);
            }

        } else if (this.currentMode == 2) {
            if (isArabicScript) {
                // Simbol 2 Khusus Arab: Waqaf Tajwid Al-Qur'an, Mushaf, Iqlab, Kaligrafi Islam
                ArrayList<KeyDef> m1 = new ArrayList<>();
                for (String str8 : new String[]{"ۖ", "ۗ", "ۘ", "ۙ", "ۚ", "ۛ", "ۜ", "۞", "۩", "۝"}) {
                    m1.add(new KeyDef(str8, str8, 1, 1.0f));
                }
                this.layoutRows.add(m1);

                ArrayList<KeyDef> m2 = new ArrayList<>();
                for (String str9 : new String[]{"ٱ", "ۢ", "ۭ", "ۡ", "۟", "۠", "ۤ", "ٰ", "ٓ", "ـ"}) {
                    m2.add(new KeyDef(str9, str9, 1, 1.0f));
                }
                this.layoutRows.add(m2);

                ArrayList<KeyDef> m3 = new ArrayList<>();
                m3.add(new KeyDef("ع+", "", 10, 1.4f));
                for (String str10 : new String[]{"ﷺ", "ﷻ", "﷽", "ﷲ", "﷼", "أ", "إ", "آ"}) {
                    m3.add(new KeyDef(str10, str10, 1, 1.0f));
                }
                m3.add(new KeyDef("⌫", "", 3, 1.4f));
                this.layoutRows.add(m3);

                ArrayList<KeyDef> m4 = new ArrayList<>();
                m4.add(new KeyDef("عرب", "", 8, 1.4f));
                m4.add(new KeyDef("?123", "", 6, 1.2f));
                m4.add(new KeyDef(zEng ? "Space" : "Spasi", " ", 4, 4.9f));
                m4.add(new KeyDef("؟", "؟", 1, 1.1f));
                m4.add(new KeyDef("⏎", "", 5, 1.4f));
                this.layoutRows.add(m4);
            } else {
                ArrayList<KeyDef> m1 = new ArrayList<>();
                for (String str8 : new String[]{"~", "`", "|", "\\", "_", "^", "<", ">", "{", "}"}) {
                    m1.add(new KeyDef(str8, str8, 1, 1.0f));
                }
                this.layoutRows.add(m1);

                ArrayList<KeyDef> m2 = new ArrayList<>();
                for (String str9 : new String[]{"[", "]", "(", ")", "°", "•", "÷", "×", "¶", "…"}) {
                    m2.add(new KeyDef(str9, str9, 1, 1.0f));
                }
                this.layoutRows.add(m2);

                ArrayList<KeyDef> m3 = new ArrayList<>();
                m3.add(new KeyDef("~^|", "", 10, 1.4f));
                for (String str10 : new String[]{"£", "¢", "€", "¥", "©", "®", "™"}) {
                    m3.add(new KeyDef(str10, str10, 1, 1.0f));
                }
                m3.add(new KeyDef("⌫", "", 3, 1.4f));
                this.layoutRows.add(m3);

                ArrayList<KeyDef> m4 = new ArrayList<>();
                m4.add(new KeyDef("ABC", "", 8, 1.4f));
                m4.add(new KeyDef(",", ",", 1, 1.1f));
                m4.add(new KeyDef(zEng ? "Space" : "Spasi", " ", 4, 5.1f));
                m4.add(new KeyDef(".", ".", 1, 1.1f));
                m4.add(new KeyDef("⏎", "", 5, 1.4f));
                this.layoutRows.add(m4);
            }

        } else if (this.currentMode == 5) {
            if (isArabicScript) {
                // ===== 8 HALAMAN ARAB LENGKAP (syakal, mad, Qur'an, tanda baca) =====
                String[][][] allArPages = KeyboardData.ALL_ARABIC_PAGES;
                if (this.emojiPage < 0) this.emojiPage = 0;
                if (this.emojiPage >= allArPages.length) this.emojiPage = allArPages.length - 1;
                String[][] currentArPage = allArPages[this.emojiPage];
                for (String[] row : currentArPage) {
                    ArrayList<KeyDef> arRow = new ArrayList<>();
                    for (String ch : row) {
                        arRow.add(new KeyDef(ch, ch, 1, 1.0f));
                    }
                    this.layoutRows.add(arRow);
                }
                ArrayList<KeyDef> arCtrl = new ArrayList<>();
                arCtrl.add(new KeyDef("عرب", "", 8, 1.5f));
                if (this.emojiPage > 0) arCtrl.add(new KeyDef("◀️", "", KEY_EMOJI_PREV, 1.2f));
                else arCtrl.add(new KeyDef("", "", 1, 1.2f));
                String arHal = KeyboardData.ARABIC_PAGE_NAMES[this.emojiPage];
                if (arHal.length() > 14) arHal = "Hal " + (this.emojiPage + 1) + "/" + allArPages.length;
                arCtrl.add(new KeyDef(arHal, " ", 4, 3.5f));
                if (this.emojiPage < allArPages.length - 1) arCtrl.add(new KeyDef("▶️", "", KEY_EMOJI_NEXT, 1.2f));
                else arCtrl.add(new KeyDef("", "", 1, 1.2f));
                arCtrl.add(new KeyDef("⌫", "", 3, 1.5f));
                this.layoutRows.add(arCtrl);
            } else {
                ArrayList<KeyDef> sp1 = new ArrayList<>();
                for (String str11 : new String[]{"✓", "√", "π", "¿", "¡", "§", "≈", "≠", "±", "∞"}) {
                    sp1.add(new KeyDef(str11, str11, 1, 1.0f));
                }
                this.layoutRows.add(sp1);

                ArrayList<KeyDef> sp2 = new ArrayList<>();
                for (String str12 : new String[]{"½", "¼", "¾", "²", "³", "«", "»", "‹", "›", "µ"}) {
                    sp2.add(new KeyDef(str12, str12, 1, 1.0f));
                }
                this.layoutRows.add(sp2);

                ArrayList<KeyDef> sp3 = new ArrayList<>();
                sp3.add(new KeyDef("?123", "", 6, 1.4f));
                for (String str13 : new String[]{"†", "‡", "♠", "♣", "♥", "♦"}) {
                    sp3.add(new KeyDef(str13, str13, 1, 1.0f));
                }
                sp3.add(new KeyDef("⌫", "", 3, 1.4f));
                this.layoutRows.add(sp3);

                ArrayList<KeyDef> sp4 = new ArrayList<>();
                sp4.add(new KeyDef("ABC", "", 8, 1.4f));
                sp4.add(new KeyDef(",", ",", 1, 1.1f));
                sp4.add(new KeyDef(zEng ? "Space" : "Spasi", " ", 4, 5.1f));
                sp4.add(new KeyDef(".", ".", 1, 1.1f));
                sp4.add(new KeyDef("⏎", "", 5, 1.4f));
                this.layoutRows.add(sp4);
            }

        }

        Iterator<ArrayList<KeyDef>> it = this.layoutRows.iterator();
        while (it.hasNext()) {
            this.allKeys.addAll(it.next());
        }
        recalculateKeyBounds(getWidth(), getHeight());
    }

    @Override
    protected void onMeasure(int i, int i2) {
        float density = getContext().getResources().getDisplayMetrics().density;
        int bottomPadding = (int)(this.prefs.getInt("keyboard_padding_bottom", 0) * density);
        int rows = Math.max(this.layoutRows.size(), 4);
        boolean hasTopBar = (this.currentMode != 3);
        int totalHeight;
        if (hasTopBar && rows > 1) {
            int topBarH = (int)(this.keyHeightPx * 0.70f);
            totalHeight = topBarH + ((rows - 1) * this.keyHeightPx) + (int)(16 * density) + bottomPadding;
        } else {
            totalHeight = (rows * this.keyHeightPx) + (int)(16 * density) + bottomPadding;
        }
        setMeasuredDimension(View.MeasureSpec.getSize(i), totalHeight);
    }

    @Override
    protected void onSizeChanged(int i, int i2, int i3, int i4) {
        super.onSizeChanged(i, i2, i3, i4);
        recalculateKeyBounds(i, i2);
    }

    private void recalculateKeyBounds(int i, int i2) {
        float density = getResources().getDisplayMetrics().density;
        int bottomPadding = (int)(this.prefs.getInt("keyboard_padding_bottom", 0) * density);
        i2 -= bottomPadding;
        if (i <= 0 || i2 <= 0 || this.layoutRows.isEmpty()) {
            return;
        }

        int rowCount = this.layoutRows.size();
        boolean hasTopBar = (this.currentMode != 3);

        String spacingPref = this.prefs != null ? this.prefs.getString("key_spacing_level", "normal") : "normal";
        float gapMult = "tight".equals(spacingPref) ? 0.55f : ("wide".equals(spacingPref) ? 1.55f : 1.0f);
        float gapH = Math.max(2.0f, 3.2f * density * gapMult);
        float gapV = Math.max(2.5f, 4.0f * density * gapMult);
        float padSide = Math.max(2.5f, 3.5f * density);
        float padTop = Math.max(2.5f, 3.0f * density);

        String oneHanded = this.prefs != null ? this.prefs.getString("one_handed_mode", "off") : "off";
        float contentStartX = padSide;
        float contentWidth = i - (2.0f * padSide);
        if ("left".equals(oneHanded)) {
            contentWidth = contentWidth * 0.84f;
        } else if ("right".equals(oneHanded)) {
            float fullW = contentWidth;
            contentWidth = fullW * 0.84f;
            contentStartX = padSide + (fullW - contentWidth);
        }

        float availH = i2 - padTop - ((rowCount - 1) * gapV);
        if (availH <= 0) availH = i2;

        float totalWeight = (hasTopBar && rowCount > 1) ? (0.70f + (rowCount - 1)) : rowCount;
        float rowHeight = availH / totalWeight;
        float topBarHeight = rowHeight * 0.70f;

        float fontScale = this.prefs.getFloat("key_font_size_scale", 1.0f);
        this.textPaint.setTextSize(0.42f * rowHeight * fontScale);
        this.specTextPaint.setTextSize(0.26f * rowHeight * fontScale);
        this.topBarTextPaint.setTextSize(0.38f * topBarHeight * fontScale);
        this.popupTextPaint.setTextSize(0.55f * rowHeight * fontScale);

        float curY = padTop;
        for (int r = 0; r < rowCount; r++) {
            ArrayList<KeyDef> rowKeys = this.layoutRows.get(r);
            float h = (r == 0 && hasTopBar) ? topBarHeight : rowHeight;
            float totalWWeight = 0.0f;
            for (KeyDef k : rowKeys) {
                totalWWeight += k.weight;
            }
            if (totalWWeight <= 0) totalWWeight = 1.0f;

            float availW = contentWidth - ((rowKeys.size() - 1) * gapH);
            float curX = contentStartX;
            for (int c = 0; c < rowKeys.size(); c++) {
                KeyDef k = rowKeys.get(c);
                float w = (k.weight / totalWWeight) * availW;
                k.bounds.set(curX, curY, curX + w, curY + h);
                curX += w + gapH;
            }
            curY += h + gapV;
        }
    }

    private void ensureThemeColors(String theme) {
        if (theme == null) theme = "Dark";
        if (theme.equals(this.cachedThemeName)) return;
        this.cachedThemeName = theme;
        if (theme.equals("Amoled")) {
            this.cachedBgCol = Color.BLACK;
            this.cachedKeyCol = Color.parseColor("#18181B");
            this.cachedKeyShadowCol = Color.parseColor("#0C0C0E");
            this.cachedSpecCol = Color.parseColor("#222226");
            this.cachedSpecShadowCol = Color.parseColor("#121214");
            this.cachedActionCol = Color.parseColor("#2563EB");
            this.cachedActionShadowCol = Color.parseColor("#1D4ED8");
            this.cachedTextCol = Color.WHITE;
            this.cachedSpecTextCol = Color.parseColor("#E4E4E7");
            this.cachedHlCol = Color.parseColor("#323238");
            this.cachedTopBarPillCol = Color.parseColor("#1F1F23");
            this.cachedTopBarShadowCol = Color.parseColor("#121214");
        } else if (theme.equals("HighContrastYellow")) {
            this.cachedBgCol = Color.BLACK;
            this.cachedKeyCol = Color.parseColor("#111111");
            this.cachedKeyShadowCol = Color.parseColor("#FFD60A");
            this.cachedSpecCol = Color.parseColor("#1E1E1E");
            this.cachedSpecShadowCol = Color.parseColor("#F59E0B");
            this.cachedActionCol = Color.parseColor("#005BBB");
            this.cachedActionShadowCol = Color.parseColor("#FFD60A");
            this.cachedTextCol = Color.parseColor("#FFD60A");
            this.cachedSpecTextCol = Color.WHITE;
            this.cachedHlCol = Color.parseColor("#333300");
            this.cachedTopBarPillCol = Color.parseColor("#1A1A1A");
            this.cachedTopBarShadowCol = Color.parseColor("#FFD60A");
        } else if (theme.equals("OceanBlue")) {
            this.cachedBgCol = Color.parseColor("#0B132B");
            this.cachedKeyCol = Color.parseColor("#1C2541");
            this.cachedKeyShadowCol = Color.parseColor("#080E1E");
            this.cachedSpecCol = Color.parseColor("#2A3B5E");
            this.cachedSpecShadowCol = Color.parseColor("#111A30");
            this.cachedActionCol = Color.parseColor("#0284C7");
            this.cachedActionShadowCol = Color.parseColor("#0369A1");
            this.cachedTextCol = Color.parseColor("#F8FAFC");
            this.cachedSpecTextCol = Color.parseColor("#BAE6FD");
            this.cachedHlCol = Color.parseColor("#3A506B");
            this.cachedTopBarPillCol = Color.parseColor("#1C2541");
            this.cachedTopBarShadowCol = Color.parseColor("#080E1E");
        } else if (theme.equals("Light")) {
            this.cachedBgCol = Color.parseColor("#EDF1F5");
            this.cachedKeyCol = Color.WHITE;
            this.cachedKeyShadowCol = Color.parseColor("#CBD5E1");
            this.cachedSpecCol = Color.parseColor("#DCE1E7");
            this.cachedSpecShadowCol = Color.parseColor("#BCC3CB");
            this.cachedActionCol = Color.parseColor("#2563EB");
            this.cachedActionShadowCol = Color.parseColor("#1D4ED8");
            this.cachedTextCol = Color.parseColor("#0F172A");
            this.cachedSpecTextCol = Color.parseColor("#334155");
            this.cachedHlCol = Color.parseColor("#DBEAFE");
            this.cachedTopBarPillCol = Color.parseColor("#E2E8F0");
            this.cachedTopBarShadowCol = Color.parseColor("#CBD5E1");
        } else {
            this.cachedBgCol = Color.parseColor("#1A1B1F");
            this.cachedKeyCol = Color.parseColor("#2D2F35");
            this.cachedKeyShadowCol = Color.parseColor("#1E2024");
            this.cachedSpecCol = Color.parseColor("#24262B");
            this.cachedSpecShadowCol = Color.parseColor("#18191C");
            this.cachedActionCol = Color.parseColor("#2563EB");
            this.cachedActionShadowCol = Color.parseColor("#1D4ED8");
            this.cachedTextCol = Color.parseColor("#F8FAFC");
            this.cachedSpecTextCol = Color.parseColor("#CBD5E1");
            this.cachedHlCol = Color.parseColor("#3E414A");
            this.cachedTopBarPillCol = Color.parseColor("#24262B");
            this.cachedTopBarShadowCol = Color.parseColor("#18191C");
        }
        this.keyPaint.setColor(this.cachedKeyCol);
        this.keyShadowPaint.setColor(this.cachedKeyShadowCol);
        this.keySpecialPaint.setColor(this.cachedSpecCol);
        this.keySpecialShadowPaint.setColor(this.cachedSpecShadowCol);
        this.actionKeyPaint.setColor(this.cachedActionCol);
        this.actionKeyShadowPaint.setColor(this.cachedActionShadowCol);
        this.keyHighlightPaint.setColor(this.cachedHlCol);
        this.topBarPillPaint.setColor(this.cachedTopBarPillCol);
        this.topBarPillShadowPaint.setColor(this.cachedTopBarShadowCol);
        this.topBarTextPaint.setColor(this.cachedSpecTextCol);
        this.popupBgPaint.setColor(theme.equals("Light") ? Color.WHITE : this.cachedKeyCol);
        this.popupTextPaint.setColor(theme.equals("Light") ? this.cachedTextCol : Color.WHITE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        String theme = prefs != null ? prefs.getString("keyboard_theme", "Dark") : "Dark";
        ensureThemeColors(theme);
        canvas.drawColor(this.cachedBgCol);

        float density = getResources().getDisplayMetrics().density;
        float shadowOffset = Math.max(1.5f, 2.0f * density);
        float radius = this.prefs != null ? this.prefs.getFloat("key_border_radius", 10.0f) : 10.0f;
        if (radius <= 0f) radius = 0f;
        boolean popup = this.prefs == null || this.prefs.getBoolean("popup_on_keypress", true);

        int keyCount = this.allKeys.size();
        for (int i = 0; i < keyCount; i++) {
            KeyDef keyDef = this.allKeys.get(i);
            boolean isPressed = (i == this.hoveredVirtualKeyId) || isPointerOnKey(i);
            if (!isPressed && requiresDoubleTap(keyDef) && i == this.focusedVirtualKeyId) {
                isPressed = true;
            }

            float keyRadius = radius;
            if (keyDef.isTopBar) {
                keyRadius = Math.min(keyDef.bounds.height() / 2.0f, 14.0f * density);
                if (radius == 0f) keyRadius = 0f;

                if (isPressed) {
                    this.tmpKeyRect.set(keyDef.bounds.left, keyDef.bounds.top + 1.5f, keyDef.bounds.right, keyDef.bounds.bottom + 1.5f);
                    canvas.drawRoundRect(this.tmpKeyRect, keyRadius, keyRadius, this.keyHighlightPaint);
                } else {
                    this.tmpShadowRect.set(keyDef.bounds.left, keyDef.bounds.top + shadowOffset, keyDef.bounds.right, keyDef.bounds.bottom + shadowOffset);
                    canvas.drawRoundRect(this.tmpShadowRect, keyRadius, keyRadius, this.topBarPillShadowPaint);
                    canvas.drawRoundRect(keyDef.bounds, keyRadius, keyRadius, this.topBarPillPaint);
                }

                float textY = keyDef.bounds.centerY() - ((this.topBarTextPaint.descent() + this.topBarTextPaint.ascent()) / 2.0f);
                if (isPressed) textY += 1.5f;
                canvas.drawText(keyDef.label, keyDef.bounds.centerX(), textY, this.topBarTextPaint);
                continue;
            }

            Paint facePaint;
            Paint shadowPaint;
            boolean isShiftActive = (keyDef.type == KEY_SHIFT && this.isCaps);
            boolean isShiftLocked = (keyDef.type == KEY_SHIFT && this.isCapsLocked);
            if (isPressed) {
                facePaint = this.keyHighlightPaint;
                shadowPaint = null;
            } else if (keyDef.type == KEY_ENTER || isShiftLocked) {
                facePaint = this.actionKeyPaint;
                shadowPaint = this.actionKeyShadowPaint;
            } else if (isShiftActive) {
                facePaint = this.keyHighlightPaint;
                shadowPaint = this.keySpecialShadowPaint;
            } else if (keyDef.type == KEY_CHAR || keyDef.type == KEY_SPACE) {
                facePaint = this.keyPaint;
                shadowPaint = this.keyShadowPaint;
            } else {
                facePaint = this.keySpecialPaint;
                shadowPaint = this.keySpecialShadowPaint;
            }

            if (isPressed) {
                this.tmpKeyRect.set(keyDef.bounds.left, keyDef.bounds.top + shadowOffset, keyDef.bounds.right, keyDef.bounds.bottom + shadowOffset);
                canvas.drawRoundRect(this.tmpKeyRect, keyRadius, keyRadius, facePaint);
            } else {
                this.tmpShadowRect.set(keyDef.bounds.left, keyDef.bounds.top + shadowOffset, keyDef.bounds.right, keyDef.bounds.bottom + shadowOffset);
                canvas.drawRoundRect(this.tmpShadowRect, keyRadius, keyRadius, shadowPaint);
                canvas.drawRoundRect(keyDef.bounds, keyRadius, keyRadius, facePaint);
            }

            String str = keyDef.label;
            if (keyDef.type == KEY_SHIFT) {
                str = this.isCapsLocked ? "⇪" : (this.isCaps ? "⬆" : "⇧");
            } else if (keyDef.type == KEY_CHAR && str.length() == 1 && Character.isLetter(str.charAt(0))) {
                boolean isUpper = this.isCaps;
                int alphabetIndex = Character.toLowerCase(str.charAt(0)) - 'a';
                if (this.currentFontIndex > 0 && alphabetIndex >= 0 && alphabetIndex < 26) {
                    if (isUpper && this.currentFontIndex < KeyboardData.FONT_MAP_UPPER.length) {
                        str = KeyboardData.FONT_MAP_UPPER[this.currentFontIndex][alphabetIndex];
                    } else if (!isUpper && this.currentFontIndex < KeyboardData.FONT_MAP_LOWER.length) {
                        str = KeyboardData.FONT_MAP_LOWER[this.currentFontIndex][alphabetIndex];
                    } else {
                        str = isUpper ? str.toUpperCase() : str.toLowerCase();
                    }
                } else {
                    str = isUpper ? str.toUpperCase() : str.toLowerCase();
                }
            }

            Paint chosenPaint;
            if (keyDef.type == KEY_ENTER || isShiftLocked) {
                chosenPaint = this.textPaint;
                this.textPaint.setColor(Color.WHITE);
            } else if (isShiftActive) {
                chosenPaint = this.specTextPaint;
                this.specTextPaint.setColor(this.cachedActionCol);
            } else if (keyDef.type == KEY_CHAR && str.length() == 1) {
                chosenPaint = this.textPaint;
                this.textPaint.setColor(this.cachedTextCol);
            } else {
                chosenPaint = this.specTextPaint;
                this.specTextPaint.setColor(this.cachedSpecTextCol);
            }

            float textY = keyDef.bounds.centerY() - ((chosenPaint.descent() + chosenPaint.ascent()) / 2.0f);
            if (isPressed) textY += shadowOffset;
            canvas.drawText(str, keyDef.bounds.centerX(), textY, chosenPaint);

            if (isPressed && popup && keyDef.type == KEY_CHAR && str.length() == 1) {
                float popupW = keyDef.bounds.width() * 1.35f;
                float popupH = keyDef.bounds.height() * 1.35f;
                float popupCenterX = keyDef.bounds.centerX();
                float popupCenterY = keyDef.bounds.centerY() - (keyDef.bounds.height() * 1.15f);
                this.tmpPopupRect.set(popupCenterX - (popupW / 2.0f), popupCenterY - (popupH / 2.0f),
                        popupCenterX + (popupW / 2.0f), popupCenterY + (popupH / 2.0f));
                this.tmpPopupShadowRect.set(this.tmpPopupRect.left, this.tmpPopupRect.top + shadowOffset,
                        this.tmpPopupRect.right, this.tmpPopupRect.bottom + shadowOffset);
                canvas.drawRoundRect(this.tmpPopupShadowRect, keyRadius + 4f, keyRadius + 4f, this.keyShadowPaint);
                canvas.drawRoundRect(this.tmpPopupRect, keyRadius + 4f, keyRadius + 4f, this.popupBgPaint);
                float pTextY = this.tmpPopupRect.centerY() - ((this.popupTextPaint.descent() + this.popupTextPaint.ascent()) / 2.0f);
                canvas.drawText(str, this.tmpPopupRect.centerX(), pTextY, this.popupTextPaint);
            }
        }
    }

    private void clearAllPointers() {
        for (int i = 0; i < MAX_POINTERS; i++) {
            this.pointerIds[i] = -1;
            this.pointerKeys[i] = -1;
            this.pointerDownKeys[i] = -1;
            this.pointerDownX[i] = 0f;
            this.pointerDownY[i] = 0f;
            this.pointerMovedFar[i] = false;
            this.pointerLongPressSerial[i] = 0;
        }
    }

    private int findPointerSlot(int pointerId) {
        for (int i = 0; i < MAX_POINTERS; i++) {
            if (this.pointerIds[i] == pointerId) return i;
        }
        return -1;
    }

    private int acquirePointerSlot(int pointerId) {
        int existing = findPointerSlot(pointerId);
        if (existing != -1) return existing;
        for (int i = 0; i < MAX_POINTERS; i++) {
            if (this.pointerIds[i] == -1) {
                this.pointerIds[i] = pointerId;
                return i;
            }
        }
        return -1;
    }

    private void clearPointerSlot(int slot) {
        if (slot < 0 || slot >= MAX_POINTERS) return;
        this.pointerIds[slot] = -1;
        this.pointerKeys[slot] = -1;
        this.pointerDownKeys[slot] = -1;
        this.pointerMovedFar[slot] = false;
    }

    private boolean isPointerOnKey(int keyIndex) {
        for (int i = 0; i < MAX_POINTERS; i++) {
            if (this.pointerIds[i] != -1 && this.pointerKeys[i] == keyIndex) return true;
        }
        return false;
    }

    /**
     * Hit-testing untuk pengetikan cepat (tanpa dead-zone di celah/pinggir tombol).
     * Top bar hanya di-snap kalau jari memang di area vertikal top bar supaya Q-P tidak nyasar ke menu atas.
     */
    private int findKeyIndexForTap(float x, float y) {
        if (x < 0f || x >= getWidth() || y < 0f || y >= getHeight()) return -1;
        int n = this.allKeys.size();
        if (n == 0) return -1;
        for (int i = 0; i < n; i++) {
            RectF b = this.allKeys.get(i).bounds;
            if (b != null && b.contains(x, y)) {
                return i;
            }
        }
        float density = density();
        float maxDist = 36.0f * density;
        float topBarBottom = -1.0f;
        for (int i = 0; i < n; i++) {
            KeyDef kd = this.allKeys.get(i);
            if (kd.isTopBar && kd.bounds != null) {
                if (kd.bounds.bottom > topBarBottom) topBarBottom = kd.bounds.bottom;
            } else {
                break;
            }
        }
        boolean allowTopBarSnap = (topBarBottom > 0f && y <= topBarBottom + (2.0f * density));

        int best = -1;
        float bestDistSq = maxDist * maxDist;
        for (int i = 0; i < n; i++) {
            KeyDef kd = this.allKeys.get(i);
            if (kd.bounds == null) continue;
            if (kd.isTopBar && !allowTopBarSnap) continue;
            RectF r = kd.bounds;
            float dx = Math.max(Math.max(r.left - x, 0.0f), x - r.right);
            float dy = Math.max(Math.max(r.top - y, 0.0f), y - r.bottom);
            float dSq = (dx * dx) + (dy * dy);
            if (dSq <= bestDistSq) {
                bestDistSq = dSq;
                best = i;
            }
        }
        return best;
    }

    /** Hit-testing untuk explore-by-touch: cek bounds langsung, fallback snap celah tipis max 10dp. */
    private int findKeyIndexExplore(float x, float y) {
        if (x < 0f || x >= getWidth() || y < 0f || y >= getHeight()) return -1;
        int n = this.allKeys.size();
        for (int i = 0; i < n; i++) {
            RectF b = this.allKeys.get(i).bounds;
            if (b != null && b.contains(x, y)) {
                return i;
            }
        }
        float maxDist = 10.0f * density();
        int best = -1;
        float bestDistSq = maxDist * maxDist;
        for (int i = 0; i < n; i++) {
            RectF r = this.allKeys.get(i).bounds;
            if (r == null) continue;
            float dx = Math.max(Math.max(r.left - x, 0.0f), x - r.right);
            float dy = Math.max(Math.max(r.top - y, 0.0f), y - r.bottom);
            float dSq = (dx * dx) + (dy * dy);
            if (dSq <= bestDistSq) {
                bestDistSq = dSq;
                best = i;
            }
        }
        return best;
    }

    private void startLongPressForKey(int keyIdx) {
        if (keyIdx < 0 || keyIdx >= this.allKeys.size()) return;
        int type = this.allKeys.get(keyIdx).type;
        if (type == KEY_DEL) {
            this.isDeleteLongPressed = true;
            this.deleteHandler.removeCallbacks(this.deleteLongPressRunnable);
            int prefDelay = this.prefs != null ? this.prefs.getInt("delay_hapus_ms", 600) : 600;
            int initialDelay = Math.max(prefDelay, MIN_DELETE_INITIAL_DELAY_MS);
            this.deleteHandler.postDelayed(this.deleteLongPressRunnable, initialDelay);
        } else if (type == KEY_SPACE) {
            this.isSpaceLongPressed = true;
            this.deleteHandler.removeCallbacks(this.spaceLongPressRunnable);
            // Spasi ditekan lama tepat 3 detik (3000ms) -> ganti keyboard
            this.deleteHandler.postDelayed(this.spaceLongPressRunnable, SPACE_LONG_PRESS_MS);
        }
        // Huruf (KEY_CHAR) ditekan lama tidak pernah mengeluarkan angka/simbol otomatis agar ramah tunanetra.
    }

    private void cancelLongPressForKey(int keyIdx) {
        if (keyIdx < 0 || keyIdx >= this.allKeys.size()) return;
        int type = this.allKeys.get(keyIdx).type;
        if (type == KEY_DEL) {
            this.isDeleteLongPressed = false;
            this.deleteHandler.removeCallbacks(this.deleteLongPressRunnable);
        } else if (type == KEY_SPACE) {
            this.isSpaceLongPressed = false;
            this.deleteHandler.removeCallbacks(this.spaceLongPressRunnable);
        }
    }

    private void cancelAllLongPress() {
        this.isDeleteLongPressed = false;
        this.isSpaceLongPressed = false;
        this.deleteHandler.removeCallbacks(this.deleteLongPressRunnable);
        this.deleteHandler.removeCallbacks(this.spaceLongPressRunnable);
    }

    /** Batalkan seluruh interaksi sentuh & timer aktif saat keyboard tertutup atau kehilangan fokus. */
    public void cancelTouchInteraction() {
        this.spaceGlidePointerId = -1;
        this.spaceGlideMoved = false;
        this.delSwipePointerId = -1;
        this.delSwipeTriggered = false;
        this.hoveredVirtualKeyId = -1;
        this.focusedVirtualKeyId = -1;
        this.lastTappedKeyIndex = -1;
        clearAllPointers();
        cancelAllLongPress();
        invalidate();
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent motionEvent) {
        if (this.accessibilityManager == null || !this.accessibilityManager.isEnabled()) {
            return false;
        }
        int action = motionEvent.getAction();
        float x = motionEvent.getX();
        float y = motionEvent.getY();
        int hitIdx = findKeyIndexExplore(x, y);
        boolean z = this.prefs != null && this.prefs.getBoolean("interface_english", false);
        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
                this.lastHoverX = x;
                this.lastHoverY = y;
                this.lastHoverMoveTime = System.currentTimeMillis();
                // fall through
            case MotionEvent.ACTION_HOVER_MOVE:
                if (action == MotionEvent.ACTION_HOVER_MOVE) {
                    long now = System.currentTimeMillis();
                    if ((now - this.lastHoverMoveTime) >= 45L) {
                        this.lastHoverX = x;
                        this.lastHoverY = y;
                        this.lastHoverMoveTime = now;
                    }
                }
                if (hitIdx == -1) {
                    // Jari digeser keluar dari area tombol/layar -> cancel tombol saat ini agar tidak salah ketik saat dilepas
                    if (this.hoveredVirtualKeyId != -1) {
                        cancelLongPressForKey(this.hoveredVirtualKeyId);
                        sendVirtualAccessibilityEvent(this.hoveredVirtualKeyId, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
                        this.hoveredVirtualKeyId = -1;
                        this.focusedVirtualKeyId = -1;
                        invalidate();
                    }
                    return true;
                }
                if (hitIdx != this.hoveredVirtualKeyId) {
                    if (this.hoveredVirtualKeyId != -1) {
                        cancelLongPressForKey(this.hoveredVirtualKeyId);
                        sendVirtualAccessibilityEvent(this.hoveredVirtualKeyId, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
                    }
                    this.hoveredVirtualKeyId = hitIdx;
                    this.focusedVirtualKeyId = hitIdx;
                    this.lastTappedKeyIndex = hitIdx;
                    this.hoverLongPressSerial = this.longPressSerial;
                    announceKeyForScreenReader(hitIdx, z);
                    if (shouldPlayPreviewFeedback()) {
                        playAudio();
                        vibrate(false);
                    }
                    // Izinkan tahan lama Spasi 3 detik & tahan lama Hapus sesuai delay_hapus_ms
                    startLongPressForKey(hitIdx);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_HOVER_EXIT:
                if (this.hoveredVirtualKeyId != -1) {
                    int exitKey = this.hoveredVirtualKeyId;
                    cancelLongPressForKey(exitKey);
                    boolean insideValidKey = (hitIdx != -1);
                    boolean holdTriggered = (this.longPressSerial != this.hoverLongPressSerial);
                    float dxExit = x - this.lastHoverX;
                    float dyExit = y - this.lastHoverY;
                    long dtExit = System.currentTimeMillis() - this.lastHoverMoveTime;
                    boolean fastSlideSwipe = (dtExit < 85L && (dxExit * dxExit + dyExit * dyExit) > (36f * density() * 36f * density()));
                    KeyDef exitKeyDef = this.allKeys.get(exitKey);
                    if (insideValidKey && !fastSlideSwipe && !holdTriggered && !requiresDoubleTap(exitKeyDef)) {
                        this.lastHoverExecutedKey = exitKey;
                        this.lastHoverExecutedAt = android.os.SystemClock.uptimeMillis();
                        safeExecuteKey(exitKeyDef);
                        vibrate(true);
                    } else if (insideValidKey && !fastSlideSwipe && !holdTriggered) {
                        this.focusedVirtualKeyId = exitKey;
                        this.lastTappedKeyIndex = exitKey;
                        this.lastTapTime = System.currentTimeMillis();
                    }
                    sendVirtualAccessibilityEvent(exitKey, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
                    this.hoveredVirtualKeyId = -1;
                    invalidate();
                }
                return true;
            default:
                return super.dispatchHoverEvent(motionEvent);
        }
    }

    /**
     * Touch-response profile controls optional preview feedback only; it never inserts
     * a timer into key activation.
     */
    private boolean shouldPlayPreviewFeedback() {
        if (this.prefs == null) return true;
        return this.prefs.getInt("touch_response_ms", 0) > 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent motionEvent) {
        int actionMasked = motionEvent.getActionMasked();
        int actionIndex = motionEvent.getActionIndex();
        int pointerId = motionEvent.getPointerId(actionIndex);
        boolean z = this.prefs != null && this.prefs.getBoolean("interface_english", false);
        boolean exploring = false;
        try {
            exploring = this.accessibilityManager != null
                    && this.accessibilityManager.isEnabled()
                    && this.accessibilityManager.isTouchExplorationEnabled();
        } catch (Throwable ignored) {}
        boolean doubleTap = isDoubleTapMode();

        float density = density();
        float tapSlop = 28.0f * density;

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN: {
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                // Fall through to handle pointer down
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                int slot = acquirePointerSlot(pointerId);
                if (slot == -1) return true;
                float x = motionEvent.getX(actionIndex);
                float y = motionEvent.getY(actionIndex);
                int keyIdx = exploring ? findKeyIndexExplore(x, y) : findKeyIndexForTap(x, y);
                this.pointerKeys[slot] = keyIdx;
                this.pointerDownKeys[slot] = keyIdx;
                this.pointerDownX[slot] = x;
                this.pointerDownY[slot] = y;
                this.pointerMovedFar[slot] = false;
                this.pointerLongPressSerial[slot] = this.longPressSerial;

                if (keyIdx != -1) {
                    int kType = this.allKeys.get(keyIdx).type;
                    if (!exploring && !doubleTap && kType == KEY_SPACE
                            && this.prefs != null && this.prefs.getBoolean("spacebar_cursor_glide", true)
                            && this.spaceGlidePointerId == -1) {
                        this.spaceGlidePointerId = pointerId;
                        this.spaceGlideLastX = x;
                        this.spaceGlideMoved = false;
                    } else if (!exploring && !doubleTap && kType == KEY_DEL
                            && this.prefs != null && this.prefs.getBoolean("swipe_delete_word_enabled", true)
                            && this.delSwipePointerId == -1) {
                        this.delSwipePointerId = pointerId;
                        this.delSwipeStartX = x;
                        this.delSwipeTriggered = false;
                    }
                    if (exploring || requiresDoubleTap(this.allKeys.get(keyIdx))) {
                        this.focusedVirtualKeyId = keyIdx;
                        this.hoveredVirtualKeyId = keyIdx;
                        announceKeyForScreenReader(keyIdx, z);
                    }
                    if (shouldPlayPreviewFeedback()) {
                        playAudio();
                        vibrate(false);
                    }
                    startLongPressForKey(keyIdx);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                boolean needsRedraw = false;
                int pCount = motionEvent.getPointerCount();
                for (int p = 0; p < pCount; p++) {
                    int pId = motionEvent.getPointerId(p);
                    int slot = findPointerSlot(pId);
                    if (slot == -1) continue;
                    float x = motionEvent.getX(p);
                    float y = motionEvent.getY(p);

                    if (!exploring && pId == this.spaceGlidePointerId && this.listener != null) {
                        float totalDx = x - this.pointerDownX[slot];
                        if (!this.spaceGlideMoved && Math.abs(totalDx) > Math.max(tapSlop, 22f * density)) {
                            this.spaceGlideMoved = true;
                            this.spaceGlideLastX = x;
                            cancelLongPressForKey(this.pointerDownKeys[slot]);
                        }
                        if (this.spaceGlideMoved) {
                            float stepPx = Math.max(12f * density, 18f);
                            float dxGlide = x - this.spaceGlideLastX;
                            int steps = (int) (dxGlide / stepPx);
                            if (steps != 0) {
                                this.spaceGlideLastX += steps * stepPx;
                                this.listener.onMoveCursor(steps);
                                vibrate(false);
                            }
                            continue;
                        }
                    }

                    if (!exploring && pId == this.delSwipePointerId && this.listener != null) {
                        float dragLeft = this.delSwipeStartX - x;
                        if (dragLeft > Math.max(tapSlop * 1.4f, 40f * density)) {
                            this.delSwipeTriggered = true;
                            this.delSwipeStartX = x;
                            cancelLongPressForKey(this.pointerDownKeys[slot]);
                            this.listener.onCommitText("LONG_DELETE");
                            vibrate(true);
                        }
                        if (this.delSwipeTriggered) continue;
                    }

                    if (exploring) {
                        int newKey = findKeyIndexExplore(x, y);
                        int oldKey = this.pointerKeys[slot];
                        if (newKey == -1) {
                            // Keluar area keyboard saat geser-geser -> batalkan tombol aktif agar aman saat jari dilepas
                            if (oldKey != -1) {
                                cancelLongPressForKey(oldKey);
                                this.pointerKeys[slot] = -1;
                                this.focusedVirtualKeyId = -1;
                                this.hoveredVirtualKeyId = -1;
                                needsRedraw = true;
                            }
                        } else if (newKey != oldKey) {
                            cancelLongPressForKey(oldKey);
                            this.pointerKeys[slot] = newKey;
                            this.focusedVirtualKeyId = newKey;
                            this.hoveredVirtualKeyId = newKey;
                            this.pointerLongPressSerial[slot] = this.longPressSerial;
                            announceKeyForScreenReader(newKey, z);
                            if (shouldPlayPreviewFeedback()) {
                                playAudio();
                                vibrate(false);
                            }
                            startLongPressForKey(newKey);
                            needsRedraw = true;
                        }
                    } else {
                        float dx = x - this.pointerDownX[slot];
                        float dy = y - this.pointerDownY[slot];
                        if ((dx * dx + dy * dy) > (tapSlop * tapSlop)) {
                            this.pointerMovedFar[slot] = true;
                            int newKey = findKeyIndexForTap(x, y);
                            int oldKey = this.pointerKeys[slot];
                            if (newKey == -1) {
                                // Digeser keluar batas view -> cancel ketik saat jari dilepas
                                if (oldKey != -1) {
                                    cancelLongPressForKey(oldKey);
                                    this.pointerKeys[slot] = -1;
                                    this.hoveredVirtualKeyId = -1;
                                    needsRedraw = true;
                                }
                            } else if (newKey != oldKey) {
                                cancelLongPressForKey(oldKey);
                                this.pointerKeys[slot] = newKey;
                                this.pointerLongPressSerial[slot] = this.longPressSerial;
                                if (requiresDoubleTap(this.allKeys.get(newKey))) {
                                    this.focusedVirtualKeyId = newKey;
                                    this.hoveredVirtualKeyId = newKey;
                                    announceKeyForScreenReader(newKey, z);
                                }
                                startLongPressForKey(newKey);
                                needsRedraw = true;
                            }
                        }
                    }
                }
                if (needsRedraw) invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int slot = findPointerSlot(pointerId);
                if (slot == -1) return true;
                boolean consumedSpaceGlide = (pointerId == this.spaceGlidePointerId && this.spaceGlideMoved);
                if (pointerId == this.spaceGlidePointerId) {
                    this.spaceGlidePointerId = -1;
                    this.spaceGlideMoved = false;
                }
                boolean consumedDelSwipe = (pointerId == this.delSwipePointerId && this.delSwipeTriggered);
                if (pointerId == this.delSwipePointerId) {
                    this.delSwipePointerId = -1;
                    this.delSwipeTriggered = false;
                }
                float x = motionEvent.getX(actionIndex);
                float y = motionEvent.getY(actionIndex);
                int keyIdx = this.pointerKeys[slot];
                int downKey = this.pointerDownKeys[slot];
                boolean movedFar = this.pointerMovedFar[slot];
                boolean lpFired = (this.longPressSerial != this.pointerLongPressSerial[slot]) || consumedSpaceGlide || consumedDelSwipe;

                if (x < 0f || x >= getWidth() || y < 0f || y >= getHeight()) {
                    // Jari dilepas di luar area keyboard -> selalu cancel ketik
                    keyIdx = -1;
                } else if (!exploring) {
                    float dx = x - this.pointerDownX[slot];
                    float dy = y - this.pointerDownY[slot];
                    if (!movedFar && (dx * dx + dy * dy) <= (tapSlop * tapSlop)) {
                        keyIdx = (downKey >= 0) ? downKey : findKeyIndexForTap(x, y);
                    }
                    // Jika movedFar == true dan pointerKeys[slot] == -1, tetap -1 (tercancel)
                }

                cancelLongPressForKey(keyIdx);
                if (downKey != keyIdx) cancelLongPressForKey(downKey);
                clearPointerSlot(slot);
                if (!exploring && !doubleTap) {
                    this.hoveredVirtualKeyId = -1;
                }

                if (keyIdx >= 0 && keyIdx < this.allKeys.size() && !lpFired) {
                    KeyDef upKeyDef = this.allKeys.get(keyIdx);
                    if (!requiresDoubleTap(upKeyDef)) {
                        boolean dupFromHover = exploring && keyIdx == this.lastHoverExecutedKey
                                && (android.os.SystemClock.uptimeMillis() - this.lastHoverExecutedAt) < 120L;
                        if (!dupFromHover) {
                            safeExecuteKey(upKeyDef);
                            vibrate(true);
                        }
                    } else {
                        long now = System.currentTimeMillis();
                        long doubleTapLimit = this.prefs != null ? this.prefs.getInt("double_tap_delay_ms", 700) : 700L;
                        if (this.lastTappedKeyIndex == keyIdx && (now - this.lastTapTime) < doubleTapLimit) {
                            safeExecuteKey(this.allKeys.get(keyIdx));
                            vibrate(true);
                            this.lastTappedKeyIndex = -1;
                            this.lastTapTime = 0L;
                        } else {
                            this.lastTappedKeyIndex = keyIdx;
                            this.lastTapTime = now;
                            this.focusedVirtualKeyId = keyIdx;
                            vibrate(false);
                        }
                    }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                this.spaceGlidePointerId = -1;
                this.spaceGlideMoved = false;
                this.delSwipePointerId = -1;
                this.delSwipeTriggered = false;
                int slot = findPointerSlot(pointerId);
                if (slot != -1) {
                    clearPointerSlot(slot);
                }
                if (actionMasked == MotionEvent.ACTION_CANCEL) {
                    clearAllPointers();
                    this.hoveredVirtualKeyId = -1;
                }
                cancelAllLongPress();
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    private void refreshTypingMode() {
        if (this.prefs != null && MODE_GBOARD.equals(this.prefs.getString("akses_ngetik_mode", "Angkat Jari (Standar)"))) {
            this.prefs.edit().putString("akses_ngetik_mode", "Angkat Jari (Standar)")
                    .putBoolean("double_tap_mode", false).apply();
        }
        cancelAllLongPress();
        clearAllPointers();
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        invalidate();
    }

    private void playAudio() {
        playAudioForKey(-1);
    }

    /** type: KEY_DEL / KEY_SPACE / KEY_CHAR / -1 generic */
    private void playAudioForKey(int keyType) {
        if (this.prefs != null && !this.prefs.getBoolean("sound_ketik_enabled", true)) {
            return;
        }
        float volume = (this.prefs != null ? this.prefs.getInt("sound_effect_volume", 70) : 70) / 100.0f;
        if (this.soundPool == null) {
            try {
                if (this.audioManager != null) this.audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, volume);
            } catch (Throwable ignored) {}
            return;
        }
        int id = -1;
        if (keyType == KEY_DEL && this.deleteSoundId != -1) {
            id = this.deleteSoundId;
        } else if (keyType == KEY_SPACE && this.spaceSoundId != -1) {
            id = this.spaceSoundId;
        } else if (this.typingSoundIds != null && this.typingSoundIds.length > 0) {
            id = this.typingSoundIds[soundRnd.nextInt(this.typingSoundIds.length)];
        } else if (this.customSoundId != -1) {
            id = this.customSoundId;
        } else if (this.spaceSoundId != -1) {
            id = this.spaceSoundId;
        } else if (this.deleteSoundId != -1) {
            id = this.deleteSoundId;
        }
        if (id != -1 && this.isSoundLoaded) {
            try { this.soundPool.play(id, volume, volume, 0, 0, 1.0f); } catch (Throwable ignored) {}
        } else {
            try {
                if (this.audioManager != null) {
                    if (keyType == KEY_DEL) this.audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_DELETE, volume);
                    else if (keyType == KEY_SPACE) this.audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR, volume);
                    else this.audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, volume);
                }
            } catch (Throwable ignored) {}
        }
    }


    @Override
    public boolean performClick() {
        super.performClick();
        // Klik buatan: dipakai mode Ketuk Ganda + screen reader double-tap ke keyboard.
        int target = -1;
        if (this.focusedVirtualKeyId >= 0 && this.focusedVirtualKeyId < this.allKeys.size()) {
            target = this.focusedVirtualKeyId;
        } else if (this.lastTappedKeyIndex >= 0 && this.lastTappedKeyIndex < this.allKeys.size()) {
            target = this.lastTappedKeyIndex;
        } else if (this.hoveredVirtualKeyId >= 0 && this.hoveredVirtualKeyId < this.allKeys.size()) {
            target = this.hoveredVirtualKeyId;
        }
        if (target >= 0) {
            safeExecuteKey(this.allKeys.get(target));
            vibrate(true);
            this.lastTappedKeyIndex = -1;
            this.lastTapTime = 0L;
            return true;
        }
        return false;
    }

    public void safeExecuteKey(KeyDef keyDef) {
        if (keyDef == null) return;
        // Zero-lag path: JANGAN buang/tunda karakter apa pun. Ngetik secepat apa pun wajib masuk.
        this.lastTypingTime = System.currentTimeMillis();
        playAudioForKey(keyDef.type);
        executeKey(keyDef);
    }

    private void executeKey(KeyDef keyDef) {
        String str = "";
        if (this.listener != null && keyDef != null) {
            boolean z = this.prefs.getBoolean("interface_english", false);
            switch (keyDef.type) {
                case KEY_CHAR:
                    String str2 = keyDef.commit;
                    if (str2.length() == 1 && Character.isLetter(str2.charAt(0))) {
                        boolean isUpper = this.isCaps;
                        int alphabetIndex = Character.toLowerCase(str2.charAt(0)) - 'a';
                        if (this.currentFontIndex > 0 && alphabetIndex >= 0 && alphabetIndex < 26) {
                            if (isUpper && this.currentFontIndex < KeyboardData.FONT_MAP_UPPER.length) {
                                str2 = KeyboardData.FONT_MAP_UPPER[this.currentFontIndex][alphabetIndex];
                            } else if (!isUpper && this.currentFontIndex < KeyboardData.FONT_MAP_LOWER.length) {
                                str2 = KeyboardData.FONT_MAP_LOWER[this.currentFontIndex][alphabetIndex];
                            } else {
                                str2 = isUpper ? str2.toUpperCase() : str2.toLowerCase();
                            }
                        } else {
                            str2 = isUpper ? str2.toUpperCase() : str2.toLowerCase();
                        }
                        if (this.isCaps && !this.isCapsLocked) {
                            this.isCaps = false;
                            if ("arabic".equals(this.prefs.getString("keyboard_script", "latin"))) {
                                reloadLayoutConfig();
                            } else {
                                invalidate();
                            }
                        }
                    }
                    this.listener.onCommitText(str2);
                    // Feedback SR setelah karakter BENAR-BENAR terketik (multi-jari + device rewel)
                    announceTypedForScreenReader(keyDef);
                    return;
                case KEY_SHIFT:
                    long time = System.currentTimeMillis();
                    boolean arShift = "arabic".equals(this.prefs.getString("keyboard_script", "latin"));
                    if (this.isCapsLocked) {
                        this.isCapsLocked = false;
                        this.isCaps = false;
                        str = arShift ? "Huruf Arab utama" : (z ? "Lowercase" : "Huruf kecil");
                    } else if (this.isCaps && (time - this.lastShiftTime) < 500L) {
                        this.isCapsLocked = true;
                        this.isCaps = true;
                        str = arShift ? "Kunci Shift Arab & Tajwid aktif" : (z ? "Caps lock active" : "Caps Lock aktif");
                    } else {
                        this.isCapsLocked = false;
                        this.isCaps = !this.isCaps;
                        if (this.isCaps) {
                            str = arShift ? "Shift Arab aktif: Hamzah, Waqaf & Tajwid" : (z ? "Uppercase shift active" : "Huruf besar shift aktif");
                        } else {
                            str = arShift ? "Huruf Arab utama" : (z ? "Lowercase" : "Huruf kecil");
                        }
                    }
                    this.lastShiftTime = time;
                    announceDirect(str);
                    if (arShift) reloadLayoutConfig();
                    else invalidate();
                    return;
                case KEY_DEL:
                    if (!this.isCapsLocked && this.isCaps) {
                        this.isCaps = false;
                        if ("arabic".equals(this.prefs.getString("keyboard_script", "latin"))) {
                            reloadLayoutConfig();
                        } else {
                            invalidate();
                        }
                    }
                    this.listener.onDelete();
                    announceTypedForScreenReader(keyDef);
                    return;
                case KEY_SPACE:
                    if (this.isCaps && !this.isCapsLocked) {
                        this.isCaps = false;
                        if ("arabic".equals(this.prefs.getString("keyboard_script", "latin"))) {
                            reloadLayoutConfig();
                        } else {
                            invalidate();
                        }
                    }
                    this.listener.onCommitText(" ");
                    announceTypedForScreenReader(keyDef);
                    if (this.currentMode == 1 || this.currentMode == 2 || this.currentMode == 5) {
                        this.currentMode = 0;
                        reloadLayoutConfig();
                    }
                    return;
                case KEY_ENTER:
                    this.listener.onEnter();
                    announceTypedForScreenReader(keyDef);
                    return;
                case KEY_TO_SYM1:
                    this.currentMode = 1;
                    reloadLayoutConfig();
                    announceDirect(z ? "Symbols and numbers board" : "Papan simbol dan angka");
                    return;
                case KEY_TO_SYM2:
                    this.currentMode = 2;
                    reloadLayoutConfig();
                    announceDirect(z ? "Other symbols board" : "Papan simbol lainnya");
                    return;
                case KEY_TO_ABC:
                    this.currentMode = 0;
                    reloadLayoutConfig();
                    announceDirect(z ? "Letter board" : "Papan huruf");
                    return;
                case 10:
                    this.currentMode = 5;
                    this.emojiPage = 0;
                    reloadLayoutConfig();
                    if ("arabic".equals(this.prefs.getString("keyboard_script", "latin"))) {
                        announceDirect(KeyboardData.ARABIC_PAGE_NAMES[0]);
                    } else {
                        announceDirect(z ? "Special symbols board" : "Papan simbol khusus");
                    }
                    return;
                case KEY_TO_EMOJI:
                    switchToEmoji();
                    return;
                case KEY_EMOJI_NEXT: {
                    boolean arScr = "arabic".equals(this.prefs.getString("keyboard_script", "latin"));
                    int maxPg = (this.currentMode == 5 && arScr)
                        ? KeyboardData.ALL_ARABIC_PAGES.length
                        : KeyboardData.ALL_EMOJI_PAGES.length;
                    if (this.emojiPage < maxPg - 1) {
                        this.emojiPage++;
                        reloadLayoutConfig();
                        if (this.currentMode == 5 && arScr) {
                            String nm = KeyboardData.ARABIC_PAGE_NAMES[this.emojiPage];
                            announceDirect(nm);
                        } else {
                            announceDirect(z ? "Emoji page " + (this.emojiPage + 1) + " of " + maxPg : "Halaman emoji " + (this.emojiPage + 1) + " dari " + maxPg);
                        }
                    }
                    return;
                }
                case KEY_EMOJI_PREV: {
                    if (this.emojiPage > 0) {
                        this.emojiPage--;
                        reloadLayoutConfig();
                        boolean arScr2 = "arabic".equals(this.prefs.getString("keyboard_script", "latin"));
                        if (this.currentMode == 5 && arScr2) {
                            String nm = KeyboardData.ARABIC_PAGE_NAMES[this.emojiPage];
                            announceDirect(nm);
                        } else {
                            int maxPg2 = KeyboardData.ALL_EMOJI_PAGES.length;
                            announceDirect(z ? "Emoji page " + (this.emojiPage + 1) + " of " + maxPg2 : "Halaman emoji " + (this.emojiPage + 1) + " dari " + maxPg2);
                        }
                    }
                    return;
                }
                default:
                    return;
            }
        }
    }

    private void vibrate(final boolean isKeyRelease) {
        if (!this.hasVibratorCached || this.vibrator == null || this.prefs == null) return;
        if (!(this.prefs.getBoolean("vibe_enabled", true) || this.prefs.getBoolean("vibrate_on_keypress", true))) {
            return;
        }
        final int dur = this.prefs.getInt("vibe_duration", 20);
        if (dur <= 0) return;
        final int ms = isKeyRelease ? dur : Math.max(5, dur / 2);
        this.hapticExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        vibrator.vibrate(ms);
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private String lastAnnouncedText = null;
    private long lastAnnouncedAt = 0L;

    /**
     * Pengumuman lewat PEMBACA LAYAR SISTEM (TalkBack, JOS, Select to Speak, dll).
     */
    public void announceDirect(String str) {
        if (str == null || str.length() == 0) return;
        if (this.accessibilityManager == null || !this.accessibilityManager.isEnabled()) return;
        long now = System.currentTimeMillis();
        if (str.equals(this.lastAnnouncedText) && (now - this.lastAnnouncedAt) < 120L) {
            return;
        }
        this.lastAnnouncedText = str;
        this.lastAnnouncedAt = now;
        try {
            announceForAccessibility(str);
        } catch (Throwable ignored) {
            try {
                AccessibilityEvent obtain = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
                obtain.setPackageName(getContext().getPackageName());
                obtain.setClassName(getClass().getName());
                obtain.setSource(this);
                obtain.getText().clear();
                obtain.getText().add(str);
                obtain.setContentDescription(str);
                if (getParent() != null && getParent().requestSendAccessibilityEvent(this, obtain)) {
                    return;
                }
                this.accessibilityManager.sendAccessibilityEvent(obtain);
            } catch (Throwable ignored2) {}
        }
    }

    private void sendVirtualAccessibilityEvent(int i, int i2) {
        if (this.accessibilityManager == null || !this.accessibilityManager.isEnabled()) return;
        if (i < 0 || i >= this.allKeys.size()) return;
        try {
            boolean z = this.prefs.getBoolean("interface_english", false);
            AccessibilityEvent obtain = AccessibilityEvent.obtain(i2);
            obtain.setPackageName(getContext().getPackageName());
            obtain.setClassName(View.class.getName());
            obtain.setSource(this, i);
            String spokenText = this.allKeys.get(i).getSpokenText(this.isCaps, z);
            if (spokenText == null) spokenText = "";
            obtain.setContentDescription(spokenText);
            obtain.getText().clear();
            obtain.getText().add(spokenText);
            boolean sent = false;
            try {
                if (getParent() != null) {
                    sent = getParent().requestSendAccessibilityEvent(this, obtain);
                }
            } catch (Throwable ignored) {}
            if (!sent) {
                try {
                    this.accessibilityManager.sendAccessibilityEvent(obtain);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Feedback aksesibilitas per tombol.
     * - Mode Angkat Jari: hanya TYPE_ANNOUNCEMENT → kursor SR tetap di kotak edit.
     * - Mode Ketuk Ganda: kirim FOCUS ke tombol virtual agar TalkBack/JOS bisa
     *   double-tap (klik buatan) ke tombol yang sama, bukan ke field "Ketik pesan".
     * Pengumuman diulang juga saat karakter BENAR-BENAR terketik (lihat executeKey),
     * biar di HP yang kadang drop event DOWN tetap 100% kebaca.
     */
    private void announceKeyForScreenReader(int keyIndex, boolean english) {
        if (keyIndex < 0 || keyIndex >= this.allKeys.size()) return;
        if (this.accessibilityManager == null || !this.accessibilityManager.isEnabled()) return;
        KeyDef keyDef = this.allKeys.get(keyIndex);
        String label = keyDef.getSpokenText(this.isCaps, english);
        if (label == null || label.length() == 0) return;
        if (requiresDoubleTap(keyDef)) {
            // Perlu fokus virtual supaya ketuk ganda screen reader kena tombol, bukan edit box.
            sendVirtualAccessibilityEvent(keyIndex, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
            sendVirtualAccessibilityEvent(keyIndex, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
            announceDirect(label);
        } else {
            // Angkat Jari: jangan curi fokus dari kotak edit.
            announceDirect(label);
        }
    }

    /**
     * Panggil setelah karakter/spasi/hapus BENAR-BENAR masuk ke InputConnection.
     * Ini yang bikin multi-jari (4 jari dll) & device rewel tetap ngomong kalau terketik,
     * dan diam kalau jari nggak mendarat di tombol.
     */
    private void announceTypedForScreenReader(KeyDef keyDef) {
        if (keyDef == null) return;
        if (this.accessibilityManager == null || !this.accessibilityManager.isEnabled()) return;
        boolean exploring = false;
        try {
            exploring = this.accessibilityManager.isTouchExplorationEnabled();
        } catch (Throwable ignored) {}
        if (!exploring && !isDoubleTapMode()) {
            // Mode awas / non-explore dengan profil cepat (0ms): hindari spam IPC a11y per ketikan
            if (this.prefs != null && this.prefs.getInt("touch_response_ms", 0) == 0) {
                return;
            }
            long now = System.currentTimeMillis();
            if ((now - this.lastTypedAnnounceAt) < 100L) {
                return;
            }
            this.lastTypedAnnounceAt = now;
        }
        boolean eng = this.prefs != null && this.prefs.getBoolean("interface_english", false);
        String label = keyDef.getSpokenText(this.isCaps, eng);
        if (label == null || label.length() == 0) return;
        if (keyDef.type == KEY_CHAR || keyDef.type == KEY_SPACE || keyDef.type == KEY_DEL
                || keyDef.type == KEY_ENTER) {
            announceDirect(label);
        }
    }

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        if (this.accessibilityManager == null || !this.accessibilityManager.isEnabled()) {
            return null;
        }
        return this.accessibilityProvider;
    }

    private class GboardAccessibilityProvider extends AccessibilityNodeProvider {
        private GboardAccessibilityProvider() {
        }

        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int i) {
            boolean z = CustomKeyboardView.this.prefs.getBoolean("interface_english", false);
            if (i == -1) {
                AccessibilityNodeInfo obtain = AccessibilityNodeInfo.obtain(CustomKeyboardView.this);
                CustomKeyboardView.this.onInitializeAccessibilityNodeInfo(obtain);
                obtain.setClassName(View.class.getName());
                obtain.setClickable(false);
                // Tombol virtual tidak ditambahkan sebagai anak untuk navigasi linear.
                // Eksplorasi sentuh tetap dilayani melalui dispatchHoverEvent/provider.
                return obtain;
            } else if (i >= 0 && i < CustomKeyboardView.this.allKeys.size()) {
                KeyDef keyDef = CustomKeyboardView.this.allKeys.get(i);
                AccessibilityNodeInfo obtain2 = AccessibilityNodeInfo.obtain(CustomKeyboardView.this, i);
                obtain2.setPackageName(CustomKeyboardView.this.getContext().getPackageName());
                obtain2.setSource(CustomKeyboardView.this, i);
                obtain2.setParent(CustomKeyboardView.this);
                obtain2.setClassName("android.widget.Button");
                // Clickable = true agar TalkBack/JOS double-tap mengirim ACTION_CLICK ke tombol virtual.
                obtain2.setClickable(true);
                obtain2.setFocusable(true);
                obtain2.setEnabled(true);
                obtain2.setVisibleToUser(true);
                String spokenText = keyDef.getSpokenText(CustomKeyboardView.this.isCaps, z);
                obtain2.setText(spokenText);
                obtain2.setContentDescription(spokenText);
                if (CustomKeyboardView.this.focusedVirtualKeyId == i) {
                    obtain2.addAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
                } else {
                    obtain2.addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                }
                obtain2.addAction(AccessibilityNodeInfo.ACTION_CLICK);
                Rect rect = new Rect((int) keyDef.bounds.left, (int) keyDef.bounds.top, (int) keyDef.bounds.right, (int) keyDef.bounds.bottom);
                obtain2.setBoundsInParent(rect);
                int[] iArr = new int[2];
                CustomKeyboardView.this.getLocationOnScreen(iArr);
                obtain2.setBoundsInScreen(new Rect(rect.left + iArr[0], rect.top + iArr[1], iArr[0] + rect.right, iArr[1] + rect.bottom));
                return obtain2;
            } else {
                return null;
            }
        }

        @Override
        public boolean performAction(int i, int i2, Bundle bundle) {
            if (i == -1) {
                return CustomKeyboardView.this.performAccessibilityAction(i2, bundle);
            }
            if (i2 == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                if (CustomKeyboardView.this.focusedVirtualKeyId != i) {
                    CustomKeyboardView.this.focusedVirtualKeyId = i;
                    CustomKeyboardView.this.sendVirtualAccessibilityEvent(i, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                    return true;
                }
            } else if (i2 == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                if (CustomKeyboardView.this.focusedVirtualKeyId == i) {
                    CustomKeyboardView.this.focusedVirtualKeyId = -1;
                    CustomKeyboardView.this.sendVirtualAccessibilityEvent(i, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                    return true;
                }
            } else if (i2 == AccessibilityNodeInfo.ACTION_CLICK) {
                // Klik buatan dari TalkBack/JOS (ketuk ganda). Prioritas: id virtual → fokus → terakhir disentuh.
                int targetKey = -1;
                if (i >= 0 && i < CustomKeyboardView.this.allKeys.size()) {
                    targetKey = i;
                } else if (CustomKeyboardView.this.focusedVirtualKeyId >= 0
                        && CustomKeyboardView.this.focusedVirtualKeyId < CustomKeyboardView.this.allKeys.size()) {
                    targetKey = CustomKeyboardView.this.focusedVirtualKeyId;
                } else if (CustomKeyboardView.this.lastTappedKeyIndex >= 0
                        && CustomKeyboardView.this.lastTappedKeyIndex < CustomKeyboardView.this.allKeys.size()) {
                    targetKey = CustomKeyboardView.this.lastTappedKeyIndex;
                } else if (CustomKeyboardView.this.hoveredVirtualKeyId >= 0
                        && CustomKeyboardView.this.hoveredVirtualKeyId < CustomKeyboardView.this.allKeys.size()) {
                    targetKey = CustomKeyboardView.this.hoveredVirtualKeyId;
                }
                if (targetKey >= 0 && targetKey < CustomKeyboardView.this.allKeys.size()) {
                    CustomKeyboardView.this.sendVirtualAccessibilityEvent(targetKey, AccessibilityEvent.TYPE_VIEW_CLICKED);
                    CustomKeyboardView.this.safeExecuteKey(CustomKeyboardView.this.allKeys.get(targetKey));
                    CustomKeyboardView.this.vibrate(true);
                    CustomKeyboardView.this.lastTappedKeyIndex = -1;
                    CustomKeyboardView.this.lastTapTime = 0L;
                    return true;
                }
            }
            return false;
        }
    }
}
