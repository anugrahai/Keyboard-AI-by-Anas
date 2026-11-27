package com.annas;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;

import android.os.Bundle;

import android.os.Handler;

import android.os.Looper;

import android.provider.Settings;
import android.speech.tts.TextToSpeech;

import android.view.inputmethod.InputMethodManager;

import android.view.Gravity;

import android.view.View;

import android.widget.AdapterView;

import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar; 
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "VoiceInput_Settings";
    private SharedPreferences prefs;
    private GitHubUpdateManager githubUpdateManager;
    
    private static final int PICK_JSON_GAYA = 111;
    private static final int PICK_DOKUMEN_CHAT = 222;
    private static final int PICK_AUDIO_KETIK = 333;
    private static final int PICK_AUDIO_NEMPEL = 444;
    private static final int PICK_CHAT_VOICE = 555;
    private static final int PICK_CHAT_ATTACH_FILE = 556;
    private static final int PICK_CHAT_ATTACH_FOLDER = 557;
    /** EditText chat AI yang lagi aktif — diisi hasil Google STT */
    private EditText pendingChatVoiceInput;
    private EditText pendingChatAttachTarget;
    private TextView pendingChatAttachBanner;

    // Navigasi 5 Tab Bawah + Sub-Halaman In-Screen (Tab Bar otomatis hilang di dalam sub-halaman)
    private static final String[] TAB_NAMES = {"Keyboard", "Asisten AI", "Coding", "Kantor", "Lainnya"};
    private static final class SubPageEntry {
        final String title;
        final View view;
        SubPageEntry(String title, View view) { this.title = title; this.view = view; }
    }
    private int activeTab = 0;
    private final ArrayList<Integer> tabHistory = new ArrayList<>();
    private final ArrayList<SubPageEntry> subPageStack = new ArrayList<>();
    private android.widget.FrameLayout contentHost;
    private LinearLayout tabBarContainer;
    private TextView headerTitleView;
    private Button headerThemeBtn;
    private TextView tabPageIndicator;
    private final Button[] tabButtons = new Button[5];
    private boolean mainUiMounted = false;
    private float tabSwipeStartX = 0f;
    private float tabSwipeStartY = 0f;
    private boolean tabSwipeTracking = false;
    private boolean tabSwipeTriggered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        AuthManager.ensureFreshPreferences(this);

        if (!AuthManager.isSignedIn()) {
            AuthManager.openLoginScreen(this);
            finish();
            return;
        }

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        UiKit.applySystemBars(this, prefs);
        extractDefaultStyles();




        // Minta semua izin runtime yang dibutuhkan di setup awal
        requestAllRuntimePermissions();

        if (!prefs.contains("json_styles_raw")) {
            try {
                InputStream is = getResources().openRawResource(R.raw.default_styles);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                prefs.edit().putString("json_styles_raw", sb.toString()).apply();
            } catch (Exception e) {
                prefs.edit().putString("json_styles_raw", "[]").apply();
            }
        }
        if (!prefs.contains("json_kamus")) prefs.edit().putString("json_kamus", "[]").apply();
        if (!prefs.contains("json_macros")) prefs.edit().putString("json_macros", "[]").apply();

        if (!prefs.contains("chat_hist")) prefs.edit().putString("chat_hist", "[]").apply();

        if (!prefs.contains("custom_inst_list")) prefs.edit().putString("custom_inst_list", "[]").apply();



        checkKeyboardStatus();
        handleOpenToolIntent(getIntent());

        // Pemeriksaan rilis GitHub ditunda sedikit agar tidak bertabrakan dengan dialog izin awal.
        githubUpdateManager = new GitHubUpdateManager(this);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                if (!isFinishing() && githubUpdateManager != null) {
                    githubUpdateManager.checkForUpdates();
                }
            }
        }, 2500L);

        AuthManager.syncUserFromServer(this, false, new AuthManager.SyncCallback() {
            @Override public void onSynced(AuthManager.AccountStatus status) {
                if (!isFinishing()) {
                    checkKeyboardStatus();
                    renderActiveTab();
                    if (status != null && status.signedIn && !status.deviceAuthorized && !shownUnregDevicePromptThisSession) {
                        shownUnregDevicePromptThisSession = true;
                        AuthManager.showDeviceNotRegisteredDialog(MainActivity.this, status, MainActivity.this::renderActiveTab);
                    }
                }
            }
            @Override public void onError(String message) {}
        });

    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenToolIntent(intent);
    }

    private void handleOpenToolIntent(final Intent intent) {
        if (intent == null) return;
        final String openTool = intent.getStringExtra("open_tool");
        if (openTool == null || openTool.isEmpty()) return;
        intent.removeExtra("open_tool");
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing()) return;
                if ("business_add".equals(openTool)) {
                    ExtraToolsUI.showBusinessOrdersDialog(MainActivity.this, prefs, true);
                } else if ("business_orders".equals(openTool)) {
                    ExtraToolsUI.showBusinessOrdersDialog(MainActivity.this, prefs, false);
                } else if ("quotes".equals(openTool)) {
                    ExtraToolsUI.showQuotesDialog(MainActivity.this, prefs);
                }
            }
        }, 180L);
    }

    private void extractDefaultStyles() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String ASSET_GAYA_PATH = "gaya input suara final";
                    File destRoot = new File(getExternalFilesDir(null), ASSET_GAYA_PATH);
                    
                    // Hitung jumlah subfolder - jika < 10, berarti belum lengkap, extract ulang
                    int folderCount = 0;
                    if (destRoot.exists() && destRoot.listFiles() != null) {
                        for (File f : destRoot.listFiles()) {
                            if (f.isDirectory()) folderCount++;
                        }
                    }
                    
                    if (folderCount >= 10) {
                        // Sudah lengkap, skip
                        return;
                    }
                    
                    // Hapus dulu kalau ada tapi tidak lengkap
                    if (destRoot.exists()) {
                        deleteRecursive(destRoot);
                    }
                    destRoot.mkdirs();
                    
                    // Copy dari assets
                    copyAssetFolder(ASSET_GAYA_PATH, destRoot.getAbsolutePath());
                    
                    // Hitung hasil
                    final int hasil = destRoot.listFiles() != null ? destRoot.listFiles().length : 0;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this,
                                hasil + " folder gaya berhasil disiapkan!", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this,
                                "Gagal extract gaya: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void copyAssetFolder(String assetPath, String destPath) throws Exception {
        android.content.res.AssetManager am = getAssets();
        String[] list = am.list(assetPath);
        if (list == null || list.length == 0) {
            // Ini file, copy langsung
            copyAssetFile(assetPath, destPath);
            return;
        }
        // Ini folder
        File destDir = new File(destPath);
        if (!destDir.exists()) destDir.mkdirs();
        for (String item : list) {
            copyAssetFolder(assetPath + "/" + item, destPath + "/" + item);
        }
    }

    private void copyAssetFile(String assetPath, String destPath) throws Exception {
        android.content.res.AssetManager am = getAssets();
        try (InputStream in = am.open(assetPath);
             OutputStream out = new FileOutputStream(destPath)) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }


    private boolean shownUnregDevicePromptThisSession = false;

    @Override
    protected void onStart() {
        super.onStart();
        if (AuthManager.isSignedIn()) {
            AuthManager.startRealtimePremiumGuard(this, "main", () -> {
                if (!isFinishing()) {
                    checkKeyboardStatus();
                    renderActiveTab();
                }
            });
        }
    }

    @Override
    protected void onStop() {
        AuthManager.stopRealtimePremiumGuard("main");
        super.onStop();
    }

    @Override

    protected void onResume() {
        super.onResume();
        if (!AuthManager.isSignedIn()) {
            AuthManager.openLoginScreen(this);
            finish();
            return;
        }
        checkKeyboardStatus();
        OfficeUtils.ensureAllWorkspaceFolders(this);
        if (githubUpdateManager != null) {
            githubUpdateManager.handleResume();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            checkKeyboardStatus();
        }
    }



    /** Minta izin runtime lengkap di setup awal (mikrofon, storage/media, notifikasi). */
    private void requestAllRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            java.util.ArrayList<String> need = new java.util.ArrayList<>();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.RECORD_AUDIO);
            }
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    need.add(Manifest.permission.POST_NOTIFICATIONS);
                }
                if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    need.add(Manifest.permission.READ_MEDIA_AUDIO);
                }
            } else {
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                }
            }
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[0]), 123);
            }
            // Izin tampil di atas aplikasi lain (untuk penanda "Merekam" saat WA call)
            try {
                if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
                    // Tidak auto-popup tiap buka; tombol di menu Audio mengarahkan user.
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /** Cek apakah app dikecualikan dari optimasi baterai. */
    private boolean isIgnoringBatteryOptimizations() {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return true;
            return pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return true;
        }
    }

    /** Cek apakah AccessibilityService app ini sudah diaktifkan user. */
    private boolean isAccessibilityServiceEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            String expected = getPackageName() + "/" + SmartReplyAccessibilityService.class.getName();
            // Format bisa "pkg/class" atau "pkg/.Class"
            return enabled.contains(expected)
                    || enabled.contains(getPackageName() + "/.SmartReplyAccessibilityService")
                    || enabled.contains(getPackageName() + "/com.annas.SmartReplyAccessibilityService");
        } catch (Throwable t) {
            return false;
        }
    }

    private void openBatteryOptimizationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {}
        }
    }

    private void checkKeyboardStatus() {

        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        boolean isEnabled = false;

        for (android.view.inputmethod.InputMethodInfo imi : imm.getEnabledInputMethodList()) {

            if (imi.getPackageName().equals(getPackageName())) {

                isEnabled = true;

                break;

            }

        }

        

        if (!isEnabled) {
            mainUiMounted = false;
            showSetupLayout(1);
            return;
        }

        String defaultIme = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);

        if (defaultIme == null || !defaultIme.contains(getPackageName())) {
            mainUiMounted = false;
            showSetupLayout(2);
            return;
        }

        // Langkah 3: optimasi baterai (agar keyboard/JOS tidak dimatikan sistem)
        if (!prefs.getBoolean("setup_battery_done", false) && !isIgnoringBatteryOptimizations()) {
            mainUiMounted = false;
            showSetupLayout(3);
            return;
        }

        // Langkah 4: pengingat aksesibilitas Smart Reply (opsional, sekali)
        if (!prefs.getBoolean("setup_a11y_hint_done", false)) {
            mainUiMounted = false;
            showSetupLayout(4);
            return;
        }

        if (!mainUiMounted || contentHost == null) {
            buildMainMenu();
        } else if (subPageStack.isEmpty()) {
            renderActiveTab();
        }
    }



    private void showSetupLayout(final int step) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 100, 60, 60);
        layout.setBackgroundColor(UiKit.bg(prefs));
        layout.setGravity(Gravity.CENTER);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Yuk, Siapin Keyboard");
        tvTitle.setTextSize(26);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(UiKit.ACCENT_OFFICE);
        tvTitle.setGravity(Gravity.CENTER);

        TextView tvDesc = new TextView(this);
        tvDesc.setGravity(Gravity.CENTER);
        tvDesc.setTextSize(16);
        tvDesc.setPadding(0, 40, 0, 80);
        tvDesc.setTextColor(UiKit.textPrimary(prefs));

        Button btnAction = new Button(this);
        UiKit.styleButton(this, btnAction, UiKit.ACCENT_AI);

        Button btnSkip = new Button(this);
        UiKit.styleButton(this, btnSkip, UiKit.ACCENT_SYSTEM);
        btnSkip.setVisibility(View.GONE);

        if (step == 1) {
            tvDesc.setText("Langkah 1/4: Aktifin keyboard ini lewat Pengaturan HP biar bisa dipakai.");
            btnAction.setText("AKTIFIN KEYBOARD");
            btnAction.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
                }
            });
        } else if (step == 2) {
            tvDesc.setText("Langkah 2/4: Keyboard sudah aktif. Pilih \"Keyboard AI by Anas\" sebagai keyboard utama.");
            btnAction.setText("PILIH KEYBOARD UTAMA");
            btnAction.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.showInputMethodPicker();
                }
            });
        } else if (step == 3) {
            tvDesc.setText("Langkah 3/4: Matikan optimasi baterai untuk aplikasi ini.\n\nTanpa ini, sistem bisa mematikan keyboard / Smart Reply saat hemat daya. Tekan tombol lalu pilih Izinkan.");
            btnAction.setText("IZINKAN JALAN DI LATAR (BATERAI)");
            btnAction.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    openBatteryOptimizationSettings();
                    // Tandai sudah ditawarkan; user bisa skip kalau vendor memblokir intent
                    prefs.edit().putBoolean("setup_battery_done", true).apply();
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override public void run() { checkKeyboardStatus(); }
                    }, 800);
                }
            });
            btnSkip.setVisibility(View.VISIBLE);
            btnSkip.setText("LEWATI (NANTI SAJA)");
            btnSkip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putBoolean("setup_battery_done", true).apply();
                    checkKeyboardStatus();
                }
            });
        } else {
            // step 4: aksesibilitas Smart Reply
            tvDesc.setText("Langkah 4/4: (Opsional) Aktifkan layanan aksesibilitas Smart Reply agar bisa baca konteks chat.\n\nJuga disarankan: izinkan Autostart di pengaturan HP (Xiaomi/OPPO/Vivo/dll) supaya tidak mudah dimatikan sistem.");
            btnAction.setText("BUKA AKSESIBILITAS");
            btnAction.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Throwable ignored) {}
                    prefs.edit().putBoolean("setup_a11y_hint_done", true).apply();
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override public void run() { checkKeyboardStatus(); }
                    }, 600);
                }
            });
            btnSkip.setVisibility(View.VISIBLE);
            btnSkip.setText("LEWATI");
            btnSkip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putBoolean("setup_a11y_hint_done", true).apply();
                    checkKeyboardStatus();
                }
            });
        }

        layout.addView(tvTitle);
        layout.addView(tvDesc);
        layout.addView(btnAction);
        if (btnSkip.getVisibility() == View.VISIBLE) {
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sp.topMargin = 24;
            btnSkip.setLayoutParams(sp);
            layout.addView(btnSkip);
        }
        setContentView(layout);
    }



    private String getApiKeyForProviderName(String provider) {
        return AiEngine.resolveApiKey(this, prefs, provider);
    }

    private String getSmartApiSummary() {
        String provider = prefs.getString("selectedProvider", "Google Gemini");
        String key = getApiKeyForProviderName(provider);
        String keyState;
        if ("Copilot External".equals(provider)) {
            keyState = "Gratis Siap Pakai";
        } else if (!key.isEmpty()) {
            keyState = "Kunci Siap";
        } else {
            String[] known = {"Google Gemini", "Grok (xAI)", "OpenAI", "Groq (Cepat)", "DeepSeek", "OpenRouter", "Cohere"};
            String detectedAlt = null;
            for (String p : known) {
                if (!p.equals(provider) && !getApiKeyForProviderName(p).isEmpty()) {
                    detectedAlt = p;
                    break;
                }
            }
            keyState = detectedAlt != null ? "Kunci Kosong • Terdeteksi kunci " + detectedAlt : "Kunci Belum Diisi";
        }
        String stt = prefs.getString("whisperProvider", "Google Gemini");
        return provider + " (" + keyState + ") • STT: " + stt;
    }

    private String getSmartModeSummary() {
        boolean isBraille = prefs.getBoolean("braille_default_mode", false);
        String script = prefs.getString("keyboard_script", "latin");
        String layoutLabel;
        if (isBraille) {
            layoutLabel = "arabic".equals(script) ? "Braille Arab 6-Titik" : "Braille Latin 6-Titik";
        } else {
            layoutLabel = "arabic".equals(script) ? "QWERTY Arab" : "QWERTY Latin";
        }
        String touchMode = prefs.getString("akses_ngetik_mode", "Angkat Jari (Standar)");
        boolean isDbl = touchMode.contains("Ketuk Ganda");
        String imeDefault = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean imeReady = imeDefault != null && imeDefault.contains(getPackageName());
        return layoutLabel + " • " + (isDbl ? "Ketuk Ganda" : "Angkat Jari") + (imeReady ? "" : " • IME Belum Dipilih");
    }

    private String getSmartStyleSummary() {
        String gaya = prefs.getString("gaya_aktif_nama", "Standar");
        if (prefs.getBoolean("custom_inst_enabled", false)) {
            String inst = prefs.getString("selected_inst", "");
            if (!inst.isEmpty()) return gaya + " + Instruksi: " + inst;
            return gaya + " + Instruksi Manual";
        }
        return gaya;
    }

    private boolean isThisKeyboardDefault() {
        try {
            String imeDefault = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            return imeDefault != null && imeDefault.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void buildMainMenu() {
        UiKit.applySystemBars(this, prefs);
        subPageStack.clear();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.bg(prefs));

        // Top Header Bar
        LinearLayout headerBar = new LinearLayout(this);
        headerBar.setOrientation(LinearLayout.HORIZONTAL);
        headerBar.setGravity(Gravity.CENTER_VERTICAL);
        headerBar.setBackgroundColor(UiKit.surface(prefs));
        headerBar.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 12), UiKit.dp(this, 16), UiKit.dp(this, 12));

        headerTitleView = new TextView(this);
        headerTitleView.setText("Keyboard AI by Anas");
        headerTitleView.setTextSize(18);
        headerTitleView.setTypeface(null, Typeface.BOLD);
        headerTitleView.setTextColor(UiKit.textPrimary(prefs));
        headerTitleView.setSingleLine(true);
        headerTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headerBar.addView(headerTitleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final boolean dark = UiKit.isDark(prefs);
        headerThemeBtn = UiKit.makePillButton(this, dark ? "Terang" : "Gelap",
                UiKit.surfaceAlt(prefs), UiKit.textPrimary(prefs), v -> {
                    prefs.edit().putString("ui_theme", dark ? "Light" : "Dark").apply();
                    buildMainMenu();
                });
        headerThemeBtn.setTextSize(12);
        headerThemeBtn.setContentDescription("Ubah tema tampilan aplikasi ke " + (dark ? "Terang" : "Gelap"));
        headerBar.addView(headerThemeBtn);
        root.addView(headerBar);

        View topDivider = new View(this);
        topDivider.setBackgroundColor(UiKit.border(prefs));
        root.addView(topDivider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 1)));

        // Host Konten (Tab atau Sub-Halaman)
        contentHost = new android.widget.FrameLayout(this);
        root.addView(contentHost, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom 5-Tab Bar + Opsi Per Halaman (diangkat aman di atas tombol Kembali HP)
        View tabDivider = new View(this);
        tabDivider.setBackgroundColor(UiKit.border(prefs));
        tabBarContainer = new LinearLayout(this);
        tabBarContainer.setOrientation(LinearLayout.VERTICAL);
        tabBarContainer.setBackgroundColor(UiKit.surface(prefs));
        tabBarContainer.addView(tabDivider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 1)));

        LinearLayout pageNavBar = new LinearLayout(this);
        pageNavBar.setOrientation(LinearLayout.HORIZONTAL);
        pageNavBar.setGravity(Gravity.CENTER_VERTICAL);
        pageNavBar.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 6), UiKit.dp(this, 10), UiKit.dp(this, 2));

        tabPageIndicator = new TextView(this);
        tabPageIndicator.setGravity(Gravity.CENTER);
        tabPageIndicator.setTextSize(11);
        tabPageIndicator.setTextColor(UiKit.textMuted(prefs));
        tabPageIndicator.setSingleLine(true);
        tabPageIndicator.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tabPageIndicator.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 4), UiKit.dp(this, 8), UiKit.dp(this, 4));
        tabPageIndicator.setContentDescription("Ketuk untuk lompat langsung ke halaman tab, atau geser kiri dan kanan");
        tabPageIndicator.setOnClickListener(v -> {
            String[] jumpItems = new String[TAB_NAMES.length];
            for (int i = 0; i < TAB_NAMES.length; i++) {
                jumpItems[i] = "Halaman " + (i + 1) + ": " + TAB_NAMES[i] + (i == activeTab ? " (Aktif)" : "");
            }
            showListDialog("Lompat ke Halaman", jumpItems, (d, which) -> selectTab(which));
        });

        pageNavBar.addView(tabPageIndicator, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabBarContainer.addView(pageNavBar);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        int tpH = UiKit.dp(this, 8);
        int tpV = UiKit.dp(this, 6);
        tabRow.setPadding(tpH, tpV, tpH, tpV);

        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int tabIdx = i;
            Button tb = new Button(this);
            tb.setText(TAB_NAMES[i]);
            tb.setAllCaps(false);
            tb.setTextSize(11);
            tb.setTypeface(null, Typeface.BOLD);
            tb.setSingleLine(true);
            tb.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 10), UiKit.dp(this, 2), UiKit.dp(this, 10));
            tb.setContentDescription("Tab " + (i + 1) + " dari 5: " + TAB_NAMES[i]);
            tb.setOnClickListener(v -> selectTab(tabIdx));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) tlp.leftMargin = UiKit.dp(this, 4);
            tabRow.addView(tb, tlp);
            tabButtons[i] = tb;
        }
        tabBarContainer.addView(tabRow);
        // Ruang aman di bawah tab agar tidak mepet tombol Back / gesture bar sistem
        final int baseBottomPad = UiKit.dp(this, 24);
        tabBarContainer.setPadding(0, 0, 0, baseBottomPad);
        if (Build.VERSION.SDK_INT >= 20) {
            tabBarContainer.setOnApplyWindowInsetsListener((v, insets) -> {
                int sysBot = insets.getSystemWindowInsetBottom();
                v.setPadding(0, 0, 0, baseBottomPad + Math.max(0, sysBot));
                return insets;
            });
        }
        root.addView(tabBarContainer);

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 20) tabBarContainer.requestApplyInsets();
        mainUiMounted = true;
        selectTab(activeTab);
    }

    private void selectTab(int tabIdx) {
        selectTabInternal(tabIdx, true);
    }

    private void selectTabInternal(int tabIdx, boolean recordHistory) {
        if (tabIdx < 0 || tabIdx >= TAB_NAMES.length) tabIdx = 0;
        if (recordHistory && mainUiMounted && tabIdx != activeTab) {
            tabHistory.add(activeTab);
        }
        activeTab = tabIdx;
        subPageStack.clear();
        if (tabBarContainer != null) tabBarContainer.setVisibility(View.VISIBLE);
        if (headerThemeBtn != null) headerThemeBtn.setVisibility(View.VISIBLE);
        if (headerTitleView != null) {
            headerTitleView.setText((tabIdx == 0 ? "Setelan Keyboard" : TAB_NAMES[tabIdx]) + " (" + (tabIdx + 1) + "/5)");
        }
        if (tabPageIndicator != null) {
            tabPageIndicator.setText("Halaman " + (tabIdx + 1) + " / 5: " + TAB_NAMES[tabIdx] + " • Geser Kiri/Kanan atau Ketuk Tab");
        }
        updateTabButtonsVisual();
        renderActiveTab();
        OfficeUtils.announce(headerTitleView, "Halaman " + (tabIdx + 1) + " dari 5: " + TAB_NAMES[tabIdx]);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (mainUiMounted && subPageStack.isEmpty() && ev.getPointerCount() == 1) {
            int action = ev.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                tabSwipeStartX = ev.getX();
                tabSwipeStartY = ev.getY();
                tabSwipeTracking = true;
                tabSwipeTriggered = false;
            } else if (action == android.view.MotionEvent.ACTION_MOVE && tabSwipeTracking && !tabSwipeTriggered) {
                float dx = ev.getX() - tabSwipeStartX;
                float dy = ev.getY() - tabSwipeStartY;
                float thresh = UiKit.dp(this, 65);
                if (Math.abs(dx) > thresh && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                    tabSwipeTriggered = true;
                    android.view.MotionEvent cancelEv = android.view.MotionEvent.obtain(ev);
                    cancelEv.setAction(android.view.MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancelEv);
                    cancelEv.recycle();
                    if (dx < 0 && activeTab < TAB_NAMES.length - 1) {
                        selectTab(activeTab + 1);
                    } else if (dx > 0 && activeTab > 0) {
                        selectTab(activeTab - 1);
                    }
                    return true;
                }
            } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                tabSwipeTracking = false;
                tabSwipeTriggered = false;
            }
        } else {
            tabSwipeTracking = false;
            tabSwipeTriggered = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void updateTabButtonsVisual() {
        int inactiveBg = UiKit.surfaceAlt(prefs);
        int inactiveTxt = UiKit.textMuted(prefs);
        for (int i = 0; i < tabButtons.length; i++) {
            Button b = tabButtons[i];
            if (b == null) continue;
            boolean sel = (i == activeTab);
            b.setBackground(UiKit.roundedBox(sel ? UiKit.ACCENT_AI : inactiveBg, Color.TRANSPARENT, UiKit.dp(this, 10), 0));
            b.setTextColor(sel ? Color.WHITE : inactiveTxt);
            b.setSelected(sel);
        }
    }

    private void renderActiveTab() {
        if (contentHost == null || !subPageStack.isEmpty()) return;
        contentHost.removeAllViews();
        View tabView;
        if (activeTab == 0) tabView = buildTab0Keyboard();
        else if (activeTab == 1) tabView = buildTab1Ai();
        else if (activeTab == 2) tabView = buildTab2Coding();
        else if (activeTab == 3) tabView = buildTab3Office();
        else tabView = buildTab4More();
        contentHost.addView(tabView);
    }

    private void pushSubPage(String title, View view) {
        if (contentHost == null) return;
        subPageStack.add(new SubPageEntry(title, view));
        if (tabBarContainer != null) tabBarContainer.setVisibility(View.GONE);
        if (headerThemeBtn != null) headerThemeBtn.setVisibility(View.GONE);
        if (headerTitleView != null) headerTitleView.setText(title);
        contentHost.removeAllViews();
        contentHost.addView(view);
        OfficeUtils.announce(headerTitleView, title);
    }

    private void popSubPage() {
        if (subPageStack.isEmpty()) return;
        subPageStack.remove(subPageStack.size() - 1);
        if (subPageStack.isEmpty()) {
            if (tabBarContainer != null) tabBarContainer.setVisibility(View.VISIBLE);
            if (headerThemeBtn != null) headerThemeBtn.setVisibility(View.VISIBLE);
            if (headerTitleView != null) {
                headerTitleView.setText(activeTab == 0 ? "Setelan Keyboard" : TAB_NAMES[activeTab] + " — Anas");
            }
            renderActiveTab();
            OfficeUtils.announce(headerTitleView, TAB_NAMES[activeTab]);
        } else {
            SubPageEntry top = subPageStack.get(subPageStack.size() - 1);
            if (headerTitleView != null) headerTitleView.setText(top.title);
            contentHost.removeAllViews();
            contentHost.addView(top.view);
            OfficeUtils.announce(headerTitleView, top.title);
        }
    }

    @Override
    public void onBackPressed() {
        if (!subPageStack.isEmpty()) {
            popSubPage();
            return;
        }
        while (!tabHistory.isEmpty()) {
            int prev = tabHistory.remove(tabHistory.size() - 1);
            if (prev != activeTab && prev >= 0 && prev < TAB_NAMES.length) {
                selectTabInternal(prev, false);
                return;
            }
        }
        if (activeTab != 0) {
            selectTabInternal(0, false);
            return;
        }
        super.onBackPressed();
    }

    private ScrollView wrapTabPage(LinearLayout container) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.bg(prefs));
        container.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12), UiKit.dp(this, 14), UiKit.dp(this, 16));
        sv.addView(container);
        return sv;
    }

    /** Menerapkan warna tema UiKit pada kontrol formulir lama agar selalu rapi & kontras di Dark/Light mode. */
    private void styleGeneratedControls(LinearLayout container) {
        if (container == null) return;
        int txt = UiKit.textPrimary(prefs);
        int muted = UiKit.textMuted(prefs);
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof Switch) {
                UiKit.styleSwitch(this, (Switch) child, prefs);
            } else if (child instanceof Button) {
                Button b = (Button) child;
                if (b.getBackground() instanceof android.graphics.drawable.ColorDrawable) continue;
                UiKit.styleButton(this, b, UiKit.ACCENT_SYSTEM);
            } else if (child instanceof EditText) {
                UiKit.styleEditText(this, (EditText) child, prefs);
            } else if (child instanceof TextView) {
                TextView tv = (TextView) child;
                String t = tv.getText() == null ? "" : tv.getText().toString();
                if (t.startsWith("Penjelasan:") || tv.getTextSize() <= 13 * getResources().getDisplayMetrics().density) {
                    tv.setTextColor(muted);
                } else if (tv.getCurrentTextColor() == Color.GRAY || tv.getCurrentTextColor() == Color.DKGRAY
                        || tv.getCurrentTextColor() == Color.BLACK || tv.getCurrentTextColor() == Color.LTGRAY) {
                    tv.setTextColor(txt);
                }
            } else if (child instanceof LinearLayout) {
                styleGeneratedControls((LinearLayout) child);
            }
        }
    }

    // =========================================================================
    // TAB 1: SETELAN KEYBOARD (Langsung kebuka pertama kali)
    // =========================================================================
    private View buildTab0Keyboard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        AuthManager.AccountStatus accStatus = AuthManager.getCachedStatus(this);
        String accountSummary = accStatus.email + " • " + accStatus.getReadablePlanLabel();

        LinearLayout heroCard = UiKit.makeCard(this, prefs);
        TextView tvSummary = new TextView(this);
        tvSummary.setText(
                "Akun: " + accountSummary
                + "\nMode: " + getSmartModeSummary()
                + "\nAPI: " + getSmartApiSummary()
                + "\nGaya: " + getSmartStyleSummary() + " • Tema KB: " + prefs.getString("keyboard_theme", "Dark")
        );
        tvSummary.setTextSize(13);
        tvSummary.setLineSpacing(UiKit.dp(this, 3), 1.08f);
        tvSummary.setTextColor(UiKit.textMuted(prefs));
        tvSummary.setPadding(0, 0, 0, UiKit.dp(this, 10));
        heroCard.addView(tvSummary);

        LinearLayout heroActions = new LinearLayout(this);
        heroActions.setOrientation(LinearLayout.HORIZONTAL);
        Button btnQuickTest = UiKit.makePillButton(this, "Tes Ketik", UiKit.ACCENT_KB, Color.WHITE,
                v -> CommunityToolsUI.showKeyboardTesterDialog(MainActivity.this, prefs));
        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lpLeft.rightMargin = UiKit.dp(this, 6);
        heroActions.addView(btnQuickTest, lpLeft);

        Button btnPickIme = UiKit.makePillButton(this,
                isThisKeyboardDefault() ? "Pilih Keyboard" : "Aktifkan Keyboard",
                isThisKeyboardDefault() ? UiKit.surfaceAlt(prefs) : UiKit.ACCENT_WARM,
                isThisKeyboardDefault() ? UiKit.textPrimary(prefs) : Color.WHITE,
                v -> {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showInputMethodPicker();
                });
        heroActions.addView(btnPickIme, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        heroCard.addView(heroActions);
        box.addView(heroCard);

        box.addView(UiKit.makeSectionLabel(this, "ATUR UKURAN, TAMPILAN & KEYBOARD", UiKit.ACCENT_KB));
        appendKeyboardSettingsCategories(box);

        return wrapTabPage(box);
    }

    // =========================================================================
    // TAB 2: ASISTEN AI
    // =========================================================================
    private View buildTab1Ai() {
        final boolean isPro = AuthManager.isPremiumActive(this);
        final String lock = isPro ? "" : " (Premium)";
        final String bonusSummary = AuthManager.getBonusSummaryLabel(this);
        final String grokBonusNote = !bonusSummary.isEmpty() ? (" • Bonus Admin: " + bonusSummary) : "";

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(UiKit.makeSectionLabel(this, "OTAK, API & GAYA AI", UiKit.ACCENT_AI));
        box.addView(UiKit.makeMenuButton(this, "API Key, Provider & Model AI" + lock,
                "Atur kunci API, provider, model & cek saldo" + grokBonusNote,
                UiKit.ACCENT_AI, prefs, v -> {
                    if (requirePremiumForAi("API & Pengaturan Otak AI")) showApiDialog();
                }));
        box.addView(UiKit.makeMenuButton(this, "Logika & Parameter AI" + lock,
                "Kreativitas, batas token coding panjang, penalaran & nada bicara",
                UiKit.ACCENT_AI_ALT, prefs, v -> {
                    if (requirePremiumForAi("Logika & Aturan AI")) showLogicDialog();
                }));
        box.addView(UiKit.makeMenuButton(this, "Gaya Ngobrol & Suara AI" + lock,
                "Pilih atau kelola koleksi gaya bahasa & instruksi manual",
                UiKit.ACCENT_WARM, prefs, v -> {
                    if (requirePremiumForAi("Gaya Ngobrol AI")) showStyleManager();
                }));

        box.addView(UiKit.makeSectionLabel(this, "FITUR AI STUDIO", UiKit.ACCENT_KB));
        box.addView(UiKit.makeMenuButton(this, "Chat AI & Lampirkan Berkas / Folder" + lock,
                "Ngobrol bebas, bedah kode, lampirkan berkas/folder via File Manager",
                UiKit.ACCENT_AI, prefs, v -> {
                    if (requirePremiumForAi("Chat AI & Lampiran Berkas")) {
                        showChatDialog("Chat AI — Lampirkan Berkas / Folder", "[]");
                    }
                }));
        box.addView(UiKit.makeMenuButton(this, "Gemini Text-to-Speech + Video Status WA" + lock,
                "Teks -> WAV ekspresif + konversi video Status WA (musik & potong urut)",
                UiKit.ACCENT_OFFICE, prefs, v -> {
                    if (requirePremiumForAi("Gemini Text-to-Speech Studio")) {
                        startActivity(new Intent(this, GeminiTtsActivity.class));
                    }
                }));
        box.addView(UiKit.makeMenuButton(this, "AI Translator Studio & Setelan" + lock,
                "Terjemahkan teks, suara, atau berkas + setelan translator keyboard",
                UiKit.ACCENT_KB_ALT, prefs, v -> {
                    if (!requirePremiumForAi("AI Translator Studio")) return;
                    LinearLayout sub = new LinearLayout(this);
                    sub.setOrientation(LinearLayout.VERTICAL);
                    sub.addView(UiKit.makeMenuButton(this, "Buka AI Translator Studio Lengkap",
                            "Terjemahkan teks panjang, suara, dan berkas", UiKit.ACCENT_AI, prefs,
                            vv -> startActivity(new Intent(this, TranslatorStudioActivity.class))));
                    sub.addView(UiKit.makeMenuButton(this, "Setelan Translator Saat Mengetik",
                            "Atur otomatis terjemahkan, bahasa asal & tujuan", UiKit.ACCENT_KB, prefs,
                            vv -> showTranslatorDialog()));
                    pushSubPage("Translator AI", wrapTabPage(sub));
                }));
        box.addView(UiKit.makeMenuButton(this, "Asisten Tulis, Surat & Bisnis AI" + lock,
                "10 mode tulis cepat, surat resmi, balasan pelanggan & materi promosi",
                UiKit.ACCENT_WARM, prefs, v -> {
                    if (!requirePremiumForAi("Asisten Tulis & Bisnis AI")) return;
                    LinearLayout sub = new LinearLayout(this);
                    sub.setOrientation(LinearLayout.VERTICAL);
                    sub.addView(UiKit.makeMenuButton(this, "Asisten Tulis AI Cepat (10 Preset)",
                            "Buat artikel, surat resmi, email & bacakan TTS", UiKit.ACCENT_WARM, prefs,
                            vv -> CommunityToolsUI.showAiWriteAssistant(MainActivity.this, prefs, target -> startChatVoiceInput(target))));
                    sub.addView(UiKit.makeMenuButton(this, "AI Asisten Bisnis & CS Toko",
                            "Rekap pesanan, penagihan ramah & caption promosi", UiKit.ACCENT_OFFICE, prefs,
                            vv -> AiPowerToolsUI.showBusinessAiDialog(MainActivity.this, prefs)));
                    pushSubPage("Asisten Tulis & Bisnis AI", wrapTabPage(sub));
                }));
        box.addView(UiKit.makeMenuButton(this, "AI Vision (Foto, Struk & Screenshot Error)" + lock,
                "Deskripsi foto ramah tunanetra, scan struk ke CSV & baca screenshot error",
                UiKit.ACCENT_AI_ALT, prefs, v -> {
                    if (requirePremiumForAi("AI Vision")) AiPowerToolsUI.launchVisionChooser(this);
                }));
        box.addView(UiKit.makeMenuButton(this, "AI Notulen Rapat & Ringkas Audio" + lock,
                "Ringkas catatan atau rekaman audio jadi Ringkasan, Keputusan & To-Do",
                UiKit.ACCENT_KB, prefs, v -> {
                    if (requirePremiumForAi("AI Notulen & Ringkas Audio")) AiPowerToolsUI.showMeetingMinutesDialog(this, prefs);
                }));
        box.addView(UiKit.makeMenuButton(this, "Memori Profil & Agen Kustom AI" + lock,
                "Atur memori pribadi AI & buat persona agen spesialis sendiri",
                UiKit.ACCENT_SYSTEM, prefs, v -> {
                    if (!requirePremiumForAi("Memori & Agen Kustom AI")) return;
                    LinearLayout sub = new LinearLayout(this);
                    sub.setOrientation(LinearLayout.VERTICAL);
                    sub.addView(UiKit.makeMenuButton(this, "Atur Memori & Profil Pribadi AI",
                            "Buat AI selalu ingat gaya dan preferensi lu", UiKit.ACCENT_AI, prefs,
                            vv -> AiPowerToolsUI.showMemoryDialog(this, prefs)));
                    sub.addView(UiKit.makeMenuButton(this, "Studio Agen Kustom",
                            "Buat dan jalankan agen spesialis sesuai kebutuhan", UiKit.ACCENT_KB, prefs,
                            vv -> AiPowerToolsUI.showCustomAgentsDialog(this, prefs, (t, sys) -> showChatDialog(t, "[]", sys))));
                    pushSubPage("Memori & Agen Kustom AI", wrapTabPage(sub));
                }));

        return wrapTabPage(box);
    }

    // =========================================================================
    // TAB 3: CODING & BERKAS
    // =========================================================================
    private View buildTab2Coding() {
        final boolean isPro = AuthManager.isPremiumActive(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(UiKit.makeSectionLabel(this, "CODING & SCRIPT WORKSPACE", UiKit.ACCENT_AI));
        box.addView(UiKit.makeMenuButton(this, "Editor Kode & Agen AI" + (isPro ? "" : " (Agen Pro)"),
                "Edit .lua, .java, .json, .xml, cek kurung/blok Lua, folder proyek & AI Coding Agent",
                UiKit.ACCENT_AI, prefs, v -> startActivity(new Intent(this, CodeTextEditorActivity.class))));
        box.addView(UiKit.makeMenuButton(this, "File Manager Internal (100% Skrip)",
                "Jelajahi folder penyimpanan HP, buat folder, dan pilih berkas secara aksesibel",
                UiKit.ACCENT_KB, prefs, v -> OfficeUtils.launchFilePicker(this, PICK_DOKUMEN_CHAT, "all", "File Manager — Keyboard AI by Anas")));

        box.addView(UiKit.makeSectionLabel(this, "POTONGAN KODE & SNIPPET", UiKit.ACCENT_KB_ALT));
        box.addView(UiKit.makeMenuButton(this, "Papan Klip — Riwayat & Pin",
                "Salin ulang riwayat salinan atau sematkan script/teks penting agar aman",
                UiKit.ACCENT_KB_ALT, prefs, v -> showClipboardGallery()));
        box.addView(UiKit.makeMenuButton(this, "Template Pesan & Snippet Cepat",
                "Simpan template kode, salam, atau balasan siap tempel dari tombol petir keyboard",
                UiKit.ACCENT_OFFICE, prefs, v -> CommunityToolsUI.showQuickSnippetsDialog(MainActivity.this, prefs)));

        return wrapTabPage(box);
    }

    // =========================================================================
    // TAB 4: KANTOR (Word, PDF, Excel, PPT)
    // =========================================================================
    private View buildTab3Office() {
        final boolean isPro = AuthManager.isPremiumActive(this);
        final String lock = isPro ? "" : " (Premium)";
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(UiKit.makeSectionLabel(this, "DOKUMEN KANTOR & PEMBACA PDF", UiKit.ACCENT_OFFICE));
        box.addView(UiKit.makeMenuButton(this, "Microsoft Word Pro — Editor Dokumen",
                "Sunting DOCX/TXT/PDF, gambar ke DOCX/PDF, template & OCR Foto",
                UiKit.ACCENT_OFFICE, prefs, v -> startActivity(new Intent(MainActivity.this, DocumentStudioActivity.class))));
        box.addView(UiKit.makeMenuButton(this, "Keyboard PDF Anas — Pembaca PDF & OCR",
                "Baca PDF bergambar 15+ bahasa offline, gestur 1 jari, Baca Novel TTS & AI Bedah PDF",
                UiKit.ACCENT_KB_ALT, prefs, v -> startActivity(new Intent(MainActivity.this, PdfReaderActivity.class))));
        box.addView(UiKit.makeMenuButton(this, "Microsoft Excel Pro — Buku Kerja Multi-Sheet",
                "60x26 sel, 30+ rumus, pembacaan kolom/baris aksesibel, Rupiah & PDF/XLSX",
                UiKit.ACCENT_KB, prefs, v -> startActivity(new Intent(MainActivity.this, SpreadsheetStudioActivity.class))));
        box.addView(UiKit.makeMenuButton(this, "Microsoft PowerPoint Pro — Presentasi Aksesibel",
                "Buka/ekspor PPTX & catatan pembicara, navigasi tombol Volume & TTS",
                UiKit.ACCENT_WARM, prefs, v -> startActivity(new Intent(MainActivity.this, PresentationStudioActivity.class))));

        box.addView(UiKit.makeSectionLabel(this, "STUDIO MEDIA AUDIO & VIDEO PRO" + (isPro ? "" : " (KHUSUS PREMIUM)"), UiKit.ACCENT_AI));
        box.addView(UiKit.makeMenuButton(this, "Studio Editor Audio Pro (36 Fitur)" + lock,
                "36 Fitur Khusus (1 Fitur = 1 Layar), Audio ke Video MP4, Potong sambil Putar + Undo/Redo, Live Efek Film/Kartun/Ketawa & Backsound MP3 Asli, Geser Kiri/Kanan Mundur-Maju, EQ, Reverb Masjid & Status WA",
                UiKit.ACCENT_KB, prefs, v -> {
                    if (requirePremiumForAi("Studio Editor Audio Pro")) {
                        startActivity(new Intent(MainActivity.this, AudioEditorStudioActivity.class));
                    }
                }));
        box.addView(UiKit.makeMenuButton(this, "Studio Editor Video Pro (36 Fitur)" + lock,
                "36 Fitur Khusus (1 Fitur = 1 Layar), Video ke Audio MP3/WAV, Potong sambil Putar + Undo/Redo, Live Efek Film/Kartun/Ketawa & Backsound MP3 Asli, Geser Kiri/Kanan Mundur-Maju, Rasio 9:16 Blur & Status WA",
                UiKit.ACCENT_AI_ALT, prefs, v -> {
                    if (requirePremiumForAi("Studio Editor Video Pro")) {
                        startActivity(new Intent(MainActivity.this, VideoEditorStudioActivity.class));
                    }
                }));
        box.addView(UiKit.makeMenuButton(this, "Studio Gudang & Laboratorium Backsound / Efek Film",
                "98 MP3 Rekaman Asli: Islami, Audio 3D/8D, Piano, Jazz, Rock, Jedag-Jedug, Horor, Sinematik, Kartun Kocak, Suara Ketawa & Efek Film. Dengarkan, Pilih, atau Hapus di Laboratorium",
                UiKit.ACCENT_WARM, prefs, v -> StudioGudangAudioManager.showStudioGudangMainDialog(MainActivity.this, prefs, null)));

        return wrapTabPage(box);
    }

    // =========================================================================
    // TAB 5: LAINNYA (Audio STT, Utilitas, Quote/Bisnis, Sistem, Akun)
    // =========================================================================
    private View buildTab4More() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(UiKit.makeSectionLabel(this, "SUARA, KAMUS & AKSESIBILITAS", UiKit.ACCENT_AI_ALT));
        box.addView(UiKit.makeMenuButton(this, "Audio, Google Speech Recognition & Suara Pelan",
                "Mode penguat suara pelan (AGC), bahasa mic otomatis ID/EN, tes mic & efek suara",
                UiKit.ACCENT_KB, prefs, v -> showAudioDialog()));
        box.addView(UiKit.makeMenuButton(this, "Kamus Anti Typo, Makro & Smart Reply",
                "Kamus perbaikan otomatis, singkatan makro, jalur teks & gaya Wingman",
                UiKit.ACCENT_AI, prefs, v -> showUtilityDialog()));
        box.addView(UiKit.makeMenuButton(this, "Panduan Keyboard Braille",
                "Tutorial mengetik 6 titik, chord & gestur lengkap khusus tunanetra",
                UiKit.ACCENT_KB_ALT, prefs, v -> showBrailleTutorialDialog()));
        box.addView(UiKit.makeMenuButton(this, "Profil Pengaturan Cepat",
                "Terapkan profil Awas, Tunanetra QWERTY, atau Braille Pro dalam 1 ketuk",
                UiKit.ACCENT_AI_ALT, prefs, v -> CommunityToolsUI.showQuickProfilesDialog(MainActivity.this, prefs, this::renderActiveTab)));

        box.addView(UiKit.makeSectionLabel(this, "QUOTE, BISNIS & PRODUKTIVITAS", UiKit.ACCENT_WARM));
        box.addView(UiKit.makeMenuButton(this, "Quote (18.750+), Bisnis, Brankas & Alat Teks",
                "Kata mutiara lengkap, catat pesanan bisnis, brankas identitas, konverter & timer",
                UiKit.ACCENT_WARM, prefs, v -> showCategoryLifestyleToolsDialog()));

        box.addView(UiKit.makeSectionLabel(this, "SISTEM, CADANGAN & AKUN", UiKit.ACCENT_SYSTEM));
        box.addView(UiKit.makeMenuButton(this, "Cadangkan & Pulihkan Data Lengkap",
                "Simpan seluruh setelan, kamus, makro, snippet & gaya ke penyimpanan HP",
                UiKit.ACCENT_SYSTEM, prefs, v -> CommunityToolsUI.showBackupRestoreDialog(MainActivity.this, prefs, this::buildMainMenu)));
        box.addView(UiKit.makeMenuButton(this, "Statistik Mengetik Lokal",
                "Lihat ringkasan karakter, kata & penggunaan makro secara privat di HP",
                UiKit.ACCENT_OFFICE, prefs, v -> CommunityToolsUI.showStatsDialog(MainActivity.this, prefs)));
        box.addView(UiKit.makeMenuButton(this, "Cek Pembaruan Aplikasi (GitHub)",
                "Periksa dan pasang rilis versi terbaru langsung dari GitHub",
                UiKit.ACCENT_AI, prefs, v -> { if (githubUpdateManager != null) githubUpdateManager.checkForUpdates(true); }));
        box.addView(UiKit.makeMenuButton(this, "Tentang Developer & Tutorial",
                "Profil Anugerah Anas, panduan lengkap & kontak dukungan",
                UiKit.ACCENT_SYSTEM, prefs, v -> showAboutAndTutorial()));
        box.addView(UiKit.makeMenuButton(this, "Akun Google & Status Premium",
                "Segarkan status paket Pro, info akun, atau keluar akun",
                UiKit.ACCENT_KB, prefs, v -> showAccountStatusDialog()));
        box.addView(UiKit.makeMenuButton(this, "Alat Bengkel (Perbaikan & Reset)",
                "Ekstrak ulang berkas gaya bawaan atau reset setelan pabrik",
                UiKit.ACCENT_DANGER, prefs, v -> showDebugDialog()));
        box.addView(UiKit.makeMenuButton(this, "Studio Gudang & Laboratorium Backsound / Efek Film",
                "Dengarkan langsung 98 MP3 asli (Islami, 3D/8D, Piano, Jazz, Rock, Jedag-Jedug, Horor, Sinematik, Kartun, Suara Ketawa & Efek Film) atau hapus audio yang tidak perlu lalu Simpan",
                UiKit.ACCENT_WARM, prefs, v -> StudioGudangAudioManager.showStudioGudangMainDialog(MainActivity.this, prefs, null)));

        return wrapTabPage(box);
    }

    private void showAccountStatusDialog() {
        final AuthManager.AccountStatus st = AuthManager.getCachedStatus(this);
        String msg = "Email Google: " + st.email + "\n"
                + "Nama: " + st.displayName + "\n"
                + "UID: " + st.uid + "\n"
                + "Device ID HP Ini: " + st.deviceId + "\n"
                + "Status Device: " + (st.deviceAuthorized ? "Terdaftar untuk Akun Ini" : "BELUM TERDAFTAR (Hubungi Admin)") + "\n"
                + "Status Paket: " + st.getReadablePlanLabel() + "\n\n"
                + "Catatan: Kunci API AI memakai kunci pribadi Anda. Status Premium sinkron real-time.";
        UiKit.dialog(this, prefs)
                .setTitle("Akun & Status AI Premium")
                .setMessage(msg)
                .setPositiveButton("Segarkan Status", (d, w) -> {
                    Toast.makeText(this, "Menyinkronkan status ke server...", Toast.LENGTH_SHORT).show();
                    AuthManager.syncUserFromServer(this, false, new AuthManager.SyncCallback() {
                        @Override public void onSynced(AuthManager.AccountStatus status) {
                            Toast.makeText(MainActivity.this, "Status: " + status.getReadablePlanLabel(), Toast.LENGTH_LONG).show();
                            renderActiveTab();
                        }
                        @Override public void onError(String message) {
                            Toast.makeText(MainActivity.this, "Gagal sinkron: " + message, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNeutralButton("Info Paket / Salin ID", (d, w) ->
                        AuthManager.showPremiumRequiredDialog(MainActivity.this, "Akses AI Lengkap", this::renderActiveTab))
                .setNegativeButton("Keluar Akun", (d, w) -> AuthManager.signOut(MainActivity.this, () -> {
                    AuthManager.openLoginScreen(MainActivity.this);
                    finish();
                }))
                .show();
    }

    private boolean requirePremiumForAi(String featureName) {
        if (AuthManager.isPremiumActive(this)) return true;
        AuthManager.showPremiumRequiredDialog(this, featureName, this::renderActiveTab);
        return false;
    }

    private TextView createHeader(String text, String hexColor) {
        return UiKit.makeHeader(this, text, Color.parseColor(hexColor), prefs);
    }

    private void showListDialog(String title, final String[] items, final DialogInterface.OnClickListener listener) {
        UiKit.dialog(this, prefs).setTitle(title).setItems(items, listener).show();
    }

    private void showCategoryLifestyleToolsDialog() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);

        ll.addView(UiKit.makeMenuButton(this, "Quote & Kata Mutiara (18.750+)",
                "15 kategori lengkap (Coding, Islami, Motivasi, Stoik, Inklusi, Inggris) + bagikan",
                UiKit.ACCENT_AI, prefs, v -> ExtraToolsUI.showQuotesDialog(MainActivity.this, prefs)));
        ll.addView(UiKit.makeMenuButton(this, "Mode Bisnis & Catat Pesanan",
                "Catat pemesan, barang/jasa, nominal Rp, status bayar (sinkron ke panel cepat keyboard)",
                UiKit.ACCENT_OFFICE, prefs, v -> ExtraToolsUI.showBusinessOrdersDialog(MainActivity.this, prefs, false)));
        ll.addView(UiKit.makeMenuButton(this, "Brankas Identitas Cepat",
                "Simpan Nama, No. HP, Email, Alamat & Rekening untuk disisipkan 1 ketuk",
                UiKit.ACCENT_KB, prefs, v -> ExtraToolsUI.showQuickIdentityVaultDialog(MainActivity.this, prefs)));
        ll.addView(UiKit.makeMenuButton(this, "Toolkit Teks, Terbilang & Kode",
                "BESAR/kecil/Judul, Terbilang Rupiah, rapikan spasi, hapus duplikat, Morse & Base64",
                UiKit.ACCENT_WARM, prefs, v -> ExtraToolsUI.showTextToolkitDialog(MainActivity.this, prefs)));
        ll.addView(UiKit.makeMenuButton(this, "Konverter Satuan, Umur & Hijriah",
                "Ubah Panjang, Berat, Suhu, Ukuran Data, hitung umur presisi & kalender Hijriah",
                UiKit.ACCENT_OFFICE, prefs, v -> ExtraToolsUI.showUnitAndDateToolsDialog(MainActivity.this, prefs)));
        ll.addView(UiKit.makeMenuButton(this, "Catatan Kilat, Fokus Timer & Sandi",
                "Catatan cepat tersimpan, timer fokus/Pomodoro bersuara TTS & generator password",
                UiKit.ACCENT_AI_ALT, prefs, v -> ExtraToolsUI.showQuickNotesAndTimerDialog(MainActivity.this, prefs)));
        ll.addView(UiKit.makeMenuButton(this, "Template Snippet Cepat",
                "Kelola daftar salam & kalimat balasan cepat yang tampil di keyboard",
                UiKit.ACCENT_KB_ALT, prefs, v -> CommunityToolsUI.showQuickSnippetsDialog(MainActivity.this, prefs)));

        pushSubPage("Quote, Bisnis & Produktivitas", wrapTabPage(ll));
    }

    private void showApiDialog() {
        ApiDialogUI.show(this, prefs, this::renderActiveTab);
    }
    private void showStyleManager() {
        AlertDialog.Builder builder = UiKit.dialog(this, prefs);
        builder.setTitle("Atur Gaya Ngobrol");
        android.widget.TextView styleIntro = new android.widget.TextView(this);
        styleIntro.setText("Pilih gaya biar hasil suara atau balasan AI punya nuansa yang lo mau. Contoh: pilih gaya santai untuk obrolan sehari-hari, atau gaya formal untuk pesan kerja.");
        styleIntro.setTextSize(13);
        styleIntro.setTextColor(UiKit.textMuted(prefs));
        styleIntro.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 6), UiKit.dp(this, 4), UiKit.dp(this, 10));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(UiKit.bg(prefs));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 16);
        layout.setPadding(p, p, p, p);

        layout.addView(createHeader("GAYA NGOBROL AI", "#FF9800"));
        layout.addView(styleIntro);

        final Button btnGayaAktif = new Button(this);
        btnGayaAktif.setText("GAYA AKTIF: " + prefs.getString("gaya_aktif_nama", "Standar"));
        UiKit.styleButton(this, btnGayaAktif, UiKit.ACCENT_WARM);
        layout.addView(btnGayaAktif);

        Button btnReset = new Button(this);
        btnReset.setText("RESET KE STANDAR (Tanpa Gaya)");
        UiKit.styleButton(this, btnReset, UiKit.ACCENT_SYSTEM);
        layout.addView(btnReset);
        btnReset.setOnClickListener(v -> {
            prefs.edit().putString("gaya_aktif_nama", "Standar").putString("gaya_aktif_prompt", "").apply();
            btnGayaAktif.setText("GAYA AKTIF: Standar");
            Toast.makeText(MainActivity.this, "Direset ke Standar!", Toast.LENGTH_SHORT).show();
        });

        Button btnPilihGaya = new Button(this);
        btnPilihGaya.setText("PILIH GAYA YANG LO MAU");
        UiKit.styleButton(this, btnPilihGaya, UiKit.ACCENT_AI);
        layout.addView(btnPilihGaya);
        btnPilihGaya.setOnClickListener(v -> showSelectStyleDialog(btnGayaAktif));

        Button btnKelola = new Button(this);
        btnKelola.setText("ATUR & BIKIN GAYA BARU");
        UiKit.styleButton(this, btnKelola, UiKit.ACCENT_KB);
        layout.addView(btnKelola);
        btnKelola.setOnClickListener(v -> showManageStyle());

        layout.addView(createHeader("INSTRUKSI KHUSUS MANUAL", "#009688"));
        final Switch swInst = new Switch(this);
        swInst.setText("Pake Instruksi Khusus Manual");
        swInst.setChecked(prefs.getBoolean("custom_inst_enabled", false));
        UiKit.styleSwitch(this, swInst, prefs);
        layout.addView(swInst);

        final TextView tvInst = new TextView(this);
        tvInst.setText("Instruksi Aktif: " + prefs.getString("selected_inst", "Kosong"));
        tvInst.setTextColor(UiKit.textMuted(prefs));
        tvInst.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 10));
        layout.addView(tvInst);

        Button btnAturInstruksi = new Button(this);
        btnAturInstruksi.setText("ATUR INSTRUKSI LU");
        UiKit.styleButton(this, btnAturInstruksi, UiKit.ACCENT_AI_ALT);
        layout.addView(btnAturInstruksi);
        btnAturInstruksi.setOnClickListener(v -> showInstructionManager(tvInst));

        scroll.addView(layout);
        builder.setView(scroll);
        builder.setPositiveButton("SIMPAN SETELAN", (dialog, which) -> {
            prefs.edit().putBoolean("custom_inst_enabled", swInst.isChecked()).apply();
            Toast.makeText(MainActivity.this, "Berhasil disimpan!", Toast.LENGTH_SHORT).show();
        });
        // Pengecualian khusus setelan Gaya: tetap ada tombol Tutup untuk konfirmasi
        builder.setNegativeButton("Tutup", null);
        AlertDialog styleDlg = builder.create();
        styleDlg.setOnDismissListener(dialog -> renderActiveTab());
        styleDlg.show();
    }

    private void showInstructionManager(final TextView tvInst) {
        AlertDialog.Builder builder = UiKit.dialog(this, prefs);
        builder.setTitle("Manajemen Instruksi");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16));
        final ListView lv = new ListView(this);
        layout.addView(lv, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 260)));
        Button btnAdd = new Button(this);
        btnAdd.setText("TAMBAH INSTRUKSI BARU");
        UiKit.styleButton(this, btnAdd, UiKit.ACCENT_AI);
        layout.addView(btnAdd);
        builder.setView(layout);
        
        final Runnable[] refreshList = new Runnable[1];
        refreshList[0] = new Runnable() {

            @Override public void run() {

                try {

                    JSONArray arr = new JSONArray(prefs.getString("custom_inst_list", "[]"));


                    List<String> items = new ArrayList<String>();

                    String activeInst = prefs.getString("selected_inst", "");

                    for(int i=0; i<arr.length(); i++) { String name = arr.getJSONObject(i).getString("name"); items.add((i+1) + ". " + name + (name.equals(activeInst) ? " (Aktif)" : "")); }


                    lv.setAdapter(new ArrayAdapter<String>(MainActivity.this, android.R.layout.simple_list_item_1, items));

                    lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {

                        @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

                            try {

                                JSONObject obj = arr.getJSONObject(position);
                                prefs.edit().putString("selected_inst", obj.getString("name")).putString("selected_inst_text", obj.getString("text")).apply();
                                tvInst.setText("Instruksi Aktif: " + obj.getString("name"));
                                Toast.makeText(MainActivity.this, "Diaktifkan: " + obj.getString("name"), Toast.LENGTH_SHORT).show();
                                if(refreshList[0] != null) refreshList[0].run(); 
                            } catch(Exception e){}
                        }
                    });
                    lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                        @Override public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
                            AlertDialog.Builder dHapus = UiKit.dialog(MainActivity.this, prefs); dHapus.setTitle("Hapus Instruksi?");
                            dHapus.setPositiveButton("HAPUS", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface dialog, int which) {
                                    try {
                                        JSONArray newArr = new JSONArray();
                                        for(int j=0; j<arr.length(); j++) { if(j != position) newArr.put(arr.getJSONObject(j)); }
                                        prefs.edit().putString("custom_inst_list", newArr.toString()).apply();
                                        if(refreshList[0] != null) refreshList[0].run();
                                    } catch(Exception e){}
                                }
                            }); dHapus.setNegativeButton("NGGAK JADI", null); dHapus.show(); return true;
                        }
                    });
                } catch (Exception e) {}
            }
        };
        if(refreshList[0] != null) refreshList[0].run();
        
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                AlertDialog.Builder dAdd = UiKit.dialog(MainActivity.this, prefs); dAdd.setTitle("Tambah Instruksi");
                LinearLayout lAdd = new LinearLayout(MainActivity.this); lAdd.setOrientation(LinearLayout.VERTICAL); lAdd.setPadding(40,40,40,40);
                final EditText etName = new EditText(MainActivity.this); etName.setHint("Nama Instruksi"); lAdd.addView(etName);
                final EditText etIsi = new EditText(MainActivity.this); etIsi.setHint("Isi instruksinya..."); etIsi.setMinLines(4); lAdd.addView(etIsi);
                dAdd.setView(lAdd);
                dAdd.setPositiveButton("SIMPAN", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if(etName.getText().toString().isEmpty()) return;
                        try {
                            JSONArray arr = new JSONArray(prefs.getString("custom_inst_list", "[]"));
                            JSONObject obj = new JSONObject(); obj.put("name", etName.getText().toString()); obj.put("text", etIsi.getText().toString());
                            arr.put(obj); prefs.edit().putString("custom_inst_list", arr.toString()).apply();
                            if(refreshList[0] != null) refreshList[0].run(); 
                        } catch(Exception e){}
                    }
                }); dAdd.show();
            }

        });

        builder.show();

    }





    private void showLogicDialog() {
        LogicDialogUI.show(this, prefs);
    }
    private void showUtilityDialog() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(UiKit.bg(prefs));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 12), UiKit.dp(this, 16), UiKit.dp(this, 16));

        layout.addView(createHeader("JALUR TEKS", "#1976D2"));
        final Switch swCopy = new Switch(this); swCopy.setText("Otomatis Ngopi ke Clipboard"); swCopy.setChecked(prefs.getBoolean("auto_copy_mode", false)); layout.addView(swCopy);
        final Button bApp = new Button(this); bApp.setText("Gaya Nempel: " + prefs.getString("append_mode", "space")); layout.addView(bApp);
        bApp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showListDialog("Gaya Nempel", new String[]{"space", "newline", "none"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int i) { prefs.edit().putString("append_mode", new String[]{"space", "newline", "none"}[i]).apply(); bApp.setText("Gaya Nempel: " + new String[]{"space", "newline", "none"}[i]); }
                });
            }
        });
        final Switch swSend = new Switch(this); swSend.setText("Langsung Kirim Otomatis"); swSend.setChecked(prefs.getBoolean("auto_send", false)); layout.addView(swSend);

        layout.addView(createHeader("KAMUS ANTI TYPO", "#1976D2"));
        final Switch swKamus = new Switch(this); swKamus.setText("Nyalain Kamus"); swKamus.setChecked(prefs.getBoolean("kamus_enabled", false)); layout.addView(swKamus);
        final Switch swReg = new Switch(this); swReg.setText("Pake Regex"); swReg.setChecked(prefs.getBoolean("regex_kamus_enabled", false)); layout.addView(swReg);
        
        LinearLayout kamusRow = new LinearLayout(this); kamusRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnTambahKamus = new Button(this); btnTambahKamus.setText("+ TAMBAH"); btnTambahKamus.setBackgroundColor(Color.parseColor("#1565C0")); btnTambahKamus.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lpBtn1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f); lpBtn1.setMargins(0,0,10,0); btnTambahKamus.setLayoutParams(lpBtn1);
        Button btnLihatKamus = new Button(this); btnLihatKamus.setText("LIHAT KAMUS"); btnLihatKamus.setBackgroundColor(Color.parseColor("#1976D2")); btnLihatKamus.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lpBtn2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f); btnLihatKamus.setLayoutParams(lpBtn2);
        
        btnTambahKamus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                AlertDialog.Builder dAdd = UiKit.dialog(MainActivity.this, prefs); dAdd.setTitle("Tambah Kata Kamus");
                LinearLayout lAdd = new LinearLayout(MainActivity.this); lAdd.setOrientation(LinearLayout.VERTICAL); lAdd.setPadding(40,40,40,40);
                final EditText etOrigin = new EditText(MainActivity.this); etOrigin.setHint("Kata Salah (Misal: yg)"); lAdd.addView(etOrigin);
                final EditText etRep = new EditText(MainActivity.this); etRep.setHint("Kata Benar (Misal: yang)"); lAdd.addView(etRep);
                dAdd.setView(lAdd);
                dAdd.setPositiveButton("SIMPAN", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if(etOrigin.getText().toString().isEmpty()) return;
                        try {
                            JSONArray arr = new JSONArray(prefs.getString("json_kamus", "[]"));
                            JSONObject obj = new JSONObject(); obj.put("origin", etOrigin.getText().toString()); obj.put("replacement", etRep.getText().toString());
                            arr.put(obj); prefs.edit().putString("json_kamus", arr.toString()).apply();
                            Toast.makeText(MainActivity.this, "Kata tersimpan!", Toast.LENGTH_SHORT).show();
                        } catch(Exception e){}
                    }
                }); dAdd.show();
            }
        });
        btnLihatKamus.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showDictionaryManager(); }});
        kamusRow.addView(btnTambahKamus); kamusRow.addView(btnLihatKamus); layout.addView(kamusRow);

        layout.addView(createHeader("MAKRO JALAN PINTAS", "#1976D2"));
        final Switch swMakro = new Switch(this); swMakro.setText("Nyalain Makro"); swMakro.setChecked(prefs.getBoolean("makro_enabled", false)); layout.addView(swMakro);
        
        LinearLayout makroRow = new LinearLayout(this); makroRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnTambahMakro = new Button(this); btnTambahMakro.setText("+ TAMBAH"); btnTambahMakro.setBackgroundColor(Color.parseColor("#1565C0")); btnTambahMakro.setTextColor(Color.WHITE);
        btnTambahMakro.setLayoutParams(lpBtn1);
        Button btnLihatMakro = new Button(this); btnLihatMakro.setText("LIHAT MAKRO"); btnLihatMakro.setBackgroundColor(Color.parseColor("#1976D2")); btnLihatMakro.setTextColor(Color.WHITE);
        btnLihatMakro.setLayoutParams(lpBtn2);
        
        btnTambahMakro.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                AlertDialog.Builder dAdd = UiKit.dialog(MainActivity.this, prefs); dAdd.setTitle("Tambah Makro Baru");
                LinearLayout lAdd = new LinearLayout(MainActivity.this); lAdd.setOrientation(LinearLayout.VERTICAL); lAdd.setPadding(40,40,40,40);
                final EditText etTrig = new EditText(MainActivity.this); etTrig.setHint("Pemicu (Misal: /alamat)"); lAdd.addView(etTrig);
                final EditText etIsi = new EditText(MainActivity.this); etIsi.setHint("Isi teks panjang..."); etIsi.setMinLines(3); lAdd.addView(etIsi);
                dAdd.setView(lAdd);
                dAdd.setPositiveButton("SIMPAN", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if(etTrig.getText().toString().isEmpty()) return;
                        try {
                            JSONArray arr = new JSONArray(prefs.getString("json_macros", "[]"));
                            JSONObject obj = new JSONObject(); obj.put("pemicu", etTrig.getText().toString()); obj.put("isi", etIsi.getText().toString());
                            arr.put(obj); prefs.edit().putString("json_macros", arr.toString()).apply();
                            Toast.makeText(MainActivity.this, "Makro tersimpan!", Toast.LENGTH_SHORT).show();
                        } catch(Exception e){}
                    }
                }); dAdd.show();
            }
        });
        btnLihatMakro.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showMacroManager(); }});
        makroRow.addView(btnTambahMakro); makroRow.addView(btnLihatMakro); layout.addView(makroRow);
        layout.addView(createHeader("SMART REPLY", "#1976D2"));
        final Button btnSmartStyle = new Button(this);
        btnSmartStyle.setText("Gaya Reply: " + prefs.getString("smart_reply_style", "Natural Santai"));
        layout.addView(btnSmartStyle);
        btnSmartStyle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] styles = {"Natural Santai", "Gaul / Guel", "Manja / Soft", "Agak Cuek", "Kasar / Frontal", "Sopan Formal", "Lucu / Receh", "Singkat Padat"};
                showListDialog("Pilih Gaya Smart Reply", styles, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int i) {
                        prefs.edit().putString("smart_reply_style", styles[i]).apply();
                        btnSmartStyle.setText("Gaya Reply: " + styles[i]);
                    }
                });
            }
        });

        final Button btnSmartLen = new Button(this);
        btnSmartLen.setText("Panjang Balasan: " + prefs.getString("smart_reply_length", "Otomatis"));
        layout.addView(btnSmartLen);
        btnSmartLen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] lens = {"Otomatis", "Singkat", "Sedang", "Panjang"};
                showListDialog("Panjang Balasan", lens, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int i) {
                        prefs.edit().putString("smart_reply_length", lens[i]).apply();
                        btnSmartLen.setText("Panjang Balasan: " + lens[i]);
                    }
                });
            }
        });

        layout.addView(createHeader("WINGMAN (ASISTEN BALAS)", "#1976D2"));
        
        final Button btnWgStruktur = new Button(this); btnWgStruktur.setText("Struktur Respon: " + prefs.getString("wingman_struktur", "Otomatis")); layout.addView(btnWgStruktur);
        btnWgStruktur.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] items = {"Otomatis", "Panjang Detail", "Singkat", "Poin-poin"};
                showListDialog("Struktur Respon", items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int i) { prefs.edit().putString("wingman_struktur", items[i]).apply(); btnWgStruktur.setText("Struktur Respon: " + items[i]); }
                });
            }
        });
        
        final EditText etWgAsal = new EditText(this); etWgAsal.setHint("Asal Kota/Daerah (Misal: Jakarta)"); etWgAsal.setText(prefs.getString("wingman_asal", "")); layout.addView(etWgAsal);
        final EditText etWgNama = new EditText(this); etWgNama.setHint("Nama Kamu (Misal: Anas)"); etWgNama.setText(prefs.getString("wingman_nama", "")); layout.addView(etWgNama);
        final EditText etWgUsia = new EditText(this); etWgUsia.setHint("Usia Kamu (Misal: 25)"); etWgUsia.setText(prefs.getString("wingman_usia", "")); layout.addView(etWgUsia);
        final EditText etWgInstruksi = new EditText(this); etWgInstruksi.setHint("Instruksi Tambahan (Opsional)"); etWgInstruksi.setText(prefs.getString("wingman_instruksi", "")); layout.addView(etWgInstruksi);

        layout.addView(createHeader("AKSESIBILITAS (BALASAN PINTAR + BRAILLE)", "#1976D2"));
        TextView tvAkses = new TextView(this);
        tvAkses.setText("Layanan aksesibilitas ini dipakai untuk 2 hal:\n" +
                "• Smart Reply (baca konteks chat)\n" +
                "• Mode Braille (supaya gesture TalkBack/JOS/Jieshuo TIDAK mengganggu saat mengetik Braille, seperti Advanced Braille Keyboard)\n\n" +
                "WAJIB aktifkan layanan ini jika kamu pakai Keyboard Braille. Tanpa ini, gesture screen reader bisa saling tabrakan.\n\n" +
                "Juga disarankan: izinkan Autostart, matikan Optimasi Baterai, dan izinkan notifikasi agar layanan tidak mudah dimatikan sistem.");
        tvAkses.setTextColor(Color.WHITE);
        tvAkses.setPadding(0, 8, 0, 12);
        layout.addView(tvAkses);

        // Status singkat apakah service sudah aktif
        boolean a11yOn = isAccessibilityServiceEnabled();
        TextView tvStatus = new TextView(this);
        tvStatus.setText(a11yOn
                ? "Status: Layanan aksesibilitas SUDAH AKTIF"
                : "Status: Layanan aksesibilitas BELUM AKTIF — wajib dinyalakan untuk mode Braille");
        tvStatus.setTextColor(Color.parseColor(a11yOn ? "#69F0AE" : "#FF8A80"));
        tvStatus.setTypeface(null, Typeface.BOLD);
        tvStatus.setPadding(0, 0, 0, 12);
        layout.addView(tvStatus);

        Button btnAkses = new Button(this);
        btnAkses.setText(a11yOn ? "BUKA AKSESIBILITAS" : "AKTIFIN AKSESIBILITAS BRAILLE");
        btnAkses.setBackgroundColor(Color.parseColor(a11yOn ? "#1976D2" : "#E65100"));
        btnAkses.setTextColor(Color.WHITE);
        btnAkses.setTypeface(null, Typeface.BOLD);
        layout.addView(btnAkses);
        btnAkses.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                UiKit.dialog(MainActivity.this, prefs)
                    .setTitle("Aktifin buat Mode Braille")
                    .setMessage(
                        "Supaya Keyboard Braille berjalan lancar (gesture screen reader tidak mengganggu):\n\n" +
                        "1. Di layar berikutnya, cari \"Keyboard AI by Anas\" atau \"Smart Reply\".\n" +
                        "2. Aktifkan saklarnya (ON).\n" +
                        "3. Konfirmasi izin yang diminta.\n\n" +
                        "Saran tambahan:\n" +
                        "• Izinkan Autostart aplikasi ini\n" +
                        "• Matikan Optimasi Baterai untuk aplikasi ini\n" +
                        "• Izinkan notifikasi\n\n" +
                        "Setelah aktif, mode Braille bisa dipakai tanpa mematikan TalkBack/JOS.")
                    .setPositiveButton("Buka Setelan Aksesibilitas", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            try {
                                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, "Setelan aksesibilitas nggak bisa dibuka di HP ini.", Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .setNegativeButton("Nanti", null)
                    .show();
            }
        });

        Button btnSimpanUtil = new Button(this);
        btnSimpanUtil.setText("SIMPAN SETELAN UTILITAS");
        UiKit.styleButton(this, btnSimpanUtil, UiKit.ACCENT_OFFICE);
        btnSimpanUtil.setOnClickListener(v -> {
            prefs.edit()
                    .putBoolean("auto_copy_mode", swCopy.isChecked())
                    .putBoolean("auto_send", swSend.isChecked())
                    .putBoolean("kamus_enabled", swKamus.isChecked())
                    .putBoolean("regex_kamus_enabled", swReg.isChecked())
                    .putBoolean("makro_enabled", swMakro.isChecked())
                    .putString("wingman_asal", etWgAsal.getText().toString())
                    .putString("wingman_nama", etWgNama.getText().toString())
                    .putString("wingman_usia", etWgUsia.getText().toString())
                    .putString("wingman_instruksi", etWgInstruksi.getText().toString())
                    .apply();
            Toast.makeText(MainActivity.this, "Utilitas Tersimpan", Toast.LENGTH_SHORT).show();
        });
        styleGeneratedControls(layout);
        layout.addView(btnSimpanUtil);
        scroll.addView(layout);
        pushSubPage("Kamus, Makro & Smart Reply", scroll);
    }

    
    private void showWingmanStyleManager() {
        AlertDialog.Builder builder = UiKit.dialog(MainActivity.this, prefs); builder.setTitle("Atur Gaya Wingman (Smart Reply)");
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); 
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL); layout.setPadding(40, 40, 40, 40);

        final android.widget.Button btnWgGaya = new android.widget.Button(this); 
        btnWgGaya.setText("GAYA AKTIF: " + prefs.getString("wingman_gaya", "Asik & Santai")); 
        btnWgGaya.setBackgroundColor(android.graphics.Color.parseColor("#1976D2")); 
        btnWgGaya.setTextColor(android.graphics.Color.WHITE); 
        layout.addView(btnWgGaya);
        
        android.widget.Button btnPilih = new android.widget.Button(this); btnPilih.setText("PILIH GAYA YANG LO MAU"); layout.addView(btnPilih);
        btnPilih.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                int c = prefs.getInt("wg_gaya_count", 0);
                final java.util.ArrayList<String> list = new java.util.ArrayList<>();
                list.add("Asik & Santai"); list.add("Sopan & Formal"); list.add("Singkat & Jelas"); list.add("Gombal & Romantis"); list.add("Ngegas & Lucu");
                final java.util.ArrayList<String> promptList = new java.util.ArrayList<>();
                promptList.add(""); promptList.add(""); promptList.add(""); promptList.add(""); promptList.add("");
                for(int i=0; i<c; i++){
                    String n = prefs.getString("wg_gaya_nama_"+i, "");
                    String p = prefs.getString("wg_gaya_prompt_"+i, "");
                    if(!n.isEmpty()) { list.add(n); promptList.add(p); }
                }
                final String[] items = list.toArray(new String[0]);
                showListDialog("Pilih Gaya Wingman", items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        prefs.edit().putString("wingman_gaya", items[which]).putString("wingman_gaya_prompt", promptList.get(which)).apply();
                        btnWgGaya.setText("GAYA AKTIF: " + items[which]);
                        android.widget.Toast.makeText(MainActivity.this, "Gaya Wingman diubah!", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        android.widget.Button btnTambah = new android.widget.Button(this); btnTambah.setText("+ BUAT GAYA BARU"); layout.addView(btnTambah);
        btnTambah.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                AlertDialog.Builder dAdd = UiKit.dialog(MainActivity.this, prefs); dAdd.setTitle("Buat Gaya Wingman Baru");
                android.widget.LinearLayout lAdd = new android.widget.LinearLayout(MainActivity.this); lAdd.setOrientation(android.widget.LinearLayout.VERTICAL); lAdd.setPadding(40,40,40,40);
                final android.widget.EditText etNama = new android.widget.EditText(MainActivity.this); etNama.setHint("Nama Gaya (Misal: Bijak)"); lAdd.addView(etNama);
                final android.widget.EditText etPrompt = new android.widget.EditText(MainActivity.this); etPrompt.setHint("Prompt Instruksi Gaya (Misal: Balas dengan bijak layaknya filsuf)"); lAdd.addView(etPrompt);
                dAdd.setView(lAdd);
                dAdd.setPositiveButton("SIMPAN", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        String n = etNama.getText().toString().trim();
                        String p = etPrompt.getText().toString().trim();
                        if(n.isEmpty()) return;
                        int c = prefs.getInt("wg_gaya_count", 0);
                        prefs.edit().putString("wg_gaya_nama_"+c, n).putString("wg_gaya_prompt_"+c, p).putInt("wg_gaya_count", c+1).putString("wingman_gaya", n).putString("wingman_gaya_prompt", p).apply();
                        btnWgGaya.setText("GAYA AKTIF: " + n);
                        android.widget.Toast.makeText(MainActivity.this, "Gaya disimpan!", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
                dAdd.show();
            }
        });

        scroll.addView(layout);
        styleGeneratedControls(layout);
        builder.setView(scroll);
        builder.show();
    }

    private void showDictionaryManager() {
        AlertDialog.Builder builder = UiKit.dialog(MainActivity.this, prefs); builder.setTitle("Daftar Kamus\n(Klik Edit, Tahan Hapus)");
        final ListView lv = new ListView(this);
        
        final Runnable[] refreshList = new Runnable[1];
        refreshList[0] = new Runnable() {

            @Override public void run() {

                try {

                    final JSONArray arr = new JSONArray(prefs.getString("json_kamus", "[]"));




                    List<String> items = new ArrayList<String>();

                    for(int i=0; i<arr.length(); i++) { items.add(arr.getJSONObject(i).getString("origin") + " \u2794 " + arr.getJSONObject(i).getString("replacement")); }

                    lv.setAdapter(new ArrayAdapter<String>(MainActivity.this, android.R.layout.simple_list_item_1, items));

                    lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {

                        @Override public void onItemClick(AdapterView<?> parent, View view, final int position, long id) {

                            try {

                                final JSONObject obj = arr.getJSONObject(position);
                                AlertDialog.Builder dEdit = UiKit.dialog(MainActivity.this, prefs); dEdit.setTitle("Edit Kata");
                                LinearLayout lEdit = new LinearLayout(MainActivity.this); lEdit.setOrientation(LinearLayout.VERTICAL); lEdit.setPadding(40,40,40,40);
                                final EditText etOrigin = new EditText(MainActivity.this); etOrigin.setText(obj.getString("origin")); lEdit.addView(etOrigin);
                                final EditText etRep = new EditText(MainActivity.this); etRep.setText(obj.getString("replacement")); lEdit.addView(etRep);
                                dEdit.setView(lEdit);
                                dEdit.setPositiveButton("SIMPAN", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface dialog, int which) {
                                        try {
                                            obj.put("origin", etOrigin.getText().toString()); obj.put("replacement", etRep.getText().toString());
                                            arr.put(position, obj); prefs.edit().putString("json_kamus", arr.toString()).apply();
                                            if (refreshList[0] != null) refreshList[0].run(); 
                                        } catch(Exception e){}
                                    }
                                }); dEdit.show();
                            } catch(Exception e){}
                        }
                    });

                    lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                        @Override public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
                            AlertDialog.Builder dHapus = UiKit.dialog(MainActivity.this, prefs); dHapus.setTitle("Hapus Kamus?"); dHapus.setMessage("Yakin hapus kata ini?");
                            dHapus.setPositiveButton("HAPUS", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface dialog, int which) {
                                    try {
                                        JSONArray arr = new JSONArray(prefs.getString("json_kamus", "[]"));
                                        JSONArray newArr = new JSONArray();
                                        for(int j=0; j<arr.length(); j++) { if(j != position) newArr.put(arr.getJSONObject(j)); }
                                        prefs.edit().putString("json_kamus", newArr.toString()).apply();
                                        if (refreshList[0] != null) refreshList[0].run(); 
                                        Toast.makeText(MainActivity.this, "Kata Dihapus", Toast.LENGTH_SHORT).show();
                                    } catch(Exception e){}
                                }
                            }); dHapus.setNegativeButton("NGGAK JADI", null); dHapus.show(); return true;
                        }
                    });
                } catch(Exception e){}
            }
        };
        if (refreshList[0] != null) refreshList[0].run();
        builder.setView(lv); builder.show();
    }

    private void showMacroManager() {
        AlertDialog.Builder builder = UiKit.dialog(MainActivity.this, prefs); builder.setTitle("Daftar Makro\n(Klik Edit, Tahan Hapus)");
        final ListView lv = new ListView(this);
        
        final Runnable[] refreshList = new Runnable[1];
        refreshList[0] = new Runnable() {

            @Override public void run() {

                try {

                    final JSONArray arr = new JSONArray(prefs.getString("json_macros", "[]"));




                    List<String> items = new ArrayList<String>();

                    for(int i=0; i<arr.length(); i++) { items.add(arr.getJSONObject(i).getString("pemicu") + " \u2794 " + arr.getJSONObject(i).getString("isi")); }

                    lv.setAdapter(new ArrayAdapter<String>(MainActivity.this, android.R.layout.simple_list_item_1, items));

                    lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {

                        @Override public void onItemClick(AdapterView<?> parent, View view, final int position, long id) {

                            try {

                                final JSONObject obj = arr.getJSONObject(position);
                                AlertDialog.Builder dEdit = UiKit.dialog(MainActivity.this, prefs); dEdit.setTitle("Edit Makro");
                                LinearLayout lEdit = new LinearLayout(MainActivity.this); lEdit.setOrientation(LinearLayout.VERTICAL); lEdit.setPadding(40,40,40,40);
                                final EditText etTrig = new EditText(MainActivity.this); etTrig.setText(obj.getString("pemicu")); lEdit.addView(etTrig);
                                final EditText etIsi = new EditText(MainActivity.this); etIsi.setText(obj.getString("isi")); etIsi.setMinLines(3); lEdit.addView(etIsi);
                                dEdit.setView(lEdit);
                                dEdit.setPositiveButton("SIMPAN", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface dialog, int which) {
                                        try {
                                            obj.put("pemicu", etTrig.getText().toString()); obj.put("isi", etIsi.getText().toString());
                                            arr.put(position, obj); prefs.edit().putString("json_macros", arr.toString()).apply();
                                            if (refreshList[0] != null) refreshList[0].run(); 
                                        } catch(Exception e){}
                                    }
                                }); dEdit.show();
                            } catch(Exception e){}
                        }
                    });

                    lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                        @Override public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
                            AlertDialog.Builder dHapus = UiKit.dialog(MainActivity.this, prefs); dHapus.setTitle("Hapus Makro?"); dHapus.setMessage("Yakin hapus makro ini?");
                            dHapus.setPositiveButton("HAPUS", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface dialog, int which) {
                                    try {
                                        JSONArray arr = new JSONArray(prefs.getString("json_macros", "[]"));
                                        JSONArray newArr = new JSONArray();
                                        for(int j=0; j<arr.length(); j++) { if(j != position) newArr.put(arr.getJSONObject(j)); }
                                        prefs.edit().putString("json_macros", newArr.toString()).apply();
                                        if (refreshList[0] != null) refreshList[0].run(); 
                                        Toast.makeText(MainActivity.this, "Makro Dihapus", Toast.LENGTH_SHORT).show();
                                    } catch(Exception e){}
                                }
                            }); dHapus.setNegativeButton("NGGAK JADI", null); dHapus.show(); return true;
                        }
                    });
                } catch(Exception e){}
            }
        };

        if (refreshList[0] != null) refreshList[0].run();

        builder.setView(lv); builder.show();

    }





    private void showTranslatorDialog() {

        AlertDialog.Builder builder = UiKit.dialog(MainActivity.this, prefs); builder.setTitle("Mesin Translator");

        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(40, 40, 40, 40);

        
        layout.addView(createHeader("SETELAN TERJEMAHAN", "#2196F3"));
        final Switch swTrans = new Switch(this); swTrans.setText("Otomatis Terjemahkan"); swTrans.setChecked(prefs.getBoolean("trans_enabled", false)); layout.addView(swTrans);
        
        final Button bSrc = new Button(this); bSrc.setText("Bahasa awal: " + prefs.getString("lang_src", "Otomatis")); layout.addView(bSrc);
        final Button bTrg = new Button(this); bTrg.setText("Bahasa tujuan: " + prefs.getString("lang_trg", "Inggris")); layout.addView(bTrg);
        
        bSrc.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            final String[] list = {"Otomatis", "Indonesia (id-ID)", "Inggris (en-US)", "Arab (ar-SA)", "Jepang (ja-JP)", "Korea (ko-KR)", "Jerman", "Prancis"};
            showListDialog("Bahasa Asal", list, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int i) { 
                    prefs.edit().putString("lang_src", list[i]).putString("bahasa_mikrofon", list[i]).apply(); 
                    bSrc.setText("Bahasa awal: " + list[i]); 
                }
            });
        }});
        bTrg.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            showListDialog("Bahasa Tujuan", new String[]{"Indonesia", "Inggris", "Arab", "Jepang", "Korea", "Jerman", "Prancis", "Jawa", "Sunda"}, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int i) { prefs.edit().putString("lang_trg", new String[]{"Indonesia", "Inggris", "Arab", "Jepang", "Korea", "Jerman", "Prancis", "Jawa", "Sunda"}[i]).apply(); bTrg.setText("Bahasa tujuan: " + new String[]{"Indonesia", "Inggris", "Arab", "Jepang", "Korea", "Jerman", "Prancis", "Jawa", "Sunda"}[i]); }
            });
        }});

        

        builder.setView(layout); builder.setPositiveButton("SIMPAN", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) { prefs.edit().putBoolean("trans_enabled", swTrans.isChecked()).apply(); }}); builder.show();

    }





    private String getHeightDescription(int h) {
        if (h <= 36) return "Ultra Mini";
        if (h <= 40) return "Mini";
        if (h <= 44) return "Cukup Rendah";
        if (h <= 48) return "Kompak";
        if (h <= 52) return "Ramping";
        if (h <= 55) return "Standar Rendah";
        if (h <= 58) return "Standar Ideal";
        if (h <= 62) return "Sedang Nyaman";
        if (h <= 66) return "Cukup Tinggi";
        if (h <= 70) return "Lapang";
        if (h <= 75) return "Ekstra Lapang";
        if (h <= 80) return "Jumbo";
        if (h <= 85) return "Super Jumbo";
        return "Raksasa";
    }

    private String getVibeDescription(int v) {
        if (v <= 5) return "Ultra Halus";
        if (v <= 10) return "Halus Lembut";
        if (v <= 15) return "Ringan Nyaman";
        if (v <= 20) return "Standar";
        if (v <= 30) return "Tegas";
        if (v <= 40) return "Mantap";
        if (v <= 50) return "Kuat";
        if (v <= 75) return "Ekstra Kuat";
        return "Maksimal";
    }

    private String getDeleteSpeedDescription(int ms) {
        if (ms <= 30) return "Ekstrem Kilat";
        if (ms <= 50) return "Super Cepat";
        if (ms <= 80) return "Sangat Cepat";
        if (ms <= 120) return "Cepat Lancar";
        if (ms <= 200) return "Cepat Terukur";
        if (ms <= 300) return "Standar Nyaman";
        if (ms <= 400) return "Normal Aman";
        if (ms <= 600) return "Santai Terkontrol";
        if (ms <= 1000) return "1 Detik";
        if (ms <= 2000) return "2 Detik";
        if (ms <= 3000) return "3 Detik";
        if (ms <= 5000) return "5 Detik";
        return "Lambat";
    }
    
    /** Galeri papan klip & item tersemat berbentuk kartu yang ramah screen reader. */
    private void showClipboardGallery() {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 14), UiKit.dp(this, 16), UiKit.dp(this, 14));
        root.setBackgroundColor(UiKit.bg(prefs));

        TextView intro = new TextView(this);
        intro.setText("PAPAN KLIP & PIN\nKetuk item untuk menyalin kembali. Gunakan tombol Pin agar teks penting tidak ikut terhapus.");
        intro.setTextSize(14);
        intro.setTextColor(UiKit.textPrimary(prefs));
        intro.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 4), UiKit.dp(this, 4), UiKit.dp(this, 12));
        root.addView(intro);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        android.widget.LinearLayout cards = new android.widget.LinearLayout(this);
        cards.setOrientation(android.widget.LinearLayout.VERTICAL);

        org.json.JSONArray pinnedArr;
        try { pinnedArr = new org.json.JSONArray(prefs.getString("pinned_clipboard_json", "[]")); }
        catch (Exception e) { pinnedArr = new org.json.JSONArray(); }

        org.json.JSONArray arr;
        try { arr = new org.json.JSONArray(prefs.getString("braille_clipboard_history_json", "[]")); }
        catch (Exception e) { arr = new org.json.JSONArray(); }

        final java.util.LinkedHashSet<String> pinnedSet = new java.util.LinkedHashSet<String>();
        for (int i = 0; i < pinnedArr.length(); i++) {
            String s = pinnedArr.optString(i, "");
            if (!s.trim().isEmpty()) pinnedSet.add(s);
        }

        final android.app.AlertDialog[] dlgRef = new android.app.AlertDialog[1];

        if (!pinnedSet.isEmpty()) {
            cards.addView(UiKit.makeSectionLabel(this, "DISEMATKAN (PIN)", UiKit.ACCENT_WARM));
            for (final String item : pinnedSet) {
                cards.addView(buildClipboardCardRow(item, true, pinnedSet, dlgRef));
            }
        }

        cards.addView(UiKit.makeSectionLabel(this, "RIWAYAT SALINAN TERBARU", UiKit.ACCENT_KB));
        boolean hasHistory = false;
        for (int i = 0; i < arr.length(); i++) {
            final String item = arr.optString(i, "");
            if (item.trim().isEmpty()) continue;
            hasHistory = true;
            cards.addView(buildClipboardCardRow(item, pinnedSet.contains(item), pinnedSet, dlgRef));
        }
        if (!hasHistory && pinnedSet.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Belum ada riwayat salinan. Salin teks saat memakai keyboard, lalu buka halaman ini lagi.");
            empty.setTextSize(14);
            empty.setTextColor(UiKit.textMuted(prefs));
            empty.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 18), UiKit.dp(this, 8), UiKit.dp(this, 18));
            cards.addView(empty);
        }

        scroll.addView(cards);
        root.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, UiKit.dp(this, 10), 0, 0);

        Button btnSnip = UiKit.makePillButton(this, "Snippet", UiKit.ACCENT_AI, Color.WHITE, v ->
                CommunityToolsUI.showQuickSnippetsDialog(MainActivity.this, prefs));
        Button btnClear = UiKit.makePillButton(this, "Bersihkan Riwayat", UiKit.ACCENT_DANGER, Color.WHITE, null);
        btnClear.setContentDescription("Hapus riwayat papan klip tanpa menghapus item tersemat");

        LinearLayout.LayoutParams aLp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        aLp1.rightMargin = UiKit.dp(this, 8);
        LinearLayout.LayoutParams aLp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        actions.addView(btnSnip, aLp1);
        actions.addView(btnClear, aLp2);
        root.addView(actions);

        btnClear.setOnClickListener(v -> {
            UiKit.dialog(MainActivity.this, prefs).setTitle("Bersihkan riwayat?")
                    .setMessage("Semua riwayat papan klip akan dihapus (item yang disematkan tetap aman). Lanjutkan?")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Hapus", (d, w) -> {
                        prefs.edit().putString("braille_clipboard_history_json", "[]")
                                .putLong("braille_clipboard_clear_ts", System.currentTimeMillis()).apply();
                        Toast.makeText(MainActivity.this, "Riwayat papan klip berhasil dibersihkan.", Toast.LENGTH_SHORT).show();
                        popSubPage();
                        showClipboardGallery();
                    }).show();
        });
        pushSubPage("Papan Klip & Pin", root);
    }

    private LinearLayout buildClipboardCardRow(final String item, final boolean isPinned, final java.util.LinkedHashSet<String> pinnedSet, final android.app.AlertDialog[] dlgRef) {
        LinearLayout row = UiKit.makeCard(this, prefs);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10), UiKit.dp(this, 12), UiKit.dp(this, 10));

        String preview = item.replace('\n', ' ');
        if (preview.length() > 160) preview = preview.substring(0, 160) + "…";

        TextView tv = new TextView(this);
        tv.setText((isPinned ? "📌 " : "📋 ") + preview);
        tv.setTextSize(14);
        tv.setTextColor(UiKit.textPrimary(prefs));
        tv.setContentDescription((isPinned ? "Item tersemat. " : "Riwayat salinan. ") + preview + ". Ketuk dua kali untuk menyalin.");
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Papan klip Keyboard AI by Anas", item));
                    Toast.makeText(MainActivity.this, "Teks disalin kembali ke papan klip", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "Gagal menyalin teks", Toast.LENGTH_SHORT).show();
                }
            }
        });
        row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button pinBtn = UiKit.makePillButton(this, isPinned ? "Lepas" : "Pin", isPinned ? UiKit.surfaceAlt(prefs) : UiKit.ACCENT_WARM, isPinned ? UiKit.textPrimary(prefs) : Color.WHITE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (isPinned) pinnedSet.remove(item); else pinnedSet.add(item);
                org.json.JSONArray out = new org.json.JSONArray();
                for (String s : pinnedSet) out.put(s);
                prefs.edit().putString("pinned_clipboard_json", out.toString()).apply();
                popSubPage();
                showClipboardGallery();
            }
        });
        pinBtn.setTextSize(12);
        pinBtn.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 6), UiKit.dp(this, 10), UiKit.dp(this, 6));
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pLp.leftMargin = UiKit.dp(this, 8);
        row.addView(pinBtn, pLp);
        return row;
    }

    private void showTtsVoicePicker() {
        final String engine = prefs.getString("braille_tts_engine", null);
        Toast.makeText(this, "Memuat daftar suara TTS…", Toast.LENGTH_SHORT).show();
        final TextToSpeech[] probe = new TextToSpeech[1];
        try {
            TextToSpeech.OnInitListener listener = status -> {
                runOnUiThread(() -> {
                    if (status != TextToSpeech.SUCCESS || probe[0] == null) {
                        Toast.makeText(MainActivity.this, "Daftar suara tidak dapat dibaca. Coba buka pengaturan TTS Android.", Toast.LENGTH_LONG).show();
                        try { if (probe[0] != null) probe[0].shutdown(); } catch (Throwable ignored) {}
                        return;
                    }
                    ArrayList<String> labels = new ArrayList<>();
                    ArrayList<String> names = new ArrayList<>();
                    labels.add("Gunakan suara bawaan mesin TTS"); names.add("");
                    if (Build.VERSION.SDK_INT >= 21) {
                        try {
                            java.util.Set<android.speech.tts.Voice> available = probe[0].getVoices();
                            if (available != null) for (android.speech.tts.Voice voice : available) {
                                if (voice == null) continue;
                                String locale = voice.getLocale() == null ? "Bahasa tidak diketahui" : voice.getLocale().getDisplayName();
                                labels.add(voice.getName() + " — " + locale + (voice.isNetworkConnectionRequired() ? " — online" : " — lokal"));
                                names.add(voice.getName());
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (labels.size() == 1) {
                        UiKit.dialog(MainActivity.this, prefs).setTitle("Pilihan Suara TTS")
                            .setMessage("Mesin ini tidak menyediakan daftar suara yang bisa dipilih dari aplikasi. Buka pengaturan TTS Android untuk mengelola suara.")
                            .setPositiveButton("Pengaturan TTS", (d, w) -> openSystemTtsSettings())
                            .show();
                    } else {
                        String current = prefs.getString("braille_tts_voice_name", "");
                        int selected = Math.max(0, names.indexOf(current));
                        UiKit.dialog(MainActivity.this, prefs).setTitle("Pilih Suara TTS")
                            .setSingleChoiceItems(labels.toArray(new String[0]), selected, (dialog, which) -> {
                                String voiceName = names.get(which);
                                prefs.edit().putString("braille_tts_voice_name", voiceName).apply();
                                Toast.makeText(MainActivity.this, voiceName.isEmpty() ? "Menggunakan suara bawaan mesin" : "Suara TTS dipilih: " + labels.get(which), Toast.LENGTH_SHORT).show();
                                dialog.dismiss();
                            }).setNegativeButton("Batal", null).show();
                    }
                    try { if (probe[0] != null) probe[0].shutdown(); } catch (Throwable ignored) {}
                });
            };
            if (engine != null && !engine.trim().isEmpty()) probe[0] = new TextToSpeech(getApplicationContext(), listener, engine.trim());
            else probe[0] = new TextToSpeech(getApplicationContext(), listener);
        } catch (Throwable e) {
            Toast.makeText(this, "Tidak dapat memuat daftar suara TTS pada perangkat ini.", Toast.LENGTH_LONG).show();
        }
    }

    private void openSystemTtsSettings() {
        try {
            Intent intent = new Intent("com.android.settings.TTS_SETTINGS");
            if (intent.resolveActivity(getPackageManager()) == null) intent = new Intent(Settings.ACTION_SETTINGS);
            startActivity(intent);
        } catch (Throwable e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Throwable ignored) { Toast.makeText(this, "Pengaturan TTS Android tidak dapat dibuka", Toast.LENGTH_LONG).show(); }
        }
    }

    /** Menambahkan penjelasan singkat yang bisa dibaca screen reader di bawah kontrol pengaturan. */
    private void addSettingDescriptions(android.widget.LinearLayout layout) {
        if (layout == null) return;
        for (int i = 0; i < layout.getChildCount(); i++) {
            android.view.View child = layout.getChildAt(i);
            if (child instanceof android.widget.TextView && !(child instanceof android.widget.Button)
                    && !(child instanceof android.widget.Switch)
                    && !(child instanceof android.widget.EditText)) continue;
            String label = "";
            if (child instanceof android.widget.TextView) label = ((android.widget.TextView) child).getText().toString();
            else if (child instanceof android.widget.EditText) label = ((android.widget.EditText) child).getHint() == null ? "Kolom isian" : ((android.widget.EditText) child).getHint().toString();
            if (label.trim().isEmpty()) continue;
            String lower = label.toLowerCase(java.util.Locale.ROOT);
            // Tombol mikrofon punya kotak penjelasan sendiri yang ikut berubah saat pilihan diganti.
            if (child instanceof android.widget.Button && (lower.startsWith("mikrofon:") || lower.startsWith("tes mikrofon") || lower.startsWith("panduan:"))) continue;
            String desc;
            if (child instanceof android.widget.Switch) {
                boolean on = ((android.widget.Switch) child).isChecked();
                String state = on ? "Sekarang aktif" : "Sekarang mati";
                if (lower.contains("whisper")) desc = state + ". Kalau aktif, input suara memakai alur rekam Whisper; kalau mati, aplikasi memakai pengenalan suara bawaan yang tersedia. Contoh: pilih Whisper kalau layanan dan API-nya sudah lo siapin.";
                else if (lower.contains("getaran")) desc = state + ". Aktifkan kalau lo pengin ada getaran tiap tombol ditekan; matikan kalau mau ngetik lebih senyap atau hemat baterai.";
                else if (lower.contains("kapital") || lower.contains("tanda baca")) desc = state + ". Kalau aktif, hasil ucapan dibantu dirapikan dengan huruf kapital dan tanda baca. Kalau mati, teks lebih mentah, seperti ucapan yang ditranskripsikan apa adanya.";
                else if (lower.contains("filler")) desc = state + ". Aktifkan untuk mempertahankan kata jeda seperti 'eee' atau 'anu'; matikan supaya kata jeda biasanya tidak ikut masuk.";
                else if (lower.contains("diarization") || lower.contains("pembicara")) desc = state + ". Aktifkan jika rekaman berisi beberapa orang dan lo ingin hasilnya diberi label pembicara; hasilnya bergantung dukungan layanan STT.";
                else if (lower.contains("multisaluran")) desc = state + ". Opsi ini untuk audio dengan lebih dari satu kanal. Untuk rekaman biasa dari HP, biasanya biarkan mati.";
                else if (lower.contains("bunyi sukses")) desc = state + ". Kalau aktif, aplikasi memutar efek saat proses selesai; matikan kalau lagi teleponan, di tempat tenang, atau nggak mau ada bunyi tambahan.";
                else if (lower.contains("huruf saat diketik")) desc = state + ". Kalau aktif, huruf yang ditekan ditampilkan sebagai pop-up. Matikan jika pop-up terasa mengganggu atau tidak dibutuhkan.";
                else desc = state + ". Aktifkan untuk memakai fitur ini; matikan kalau lo nggak membutuhkannya. Contoh: coba aktifkan, tes sebentar, lalu matikan lagi kalau terasa mengganggu.";
            } else if (child instanceof android.widget.EditText) {
                desc = "Isi bagian ini kalau lo ingin mengubah opsi tersebut. Contoh: masukkan nilai sesuai petunjuk di kolom; kalau belum paham, biarkan kosong atau pakai nilai bawaan.";
                if (lower.contains("ambang vad")) desc = "Atur seberapa yakin sistem mendeteksi suara. Contoh 0,5 adalah titik awal; terlalu tinggi bisa memotong suara pelan, terlalu rendah bisa ikut menangkap kebisingan.";
                else if (lower.contains("kata kunci")) desc = "Masukkan nama atau istilah penting, pisahkan dengan koma. Contoh: Anugrah, Keyboard AI, Braille. Fitur ini hanya berpengaruh jika layanan STT yang dipakai mendukung kata kunci.";
            } else {
                if (lower.contains("mode:") || lower.contains("mode keyboard")) desc = "Pilih jenis keyboard yang paling cocok. QWERTY seperti keyboard HP biasa; Braille memakai pola titik untuk input aksesibel. Contoh: pilih QWERTY untuk mengetik biasa, atau Braille jika itu cara input utama lo.";
                else if (lower.contains("ukuran keyboard") || lower.contains("ukuran huruf")) desc = "Atur besar-kecilnya tombol atau huruf. Ibarat memperbesar tombol remote: ukuran besar lebih mudah dijangkau, ukuran kecil memberi ruang lebih luas di layar.";
                else if (lower.contains("kekuatan getar")) desc = "Atur lamanya getaran tiap tombol. Nilai kecil terasa tipis, nilai besar terasa lebih kuat dan mungkin lebih boros baterai.";
                else if (lower.contains("bahasa yang dikenali")) desc = "Pilih bahasa yang lo ucapkan. 'Otomatis' membiarkan layanan memilih sendiri; pilih bahasa tertentu kalau hasil otomatis sering salah mengenali ucapan lo.";
                else if (lower.contains("format audio")) desc = "Saat ini semua rekaman Whisper selalu disimpan sebagai MPEG-4/AAC supaya semua layanan STT bisa membacanya, jadi pilihan ini belum mengubah apa pun. Yang berpengaruh pada kualitas adalah Bitrate.";
                else if (lower.contains("bitrate") || lower.contains("kualitas audio")) desc = "Bitrate lebih tinggi biasanya menghasilkan audio lebih bagus tetapi ukuran file lebih besar. Contoh: 128 kbps cukup untuk penggunaan umum; pilih lebih tinggi jika kualitas lebih penting daripada ukuran.";
                else if (lower.contains("tema")) desc = "Pilih tampilan yang nyaman. Tema gelap cocok di tempat redup; tema terang lebih jelas bagi sebagian orang di tempat terang.";
                else desc = "Ketuk untuk melihat pilihan atau mengatur fitur ini. Contoh: pilih opsi yang paling sesuai dengan kebiasaan lo; perubahan hanya mengatur fitur terkait dan tidak seharusnya menghapus fitur lain.";
            }
            android.widget.TextView note = new android.widget.TextView(this);
            note.setText("Penjelasan: " + desc);
            note.setTextSize(13);
            note.setTextColor(android.graphics.Color.rgb(100, 100, 100));
            note.setPadding(12, 2, 12, 14);
            note.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            layout.addView(note, i + 1);
            i++;
        }
    }

    private void appendKeyboardSettingsCategories(final LinearLayout targetBox) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        layout.addView(createHeader("PILIH MODE KEYBOARD UTAMA", "#FF5722"));
        final android.widget.Button bKeyboardMode = new android.widget.Button(this);
        boolean isBrailleDefault = prefs.getBoolean("braille_default_mode", false);
        String scriptPref = prefs.getString("keyboard_script", "latin");
        String modeLabel;
        if (isBrailleDefault) {
            modeLabel = "arabic".equals(scriptPref)
                ? "4. Keyboard Braille Arab (6-Titik Resmi + Syakal)"
                : "3. Keyboard Braille Latin (6-Titik Standar)";
        } else {
            modeLabel = "arabic".equals(scriptPref)
                ? "2. Keyboard Standar Arab (Visual Lengkap)"
                : "1. Keyboard Standar QWERTY Latin";
        }
        bKeyboardMode.setText("Mode: " + modeLabel);
        bKeyboardMode.setBackgroundColor(Color.parseColor(isBrailleDefault ? "#00BCD4" : "#1976D2"));
        bKeyboardMode.setTextColor(Color.WHITE);
        bKeyboardMode.setTypeface(null, Typeface.BOLD);
        layout.addView(bKeyboardMode);

        bKeyboardMode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] modes = {
                    "1. Keyboard Standar QWERTY Latin (Huruf Biasa)",
                    "2. Keyboard Standar Arab (Visual Lengkap + Syakal)",
                    "3. Keyboard Braille Latin 6-Titik (Mode Tunanetra)",
                    "4. Keyboard Braille Arab 6-Titik (Resmi + Syakal/Sukun)"
                };
                showListDialog("Pilih Mode Keyboard Utama", modes, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int i) {
                        boolean brailleMode = (i == 2 || i == 3);
                        String newScript = (i == 1 || i == 3) ? "arabic" : "latin";
                        prefs.edit()
                            .putBoolean("braille_default_mode", brailleMode)
                            .putString("keyboard_script", newScript)
                            .apply();
                        bKeyboardMode.setText("Mode: " + modes[i]);
                        bKeyboardMode.setBackgroundColor(Color.parseColor(brailleMode ? "#00BCD4" : "#1976D2"));
                        Toast.makeText(MainActivity.this, "Mode Keyboard Aktif:\n" + modes[i], Toast.LENGTH_LONG).show();

                        if (brailleMode && !isAccessibilityServiceEnabled()) {
                            UiKit.dialog(MainActivity.this, prefs)
                                .setTitle("Aktifkan Aksesibilitas untuk Braille")
                                .setMessage(
                                    "Mode Braille butuh layanan aksesibilitas aplikasi ini agar gesture TalkBack/JOS/Jieshuo tidak mengganggu saat mengetik (seperti Advanced Braille Keyboard).\n\n" +
                                    "Tanpa layanan ini, gesture screen reader bisa saling tabrakan.\n\n" +
                                    "Aktifkan sekarang?")
                                .setPositiveButton("Buka Setelan Aksesibilitas", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        try {
                                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                                        } catch (Exception e) {
                                            Toast.makeText(MainActivity.this, "Tidak bisa membuka pengaturan", Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                })
                                .setNegativeButton("Nanti", null)
                                .show();
                        }
                    }
                });
            }
        });

        final android.widget.Button bBrailleTutorial = new android.widget.Button(this);
        bBrailleTutorial.setText("Panduan Lengkap & Tutorial Keyboard Braille");
        bBrailleTutorial.setBackgroundColor(Color.parseColor("#4CAF50"));
        bBrailleTutorial.setTextColor(Color.WHITE);
        bBrailleTutorial.setTypeface(null, Typeface.BOLD);
        layout.addView(bBrailleTutorial);
        bBrailleTutorial.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showBrailleTutorialDialog();
            }
        });

        layout.addView(createHeader("UKURAN KEYBOARD", "#2196F3"));
        final android.widget.Button bHeight = new android.widget.Button(this);
        int savedHeight = prefs.getInt("key_height", 58);
        bHeight.setText("Ukuran Keyboard: " + savedHeight + " dp (" + getHeightDescription(savedHeight) + ")");
        layout.addView(bHeight);
        bHeight.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] items = {
                    "36 dp - Sangat Pendek (Ultra Mini)",
                    "40 dp - Ringkas Kompak (Mini)",
                    "44 dp - Cukup Rendah (Kompak)",
                    "48 dp - Kompak Ramping",
                    "52 dp - Ramping Nyaman",
                    "55 dp - Standar Rendah",
                    "58 dp - Standar Ideal (Default)",
                    "62 dp - Sedang Nyaman",
                    "66 dp - Cukup Tinggi",
                    "70 dp - Lapang Luas",
                    "75 dp - Ekstra Lapang",
                    "80 dp - Sangat Tinggi (Jumbo)",
                    "85 dp - Super Jumbo",
                    "90 dp - Maksimal (Raksasa)"
                };
                final int[] values = {36, 40, 44, 48, 52, 55, 58, 62, 66, 70, 75, 80, 85, 90};
                showListDialog("Pilih Ukuran Keyboard", items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int i) {
                        prefs.edit().putInt("key_height", values[i]).apply();
                        bHeight.setText("Ukuran Keyboard: " + values[i] + " dp (" + getHeightDescription(values[i]) + ")");
                        Toast.makeText(MainActivity.this, "Ukuran keyboard: " + values[i] + " dp", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        layout.addView(createHeader("GETAR SAAT DITEKAN", "#FF9800"));
        final android.widget.Switch swVib = new android.widget.Switch(this);
        swVib.setText("Aktifin getaran");
        swVib.setChecked(prefs.getBoolean("vibrate_on_keypress", true) || prefs.getBoolean("vibe_enabled", true));
        swVib.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("vibrate_on_keypress", isChecked).putBoolean("vibe_enabled", isChecked).apply();
            }
        });
        layout.addView(swVib);

        final android.widget.Button bVibeDur = new android.widget.Button(this);
        int curVibe = prefs.getInt("vibe_duration", 20);
        bVibeDur.setText("Kekuatan Getar: " + curVibe + " ms (" + getVibeDescription(curVibe) + ")");
        layout.addView(bVibeDur);
        bVibeDur.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] items = {
                    "5 ms - Ultra Halus",
                    "10 ms - Halus Lembut",
                    "15 ms - Ringan Nyaman",
                    "20 ms - Standar (Default)",
                    "30 ms - Tegas",
                    "40 ms - Mantap",
                    "50 ms - Kuat",
                    "75 ms - Ekstra Kuat",
                    "100 ms - Maksimal"
                };
                final int[] values = {5, 10, 15, 20, 30, 40, 50, 75, 100};
                showListDialog("Atur Kekuatan Getaran", items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int i) {
                        prefs.edit().putInt("vibe_duration", values[i]).apply();
                        bVibeDur.setText("Kekuatan Getar: " + values[i] + " ms (" + getVibeDescription(values[i]) + ")");
                    }
                });
            }
        });

        layout.addView(createHeader("TAMPILAN & ESTETIKA", "#4CAF50"));
        
        final android.widget.Switch swPopup = new android.widget.Switch(this);
        swPopup.setText("Munculkan Huruf saat Diketik (Untuk Orang Awas)");
        swPopup.setChecked(prefs.getBoolean("popup_on_keypress", true));
        swPopup.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("popup_on_keypress", isChecked).apply();
            }
        });
        layout.addView(swPopup);
        
        final android.widget.Button bTheme = new android.widget.Button(this);
        bTheme.setText("Tema Keyboard: " + prefs.getString("keyboard_theme", "Dark"));
        layout.addView(bTheme);
        bTheme.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                final String[] items = {
                    "Dark (Gelap)",
                    "Light (Terang)",
                    "Amoled (Hitam Pekat)",
                    "Kontras Tinggi Kuning-Hitam (Ramah Low Vision)",
                    "Ocean Blue (Biru Laut Elegan)"
                };
                final String[] vals = {"Dark", "Light", "Amoled", "HighContrastYellow", "OceanBlue"};
                showListDialog("Pilih Tema Keyboard", items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int i) {
                        prefs.edit().putString("keyboard_theme", vals[i]).apply();
                        bTheme.setText("Tema Keyboard: " + vals[i]);
                    }
                });
            }
        });

        final android.widget.Button bSize = new android.widget.Button(this);
        bSize.setText("Ukuran Huruf Tombol: " + prefs.getString("key_font_size_label", "Normal Standar (100%)"));
        layout.addView(bSize);
        bSize.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                final String[] items = {
                    "Sangat Kecil (70%)",
                    "Kecil Kompak (80%)",
                    "Agak Kecil (90%)",
                    "Normal Standar (100%)",
                    "Agak Besar (110%)",
                    "Besar Jelas (120%)",
                    "Ekstra Besar (135%)",
                    "Raksasa (150%)"
                };
                final float[] vals = {0.70f, 0.80f, 0.90f, 1.0f, 1.10f, 1.20f, 1.35f, 1.50f};
                showListDialog("Atur Ukuran Huruf", items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int i) {
                        prefs.edit().putFloat("key_font_size_scale", vals[i]).putString("key_font_size_label", items[i]).apply();
                        bSize.setText("Ukuran Huruf Tombol: " + items[i]);
                    }
                });
            }
        });

        final android.widget.Button bRadius = new android.widget.Button(this);
        bRadius.setText("Bentuk Tombol: " + prefs.getString("key_border_label", "Sedikit Melengkung (12 dp)"));
        layout.addView(bRadius);
        bRadius.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                final String[] items = {
                    "Kotak Tegas (0 dp - Persegi)",
                    "Sudut Minimalis (4 dp)",
                    "Sudut Halus (8 dp)",
                    "Sedikit Melengkung (12 dp)",
                    "Melengkung Modern (16 dp)",
                    "Semi Bulat (24 dp)",
                    "Bulat Elips Kapsul (36 dp)"
                };
                final float[] vals = {0f, 4f, 8f, 12f, 16f, 24f, 36f};
                showListDialog("Atur Bentuk Tombol", items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int i) {
                        prefs.edit().putFloat("key_border_radius", vals[i]).putString("key_border_label", items[i]).apply();
                        bRadius.setText("Bentuk Tombol: " + items[i]);
                    }
                });
            }
        });

        final android.widget.Button bPad = new android.widget.Button(this);
        bPad.setText("Jarak Bawah Layar: " + prefs.getInt("keyboard_padding_bottom", 0) + " dp");
        layout.addView(bPad);
        bPad.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] items = {
                    "0 dp - Rapat Penuh Bawah",
                    "8 dp - Sedikit Spasi",
                    "15 dp - Spasi Minimal",
                    "22 dp - Spasi Nyaman",
                    "30 dp - Spasi Sedang (Cocok Nav Gesture)",
                    "40 dp - Cukup Tinggi",
                    "50 dp - Spasi Lebar",
                    "65 dp - Lebar",
                    "70 dp - Lebih Tinggi",
                    "80 dp - Tinggi Nyaman",
                    "90 dp - Sangat Tinggi",
                    "100 dp - Ekstra Tinggi",
                    "120 dp - Maksimal Tinggi"
                };
                final int[] vals = {0, 8, 15, 22, 30, 40, 50, 65, 70, 80, 90, 100, 120};
                showListDialog("Jarak Ekstra Bawah (Bottom Padding)", items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int i) {
                        prefs.edit().putInt("keyboard_padding_bottom", vals[i]).apply();
                        bPad.setText("Jarak Bawah Layar: " + vals[i] + " dp");
                    }
                });
            }
        });

        final android.widget.Button bOneHand = new android.widget.Button(this);
        final String[] ohLabels = {"Mati (Penuh Layar)", "Mode Satu Tangan — Rapat Kiri", "Mode Satu Tangan — Rapat Kanan"};
        final String[] ohValues = {"off", "left", "right"};
        String curOh = prefs.getString("one_handed_mode", "off");
        int curOhIdx = "left".equals(curOh) ? 1 : ("right".equals(curOh) ? 2 : 0);
        bOneHand.setText("Mode Satu Tangan: " + ohLabels[curOhIdx]);
        layout.addView(bOneHand);
        bOneHand.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(View v) {
                showListDialog("Mode Satu Tangan", ohLabels, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int i) {
                        prefs.edit().putString("one_handed_mode", ohValues[i]).apply();
                        bOneHand.setText("Mode Satu Tangan: " + ohLabels[i]);
                    }
                });
            }
        });

        final android.widget.Button bKeyGap = new android.widget.Button(this);
        final String[] gapLabels = {"Rapat (Compact)", "Standar", "Renggang (Mudah Ditekan)"};
        final String[] gapValues = {"tight", "normal", "wide"};
        String curGap = prefs.getString("key_spacing_level", "normal");
        int curGapIdx = "tight".equals(curGap) ? 0 : ("wide".equals(curGap) ? 2 : 1);
        bKeyGap.setText("Jarak Antar Tombol: " + gapLabels[curGapIdx]);
        layout.addView(bKeyGap);
        bKeyGap.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(View v) {
                showListDialog("Jarak Antar Tombol", gapLabels, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int i) {
                        prefs.edit().putString("key_spacing_level", gapValues[i]).apply();
                        bKeyGap.setText("Jarak Antar Tombol: " + gapLabels[i]);
                    }
                });
            }
        });

        layout.addView(createHeader("FITUR KETIK & LAYOUT", "#00BCD4"));
        
        final android.widget.Switch swNum = new android.widget.Switch(this);
        swNum.setText("Tampilkan Baris Angka (Number Row)");
        swNum.setChecked(prefs.getBoolean("number_row_enabled", false));
        swNum.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("number_row_enabled", isChecked).apply();
            }
        });
        layout.addView(swNum);

        final android.widget.Switch swEmojiQwerty = new android.widget.Switch(this);
        swEmojiQwerty.setText("Tampilkan tombol daftar emoji di samping Spasi (QWERTY)");
        swEmojiQwerty.setChecked(prefs.getBoolean("qwerty_emoji_button_enabled", true));
        swEmojiQwerty.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("qwerty_emoji_button_enabled", isChecked).apply();
                Toast.makeText(MainActivity.this, "Tombol emoji " + (isChecked ? "diaktifkan" : "dinonaktifkan") + ". Buka ulang keyboard agar tampilan diperbarui.", Toast.LENGTH_LONG).show();
            }
        });
        layout.addView(swEmojiQwerty);
        android.widget.TextView emojiHelp = new android.widget.TextView(this);
        emojiHelp.setText("Penjelasan: pilihan ini hanya menampilkan atau menyembunyikan tombol pembuka daftar emoji di samping Spasi. Emoji tidak dihapus dari Android; pengaturan ini bisa dimatikan agar tombol QWERTY lebih sederhana dan tidak mudah salah tekan.");
        emojiHelp.setTextSize(14);
        emojiHelp.setPadding(8, 0, 8, 18);
        layout.addView(emojiHelp);

        final android.widget.Switch swStickerButton = new android.widget.Switch(this);
        swStickerButton.setText("Tampilkan tombol stiker di sebelah kanan Spasi");
        swStickerButton.setChecked(prefs.getBoolean("sticker_button_enabled", true));
        swStickerButton.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("sticker_button_enabled", isChecked).apply();
                Toast.makeText(MainActivity.this, "Tombol stiker " + (isChecked ? "diaktifkan" : "disembunyikan") + ". Buka ulang keyboard agar tampilan diperbarui.", Toast.LENGTH_LONG).show();
            }
        });
        layout.addView(swStickerButton);
        android.widget.TextView stickerButtonHelp = new android.widget.TextView(this);
        stickerButtonHelp.setText("Penjelasan: tombol 🧩 membuka koleksi stiker tersimpan. Di Pengelola Stiker kamu dapat menambah gambar, membuat stiker teks, dan mencari katalog emoji/teks. Menyembunyikan tombol tidak menghapus koleksi stiker.");
        stickerButtonHelp.setTextSize(14);
        stickerButtonHelp.setPadding(8, 0, 8, 18);
        layout.addView(stickerButtonHelp);

        final String[] dtKeyLabels = {
            "⚙️ Pengaturan",
            "🎤 Input Suara AI",
            "📋 Tempel (Paste)",
            "⚡ Alat Cepat Keyboard",
            "🤖 Smart Reply AI",
            "✍️ Revisi Teks AI",
            "📝 Word / Editor Dokumen",
            "𝔽 Gaya Font",
            "😊 Daftar Emoji",
            "🧩 Tombol Stiker",
            "?123 / =\\< (Pindah Simbol)",
            "ABC / ~^| (Huruf & Simbol Khusus)",
            "◀️ / ▶️ (Halaman Emoji & Simbol)"
        };
        final String[] dtKeyPrefKeys = {
            "blind_dt_key_settings",
            "blind_dt_key_mic",
            "blind_dt_key_paste",
            "blind_dt_key_quick_tools",
            "blind_dt_key_smart_reply",
            "blind_dt_key_revise",
            "blind_dt_key_docs",
            "blind_dt_key_font",
            "blind_dt_key_emoji",
            "blind_dt_key_sticker",
            "blind_dt_key_sym_switch",
            "blind_dt_key_abc_special",
            "blind_dt_key_page_nav"
        };

        final android.widget.Switch swBlindActionDoubleTap = new android.widget.Switch(this);
        swBlindActionDoubleTap.setText("Wajib Ketuk 2x untuk Tombol Aksi (Khusus Tunanetra)");
        swBlindActionDoubleTap.setChecked(prefs.getBoolean("blind_action_double_tap_enabled", true));
        layout.addView(swBlindActionDoubleTap);

        final android.widget.Button bSelectDoubleTapKeys = new android.widget.Button(this);
        final Runnable refreshDoubleTapButtonLabel = new Runnable() {
            @Override public void run() {
                int count = 0;
                for (String k : dtKeyPrefKeys) {
                    if (prefs.getBoolean(k, true)) count++;
                }
                boolean masterOn = prefs.getBoolean("blind_action_double_tap_enabled", true);
                bSelectDoubleTapKeys.setEnabled(masterOn);
                bSelectDoubleTapKeys.setAlpha(masterOn ? 1.0f : 0.5f);
                bSelectDoubleTapKeys.setText("Pilih Tombol yang Wajib Ketuk 2x (" + count + " dari " + dtKeyPrefKeys.length + " Aktif)");
            }
        };
        refreshDoubleTapButtonLabel.run();
        swBlindActionDoubleTap.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("blind_action_double_tap_enabled", isChecked).apply();
                refreshDoubleTapButtonLabel.run();
                Toast.makeText(MainActivity.this, "Ketuk 2x tombol aksi (tunanetra) " + (isChecked ? "diaktifkan" : "dinonaktifkan"), Toast.LENGTH_SHORT).show();
            }
        });
        bSelectDoubleTapKeys.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final boolean[] checkedItems = new boolean[dtKeyPrefKeys.length];
                for (int i = 0; i < dtKeyPrefKeys.length; i++) {
                    checkedItems[i] = prefs.getBoolean(dtKeyPrefKeys[i], true);
                }
                android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(MainActivity.this);
                builder.setTitle("Pilih Tombol Wajib Ketuk 2x (Tunanetra)");
                builder.setMultiChoiceItems(dtKeyLabels, checkedItems, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        checkedItems[which] = isChecked;
                    }
                });
                builder.setPositiveButton("Simpan", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        android.content.SharedPreferences.Editor ed = prefs.edit();
                        for (int i = 0; i < dtKeyPrefKeys.length; i++) {
                            ed.putBoolean(dtKeyPrefKeys[i], checkedItems[i]);
                        }
                        ed.apply();
                        refreshDoubleTapButtonLabel.run();
                        Toast.makeText(MainActivity.this, "Pilihan tombol ketuk 2x disimpan.", Toast.LENGTH_SHORT).show();
                    }
                });
                builder.setNeutralButton("Centang Semua", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        android.content.SharedPreferences.Editor ed = prefs.edit();
                        for (String k : dtKeyPrefKeys) ed.putBoolean(k, true);
                        ed.apply();
                        refreshDoubleTapButtonLabel.run();
                        Toast.makeText(MainActivity.this, "Semua tombol aksi diwajibkan ketuk 2x.", Toast.LENGTH_SHORT).show();
                    }
                });
                builder.setNegativeButton("Hapus Semua", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        android.content.SharedPreferences.Editor ed = prefs.edit();
                        for (String k : dtKeyPrefKeys) ed.putBoolean(k, false);
                        ed.apply();
                        refreshDoubleTapButtonLabel.run();
                        Toast.makeText(MainActivity.this, "Semua tombol aksi diset sekali ketuk.", Toast.LENGTH_SHORT).show();
                    }
                });
                builder.show();
            }
        });
        layout.addView(bSelectDoubleTapKeys);

        android.widget.TextView blindDtHelp = new android.widget.TextView(this);
        blindDtHelp.setText("Penjelasan: mencegah tombol seperti Input Suara, Tempel, Pengaturan, atau Stiker kepencet tidak sengaja saat pengguna tunanetra (TalkBack/Jieshuo) mengetik cepat. Matikan centang tombol tertentu jika ingin sekali ketuk. Pengguna awas tetap selalu sekali ketuk.");
        blindDtHelp.setTextSize(14);
        blindDtHelp.setPadding(8, 0, 8, 18);
        layout.addView(blindDtHelp);

        layout.addView(createHeader("AKSESIBILITAS & EDIT TEKS", "#673AB7"));

        final android.widget.Button bQwertyTts = new android.widget.Button(this);
        final String[] ttsEchoLabels = {"Mati", "Ucapkan Per Huruf", "Ucapkan Per Kata", "Ucapkan Huruf & Kata"};
        final String[] ttsEchoValues = {"off", "char", "word", "char_word"};
        String curEcho = prefs.getString("qwerty_tts_echo", "off");
        int curEchoIdx = 0;
        for (int idx = 0; idx < ttsEchoValues.length; idx++) if (ttsEchoValues[idx].equals(curEcho)) curEchoIdx = idx;
        bQwertyTts.setText("Suara Umpan Balik Mandiri QWERTY: " + ttsEchoLabels[curEchoIdx]);
        layout.addView(bQwertyTts);
        bQwertyTts.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showListDialog("Suara Umpan Balik Mandiri QWERTY", ttsEchoLabels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putString("qwerty_tts_echo", ttsEchoValues[which]).apply();
                        bQwertyTts.setText("Suara Umpan Balik Mandiri QWERTY: " + ttsEchoLabels[which]);
                    }
                });
            }
        });

        final android.widget.Switch swQwertyEchoScreenReader = new android.widget.Switch(this);
        swQwertyEchoScreenReader.setText("Gunakan Screen Reader yang Aktif (TalkBack / Jieshuo)");
        swQwertyEchoScreenReader.setChecked(prefs.getBoolean("qwerty_echo_use_screen_reader", false));
        swQwertyEchoScreenReader.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("qwerty_echo_use_screen_reader", isChecked).apply();
                Toast.makeText(MainActivity.this, isChecked
                    ? "Umpan balik spasi/huruf QWERTY kini memakai Screen Reader aktif (TalkBack/Jieshuo)."
                    : "Umpan balik spasi/huruf QWERTY kembali memakai suara TTS mandiri.", Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(swQwertyEchoScreenReader);

        android.widget.TextView qwertySrHelp = new android.widget.TextView(this);
        qwertySrHelp.setText("Penjelasan: jika diaktifkan, saat menekan Spasi atau mengetik huruf di mode QWERTY, kata/huruf akan dibacakan langsung oleh TalkBack / Jieshuo yang aktif (tanpa memutar TTS bawaan keyboard agar suara tidak bertabrakan).");
        qwertySrHelp.setTextSize(14);
        qwertySrHelp.setPadding(8, 0, 8, 18);
        layout.addView(qwertySrHelp);

        final android.widget.Switch swFieldAnnounce = new android.widget.Switch(this);
        swFieldAnnounce.setText("Umumkan Jenis Kolom Kata Sandi via Suara (Aman: Tidak Baca Isi Sandi)");
        swFieldAnnounce.setChecked(prefs.getBoolean("tts_field_type_announce", true));
        swFieldAnnounce.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("tts_field_type_announce", isChecked).apply();
            }
        });
        layout.addView(swFieldAnnounce);

        final android.widget.Switch swAdvancedEdit = new android.widget.Switch(this);
        swAdvancedEdit.setText("Aktifin mode edit lanjutan");
        swAdvancedEdit.setChecked(prefs.getBoolean("advanced_edit_mode_enabled", false));
        layout.addView(swAdvancedEdit);
        android.widget.TextView advancedHelp = new android.widget.TextView(this);
        advancedHelp.setText("Jika dimatikan, keyboard tetap memakai perilaku lama. Jika diaktifkan, tekan singkat tombol Hapus tetap menghapus satu karakter, sedangkan tekan lama mengikuti pilihan di bawah. Mode ini opsional agar pengguna pemula tidak mendapat fungsi tambahan yang tidak diperlukan.");
        advancedHelp.setTextSize(14);
        advancedHelp.setPadding(8, 0, 8, 12);
        layout.addView(advancedHelp);
        final android.widget.Button bLongDeleteMode = new android.widget.Button(this);
        final String[] deleteModeLabels = {"Satu karakter", "Satu kata", "Satu paragraf"};
        final String[] deleteModeValues = {"character", "word", "paragraph"};
        final Runnable updateDeleteModeLabel = new Runnable() {
            @Override public void run() {
                String savedMode = prefs.getString("advanced_long_delete_mode", "word");
                int index = 1;
                for (int i = 0; i < deleteModeValues.length; i++) if (deleteModeValues[i].equals(savedMode)) index = i;
                bLongDeleteMode.setText("Aksi tahan tombol Hapus: " + deleteModeLabels[index]);
                bLongDeleteMode.setEnabled(swAdvancedEdit.isChecked());
                bLongDeleteMode.setAlpha(swAdvancedEdit.isChecked() ? 1.0f : 0.5f);
            }
        };
        bLongDeleteMode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("Pilih aksi saat tombol Hapus ditahan")
                    .setItems(new String[]{"Hapus satu karakter — buat koreksi biasa", "Hapus satu kata di belakang kursor", "Hapus satu paragraf sampai batas sebelumnya"}, new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface dialog, int which) {
                            prefs.edit().putString("advanced_long_delete_mode", deleteModeValues[which]).apply();
                            updateDeleteModeLabel.run();
                        }
                    }).setNegativeButton("Batal", null).show();
            }
        });
        layout.addView(bLongDeleteMode);
        updateDeleteModeLabel.run();

        final android.widget.Button bHoldDeleteDuration = new android.widget.Button(this);
        bHoldDeleteDuration.setText("Durasi Tahan Hapus Kata: " + prefs.getInt("delay_hapus_ms", 600) + " ms");
        bHoldDeleteDuration.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] items = {
                    "30 ms (Ekstrem Kilat)",
                    "50 ms (Super Cepat)",
                    "120 ms (Cepat)",
                    "200 ms (Cepat Terukur)",
                    "300 ms (Standar Nyaman)",
                    "400 ms (Normal Aman)",
                    "600 ms (Santai Terkontrol)",
                    "1 Detik (1000 ms)",
                    "1.5 Detik (1500 ms)",
                    "2 Detik (2000 ms)",
                    "3 Detik (3000 ms)",
                    "4 Detik (4000 ms)",
                    "5 Detik (5000 ms)",
                    "10 Detik (10000 ms)"
                };
                final int[] values = {30, 50, 120, 200, 300, 400, 600, 1000, 1500, 2000, 3000, 4000, 5000, 10000};
                showListDialog("Berapa Detik Ditahan untuk Hapus Kata?", items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putInt("delay_hapus_ms", values[which]).apply();
                        bHoldDeleteDuration.setText("Durasi Tahan Hapus Kata: " + values[which] + " ms");
                        Toast.makeText(MainActivity.this, "Durasi tahan tombol Hapus disimpan: " + items[which], Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
        layout.addView(bHoldDeleteDuration);

        swAdvancedEdit.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean checked) {
                prefs.edit().putBoolean("advanced_edit_mode_enabled", checked).apply();
                updateDeleteModeLabel.run();
                Toast.makeText(MainActivity.this, checked ? "Mode edit lanjutan aktif. Tekan sebentar tetap hapus satu karakter." : "Mode edit lanjutan mati. Cara hapus yang biasa tetap dipakai.", Toast.LENGTH_LONG).show();
            }
        });

        final android.widget.Button bSettingsGuide = new android.widget.Button(this);
        bSettingsGuide.setText("Baca penjelasan pengaturan dan fitur AI");
        bSettingsGuide.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("Panduan pengaturan yang ramah pembaca layar")
                    .setMessage("MODE KEYBOARD\n" +
                        "Pilih QWERTY Latin atau keyboard Arab untuk mengetik biasa. Mode Braille dipilih dari pengaturan; QWERTY tetap menjadi mode awal kecuali kamu memilih mode lain.\n\n" +
                        "BARIS ANGKA\n" +
                        "Menampilkan angka di bagian atas keyboard agar angka bisa dimasukkan tanpa berpindah halaman.\n\n" +
                        "TOMBOL EMOJI\n" +
                        "Mengatur apakah tombol pembuka daftar emoji ditampilkan di samping Spasi. Menonaktifkannya membuat tampilan lebih sederhana.\n\n" +
                        "AKSESIBILITAS & EDITING TEKS\n" +
                        "Fitur tambahan ini nonaktif secara bawaan. Jika aktif, tekan singkat Hapus tetap menghapus satu karakter. Tekan lama dapat diatur untuk menghapus karakter, kata, atau paragraf. Contoh: pilih \"Satu kata\" untuk menghapus kata \"halo\" sekaligus saat kursor berada setelah kata itu.\n\n" +
                        "SUARA KETIKAN\n" +
                        "Mengatur efek bunyi dan volume internal keyboard. Pengaturan ini hanya mengatur keras-lemahnya efek suara ketikan keyboard, bukan volume media atau notifikasi.\n\n" +
                        "FITUR AI DAN LOGIKA AI\n" +
                        "Fitur AI seperti Smart Reply, revisi teks, dan input suara membantu menyusun atau memperbaiki teks berdasarkan konteks yang tersedia. Hasil AI adalah saran yang perlu diperiksa sebelum dikirim. Jika tidak ingin fitur tambahan mengganggu, gunakan tombol fitur saat dibutuhkan dan matikan izin atau opsi yang tidak dipakai.\n\n" +
                        "STIKER (TOMBOL 🧩)\n" +
                        "Ketuk tombol 🧩 di sebelah kanan Spasi untuk memilih stiker teks atau gambar. Pada Pengelola Stiker, kamu bisa menambahkan gambar dari pemilih berkas Android, membuat stiker teks sendiri, mengelola koleksi, dan mencari lebih dari 1.000 kombinasi emoji/teks. Stiker gambar hanya dapat disisipkan sebagai gambar pada aplikasi yang mendukung gambar dari keyboard; jika tidak didukung, gunakan fitur lampiran aplikasi tujuan. Koleksi disimpan secara lokal.\n\n" +
                        "AKSESIBILITAS\n" +
                        "Setiap pilihan sebaiknya diubah satu per satu lalu diuji di kolom teks. Hindari mengaktifkan banyak fitur sekaligus ketika sedang menguji agar mudah mengetahui perubahan mana yang berpengaruh.")
                    .setPositiveButton("Mengerti", null).show();
            }
        });
        layout.addView(bSettingsGuide);

        final android.widget.Switch swEng = new android.widget.Switch(this);
        swEng.setText("Tombol Spasi pakai teks Bahasa Inggris");
        swEng.setChecked(prefs.getBoolean("interface_english", false));
        swEng.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("interface_english", isChecked).apply();
            }
        });
        layout.addView(swEng);

        final android.widget.Switch swCap = new android.widget.Switch(this);
        swCap.setText("Otomatis Kapital di Awal Kalimat (Nonaktif otomatis di Email/Password)");
        swCap.setChecked(prefs.getBoolean("auto_capitalization", true));
        swCap.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("auto_capitalization", isChecked).apply();
            }
        });
        layout.addView(swCap);

        final android.widget.Switch swDbl = new android.widget.Switch(this);
        swDbl.setText("Spasi Ganda menjadi Titik");
        swDbl.setChecked(prefs.getBoolean("double_space_period", true));
        swDbl.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("double_space_period", isChecked).apply();
            }
        });
        layout.addView(swDbl);

        final android.widget.Switch swSpaceGlide = new android.widget.Switch(this);
        swSpaceGlide.setText("Geser Spasi untuk Gerak Kursor Kiri/Kanan");
        swSpaceGlide.setChecked(prefs.getBoolean("spacebar_cursor_glide", true));
        swSpaceGlide.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("spacebar_cursor_glide", isChecked).apply();
            }
        });
        layout.addView(swSpaceGlide);

        final android.widget.Switch swSwipeDelWord = new android.widget.Switch(this);
        swSwipeDelWord.setText("Geser Tombol Hapus ke Kiri = Hapus 1 Kata Sekaligus");
        swSwipeDelWord.setChecked(prefs.getBoolean("swipe_delete_word_enabled", true));
        swSwipeDelWord.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("swipe_delete_word_enabled", isChecked).apply();
            }
        });
        layout.addView(swSwipeDelWord);

        final android.widget.Switch swMacroExpand = new android.widget.Switch(this);
        swMacroExpand.setText("Ekspansi Singkatan / Makro Otomatis saat Tekan Spasi");
        swMacroExpand.setChecked(prefs.getBoolean("macro_auto_expand_enabled", true));
        swMacroExpand.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("macro_auto_expand_enabled", isChecked).apply();
            }
        });
        layout.addView(swMacroExpand);

        final android.widget.Switch swCalc = new android.widget.Switch(this);
        swCalc.setText("Kalkulator Mini Otomatis (=hitungan lalu Spasi, misal =15*3+5)");
        swCalc.setChecked(prefs.getBoolean("inline_calculator_enabled", true));
        swCalc.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("inline_calculator_enabled", isChecked).apply();
            }
        });
        layout.addView(swCalc);

        final android.widget.Switch swAutoSpacePunct = new android.widget.Switch(this);
        swAutoSpacePunct.setText("Spasi Otomatis Setelah Tanda Baca (, ? ! ;)");
        swAutoSpacePunct.setChecked(prefs.getBoolean("auto_space_after_punct", false));
        swAutoSpacePunct.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("auto_space_after_punct", isChecked).apply();
            }
        });
        layout.addView(swAutoSpacePunct);

        final android.widget.Switch swSfxKetik = new android.widget.Switch(this);
        swSfxKetik.setText("Bunyi Efek Suara Ketikan (QWERTY)");
        swSfxKetik.setChecked(prefs.getBoolean("sound_ketik_enabled", true));
        swSfxKetik.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("sound_ketik_enabled", isChecked).apply();
            }
        });
        layout.addView(swSfxKetik);

        final android.widget.Button bSoundPreset = new android.widget.Button(this);
        bSoundPreset.setText("Efek Suara: " + prefs.getString("sound_effect_name", "Key Pack 2 (10 Variasi + Space/Delete)"));
        layout.addView(bSoundPreset);

        final android.widget.Button bSoundVolume = new android.widget.Button(this);
        bSoundVolume.setText("Volume Internal Keyboard: " + prefs.getInt("sound_effect_volume", 70) + "%");
        layout.addView(bSoundVolume);
        bSoundVolume.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                final android.widget.LinearLayout box = new android.widget.LinearLayout(MainActivity.this);
                box.setOrientation(android.widget.LinearLayout.VERTICAL);
                int pad = (int) (20 * getResources().getDisplayMetrics().density);
                box.setPadding(pad, pad, pad, pad);
                final android.widget.TextView label = new android.widget.TextView(MainActivity.this);
                final android.widget.SeekBar seek = new android.widget.SeekBar(MainActivity.this);
                seek.setMax(100);
                seek.setProgress(prefs.getInt("sound_effect_volume", 70));
                label.setText("Volume efek ketikan: " + seek.getProgress() + "%");
                box.addView(label); box.addView(seek);
                seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(android.widget.SeekBar b, int value, boolean fromUser) { label.setText("Volume efek ketikan: " + value + "%"); }
                    @Override public void onStartTrackingTouch(android.widget.SeekBar b) {}
                    @Override public void onStopTrackingTouch(android.widget.SeekBar b) {}
                });
                new android.app.AlertDialog.Builder(MainActivity.this).setTitle("Volume Internal Keyboard")
                    .setView(box).setPositiveButton("Simpan", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int which) {
                            int value = seek.getProgress();
                            prefs.edit().putInt("sound_effect_volume", value).remove("sound_effect_stream").apply();
                            bSoundVolume.setText("Volume Internal Keyboard: " + value + "%");
                            android.widget.Toast.makeText(MainActivity.this, "Volume efek ketikan internal keyboard disimpan.", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }).setNegativeButton("Batal", null).show();
            }
        });
        bSoundPreset.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                final String[] presetNames = {
                    "Blue Switch Mechanical (Clicky)",
                    "Red Switch Mechanical",
                    "Brown Switch Tactile (Multi Sample)",
                    "Android Keyboard Classic",
                    "Android Keyboard Space Style",
                    "iPhone Keyboard",
                    "Key Pack 1 (10 Variasi Ketik)",
                    "Key Pack 2 (10 Variasi + Space/Delete)",
                    "Samsung Keyboard 1",
                    "Samsung Keyboard 2",
                    "Classic Typewriter (Mesin Tik)",
                    "Perkins Braille Keyboard",
                    "File Audio Custom Sendiri (MP3 / WAV)"
                };
                final String[] presetKeys = {
                    "1blueSwitch",
                    "2redSwitch",
                    "3brownSwitch",
                    "androidKeyboard1",
                    "androidKeyboard2",
                    "iPhoneKeyboard",
                    "key1",
                    "key2",
                    "samsung1",
                    "samsung2",
                    "Classic_Typewriter",
                    "Perkins_Braill",
                    "custom"
                };
                showListDialog("Pilih Efek Suara Ketikan", presetNames, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int i) {
                        if (presetKeys[i].equals("custom")) {
                            OfficeUtils.launchFilePicker(MainActivity.this, PICK_AUDIO_KETIK, "audio", "Pilih Audio Efek Ketikan");
                            return;
                        }
                        prefs.edit()
                            .putString("sound_effect_key", presetKeys[i])
                            .putString("sound_effect_name", presetNames[i])
                            .apply();
                        bSoundPreset.setText("Efek Suara: " + presetNames[i]);
                        // Preview: putar salah satu file dari pack assets
                        try {
                            String[] files = getAssets().list("typingsound/" + presetKeys[i]);
                            String pick = null;
                            if (files != null) {
                                for (String f : files) {
                                    String low = f.toLowerCase();
                                    if (low.contains("typing") || low.contains("type") || low.startsWith("s") || low.contains("letter")) {
                                        pick = f; break;
                                    }
                                }
                                if (pick == null && files.length > 0) pick = files[0];
                            }
                            if (pick != null) {
                                java.io.InputStream is = getAssets().open("typingsound/" + presetKeys[i] + "/" + pick);
                                java.io.File cache = new java.io.File(getCacheDir(), "preview_" + pick);
                                java.io.FileOutputStream fos = new java.io.FileOutputStream(cache);
                                byte[] buf = new byte[8192]; int n;
                                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                                fos.close(); is.close();
                                android.media.MediaPlayer mp = new android.media.MediaPlayer();
                                mp.setDataSource(cache.getAbsolutePath());
                                mp.prepare();
                                mp.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                                    @Override public void onCompletion(android.media.MediaPlayer mediaPlayer) { mediaPlayer.release(); }
                                });
                                mp.start();
                            }
                        } catch (Exception ex) {}
                        android.widget.Toast.makeText(MainActivity.this, "Efek Suara Aktif: " + presetNames[i], android.widget.Toast.LENGTH_SHORT).show();
                    }

                });

            }
        });

        android.widget.Button btnPilihKetik = new android.widget.Button(this); 
        btnPilihKetik.setText("PILIH BERKAS AUDIO SENDIRI (CUSTOM MP3/WAV)"); 
        layout.addView(btnPilihKetik);
        btnPilihKetik.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { 
                OfficeUtils.launchFilePicker(MainActivity.this, PICK_AUDIO_KETIK, "audio", "Pilih Audio Efek Ketikan");
            }
        });

        final android.widget.Button btnResetKetik = new android.widget.Button(this);
        btnResetKetik.setText("↺ RESET KE SUARA MECHANICAL BAWAAN");
        layout.addView(btnResetKetik);
        btnResetKetik.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                java.io.File f = new java.io.File(getFilesDir(), "custom_ketik.mp3");
                if (f.exists()) f.delete();
                prefs.edit()
                    .putString("sound_effect_key", "key2")
                    .putString("sound_effect_name", "Key Pack 2 (10 Variasi + Space/Delete)")
                    .apply();
                bSoundPreset.setText("Efek Suara: Key Pack 2 (10 Variasi + Space/Delete)");
                android.widget.Toast.makeText(MainActivity.this, "Kembali ke Key Pack 2!", android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        layout.addView(createHeader("SENSITIVITAS KEYBOARD", "#E91E63"));
        final android.widget.Button bSens = new android.widget.Button(this);
        bSens.setText("Kecepatan Ketik: " + prefs.getString("typing_speed", "Super Dewa (0 ms - Instan Tanpa Jeda)"));
        layout.addView(bSens);
        bSens.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] items = {
                    "Super Dewa (0 ms - Instan Tanpa Jeda)",
                    "Kilat Ekstrem (15 ms)",
                    "Responsif Cepat (40 ms)",
                    "Cepat Natural (80 ms)",
                    "Standar Nyaman (120 ms)",
                    "Santai Terukur (180 ms)",
                    "Perlahan Terkendali (250 ms)",
                    "Sangat Perlahan (350 ms)",
                    "Mode Aman Anti-Typo (500 ms)"
                };
                final int[] values = {0, 15, 40, 80, 120, 180, 250, 350, 500};
                showListDialog("Pilih Sensitivitas & Kecepatan Ketik", items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int i) {
                        prefs.edit().putString("typing_speed", items[i]).putInt("typing_speed_delay_ms", values[i]).apply();
                        bSens.setText("Kecepatan Ketik: " + items[i]);
                    }
                });
            }
        });
        android.widget.TextView tvInfo = new android.widget.TextView(this);
        tvInfo.setText("Pilih 'Super Dewa' untuk respon instan tanpa jeda sama sekali.");
        tvInfo.setTextSize(12);
        tvInfo.setTextColor(android.graphics.Color.GRAY);
        layout.addView(tvInfo);

        final android.widget.Button bTouchResponse = new android.widget.Button(this);
        bTouchResponse.setText("Respons Sentuh: " + prefs.getString("touch_response_profile", "Instan Maksimal (0 ms)"));
        layout.addView(bTouchResponse);
        bTouchResponse.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] touchItems = {
                    "Super Extreme (0 — minim kerja sentuh & tanpa pengumuman duplikat)",
                    "Super Ngebut (5 — minim kerja sentuh)",
                    "Responsif (10 — pengumuman tambahan aktif)",
                    "Seimbang (20)",
                    "Stabil (35)",
                    "Umpan Balik Penuh (50)"
                };
                final int[] touchValues = {0, 5, 10, 20, 35, 50};
                showListDialog("Pilih Respons Sentuh QWERTY", touchItems, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int i) {
                        prefs.edit().putString("touch_response_profile", touchItems[i])
                            .putInt("touch_response_ms", touchValues[i]).apply();
                        bTouchResponse.setText("Respons Sentuh: " + touchItems[i]);
                    }
                });
            }
        });
        android.widget.TextView tvTouchInfo = new android.widget.TextView(this);
        tvTouchInfo.setText("Profil ini tidak menambahkan timer ke ketikan. Prioritas Maksimal mengurangi kerja suara/getar saat jari baru menyentuh atau meraba tombol; bunyi/getar saat tombol diaktifkan tetap mengikuti pengaturan. Pengumuman pembaca layar dipertahankan. Tidak ada aplikasi yang dapat menjamin 0 ms mutlak karena Android, perangkat, dan pembaca layar tetap memproses sentuhan.");
        tvTouchInfo.setTextSize(12);
        tvTouchInfo.setTextColor(android.graphics.Color.GRAY);
        layout.addView(tvTouchInfo);

        layout.addView(createHeader("KEYBOARD BRAILLE & SUARA TTS MANDIRI", "#00BCD4"));

        final android.widget.Switch swBrailleDef = new android.widget.Switch(this);
        swBrailleDef.setText("Jadikan Keyboard Braille sebagai Default");
        swBrailleDef.setChecked(prefs.getBoolean("braille_default_mode", false));
        swBrailleDef.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("braille_default_mode", isChecked).apply();
            }
        });
        layout.addView(swBrailleDef);

        // ===== GESTURE KIRIM BRAILLE (bisa diatur) =====
        layout.addView(createHeader("GESTURE KIRIM DI BRAILLE", "#00897B"));

        final android.widget.Switch swSend3Finger = new android.widget.Switch(this);
        swSend3Finger.setText("Geser 3 jari ke kanan = Kirim");
        swSend3Finger.setChecked(prefs.getBoolean("braille_send_3finger_right", true));
        swSend3Finger.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("braille_send_3finger_right", isChecked).apply();
            }
        });
        layout.addView(swSend3Finger);

        final android.widget.Switch swSendLongPress = new android.widget.Switch(this);
        swSendLongPress.setText("Tahan jari (long-press) = Kirim");
        swSendLongPress.setChecked(prefs.getBoolean("braille_send_longpress_enabled", true));
        swSendLongPress.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("braille_send_longpress_enabled", isChecked).apply();
            }
        });
        layout.addView(swSendLongPress);

        final android.widget.Button bSendFingers = new android.widget.Button(this);
        int curFingers = prefs.getInt("braille_send_longpress_fingers", 2);
        bSendFingers.setText("Jumlah jari tahan kirim: " + curFingers + " jari");
        layout.addView(bSendFingers);
        bSendFingers.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] opts = new String[] { "2 jari", "3 jari" };
                int cur = prefs.getInt("braille_send_longpress_fingers", 2);
                int checked = (cur >= 3) ? 1 : 0;
                UiKit.dialog(MainActivity.this, prefs)
                    .setTitle("Jumlah jari untuk tahan = Kirim")
                    .setSingleChoiceItems(opts, checked, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            int val = (which == 1) ? 3 : 2;
                            prefs.edit().putInt("braille_send_longpress_fingers", val).apply();
                            bSendFingers.setText("Jumlah jari tahan kirim: " + val + " jari");
                            dialog.dismiss();
                        }
                    })
                    .setNegativeButton("Batal", null)
                    .show();
            }
        });

        final android.widget.Button bSendHoldMs = new android.widget.Button(this);
        int curMs = prefs.getInt("braille_send_longpress_ms", 2500);
        bSendHoldMs.setText("Durasi tahan kirim: " + (curMs / 1000.0) + " detik");
        layout.addView(bSendHoldMs);
        bSendHoldMs.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] opts = new String[] { "2 detik", "2,5 detik", "3 detik" };
                int cur = prefs.getInt("braille_send_longpress_ms", 2500);
                int checked = 1;
                if (cur <= 2000) checked = 0;
                else if (cur >= 3000) checked = 2;
                UiKit.dialog(MainActivity.this, prefs)
                    .setTitle("Durasi tahan untuk Kirim")
                    .setMessage("Kalau di bawah durasi ini, dianggap ketikan biasa (chord), bukan kirim.")
                    .setSingleChoiceItems(opts, checked, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            int val = 2500;
                            if (which == 0) val = 2000;
                            else if (which == 2) val = 3000;
                            prefs.edit().putInt("braille_send_longpress_ms", val).apply();
                            bSendHoldMs.setText("Durasi tahan kirim: " + (val / 1000.0) + " detik");
                            dialog.dismiss();
                        }
                    })
                    .setNegativeButton("Batal", null)
                    .show();
            }
        });

        android.widget.TextView tvSendInfo = new android.widget.TextView(this);
        tvSendInfo.setText("Tips: Geser 3 jari ke kanan langsung kirim. Atau tahan 2/3 jari diam sesuai durasi di atas. Switch \"Langsung Kirim Otomatis\" di Utilitas juga tetap jalan setelah AI selesai.");
        tvSendInfo.setTextSize(12);
        tvSendInfo.setTextColor(android.graphics.Color.GRAY);
        tvSendInfo.setPadding(0, 8, 0, 16);
        layout.addView(tvSendInfo);

        final android.widget.Switch swBrailleSpeech = new android.widget.Switch(this);
        swBrailleSpeech.setText("Suara Pengucapan Braille & Emoji (TTS)");
        swBrailleSpeech.setChecked(prefs.getBoolean("braille_speech_enabled", true));
        swBrailleSpeech.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("braille_speech_enabled", isChecked).apply();
            }
        });
        layout.addView(swBrailleSpeech);

        final android.widget.Button bBrailleRate = new android.widget.Button(this);
        float curRate = prefs.getFloat("braille_tts_rate", 1.0f);
        bBrailleRate.setText("Kecepatan Bicara TTS: " + curRate + "x");
        layout.addView(bBrailleRate);
        bBrailleRate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final LinearLayout rateBox = new LinearLayout(MainActivity.this);
                rateBox.setOrientation(LinearLayout.VERTICAL);
                rateBox.setPadding(36, 24, 36, 8);
                final TextView rateLabel = new TextView(MainActivity.this);
                final SeekBar rateSlider = new SeekBar(MainActivity.this);
                rateSlider.setMax(350); // 0.5x sampai 4.0x, interval 0.01x
                float saved = prefs.getFloat("braille_tts_rate", 1.0f);
                rateSlider.setProgress(Math.max(0, Math.min(350, Math.round((saved - 0.5f) * 100))));
                rateLabel.setText("Kecepatan bicara: " + String.format(java.util.Locale.US, "%.2fx", saved));
                rateSlider.setContentDescription("Atur kecepatan bicara TTS dari 0,5 sampai 4 kali");
                rateSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                        float rate = 0.5f + (progress / 100.0f);
                        rateLabel.setText("Kecepatan bicara: " + String.format(java.util.Locale.US, "%.2fx", rate));
                    }
                    @Override public void onStartTrackingTouch(SeekBar bar) {}
                    @Override public void onStopTrackingTouch(SeekBar bar) {}
                });
                rateBox.addView(rateLabel);
                rateBox.addView(rateSlider);
                final AlertDialog rateDialog = UiKit.dialog(MainActivity.this, prefs)
                    .setTitle("Atur Kecepatan TTS")
                    .setMessage("Geser ke kiri untuk lambat, ke kanan untuk cepat.")
                    .setView(rateBox)
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Simpan", null)
                    .create();
                rateDialog.setOnShowListener(ignored -> rateDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
                    float rate = 0.5f + (rateSlider.getProgress() / 100.0f);
                    prefs.edit().putFloat("braille_tts_rate", rate).apply();
                    bBrailleRate.setText("Kecepatan Bicara TTS: " + String.format(java.util.Locale.US, "%.2fx", rate));
                    Toast.makeText(MainActivity.this, "Kecepatan TTS disimpan", Toast.LENGTH_SHORT).show();
                    rateDialog.dismiss();
                }));
                rateDialog.show();
            }
        });

        final android.widget.Button bBraillePitch = new android.widget.Button(this);
        float savedPitch = prefs.getFloat("braille_tts_pitch", 1.0f);
        bBraillePitch.setText("Nada TTS: " + String.format(java.util.Locale.US, "%.2fx", savedPitch));
        bBraillePitch.setContentDescription("Atur tinggi rendah nada suara TTS");
        layout.addView(bBraillePitch);
        bBraillePitch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final LinearLayout pitchBox = new LinearLayout(MainActivity.this);
                pitchBox.setOrientation(LinearLayout.VERTICAL);
                pitchBox.setPadding(36, 24, 36, 8);
                final TextView pitchLabel = new TextView(MainActivity.this);
                final SeekBar pitchSlider = new SeekBar(MainActivity.this);
                pitchSlider.setMax(150); // 0,5x sampai 2,0x
                float saved = prefs.getFloat("braille_tts_pitch", 1.0f);
                pitchSlider.setProgress(Math.max(0, Math.min(150, Math.round((saved - 0.5f) * 100))));
                pitchLabel.setText("Nada suara: " + String.format(java.util.Locale.US, "%.2fx", saved));
                pitchSlider.setContentDescription("Atur nada TTS dari rendah ke tinggi");
                pitchSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                        pitchLabel.setText("Nada suara: " + String.format(java.util.Locale.US, "%.2fx", 0.5f + progress / 100.0f));
                    }
                    @Override public void onStartTrackingTouch(SeekBar bar) {}
                    @Override public void onStopTrackingTouch(SeekBar bar) {}
                });
                pitchBox.addView(pitchLabel); pitchBox.addView(pitchSlider);
                AlertDialog pitchDialog = UiKit.dialog(MainActivity.this, prefs).setTitle("Atur Nada TTS")
                    .setMessage("Nilai rendah membuat suara lebih rendah; nilai tinggi membuat suara lebih tinggi. Dukungan akhir mengikuti mesin TTS.")
                    .setView(pitchBox).setNegativeButton("Batal", null).setPositiveButton("Simpan", null).create();
                pitchDialog.setOnShowListener(ignored -> pitchDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
                    float pitch = 0.5f + pitchSlider.getProgress() / 100.0f;
                    prefs.edit().putFloat("braille_tts_pitch", pitch).apply();
                    bBraillePitch.setText("Nada TTS: " + String.format(java.util.Locale.US, "%.2fx", pitch));
                    Toast.makeText(MainActivity.this, "Nada TTS disimpan", Toast.LENGTH_SHORT).show();
                    pitchDialog.dismiss();
                }));
                pitchDialog.show();
            }
        });

        final android.widget.Button bBrailleEngine = new android.widget.Button(this);
        String engName = prefs.getString("braille_tts_engine_name", "Default Sistem Android");
        bBrailleEngine.setText("Mesin Suara TTS: " + engName);
        layout.addView(bBrailleEngine);
        bBrailleEngine.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Mendeteksi seluruh mesin TTS yang terpasang (Vocalizer, AutoTTS, Google, Samsung, dsb)
                java.util.List<android.speech.tts.TextToSpeech.EngineInfo> engines = BrailleTTSManager.getAllAvailableEngines(MainActivity.this);
                final java.util.ArrayList<String> engineNames = new java.util.ArrayList<>();
                final java.util.ArrayList<String> enginePackages = new java.util.ArrayList<>();

                engineNames.add("Default Sistem Android");
                enginePackages.add("");

                if (engines != null) {
                    for (android.speech.tts.TextToSpeech.EngineInfo info : engines) {
                        engineNames.add(info.label + " (" + info.name + ")");
                        enginePackages.add(info.name);
                    }
                }

                showListDialog("Pilih Mesin Suara (Semua TTS Terdeteksi)", engineNames.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int i) {
                        String pkg = enginePackages.get(i);
                        String name = engineNames.get(i);
                        prefs.edit().putString("braille_tts_engine", pkg.isEmpty() ? null : pkg)
                                   .putString("braille_tts_engine_name", name).apply();
                        bBrailleEngine.setText("Mesin Suara TTS: " + name);
                        // Prefs sudah berubah; keyboard akan ensureEngineUpToDate()
                        // saat speak / onStartInputView — tanpa tutup aplikasi.
                        Toast.makeText(MainActivity.this, "Mesin TTS diganti: " + name + " (langsung aktif di keyboard)", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        final android.widget.Button bBrailleVoice = new android.widget.Button(this);
        String selectedVoice = prefs.getString("braille_tts_voice_name", "");
        bBrailleVoice.setText("Pilih Suara TTS: " + (selectedVoice.isEmpty() ? "Suara bawaan mesin" : selectedVoice));
        bBrailleVoice.setContentDescription("Pilih suara spesifik yang tersedia pada mesin TTS yang dipilih, termasuk suara Vocalizer jika terpasang");
        layout.addView(bBrailleVoice);
        bBrailleVoice.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTtsVoicePicker(); }
        });

        final android.widget.Button bSystemTts = new android.widget.Button(this);
        bSystemTts.setText("Kelola Mesin dan Suara TTS di Pengaturan Android");
        bSystemTts.setContentDescription("Buka pengaturan sistem untuk memasang atau memilih mesin TTS seperti Vocalizer, Google, atau Samsung, serta memilih suara yang tersedia");
        layout.addView(bSystemTts);
        bSystemTts.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent intent = new Intent("com.android.settings.TTS_SETTINGS");
                    if (intent.resolveActivity(getPackageManager()) == null) {
                        intent = new Intent(Settings.ACTION_SETTINGS);
                    }
                    startActivity(intent);
                } catch (Throwable e) {
                    try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                    catch (Throwable ignored) { Toast.makeText(MainActivity.this, "Pengaturan TTS Android tidak dapat dibuka di perangkat ini", Toast.LENGTH_LONG).show(); }
                }
            }
        });

        final android.widget.Button bCalibInfo = new android.widget.Button(this);
        boolean isCalib = prefs.getBoolean("braille_calibrated", false);
        bCalibInfo.setText("Status Kalibrasi Jari: " + (isCalib ? "Sudah Dikalibrasi (Sesuai Ukuran Jari)" : "Standar Bawaan"));
        layout.addView(bCalibInfo);
        bCalibInfo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("Kalibrasi Posisi Jari 6 Titik")
                    .setMessage("Fitur ini membuat posisi 6 titik Braille pas dengan ukuran tangan dan jari Anda agar tidak acak-acakan!\n\n"
                            + "Cara Kalibrasi:\n"
                            + "1. Buka Keyboard Braille di aplikasi apa pun (otomatis dipandu saat pertama buka).\n"
                            + "2. Ketuk tombol 'Kalibrasi' di bar bawah jika ingin kalibrasi ulang.\n"
                            + "3. Ikuti panduan suara: Sentuh posisi Titik 1 sampai Titik 6 sesuai posisi alami jari Anda, ATAU tempelkan ke-6 jari sekaligus di layar!\n\n"
                            + "Status saat ini: " + (prefs.getBoolean("braille_calibrated", false) ? "Sudah Dikalibrasi Khusus" : "Menggunakan Standar Bawaan"))
                    .setPositiveButton("OK", null)
                    .show();
            }
        });

        final android.widget.Button bResetCalib = new android.widget.Button(this);
        bResetCalib.setText("Reset Kalibrasi Jari ke Posisi Standar");
        layout.addView(bResetCalib);
        bResetCalib.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().remove("braille_calibrated").apply();
                for (int k = 0; k < 6; k++) {
                    prefs.edit().remove("braille_dot_x_" + k).remove("braille_dot_y_" + k).apply();
                }
                bCalibInfo.setText("Status Kalibrasi Jari: Standar Bawaan");
                Toast.makeText(MainActivity.this, "Kalibrasi jari dikembalikan ke posisi standar!", Toast.LENGTH_SHORT).show();
            }
        });

        layout.addView(createHeader("GAYA MENGETIK (AKSESIBILITAS)", "#9C27B0"));
        final android.widget.Button bMode = new android.widget.Button(this);
        bMode.setText("Mode Mengetik: " + prefs.getString("akses_ngetik_mode", "Angkat Jari (Standar)"));
        layout.addView(bMode);
        bMode.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] items = {"Angkat Jari (Standar)", "Ketuk Ganda (Double Click)"};
                showListDialog("Pilih Gaya Mengetik", items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int i) {
                        boolean isDouble = items[i].contains("Ketuk Ganda");
                        prefs.edit()
                            .putString("akses_ngetik_mode", items[i])
                            .putBoolean("double_tap_mode", isDouble)
                            .apply();
                        bMode.setText("Mode Mengetik: " + items[i]);
                    }
                });
            }
        });

        final android.widget.Button bDoubleTapSpeed = new android.widget.Button(this);
        int dtVal = prefs.getInt("double_tap_delay_ms", 700);
        bDoubleTapSpeed.setText("Jeda Ketuk Ganda: " + dtVal + " ms");
        layout.addView(bDoubleTapSpeed);
        bDoubleTapSpeed.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] items = {
                    "Super Cepat (350 ms)",
                    "Cepat (500 ms)",
                    "Standar (700 ms)",
                    "Santai (900 ms)",
                    "Sangat Santai (1200 ms)"
                };
                final int[] values = {350, 500, 700, 900, 1200};
                showListDialog("Pilih Jeda Ketuk Ganda", items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int i) {
                        prefs.edit().putInt("double_tap_delay_ms", values[i]).apply();
                        bDoubleTapSpeed.setText("Jeda Ketuk Ganda: " + values[i] + " ms");
                    }
                });
            }
        });

        layout.addView(createHeader("SENSITIVITAS HAPUS (TAHAN LAMA)", "#D32F2F"));
        final android.widget.Button bHapus = new android.widget.Button(this);
        int currentDelay = prefs.getInt("delay_hapus_ms", 600);
        bHapus.setText("Kecepatan Hapus: " + currentDelay + " ms (" + getDeleteSpeedDescription(currentDelay) + ")");
        layout.addView(bHapus);
        bHapus.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(View v) {
                final String[] items = {
                    "Ekstrem Kilat (30 ms)",
                    "Super Cepat (50 ms)",
                    "Sangat Cepat (80 ms)",
                    "Cepat Lancar (120 ms)",
                    "Cepat Terukur (200 ms)",
                    "Standar Nyaman (300 ms)",
                    "Normal Aman (400 ms)",
                    "Santai Terkontrol (600 ms)",
                    "1 Detik (1000 ms)",
                    "1.5 Detik (1500 ms)",
                    "2 Detik (2000 ms)",
                    "3 Detik (3000 ms)",
                    "4 Detik (4000 ms)",
                    "5 Detik (5000 ms)",
                    "10 Detik (10000 ms)"
                };
                final int[] values = {30, 50, 80, 120, 200, 300, 400, 600, 1000, 1500, 2000, 3000, 4000, 5000, 10000};
                showListDialog("Pilih Kecepatan Hapus", items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int i) {
                        prefs.edit().putInt("delay_hapus_ms", values[i]).apply();
                        bHapus.setText("Kecepatan Hapus: " + values[i] + " ms (" + getDeleteSpeedDescription(values[i]) + ")");
                    }
                });
            }
        });

        // Ubah formulir panjang menjadi halaman kategori. Kontrol dan key SharedPreferences
        // tetap dipakai; yang berubah hanya cara pengaturannya ditampilkan.
        final String[] categoryNames = {
            "Mode Keyboard & Panduan",
            "Ukuran Keyboard & Getaran",
            "Tampilan & Tombol",
            "Aksesibilitas & Edit Teks",
            "Respons Sentuh & Hapus",
            "Braille & Suara TTS"
        };
        final String[] categoryDescriptions = {
            "Pilih QWERTY/Braille dan buka panduan penggunaan.",
            "Atur tinggi keyboard, getaran, dan kekuatan getar.",
            "Atur tema, ukuran huruf, baris angka, emoji, dan tombol stiker.",
            "Atur fitur aksesibilitas dan perilaku editing teks.",
            "Atur sensitivitas serta kecepatan tekan lama tombol Hapus.",
            "Atur mesin TTS, suara, kecepatan, nada, dan pengucapan Braille."
        };
        final int[] categoryColors = {
            UiKit.ACCENT_WARM,
            UiKit.ACCENT_AI,
            UiKit.ACCENT_OFFICE,
            UiKit.ACCENT_AI_ALT,
            UiKit.ACCENT_DANGER,
            UiKit.ACCENT_KB
        };
        final ArrayList<ArrayList<View>> categoryViews = new ArrayList<ArrayList<View>>();
        for (int i = 0; i < categoryNames.length; i++) categoryViews.add(new ArrayList<View>());
        int currentCategory = 0;
        for (int i = 0; i < layout.getChildCount(); i++) {
            View child = layout.getChildAt(i);
            if (child instanceof TextView) {
                String headerText = ((TextView) child).getText().toString().trim();
                if ("PILIH MODE KEYBOARD UTAMA".equals(headerText)) currentCategory = 0;
                else if ("UKURAN KEYBOARD".equals(headerText) || "GETAR SAAT DITEKAN".equals(headerText)) currentCategory = 1;
                else if ("TAMPILAN & ESTETIKA".equals(headerText) || "FITUR KETIK & LAYOUT".equals(headerText)) currentCategory = 2;
                else if ("AKSESIBILITAS & EDIT TEKS".equals(headerText) || "GAYA MENGETIK (AKSESIBILITAS)".equals(headerText)) currentCategory = 3;
                else if ("SENSITIVITAS KEYBOARD".equals(headerText) || "SENSITIVITAS HAPUS (TAHAN LAMA)".equals(headerText)) currentCategory = 4;
                else if ("KEYBOARD BRAILLE & SUARA TTS MANDIRI".equals(headerText)) currentCategory = 5;
            }
            categoryViews.get(currentCategory).add(child);
        }
        // Lepaskan kontrol dari formulir lama sebelum dipindahkan ke halaman kategori.
        layout.removeAllViews();

        for (int i = 0; i < categoryNames.length; i++) {
            final int categoryIndex = i;
            Button categoryButton = UiKit.makeMenuButton(this, categoryNames[i], categoryDescriptions[i], categoryColors[i], prefs, v -> {
                LinearLayout sectionLayout = new LinearLayout(MainActivity.this);
                sectionLayout.setOrientation(LinearLayout.VERTICAL);
                sectionLayout.setPadding(UiKit.dp(MainActivity.this, 16), UiKit.dp(MainActivity.this, 12),
                        UiKit.dp(MainActivity.this, 16), UiKit.dp(MainActivity.this, 16));
                for (View settingView : categoryViews.get(categoryIndex)) {
                    if (settingView.getParent() instanceof android.view.ViewGroup) {
                        ((android.view.ViewGroup) settingView.getParent()).removeView(settingView);
                    }
                    sectionLayout.addView(settingView);
                }
                addSettingDescriptions(sectionLayout);
                styleGeneratedControls(sectionLayout);
                ScrollView sectionScroll = new ScrollView(MainActivity.this);
                sectionScroll.setBackgroundColor(UiKit.bg(prefs));
                sectionScroll.addView(sectionLayout);
                pushSubPage(categoryNames[categoryIndex], sectionScroll);
            });
            targetBox.addView(categoryButton);
        }
    }
        private static final String PANDUAN_MIKROFON =
        "Saat telepon WhatsApp\n\n"
        + "1. Aktifkan layanan aksesibilitas Keyboard AI.\n"
        + "2. Izinkan tampil di atas aplikasi lain (agar penanda Merekam muncul).\n"
        + "3. Whisper: paling stabil saat telepon.\n"
        + "4. Pengenal Google saat telepon butuh Android 13+. Jika gagal, nyalakan Whisper.\n\n"
        + "Penanda merah Merekam harus muncul di atas layar saat merekam.";

    private void showAudioDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 12), UiKit.dp(this, 16), UiKit.dp(this, 16));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(UiKit.bg(prefs));
        scroll.addView(layout);

        layout.addView(createHeader("GOOGLE SPEECH RECOGNITION (SUARA PELAN & OTOMATIS)", "#2196F3"));
        final Switch swSoftBoost = new Switch(this);
        swSoftBoost.setText("Mode Suara Pelan / Bisik (Penguat AGC Android 13+)");
        swSoftBoost.setChecked(prefs.getBoolean("stt_soft_voice_boost", true));
        swSoftBoost.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("stt_soft_voice_boost", c).apply());
        layout.addView(swSoftBoost);

        final Button btnMicLang = new Button(this);
        btnMicLang.setText("Bahasa yang dikenali: " + prefs.getString("bahasa_mikrofon", prefs.getString("lang_src", "Otomatis")));
        layout.addView(btnMicLang);
        btnMicLang.setOnClickListener(v -> {
            final String[] list = {"Otomatis", "Indonesia (id-ID)", "Inggris (en-US)", "Arab (ar-SA)", "Jepang (ja-JP)", "Korea (ko-KR)", "Jerman", "Prancis"};
            showListDialog("Pilih Bahasa Input", list, (d, i) -> {
                prefs.edit().putString("bahasa_mikrofon", list[i]).putString("lang_src", list[i]).apply();
                btnMicLang.setText("Bahasa yang dikenali: " + list[i]);
            });
        });

        layout.addView(createHeader("MODE INPUT SUARA CADANGAN (WHISPER)", "#00BCD4"));
        final android.widget.Switch swWhisper = new android.widget.Switch(this);
        swWhisper.setText("Pakai Whisper (opsional untuk API eksternal atau Offline Premium)");
        swWhisper.setChecked(prefs.getBoolean("use_whisper", false));
        layout.addView(swWhisper);

        final Button btnWspMode = new Button(this);
        final Button btnOfflineModels = new Button(this);
        final Runnable refreshAudioWhisperUi = () -> {
            String curProv = prefs.getString("whisperProvider", "Google Gemini");
            btnWspMode.setText("Provider Whisper: " + curProv);
            String mFile = WhisperOfflineManager.getSelectedModelFileName(prefs);
            boolean installed = WhisperOfflineManager.isModelInstalled(MainActivity.this, mFile);
            btnOfflineModels.setText("Kelola Model Whisper Offline: "
                    + WhisperOfflineManager.findModel(mFile).label
                    + (installed ? " [Siap]" : " [Belum Unduh]"));
        };
        refreshAudioWhisperUi.run();
        layout.addView(btnWspMode);
        layout.addView(btnOfflineModels);

        swWhisper.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && WhisperOfflineManager.isOfflineProvider(prefs)) {
                if (!AuthManager.isPremiumActive(MainActivity.this)) {
                    buttonView.setChecked(false);
                    AuthManager.showPremiumRequiredDialog(MainActivity.this, "Whisper Offline (Lokal)", null);
                    return;
                }
                prefs.edit().putBoolean("use_whisper", true).apply();
                if (!WhisperOfflineManager.isSelectedModelInstalled(MainActivity.this, prefs)) {
                    WhisperOfflineManager.showMissingModelWarning(MainActivity.this, prefs, refreshAudioWhisperUi);
                }
                return;
            }
            prefs.edit().putBoolean("use_whisper", isChecked).apply();
        });

        btnWspMode.setOnClickListener(v -> {
            final String[] provs = {
                    "Google Gemini",
                    "Transkripsi Gemini",
                    "Groq (Cepat)",
                    "Grok (xAI)",
                    "OpenAI",
                    WhisperOfflineManager.PROVIDER_OFFLINE
            };
            showListDialog("Pilih Provider Whisper", provs, (d, i) -> {
                String chosen = provs[i];
                if (WhisperOfflineManager.PROVIDER_OFFLINE.equals(chosen)) {
                    if (!AuthManager.isPremiumActive(MainActivity.this)) {
                        AuthManager.showPremiumRequiredDialog(MainActivity.this, "Whisper Offline (Lokal)", null);
                        return;
                    }
                    String mFile = WhisperOfflineManager.getSelectedModelFileName(prefs);
                    prefs.edit()
                            .putString("whisperProvider", chosen)
                            .putString("whisper_model", mFile)
                            .putBoolean("use_whisper", true)
                            .apply();
                    swWhisper.setChecked(true);
                    refreshAudioWhisperUi.run();
                    if (!WhisperOfflineManager.isModelInstalled(MainActivity.this, mFile)) {
                        WhisperOfflineManager.showMissingModelWarning(MainActivity.this, prefs, refreshAudioWhisperUi);
                    }
                    return;
                }
                prefs.edit().putString("whisperProvider", chosen).apply();
                refreshAudioWhisperUi.run();
            });
        });

        btnOfflineModels.setOnClickListener(v ->
                WhisperOfflineManager.showModelManagerDialog(MainActivity.this, prefs, () -> {
                    swWhisper.setChecked(prefs.getBoolean("use_whisper", false));
                    refreshAudioWhisperUi.run();
                }));

        layout.addView(createHeader("MIKROFON & TELEPON", "#673AB7"));
        android.widget.TextView micHelp = new android.widget.TextView(this);
        micHelp.setText("Sumber mikrofon diatur otomatis. Untuk merekam saat telepon WhatsApp, aktifkan aksesibilitas dan izin tampil di atas aplikasi lain agar penanda Merekam bisa muncul.");
        micHelp.setPadding(8, 8, 8, 12);
        layout.addView(micHelp);

        final Button btnOverlay = new Button(this);
        btnOverlay.setAllCaps(false);
        btnOverlay.setBackgroundColor(Color.parseColor("#FF5722"));
        btnOverlay.setTextColor(Color.WHITE);
        boolean overlayOk = true;
        if (Build.VERSION.SDK_INT >= 23) {
            try { overlayOk = android.provider.Settings.canDrawOverlays(this); } catch (Throwable ignored) {}
        }
        btnOverlay.setText(overlayOk
                ? "Tampil di atas aplikasi lain: sudah diizinkan"
                : "Izinkan tampil di atas aplikasi lain");
        layout.addView(btnOverlay);
        android.widget.TextView overlayHint = new android.widget.TextView(this);
        overlayHint.setText("Fungsi: menampilkan penanda Merekam di atas layar WhatsApp saat telepon, supaya mikrofon tetap bisa dipakai.");
        overlayHint.setTextSize(12);
        overlayHint.setPadding(8, 4, 8, 12);
        layout.addView(overlayHint);
        btnOverlay.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                } catch (Throwable ignored) {
                    Toast.makeText(MainActivity.this, "Buka Pengaturan > Aplikasi > Keyboard AI > Tampil di atas app lain", Toast.LENGTH_LONG).show();
                }
            }
        }});

        final Button btnA11yMic = new Button(this);
        btnA11yMic.setAllCaps(false);
        btnA11yMic.setBackgroundColor(Color.parseColor("#4CAF50"));
        btnA11yMic.setTextColor(Color.WHITE);
        btnA11yMic.setText("Buka pengaturan aksesibilitas");
        layout.addView(btnA11yMic);
        btnA11yMic.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }});

        final Button btnMicTes = new Button(this);
        btnMicTes.setAllCaps(false);
        btnMicTes.setText("Tes mikrofon sekarang (2 detik)");
        layout.addView(btnMicTes);
        btnMicTes.setOnClickListener(v -> {
            Toast.makeText(MainActivity.this, "Bicara sekarang selama 2 detik...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try { SmartReplyAccessibilityService.tampilkanIndikatorMic("Tes mikrofon"); } catch (Throwable ignored) {}
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                final String hasil = MicHelper.tes(MainActivity.this, prefs);
                try { SmartReplyAccessibilityService.sembunyikanIndikatorMic(); } catch (Throwable ignored) {}
                runOnUiThread(() -> {
                    if (UiKit.isActivityAlive(MainActivity.this)) {
                        UiKit.dialog(MainActivity.this, prefs).setTitle("Hasil tes mikrofon").setMessage(hasil).show();
                    }
                });
            }).start();
        });

        final Button btnMicPanduan = new Button(this);
        btnMicPanduan.setAllCaps(false);
        btnMicPanduan.setText("Panduan saat telepon WhatsApp");
        layout.addView(btnMicPanduan);
        btnMicPanduan.setOnClickListener(v -> {
            android.widget.ScrollView sv = new android.widget.ScrollView(MainActivity.this);
            android.widget.TextView tv = new android.widget.TextView(MainActivity.this);
            tv.setText(PANDUAN_MIKROFON);
            tv.setTextColor(UiKit.textPrimary(prefs));
            tv.setTextSize(15);
            tv.setPadding(UiKit.dp(MainActivity.this, 16), UiKit.dp(MainActivity.this, 14),
                    UiKit.dp(MainActivity.this, 16), UiKit.dp(MainActivity.this, 14));
            sv.addView(tv);
            UiKit.dialog(MainActivity.this, prefs).setTitle("Panduan telepon WhatsApp").setView(sv).show();
        });

        layout.addView(createHeader("FORMAT REKAMAN (WHISPER)", "#00BCD4"));
        final Button btnAudFmt = new Button(this); btnAudFmt.setText("Format Audio: " + prefs.getString("audio_format", "MPEG-4/AAC")); layout.addView(btnAudFmt);
        btnAudFmt.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            final String[] list = {"MPEG-4/AAC (Standar)", "AMR-NB", "AMR-WB", "OGG", "WEBM"};
            showListDialog("Pilih Format Audio", list, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int i) {
                prefs.edit().putString("audio_format", list[i]).apply(); btnAudFmt.setText("Format Audio: " + list[i]);
            }});
        }});
        
        final Button btnAudBit = new Button(this); btnAudBit.setText("Bitrate: " + prefs.getString("audio_bitrate", "128 kbps")); layout.addView(btnAudBit);
        btnAudBit.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            final String[] list = {"64 kbps", "128 kbps", "192 kbps", "256 kbps", "320 kbps"};
            showListDialog("Atur Kualitas Audio", list, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int i) {
                prefs.edit().putString("audio_bitrate", list[i]).apply(); btnAudBit.setText("Bitrate: " + list[i]);
            }});
        }});

        
        

        layout.addView(createHeader("PENGATURAN GROK STT (OPSIONAL)", "#00BCD4"));
        final Switch swFormat = new Switch(this); swFormat.setText("Format Kalimat (Huruf Kapital & Tanda Baca)"); swFormat.setChecked(prefs.getBoolean("grok_stt_format", true)); layout.addView(swFormat);
        final Switch swFiller = new Switch(this); swFiller.setText("Sertakan Kata Filler (Uh, Um)"); swFiller.setChecked(prefs.getBoolean("grok_stt_filler", false)); layout.addView(swFiller);
        final Switch swDiarize = new Switch(this); swDiarize.setText("Diarization (Label Pembicara)"); swDiarize.setChecked(prefs.getBoolean("grok_stt_diarize", false)); layout.addView(swDiarize);
        final Switch swMulti = new Switch(this); swMulti.setText("Audio Multisaluran"); swMulti.setChecked(prefs.getBoolean("grok_stt_multichannel", false)); layout.addView(swMulti);
        
        final EditText etVAD = new EditText(this); etVAD.setHint("Ambang VAD (0.0 - 1.0, contoh: 0.5)"); etVAD.setText(prefs.getString("grok_stt_vad", "")); layout.addView(etVAD);
        final EditText etKeyterm = new EditText(this); etKeyterm.setHint("Kata Kunci (pisah koma, contoh: Anas, AI)"); etKeyterm.setText(prefs.getString("grok_stt_keyterm", "")); layout.addView(etKeyterm);


        final Button btnAksesibilitas = new Button(this); btnAksesibilitas.setText("Buka pengaturan aksesibilitas"); btnAksesibilitas.setBackgroundColor(Color.parseColor("#4CAF50")); btnAksesibilitas.setTextColor(Color.WHITE); layout.addView(btnAksesibilitas);
        btnAksesibilitas.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        }});

        layout.addView(createHeader("EFEK SUARA", "#4CAF50"));

        final Switch swSfxNempel = new Switch(this); swSfxNempel.setText("Bunyi saat teks selesai dimasukkan"); swSfxNempel.setChecked(prefs.getBoolean("sound_nempel_enabled", true)); layout.addView(swSfxNempel);

        Button btnPilihNempel = new Button(this); btnPilihNempel.setText("Pilih file bunyi penempelan"); layout.addView(btnPilihNempel);

        btnPilihNempel.setOnClickListener(v ->
                OfficeUtils.launchFilePicker(MainActivity.this, PICK_AUDIO_NEMPEL, "audio", "Pilih Bunyi Penempelan"));

        layout.addView(createHeader("TAMPILAN", "#9C27B0"));

        final Button bTheme = new Button(this);
        bTheme.setText("Tema UI: " + prefs.getString("ui_theme", "Dark"));
        layout.addView(bTheme);
        bTheme.setOnClickListener(v -> {
            showListDialog("Tema UI", new String[]{"Light", "Dark"}, (d, i) -> {
                String nextTheme = new String[]{"Light", "Dark"}[i];
                prefs.edit().putString("ui_theme", nextTheme).apply();
                bTheme.setText("Tema UI: " + nextTheme);
                buildMainMenu();
                Toast.makeText(MainActivity.this, "Tema UI diubah ke " + nextTheme, Toast.LENGTH_SHORT).show();
            });
        });

        addSettingDescriptions(layout);
        styleGeneratedControls(layout);

        Button btnSaveAudio = new Button(this);
        btnSaveAudio.setText("SIMPAN SETELAN AUDIO");
        UiKit.styleButton(this, btnSaveAudio, UiKit.ACCENT_OFFICE);
        btnSaveAudio.setOnClickListener(v -> {
            prefs.edit()
                    .putBoolean("stt_soft_voice_boost", swSoftBoost.isChecked())
                    .putBoolean("grok_stt_format", swFormat.isChecked())
                    .putBoolean("grok_stt_filler", swFiller.isChecked())
                    .putBoolean("grok_stt_diarize", swDiarize.isChecked())
                    .putBoolean("grok_stt_multichannel", swMulti.isChecked())
                    .putString("grok_stt_vad", etVAD.getText().toString().trim())
                    .putString("grok_stt_keyterm", etKeyterm.getText().toString().trim())
                    .putBoolean("sound_nempel_enabled", swSfxNempel.isChecked())
                    .apply();
            Toast.makeText(MainActivity.this, "Setelan Audio & STT Tersimpan!", Toast.LENGTH_SHORT).show();
        });
        layout.addView(btnSaveAudio);
        pushSubPage("Audio & Speech Recognition", scroll);
    }

    private void pickDocumentForChat() {
        OfficeUtils.launchFilePicker(this, PICK_DOKUMEN_CHAT, "code", "Pilih Berkas Kode / Teks");
    }

    private void copyUriToFile(Uri uri, String destFileName) {
        try (InputStream is = OfficeUtils.openRead(this, uri);
             FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), destFileName))) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) > 0) fos.write(buffer, 0, len);
            Toast.makeText(this, "Audio " + destFileName + " sukses dipasang!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Gagal masang audio: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            if (requestCode == PICK_JSON_GAYA) {
                try (InputStream is = OfficeUtils.openRead(this, data.getData());
                     BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    JSONArray newStyles = new JSONArray(sb.toString());
                    prefs.edit().putString("json_styles_raw", newStyles.toString()).apply();
                    Toast.makeText(this, "Sukses Import JSON!", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Gagal/Format Salah!", Toast.LENGTH_LONG).show();
                }
            } else if (requestCode == PICK_DOKUMEN_CHAT) {
                try (InputStream is = OfficeUtils.openRead(this, data.getData());
                     BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null && sb.length() < 60000) sb.append(line).append("\n");
                    String isiDokumen = sb.toString();
                    if (isiDokumen.isEmpty()) {
                        Toast.makeText(this, "File Kosong!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!AuthManager.isPremiumActive(this)) {
                        Intent ed = new Intent(this, CodeTextEditorActivity.class);
                        ed.setData(data.getData());
                        startActivity(ed);
                        return;
                    }
                    JSONArray tempHistory = new JSONArray();
                    tempHistory.put(new JSONObject().put("role", "user").put("content", "Tolong pelajari isi berkas ini:\n\n" + isiDokumen));
                    tempHistory.put(new JSONObject().put("role", "assistant").put("content", "Oke Bro, isi berkas udah gua baca. Lu mau suruh gua jelasin, cari error, atau rangkum nih?"));
                    showChatDialog("Bedah Berkas AI", tempHistory.toString());
                } catch (Exception e) {
                    Toast.makeText(this, "Gagal baca dokumen: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            } else if (requestCode == PICK_AUDIO_KETIK) {
                copyUriToFile(data.getData(), "custom_ketik.mp3");
                prefs.edit().putString("sound_effect_key", "custom").putString("sound_effect_name", "File Audio Custom Sendiri (MP3 / WAV)").apply();
                Toast.makeText(this, "Efek Suara Custom berhasil dipasang!", Toast.LENGTH_SHORT).show();
            } else if (requestCode == PICK_AUDIO_NEMPEL) {
                copyUriToFile(data.getData(), "custom_nempel.mp3");
            } else if (requestCode == PICK_CHAT_ATTACH_FILE) {
                try (InputStream is = OfficeUtils.openRead(this, data.getData());
                     BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null && sb.length() < 60000) sb.append(line).append("\n");
                    String isi = sb.toString();
                    if (!isi.isEmpty() && pendingChatAttachTarget != null) {
                        String cur = pendingChatAttachTarget.getText() == null ? "" : pendingChatAttachTarget.getText().toString();
                        pendingChatAttachTarget.setText((cur.isEmpty() ? "" : cur + "\n\n") + "[Lampiran 1 Berkas]:\n" + isi);
                        if (pendingChatAttachBanner != null) {
                            pendingChatAttachBanner.setText("Terlampir 1 berkas (" + isi.length() + " kar.) — tulis perintah Anda lalu klik Kirim.");
                        }
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Gagal melampirkan berkas: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
            else if (requestCode == PICK_CHAT_ATTACH_FOLDER) {
                handleChatAttachFolderResult(data.getStringExtra("selected_folder"));
            }
            else if (requestCode == AiPowerToolsUI.REQ_VISION_IMAGE) {
                AiPowerToolsUI.handleVisionImageResult(this, prefs, data.getData());
            }
            else if (requestCode == AiPowerToolsUI.REQ_MEETING_AUDIO) {
                AiPowerToolsUI.handleMeetingAudioResult(this, prefs, data.getData());
            }
            else if (requestCode == PICK_CHAT_VOICE) {
                try {
                    java.util.ArrayList<String> results = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
                    if (results != null && !results.isEmpty() && pendingChatVoiceInput != null) {
                        String spoken = results.get(0);
                        if (spoken != null && !spoken.isEmpty()) {
                            CharSequence cur = pendingChatVoiceInput.getText();
                            if (cur != null && cur.length() > 0) {
                                pendingChatVoiceInput.setText(cur.toString() + " " + spoken);
                            } else {
                                pendingChatVoiceInput.setText(spoken);
                            }
                            pendingChatVoiceInput.setSelection(pendingChatVoiceInput.getText().length());
                        }
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Gagal ambil hasil suara.", Toast.LENGTH_SHORT).show();
                }
            }

        }

    }

    /**
     * Input suara di chat AI — pure Google Speech-to-Text (RecognizerIntent).
     * Nggak nyentuh WhatsApp, nggak pakai Whisper, cuma dialog Google biasa.
     */
    private void startChatVoiceInput(EditText target) {
        if (target == null) return;
        pendingChatVoiceInput = target;
        try {
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Izin mikrofon belum diberikan. Izinkan dulu di pengaturan app.", Toast.LENGTH_LONG).show();
                try { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 123); } catch (Exception ignored) {}
                return;
            }
            Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            String lang = prefs.getString("bahasa_mikrofon", prefs.getString("lang_src", "Otomatis"));
            if (lang != null && !lang.isEmpty() && !lang.equalsIgnoreCase("Otomatis")) {
                // Map label sederhana ke locale kalau ada; else biarin default sistem
                if (lang.toLowerCase(java.util.Locale.US).contains("indonesia") || lang.equalsIgnoreCase("id") || lang.equalsIgnoreCase("id-ID")) {
                    intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "id-ID");
                } else if (lang.toLowerCase(java.util.Locale.US).startsWith("en")) {
                    intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-US");
                } else {
                    intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, lang);
                }
            }
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Bicara sekarang...");
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            startActivityForResult(intent, PICK_CHAT_VOICE);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "Google Speech Recognition nggak tersedia di HP ini.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Gagal buka input suara: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showChatAttachmentMenu() {
        String[] opts = {
                "Lampirkan 1 Berkas Saja (Teks, Lua, Java, JSON)",
                "Lampirkan Folder (Pilih Analisis Semua atau Berkas Penting)",
                "Lampirkan Gambar / Foto (AI Vision)",
                "Lampirkan Teks dari Clipboard"
        };
        UiKit.dialog(this, prefs)
                .setTitle("Lampirkan ke Chat AI")
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        OfficeUtils.launchFilePicker(this, PICK_CHAT_ATTACH_FILE, "Pilih Berkas Lampiran", "all");
                    } else if (which == 1) {
                        OfficeUtils.launchFolderPicker(this, PICK_CHAT_ATTACH_FOLDER, "Pilih Folder Lampiran");
                    } else if (which == 2) {
                        AiPowerToolsUI.launchVisionChooser(this);
                    } else {
                        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip().getItemCount() > 0 && pendingChatAttachTarget != null) {
                            CharSequence cs = cb.getPrimaryClip().getItemAt(0).coerceToText(this);
                            if (cs != null) {
                                String cur = pendingChatAttachTarget.getText().toString();
                                pendingChatAttachTarget.setText((cur.isEmpty() ? "" : cur + "\n\n") + cs.toString());
                            }
                        }
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void handleChatAttachFolderResult(String folderPath) {
        if (folderPath == null || folderPath.isEmpty()) return;
        final File dir = new File(folderPath);
        final java.util.List<File> files = AiCodeAgentStudio.collectWorkspaceFiles(dir, 50);
        if (files.isEmpty()) {
            Toast.makeText(this, "Tidak ada berkas kode/teks di folder " + dir.getName(), Toast.LENGTH_LONG).show();
            return;
        }
        String[] opts = {
                "Analisis Semua Berkas di Folder (" + files.size() + " berkas)",
                "Pilih Berkas Penting Saja (Checklist Hemat Token)"
        };
        UiKit.dialog(this, prefs)
                .setTitle("Folder: " + dir.getName())
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        injectFilesIntoChatInput(dir, files);
                    } else {
                        final String[] names = new String[files.size()];
                        final boolean[] checked = new boolean[files.size()];
                        for (int i = 0; i < files.size(); i++) names[i] = files.get(i).getName();
                        UiKit.dialog(this, prefs)
                                .setTitle("Pilih Berkas Penting")
                                .setMultiChoiceItems(names, checked, (dd, idx, isC) -> checked[idx] = isC)
                                .setNegativeButton("Batal", null)
                                .setPositiveButton("Lampirkan Terpilih", (dd, w) -> {
                                    java.util.List<File> chosen = new java.util.ArrayList<>();
                                    for (int i = 0; i < files.size(); i++) if (checked[i]) chosen.add(files.get(i));
                                    if (!chosen.isEmpty()) injectFilesIntoChatInput(dir, chosen);
                                }).show();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void injectFilesIntoChatInput(File dir, java.util.List<File> files) {
        if (pendingChatAttachTarget == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("[Lampiran Folder: ").append(dir.getName()).append(" | ").append(files.size()).append(" berkas]\n");
        sb.append(AiCodeAgentStudio.buildProjectMap(dir, files)).append("\n");
        int budget = 45000;
        for (File f : files) {
            if (sb.length() >= budget) break;
            String txt = AiCodeAgentStudio.readFileUtf8(f, 12000);
            sb.append("--- ").append(f.getName()).append(" ---\n").append(txt).append("\n\n");
        }
        String cur = pendingChatAttachTarget.getText() == null ? "" : pendingChatAttachTarget.getText().toString();
        pendingChatAttachTarget.setText((cur.isEmpty() ? "" : cur + "\n\n") + sb.toString());
        if (pendingChatAttachBanner != null) {
            pendingChatAttachBanner.setText("Terlampir " + files.size() + " berkas dari " + dir.getName() + " — tulis pertanyaan lalu kirim.");
        }
    }

    private void showChatDialog(String title, String initialHistoryJson) {
        showChatDialog(title, initialHistoryJson, "Jawab dalam bahasa Indonesia dengan jelas, ramah, dan langsung ke inti.");
    }

    private void showChatDialog(String title, String initialHistoryJson, final String customSystemPrompt) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 8), UiKit.dp(this, 12), UiKit.dp(this, 10));
        root.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));

        LinearLayout topTools = new LinearLayout(this);
        topTools.setOrientation(LinearLayout.HORIZONTAL);
        topTools.setPadding(0, 0, 0, UiKit.dp(this, 6));
        Button btnCopyLast = new Button(this);
        btnCopyLast.setText("Salin Terakhir");
        UiKit.styleButton(this, btnCopyLast, UiKit.ACCENT_KB);
        Button btnSaveTxt = new Button(this);
        btnSaveTxt.setText("Simpan (.txt)");
        UiKit.styleButton(this, btnSaveTxt, UiKit.ACCENT_AI);
        Button btnClearHist = new Button(this);
        btnClearHist.setText("Hapus Riwayat");
        UiKit.styleButton(this, btnClearHist, UiKit.ACCENT_DANGER);
        LinearLayout.LayoutParams lpTool1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        lpTool1.setMargins(0, 0, UiKit.dp(this, 4), 0);
        btnCopyLast.setLayoutParams(lpTool1);
        LinearLayout.LayoutParams lpToolSave = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        lpToolSave.setMargins(0, 0, UiKit.dp(this, 4), 0);
        btnSaveTxt.setLayoutParams(lpToolSave);
        LinearLayout.LayoutParams lpTool2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        btnClearHist.setLayoutParams(lpTool2);
        topTools.addView(btnCopyLast);
        topTools.addView(btnSaveTxt);
        topTools.addView(btnClearHist);
        root.addView(topTools);

        final ScrollView sc = new ScrollView(this);
        sc.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
        final LinearLayout chatContainer = new LinearLayout(this);
        chatContainer.setOrientation(LinearLayout.VERTICAL);
        sc.addView(chatContainer);
        root.addView(sc);

        final TextView tvAttachInfo = new TextView(this);
        tvAttachInfo.setText("Tips: Ketuk Lampirkan untuk menyertakan 1 berkas, folder, atau gambar.");
        tvAttachInfo.setTextSize(12);
        tvAttachInfo.setTextColor(UiKit.textMuted(prefs));
        tvAttachInfo.setPadding(0, 8, 0, 4);
        root.addView(tvAttachInfo);

        LinearLayout inputLayout = new LinearLayout(this);
        inputLayout.setOrientation(LinearLayout.HORIZONTAL);
        inputLayout.setPadding(0, 8, 0, 0);
        final EditText etInput = new EditText(this);
        etInput.setHint("Tulis pesan atau perintah...");
        UiKit.styleEditText(this, etInput, prefs);
        etInput.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        inputLayout.addView(etInput);

        Button btnAttach = new Button(this);
        btnAttach.setText("File");
        btnAttach.setContentDescription("Lampirkan berkas, folder, atau gambar ke percakapan AI");
        UiKit.styleButton(this, btnAttach, UiKit.ACCENT_KB);
        LinearLayout.LayoutParams lpAtt = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpAtt.setMargins(6, 0, 0, 0);
        btnAttach.setLayoutParams(lpAtt);
        inputLayout.addView(btnAttach);

        Button btnMic = new Button(this);
        btnMic.setText("Suara");
        btnMic.setContentDescription("Input suara Google");
        UiKit.styleButton(this, btnMic, UiKit.ACCENT_AI);
        LinearLayout.LayoutParams lpMic = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpMic.setMargins(6, 0, 0, 0);
        btnMic.setLayoutParams(lpMic);
        inputLayout.addView(btnMic);

        final Button btnSend = new Button(this);
        btnSend.setText("KIRIM");
        UiKit.styleButton(this, btnSend, UiKit.ACCENT_OFFICE);
        LinearLayout.LayoutParams lpSend = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpSend.setMargins(6, 0, 0, 0);
        btnSend.setLayoutParams(lpSend);
        inputLayout.addView(btnSend);
        root.addView(inputLayout);

        btnAttach.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pendingChatAttachTarget = etInput;
                pendingChatAttachBanner = tvAttachInfo;
                showChatAttachmentMenu();
            }
        });

        btnMic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startChatVoiceInput(etInput);
            }
        });

        JSONArray parsedHist;
        try {
            if ("[]".equals(initialHistoryJson)) {
                parsedHist = new JSONArray(UiKit.readStringPref(prefs, "chat_hist", "[]"));
            } else {
                parsedHist = new JSONArray(initialHistoryJson);
            }
        } catch (Exception e) {
            parsedHist = new JSONArray();
            if ("[]".equals(initialHistoryJson)) {
                prefs.edit().putString("chat_hist", "[]").apply();
            }
        }
        final JSONArray chatHistory = parsedHist;
        int startRender = Math.max(0, chatHistory.length() - 30);
        for (int i = startRender; i < chatHistory.length(); i++) {
            JSONObject msg = chatHistory.optJSONObject(i);
            if (msg != null) {
                addChatBubble(chatContainer, msg.optString("role", "user"), msg.optString("content", ""));
            }
        }
        sc.post(new Runnable() { public void run() { sc.fullScroll(View.FOCUS_DOWN); } });

        btnCopyLast.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    if (chatHistory.length() > 0) {
                        String lastMsg = chatHistory.getJSONObject(chatHistory.length() - 1).optString("content", "");
                        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("Chat AI", lastMsg));
                        Toast.makeText(MainActivity.this, "Pesan terakhir disalin ke Clipboard!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Belum ada pesan untuk disalin.", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception ignored) {}
            }
        });

        btnSaveTxt.setOnClickListener(v -> {
            if (chatHistory.length() == 0) {
                Toast.makeText(MainActivity.this, "Belum ada percakapan untuk disimpan.", Toast.LENGTH_SHORT).show();
                return;
            }
            StringBuilder exportSb = new StringBuilder();
            for (int i = 0; i < chatHistory.length(); i++) {
                JSONObject m = chatHistory.optJSONObject(i);
                if (m != null) {
                    String roleLabel = "user".equalsIgnoreCase(m.optString("role", "user")) ? "ANAS" : "AI";
                    exportSb.append(roleLabel).append(":\n").append(m.optString("content", "")).append("\n\n");
                }
            }
            try {
                String safePrefix = "Chat_AI_" + title.replaceAll("[^a-zA-Z0-9_-]", "_");
                File savedFile = OfficeUtils.saveTextToBackupFolder(MainActivity.this, "Teks & Catatan AI", safePrefix, exportSb.toString().trim());
                Toast.makeText(MainActivity.this, savedFile != null ? "Disimpan ke Teks & Catatan AI:\n" + savedFile.getAbsolutePath() : "Gagal menyimpan catatan.", Toast.LENGTH_LONG).show();
            } catch (Exception ex) {
                Toast.makeText(MainActivity.this, "Gagal menyimpan: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        btnClearHist.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                UiKit.dialog(MainActivity.this, prefs)
                        .setTitle("Hapus Riwayat Chat?")
                        .setMessage("Semua riwayat percakapan di layar ini akan dibersihkan.")
                        .setPositiveButton("HAPUS", (d, w) -> {
                            while (chatHistory.length() > 0) chatHistory.remove(chatHistory.length() - 1);
                            if ("[]".equals(initialHistoryJson)) {
                                prefs.edit().putString("chat_hist", "[]").apply();
                            }
                            chatContainer.removeAllViews();
                            Toast.makeText(MainActivity.this, "Riwayat Bersih!", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Batal", null)
                        .show();
            }
        });

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String userText = etInput.getText().toString().trim();
                if (userText.isEmpty()) return;

                etInput.setText("");
                addChatBubble(chatContainer, "user", userText);
                sc.post(new Runnable() { public void run() { sc.fullScroll(View.FOCUS_DOWN); } });

                try {
                    JSONObject userMsg = new JSONObject();
                    userMsg.put("role", "user");
                    userMsg.put("content", userText);
                    chatHistory.put(userMsg);
                } catch (Exception ignored) {}

                final TextView loadingBubble = addChatBubble(chatContainer, "assistant", "AI sedang memproses...");
                final boolean persistHistory = "[]".equals(initialHistoryJson)
                        && (customSystemPrompt == null || customSystemPrompt.startsWith("Jawab dalam bahasa Indonesia"));

                // Kirim maksimal 20 turn terakhir supaya aman dari limit konteks
                JSONArray windowHistory = new JSONArray();
                int winStart = Math.max(0, chatHistory.length() - 20);
                for (int i = winStart; i < chatHistory.length(); i++) {
                    JSONObject item = chatHistory.optJSONObject(i);
                    if (item != null) windowHistory.put(item);
                }

                btnSend.setEnabled(false);
                AiEngine.chat(MainActivity.this, prefs, customSystemPrompt, windowHistory, 0.7, false, new AiEngine.TextCallback() {
                    @Override public void onSuccess(final String finalResult) {
                        if (!UiKit.isActivityAlive(MainActivity.this)) return;
                        btnSend.setEnabled(true);
                        chatContainer.removeView(loadingBubble);
                        addChatBubble(chatContainer, "assistant", finalResult);
                        try {
                            JSONObject aiMsg = new JSONObject();
                            aiMsg.put("role", "assistant");
                            aiMsg.put("content", finalResult);
                            chatHistory.put(aiMsg);
                            if (persistHistory) {
                                // Simpan maksimal 40 pesan terakhir di SharedPreferences
                                JSONArray trimmed = new JSONArray();
                                int saveStart = Math.max(0, chatHistory.length() - 40);
                                for (int i = saveStart; i < chatHistory.length(); i++) {
                                    trimmed.put(chatHistory.optJSONObject(i));
                                }
                                prefs.edit().putString("chat_hist", trimmed.toString()).apply();
                            }
                        } catch (Exception ignored) {}
                        sc.post(new Runnable() { public void run() { sc.fullScroll(View.FOCUS_DOWN); } });
                    }

                    @Override public void onError(String err) {
                        if (!UiKit.isActivityAlive(MainActivity.this)) return;
                        btnSend.setEnabled(true);
                        chatContainer.removeView(loadingBubble);
                        addChatBubble(chatContainer, "assistant", "Error: " + err);
                        sc.post(new Runnable() { public void run() { sc.fullScroll(View.FOCUS_DOWN); } });
                    }
                });
            }
        });

        pushSubPage(title, root);
    }



    private TextView addChatBubble(LinearLayout container, String role, String text) {


        TextView tv = new TextView(this); tv.setText((role.equals("user") ? "\ud83d\udde3\ufe0f LU:\n" : "\ud83e\udd16 AI:\n") + text); tv.setPadding(20, 20, 20, 20); tv.setTextSize(15); tv.setTextColor(Color.WHITE); tv.setTextIsSelectable(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.setMargins(0, 10, 0, 10); tv.setLayoutParams(lp);
        tv.setBackground(UiKit.roundedBox(role.equals("user") ? UiKit.ACCENT_AI : UiKit.ACCENT_SYSTEM, Color.TRANSPARENT, UiKit.dp(this, 12), 0));
        container.addView(tv); return tv;

    }

    // =========================================================================
    // 10 & 11. TENTANG & BENGKEL DEBUG
    // =========================================================================
    private void showAboutAndTutorial() {
        ScrollView sc = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 24));

        TextView tv = new TextView(this);
        tv.setText("Halo Bro! Gua Anugerah Annas.\n\nScript ini gua racik khusus biar lu pada makin gampang ngobrol sama AI. Tutorial lengkapnya udah ada di otak sistem. Kalau lu nemu bug, japri gua aja!\n\nMari kembangin tools ini bareng-bareng!");
        tv.setTextSize(15);
        tv.setTextColor(UiKit.textPrimary(prefs));
        tv.setPadding(0, 0, 0, UiKit.dp(this, 16));
        layout.addView(tv);

        Button btnTutorialBraille = new Button(this);
        btnTutorialBraille.setText("BUKA TUTORIAL KEYBOARD BRAILLE");
        UiKit.styleButton(this, btnTutorialBraille, UiKit.ACCENT_KB);
        layout.addView(btnTutorialBraille);
        btnTutorialBraille.setOnClickListener(v -> showBrailleTutorialDialog());

        Button btnWa = new Button(this);
        btnWa.setText("JAPRI VIA WHATSAPP");
        UiKit.styleButton(this, btnWa, UiKit.ACCENT_OFFICE);
        layout.addView(btnWa);
        btnWa.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=6282119059741"))));

        sc.addView(layout);
        pushSubPage("Tentang Developer & Tutorial", sc);
    }

    private void showBrailleTutorialDialog() {
        ScrollView sc = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 24));

        TextView tvHint = new TextView(this);
        tvHint.setText("Pilih panduan di bawah. Tekan tombol Kembali HP untuk mundur 1 langkah.");
        tvHint.setTextSize(13);
        tvHint.setTextColor(UiKit.textMuted(prefs));
        tvHint.setPadding(0, 0, 0, UiKit.dp(this, 12));
        layout.addView(tvHint);

        Button b1 = new Button(this);
        b1.setText("1. Tutorial Mengetik (titik & chord)");
        UiKit.styleButton(this, b1, UiKit.ACCENT_KB);
        b1.setOnClickListener(v -> showBrailleTutorialTyping());
        layout.addView(b1);

        Button b2 = new Button(this);
        b2.setText("2. Tutorial Gesture (usap & papan klip)");
        UiKit.styleButton(this, b2, UiKit.ACCENT_AI);
        b2.setOnClickListener(v -> showBrailleTutorialGestures());
        layout.addView(b2);

        sc.addView(layout);
        pushSubPage("Panduan Keyboard Braille", sc);
    }

    private void showTutorialScroll(String title, String body) {
        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextSize(16);
        tv.setLineSpacing(6, 1.15f);
        tv.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 24));
        tv.setTextColor(UiKit.textPrimary(prefs));
        tv.setTextIsSelectable(true);
        tv.setContentDescription(title + ". " + body);
        scroll.addView(tv);
        pushSubPage(title, scroll);
    }

    private void showBrailleTutorialTyping() {
        String t =
            "TUTORIAL MENGETIK BRAILLE\n\n"
            + "1. Layar penuh\n"
            + "Saat mode Braille aktif, keyboard menutupi seluruh layar. Screen reader tetap boleh nyala; area keyboard dikelola khusus agar chord multi-jari tetap jalan.\n\n"
            + "2. Tata letak 6 titik (2 baris × 3 kolom)\n"
            + "Baris atas: Titik 1 kiri, Titik 2 tengah, Titik 3 kanan.\n"
            + "Baris bawah: Titik 4 kiri, Titik 5 tengah, Titik 6 kanan.\n\n"
            + "3. Cara mengetik (chord)\n"
            + "Tekan satu atau beberapa titik bersamaan, lalu ANGKAT jari. Huruf baru tercetak saat jari diangkat.\n"
            + "Contoh: titik 1 = a, 1-2 = b, 1-4 = c, 1-2-3 = l.\n"
            + "Jari boleh 1, 2, atau 3+ — yang penting DIAM di titik, jangan digeser jauh.\n\n"
            + "4. Mode angka & kapital\n"
            + "Tanda angka: titik 3-4-5-6, lalu ketik digit.\n"
            + "Kapital: titik 6 (atau sesuai panduan suara) sebelum huruf.\n\n"
            + "5. Kalibrasi jari\n"
            + "Tombol Kalibrasi di bar bawah: sentuh 6 titik satu-satu, atau tempel 6 jari sekaligus. Atau pilih susunan standar.\n\n"
            + "6. Emoji\n"
            + "Masuk mode emoji (tombol bar bawah atau 2 jari usap kanan). Geser kiri/kanan 1 jari memilih emoji; sistem/TTS membaca karakter emoji saja. Ketuk dua kali untuk memasukkan. 2 jari kiri/kanan ganti halaman 1 sampai 13.";
        showTutorialScroll("Tutorial Mengetik", t);
    }

    private void showBrailleTutorialGestures() {
        String t =
            "TUTORIAL GESTURE\n\n"
            + "Usap = geser jauh. Mengetik = tekan diam lalu angkat.\n\n"
            + "GANTI MODE (3 jari atas/bawah):\n"
            + "Karakter → Kata → Baris → Paragraf → Papan klip → Salin → Tempel → TTS mati → TTS per karakter → TTS per kata saat spasi → TTS karakter + kata → ulang.\n"
            + "Mode TTS mengatur umpan balik saat mengetik Braille. Mode kata membacakan kata sebelumnya ketika spasi dimasukkan.\n\n"
            + "MODE TEKS (Karakter/Kata/Baris/Paragraf):\n"
            + "1 jari kiri/kanan = kursor mundur/maju.\n"
            + "1 jari atas = hapus sesuai mode.\n"
            + "1 jari bawah = spasi.\n\n"
            + "MODE PAPAN KLIP:\n"
            + "1 jari kiri/kanan = pilih teks di riwayat (dibacakan).\n"
            + "Klik (seperti emoji) = tempel teks yang dipilih.\n"
            + "1 jari atas = batalkan tempelan terakhir, atau hapus item dari riwayat.\n"
            + "1 jari bawah = spasi.\n\n"
            + "MODE SALIN:\n"
            + "1 jari kanan = salin teks ke papan klip.\n\n"
            + "MODE TEMPEL:\n"
            + "1 jari kiri = tempel (TTS membacakan isinya).\n\n"
            + "LAINNYA:\n"
            + "2 jari bawah = Enter. 2 jari atas = pengaturan.\n"
            + "2 jari kiri = sembunyikan. 2 jari kanan = emoji.\n"
            + "Input suara: tahan 1 jari 3 detik. Keyboard ditutup = TTS berhenti.";
        showTutorialScroll("Tutorial Gesture", t);
    }

    private void showDebugDialog() {
        ScrollView sc = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 12), UiKit.dp(this, 16), UiKit.dp(this, 24));

        layout.addView(createHeader("MENU DEBUG SISTEM", "#DC2626"));
        Button btnReset = new Button(this);
        btnReset.setText("RESET SEMUA SETELAN");
        UiKit.styleButton(this, btnReset, UiKit.ACCENT_DANGER);
        layout.addView(btnReset);

        btnReset.setOnClickListener(v -> {
            UiKit.dialog(MainActivity.this, prefs)
                    .setTitle("Peringatan Keras")
                    .setMessage("Yakin reset semua? Termasuk Kamus dan Makro!")
                    .setPositiveButton("YA", (dialog, which) -> {
                        prefs.edit().clear().apply();
                        Toast.makeText(MainActivity.this, "Reset selesai. Buka ulang App.", Toast.LENGTH_LONG).show();
                        finish();
                    })
                    .setNegativeButton("Batal", null)
                    .show();
        });

        layout.addView(createHeader("ALAT GAYA BAHASA", "#D97706"));

        Button btnCekGaya = new Button(this);
        btnCekGaya.setText("CEK STATUS FOLDER GAYA");
        UiKit.styleButton(this, btnCekGaya, UiKit.ACCENT_AI);
        layout.addView(btnCekGaya);
        btnCekGaya.setOnClickListener(v -> {
            File pathGaya = new File(getExternalFilesDir(null), "gaya input suara final");
            if (!pathGaya.exists()) {
                Toast.makeText(MainActivity.this, "Folder gaya BELUM ADA di: " + pathGaya.getAbsolutePath(), Toast.LENGTH_LONG).show();
                return;
            }
            File[] folders = pathGaya.listFiles();
            int count = 0;
            if (folders != null) { for (File f : folders) { if (f.isDirectory()) count++; } }
            Toast.makeText(MainActivity.this,
                    count + " folder gaya ditemukan di:\n" + pathGaya.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        });

        Button btnExtract = new Button(this);
        btnExtract.setText("EXTRACT ULANG SEMUA GAYA (PAKSA)");
        UiKit.styleButton(this, btnExtract, UiKit.ACCENT_WARM);
        layout.addView(btnExtract);
        btnExtract.setOnClickListener(v -> {
            UiKit.dialog(MainActivity.this, prefs)
                    .setTitle("Extract Ulang Gaya?")
                    .setMessage("Ini akan hapus dan extract ulang semua folder gaya dari APK. Lanjut?")
                    .setPositiveButton("LANJUT", (d, w) -> {
                        Toast.makeText(MainActivity.this, "Sedang mengekstrak gaya...", Toast.LENGTH_SHORT).show();
                        new Thread(() -> {
                            try {
                                File pathGaya = new File(getExternalFilesDir(null), "gaya input suara final");
                                if (pathGaya.exists()) deleteRecursive(pathGaya);
                                pathGaya.mkdirs();
                                copyAssetFolder("gaya input suara final", pathGaya.getAbsolutePath());
                                File[] folders = pathGaya.listFiles();
                                int cnt = 0;
                                if (folders != null) { for (File f : folders) { if (f.isDirectory()) cnt++; } }
                                final int finalCnt = cnt;
                                runOnUiThread(() -> {
                                    if (!UiKit.isActivityAlive(MainActivity.this)) return;
                                    Toast.makeText(MainActivity.this,
                                            "Selesai! " + finalCnt + " folder gaya berhasil diekstrak.",
                                            Toast.LENGTH_LONG).show();
                                });
                            } catch (final Exception e) {
                                runOnUiThread(() -> {
                                    if (!UiKit.isActivityAlive(MainActivity.this)) return;
                                    Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                });
                            }
                        }).start();
                    })
                    .setNegativeButton("Batal", null)
                    .show();
        });

        sc.addView(layout);
        pushSubPage("Alat Bengkel (Debug)", sc);
    }

    private ArrayAdapter<String> makeThemedListAdapter(final java.util.List<String> items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(UiKit.textPrimary(prefs));
                }
                return v;
            }
        };
    }

    private void showSelectStyleDialog(final Button btnGayaAktif) {
        final File pathGaya = new File(getExternalFilesDir(null), "gaya input suara final");
        if (!pathGaya.exists()) pathGaya.mkdirs();

        File[] rawFolders = pathGaya.listFiles();
        final java.util.List<String> folderNames = new java.util.ArrayList<>();
        if (rawFolders != null) {
            for (File f : rawFolders) { if (f.isDirectory()) folderNames.add(f.getName()); }
        }
        java.util.Collections.sort(folderNames);

        if (folderNames.isEmpty()) {
            UiKit.dialog(this, prefs)
                    .setTitle("Folder Gaya Belum Ada")
                    .setMessage("Folder gaya belum terdeteksi. Mau extract sekarang dari APK?")
                    .setPositiveButton("EXTRACT SEKARANG", (d, w) -> {
                        Toast.makeText(MainActivity.this, "Mengekstrak gaya dari APK...", Toast.LENGTH_SHORT).show();
                        new Thread(() -> {
                            try {
                                if (pathGaya.exists()) deleteRecursive(pathGaya);
                                pathGaya.mkdirs();
                                copyAssetFolder("gaya input suara final", pathGaya.getAbsolutePath());
                                runOnUiThread(() -> {
                                    if (!UiKit.isActivityAlive(MainActivity.this)) return;
                                    Toast.makeText(MainActivity.this, "Selesai! Buka 'Pilih Gaya' lagi.", Toast.LENGTH_LONG).show();
                                });
                            } catch (final Exception e) {
                                runOnUiThread(() -> {
                                    if (!UiKit.isActivityAlive(MainActivity.this)) return;
                                    Toast.makeText(MainActivity.this, "Gagal: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                });
                            }
                        }).start();
                    })
                    .setNegativeButton("Batal", null)
                    .show();
            return;
        }

        UiKit.dialog(this, prefs)
                .setTitle("Pilih Kategori Gaya")
                .setItems(folderNames.toArray(new String[0]), (dK, idxKat) -> {
                    String namaKat = folderNames.get(idxKat);
                    File folderKat = new File(pathGaya, namaKat);

                    File[] rawFiles = folderKat.listFiles();
                    final java.util.List<String> styleNames = new java.util.ArrayList<>();
                    final java.util.List<File> styleFiles = new java.util.ArrayList<>();
                    if (rawFiles != null) {
                        for (File f : rawFiles) {
                            if (f.isFile() && f.getName().endsWith(".txt")) {
                                styleNames.add(f.getName().replace(".txt", ""));
                                styleFiles.add(f);
                            }
                        }
                    }
                    java.util.Collections.sort(styleNames);
                    final java.util.List<File> sortedFiles = new java.util.ArrayList<>();
                    for (String nm : styleNames) {
                        for (File sf : styleFiles) {
                            if (sf.getName().replace(".txt", "").equals(nm)) { sortedFiles.add(sf); break; }
                        }
                    }

                    if (styleNames.isEmpty()) {
                        Toast.makeText(MainActivity.this, "Belum ada gaya di kategori ini.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String gayaAktifNama = UiKit.readStringPref(prefs, "gaya_aktif_nama", "Standar");
                    String[] displayNames = new String[styleNames.size()];
                    for (int i = 0; i < styleNames.size(); i++) {
                        displayNames[i] = styleNames.get(i).equals(gayaAktifNama)
                                ? "[AKTIF] " + styleNames.get(i)
                                : styleNames.get(i);
                    }

                    UiKit.dialog(MainActivity.this, prefs)
                            .setTitle("Pilih Gaya - " + namaKat)
                            .setItems(displayNames, (dS, idxStyle) -> {
                                File styleFile = sortedFiles.get(idxStyle);
                                try {
                                    java.io.BufferedReader br = new java.io.BufferedReader(
                                            new java.io.InputStreamReader(new java.io.FileInputStream(styleFile), "UTF-8"));
                                    StringBuilder sb = new StringBuilder();
                                    String line;
                                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                                    br.close();
                                    String nama = styleFile.getName().replace(".txt", "");
                                    prefs.edit()
                                            .putString("gaya_aktif_nama", nama)
                                            .putString("gaya_aktif_prompt", sb.toString().trim())
                                            .apply();
                                    if (btnGayaAktif != null) btnGayaAktif.setText("GAYA AKTIF: " + nama);
                                    Toast.makeText(MainActivity.this, "Gaya \"" + nama + "\" diaktifkan!", Toast.LENGTH_LONG).show();
                                } catch (Exception e) {
                                    Toast.makeText(MainActivity.this, "Gagal baca file gaya.", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton("Batal", null)
                            .show();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void showManageStyle() {
        final File pathGaya = new File(getExternalFilesDir(null), "gaya input suara final");
        if (!pathGaya.exists()) pathGaya.mkdirs();
        refreshManageStyleDialog(pathGaya);
    }

    private void refreshManageStyleDialog(final File pathGaya) {
        if (!UiKit.isActivityAlive(this)) return;
        File[] rawFolders = pathGaya.listFiles();
        final java.util.List<String> folderNames = new java.util.ArrayList<>();
        if (rawFolders != null) {
            for (File f : rawFolders) { if (f.isDirectory()) folderNames.add(f.getName()); }
        }
        java.util.Collections.sort(folderNames);

        final AlertDialog[] dialogRef = new AlertDialog[1];
        AlertDialog.Builder dM = UiKit.dialog(this, prefs);
        dM.setTitle("Koleksi Gaya Lu (" + folderNames.size() + " Kategori)");

        LinearLayout rootM = new LinearLayout(this);
        rootM.setOrientation(LinearLayout.VERTICAL);
        rootM.setPadding(30, 30, 30, 30);

        Button btnBuatKat = new Button(this);
        btnBuatKat.setText("+ BIKIN KATEGORI BARU");
        UiKit.styleButton(this, btnBuatKat, UiKit.ACCENT_OFFICE);
        rootM.addView(btnBuatKat);
        btnBuatKat.setOnClickListener(v -> {
            final EditText etCat = new EditText(MainActivity.this);
            etCat.setHint("Nama kategori...");
            UiKit.styleEditText(MainActivity.this, etCat, prefs);
            UiKit.dialog(MainActivity.this, prefs)
                    .setTitle("Kategori Baru")
                    .setView(etCat)
                    .setPositiveButton("SIMPAN", (d, w) -> {
                        String nm = etCat.getText().toString().trim();
                        if (!nm.isEmpty()) {
                            new File(pathGaya, nm).mkdirs();
                            if (dialogRef[0] != null) dialogRef[0].dismiss();
                            refreshManageStyleDialog(pathGaya);
                        }
                    })
                    .setNegativeButton("Batal", null)
                    .show();
        });

        if (folderNames.isEmpty()) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("Belum ada kategori. Coba buka ulang app.");
            tvEmpty.setTextColor(UiKit.textPrimary(prefs));
            tvEmpty.setPadding(0, 20, 0, 20);
            rootM.addView(tvEmpty);
        } else {
            TextView tvInfo = new TextView(this);
            tvInfo.setText("Klik = Masuk Kategori | Tahan = Hapus Folder");
            tvInfo.setTextColor(UiKit.textMuted(prefs));
            tvInfo.setPadding(0, 15, 0, 10);
            rootM.addView(tvInfo);

            final ListView lvCat = new ListView(this);
            lvCat.setAdapter(makeThemedListAdapter(folderNames));
            rootM.addView(lvCat, new LinearLayout.LayoutParams(-1, 900));

            lvCat.setOnItemClickListener((parent, view, pos, id) -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                showManageFilesInFolder(new File(pathGaya, folderNames.get(pos)), pathGaya);
            });
            lvCat.setOnItemLongClickListener((parent, view, pos, id) -> {
                final File folder = new File(pathGaya, folderNames.get(pos));
                UiKit.dialog(MainActivity.this, prefs)
                        .setTitle("Hapus Folder?")
                        .setMessage("Hapus folder \"" + folder.getName() + "\" beserta semua gaya di dalamnya?")
                        .setPositiveButton("HAPUS", (d, w) -> {
                            deleteRecursive(folder);
                            if (dialogRef[0] != null) dialogRef[0].dismiss();
                            refreshManageStyleDialog(pathGaya);
                        })
                        .setNegativeButton("Batal", null)
                        .show();
                return true;
            });
        }

        ScrollView sc = new ScrollView(this);
        sc.addView(rootM);
        dM.setView(sc);
        dM.setPositiveButton("TUTUP", null);
        dialogRef[0] = dM.show();
    }

    private void showManageFilesInFolder(final File folder, final File pathGaya) {
        if (!UiKit.isActivityAlive(this)) return;
        final AlertDialog[] catRef = new AlertDialog[1];
        AlertDialog.Builder b = UiKit.dialog(this, prefs);
        b.setTitle("Kategori: " + folder.getName());

        LinearLayout rootCat = new LinearLayout(this);
        rootCat.setOrientation(LinearLayout.VERTICAL);
        rootCat.setPadding(30, 30, 30, 30);

        Button btnBuat = new Button(this);
        btnBuat.setText("BIKIN GAYA MANUAL");
        UiKit.styleButton(this, btnBuat, UiKit.ACCENT_KB);
        rootCat.addView(btnBuat);
        btnBuat.setOnClickListener(v -> {
            LinearLayout lAdd = new LinearLayout(MainActivity.this);
            lAdd.setOrientation(LinearLayout.VERTICAL);
            lAdd.setPadding(40, 40, 40, 40);
            final EditText etName = new EditText(MainActivity.this);
            etName.setHint("Nama Gaya");
            UiKit.styleEditText(MainActivity.this, etName, prefs);
            lAdd.addView(etName);
            final EditText etIsi = new EditText(MainActivity.this);
            etIsi.setHint("Instruksi Prompt AI...");
            etIsi.setMinLines(4);
            UiKit.styleEditText(MainActivity.this, etIsi, prefs);
            lAdd.addView(etIsi);
            UiKit.dialog(MainActivity.this, prefs)
                    .setTitle("Buat Gaya Baru")
                    .setView(lAdd)
                    .setPositiveButton("SIMPAN", (dialog, which) -> {
                        String nm = etName.getText().toString().trim();
                        if (nm.isEmpty()) return;
                        try {
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(new File(folder, nm + ".txt"));
                            fos.write(etIsi.getText().toString().getBytes("UTF-8"));
                            fos.close();
                            Toast.makeText(MainActivity.this, "Gaya manual disimpan!", Toast.LENGTH_SHORT).show();
                            if (catRef[0] != null) catRef[0].dismiss();
                            showManageFilesInFolder(folder, pathGaya);
                        } catch (Exception ignored) {}
                    })
                    .setNegativeButton("Batal", null)
                    .show();
        });

        File[] rawFiles = folder.listFiles();
        final java.util.List<String> styleNames = new java.util.ArrayList<>();
        final java.util.List<File> styleFiles = new java.util.ArrayList<>();
        if (rawFiles != null) {
            for (File f : rawFiles) {
                if (f.isFile() && f.getName().endsWith(".txt")) {
                    styleNames.add(f.getName().replace(".txt", ""));
                    styleFiles.add(f);
                }
            }
        }
        java.util.Collections.sort(styleNames);
        final java.util.List<File> sortedFiles = new java.util.ArrayList<>();
        for (String nm : styleNames) {
            for (File sf : styleFiles) {
                if (sf.getName().replace(".txt", "").equals(nm)) { sortedFiles.add(sf); break; }
            }
        }

        if (!styleNames.isEmpty()) {
            String gayaAktif = UiKit.readStringPref(prefs, "gaya_aktif_nama", "Standar");
            java.util.List<String> displayNames = new java.util.ArrayList<>();
            for (int i = 0; i < styleNames.size(); i++) {
                displayNames.add(styleNames.get(i).equals(gayaAktif) ? "[AKTIF] " + styleNames.get(i) : styleNames.get(i));
            }

            TextView tvInfo = new TextView(this);
            tvInfo.setText("Klik = Aktifkan | Tahan = Edit/Hapus");
            tvInfo.setTextColor(UiKit.textMuted(prefs));
            tvInfo.setPadding(0, 15, 0, 10);
            rootCat.addView(tvInfo);

            final ListView lv = new ListView(this);
            lv.setAdapter(makeThemedListAdapter(displayNames));
            rootCat.addView(lv, new LinearLayout.LayoutParams(-1, 900));

            lv.setOnItemClickListener((parent, view, position, id) -> {
                File sf = sortedFiles.get(position);
                try {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(new java.io.FileInputStream(sf), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                    br.close();
                    String nama = sf.getName().replace(".txt", "");
                    prefs.edit().putString("gaya_aktif_nama", nama).putString("gaya_aktif_prompt", sb.toString().trim()).apply();
                    Toast.makeText(MainActivity.this, "Gaya \"" + nama + "\" diaktifkan!", Toast.LENGTH_LONG).show();
                    if (catRef[0] != null) catRef[0].dismiss();
                    showManageFilesInFolder(folder, pathGaya);
                } catch (Exception ignored) {}
            });
            lv.setOnItemLongClickListener((parent, view, position, id) -> {
                final File sf = sortedFiles.get(position);
                final String sName = sf.getName().replace(".txt", "");
                UiKit.dialog(MainActivity.this, prefs)
                        .setTitle("Kelola: " + sName)
                        .setItems(new String[]{"Edit Isi", "Hapus Gaya"}, (d, i) -> {
                            if (i == 0) {
                                try {
                                    java.io.BufferedReader br = new java.io.BufferedReader(
                                            new java.io.InputStreamReader(new java.io.FileInputStream(sf), "UTF-8"));
                                    StringBuilder sb = new StringBuilder();
                                    String line;
                                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                                    br.close();
                                    final EditText etIsi = new EditText(MainActivity.this);
                                    etIsi.setText(sb.toString());
                                    etIsi.setMinLines(5);
                                    UiKit.styleEditText(MainActivity.this, etIsi, prefs);
                                    UiKit.dialog(MainActivity.this, prefs)
                                            .setTitle("Edit: " + sName)
                                            .setView(etIsi)
                                            .setPositiveButton("SIMPAN", (dialog, which) -> {
                                                try {
                                                    java.io.FileOutputStream fos = new java.io.FileOutputStream(sf);
                                                    fos.write(etIsi.getText().toString().getBytes("UTF-8"));
                                                    fos.close();
                                                    if (UiKit.readStringPref(prefs, "gaya_aktif_nama", "").equals(sName)) {
                                                        prefs.edit().putString("gaya_aktif_prompt", etIsi.getText().toString().trim()).apply();
                                                    }
                                                    Toast.makeText(MainActivity.this, "Gaya diupdate!", Toast.LENGTH_SHORT).show();
                                                } catch (Exception ignored) {}
                                            })
                                            .setNegativeButton("Batal", null)
                                            .show();
                                } catch (Exception ignored) {}
                            } else {
                                UiKit.dialog(MainActivity.this, prefs)
                                        .setTitle("Hapus?")
                                        .setMessage("Hapus gaya \"" + sName + "\"?")
                                        .setPositiveButton("HAPUS", (d2, w) -> {
                                            sf.delete();
                                            Toast.makeText(MainActivity.this, "Gaya dihapus.", Toast.LENGTH_SHORT).show();
                                            if (catRef[0] != null) catRef[0].dismiss();
                                            showManageFilesInFolder(folder, pathGaya);
                                        })
                                        .setNegativeButton("Batal", null)
                                        .show();
                            }
                        })
                        .setNegativeButton("Batal", null)
                        .show();
                return true;
            });
        } else {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("Belum ada gaya di kategori ini, Bro.");
            tvEmpty.setTextColor(UiKit.textPrimary(prefs));
            tvEmpty.setPadding(0, 20, 0, 20);
            rootCat.addView(tvEmpty);
        }

        ScrollView sc = new ScrollView(this);
        sc.addView(rootCat);
        b.setView(sc);
        b.setNeutralButton("Kategori", (d, w) -> refreshManageStyleDialog(pathGaya));
        b.setPositiveButton("TUTUP", null);
        catRef[0] = b.show();
    }

    private void deleteRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) { for (File child : children) deleteRecursive(child); }
        }
        fileOrDir.delete();
    }

}
