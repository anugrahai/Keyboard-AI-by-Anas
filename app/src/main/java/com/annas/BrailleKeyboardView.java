package com.annas;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class BrailleKeyboardView extends View {

    public interface OnBrailleActionListener {
        void onCommitText(String text);
        void onSpace();
        void onDelete();
        void onDeleteWord();
        void onEnter();
        /** Kirim pesan (IME_ACTION_SEND / Enter di WhatsApp dll). Dipicu gesture kirim Braille. */
        void onSend();
        void onHideKeyboard();
        void onVoiceInput();
        void onSwitchToQwerty();
        void onSpeakTTS(String speechText, boolean interrupt);
        void onNavigateLeft();
        void onNavigateRight();
        void onBrailleDeleteAdaptive(int granularity);
        void onBrailleNavigateAdaptive(int granularity, boolean forward);
        void onOpenSettings();
        /** Salin teks (biasanya 3 jari usap kanan). */
        void onCopy();
        /** Tempel dari papan klip (biasanya 3 jari usap kiri). */
        void onPaste();
        /** Bacakan kata sebelum kursor saat spasi dimasukkan pada mode TTS kata. */
        void onReadWordBeforeCursor();
        /** Hapus seluruh teks di field (mode rotor + konfirmasi usap atas 1 jari). */
        void onClearAll();
    }

    // Rotor 3 jari: Karakter → Kata → Baris → Paragraf → Papan klip → Salin → Tempel → Bersihkan semua → TTS…
    public static final int GRANULARITY_CHARACTER = 0;
    public static final int GRANULARITY_WORD = 1;
    public static final int GRANULARITY_LINE = 2;
    public static final int GRANULARITY_PARAGRAPH = 3;
    public static final int GRANULARITY_CLIPBOARD = 4; // kiri/kanan pilih, klik = tempel
    public static final int GRANULARITY_COPY = 5;      // 1 jari kanan = salin
    public static final int GRANULARITY_PASTE = 6;     // 1 jari kiri = tempel
    public static final int SPEECH_OFF = 7;
    public static final int SPEECH_CHARACTER = 8;
    public static final int SPEECH_WORD_ON_SPACE = 9;
    public static final int SPEECH_CHARACTER_AND_WORD = 10;
    public static final int GRANULARITY_CLEAR_ALL = 11; // 3 jari rotor → lalu 1 jari atas 2x (konfirmasi)

    private int currentGranularity = GRANULARITY_CHARACTER;
    private static final String[] GRANULARITY_NAMES = {
        "Karakter",
        "Kata",
        "Baris",
        "Paragraf",
        "Papan klip",
        "Salin",
        "Tempel",
        "TTS mati",
        "TTS per karakter",
        "TTS per kata saat spasi",
        "TTS karakter + kata",
        "Bersihkan semua teks"
    };

    private OnBrailleActionListener listener;
    private SharedPreferences prefs;
    private Vibrator vibrator;
    private SoundPool soundPool;
    private int soundKetikId = -1;
    private boolean isSoundLoaded = false;

    // Mode status
    private boolean isEmojiMode = false;
    private boolean isNumberMode = false;
    private boolean isCaps = false;
    private boolean isCapsLocked = false;
    private long lastCapitalSignTime = 0L;
    private int emojiPage = 0;
    private int hoveredEmojiIndex = 0;

    // Variabel Gesture & Sentuhan Khusus Mode Emoji (Standar TalkBack)
    private float emojiStartX = 0f;
    private float emojiStartY = 0f;
    private long emojiStartTime = 0L;
    private int emojiMaxPointers = 1;
    private boolean emojiIsExploring = false;
    private long lastEmojiTapTime = 0L;

    // Multi-cell chord tracking (misal: 2-5 lalu 2-5 -> '=')
    private int lastChordMask = 0;
    private long lastChordTime = 0L;

    // Chording bitmask
    private int accumulatedChordMask = 0;

    // Koordinat 6 Titik Braille
    // Baris Atas: Titik 1 (Kiri Atas), Titik 2 (Tengah Atas), Titik 3 (Kanan Atas)
    // Baris Bawah: Titik 4 (Kiri Bawah), Titik 5 (Tengah Bawah), Titik 6 (Kanan Bawah)
    private float[] dotX = new float[6];
    private float[] dotY = new float[6];
    private boolean[] dotPressed = new boolean[6];
    private float dotRadius = 40f;

    // Paints
    private Paint bgPaint;
    private Paint dotBgPaint;
    private Paint dotRingPaint;
    private Paint dotActivePaint;
    private Paint dotTextPaint;
    private Paint headerTextPaint;
    private Paint pillBtnPaint;
    private Paint pillTextPaint;
    private Paint emojiGridPaint;
    private Paint emojiHighlightPaint;
    private Paint emojiTextPaint;
    private Paint emojiLabelPaint;
    private Paint dividerPaint;

    // Tombol di bar bawah layar (tidak mengganggu area pengetikan 6 titik)
    private RectF btnQwertyRect = new RectF();
    private RectF btnEmojiRect = new RectF();
    private RectF btnCalibRect = new RectF();
    private RectF btnSettingsRect = new RectF();
    private RectF btnSkipCalibRect = new RectF();

    // Mode Kalibrasi Jari 6 Titik (Manual Tap 1..6 atau Tahan 6 Jari 5 Detik Kapanpun)
    private boolean isCalibrating = false;
    private int calibrationStep = 0;
    private final List<Integer> holdPointerOrder = new ArrayList<>();
    private final long[] pointerDownTime = new long[10];
    private final Handler holdCalibHandler = new Handler(Looper.getMainLooper());
    private Runnable holdCalibRunnable;
    private Runnable holdCalibTickRunnable;
    private int holdCalibSecondsLeft = 0;
    private boolean isHoldCalibPending = false;
    private boolean holdCalibCompleted = false;

    // Pelacakan Multi-Touch Independen per-Pointer ID
    private float[] pointerDownX = new float[10];
    private float[] pointerDownY = new float[10];
    private float[] pointerCurrX = new float[10];
    private float[] pointerCurrY = new float[10];
    private boolean[] pointerActive = new boolean[10];
    private boolean isAnyPointerSwiping = false;
    private float gestureDeltaX = 0f;
    private float gestureDeltaY = 0f;
    private int maxPointerCountInStroke = 1;
    private float touchSlop = 55f;

    // Bersihkan semua teks: pilih mode rotor "Bersihkan semua teks" (3 jari atas/bawah),
    // lalu 1 jari usap atas sekali = minta konfirmasi, usap atas sekali lagi = eksekusi.
    private boolean clearAllConfirmPending = false;
    private long clearAllConfirmUntilMs = 0L;
    private static final long CLEAR_ALL_CONFIRM_WINDOW_MS = 5000L;

    // Handler untuk 3 Detik Input Suara (Tahan 1 jari diam)
    private Handler voiceHandler = new Handler(Looper.getMainLooper());
    private Runnable voiceRunnable;
    private boolean isVoiceTriggered = false;

    // Handler untuk Kirim lewat tahan 2/3 jari (durasi diatur di pengaturan Braille)
    private Handler sendHoldHandler = new Handler(Looper.getMainLooper());
    private Runnable sendHoldRunnable;
    private boolean isSendHoldTriggered = false;
    private int sendHoldActivePointers = 0;
    private static final long HOLD_CALIBRATION_DURATION_MS = 5000L;

    public BrailleKeyboardView(Context context, OnBrailleActionListener listener, SharedPreferences prefs, Vibrator vib) {
        super(context);
        this.listener = listener;
        this.prefs = prefs;
        this.vibrator = vib;
        init();
    }

    public BrailleKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // ============================================================
        // ANTI-TABRAKAN SCREEN READER (TalkBack / JOS / Jieshuo / dll)
        // Saat mode Braille aktif, area ini TIDAK dieksplorasi sistem.
        // Touch exploration (hover) dikonversi jadi sentuhan nyata
        // lewat dispatchHoverEvent → onTouchEvent, agar chord 6 titik
        // tetap bisa dipakai tanpa mematikan screen reader.
        // Feedback ketikan: TTS internal (BrailleTTSManager).
        // ============================================================
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(true);
        setLongClickable(false);
        setContentDescription(null);
        setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override
            public void sendAccessibilityEvent(View host, int eventType) {
                // Blok semua event aksesibilitas dari view Braille
                // agar JOS/TalkBack tidak "raba" / announce area ini.
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                return false;
            }

            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                // Node kosong / tidak interaktif bagi screen reader
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (info != null) {
                    info.setClickable(false);
                    info.setLongClickable(false);
                    info.setFocusable(false);
                    info.setVisibleToUser(false);
                    info.setContentDescription(null);
                    info.setClassName(View.class.getName());
                }
            }
        });

        touchSlop = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 55, getResources().getDisplayMetrics());

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.parseColor("#15161A"));

        dotBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotBgPaint.setColor(Color.parseColor("#22252E"));
        dotBgPaint.setStyle(Paint.Style.FILL);

        dotRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotRingPaint.setColor(Color.parseColor("#373C4B"));
        dotRingPaint.setStyle(Paint.Style.STROKE);
        dotRingPaint.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3.5f, getResources().getDisplayMetrics()));

        dotActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotActivePaint.setColor(Color.parseColor("#00E5FF")); // Neon Cyan
        dotActivePaint.setStyle(Paint.Style.FILL);

        dotTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotTextPaint.setColor(Color.WHITE);
        dotTextPaint.setTextAlign(Paint.Align.CENTER);
        dotTextPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

        headerTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headerTextPaint.setColor(Color.parseColor("#E0E3EA"));
        headerTextPaint.setTextAlign(Paint.Align.LEFT);
        headerTextPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

        pillBtnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pillBtnPaint.setColor(Color.parseColor("#2C303E"));

        pillTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pillTextPaint.setColor(Color.WHITE);
        pillTextPaint.setTextAlign(Paint.Align.CENTER);
        pillTextPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

        emojiGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        emojiGridPaint.setColor(Color.parseColor("#22252E"));
        emojiGridPaint.setStyle(Paint.Style.FILL);

        emojiHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        emojiHighlightPaint.setColor(Color.parseColor("#00E5FF"));
        emojiHighlightPaint.setStyle(Paint.Style.STROKE);
        emojiHighlightPaint.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, getResources().getDisplayMetrics()));

        emojiTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        emojiTextPaint.setTextAlign(Paint.Align.CENTER);

        emojiLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        emojiLabelPaint.setColor(Color.parseColor("#8E95A8"));
        emojiLabelPaint.setTextAlign(Paint.Align.CENTER);
        emojiLabelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));

        dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dividerPaint.setColor(Color.parseColor("#1F232D"));
        dividerPaint.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, getResources().getDisplayMetrics()));

        initSoundPool();

        voiceRunnable = new Runnable() {
            @Override
            public void run() {
                isVoiceTriggered = true;
                vibrate(150);
                speakTTS("Mendengarkan suara...", true);
                if (listener != null) {
                    listener.onVoiceInput();
                }
            }
        };

        sendHoldRunnable = new Runnable() {
            @Override
            public void run() {
                if (!prefs.getBoolean("braille_send_longpress_enabled", true)) return;
                isSendHoldTriggered = true;
                vibrate(120);
                speakTTS("Mengirim", true);
                if (listener != null) {
                    try { listener.onSend(); } catch (Throwable ignored) {}
                }
            }
        };

        holdCalibRunnable = new Runnable() {
            @Override
            public void run() {
                completeSixFingerHoldCalibration();
            }
        };

        holdCalibTickRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isHoldCalibPending) return;
                holdCalibSecondsLeft--;
                if (holdCalibSecondsLeft > 0) {
                    vibrate(18);
                    holdCalibHandler.postDelayed(this, 1000L);
                }
            }
        };
    }

    public boolean isCalibrating() {
        return isCalibrating;
    }

    private void startHoldCalibrationCountdown() {
        cancelHoldCalibration(false);
        isHoldCalibPending = true;
        holdCalibSecondsLeft = 5;
        vibrate(45);
        speakTTS("Enam jari terdeteksi, tahan lima detik untuk kalibrasi otomatis", true);
        holdCalibHandler.postDelayed(holdCalibRunnable, HOLD_CALIBRATION_DURATION_MS);
        holdCalibHandler.postDelayed(holdCalibTickRunnable, 1000L);
        invalidate();
    }

    private void cancelHoldCalibration(boolean clearOrder) {
        if (holdCalibRunnable != null) holdCalibHandler.removeCallbacks(holdCalibRunnable);
        if (holdCalibTickRunnable != null) holdCalibHandler.removeCallbacks(holdCalibTickRunnable);
        isHoldCalibPending = false;
        holdCalibSecondsLeft = 0;
        if (clearOrder) holdPointerOrder.clear();
    }

    private void completeSixFingerHoldCalibration() {
        isHoldCalibPending = false;
        if (holdCalibTickRunnable != null) holdCalibHandler.removeCallbacks(holdCalibTickRunnable);

        List<PointF> orderedPoints = new ArrayList<>();
        long earliestMs = Long.MAX_VALUE;
        long latestMs = 0L;
        for (int i = 0; i < holdPointerOrder.size(); i++) {
            int pId = holdPointerOrder.get(i);
            if (pId >= 0 && pId < 10 && pointerActive[pId]) {
                orderedPoints.add(new PointF(pointerCurrX[pId], pointerCurrY[pId]));
                long t = pointerDownTime[pId];
                if (t > 0L) {
                    if (t < earliestMs) earliestMs = t;
                    if (t > latestMs) latestMs = t;
                }
            }
            if (orderedPoints.size() == 6) break;
        }
        if (orderedPoints.size() < 6) {
            for (int id = 0; id < 10 && orderedPoints.size() < 6; id++) {
                if (pointerActive[id] && !holdPointerOrder.contains(id)) {
                    orderedPoints.add(new PointF(pointerCurrX[id], pointerCurrY[id]));
                }
            }
        }
        if (orderedPoints.size() < 6) return;

        // Jika jari ditaruh berurutan satu per satu (jari 1 lalu 2..6 >= 350ms), ikuti urutan turun masing-masing!
        // Jika ditaruh serentak (< 350ms), urutkan spasial baris atas (1,2,3) dan bawah (4,5,6).
        boolean sequentialPlacement = (earliestMs < Long.MAX_VALUE && (latestMs - earliestMs) >= 350L);
        if (sequentialPlacement) {
            for (int i = 0; i < 6; i++) {
                dotX[i] = orderedPoints.get(i).x;
                dotY[i] = orderedPoints.get(i).y;
            }
        } else {
            Collections.sort(orderedPoints, new Comparator<PointF>() {
                @Override public int compare(PointF p1, PointF p2) {
                    return Float.compare(p1.y, p2.y);
                }
            });
            List<PointF> topRow = new ArrayList<>(orderedPoints.subList(0, 3));
            List<PointF> bottomRow = new ArrayList<>(orderedPoints.subList(3, 6));
            Collections.sort(topRow, new Comparator<PointF>() {
                @Override public int compare(PointF p1, PointF p2) {
                    return Float.compare(p1.x, p2.x);
                }
            });
            Collections.sort(bottomRow, new Comparator<PointF>() {
                @Override public int compare(PointF p1, PointF p2) {
                    return Float.compare(p1.x, p2.x);
                }
            });
            for (int i = 0; i < 3; i++) {
                dotX[i] = topRow.get(i).x;
                dotY[i] = topRow.get(i).y;
                dotX[i + 3] = bottomRow.get(i).x;
                dotY[i + 3] = bottomRow.get(i).y;
            }
        }

        saveCalibration();
        isCalibrating = false;
        holdCalibCompleted = true;
        clearDotPressState();
        vibrate(140);
        playAudio();
        speakTTS("Kalibrasi enam jari selesai dan tersimpan aman.", true);
        invalidate();
    }

    private void initSoundPool() {
        try {
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(6)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .build();

            soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                @Override
                public void onLoadComplete(SoundPool sp, int sampleId, int status) {
                    if (status == 0) isSoundLoaded = true;
                }
            });

            reloadSound();
        } catch (Throwable ignored) {}
    }

    public void reloadSound() {
        if (soundPool == null) return;
        isSoundLoaded = false;
        soundKetikId = -1;
        try {
            String effectKey = prefs != null ? prefs.getString("sound_effect_key", "key2") : "key2";
            if ("custom".equals(effectKey)) {
                File f = new File(getContext().getFilesDir(), "custom_ketik.mp3");
                if (f.exists() && f.canRead()) {
                    soundKetikId = soundPool.load(f.getAbsolutePath(), 1);
                    return;
                }
            }
            String[] files = null;
            try { files = getContext().getAssets().list("typingsound/" + effectKey); } catch (Exception e) {}
            if (files == null || files.length == 0) {
                effectKey = "key2";
                try { files = getContext().getAssets().list("typingsound/" + effectKey); } catch (Exception e) {}
            }
            String pick = null;
            if (files != null) {
                for (String fn : files) {
                    String low = fn.toLowerCase();
                    if (low.contains("typing") || low.startsWith("type") || low.contains("letter") || (low.startsWith("s") && low.endsWith(".wav"))) {
                        pick = fn; break;
                    }
                }
                if (pick == null && files.length > 0) pick = files[0];
            }
            if (pick != null) {
                java.io.InputStream is = getContext().getAssets().open("typingsound/" + effectKey + "/" + pick);
                File cache = new File(getContext().getCacheDir(), "braille_sfx_" + effectKey + "_" + pick.replaceAll("[^a-zA-Z0-9._-]", "_"));
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

    private void playAudio() {
        if (prefs != null && !prefs.getBoolean("sound_ketik_enabled", true)) return;
        if (soundPool != null && soundKetikId != -1 && isSoundLoaded) {
            soundPool.play(soundKetikId, 1.0f, 1.0f, 0, 0, 1.0f);
        }
    }

    private void vibrate(int ms) {
        if (vibrator != null && prefs != null && prefs.getBoolean("vib_enabled", true)) {
            vibrator.vibrate(ms);
        }
    }

    /** True jika TalkBack / screen reader dengan Touch Exploration sedang aktif. */
    private boolean isSystemScreenReaderExploring() {
        try {
            AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
            return am != null && am.isEnabled() && am.isTouchExplorationEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private void speakTTS(String text, boolean interrupt) {
        if (listener != null) {
            listener.onSpeakTTS(text, interrupt);
        }
    }

    public static int getRealScreenWidth(Context context) {
        android.view.WindowManager wm = (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return context.getResources().getDisplayMetrics().widthPixels;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return wm.getMaximumWindowMetrics().getBounds().width();
        } else {
            android.graphics.Point size = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(size);
            return size.x;
        }
    }

    public static int getRealScreenHeight(Context context) {
        android.view.WindowManager wm = (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return context.getResources().getDisplayMetrics().heightPixels;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return wm.getMaximumWindowMetrics().getBounds().height();
        } else {
            android.graphics.Point size = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(size);
            return size.y;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int screenW = getRealScreenWidth(getContext());
        int screenH = getRealScreenHeight(getContext());
        int specW = MeasureSpec.getSize(widthMeasureSpec);
        int finalW = (specW > 0) ? specW : screenW;
        setMeasuredDimension(finalW, screenH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        calculateDotPositions(w, h);
    }

    private void calculateDotPositions(int w, int h) {
        boolean isCalibrated = prefs != null && prefs.getBoolean("braille_calibrated", false);
        if (isCalibrated) {
            boolean valid = true;
            for (int i = 0; i < 6; i++) {
                float x = prefs.getFloat("braille_dot_x_" + i, -1f);
                float y = prefs.getFloat("braille_dot_y_" + i, -1f);
                if (x < 0 || y < 0) { valid = false; break; }
            }
            if (valid) {
                for (int i = 0; i < 6; i++) {
                    dotX[i] = prefs.getFloat("braille_dot_x_" + i, (i % 3 == 0 ? w * 0.17f : (i % 3 == 1 ? w * 0.50f : w * 0.83f)));
                    dotY[i] = prefs.getFloat("braille_dot_y_" + i, (i < 3 ? h * 0.25f : h * 0.55f));
                }
            } else {
                isCalibrated = false;
                if (prefs != null) prefs.edit().remove("braille_calibrated").apply();
            }
        }

        if (!isCalibrated) {
            // SUSUNAN 2 BARIS x 3 KOLOM SESUAI INSTRUKSI SPESIFIK USER:
            // Baris Atas:
            // 1 di kiri atas
            // 2 di tengah
            // 3 di kanan paling ujung
            // Baris Bawah:
            // 4 di kiri bawah (di bawah 1)
            // 5 di tengah
            // 6 di paling kanan
            dotX[0] = w * 0.17f; // Titik 1 (Kiri Atas)
            dotX[1] = w * 0.50f; // Titik 2 (Tengah Atas)
            dotX[2] = w * 0.83f; // Titik 3 (Kanan Paling Ujung Atas)

            dotX[3] = w * 0.17f; // Titik 4 (Kiri Bawah)
            dotX[4] = w * 0.50f; // Titik 5 (Tengah Bawah)
            dotX[5] = w * 0.83f; // Titik 6 (Kanan Paling Ujung Bawah)

            dotY[0] = h * 0.25f; // Titik 1
            dotY[1] = h * 0.25f; // Titik 2
            dotY[2] = h * 0.25f; // Titik 3

            dotY[3] = h * 0.55f; // Titik 4
            dotY[4] = h * 0.55f; // Titik 5
            dotY[5] = h * 0.55f; // Titik 6
        }

        dotRadius = Math.min(w, h) * 0.080f;
        dotTextPaint.setTextSize(dotRadius * 0.95f);
        headerTextPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13, getResources().getDisplayMetrics()));
        pillTextPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, getResources().getDisplayMetrics()));
        // Ukuran emoji disesuaikan biar muat 10 kolom (sama urutan & isi kayak keyboard biasa)
        emojiTextPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20, getResources().getDisplayMetrics()));

        // Tombol pill di bar paling bawah layar (tinggi 32dp di area bawah yang tidak mengganggu titik Braille)
        float btnW = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 58, getResources().getDisplayMetrics());
        float btnH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32, getResources().getDisplayMetrics());
        float bottomM = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics());
        float rightM = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());

        float bottomY = h - bottomM - btnH;
        btnEmojiRect.set(w - rightM - btnW, bottomY, w - rightM, bottomY + btnH);
        btnCalibRect.set(btnEmojiRect.left - 5 - btnW, bottomY, btnEmojiRect.left - 5, bottomY + btnH);
        btnSettingsRect.set(btnCalibRect.left - 5 - btnW, bottomY, btnCalibRect.left - 5, bottomY + btnH);
        btnQwertyRect.setEmpty();

        float skipW = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 145, getResources().getDisplayMetrics());
        btnSkipCalibRect.set(w - rightM - skipW, bottomY, w - rightM, bottomY + btnH);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Pastikan flag anti-screen-reader tetap aktif tiap kali view menempel
        // (WhatsApp, YouTube, PIN, password, game — semua sama)
        try {
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        } catch (Throwable ignored) {
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(true);
        // Reset state hover/chord supaya sentuhan PERTAMA langsung dipakai
        // (tanpa harus "sentuh dulu sekali" atau tangguhkan TalkBack)
        hoverChordActive = false;
        hoverWasSwipe = false;
        isAnyPointerSwiping = false;
        maxPointerCountInStroke = 0;
        isVoiceTriggered = false;
        try { clearDotPressState(); } catch (Throwable ignored) {}
        checkAutoCalibrationPrompt();
    }

    public void resetShiftState() {
        isCaps = false;
        isCapsLocked = false;
        isNumberMode = false;
        invalidate();
    }

    public void checkAutoCalibrationPrompt() {
        if (prefs != null && !prefs.getBoolean("braille_calibrated", false)) {
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (prefs != null && !prefs.getBoolean("braille_calibrated", false) && !isCalibrating) {
                        startCalibration();
                    }
                }
            }, 350);
        }
    }

    public void startCalibration() {
        isCalibrating = true;
        calibrationStep = 0;
        cancelHoldCalibration(true);
        vibrate(60);
        speakTTS("Mode kalibrasi aktif. Sentuh titik 1 sampai 6 satu per satu, atau taruh jari 1 sampai 6 lalu tahan lima detik. Ketuk Gunakan Standar di kanan bawah untuk melewati.", true);
        invalidate();
    }

    public void useDefaultCalibration() {
        isCalibrating = false;
        calculateDotPositions(getWidth(), getHeight());
        if (prefs != null) {
            prefs.edit().putBoolean("braille_calibrated", true).apply();
            for (int i = 0; i < 6; i++) {
                prefs.edit().putFloat("braille_dot_x_" + i, dotX[i]);
                prefs.edit().putFloat("braille_dot_y_" + i, dotY[i]);
            }
            prefs.edit().apply();
        }
        vibrate(80);
        speakTTS("Menggunakan susunan standar: Baris atas titik 1, 2, 3 dan baris bawah titik 4, 5, 6. Kalibrasi selesai, siap mengetik.", true);
        invalidate();
    }

    private void saveCalibration() {
        if (prefs == null) return;
        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < 6; i++) {
            ed.putFloat("braille_dot_x_" + i, dotX[i]);
            ed.putFloat("braille_dot_y_" + i, dotY[i]);
        }
        ed.putBoolean("braille_calibrated", true);
        ed.apply();
    }

    public void resetCalibration() {
        if (prefs != null) {
            SharedPreferences.Editor ed = prefs.edit();
            ed.remove("braille_calibrated");
            for (int i = 0; i < 6; i++) {
                ed.remove("braille_dot_x_" + i);
                ed.remove("braille_dot_y_" + i);
            }
            ed.apply();
        }
        calculateDotPositions(getWidth(), getHeight());
        speakTTS("Kalibrasi dikembalikan ke susunan 2 baris: atas titik 1, 2, 3 dan bawah titik 4, 5, 6.", true);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        canvas.drawRect(0, 0, w, h, bgPaint);

        if (isCalibrating) {
            drawCalibrationMode(canvas, w, h);
        } else if (isEmojiMode) {
            drawEmojiMode(canvas, w, h);
        } else {
            drawBrailleMode(canvas, w, h);
        }
    }

    private void drawCalibrationMode(Canvas canvas, int w, int h) {
        canvas.drawText("Kalibrasi Jari Braille", TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14, getResources().getDisplayMetrics()),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32, getResources().getDisplayMetrics()), headerTextPaint);

        float corner = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 13, getResources().getDisplayMetrics());
        canvas.drawRoundRect(btnCalibRect, corner, corner, pillBtnPaint);
        float cy = btnCalibRect.centerY() - ((pillTextPaint.descent() + pillTextPaint.ascent()) / 2f);
        canvas.drawText("Batal", btnCalibRect.centerX(), cy, pillTextPaint);

        canvas.drawRoundRect(btnSkipCalibRect, corner, corner, pillBtnPaint);
        float skipY = btnSkipCalibRect.centerY() - ((pillTextPaint.descent() + pillTextPaint.ascent()) / 2f);
        canvas.drawText("Gunakan Standar", btnSkipCalibRect.centerX(), skipY, pillTextPaint);

        String[] instructions = {
            "Sentuh TITIK 1\n(Kiri Atas)",
            "Sentuh TITIK 2\n(Tengah Atas)",
            "Sentuh TITIK 3\n(Kanan Paling Ujung Atas)",
            "Sentuh TITIK 4\n(Kiri Bawah)",
            "Sentuh TITIK 5\n(Tengah Bawah)",
            "Sentuh TITIK 6\n(Kanan Paling Ujung Bawah)"
        };

        Paint stepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        stepPaint.setColor(Color.parseColor("#00E5FF"));
        stepPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20, getResources().getDisplayMetrics()));
        stepPaint.setTextAlign(Paint.Align.CENTER);
        stepPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

        String curText = (calibrationStep < 6) ? instructions[calibrationStep] : "Selesai!";
        String[] lines = curText.split("\n");
        float promptY = h * 0.38f;
        for (int i = 0; i < lines.length; i++) {
            canvas.drawText(lines[i], w * 0.5f, promptY + (i * 65), stepPaint);
        }

        Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hintPaint.setColor(Color.parseColor("#8E95A8"));
        hintPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13, getResources().getDisplayMetrics()));
        hintPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Langkah " + (calibrationStep + 1) + " dari 6 • Sentuh layar atau tempelkan 6 jari sekaligus",
                w * 0.5f, h - TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 55, getResources().getDisplayMetrics()), hintPaint);

        for (int i = 0; i < calibrationStep; i++) {
            canvas.drawCircle(dotX[i], dotY[i], dotRadius + 4, dotActivePaint);
            canvas.drawCircle(dotX[i], dotY[i], dotRadius, dotBgPaint);
            float ty = dotY[i] - ((dotTextPaint.descent() + dotTextPaint.ascent()) / 2f);
            canvas.drawText(String.valueOf(i + 1), dotX[i], ty, dotTextPaint);
        }
    }

    private void drawBrailleMode(Canvas canvas, int w, int h) {
        // Garis Pembagi Kisi 2 Baris x 3 Kolom
        float colW = w / 3f;
        float rowSplit = h * 0.40f;
        canvas.drawLine(colW, 0, colW, h - 50, dividerPaint);
        canvas.drawLine(colW * 2f, 0, colW * 2f, h - 50, dividerPaint);
        canvas.drawLine(0, rowSplit, w, rowSplit, dividerPaint);

        // 6 Titik Braille (1, 2, 3 di Atas; 4, 5, 6 di Bawah)
        int[] dotNumbers = {1, 2, 3, 4, 5, 6};
        for (int i = 0; i < 6; i++) {
            float cx = dotX[i];
            float cy = dotY[i];

            if (dotPressed[i]) {
                canvas.drawCircle(cx, cy, dotRadius + 6, dotActivePaint);
                canvas.drawCircle(cx, cy, dotRadius, dotBgPaint);
                dotTextPaint.setColor(Color.parseColor("#00E5FF"));
            } else {
                canvas.drawCircle(cx, cy, dotRadius, dotBgPaint);
                canvas.drawCircle(cx, cy, dotRadius, dotRingPaint);
                dotTextPaint.setColor(Color.WHITE);
            }

            float ty = cy - ((dotTextPaint.descent() + dotTextPaint.ascent()) / 2f);
            canvas.drawText(String.valueOf(dotNumbers[i]), cx, ty, dotTextPaint);
        }

        // Status & Tombol di Bar Bawah Layar
        boolean arabicScript = prefs != null && "arabic".equals(prefs.getString("keyboard_script", "latin"));
        String statusText = (arabicScript ? "ع " : "⠃ ") + GRANULARITY_NAMES[currentGranularity];
        if (arabicScript) statusText += " (Arab)";
        if (isNumberMode) statusText += " (Angka)";
        if (isCaps) statusText += " (Shift)";
        canvas.drawText(statusText, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, getResources().getDisplayMetrics()),
                h - TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18, getResources().getDisplayMetrics()), headerTextPaint);

        float corner = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 13, getResources().getDisplayMetrics());
        canvas.drawRoundRect(btnSettingsRect, corner, corner, pillBtnPaint);
        float setY = btnSettingsRect.centerY() - ((pillTextPaint.descent() + pillTextPaint.ascent()) / 2f);
        canvas.drawText("Setelan", btnSettingsRect.centerX(), setY, pillTextPaint);

        canvas.drawRoundRect(btnCalibRect, corner, corner, pillBtnPaint);
        float calibY = btnCalibRect.centerY() - ((pillTextPaint.descent() + pillTextPaint.ascent()) / 2f);
        canvas.drawText("Kalibrasi", btnCalibRect.centerX(), calibY, pillTextPaint);

        canvas.drawRoundRect(btnEmojiRect, corner, corner, pillBtnPaint);
        float ey = btnEmojiRect.centerY() - ((pillTextPaint.descent() + pillTextPaint.ascent()) / 2f);
        canvas.drawText(arabicScript ? "ع+ Tajwid" : "😊 Emoji", btnEmojiRect.centerX(), ey, pillTextPaint);
    }

    private boolean isArabicScriptMode() {
        return prefs != null && "arabic".equals(prefs.getString("keyboard_script", "latin"));
    }

    private String[][][] activeGridPages() {
        return isArabicScriptMode() ? KeyboardData.ALL_ARABIC_PAGES : KeyboardData.ALL_EMOJI_PAGES;
    }

    private String[] activeGridPageNames() {
        return isArabicScriptMode() ? KeyboardData.ARABIC_PAGE_NAMES : KeyboardData.EMOJI_PAGE_NAMES;
    }

    private String speakGridSymbol(String item) {
        if (item == null || item.isEmpty()) return "";
        return isArabicScriptMode()
                ? BrailleHelper.getArabicSymbolSpokenName(item)
                : BrailleHelper.getEmojiName(item, emojiPage);
    }

    private void drawEmojiMode(Canvas canvas, int w, int h) {
        String[][][] pages = activeGridPages();
        String[] names = activeGridPageNames();
        if (emojiPage < 0) emojiPage = 0;
        if (emojiPage >= pages.length) emojiPage = pages.length - 1;
        boolean arMode = isArabicScriptMode();

        String pageTitle = (arMode ? "ع " : "😊 ") + names[emojiPage];
        canvas.drawText(pageTitle, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14, getResources().getDisplayMetrics()),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28, getResources().getDisplayMetrics()), headerTextPaint);

        float corner = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 13, getResources().getDisplayMetrics());
        canvas.drawRoundRect(btnEmojiRect, corner, corner, pillBtnPaint);
        float qy = btnEmojiRect.centerY() - ((pillTextPaint.descent() + pillTextPaint.ascent()) / 2f);
        canvas.drawText(arMode ? "ع Braille" : "⠃ Braille", btnEmojiRect.centerX(), qy, pillTextPaint);

        float topY = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 38, getResources().getDisplayMetrics());
        float bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 50, getResources().getDisplayMetrics());
        float gridH = h - topY - bottomMargin;
        float rowH = gridH / 4f;
        // 10 kolom biar urutan & isi sama persis kayak keyboard biasa
        float colW = w / 10f;

        String[][] pageGrid = pages[emojiPage];
        int itemIndex = 0;

        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 10; c++) {
                if (r < pageGrid.length && c < pageGrid[r].length) {
                    String emj = pageGrid[r][c];
                    float left = c * colW + 2;
                    float top = topY + r * rowH + 4;
                    float right = (c + 1) * colW - 2;
                    float bottom = topY + (r + 1) * rowH - 4;
                    RectF cellRect = new RectF(left, top, right, bottom);

                    if (itemIndex == hoveredEmojiIndex) {
                        Paint activeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
                        activeFill.setColor(Color.parseColor("#263852"));
                        canvas.drawRoundRect(cellRect, 10, 10, activeFill);
                        canvas.drawRoundRect(cellRect, 10, 10, emojiHighlightPaint);
                    } else {
                        canvas.drawRoundRect(cellRect, 10, 10, emojiGridPaint);
                    }

                    float ty = cellRect.centerY() - ((emojiTextPaint.descent() + emojiTextPaint.ascent()) / 2f);
                    canvas.drawText(emj, cellRect.centerX(), ty, emojiTextPaint);
                }
                itemIndex++;
            }
        }

        String footerInfo = "Hal " + (emojiPage + 1) + "/" + pages.length + " • 1 jari geser: pilih • Klik 1x: dengar • Klik 2x: masukkan • 2 jari: hal";
        canvas.drawText(footerInfo, w * 0.5f, h - TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, getResources().getDisplayMetrics()), emojiLabelPaint);
    }

    private int getDotFromCoordinates(float x, float y) {
        float bottomBarH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48, getResources().getDisplayMetrics());
        int w = getWidth();
        int h = getHeight();

        if (y > h - bottomBarH) {
            if (btnQwertyRect.contains(x, y) || btnEmojiRect.contains(x, y) || btnCalibRect.contains(x, y) || btnSkipCalibRect.contains(x, y)) {
                return -1;
            }
        }

        boolean isCalibrated = prefs != null && prefs.getBoolean("braille_calibrated", false);
        if (isCalibrated) {
            int closest = 0;
            float minDist = Float.MAX_VALUE;
            for (int i = 0; i < 6; i++) {
                float dist = (x - dotX[i]) * (x - dotX[i]) + (y - dotY[i]) * (y - dotY[i]);
                if (dist < minDist) {
                    minDist = dist;
                    closest = i;
                }
            }
            return closest;
        }

        // TATA LETAK 2 BARIS x 3 KOLOM SESUAI PERMINTAAN USER:
        // 1 di kiri atas | 2 di tengah | 3 di kanan paling ujung
        // 4 di kiri bawah | 5 di tengah | 6 di kanan paling ujung
        float colWidth = w / 3f;
        float rowSplit = h * 0.40f;

        if (y < rowSplit) {
            // BARIS ATAS:
            if (x < colWidth) {
                return 0; // Titik 1 (Kiri Atas)
            } else if (x < colWidth * 2f) {
                return 1; // Titik 2 (Tengah Atas)
            } else {
                return 2; // Titik 3 (Kanan Paling Ujung Atas)
            }
        } else {
            // BARIS BAWAH:
            if (x < colWidth) {
                return 3; // Titik 4 (Kiri Bawah - di bawah 1)
            } else if (x < colWidth * 2f) {
                return 4; // Titik 5 (Tengah Bawah - di bawah 2)
            } else {
                return 5; // Titik 6 (Kanan Paling Ujung Bawah - di bawah 3)
            }
        }
    }

    private int getEmojiIndexFromCoordinates(float x, float y) {
        float topY = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 42, getResources().getDisplayMetrics());
        float bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28, getResources().getDisplayMetrics());
        int w = getWidth();
        int h = getHeight();

        if (y < topY || y > h - bottomMargin) return -1;

        float gridH = h - topY - bottomMargin;
        float rowH = gridH / 4f;
        // 10 kolom — sama struktur ALL_EMOJI_PAGES (4 baris x 10 emoji)
        float colW = w / 10f;

        int col = (int) (x / colW);
        int row = (int) ((y - topY) / rowH);

        if (col >= 0 && col < 10 && row >= 0 && row < 4) {
            return (row * 10) + col;
        }
        return -1;
    }

    private String getEmojiAt(int index) {
        if (index < 0) return null;
        String[][][] pages = activeGridPages();
        if (emojiPage < 0) emojiPage = 0;
        if (emojiPage >= pages.length) emojiPage = pages.length - 1;
        String[][] pageGrid = pages[emojiPage];
        // Index flat 0..39 = 4 baris x 10 kolom, urutan identik keyboard biasa
        int r = index / 10;
        int c = index % 10;
        if (r < pageGrid.length && c < pageGrid[r].length) {
            return pageGrid[r][c];
        }
        return null;
    }

    // Mode ketik 1-jari lewat HOVER (JOS/TalkBack Touch Exploration).
    // Fakta: 2–3 jari sudah TOUCH nyata; 1 jari hanya HOVER.
    private boolean hoverChordActive = false;
    private float hoverStartX = 0f;
    private float hoverStartY = 0f;
    private boolean hoverWasSwipe = false;

    /**
     * Tangani HOVER langsung (jangan lewat MotionEvent.obtain — sering gagal di JOS).
     * ENTER: tekan titik di bawah jari. MOVE: akumulasi titik yang dilalui.
     * EXIT: commit chord / jalankan gesture swipe 1 jari.
     */
    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        if (event == null) return true;
        onHoverEvent(event);
        return true; // selalu konsumsi — JOS jangan announce area Braille
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        if (event == null) return true;

        if (isEmojiMode) {
            return handleEmojiHover(event);
        }

        float x = event.getX();
        float y = event.getY();
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER: {
                hoverChordActive = true;
                hoverWasSwipe = false;
                hoverStartX = x;
                hoverStartY = y;
                isAnyPointerSwiping = false;
                maxPointerCountInStroke = 1;
                isVoiceTriggered = false;
                clearDotPressState();

                if (isCalibrating) {
                    invalidate();
                    return true;
                }

                // Tekan titik di bawah jari (contoh huruf "a" = 1 titik)
                float bottomBarH = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 48, getResources().getDisplayMetrics());
                if (y <= getHeight() - bottomBarH) {
                    registerDotTouch(x, y);
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_HOVER_MOVE: {
                if (!hoverChordActive) return true;
                float dx = x - hoverStartX;
                float dy = y - hoverStartY;
                if (Math.hypot(dx, dy) > touchSlop) {
                    hoverWasSwipe = true;
                    isAnyPointerSwiping = true;
                    gestureDeltaX = dx;
                    gestureDeltaY = dy;
                }
                // Geser pelan antar titik = akumulasi chord 1 jari (1→2 = "b")
                if (!hoverWasSwipe && !isCalibrating) {
                    float bottomBarH = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP, 48, getResources().getDisplayMetrics());
                    if (y <= getHeight() - bottomBarH) {
                        registerDotTouch(x, y);
                    }
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_HOVER_EXIT: {
                if (!hoverChordActive) return true;
                hoverChordActive = false;

                float dx = x - hoverStartX;
                float dy = y - hoverStartY;
                if (Math.hypot(dx, dy) > touchSlop) {
                    hoverWasSwipe = true;
                    isAnyPointerSwiping = true;
                    gestureDeltaX = dx;
                    gestureDeltaY = dy;
                }
                maxPointerCountInStroke = 1;

                // Kalibrasi 1 jari
                if (isCalibrating) {
                    if (btnSkipCalibRect.contains(hoverStartX, hoverStartY)) {
                        useDefaultCalibration();
                    } else if (btnCalibRect.contains(hoverStartX, hoverStartY)) {
                        isCalibrating = false;
                        speakTTS("Kalibrasi jari dibatalkan", true);
                    } else if (!hoverWasSwipe) {
                        dotX[Math.min(calibrationStep, 5)] = hoverStartX;
                        dotY[Math.min(calibrationStep, 5)] = hoverStartY;
                        calibrationStep++;
                        vibrate(40);
                        if (calibrationStep >= 6) {
                            saveCalibration();
                            isCalibrating = false;
                            speakTTS("Kalibrasi selesai! Posisi 6 titik tersimpan pas dengan tangan Anda.", true);
                        } else {
                            speakTTS("Titik " + calibrationStep + " tersimpan. Lanjut titik berikutnya.", true);
                        }
                    }
                    clearDotPressState();
                    invalidate();
                    return true;
                }

                // Tombol pill bawah
                float bottomBarH = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 48, getResources().getDisplayMetrics());
                if (!hoverWasSwipe && hoverStartY > getHeight() - bottomBarH) {
                    if (btnSettingsRect.contains(hoverStartX, hoverStartY)) {
                        if (listener != null) listener.onOpenSettings();
                        speakTTS("Membuka pengaturan keyboard", true);
                    } else if (btnQwertyRect.contains(hoverStartX, hoverStartY)) {
                        if (listener != null) listener.onSwitchToQwerty();
                    } else if (btnEmojiRect.contains(hoverStartX, hoverStartY)) {
                        isEmojiMode = true;
                        emojiPage = 0;
                        hoveredEmojiIndex = 0;
                        speakTTS(isArabicScriptMode()
                                ? "Simbol Arab dan Tajwid. " + activeGridPageNames()[0]
                                : "Mode emoji. Halaman 1", true);
                    } else if (btnCalibRect.contains(hoverStartX, hoverStartY)) {
                        startCalibration();
                    }
                    clearDotPressState();
                    invalidate();
                    return true;
                }

                // Swipe ATAU chord — pakai handler yang sama dengan multi-touch
                isAnyPointerSwiping = hoverWasSwipe;
                handleBrailleActionUp();
                clearDotPressState();
                invalidate();
                return true;
            }

            default:
                return true;
        }
    }

    private boolean handleEmojiHover(MotionEvent event) {
        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();
        if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
            int idx = getEmojiIndexFromCoordinates(x, y);
            if (idx != -1 && idx != hoveredEmojiIndex) {
                hoveredEmojiIndex = idx;
                String emj = getEmojiAt(idx);
                if (emj != null) {
                    vibrate(12);
                    playAudio();
                    // Baca di semua halaman (1–13), pakai TTS keyboard agar rapi
                    speakTTS(speakGridSymbol(emj), true);
                }
                invalidate();
            }
        }
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isEmojiMode) {
            return handleEmojiTouchEvent(event);
        }

        int action = event.getActionMasked();
        int actionIdx = event.getActionIndex();
        int pointerCount = event.getPointerCount();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                int id = event.getPointerId(0);
                for (int i = 0; i < 10; i++) pointerActive[i] = false;
                cancelHoldCalibration(true);
                holdCalibCompleted = false;

                if (id < 10) {
                    pointerDownX[id] = event.getX(0);
                    pointerDownY[id] = event.getY(0);
                    pointerCurrX[id] = pointerDownX[id];
                    pointerCurrY[id] = pointerDownY[id];
                    pointerActive[id] = true;
                    pointerDownTime[id] = System.currentTimeMillis();
                    holdPointerOrder.add(id);
                }

                maxPointerCountInStroke = 1;
                isAnyPointerSwiping = false;
                gestureDeltaX = 0f;
                gestureDeltaY = 0f;
                isVoiceTriggered = false;

                // 1. Cek Mode Kalibrasi Jari (Tombol Batal / Standar langsung diproses; tap titik diproses saat angkat 1 jari,
                //    sedangkan jika ditahan dan disusul jari 2..6 selama 5 detik akan memicu kalibrasi otomatis!)
                if (isCalibrating) {
                    if (id < 10 && btnCalibRect.contains(pointerDownX[id], pointerDownY[id])) {
                        isCalibrating = false;
                        cancelHoldCalibration(true);
                        speakTTS("Kalibrasi jari dibatalkan", true);
                        invalidate();
                        return true;
                    }
                    if (id < 10 && btnSkipCalibRect.contains(pointerDownX[id], pointerDownY[id])) {
                        cancelHoldCalibration(true);
                        useDefaultCalibration();
                        return true;
                    }
                    vibrate(25);
                    invalidate();
                    return true;
                }

                // 2. Cek Tombol Pill di Bagian Bawah Layar (di luar area 6 titik)
                float bottomBarH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48, getResources().getDisplayMetrics());
                if (id < 10 && pointerDownY[id] > getHeight() - bottomBarH) {
                    if (btnSettingsRect.contains(pointerDownX[id], pointerDownY[id])) {
                        vibrate(40);
                        playAudio();
                        speakTTS("Membuka pengaturan keyboard", true);
                        if (listener != null) listener.onOpenSettings();
                        return true;
                    }
                    if (btnQwertyRect.contains(pointerDownX[id], pointerDownY[id])) {
                        if (listener != null) listener.onSwitchToQwerty();
                        return true;
                    }
                    if (btnEmojiRect.contains(pointerDownX[id], pointerDownY[id])) {
                        isEmojiMode = true;
                        emojiPage = 0;
                        hoveredEmojiIndex = 0;
                        speakTTS(isArabicScriptMode()
                                ? "Simbol Arab dan Tajwid. " + activeGridPageNames()[0]
                                : "Mode emoji. Halaman 1", true);
                        invalidate();
                        return true;
                    }
                    if (btnCalibRect.contains(pointerDownX[id], pointerDownY[id])) {
                        startCalibration();
                        return true;
                    }
                }

                // 3. Timer 3 Detik Input Suara (Khusus tahan 1 jari diam)
                voiceHandler.postDelayed(voiceRunnable, 3000);
                // Reset timer kirim (hanya multi-jari)
                sendHoldHandler.removeCallbacks(sendHoldRunnable);
                isSendHoldTriggered = false;
                sendHoldActivePointers = 1;

                accumulatedChordMask = 0;
                clearDotPressState();
                if (id < 10) registerDotTouch(pointerDownX[id], pointerDownY[id]);
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = event.getPointerId(actionIdx);
                if (id < 10) {
                    pointerDownX[id] = event.getX(actionIdx);
                    pointerDownY[id] = event.getY(actionIdx);
                    pointerCurrX[id] = pointerDownX[id];
                    pointerCurrY[id] = pointerDownY[id];
                    pointerActive[id] = true;
                    pointerDownTime[id] = System.currentTimeMillis();
                    if (!holdPointerOrder.contains(id)) holdPointerOrder.add(id);
                }
                maxPointerCountInStroke = Math.max(maxPointerCountInStroke, event.getPointerCount());
                voiceHandler.removeCallbacks(voiceRunnable);

                // Jika >= 4 jari turun, user sedang bersiap menaruh 6 jari untuk kalibrasi 5 detik — jangan picu Send!
                if (pointerCount >= 4) {
                    sendHoldHandler.removeCallbacks(sendHoldRunnable);
                    isSendHoldTriggered = false;
                } else {
                    sendHoldHandler.removeCallbacks(sendHoldRunnable);
                    isSendHoldTriggered = false;
                    sendHoldActivePointers = event.getPointerCount();
                    if (!isCalibrating && prefs != null && prefs.getBoolean("braille_send_longpress_enabled", true)) {
                        int needFingers = prefs.getInt("braille_send_longpress_fingers", 2);
                        if (needFingers < 2) needFingers = 2;
                        if (needFingers > 3) needFingers = 3;
                        if (sendHoldActivePointers >= needFingers) {
                            int holdMs = prefs.getInt("braille_send_longpress_ms", 2500);
                            if (holdMs < 1500) holdMs = 1500;
                            if (holdMs > 4000) holdMs = 4000;
                            sendHoldHandler.postDelayed(sendHoldRunnable, holdMs);
                        }
                    }
                }

                // KALIBRASI OTOMATIS KAPANPUN: Taruh jari 1 tahan, lalu susul jari 2, 3, 4, 5, 6 selama 5 detik!
                if (pointerCount >= 6 && !isHoldCalibPending && !holdCalibCompleted && !isAnyPointerSwiping) {
                    startHoldCalibrationCountdown();
                    invalidate();
                    return true;
                }

                if (isCalibrating) {
                    vibrate(20);
                    invalidate();
                    return true;
                }

                // Daftarkan titik di bawah jari tambahan (chord multi-titik: l, g, q, dll)
                if (!isEmojiMode && !isAnyPointerSwiping && id < 10) {
                    registerDotTouch(pointerDownX[id], pointerDownY[id]);
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                maxPointerCountInStroke = Math.max(maxPointerCountInStroke, pointerCount);
                float swipeSlop = (pointerCount >= 2) ? (touchSlop * 1.15f) : touchSlop;

                float sumDx = 0f, sumDy = 0f;
                int activeN = 0;
                boolean anyBeyondSlop = false;
                for (int i = 0; i < pointerCount; i++) {
                    int id = event.getPointerId(i);
                    if (id < 10) {
                        pointerCurrX[id] = event.getX(i);
                        pointerCurrY[id] = event.getY(i);
                        float dx = pointerCurrX[id] - pointerDownX[id];
                        float dy = pointerCurrY[id] - pointerDownY[id];
                        sumDx += dx;
                        sumDy += dy;
                        activeN++;
                        float dist = (float) Math.hypot(dx, dy);
                        if (dist > swipeSlop) {
                            anyBeyondSlop = true;
                            voiceHandler.removeCallbacks(voiceRunnable);
                            sendHoldHandler.removeCallbacks(sendHoldRunnable);
                            if (isHoldCalibPending && dist > touchSlop * 1.6f) {
                                cancelHoldCalibration(false);
                            }
                        }
                    }
                }
                if (isCalibrating) {
                    invalidate();
                    return true;
                }
                if (!isHoldCalibPending && !holdCalibCompleted && anyBeyondSlop && activeN > 0) {
                    isAnyPointerSwiping = true;
                    gestureDeltaX = sumDx / activeN;
                    gestureDeltaY = sumDy / activeN;
                    accumulatedChordMask = 0;
                    for (int i = 0; i < 6; i++) dotPressed[i] = false;
                    sendHoldHandler.removeCallbacks(sendHoldRunnable);
                }

                invalidate();
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int id = event.getPointerId(actionIdx);
                if (id < 10) {
                    pointerCurrX[id] = event.getX(actionIdx);
                    pointerCurrY[id] = event.getY(actionIdx);
                    float dx = pointerCurrX[id] - pointerDownX[id];
                    float dy = pointerCurrY[id] - pointerDownY[id];
                    float dist = (float) Math.hypot(dx, dy);
                    if (!isHoldCalibPending && !holdCalibCompleted && dist > touchSlop) {
                        isAnyPointerSwiping = true;
                        gestureDeltaX = dx;
                        gestureDeltaY = dy;
                    }
                    pointerActive[id] = false;
                }
                maxPointerCountInStroke = Math.max(maxPointerCountInStroke, event.getPointerCount());
                sendHoldHandler.removeCallbacks(sendHoldRunnable);

                int remaining = event.getPointerCount() - 1;
                if (isHoldCalibPending && remaining < 6) {
                    cancelHoldCalibration(false);
                    if (isCalibrating) {
                        speakTTS("Jari terlepas sebelum lima detik. Tahan keenam jari selama lima detik atau sentuh satu per satu.", true);
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                voiceHandler.removeCallbacks(voiceRunnable);
                sendHoldHandler.removeCallbacks(sendHoldRunnable);
                cancelHoldCalibration(false);

                int id = event.getPointerId(0);
                if (id < 10) {
                    pointerCurrX[id] = event.getX(0);
                    pointerCurrY[id] = event.getY(0);
                    pointerActive[id] = false;
                }

                if (holdCalibCompleted) {
                    holdCalibCompleted = false;
                    holdPointerOrder.clear();
                    clearDotPressState();
                    invalidate();
                    return true;
                }
                holdPointerOrder.clear();

                if (isCalibrating) {
                    // Kalibrasi 1 jari satu per satu hanya jika tidak ada gerakan multi-jari/swipe
                    if (maxPointerCountInStroke == 1 && id < 10) {
                        float dx = pointerCurrX[id] - pointerDownX[id];
                        float dy = pointerCurrY[id] - pointerDownY[id];
                        if (Math.hypot(dx, dy) <= touchSlop) {
                            dotX[Math.min(calibrationStep, 5)] = pointerDownX[id];
                            dotY[Math.min(calibrationStep, 5)] = pointerDownY[id];
                            vibrate(40);
                            calibrationStep++;
                            if (calibrationStep >= 6) {
                                saveCalibration();
                                isCalibrating = false;
                                vibrate(100);
                                speakTTS("Kalibrasi selesai! Posisi 6 titik tersimpan pas dengan tangan Anda.", true);
                            } else {
                                String[] stepNames = {
                                        "Titik 1 (Kiri Atas)",
                                        "Titik 2 (Tengah Atas)",
                                        "Titik 3 (Kanan Paling Ujung Atas)",
                                        "Titik 4 (Kiri Bawah)",
                                        "Titik 5 (Tengah Bawah)",
                                        "Titik 6 (Kanan Paling Ujung Bawah)"
                                };
                                speakTTS("Titik " + calibrationStep + " tersimpan. Sekarang sentuh " + stepNames[calibrationStep], true);
                            }
                        }
                    }
                    invalidate();
                    return true;
                }

                if (isVoiceTriggered) {
                    isVoiceTriggered = false;
                    clearDotPressState();
                    invalidate();
                    return true;
                }
                if (isSendHoldTriggered) {
                    isSendHoldTriggered = false;
                    clearDotPressState();
                    invalidate();
                    return true;
                }

                if (id < 10) {
                    float dx = pointerCurrX[id] - pointerDownX[id];
                    float dy = pointerCurrY[id] - pointerDownY[id];
                    if (Math.hypot(dx, dy) > touchSlop) {
                        isAnyPointerSwiping = true;
                        gestureDeltaX = dx;
                        gestureDeltaY = dy;
                    }
                }

                handleBrailleActionUp();

                clearDotPressState();
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                voiceHandler.removeCallbacks(voiceRunnable);
                sendHoldHandler.removeCallbacks(sendHoldRunnable);
                cancelHoldCalibration(true);
                holdCalibCompleted = false;
                isSendHoldTriggered = false;
                clearDotPressState();
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private boolean handleEmojiTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                emojiStartX = event.getX(0);
                emojiStartY = event.getY(0);
                emojiStartTime = System.currentTimeMillis();
                emojiMaxPointers = 1;
                emojiIsExploring = false;
                isVoiceTriggered = false;

                voiceHandler.postDelayed(voiceRunnable, 3000);
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                emojiMaxPointers = Math.max(emojiMaxPointers, pointerCount);
                voiceHandler.removeCallbacks(voiceRunnable);
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                emojiMaxPointers = Math.max(emojiMaxPointers, pointerCount);
                float curX = event.getX(0);
                float curY = event.getY(0);
                float dx = curX - emojiStartX;
                float dy = curY - emojiStartY;
                float dist = (float) Math.hypot(dx, dy);
                long elapsed = System.currentTimeMillis() - emojiStartTime;

                if (dist > touchSlop / 2f) {
                    voiceHandler.removeCallbacks(voiceRunnable);
                }

                // Mode Raba Layar (Touch Exploration) ala TalkBack:
                // Jika pengguna menahan dan menggeser jari lebih dari 250ms dengan 1 jari
                if (emojiMaxPointers == 1 && elapsed > 250) {
                    emojiIsExploring = true;
                    int idx = getEmojiIndexFromCoordinates(curX, curY);
                    if (idx != -1 && idx != hoveredEmojiIndex) {
                        hoveredEmojiIndex = idx;
                        String emj = getEmojiAt(idx);
                        if (emj != null) {
                            vibrate(12);
                            playAudio();
                            // Emoji standar saja; jika screen reader aktif biarkan sistem yang baca
                            String name = speakGridSymbol(emj);
                            speakTTS(name, true);
                        }
                        invalidate();
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                emojiMaxPointers = Math.max(emojiMaxPointers, pointerCount);
                return true;
            }

            case MotionEvent.ACTION_UP: {
                voiceHandler.removeCallbacks(voiceRunnable);
                if (isVoiceTriggered) {
                    isVoiceTriggered = false;
                    return true;
                }

                emojiMaxPointers = Math.max(emojiMaxPointers, pointerCount);
                float curX = event.getX(0);
                float curY = event.getY(0);
                float dx = curX - emojiStartX;
                float dy = curY - emojiStartY;
                float absX = Math.abs(dx);
                float absY = Math.abs(dy);
                long duration = System.currentTimeMillis() - emojiStartTime;

                // 1. Cek Tombol Pill di Bagian Bawah Layar (Kembali ke Braille)
                float bottomBarH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48, getResources().getDisplayMetrics());
                if (curY > getHeight() - bottomBarH && btnEmojiRect.contains(curX, curY)) {
                    isEmojiMode = false;
                    lastEmojiTapTime = 0L;
                    vibrate(30);
                    playAudio();
                    speakTTS("Kembali ke papan ketik Braille", true);
                    invalidate();
                    return true;
                }

                // 2. Deteksi Gestur Usap (Swipe) TalkBack
                boolean isSwipe = (absX > touchSlop || absY > touchSlop) && (!emojiIsExploring || duration < 500);
                if (isSwipe) {
                    lastEmojiTapTime = 0L;
                    if (absX > absY) {
                        // === USAPAN HORIZONTAL ===
                        if (emojiMaxPointers >= 2) {
                            // USAPAN 2 JARI: GANTI HALAMAN EMOJI / SIMBOL ARAB TAJWID
                            int pageCount = activeGridPages().length;
                            if (dx > 0) {
                                emojiPage = (emojiPage + 1) % pageCount;
                            } else {
                                emojiPage = (emojiPage - 1 + pageCount) % pageCount;
                            }
                            hoveredEmojiIndex = 0;
                            vibrate(40);
                            playAudio();
                            String first = getEmojiAt(0);
                            String pgLabel = isArabicScriptMode()
                                    ? activeGridPageNames()[emojiPage]
                                    : ("Halaman " + (emojiPage + 1));
                            speakTTS(pgLabel + (first != null && !first.isEmpty() ? ". " + speakGridSymbol(first) : ""), true);
                        } else {
                            // USAPAN 1 JARI: pilih item satu per satu
                            if (dx > 0) {
                                hoveredEmojiIndex = (hoveredEmojiIndex + 1) % 40;
                            } else {
                                hoveredEmojiIndex = (hoveredEmojiIndex - 1 + 40) % 40;
                            }
                            vibrate(20);
                            playAudio();
                            String emj = getEmojiAt(hoveredEmojiIndex);
                            if (emj != null && !emj.isEmpty()) {
                                speakTTS(speakGridSymbol(emj), true);
                            } else {
                                speakTTS("Kosong", true);
                            }
                        }
                    } else {
                        // === USAPAN VERTIKAL ===
                        if (dy < 0) {
                            // Usap ke Atas 1 Jari: Kembali ke Papan Ketik Braille
                            isEmojiMode = false;
                            vibrate(30);
                            playAudio();
                            speakTTS("Kembali ke papan ketik Braille", true);
                        } else {
                            // Usap ke Bawah 1 Jari: Sembunyikan Keyboard
                            vibrate(30);
                            speakTTS("Keyboard disembunyikan", true);
                            final OnBrailleActionListener l = listener;
                            post(new Runnable() {
                                @Override public void run() {
                                    try {
                                        if (l != null) l.onHideKeyboard();
                                    } catch (Throwable ignored) {}
                                }
                            });
                        }
                    }
                    invalidate();
                    return true;
                }

                // 3. Jika sedang eksplorasi raba layar dan diangkat, jangan commit
                if (emojiIsExploring) {
                    lastEmojiTapTime = 0L;
                    return true;
                }

                // 4. KETUKAN DI LAYAR (STANDAR TALKBACK: 1X HANYA BACA, 2X MASUKKAN BEBAS DI MANA SAJA)
                long now = System.currentTimeMillis();
                if (lastEmojiTapTime > 0 && (now - lastEmojiTapTime) < 750) {
                    // === KLIK DUA KALI: MASUKKAN EMOJI FOKUS (BEBAS DI TENGAH ATAU DI MANA PUN) ===
                    String emj = getEmojiAt(hoveredEmojiIndex);
                    if (emj != null) {
                        vibrate(55);
                        playAudio();
                        if (listener != null) {
                            listener.onCommitText(emj);
                        }
                        // Commit karakter / emoji standar
                        speakTTS(speakGridSymbol(emj), true);
                    }
                    lastEmojiTapTime = 0L;
                } else {
                    // === KLIK SATU KALI: HANYA MEMBACAKAN ITEM YANG SEDANG DIFOKUSKAN ===
                    lastEmojiTapTime = now;
                    String emj = getEmojiAt(hoveredEmojiIndex);
                    if (emj != null) {
                        vibrate(15);
                        playAudio();
                        String desc = speakGridSymbol(emj);
                        speakTTS(desc, true);
                    }
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                voiceHandler.removeCallbacks(voiceRunnable);
                return true;
            }
        }
        return true;
    }

    private int effectiveGranularity() {
        if (currentGranularity == GRANULARITY_CLEAR_ALL) return GRANULARITY_CHARACTER;
        return currentGranularity >= SPEECH_OFF ? GRANULARITY_CHARACTER : currentGranularity;
    }

    private void handleBrailleActionUp() {
        if (isAnyPointerSwiping) {
            float absX = Math.abs(gestureDeltaX);
            float absY = Math.abs(gestureDeltaY);

            if (absY >= absX) {
                // === GESTUR VERTIKAL (BEBAS DARI AREA MANA SAJA DI LAYAR) ===
                if (maxPointerCountInStroke >= 3) {
                    // USAPAN 3 JARI: GANTI MODE NAVIGASI (termasuk "Bersihkan semua teks")
                    if (gestureDeltaY > 0) {
                        currentGranularity = (currentGranularity + 1) % GRANULARITY_NAMES.length;
                    } else {
                        currentGranularity = (currentGranularity - 1 + GRANULARITY_NAMES.length) % GRANULARITY_NAMES.length;
                    }
                    clearAllConfirmPending = false;
                    clearAllConfirmUntilMs = 0L;
                    vibrate(45);
                    playAudio();
                    if (currentGranularity == GRANULARITY_CLEAR_ALL) {
                        speakTTS("Bersihkan semua teks. Geser ke atas dengan satu jari dua kali untuk menghapus seluruh kotak edit", true);
                    } else {
                        speakTTS(GRANULARITY_NAMES[currentGranularity], true);
                    }
                    invalidate();
                } else if (gestureDeltaY < 0) {
                    // USAPAN KE ATAS — di SEMUA mode:
                    if (maxPointerCountInStroke >= 2) {
                        // 2 JARI KE ATAS: pengaturan
                        vibrate(50);
                        playAudio();
                        speakTTS("Membuka pengaturan keyboard", true);
                        if (listener != null) listener.onOpenSettings();
                    } else {
                        // 1 JARI KE ATAS
                        vibrate(30);
                        playAudio();
                        if (currentGranularity == GRANULARITY_CLEAR_ALL) {
                            long now = System.currentTimeMillis();
                            if (clearAllConfirmPending && now <= clearAllConfirmUntilMs) {
                                clearAllConfirmPending = false;
                                clearAllConfirmUntilMs = 0L;
                                speakTTS("Seluruh teks dihapus", true);
                                if (listener != null) {
                                    try { listener.onClearAll(); } catch (Throwable ignored) {}
                                }
                            } else {
                                clearAllConfirmPending = true;
                                clearAllConfirmUntilMs = now + CLEAR_ALL_CONFIRM_WINDOW_MS;
                                speakTTS("Apakah kamu yakin ingin menghapus seluruh teks? Geser ke atas sekali lagi untuk mengonfirmasi", true);
                            }
                            invalidate();
                        } else {
                            // Menghapus karakter juga mengembalikan kapital ke huruf kecil.
                            clearAllConfirmPending = false;
                            isCaps = false;
                            isCapsLocked = false;
                            lastCapitalSignTime = 0L;
                            invalidate();
                            if (listener != null) {
                                if (currentGranularity == GRANULARITY_CLIPBOARD) {
                                    listener.onBrailleDeleteAdaptive(GRANULARITY_CLIPBOARD);
                                } else if (currentGranularity == GRANULARITY_COPY
                                        || currentGranularity == GRANULARITY_PASTE) {
                                    listener.onBrailleDeleteAdaptive(GRANULARITY_CHARACTER);
                                } else {
                                    listener.onBrailleDeleteAdaptive(effectiveGranularity());
                                }
                            }
                        }
                    }
                } else {
                    // USAP KE BAWAH
                    if (maxPointerCountInStroke >= 2) {
                        vibrate(35);
                        playAudio();
                        if (listener != null) listener.onEnter();
                        speakTTS("Baris baru", true);
                    } else {
                        // 1 JARI KE BAWAH: SPASI (selalu)
                        vibrate(25);
                        playAudio();
                        if (listener != null) {
                            if (currentGranularity == SPEECH_WORD_ON_SPACE || currentGranularity == SPEECH_CHARACTER_AND_WORD) {
                                listener.onReadWordBeforeCursor();
                            }
                            listener.onSpace();
                        }
                        isNumberMode = false;
                        // Spasi selalu mengakhiri Caps Lock, sama seperti keyboard biasa.
                        isCaps = false;
                        isCapsLocked = false;
                        lastCapitalSignTime = 0L;
                        invalidate();
                    }
                }
            } else {
                // === GESTUR HORIZONTAL — SALIN/TEMPEL pakai 1 jari di mode-nya ===
                if (gestureDeltaX < 0) {
                    if (maxPointerCountInStroke >= 3) {
                        // 3 JARI KE KIRI: (belum dipakai khusus) — biarkan / nanti bisa tempel global
                        vibrate(30);
                        playAudio();
                        if (listener != null) listener.onPaste();
                        speakTTS("Tempel", true);
                    } else if (maxPointerCountInStroke >= 2) {
                        // 2 JARI KE KIRI: sembunyikan keyboard
                        // PENTING: jangan panggil onHideKeyboard sinkron di frame
                        // ACTION_UP multi-touch — clear passthrough / hideWindow
                        // di sini bisa force-close app host (TeamTalk) atau macet HP.
                        speakTTS("Keyboard disembunyikan", true);
                        final OnBrailleActionListener l = listener;
                        post(new Runnable() {
                            @Override public void run() {
                                try {
                                    if (l != null) l.onHideKeyboard();
                                } catch (Throwable ignored) {}
                            }
                        });
                    } else {
                        // 1 JARI KE KIRI
                        vibrate(20);
                        playAudio();
                        if (currentGranularity == GRANULARITY_PASTE
                                || currentGranularity == GRANULARITY_COPY) {
                            // Mode Tempel (atau Salin): kiri = tempel
                            if (listener != null) listener.onPaste();
                        } else if (listener != null) {
                            // Teks / Papan klip: navigasi mundur / item sebelumnya
                            listener.onBrailleNavigateAdaptive(effectiveGranularity(), false);
                        }
                    }
                } else {
                    if (maxPointerCountInStroke >= 3) {
                        // 3 JARI KE KANAN: KIRIM (bisa dimatikan di pengaturan Braille)
                        if (prefs != null && prefs.getBoolean("braille_send_3finger_right", true)) {
                            vibrate(80);
                            playAudio();
                            speakTTS("Mengirim", true);
                            if (listener != null) {
                                try { listener.onSend(); } catch (Throwable ignored) {}
                            }
                        } else {
                            // Kalau dimatikan, anggap seperti 2 jari (mode emoji / simbol Arab tajwid)
                            isEmojiMode = true;
                            emojiPage = 0;
                            hoveredEmojiIndex = 0;
                            vibrate(40);
                            playAudio();
                            speakTTS(isArabicScriptMode()
                                    ? "Simbol Arab dan Tajwid. " + activeGridPageNames()[0]
                                    : "Mode emoji. Halaman 1", true);
                        }
                    } else if (maxPointerCountInStroke >= 2) {
                        // 2 JARI KE KANAN: mode emoji / simbol Arab tajwid
                        isEmojiMode = true;
                        emojiPage = 0;
                        hoveredEmojiIndex = 0;
                        vibrate(40);
                        playAudio();
                        speakTTS(isArabicScriptMode()
                                ? "Simbol Arab dan Tajwid. " + activeGridPageNames()[0]
                                : "Mode emoji. Halaman 1", true);
                    } else {
                        // 1 JARI KE KANAN
                        vibrate(20);
                        playAudio();
                        if (currentGranularity == GRANULARITY_COPY
                                || currentGranularity == GRANULARITY_PASTE) {
                            // Mode Salin (atau Tempel): kanan = salin
                            if (listener != null) listener.onCopy();
                        } else if (listener != null) {
                            listener.onBrailleNavigateAdaptive(effectiveGranularity(), true);
                        }
                    }
                }
            }
            lastChordMask = 0;
            lastChordTime = 0L;
            return;
        }

        // Mode papan klip: klik (angkat tanpa usap, tanpa chord) = tempel item terpilih
        if (currentGranularity == GRANULARITY_CLIPBOARD && accumulatedChordMask == 0) {
            vibrate(25);
            playAudio();
            if (listener != null) listener.onPaste();
            return;
        }

        // BUKAN USAPAN -> PENGETIKAN CHORD BRAILLE
        if (accumulatedChordMask > 0) {
            vibrate(25);
            playAudio(); // Suara ketik HANYA berbunyi 1x saat karakter selesai diketik!
            long now = System.currentTimeMillis();

            boolean isArabicMode = prefs != null && "arabic".equals(prefs.getString("keyboard_script", "latin"));

            // Multi-cell Latin (= / ÷) HANYA di mode Latin.
            // Mode Arab: titik 2-5 = sukun (ْ) resmi Unified Arabic Braille — jangan di-hijack.
            if (!isArabicMode) {
                // 1. Tanda Sama Dengan (=): Titik 2-5 lalu Titik 2-5 lagi
                if (accumulatedChordMask == (BrailleHelper.DOT_2 | BrailleHelper.DOT_5)) {
                    if (lastChordMask == (BrailleHelper.DOT_2 | BrailleHelper.DOT_5) && (now - lastChordTime) < 2500) {
                        if (listener != null) {
                            listener.onDelete();
                            listener.onCommitText("=");
                        }
                        speakTTS("Sama dengan", true);
                        lastChordMask = 0;
                        lastChordTime = 0L;
                        return;
                    } else {
                        if (listener != null) {
                            listener.onCommitText(":");
                        }
                        speakTTS("Titik dua", true);
                        lastChordMask = accumulatedChordMask;
                        lastChordTime = now;
                        return;
                    }
                }

                // 2. Tanda Bagi (÷): Titik 3-4 lalu Titik 3-4 lagi
                if (accumulatedChordMask == (BrailleHelper.DOT_3 | BrailleHelper.DOT_4)) {
                    if (lastChordMask == (BrailleHelper.DOT_3 | BrailleHelper.DOT_4) && (now - lastChordTime) < 2500) {
                        if (listener != null) {
                            listener.onDelete();
                            listener.onCommitText("÷");
                        }
                        speakTTS("Bagi", true);
                        lastChordMask = 0;
                        lastChordTime = 0L;
                        return;
                    } else {
                        if (listener != null) {
                            listener.onCommitText("/");
                        }
                        speakTTS("Garis miring", true);
                        lastChordMask = accumulatedChordMask;
                        lastChordTime = now;
                        return;
                    }
                }
            }

            lastChordMask = accumulatedChordMask;
            lastChordTime = now;

            BrailleHelper.ParseResult res = BrailleHelper.parseBraille(accumulatedChordMask, isNumberMode, isCaps, isArabicMode);

            if (res.isNumberToggle) {
                isNumberMode = !isNumberMode;
                speakTTS(res.spokenText, true);
            } else if (res.isCapitalToggle) {
                // Kapital Braille: satu tanda = huruf besar berikutnya;
                // dua tanda berurutan = Caps Lock sampai spasi atau hapus.
                long capitalNow = System.currentTimeMillis();
                if (isCapsLocked) {
                    isCapsLocked = false;
                    isCaps = false;
                    speakTTS("Caps Lock mati, huruf kecil", true);
                } else if (isCaps && capitalNow - lastCapitalSignTime <= 1500L) {
                    isCapsLocked = true;
                    isCaps = true;
                    speakTTS("Caps Lock aktif, semua huruf besar", true);
                } else {
                    isCaps = !isCaps;
                    speakTTS(isCaps ? "Huruf besar untuk satu huruf" : "Huruf kecil", true);
                }
                lastCapitalSignTime = capitalNow;
                invalidate();
            } else if (!res.text.isEmpty()) {
                if (listener != null) {
                    listener.onCommitText(res.text);
                }
                if (currentGranularity == SPEECH_CHARACTER || currentGranularity == SPEECH_CHARACTER_AND_WORD) {
                    speakTTS(res.spokenText, true);
                }
                if (isCaps && !isCapsLocked) {
                    isCaps = false;
                }
            } else {
                speakTTS(res.spokenText, true);
            }
        }
    }

    private void registerDotTouch(float x, float y) {
        int dotIdx = getDotFromCoordinates(x, y);
        if (dotIdx >= 0 && dotIdx < 6) {
            if (!dotPressed[dotIdx]) {
                dotPressed[dotIdx] = true;
                accumulatedChordMask |= (1 << dotIdx);
                vibrate(12);
                // TIDAK memainkan playAudio() di sini agar tidak berbunyi ganda!
            }
        }
    }

    private void clearDotPressState() {
        for (int i = 0; i < 6; i++) {
            dotPressed[i] = false;
        }
        accumulatedChordMask = 0;
    }

    public void setEmojiMode(boolean emoji) {
        this.isEmojiMode = emoji;
        invalidate();
    }
}
