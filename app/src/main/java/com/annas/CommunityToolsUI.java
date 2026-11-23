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
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Kumpulan fitur produktivitas, AI, aksesibilitas tunanetra & awas, serta pencadangan penuh
 * ke penyimpanan utama (/storage/emulated/0/Keyboard AI by Anas/Cadangan).
 */
public final class CommunityToolsUI {

    public interface VoiceTargetCallback {
        void onRequestVoiceInput(EditText target);
    }

    private CommunityToolsUI() {}

    // =========================================================================
    // 1. ASISTEN TULIS AI CEPAT (10 PRESET + BACA SUARA TTS)
    // =========================================================================
    public static void showAiWriteAssistant(final Activity act, final SharedPreferences prefs,
                                            final VoiceTargetCallback voiceCb) {
        final boolean dark = UiKit.isDark(prefs);
        AlertDialog.Builder builder = UiKit.dialog(act, prefs);
        builder.setTitle("Asisten Tulis AI Cepat");

        ScrollView scroll = new ScrollView(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 14), UiKit.dp(act, 16), UiKit.dp(act, 14));
        root.setBackgroundColor(UiKit.bg(prefs));

        TextView tvInfo = new TextView(act);
        tvInfo.setText("Ketik, tempel, atau ucapkan teks lalu pilih aksi cepat AI di bawah. Hasil bisa langsung disalin atau dibacakan dengan suara.");
        tvInfo.setTextColor(UiKit.textMuted(prefs));
        tvInfo.setTextSize(13);
        tvInfo.setPadding(0, 0, 0, UiKit.dp(act, 10));
        root.addView(tvInfo);

        final EditText etInput = new EditText(act);
        etInput.setHint("Masukkan teks yang ingin diolah AI...");
        etInput.setMinLines(3);
        etInput.setMaxLines(6);
        etInput.setTextColor(UiKit.textPrimary(prefs));
        etInput.setHintTextColor(UiKit.textMuted(prefs));
        etInput.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 10), UiKit.dp(act, 12), UiKit.dp(act, 10));
        etInput.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 12), UiKit.dp(act, 1)));
        root.addView(etInput);

        LinearLayout inputTools = new LinearLayout(act);
        inputTools.setOrientation(LinearLayout.HORIZONTAL);
        inputTools.setPadding(0, UiKit.dp(act, 8), 0, UiKit.dp(act, 10));

        inputTools.addView(UiKit.makePillButton(act, "Tempel", UiKit.ACCENT_SYSTEM, Color.WHITE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    ClipboardManager cb = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip() != null && cb.getPrimaryClip().getItemCount() > 0) {
                        CharSequence txt = cb.getPrimaryClip().getItemAt(0).coerceToText(act);
                        if (txt != null) {
                            etInput.setText(txt.toString());
                            etInput.setSelection(etInput.getText().length());
                        }
                    } else {
                        Toast.makeText(act, "Papan klip masih kosong", Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable ignored) {}
            }
        }));

        View sp1 = new View(act);
        inputTools.addView(sp1, new LinearLayout.LayoutParams(UiKit.dp(act, 8), 1));

        inputTools.addView(UiKit.makePillButton(act, "Dikte Suara", UiKit.ACCENT_KB, Color.WHITE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (voiceCb != null) voiceCb.onRequestVoiceInput(etInput);
            }
        }));

        View sp2 = new View(act);
        inputTools.addView(sp2, new LinearLayout.LayoutParams(UiKit.dp(act, 8), 1));

        inputTools.addView(UiKit.makePillButton(act, "Bersihkan", dark ? UiKit.DARK_SURFACE_ALT : UiKit.LIGHT_SURFACE_ALT, UiKit.textPrimary(prefs), new View.OnClickListener() {
            @Override public void onClick(View v) { etInput.setText(""); }
        }));
        root.addView(inputTools);

        final TextView tvResult = new TextView(act);
        tvResult.setText("Hasil olahan AI akan tampil di sini...");
        tvResult.setTextIsSelectable(true);
        tvResult.setTextColor(UiKit.textPrimary(prefs));
        tvResult.setTextSize(14);
        tvResult.setPadding(UiKit.dp(act, 14), UiKit.dp(act, 12), UiKit.dp(act, 14), UiKit.dp(act, 12));
        tvResult.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 12), UiKit.dp(act, 1)));

        final String[][] presets = {
            {"Rapikan Ejaan & Tanda Baca", "Perbaiki ejaan, salah ketik, dan tanda baca teks ini dengan bahasa Indonesia yang natural dan rapi. Kembalikan hasilnya saja."},
            {"Ubah Jadi Bahasa Sopan / Formal", "Ubah teks berikut menjadi bahasa Indonesia yang sopan, rapi, dan hormat untuk atasan, dosen, atau klien. Kembalikan hasilnya saja."},
            {"Ubah Jadi Bahasa Santai & Akrab", "Ubah teks berikut menjadi gaya bahasa santai dan akrab untuk teman dekat. Kembalikan hasilnya saja."},
            {"Gaya Email / Bisnis Profesional", "Susun ulang teks berikut menjadi format pesan atau email profesional yang jelas, ringkas, dan berwibawa."},
            {"Ringkas Jadi Poin Penting", "Ringkas teks berikut menjadi poin-poin inti yang padat dan mudah dibaca."},
            {"Kembangkan Jadi Paragraf Lengkap", "Kembangkan gagasan singkat berikut menjadi paragraf yang mengalir rapi, jelas, dan informatif."},
            {"Buatkan 3 Ide Balasan Chat", "Buatkan 3 pilihan balasan pesan yang natural untuk teks berikut: 1) Sopan, 2) Santai, 3) Singkat dan jelas."},
            {"Perbaiki Bahasa Inggris (Grammar)", "Correct the English grammar, spelling, and natural phrasing of the following text. Return only the improved text."},
            {"Buat Caption Sosial Media + Tagar", "Buatkan caption media sosial yang menarik beserta tagar yang relevan berdasarkan topik berikut."},
            {"Jelaskan dengan Bahasa Sederhana", "Jelaskan isi teks berikut menggunakan bahasa Indonesia sehari-hari yang sangat sederhana dan mudah dipahami siapa saja."}
        };

        root.addView(UiKit.makeSectionLabel(act, "PILIH AKSI CEPAT AI (10 PRESET)", UiKit.ACCENT_AI));
        for (int i = 0; i < presets.length; i++) {
            final String title = presets[i][0];
            final String sysPrompt = presets[i][1];
            int color = (i % 2 == 0) ? UiKit.ACCENT_AI : UiKit.ACCENT_AI_ALT;
            Button btnPreset = new Button(act);
            btnPreset.setText(title);
            UiKit.styleButton(act, btnPreset, color);
            btnPreset.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    final String src = etInput.getText().toString().trim();
                    if (src.isEmpty()) {
                        Toast.makeText(act, "Isi atau tempel teks terlebih dahulu.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!AuthManager.isPremiumActive(act)) {
                        AuthManager.showPremiumRequiredDialog(act, title, null);
                        return;
                    }
                    tvResult.setText("AI sedang memproses: " + title + "...");
                    runSingleAiPrompt(act, prefs, sysPrompt, src, new AiCallback() {
                        @Override public void onResult(String out) {
                            tvResult.setText(out);
                            prefs.edit().putLong("stat_ai_requests", prefs.getLong("stat_ai_requests", 0L) + 1L).apply();
                        }
                        @Override public void onError(String err) {
                            tvResult.setText("Gagal memproses AI: " + err);
                        }
                    });
                }
            });
            root.addView(btnPreset);
        }

        root.addView(UiKit.makeSectionLabel(act, "HASIL OLAHAN AI", UiKit.ACCENT_KB));
        root.addView(tvResult);

        final TextToSpeech[] ttsHolder = new TextToSpeech[1];
        LinearLayout outTools = new LinearLayout(act);
        outTools.setOrientation(LinearLayout.HORIZONTAL);
        outTools.setPadding(0, UiKit.dp(act, 10), 0, UiKit.dp(act, 4));

        outTools.addView(UiKit.makePillButton(act, "Salin", UiKit.ACCENT_OFFICE, Color.WHITE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String res = tvResult.getText().toString().trim();
                if (res.isEmpty() || res.startsWith("Hasil olahan") || res.startsWith("⏳")) {
                    Toast.makeText(act, "Belum ada hasil untuk disalin", Toast.LENGTH_SHORT).show();
                    return;
                }
                ClipboardManager cb = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("Hasil AI", res));
                Toast.makeText(act, "Hasil AI berhasil disalin ke Papan Klip.", Toast.LENGTH_SHORT).show();
            }
        }));

        View sp3 = new View(act);
        outTools.addView(sp3, new LinearLayout.LayoutParams(UiKit.dp(act, 8), 1));

        outTools.addView(UiKit.makePillButton(act, "Bacakan", UiKit.ACCENT_KB, Color.WHITE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String res = tvResult.getText().toString().trim();
                if (res.isEmpty() || res.startsWith("Hasil olahan") || res.startsWith("⏳")) return;
                if (ttsHolder[0] == null) {
                    ttsHolder[0] = new TextToSpeech(act.getApplicationContext(), new TextToSpeech.OnInitListener() {
                        @Override public void onInit(int status) {
                            if (status == TextToSpeech.SUCCESS && ttsHolder[0] != null) {
                                ttsHolder[0].setLanguage(new Locale("id", "ID"));
                                ttsHolder[0].speak(res, TextToSpeech.QUEUE_FLUSH, null, "ai_read");
                            }
                        }
                    });
                } else {
                    ttsHolder[0].speak(res, TextToSpeech.QUEUE_FLUSH, null, "ai_read");
                }
            }
        }));

        View sp4 = new View(act);
        outTools.addView(sp4, new LinearLayout.LayoutParams(UiKit.dp(act, 8), 1));

        outTools.addView(UiKit.makePillButton(act, "Hentikan Suara", UiKit.ACCENT_SYSTEM, Color.WHITE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (ttsHolder[0] != null) ttsHolder[0].stop();
            }
        }));

        View sp5 = new View(act);
        outTools.addView(sp5, new LinearLayout.LayoutParams(UiKit.dp(act, 6), 1));

        outTools.addView(UiKit.makePillButton(act, "Simpan (.txt)", UiKit.ACCENT_AI, Color.WHITE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String res = tvResult.getText().toString().trim();
                if (res.isEmpty() || res.startsWith("Hasil olahan") || res.startsWith("⏳")) {
                    Toast.makeText(act, "Belum ada hasil untuk disimpan", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    java.io.File savedFile = OfficeUtils.saveTextToBackupFolder(act, "Teks & Catatan AI", "Catatan_AI", res);
                    Toast.makeText(act, savedFile != null ? "Disimpan ke Teks & Catatan AI:\n" + savedFile.getAbsolutePath() : "Gagal menyimpan.", Toast.LENGTH_LONG).show();
                } catch (Exception ex) {
                    Toast.makeText(act, "Gagal menyimpan: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        }));
        root.addView(outTools);

        scroll.addView(root);
        builder.setView(scroll);
        builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) {
                if (ttsHolder[0] != null) { try { ttsHolder[0].stop(); ttsHolder[0].shutdown(); } catch (Throwable ignored) {} }
            }
        });
        builder.show();
    }

    // =========================================================================
    // 2. KOTAK TES KEYBOARD & PASSWORD
    // =========================================================================
    public static void showKeyboardTesterDialog(final Activity act, final SharedPreferences prefs) {
        AlertDialog.Builder builder = UiKit.dialog(act, prefs);
        builder.setTitle("Kotak Uji Ketik & Kata Sandi");

        ScrollView scroll = new ScrollView(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 14), UiKit.dp(act, 16), UiKit.dp(act, 14));
        root.setBackgroundColor(UiKit.bg(prefs));

        TextView desc = new TextView(act);
        desc.setText("Uji langsung kecepatan ketik, geser Spasi untuk kursor, geser tombol Hapus ke kiri untuk hapus kata, kalkulator otomatis (contoh: 25*4= lalu Spasi), serta huruf kecil otomatis di kolom Email & Kata Sandi.");
        desc.setTextColor(UiKit.textMuted(prefs));
        desc.setTextSize(13);
        desc.setPadding(0, 0, 0, UiKit.dp(act, 10));
        root.addView(desc);

        final EditText etChat = makeTestField(act, prefs, "Kolom Kalimat Chat", "Contoh: halo. nama saya anas...",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        final EditText etFree = makeTestField(act, prefs, "Kolom Teks Bebas", "Mulai huruf kecil otomatis...",
                InputType.TYPE_CLASS_TEXT);
        final EditText etEmail = makeTestField(act, prefs, "Kolom Email atau Tautan", "Contoh: nama@gmail.com (otomatis huruf kecil)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        final EditText etPass = makeTestField(act, prefs, "Kolom Kata Sandi", "Wajib huruf kecil dari awal hingga akhir",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        root.addView(labelForField(act, prefs, "1. Kalimat Chat (Kapital hanya setelah '. ' atau baris baru)"));
        root.addView(etChat);
        root.addView(labelForField(act, prefs, "2. Pencarian / Teks Bebas (Mulai huruf kecil)"));
        root.addView(etFree);
        root.addView(labelForField(act, prefs, "3. Email / URL (Otomatis huruf kecil penuh)"));
        root.addView(etEmail);
        root.addView(labelForField(act, prefs, "4. Kata Sandi (Wajib huruf kecil dari awal hingga akhir)"));
        root.addView(etPass);

        final CheckBox cbShowPass = new CheckBox(act);
        cbShowPass.setText("Tampilkan isi kata sandi untuk memastikan huruf kecil/besar");
        cbShowPass.setTextColor(UiKit.textPrimary(prefs));
        cbShowPass.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int sel = etPass.getSelectionEnd();
                etPass.setInputType(cbShowPass.isChecked()
                        ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                        : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
                if (sel >= 0) etPass.setSelection(Math.min(sel, etPass.getText().length()));
            }
        });
        root.addView(cbShowPass);

        scroll.addView(root);
        builder.setView(scroll);
        builder.setNeutralButton("Bersihkan Semua", null);
        builder.setPositiveButton("Selesai", null);
        final AlertDialog dlg = builder.create();
        dlg.show();
        Button neutral = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neutral != null) {
            neutral.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    etChat.setText(""); etFree.setText(""); etEmail.setText(""); etPass.setText("");
                    Toast.makeText(act, "Semua kolom uji dibersihkan", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private static TextView labelForField(Activity act, SharedPreferences prefs, String title) {
        TextView tv = new TextView(act);
        tv.setText(title);
        tv.setTextColor(UiKit.textPrimary(prefs));
        tv.setTextSize(13);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, UiKit.dp(act, 10), 0, UiKit.dp(act, 4));
        return tv;
    }

    private static EditText makeTestField(Activity act, SharedPreferences prefs, String desc, String hint, int inputType) {
        EditText et = new EditText(act);
        et.setHint(hint);
        et.setContentDescription(desc);
        et.setInputType(inputType);
        et.setTextColor(UiKit.textPrimary(prefs));
        et.setHintTextColor(UiKit.textMuted(prefs));
        et.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 10), UiKit.dp(act, 12), UiKit.dp(act, 10));
        et.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 10), UiKit.dp(act, 1)));
        return et;
    }

    // =========================================================================
    // 3. CADANGKAN & PULIHKAN LENGKAP -> /storage/emulated/0/Keyboard AI by Anas/Cadangan/
    // =========================================================================
    private static boolean hasPrimaryStorageWriteAccess(Activity act) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return act.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static File resolveBackupFolder(Activity act) {
        return OfficeUtils.resolveBackupSubfolder(act, "Cadangan");
    }

    public static void showBackupRestoreDialog(final Activity act, final SharedPreferences prefs, final Runnable onRestored) {
        AlertDialog.Builder builder = UiKit.dialog(act, prefs);
        builder.setTitle("Cadangkan & Pulihkan Lengkap");

        ScrollView scroll = new ScrollView(act);
        final LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 14), UiKit.dp(act, 16), UiKit.dp(act, 14));
        root.setBackgroundColor(UiKit.bg(prefs));

        final boolean granted = hasPrimaryStorageWriteAccess(act);
        final File targetDir = resolveBackupFolder(act);

        TextView tvPath = new TextView(act);
        tvPath.setText((granted
                ? "Penyimpanan Utama Aktif:\n" + targetDir.getAbsolutePath()
                : "Perhatian: Izin Semua Berkas belum aktif (masih memakai folder cadangan aplikasi). Aktifkan agar cadangan tersimpan langsung di /storage/emulated/0/Keyboard AI by Anas/Cadangan")
                + "\n\nCadangan menyimpan 100% setelan keyboard, Braille, kalibrasi titik, suara, STT, kamus, makro, template cepat, item pin klip, dan berkas gaya bahasa.");
        tvPath.setTextColor(UiKit.textPrimary(prefs));
        tvPath.setTextSize(13);
        tvPath.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 10), UiKit.dp(act, 12), UiKit.dp(act, 10));
        tvPath.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 12), UiKit.dp(act, 1)));
        root.addView(tvPath);

        if (!granted) {
            Button btnGrant = new Button(act);
            btnGrant.setText("Izinkan Simpan ke Penyimpanan Utama (storage/0)");
            UiKit.styleButton(act, btnGrant, UiKit.ACCENT_WARM);
            btnGrant.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + act.getPackageName()));
                            act.startActivity(i);
                        } else {
                            act.requestPermissions(new String[]{
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                            }, 701);
                        }
                    } catch (Throwable t) {
                        Toast.makeText(act, "Buka Pengaturan Aplikasi -> Izin -> Penyimpanan / Semua Berkas", Toast.LENGTH_LONG).show();
                    }
                }
            });
            root.addView(btnGrant);
        }

        final CheckBox cbIncludeKeys = new CheckBox(act);
        cbIncludeKeys.setText("Sertakan Kunci API AI pribadi (matikan jika file ingin dibagikan ke orang lain)");
        cbIncludeKeys.setChecked(false);
        cbIncludeKeys.setTextColor(UiKit.textPrimary(prefs));
        cbIncludeKeys.setPadding(0, UiKit.dp(act, 6), 0, UiKit.dp(act, 6));
        root.addView(cbIncludeKeys);

        final LinearLayout listFilesLayout = new LinearLayout(act);
        listFilesLayout.setOrientation(LinearLayout.VERTICAL);

        final Runnable refreshBackupList = new Runnable() {
            @Override public void run() {
                listFilesLayout.removeAllViews();
                File dir = resolveBackupFolder(act);
                File[] files = dir.listFiles();
                if (files == null || files.length == 0) {
                    TextView empty = new TextView(act);
                    empty.setText("Belum ada berkas cadangan di folder ini.");
                    empty.setTextColor(UiKit.textMuted(prefs));
                    empty.setTextSize(13);
                    empty.setPadding(UiKit.dp(act, 4), UiKit.dp(act, 6), UiKit.dp(act, 4), UiKit.dp(act, 8));
                    listFilesLayout.addView(empty);
                    return;
                }
                Arrays.sort(files, new Comparator<File>() {
                    @Override public int compare(File a, File b) {
                        return Long.compare(b.lastModified(), a.lastModified());
                    }
                });
                for (final File f : files) {
                    if (!f.isFile() || !f.getName().endsWith(".json")) continue;
                    String sizeKb = String.format(Locale.US, "%.1f KB", f.length() / 1024.0);
                    Button bItem = UiKit.makeMenuButton(act,
                            "Berkas Cadangan: " + f.getName(),
                            "Ukuran: " + sizeKb + " • Ketuk untuk pulihkan atau kelola",
                            UiKit.ACCENT_SYSTEM, prefs, new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    showSingleBackupActions(act, prefs, f, new Runnable() {
                                        @Override public void run() {
                                            if (onRestored != null) onRestored.run();
                                        }
                                    }, this);
                                }
                            });
                    listFilesLayout.addView(bItem);
                }
            }
        };

        Button btnCreateFile = new Button(act);
        btnCreateFile.setText("Buat Cadangan Lengkap ke File (.json)");
        UiKit.styleButton(act, btnCreateFile, UiKit.ACCENT_OFFICE);
        btnCreateFile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    String jsonStr = buildFullBackupJson(act, prefs, cbIncludeKeys.isChecked());
                    File dir = resolveBackupFolder(act);
                    String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
                    File outFile = new File(dir, "KeyboardAI_Cadangan_" + stamp + ".json");
                    FileOutputStream fos = new FileOutputStream(outFile);
                    fos.write(jsonStr.getBytes("UTF-8"));
                    fos.close();
                    Toast.makeText(act, "Cadangan lengkap tersimpan di:\n" + outFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    refreshBackupList.run();
                } catch (Exception e) {
                    Toast.makeText(act, "Gagal menyimpan berkas: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        root.addView(btnCreateFile);

        Button btnCopyBackup = new Button(act);
        btnCopyBackup.setText("Salin Cadangan Lengkap ke Papan Klip");
        UiKit.styleButton(act, btnCopyBackup, UiKit.ACCENT_KB);
        btnCopyBackup.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    String jsonStr = buildFullBackupJson(act, prefs, cbIncludeKeys.isChecked());
                    ClipboardManager cb = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("Cadangan Lengkap Keyboard AI Anas", jsonStr));
                    Toast.makeText(act, "Seluruh setelan & gaya berhasil disalin ke Papan Klip.", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(act, "Gagal membuat cadangan: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(btnCopyBackup);

        root.addView(UiKit.makeSectionLabel(act, "BERKAS CADANGAN TERSIMPAN", UiKit.ACCENT_AI));
        refreshBackupList.run();
        root.addView(listFilesLayout);

        root.addView(UiKit.makeSectionLabel(act, "PULIHKAN DARI TEKS JSON", UiKit.ACCENT_AI));
        final EditText etJson = new EditText(act);
        etJson.setHint("Atau tempel teks JSON cadangan di sini...");
        etJson.setMinLines(3);
        etJson.setMaxLines(7);
        etJson.setTextColor(UiKit.textPrimary(prefs));
        etJson.setHintTextColor(UiKit.textMuted(prefs));
        etJson.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 10), UiKit.dp(act, 12), UiKit.dp(act, 10));
        etJson.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 12), UiKit.dp(act, 1)));
        root.addView(etJson);

        Button btnImport = new Button(act);
        btnImport.setText("Pulihkan Sekarang dari Teks JSON");
        UiKit.styleButton(act, btnImport, UiKit.ACCENT_AI);
        btnImport.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String raw = etJson.getText().toString().trim();
                if (raw.isEmpty()) {
                    Toast.makeText(act, "Tempel teks JSON terlebih dahulu atau pilih berkas cadangan di atas.", Toast.LENGTH_SHORT).show();
                    return;
                }
                restoreFromBackupJson(act, prefs, raw, onRestored);
            }
        });
        root.addView(btnImport);

        scroll.addView(root);
        builder.setView(scroll);
        builder.show();
    }

    private static void showSingleBackupActions(final Activity act, final SharedPreferences prefs,
                                                final File file, final Runnable onRestored,
                                                final View.OnClickListener refreshAnchor) {
        final String[] opts = {"Pulihkan Semua Setelan dari Berkas Ini", "Salin Isi Cadangan ke Papan Klip", "Hapus Berkas Cadangan"};
        UiKit.dialog(act, prefs)
                .setTitle(file.getName())
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        try {
                            if (which == 0) {
                                String content = readFileUtf8(file);
                                restoreFromBackupJson(act, prefs, content, onRestored);
                            } else if (which == 1) {
                                String content = readFileUtf8(file);
                                ClipboardManager cb = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cb != null) cb.setPrimaryClip(ClipData.newPlainText(file.getName(), content));
                                Toast.makeText(act, "Isi berkas cadangan disalin ke Papan Klip.", Toast.LENGTH_SHORT).show();
                            } else if (which == 2) {
                                if (file.delete()) {
                                    Toast.makeText(act, "Berkas cadangan dihapus. Buka ulang menu untuk menyegarkan daftar.", Toast.LENGTH_SHORT).show();
                                }
                            }
                        } catch (Exception e) {
                            Toast.makeText(act, "Gagal memproses berkas: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private static String buildFullBackupJson(Activity act, SharedPreferences prefs, boolean includeApiKeys) throws Exception {
        JSONObject rootJson = new JSONObject();
        rootJson.put("app", "KeyboardAIByAnas");
        rootJson.put("format", 2);
        rootJson.put("exported_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));

        JSONObject settings = new JSONObject();
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (!includeApiKeys && key.startsWith("api_key_")) continue;
            if ("chat_hist".equals(key)) continue;
            Object val = entry.getValue();
            if (val != null) settings.put(key, val);
        }
        rootJson.put("settings", settings);

        // Sertakan seluruh berkas di folder gaya bahasa kustom
        JSONObject styleFiles = new JSONObject();
        File styleRoot = new File(act.getExternalFilesDir(null), "gaya input suara final");
        if (styleRoot.exists() && styleRoot.isDirectory()) {
            collectStyleFilesRecursive(styleRoot, "", styleFiles);
        }
        rootJson.put("custom_style_files", styleFiles);
        return rootJson.toString(2);
    }

    private static void collectStyleFilesRecursive(File dir, String prefix, JSONObject out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            String rel = prefix.isEmpty() ? f.getName() : (prefix + "/" + f.getName());
            if (f.isDirectory()) {
                collectStyleFilesRecursive(f, rel, out);
            } else if (f.isFile() && f.length() <= 512 * 1024) {
                try {
                    out.put(rel, readFileUtf8(f));
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void restoreFromBackupJson(Activity act, SharedPreferences prefs, String raw, Runnable onRestored) {
        try {
            JSONObject rootJson = new JSONObject(raw);
            JSONObject settings = rootJson.has("settings") ? rootJson.getJSONObject("settings") : rootJson;
            SharedPreferences.Editor ed = prefs.edit();
            JSONArray names = settings.names();
            int restoredSettings = 0;
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.getString(i);
                    Object val = settings.get(k);
                    if (val instanceof Boolean) ed.putBoolean(k, (Boolean) val);
                    else if (val instanceof Integer) ed.putInt(k, (Integer) val);
                    else if (val instanceof Long) {
                        long lv = (Long) val;
                        if (k.startsWith("stat_") || k.endsWith("_ts")) ed.putLong(k, lv);
                        else ed.putInt(k, (int) lv);
                    } else if (val instanceof Double) ed.putFloat(k, ((Double) val).floatValue());
                    else if (val instanceof String) ed.putString(k, (String) val);
                    restoredSettings++;
                }
            }
            ed.apply();

            int restoredFiles = 0;
            if (rootJson.has("custom_style_files")) {
                JSONObject filesObj = rootJson.optJSONObject("custom_style_files");
                if (filesObj != null) {
                    File styleRoot = new File(act.getExternalFilesDir(null), "gaya input suara final");
                    Iterator<String> keys = filesObj.keys();
                    while (keys.hasNext()) {
                        String relPath = keys.next();
                        if (relPath.contains("..")) continue;
                        File dst = new File(styleRoot, relPath);
                        File parent = dst.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        FileOutputStream fos = new FileOutputStream(dst);
                        fos.write(filesObj.optString(relPath, "").getBytes("UTF-8"));
                        fos.close();
                        restoredFiles++;
                    }
                }
            }

            Toast.makeText(act,
                    "Pemulihan selesai: " + restoredSettings + " setelan & " + restoredFiles + " berkas gaya bahasa dipulihkan!",
                    Toast.LENGTH_LONG).show();
            if (onRestored != null) onRestored.run();
        } catch (Exception e) {
            Toast.makeText(act, "Berkas cadangan tidak valid: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String readFileUtf8(File file) throws Exception {
        FileInputStream fis = new FileInputStream(file);
        BufferedReader br = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        br.close();
        return sb.toString();
    }

    // =========================================================================
    // 4. TEMPLATE PESAN CEPAT (QUICK SNIPPETS UNTUK PANEL ⚡ KEYBOARD)
    // =========================================================================
    public static void showQuickSnippetsDialog(final Activity act, final SharedPreferences prefs) {
        AlertDialog.Builder builder = UiKit.dialog(act, prefs);
        builder.setTitle("Template Pesan Cepat");

        ScrollView scroll = new ScrollView(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 14), UiKit.dp(act, 16), UiKit.dp(act, 14));
        root.setBackgroundColor(UiKit.bg(prefs));

        TextView desc = new TextView(act);
        desc.setText("Daftar kalimat siap pakai yang bisa disisipkan 1 ketukan lewat tombol Alat Cepat di bagian atas keyboard.");
        desc.setTextColor(UiKit.textMuted(prefs));
        desc.setTextSize(13);
        desc.setPadding(0, 0, 0, UiKit.dp(act, 10));
        root.addView(desc);

        final EditText etNew = new EditText(act);
        etNew.setHint("Tulis template pesan baru di sini...");
        etNew.setMinLines(2);
        etNew.setTextColor(UiKit.textPrimary(prefs));
        etNew.setHintTextColor(UiKit.textMuted(prefs));
        etNew.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 10), UiKit.dp(act, 12), UiKit.dp(act, 10));
        etNew.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 10), UiKit.dp(act, 1)));
        root.addView(etNew);

        final LinearLayout listWrap = new LinearLayout(act);
        listWrap.setOrientation(LinearLayout.VERTICAL);

        final Runnable[] renderRef = new Runnable[1];
        renderRef[0] = new Runnable() {
            @Override public void run() {
                listWrap.removeAllViews();
                final JSONArray arr = KeyboardQuickPanelHelper.loadOrDefaultSnippets(prefs);
                for (int i = 0; i < arr.length(); i++) {
                    final int idx = i;
                    final String txt = arr.optString(i, "");
                    Button b = UiKit.makeMenuButton(act, "Template: " + txt, "Ketuk untuk salin • tahan atau pilih untuk hapus",
                            UiKit.ACCENT_SYSTEM, prefs, new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    UiKit.dialog(act, prefs)
                                            .setTitle("Kelola Template")
                                            .setMessage(txt)
                                            .setPositiveButton("Salin", new DialogInterface.OnClickListener() {
                                                @Override public void onClick(DialogInterface d, int w) {
                                                    ClipboardManager cb = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                                                    if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("Template", txt));
                                                    Toast.makeText(act, "Disalin ke Papan Klip", Toast.LENGTH_SHORT).show();
                                                }
                                            })
                                            .setNegativeButton("Hapus", new DialogInterface.OnClickListener() {
                                                @Override public void onClick(DialogInterface d, int w) {
                                                    JSONArray next = new JSONArray();
                                                    for (int k = 0; k < arr.length(); k++) if (k != idx) next.put(arr.optString(k));
                                                    prefs.edit().putString("json_quick_snippets", next.toString()).apply();
                                                    renderRef[0].run();
                                                }
                                            })
                                            .setNeutralButton("Batal", null)
                                            .show();
                                }
                            });
                    listWrap.addView(b);
                }
            }
        };

        Button btnAdd = new Button(act);
        btnAdd.setText("Tambah ke Daftar Template");
        UiKit.styleButton(act, btnAdd, UiKit.ACCENT_KB);
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String txt = etNew.getText().toString().trim();
                if (txt.isEmpty()) return;
                JSONArray arr = KeyboardQuickPanelHelper.loadOrDefaultSnippets(prefs);
                arr.put(txt);
                prefs.edit().putString("json_quick_snippets", arr.toString()).apply();
                etNew.setText("");
                renderRef[0].run();
                Toast.makeText(act, "Template ditambahkan!", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(btnAdd);
        root.addView(UiKit.makeSectionLabel(act, "DAFTAR TEMPLATE TERSIMPAN", UiKit.ACCENT_KB));
        renderRef[0].run();
        root.addView(listWrap);

        scroll.addView(root);
        builder.setView(scroll);
        builder.show();
    }

    // =========================================================================
    // 5. PROFIL PENGATURAN CEPAT 1-KETUK (AWAS & TUNANETRA)
    // =========================================================================
    public static void showQuickProfilesDialog(final Activity act, final SharedPreferences prefs, final Runnable onApplied) {
        AlertDialog.Builder builder = UiKit.dialog(act, prefs);
        builder.setTitle("Profil Pengaturan Cepat");

        ScrollView scroll = new ScrollView(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 14), UiKit.dp(act, 16), UiKit.dp(act, 14));
        root.setBackgroundColor(UiKit.bg(prefs));

        TextView desc = new TextView(act);
        desc.setText("Atur perilaku & fitur cepat dalam 1 ketukan tanpa mengubah tinggi tombol atau jarak dasar keyboard Anda.");
        desc.setTextColor(UiKit.textMuted(prefs));
        desc.setTextSize(13);
        desc.setPadding(0, 0, 0, UiKit.dp(act, 10));
        root.addView(desc);

        root.addView(UiKit.makeMenuButton(act,
                "Profil Cepat Pengguna Awas (Standar Gboard)",
                "Respons instan 0ms, geser spasi gerak kursor, geser hapus kata, kalkulator & makro otomatis",
                UiKit.ACCENT_AI, prefs, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        prefs.edit()
                                .putBoolean("braille_default_mode", false)
                                .putString("akses_ngetik_mode", "Angkat Jari (Standar)")
                                .putBoolean("double_tap_mode", false)
                                .putInt("touch_response_ms", 0)
                                .putBoolean("popup_on_keypress", true)
                                .putBoolean("spacebar_cursor_glide", true)
                                .putBoolean("swipe_delete_word_enabled", true)
                                .putBoolean("macro_auto_expand_enabled", true)
                                .putBoolean("inline_calculator_enabled", true)
                                .putString("qwerty_tts_echo", "off")
                                .putString("keyboard_theme", "Dark")
                                .apply();
                        Toast.makeText(act, "Profil Cepat Pengguna Awas diterapkan!", Toast.LENGTH_LONG).show();
                        if (onApplied != null) onApplied.run();
                    }
                }));

        root.addView(UiKit.makeMenuButton(act,
                "Profil Ramah Tunanetra & Low Vision (QWERTY)",
                "Suara umpan balik per kata, umumkan kolom kata sandi, kontras kuning-hitam, getar tegas",
                UiKit.ACCENT_KB, prefs, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        prefs.edit()
                                .putBoolean("braille_default_mode", false)
                                .putString("qwerty_tts_echo", "word")
                                .putBoolean("tts_field_type_announce", true)
                                .putBoolean("advanced_edit_mode_enabled", true)
                                .putString("advanced_long_delete_mode", "word")
                                .putString("keyboard_theme", "HighContrastYellow")
                                .putString("ui_theme", "Dark")
                                .putBoolean("vibrate_on_keypress", true)
                                .putBoolean("vibe_enabled", true)
                                .putInt("vibe_duration", 30)
                                .apply();
                        Toast.makeText(act, "Profil Ramah Tunanetra QWERTY diterapkan!", Toast.LENGTH_LONG).show();
                        if (onApplied != null) onApplied.run();
                    }
                }));

        root.addView(UiKit.makeMenuButton(act,
                "Profil Braille Profesional (Layar Penuh)",
                "Aktifkan mode Braille sebagai papan ketik utama, suara karakter + kata, umumkan kata sandi otomatis",
                UiKit.ACCENT_AI_ALT, prefs, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        prefs.edit()
                                .putBoolean("braille_default_mode", true)
                                .putInt("braille_tts_granularity", 3)
                                .putBoolean("tts_field_type_announce", true)
                                .putBoolean("vibrate_on_keypress", true)
                                .putBoolean("vibe_enabled", true)
                                .putInt("vibe_duration", 30)
                                .apply();
                        Toast.makeText(act, "Profil Braille Profesional diterapkan!", Toast.LENGTH_LONG).show();
                        if (onApplied != null) onApplied.run();
                    }
                }));

        root.addView(UiKit.makeMenuButton(act,
                "Profil Ringan Hemat Baterai & RAM",
                "Tema AMOLED hitam pekat, matikan popup & efek suara berat, cocok untuk sesi panjang",
                UiKit.ACCENT_OFFICE, prefs, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        prefs.edit()
                                .putString("keyboard_theme", "Amoled")
                                .putBoolean("popup_on_keypress", false)
                                .putBoolean("sound_ketik_enabled", false)
                                .putBoolean("sound_nempel_enabled", false)
                                .putString("qwerty_tts_echo", "off")
                                .apply();
                        Toast.makeText(act, "Profil Hemat Daya & RAM diterapkan!", Toast.LENGTH_LONG).show();
                        if (onApplied != null) onApplied.run();
                    }
                }));

        scroll.addView(root);
        builder.setView(scroll);
        builder.show();
    }

    // =========================================================================
    // 6. STATISTIK PENGETIKAN LOKAL (PRIVASI TERJAGA)
    // =========================================================================
    public static void showStatsDialog(final Activity act, final SharedPreferences prefs) {
        long chars = prefs.getLong("stat_chars_typed", 0L);
        long words = prefs.getLong("stat_words_typed", 0L);
        long macros = prefs.getLong("stat_macros_used", 0L);
        long ai = prefs.getLong("stat_ai_requests", 0L);

        AlertDialog.Builder b = UiKit.dialog(act, prefs);
        b.setTitle("Statistik Pengetikan Lokal");
        String msg = "Ringkasan aktivitas di perangkat ini:\n\n"
                + "• Total karakter diketik : " + chars + "\n"
                + "• Total kata tersusun     : " + words + "\n"
                + "• Ekspansi makro dipakai  : " + macros + " kali\n"
                + "• Bantuan Asisten AI      : " + ai + " kali\n\n"
                + "Catatan Privasi: Angka ini dihitung murni secara lokal di HP Anda. Kolom kata sandi & PIN TIDAK pernah dihitung atau disimpan.";
        b.setMessage(msg);
        b.setNegativeButton("Reset Angka", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                prefs.edit()
                        .putLong("stat_chars_typed", 0L)
                        .putLong("stat_words_typed", 0L)
                        .putLong("stat_macros_used", 0L)
                        .putLong("stat_ai_requests", 0L)
                        .apply();
                Toast.makeText(act, "Statistik direset ke 0", Toast.LENGTH_SHORT).show();
            }
        });
        b.show();
    }

    // =========================================================================
    // HELPER PANGGILAN AI TUNGGAL
    // =========================================================================
    private interface AiCallback {
        void onResult(String text);
        void onError(String err);
    }

    private static void runSingleAiPrompt(final Activity act, final SharedPreferences prefs,
                                          final String systemPrompt, final String userText, final AiCallback cb) {
        AiEngine.singlePrompt(act, prefs, systemPrompt, userText, new AiEngine.TextCallback() {
            @Override public void onSuccess(String text) { cb.onResult(text); }
            @Override public void onError(String err) { cb.onError(err); }
        });
    }
}
