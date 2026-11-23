package com.annas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
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
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import android.app.DatePickerDialog;
import java.text.NumberFormat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Random;

/**
 * Fitur Tambahan Lengkap & Produktivitas Komunitas (v23.3):
 * 1. Bank Quote & Kata Mutiara (12.500+ Quote terkurasi dalam 10 Kategori + Quote Kustom, Pencarian & Halaman)
 * 2. Brankas Identitas Cepat (Auto-Isi Nama, Alamat, HP, Email, Rekening dari Keyboard)
 * 3. Alat Pengolah Teks Offline (Kapital, Terbilang Rupiah, Urut/Unik Baris, Sandi Morse/Base64)
 * 4. Konverter Satuan & Kalkulator Umur / Selisih Hari + Kalender Hijriah
 * 5. Catatan Cepat Harian (Quick Notes) & Timer Fokus Suara (Pomodoro)
 * 6. Generator Kata Sandi Aman
 */
public final class ExtraToolsUI {

    private ExtraToolsUI() {}

    public static final int BUILTIN_CATEGORY_COUNT = 15;
    public static final int CUSTOM_QUOTE_CATEGORY_INDEX = 15;

    public static final String[] QUOTE_CATEGORIES = {
        "Motivasi Hidup & Sukses",
        "Islami, Syukur & Doa",
        "Kerja, Karier & Rezeki",
        "Stoik & Bijaksana",
        "Sahabat & Tongkrongan",
        "Cinta, Rindu & Kesetiaan",
        "Keluarga & Bakti Orang Tua",
        "Ketenangan Hati & Mental",
        "Ilmu, Belajar & Karya",
        "English Wisdom & Quotes",
        "Coding, Bug Error & Developer",
        "Sabar, Ujian & Kekuatan Diri",
        "Pagi Produktif & Energi Baru",
        "Kepemimpinan & Amanah",
        "Inklusi, Aksesibilitas & Karya",
        "Quote Kustom Saya"
    };

    private static ArrayList<ArrayList<String>> cachedQuoteCategories = null;
    private static final Object QUOTES_LOCK = new Object();

    private static void ensureQuotesLoaded(Context ctx) {
        if (cachedQuoteCategories != null || ctx == null) return;
        synchronized (QUOTES_LOCK) {
            if (cachedQuoteCategories != null) return;
            ArrayList<ArrayList<String>> buckets = new ArrayList<>(BUILTIN_CATEGORY_COUNT);
            for (int i = 0; i < BUILTIN_CATEGORY_COUNT; i++) buckets.add(new ArrayList<String>());
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    ctx.getAssets().open("quotes_bank.txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    int sep = line.indexOf('|');
                    if (sep <= 0 || sep >= line.length() - 1) continue;
                    try {
                        int cat = Integer.parseInt(line.substring(0, sep));
                        if (cat >= 0 && cat < BUILTIN_CATEGORY_COUNT) {
                            buckets.get(cat).add(line.substring(sep + 1));
                        }
                    } catch (NumberFormatException ignored) {}
                }
            } catch (Throwable ignored) {}
            cachedQuoteCategories = buckets;
        }
    }

    public static ArrayList<String> getQuotesForCategory(Context ctx, SharedPreferences prefs, int catIdx) {
        if (catIdx >= 0 && catIdx < BUILTIN_CATEGORY_COUNT) {
            ensureQuotesLoaded(ctx);
            if (cachedQuoteCategories != null && catIdx < cachedQuoteCategories.size()) {
                return cachedQuoteCategories.get(catIdx);
            }
            return new ArrayList<>();
        }
        ArrayList<String> custom = new ArrayList<>();
        try {
            String raw = prefs != null ? prefs.getString("json_custom_quotes", "[]") : "[]";
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String q = arr.optString(i, "").trim();
                if (!q.isEmpty()) custom.add(q);
            }
        } catch (Throwable ignored) {}
        return custom;
    }

    public static String getRandomQuote(Context ctx, SharedPreferences prefs) {
        ensureQuotesLoaded(ctx);
        ArrayList<String> custom = getQuotesForCategory(ctx, prefs, CUSTOM_QUOTE_CATEGORY_INDEX);
        int total = custom.size();
        if (cachedQuoteCategories != null) {
            for (ArrayList<String> b : cachedQuoteCategories) total += b.size();
        }
        if (total <= 0) return "Tetap semangat berkarya hari ini!";
        int pick = new Random().nextInt(total);
        if (cachedQuoteCategories != null) {
            for (ArrayList<String> b : cachedQuoteCategories) {
                if (pick < b.size()) return b.get(pick);
                pick -= b.size();
            }
        }
        if (pick < custom.size()) return custom.get(pick);
        return "Tetap semangat berkarya hari ini!";
    }

    public static String getRandomQuoteFromCategory(Context ctx, SharedPreferences prefs, int catIdx) {
        ArrayList<String> list = getQuotesForCategory(ctx, prefs, catIdx);
        if (list.isEmpty()) return getRandomQuote(ctx, prefs);
        return list.get(new Random().nextInt(list.size()));
    }

    public static int getTotalBuiltinQuotesCount(Context ctx) {
        ensureQuotesLoaded(ctx);
        int total = 0;
        if (cachedQuoteCategories != null) {
            for (ArrayList<String> b : cachedQuoteCategories) total += b.size();
        }
        return total;
    }

    // =========================================================================
    // 1. KOLEKSI 12.500+ QUOTE & KATA MUTIARA (DIBAGI PER KATEGORI & HALAMAN)
    // =========================================================================
    public static void showQuotesDialog(final Activity act, final SharedPreferences prefs) {
        ensureQuotesLoaded(act);
        LinearLayout root = makeBaseLayout(act, prefs);

        int totalQuotes = getTotalBuiltinQuotesCount(act);
        TextView desc = makeMutedLabel(act, prefs, "Tersedia " + totalQuotes + " kata mutiara terbagi ke 10 kategori + Quote Kustom. Semua juga bisa langsung disisipkan dari tombol ⚡ Alat Cepat di keyboard.");
        root.addView(desc);

        String[] spinnerLabels = new String[QUOTE_CATEGORIES.length];
        for (int i = 0; i < QUOTE_CATEGORIES.length; i++) {
            int cnt = getQuotesForCategory(act, prefs, i).size();
            spinnerLabels[i] = QUOTE_CATEGORIES[i] + " (" + cnt + ")";
        }

        final Spinner catSpin = new Spinner(act);
        catSpin.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, spinnerLabels));
        catSpin.setContentDescription("Pilih kategori kata mutiara");
        root.addView(catSpin);

        LinearLayout searchRow = new LinearLayout(act);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(0, UiKit.dp(act, 6), 0, UiKit.dp(act, 4));
        final EditText etSearch = new EditText(act);
        etSearch.setHint("Cari kata kunci (mis. sabar, kerja, doa)...");
        etSearch.setSingleLine(true);
        etSearch.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button btnSearch = UiKit.makePillButton(act, "Cari", UiKit.ACCENT_KB, Color.WHITE, null);
        searchRow.addView(etSearch);
        searchRow.addView(btnSearch);
        root.addView(searchRow);

        LinearLayout topBtns = new LinearLayout(act);
        topBtns.setOrientation(LinearLayout.HORIZONTAL);
        topBtns.setPadding(0, UiKit.dp(act, 6), 0, UiKit.dp(act, 6));
        Button btnRand = UiKit.makePillButton(act, "Quote Acak", UiKit.ACCENT_AI, Color.WHITE, null);
        Button btnAdd = UiKit.makePillButton(act, "Buat Quote Saya", UiKit.ACCENT_OFFICE, Color.WHITE, null);
        topBtns.addView(btnRand);
        topBtns.addView(new View(act), new LinearLayout.LayoutParams(UiKit.dp(act, 8), 1));
        topBtns.addView(btnAdd);
        root.addView(topBtns);

        final TextView pageInfo = makeMutedLabel(act, prefs, "");
        root.addView(pageInfo);

        LinearLayout navRow = new LinearLayout(act);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setPadding(0, UiKit.dp(act, 4), 0, UiKit.dp(act, 8));
        final Button btnPrev = UiKit.makePillButton(act, "Halaman Sebelumnya", UiKit.ACCENT_KB_ALT, Color.WHITE, null);
        final Button btnJump = UiKit.makePillButton(act, "Lompat Halaman", UiKit.ACCENT_WARM, Color.WHITE, null);
        final Button btnNext = UiKit.makePillButton(act, "Halaman Berikutnya", UiKit.ACCENT_KB, Color.WHITE, null);
        navRow.addView(btnPrev);
        navRow.addView(new View(act), new LinearLayout.LayoutParams(UiKit.dp(act, 6), 1));
        navRow.addView(btnJump);
        navRow.addView(new View(act), new LinearLayout.LayoutParams(UiKit.dp(act, 6), 1));
        navRow.addView(btnNext);
        root.addView(navRow);

        final LinearLayout listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        final int pageSize = 15;
        final int[] curPage = {0};
        final int[] totalPagesRef = {1};
        final Runnable[] refresh = new Runnable[1];

        refresh[0] = () -> {
            listBox.removeAllViews();
            int idx = catSpin.getSelectedItemPosition();
            ArrayList<String> rawQuotes = getQuotesForCategory(act, prefs, idx);
            String qFilter = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
            ArrayList<String> filtered = new ArrayList<>();
            if (qFilter.isEmpty()) {
                filtered = rawQuotes;
            } else {
                for (String s : rawQuotes) {
                    if (s.toLowerCase(Locale.ROOT).contains(qFilter)) filtered.add(s);
                }
            }

            if (filtered.isEmpty()) {
                totalPagesRef[0] = 1;
                curPage[0] = 0;
                pageInfo.setText("Halaman 1 / 1 • 0 kata mutiara");
                btnPrev.setEnabled(false);
                btnNext.setEnabled(false);
                listBox.addView(makeMutedLabel(act, prefs, idx == CUSTOM_QUOTE_CATEGORY_INDEX
                        ? "Belum ada Quote Kustom. Tekan '+ Buat Quote Saya' untuk menambah."
                        : "Tidak ada quote yang cocok dengan pencarian."));
                return;
            }

            int totalPages = Math.max(1, (filtered.size() + pageSize - 1) / pageSize);
            totalPagesRef[0] = totalPages;
            if (curPage[0] >= totalPages) curPage[0] = totalPages - 1;
            if (curPage[0] < 0) curPage[0] = 0;

            int from = curPage[0] * pageSize;
            int to = Math.min(filtered.size(), from + pageSize);
            pageInfo.setText("Halaman " + (curPage[0] + 1) + " dari " + totalPages + " • Menampilkan " + (from + 1) + "-" + to + " dari " + filtered.size());
            btnPrev.setEnabled(curPage[0] > 0);
            btnNext.setEnabled(curPage[0] + 1 < totalPages);

            for (int i = from; i < to; i++) {
                final int qIdx = i;
                final String q = filtered.get(i);
                Button b = UiKit.makeMenuButton(act, (i + 1) + ". \"" + q + "\"",
                        "Ketuk untuk salin, bacakan suara, atau bagikan ke WhatsApp",
                        UiKit.surface(prefs), prefs,
                        v -> showQuoteActionDialog(act, prefs, q, idx == CUSTOM_QUOTE_CATEGORY_INDEX ? qIdx : -1, refresh[0]));
                b.setTextColor(UiKit.textPrimary(prefs));
                b.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 12), UiKit.dp(act, 1)));
                listBox.addView(b);
            }
        };

        catSpin.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                curPage[0] = 0;
                refresh[0].run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        btnSearch.setOnClickListener(v -> { curPage[0] = 0; refresh[0].run(); });
        btnPrev.setOnClickListener(v -> { if (curPage[0] > 0) { curPage[0]--; refresh[0].run(); } });
        btnNext.setOnClickListener(v -> { if (curPage[0] + 1 < totalPagesRef[0]) { curPage[0]++; refresh[0].run(); } });
        btnJump.setOnClickListener(v -> {
            final EditText pgIn = new EditText(act);
            pgIn.setInputType(InputType.TYPE_CLASS_NUMBER);
            pgIn.setHint("Masukkan nomor halaman (1 - " + totalPagesRef[0] + ")");
            UiKit.dialog(act, prefs).setTitle("Lompat ke Halaman").setView(pgIn)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Buka", (d, w) -> {
                    try {
                        int p = Integer.parseInt(pgIn.getText().toString().trim()) - 1;
                        curPage[0] = Math.max(0, Math.min(totalPagesRef[0] - 1, p));
                        refresh[0].run();
                    } catch (Throwable ignored) {}
                }).show();
        });

        btnRand.setOnClickListener(v -> showQuoteActionDialog(act, prefs,
                getRandomQuoteFromCategory(act, prefs, catSpin.getSelectedItemPosition()), -1, refresh[0]));
        btnAdd.setOnClickListener(v -> {
            final EditText in = new EditText(act);
            in.setHint("Tulis quote atau kata mutiara buatan lu...");
            in.setMinLines(3);
            UiKit.dialog(act, prefs).setTitle("Tambah Quote Kustom").setView(in)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (d, w) -> {
                    String txt = in.getText().toString().trim();
                    if (txt.isEmpty()) return;
                    try {
                        JSONArray arr = new JSONArray(prefs.getString("json_custom_quotes", "[]"));
                        arr.put(txt);
                        prefs.edit().putString("json_custom_quotes", arr.toString()).apply();
                        catSpin.setSelection(CUSTOM_QUOTE_CATEGORY_INDEX);
                        curPage[0] = 0;
                        refresh[0].run();
                        Toast.makeText(act, "Quote ditambahkan & siap dipakai di keyboard!", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                }).show();
        });

        refresh[0].run();
        showDialogScroll(act, "Quote & Kata Mutiara (" + totalQuotes + "+)", root);
    }

    private static void showQuoteActionDialog(final Activity act, final SharedPreferences prefs,
                                              final String quote, final int customDeleteIdx, final Runnable onDeleted) {
        LinearLayout box = makeBaseLayout(act, prefs);
        TextView preview = new TextView(act);
        preview.setText("\"" + quote + "\"");
        preview.setTextColor(UiKit.textPrimary(prefs));
        preview.setTextSize(15);
        preview.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 8), UiKit.dp(act, 10), UiKit.dp(act, 12));
        preview.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 10), UiKit.dp(act, 1)));
        box.addView(preview);
        box.addView(new View(act), new LinearLayout.LayoutParams(1, UiKit.dp(act, 10)));

        final AlertDialog[] dlgRef = new AlertDialog[1];

        box.addView(UiKit.makeMenuButton(act, "Bagikan ke WhatsApp / TikTok / IG / Lainnya",
                "Kirim langsung sebagai teks pesan lewat menu berbagi Android",
                UiKit.ACCENT_OFFICE, prefs, v -> {
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    sharePlainText(act, quote, "Bagikan Kata Mutiara");
                }));

        box.addView(UiKit.makeMenuButton(act, "Salin ke Papan Klip",
                "Simpan teks kutipan ini agar siap ditempel",
                UiKit.ACCENT_KB, prefs, v -> {
                    copyText(act, quote, "Quote disalin!");
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                }));

        box.addView(UiKit.makeMenuButton(act, "Bacakan dengan Suara (TTS)",
                "Putar suara pembaca untuk mendengarkan kutipan",
                UiKit.ACCENT_AI, prefs, v -> speakOnce(act, quote)));

        if (customDeleteIdx >= 0) {
            box.addView(UiKit.makeMenuButton(act, "Hapus Quote Kustom Ini",
                    "Hapus kutipan buatan sendiri ini",
                    Color.parseColor("#DC2626"), prefs, v -> {
                        try {
                            JSONArray arr = new JSONArray(prefs.getString("json_custom_quotes", "[]"));
                            JSONArray next = new JSONArray();
                            for (int i = 0; i < arr.length(); i++) if (i != customDeleteIdx) next.put(arr.get(i));
                            prefs.edit().putString("json_custom_quotes", next.toString()).apply();
                            if (dlgRef[0] != null) dlgRef[0].dismiss();
                            if (onDeleted != null) onDeleted.run();
                        } catch (Throwable ignored) {}
                    }));
        }

        ScrollView sc = new ScrollView(act);
        sc.addView(box);
        dlgRef[0] = UiKit.dialog(act, prefs)
                .setTitle("Aksi Kata Mutiara")
                .setView(sc)
                .create();
        dlgRef[0].show();
    }

    // =========================================================================
    // 2. BRANKAS IDENTITAS & DATA CEPAT (TERHUBUNG KE KEYBOARD ⚡)
    // =========================================================================
    public static void showQuickIdentityVaultDialog(final Activity act, final SharedPreferences prefs) {
        LinearLayout root = makeBaseLayout(act, prefs);
        root.addView(makeMutedLabel(act, prefs, "Simpan data pribadi di sini agar bisa disisipkan 1 ketukan saat mengisi formulir atau chat lewat tombol ⚡ Alat Cepat -> Tab Identitas."));

        final String[] keys = {"id_nama", "id_hp", "id_email", "id_alamat", "id_rekening", "id_catatan"};
        final String[] hints = {"Nama Lengkap", "Nomor HP / WhatsApp", "Alamat Email", "Alamat Lengkap Rumah/Kirim", "No. Rekening / E-Wallet (Dana/OVO/BCA)", "Data Cepat Lain (NIM/NIP/Link)"};
        final EditText[] inputs = new EditText[keys.length];

        for (int i = 0; i < keys.length; i++) {
            TextView lbl = new TextView(act);
            lbl.setText(hints[i]);
            lbl.setTextColor(UiKit.textPrimary(prefs));
            lbl.setTextSize(13);
            lbl.setTypeface(null, Typeface.BOLD);
            lbl.setPadding(0, UiKit.dp(act, 6), 0, UiKit.dp(act, 2));
            root.addView(lbl);

            EditText ed = new EditText(act);
            ed.setHint("Isi " + hints[i]);
            ed.setText(prefs.getString(keys[i], ""));
            ed.setTextColor(UiKit.textPrimary(prefs));
            ed.setHintTextColor(UiKit.textMuted(prefs));
            ed.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 10), UiKit.dp(act, 1)));
            ed.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 8), UiKit.dp(act, 12), UiKit.dp(act, 8));
            root.addView(ed);
            inputs[i] = ed;
        }

        ScrollView sc = new ScrollView(act);
        sc.setBackgroundColor(UiKit.bg(prefs));
        sc.addView(root);

        UiKit.dialog(act, prefs).setTitle("Brankas Identitas & Data Cepat").setView(sc)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan Semua", (d, w) -> {
                SharedPreferences.Editor ed = prefs.edit();
                for (int i = 0; i < keys.length; i++) {
                    ed.putString(keys[i], inputs[i].getText().toString().trim());
                }
                ed.apply();
                Toast.makeText(act, "Data Identitas Cepat disimpan! Siap pakai dari tombol ⚡ di keyboard.", Toast.LENGTH_LONG).show();
            }).show();
    }

    // =========================================================================
    // 3. ALAT PENGOLAH TEKS OFFLINE + TERBILANG RUPIAH + SANDI MORSE
    // =========================================================================
    public static void showTextToolkitDialog(final Activity act, final SharedPreferences prefs) {
        LinearLayout root = makeBaseLayout(act, prefs);
        root.addView(makeMutedLabel(act, prefs, "Olah teks seketika 100% offline: ubah huruf besar/kecil, rapikan baris, terbilang nominal Rupiah, hingga sandi Morse & Base64."));

        final EditText input = new EditText(act);
        input.setHint("Tempel atau tulis teks / nominal angka (mis. 150000) di sini...");
        input.setMinLines(4);
        input.setTextColor(UiKit.textPrimary(prefs));
        input.setHintTextColor(UiKit.textMuted(prefs));
        input.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 10), UiKit.dp(act, 1)));
        input.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 10), UiKit.dp(act, 10), UiKit.dp(act, 10));
        root.addView(input);

        LinearLayout ioRow = new LinearLayout(act);
        ioRow.setOrientation(LinearLayout.HORIZONTAL);
        ioRow.setPadding(0, UiKit.dp(act, 8), 0, UiKit.dp(act, 8));
        ioRow.addView(UiKit.makePillButton(act, "Tempel Teks", UiKit.ACCENT_SYSTEM, Color.WHITE, v -> {
            String c = readClipboard(act);
            if (!c.isEmpty()) input.setText(c);
        }));
        ioRow.addView(new View(act), new LinearLayout.LayoutParams(UiKit.dp(act, 6), 1));
        ioRow.addView(UiKit.makePillButton(act, "Salin Hasil", UiKit.ACCENT_OFFICE, Color.WHITE, v -> copyText(act, input.getText().toString(), "Hasil disalin!")));
        ioRow.addView(new View(act), new LinearLayout.LayoutParams(UiKit.dp(act, 6), 1));
        ioRow.addView(UiKit.makePillButton(act, "Bacakan Suara", UiKit.ACCENT_AI, Color.WHITE, v -> speakOnce(act, input.getText().toString())));
        root.addView(ioRow);

        final TextView statTv = makeMutedLabel(act, prefs, "Statistik muncul setelah memproses teks.");
        root.addView(statTv);

        String[] ops = {
            "HURUF BESAR SEMUA",
            "huruf kecil semua",
            "Huruf Awal Kata Kapital (Title Case)",
            "Terbilang Angka Rupiah (mis. 250000 -> dua ratus...)",
            "Rapikan Spasi & Hapus Baris Kosong",
            "Urutkan Baris A → Z",
            "Hapus Baris Duplikat",
            "Balik Teks (Reverse)",
            "Konversi ke Sandi Morse",
            "Encode / Decode Base64"
        };
        for (int i = 0; i < ops.length; i++) {
            final int op = i;
            Button b = UiKit.makeMenuButton(act, ops[i], null, UiKit.surfaceAlt(prefs), prefs, v -> {
                String src = input.getText().toString();
                String res = applyTextOperation(src, op);
                input.setText(res);
                int words = res.trim().isEmpty() ? 0 : res.trim().split("\\s+").length;
                statTv.setText("Kata: " + words + " • Karakter: " + res.length() + " • Baris: " + res.split("\n", -1).length);
            });
            b.setTextColor(UiKit.textPrimary(prefs));
            b.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 10), UiKit.dp(act, 1)));
            root.addView(b);
        }

        showDialogScroll(act, "Alat Pengolah Teks & Terbilang", root);
    }

    public static String applyTextOperation(String src, int op) {
        if (src == null) src = "";
        switch (op) {
            case 0: return src.toUpperCase(new Locale("id", "ID"));
            case 1: return src.toLowerCase(new Locale("id", "ID"));
            case 2: return toTitleCase(src);
            case 3: {
                String digits = src.replaceAll("[^0-9]", "");
                if (digits.isEmpty()) return "Masukkan angka (contoh: 150000)";
                try {
                    long val = Long.parseLong(digits);
                    return numberToIndonesianWords(val).trim() + " rupiah";
                } catch (Exception e) { return "Angka terlalu besar (maks 12 digit)"; }
            }
            case 4: return src.replaceAll("[ \\t]{2,}", " ").replaceAll("(?m)^[ \\t]*\\r?\\n", "").trim();
            case 5: {
                String[] lines = src.split("\n");
                Arrays.sort(lines, String.CASE_INSENSITIVE_ORDER);
                return joinLines(lines);
            }
            case 6: {
                LinkedHashSet<String> set = new LinkedHashSet<>(Arrays.asList(src.split("\n")));
                return joinLines(set.toArray(new String[0]));
            }
            case 7: return new StringBuilder(src).reverse().toString();
            case 8: return toMorse(src);
            case 9: {
                try {
                    if (src.matches("^[A-Za-z0-9+/=\\r\\n]+$") && src.trim().length() % 4 == 0 && src.trim().length() >= 4) {
                        return new String(Base64.decode(src.trim(), Base64.DEFAULT), StandardCharsets.UTF_8);
                    }
                } catch (Throwable ignored) {}
                return Base64.encodeToString(src.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            }
            default: return src;
        }
    }

    public static String toTitleCase(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        boolean capNext = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                out.append(c);
                capNext = true;
            } else if (capNext) {
                out.append(Character.toUpperCase(c));
                capNext = false;
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    private static String joinLines(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private static String numberToIndonesianWords(long n) {
        if (n == 0) return "nol";
        String[] units = {"", "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "delapan", "sembilan", "sepuluh", "sebelas"};
        if (n < 12) return units[(int) n];
        if (n < 20) return units[(int) (n - 10)] + " belas";
        if (n < 100) return units[(int) (n / 10)] + " puluh" + (n % 10 > 0 ? " " + numberToIndonesianWords(n % 10) : "");
        if (n < 200) return "seratus" + (n % 100 > 0 ? " " + numberToIndonesianWords(n % 100) : "");
        if (n < 1000) return units[(int) (n / 100)] + " ratus" + (n % 100 > 0 ? " " + numberToIndonesianWords(n % 100) : "");
        if (n < 2000) return "seribu" + (n % 1000 > 0 ? " " + numberToIndonesianWords(n % 1000) : "");
        if (n < 1_000_000) return numberToIndonesianWords(n / 1000) + " ribu" + (n % 1000 > 0 ? " " + numberToIndonesianWords(n % 1000) : "");
        if (n < 1_000_000_000L) return numberToIndonesianWords(n / 1_000_000) + " juta" + (n % 1_000_000 > 0 ? " " + numberToIndonesianWords(n % 1_000_000) : "");
        if (n < 1_000_000_000_000L) return numberToIndonesianWords(n / 1_000_000_000L) + " miliar" + (n % 1_000_000_000L > 0 ? " " + numberToIndonesianWords(n % 1_000_000_000L) : "");
        return String.valueOf(n);
    }

    private static String toMorse(String s) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        String[] code = {".-","-...","-.-.","-..",".","..-.","--.","....","..",".---","-.-",".-..","--","-.","---",".--.","--.-",".-.","...","-","..-","...-",".--","-..-","-.--","--..","-----",".----","..---","...--","....-",".....","-....","--...","---..","----."};
        String u = s.toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c == ' ') { sb.append(" / "); continue; }
            int idx = chars.indexOf(c);
            if (idx >= 0) sb.append(code[idx]).append(' ');
        }
        return sb.toString().trim();
    }

    // =========================================================================
    // 4. KONVERTER SATUAN, HITUNG UMUR & KALENDER HIJRIAH
    // =========================================================================
    public static void showUnitAndDateToolsDialog(final Activity act, final SharedPreferences prefs) {
        LinearLayout root = makeBaseLayout(act, prefs);

        // Info Hari Ini + Hijriah
        String masehi = new SimpleDateFormat("EEEE, dd MMMM yyyy • HH:mm", new Locale("id", "ID")).format(new Date());
        String hijriStr = getTodayHijriDate();
        TextView dateCard = new TextView(act);
        dateCard.setText("Hari Ini: " + masehi + "\nPerkiraan Hijriah: " + hijriStr);
        dateCard.setTextColor(UiKit.textPrimary(prefs));
        dateCard.setTextSize(14);
        dateCard.setTypeface(null, Typeface.BOLD);
        dateCard.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 12), UiKit.dp(act, 12), UiKit.dp(act, 12));
        dateCard.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 12), UiKit.dp(act, 1)));
        root.addView(dateCard);

        // Konverter Satuan Cepat
        TextView uTitle = makeMutedLabel(act, prefs, "\nKonverter Satuan Cepat:");
        uTitle.setTypeface(null, Typeface.BOLD);
        root.addView(uTitle);

        final EditText numIn = new EditText(act);
        numIn.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        numIn.setHint("Masukkan angka (contoh: 100)");
        numIn.setTextColor(UiKit.textPrimary(prefs));
        numIn.setHintTextColor(UiKit.textMuted(prefs));
        root.addView(numIn);

        final Spinner unitSpin = new Spinner(act);
        String[] convs = {
            "Celcius (°C) → Fahrenheit (°F)",
            "Fahrenheit (°F) → Celcius (°C)",
            "Kilometer (km) → Meter (m) & Mil",
            "Meter (m) → Centimeter (cm) & Inci",
            "Kilogram (kg) → Gram & Ons",
            "Gigabyte (GB) → Megabyte (MB)"
        };
        unitSpin.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, convs));
        root.addView(unitSpin);

        final TextView unitOut = makeMutedLabel(act, prefs, "Hasil konversi akan muncul di sini.");
        unitOut.setTextColor(UiKit.textPrimary(prefs));
        unitOut.setTextSize(15);

        root.addView(UiKit.makeMenuButton(act, "Hitung Konversi", null, UiKit.ACCENT_KB, prefs, v -> {
            try {
                double val = Double.parseDouble(numIn.getText().toString().trim());
                int p = unitSpin.getSelectedItemPosition();
                String res;
                if (p == 0) res = String.format(Locale.ROOT, "%.2f °C = %.2f °F", val, (val * 9.0 / 5.0) + 32.0);
                else if (p == 1) res = String.format(Locale.ROOT, "%.2f °F = %.2f °C", val, (val - 32.0) * 5.0 / 9.0);
                else if (p == 2) res = String.format(Locale.ROOT, "%.2f km = %.0f m = %.2f mil", val, val * 1000, val * 0.621371);
                else if (p == 3) res = String.format(Locale.ROOT, "%.2f m = %.1f cm = %.2f inci", val, val * 100, val * 39.3701);
                else if (p == 4) res = String.format(Locale.ROOT, "%.2f kg = %.0f gram = %.1f ons", val, val * 1000, val * 10);
                else res = String.format(Locale.ROOT, "%.2f GB = %.0f MB", val, val * 1024);
                unitOut.setText("✅ " + res);
            } catch (Exception e) {
                unitOut.setText("Masukkan angka yang valid dahulu.");
            }
        }));
        root.addView(unitOut);

        // Kalkulator Umur / Selisih Hari
        TextView dTitle = makeMutedLabel(act, prefs, "\n🎂 Hitung Umur atau Selisih Hari ke Tanggal (DD-MM-YYYY):");
        dTitle.setTypeface(null, Typeface.BOLD);
        root.addView(dTitle);

        final EditText dIn = new EditText(act);
        dIn.setHint("Contoh: 17-08-2006 atau 01-01-2027");
        dIn.setTextColor(UiKit.textPrimary(prefs));
        dIn.setHintTextColor(UiKit.textMuted(prefs));
        root.addView(dIn);

        final TextView dOut = makeMutedLabel(act, prefs, "Hasil umur / selisih hari.");
        dOut.setTextColor(UiKit.textPrimary(prefs));
        root.addView(UiKit.makeMenuButton(act, "Hitung Selisih Tanggal", null, UiKit.ACCENT_AI, prefs, v -> {
            try {
                Date target = new SimpleDateFormat("dd-MM-yyyy", Locale.ROOT).parse(dIn.getText().toString().trim());
                if (target == null) throw new IllegalArgumentException();
                long diffMs = System.currentTimeMillis() - target.getTime();
                long days = Math.abs(diffMs) / (1000L * 60 * 60 * 24);
                long yrs = days / 365;
                long remMonths = (days % 365) / 30;
                long remDays = (days % 365) % 30;
                if (diffMs >= 0) {
                    dOut.setText("🎉 Sudah berlalu " + days + " hari (± " + yrs + " tahun " + remMonths + " bulan " + remDays + " hari).");
                } else {
                    dOut.setText("⏳ Tersisa " + days + " hari lagi menuju tanggal tersebut.");
                }
            } catch (Exception e) {
                dOut.setText("Format harus DD-MM-YYYY (contoh: 17-08-2006).");
            }
        }));
        root.addView(dOut);

        showDialogScroll(act, "Konverter, Umur & Kalender", root);
    }

    public static String getTodayHijriDate() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                android.icu.util.IslamicCalendar ic = new android.icu.util.IslamicCalendar();
                String[] bulan = {"Muharram", "Safar", "Rabiul Awal", "Rabiul Akhir", "Jumadil Awal", "Jumadil Akhir", "Rajab", "Syaban", "Ramadan", "Syawal", "Zulkaidah", "Zulhijjah"};
                int m = ic.get(android.icu.util.Calendar.MONTH);
                int day = ic.get(android.icu.util.Calendar.DAY_OF_MONTH);
                int yr = ic.get(android.icu.util.Calendar.YEAR);
                return day + " " + bulan[Math.max(0, Math.min(11, m))] + " " + yr + " H";
            }
        } catch (Throwable ignored) {}
        return "(Perlu Android 7+ untuk kalender Hijriah)";
    }

    // =========================================================================
    // 5. CATATAN CEPAT HARIAN & TIMER FOKUS SUARA + GENERATOR PASSWORD
    // =========================================================================
    public static void showQuickNotesAndTimerDialog(final Activity act, final SharedPreferences prefs) {
        LinearLayout root = makeBaseLayout(act, prefs);

        // Bagian Timer Fokus
        TextView tmLbl = makeMutedLabel(act, prefs, "Timer Fokus / Pengingat Suara (Pomodoro):");
        tmLbl.setTypeface(null, Typeface.BOLD);
        root.addView(tmLbl);

        final TextView timerDisplay = new TextView(act);
        timerDisplay.setText("Belum ada timer berjalan");
        timerDisplay.setTextColor(UiKit.textPrimary(prefs));
        timerDisplay.setTextSize(16);
        timerDisplay.setTypeface(null, Typeface.BOLD);
        timerDisplay.setPadding(0, UiKit.dp(act, 4), 0, UiKit.dp(act, 6));
        root.addView(timerDisplay);

        LinearLayout tmRow = new LinearLayout(act);
        tmRow.setOrientation(LinearLayout.HORIZONTAL);
        final CountDownTimer[] activeTimer = new CountDownTimer[1];
        int[] mins = {5, 15, 25, 45};
        for (final int m : mins) {
            Button b = UiKit.makePillButton(act, m + " Menit", UiKit.ACCENT_WARM, Color.WHITE, v -> {
                if (activeTimer[0] != null) activeTimer[0].cancel();
                activeTimer[0] = new CountDownTimer(m * 60L * 1000L, 1000L) {
                    @Override public void onTick(long ms) {
                        long s = ms / 1000;
                        timerDisplay.setText(String.format(Locale.ROOT, "Sisa waktu: %02d:%02d", s / 60, s % 60));
                    }
                    @Override public void onFinish() {
                        timerDisplay.setText("Waktu fokus " + m + " menit selesai!");
                        speakOnce(act, "Waktu fokus " + m + " menit telah selesai!");
                    }
                }.start();
            });
            tmRow.addView(b);
            tmRow.addView(new View(act), new LinearLayout.LayoutParams(UiKit.dp(act, 6), 1));
        }
        root.addView(tmRow);

        // Bagian Catatan Cepat
        TextView noteLbl = makeMutedLabel(act, prefs, "\nCatatan Cepat Harian:");
        noteLbl.setTypeface(null, Typeface.BOLD);
        root.addView(noteLbl);

        final EditText noteIn = new EditText(act);
        noteIn.setHint("Tulis pengingat, ide, atau daftar belanjaan...");
        noteIn.setMinLines(2);
        noteIn.setTextColor(UiKit.textPrimary(prefs));
        noteIn.setHintTextColor(UiKit.textMuted(prefs));
        noteIn.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 10), UiKit.dp(act, 1)));
        noteIn.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 8), UiKit.dp(act, 10), UiKit.dp(act, 8));
        root.addView(noteIn);

        final LinearLayout notesList = new LinearLayout(act);
        notesList.setOrientation(LinearLayout.VERTICAL);

        final Runnable[] reloadNotes = new Runnable[1];
        reloadNotes[0] = () -> {
            notesList.removeAllViews();
            try {
                JSONArray arr = new JSONArray(prefs.getString("json_quick_notes", "[]"));
                if (arr.length() == 0) {
                    notesList.addView(makeMutedLabel(act, prefs, "Belum ada catatan tersimpan."));
                }
                for (int i = arr.length() - 1; i >= 0; i--) {
                    final int idx = i;
                    final String item = arr.optString(i, "");
                    Button nb = UiKit.makeMenuButton(act, "Catatan: " + item, "Ketuk untuk Salin, Bacakan Suara, atau Hapus",
                            UiKit.surface(prefs), prefs, v -> UiKit.dialog(act, prefs).setTitle("Catatan")
                                .setMessage(item)
                                .setPositiveButton("Salin", (dd, w) -> copyText(act, item, "Catatan disalin!"))
                                .setNeutralButton("Bacakan TTS", (dd, w) -> speakOnce(act, item))
                                .setNegativeButton("Hapus", (dd, w) -> {
                                    try {
                                        JSONArray a = new JSONArray(prefs.getString("json_quick_notes", "[]"));
                                        JSONArray n = new JSONArray();
                                        for (int k = 0; k < a.length(); k++) if (k != idx) n.put(a.get(k));
                                        prefs.edit().putString("json_quick_notes", n.toString()).apply();
                                        reloadNotes[0].run();
                                    } catch (Throwable ignored) {}
                                }).show());
                    nb.setTextColor(UiKit.textPrimary(prefs));
                    nb.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 10), UiKit.dp(act, 1)));
                    notesList.addView(nb);
                }
            } catch (Throwable ignored) {}
        };

        root.addView(UiKit.makeMenuButton(act, "Simpan Catatan Baru", null, UiKit.ACCENT_OFFICE, prefs, v -> {
            String t = noteIn.getText().toString().trim();
            if (t.isEmpty()) return;
            try {
                JSONArray arr = new JSONArray(prefs.getString("json_quick_notes", "[]"));
                String stamp = new SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(new Date());
                arr.put("[" + stamp + "] " + t);
                prefs.edit().putString("json_quick_notes", arr.toString()).apply();
                noteIn.setText("");
                reloadNotes[0].run();
            } catch (Throwable ignored) {}
        }));

        reloadNotes[0].run();
        root.addView(notesList);

        // Generator Password Aman
        TextView pwLbl = makeMutedLabel(act, prefs, "\nGenerator Kata Sandi Aman:");
        pwLbl.setTypeface(null, Typeface.BOLD);
        root.addView(pwLbl);

        final TextView pwResult = new TextView(act);
        pwResult.setText("Ketuk tombol di bawah untuk membuat sandi acak kuat");
        pwResult.setTextColor(UiKit.textPrimary(prefs));
        pwResult.setTextSize(15);
        pwResult.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pwResult.setPadding(0, UiKit.dp(act, 4), 0, UiKit.dp(act, 6));
        root.addView(pwResult);

        root.addView(UiKit.makeMenuButton(act, "Buat Sandi Acak 16 Karakter + Salin", null, UiKit.ACCENT_AI_ALT, prefs, v -> {
            String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*?";
            SecureRandom sr = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(chars.charAt(sr.nextInt(chars.length())));
            String pass = sb.toString();
            pwResult.setText(pass);
            copyText(act, pass, "Kata sandi kuat disalin ke Papan Klip!");
        }));

        showDialogScroll(act, "Catatan Cepat, Timer & Sandi", root);
    }

    // =========================================================================
    // 6. MODE BISNIS & CATATAN ORDER / PESANAN (TERHUBUNG KE KEYBOARD ⚡)
    // =========================================================================
    public static final String PREF_BUSINESS_ORDERS = "json_business_orders";
    public static final String[] ORDER_STATUS_OPTIONS = {"Menunggu / DP", "Proses", "Lunas"};

    public static void sharePlainText(Context ctx, String text, String chooserTitle) {
        if (ctx == null || text == null || text.trim().isEmpty()) return;
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, text);
            Intent chooser = Intent.createChooser(share, chooserTitle != null ? chooserTitle : "Bagikan Teks");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
        } catch (Throwable e) {
            Toast.makeText(ctx, "Tidak ada aplikasi berbagi tersedia.", Toast.LENGTH_SHORT).show();
        }
    }

    public static String formatRupiah(long nominal) {
        try {
            NumberFormat nf = NumberFormat.getIntegerInstance(new Locale("id", "ID"));
            return "Rp " + nf.format(nominal);
        } catch (Throwable e) {
            return "Rp " + nominal;
        }
    }

    public static ArrayList<JSONObject> loadBusinessOrders(SharedPreferences prefs) {
        ArrayList<JSONObject> out = new ArrayList<>();
        if (prefs == null) return out;
        try {
            JSONArray arr = new JSONArray(prefs.getString(PREF_BUSINESS_ORDERS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(o);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static void saveBusinessOrders(SharedPreferences prefs, ArrayList<JSONObject> orders) {
        if (prefs == null || orders == null) return;
        JSONArray arr = new JSONArray();
        for (JSONObject o : orders) arr.put(o);
        prefs.edit().putString(PREF_BUSINESS_ORDERS, arr.toString()).apply();
    }

    public static String formatOrderReceiptText(JSONObject o) {
        if (o == null) return "";
        String customer = o.optString("customer", "-").trim();
        String item = o.optString("item", "-").trim();
        long amount = o.optLong("amount", 0L);
        String start = o.optString("start_date", "").trim();
        String end = o.optString("end_date", "").trim();
        String status = o.optString("status", ORDER_STATUS_OPTIONS[0]).trim();
        String note = o.optString("note", "").trim();

        StringBuilder sb = new StringBuilder();
        sb.append("*CATATAN PESANAN*\n");
        sb.append("Pemesan: ").append(customer.isEmpty() ? "-" : customer).append("\n");
        sb.append("Pesanan: ").append(item.isEmpty() ? "-" : item).append("\n");
        sb.append("Nominal: ").append(formatRupiah(amount)).append("\n");
        sb.append("Status: ").append(status).append("\n");
        if (!start.isEmpty()) sb.append("Mulai: ").append(start).append("\n");
        if (!end.isEmpty()) sb.append("Berakhir: ").append(end).append("\n");
        if (!note.isEmpty()) sb.append("Catatan: ").append(note).append("\n");
        return sb.toString().trim();
    }

    public static void showBusinessOrdersDialog(final Activity act, final SharedPreferences prefs,
                                                boolean openAddFormImmediately) {
        LinearLayout root = makeBaseLayout(act, prefs);
        root.addView(makeMutedLabel(act, prefs,
                "Catat semua pesanan klien lengkap dengan nominal, status bayar, tanggal awal & akhir opsional. Langsung sinkron dengan Mode Bisnis di tombol ⚡ keyboard!"));

        final TextView summaryTv = new TextView(act);
        summaryTv.setTextColor(UiKit.textPrimary(prefs));
        summaryTv.setTextSize(13);
        summaryTv.setTypeface(null, Typeface.BOLD);
        summaryTv.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 10), UiKit.dp(act, 12), UiKit.dp(act, 10));
        summaryTv.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 10), UiKit.dp(act, 1)));
        root.addView(summaryTv);
        root.addView(new View(act), new LinearLayout.LayoutParams(1, UiKit.dp(act, 8)));

        final String[] filters = {"Semua Pesanan", "Belum Lunas (Menunggu/Proses)", "Sudah Lunas"};
        final Spinner spFilter = new Spinner(act);
        spFilter.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, filters));
        spFilter.setContentDescription("Filter daftar pesanan");
        root.addView(spFilter);

        LinearLayout searchRow = new LinearLayout(act);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(0, UiKit.dp(act, 6), 0, UiKit.dp(act, 6));
        final EditText etSearch = new EditText(act);
        etSearch.setHint("Cari nama pemesan atau barang...");
        etSearch.setSingleLine(true);
        etSearch.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button btnCari = UiKit.makePillButton(act, "Cari", UiKit.ACCENT_KB, Color.WHITE, null);
        searchRow.addView(etSearch);
        searchRow.addView(btnCari);
        root.addView(searchRow);

        final LinearLayout listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);

        final TextView pageInfo = makeMutedLabel(act, prefs, "");
        final Button btnPrev = UiKit.makePillButton(act, "Halaman Sebelumnya", UiKit.ACCENT_KB_ALT, Color.WHITE, null);
        final Button btnNext = UiKit.makePillButton(act, "Halaman Berikutnya", UiKit.ACCENT_KB, Color.WHITE, null);
        final int[] curPage = {0};
        final int[] totalPagesRef = {1};
        final Runnable[] reloadRef = new Runnable[1];

        Button btnAddNew = UiKit.makeMenuButton(act, "Catat Pesanan Baru",
                "Tambah data pesanan, nominal, status & tanggal awal/akhir opsional",
                UiKit.ACCENT_OFFICE, prefs, v -> showAddOrEditBusinessOrderDialog(act, prefs, -1, reloadRef[0]));
        root.addView(btnAddNew);

        LinearLayout quickToolsRow = new LinearLayout(act);
        quickToolsRow.setOrientation(LinearLayout.HORIZONTAL);
        quickToolsRow.setPadding(0, 0, 0, UiKit.dp(act, 8));
        Button btnSpeakRekap = UiKit.makePillButton(act, "Bacakan Rekap", UiKit.ACCENT_AI, Color.WHITE, null);
        Button btnShareRekap = UiKit.makePillButton(act, "Bagikan Rekap", UiKit.ACCENT_WARM, Color.WHITE, null);
        quickToolsRow.addView(btnSpeakRekap);
        quickToolsRow.addView(new View(act), new LinearLayout.LayoutParams(UiKit.dp(act, 8), 1));
        quickToolsRow.addView(btnShareRekap);
        root.addView(quickToolsRow);

        root.addView(pageInfo);
        LinearLayout navRow = new LinearLayout(act);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setPadding(0, UiKit.dp(act, 4), 0, UiKit.dp(act, 8));
        navRow.addView(btnPrev);
        navRow.addView(new View(act), new LinearLayout.LayoutParams(UiKit.dp(act, 8), 1));
        navRow.addView(btnNext);
        root.addView(navRow);
        root.addView(listBox);

        reloadRef[0] = () -> {
            ArrayList<JSONObject> all = loadBusinessOrders(prefs);
            long totalPaid = 0L, totalPending = 0L;
            int cntPaid = 0, cntPending = 0;
            for (JSONObject o : all) {
                long amt = o.optLong("amount", 0L);
                String st = o.optString("status", ORDER_STATUS_OPTIONS[0]);
                if ("Lunas".equalsIgnoreCase(st)) {
                    totalPaid += amt;
                    cntPaid++;
                } else {
                    totalPending += amt;
                    cntPending++;
                }
            }
            String sumText = "Total " + all.size() + " pesanan • Lunas (" + cntPaid + "): " + formatRupiah(totalPaid)
                    + " • Belum Lunas (" + cntPending + "): " + formatRupiah(totalPending);
            summaryTv.setText(sumText);
            summaryTv.setContentDescription(sumText);

            int fIdx = spFilter.getSelectedItemPosition();
            String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
            ArrayList<Integer> matchedIndices = new ArrayList<>();
            for (int i = all.size() - 1; i >= 0; i--) {
                JSONObject o = all.get(i);
                String st = o.optString("status", ORDER_STATUS_OPTIONS[0]);
                boolean isLunas = "Lunas".equalsIgnoreCase(st);
                if (fIdx == 1 && isLunas) continue;
                if (fIdx == 2 && !isLunas) continue;
                if (!q.isEmpty()) {
                    String hay = (o.optString("customer", "") + " " + o.optString("item", "")
                            + " " + o.optString("note", "")).toLowerCase(Locale.ROOT);
                    if (!hay.contains(q)) continue;
                }
                matchedIndices.add(i);
            }

            listBox.removeAllViews();
            if (matchedIndices.isEmpty()) {
                totalPagesRef[0] = 1;
                curPage[0] = 0;
                pageInfo.setText("Halaman 1 / 1 • 0 pesanan tampil");
                btnPrev.setEnabled(false);
                btnNext.setEnabled(false);
                listBox.addView(makeMutedLabel(act, prefs, "Belum ada catatan pesanan. Tekan '➕ Catat Pesanan Baru' untuk mulai mencatat."));
                return;
            }

            int pageSize = 10;
            int totalPages = Math.max(1, (matchedIndices.size() + pageSize - 1) / pageSize);
            totalPagesRef[0] = totalPages;
            if (curPage[0] >= totalPages) curPage[0] = totalPages - 1;
            if (curPage[0] < 0) curPage[0] = 0;
            int from = curPage[0] * pageSize;
            int to = Math.min(matchedIndices.size(), from + pageSize);

            pageInfo.setText("Halaman " + (curPage[0] + 1) + " dari " + totalPages + " • Menampilkan " + (from + 1) + "-" + to + " dari " + matchedIndices.size());
            btnPrev.setEnabled(curPage[0] > 0);
            btnNext.setEnabled(curPage[0] + 1 < totalPages);

            for (int k = from; k < to; k++) {
                final int origIdx = matchedIndices.get(k);
                final JSONObject o = all.get(origIdx);
                String cust = o.optString("customer", "Tanpa Nama");
                String item = o.optString("item", "-");
                long amt = o.optLong("amount", 0L);
                String st = o.optString("status", ORDER_STATUS_OPTIONS[0]);
                String sDate = o.optString("start_date", "");
                String eDate = o.optString("end_date", "");
                String dateStr = (!sDate.isEmpty() || !eDate.isEmpty())
                        ? (" | Tgl: " + (sDate.isEmpty() ? "-" : sDate) + " s/d " + (eDate.isEmpty() ? "-" : eDate))
                        : "";
                String title = "[" + st + "] " + cust + " — " + formatRupiah(amt);
                String sub = item + " • Status: " + st + dateStr;
                Button cb = UiKit.makeMenuButton(act, title, sub, UiKit.surface(prefs), prefs,
                        v -> showBusinessOrderActionDialog(act, prefs, origIdx, reloadRef[0]));
                cb.setTextColor(UiKit.textPrimary(prefs));
                cb.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 10), UiKit.dp(act, 1)));
                listBox.addView(cb);
            }
        };

        spFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { curPage[0] = 0; reloadRef[0].run(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        btnCari.setOnClickListener(v -> { curPage[0] = 0; reloadRef[0].run(); });
        btnPrev.setOnClickListener(v -> { if (curPage[0] > 0) { curPage[0]--; reloadRef[0].run(); } });
        btnNext.setOnClickListener(v -> { if (curPage[0] + 1 < totalPagesRef[0]) { curPage[0]++; reloadRef[0].run(); } });
        btnSpeakRekap.setOnClickListener(v -> speakOnce(act, summaryTv.getText().toString()));
        btnShareRekap.setOnClickListener(v -> {
            ArrayList<JSONObject> all = loadBusinessOrders(prefs);
            if (all.isEmpty()) {
                Toast.makeText(act, "Belum ada pesanan untuk dibagikan.", Toast.LENGTH_SHORT).show();
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("*REKAP PESANAN BISNIS*\n").append(summaryTv.getText().toString()).append("\n\n");
            int limit = Math.min(25, all.size());
            for (int i = all.size() - 1, no = 1; i >= all.size() - limit; i--, no++) {
                JSONObject o = all.get(i);
                sb.append(no).append(". ").append(o.optString("customer", "-"))
                  .append(" | ").append(o.optString("item", "-"))
                  .append(" | ").append(formatRupiah(o.optLong("amount", 0L)))
                  .append(" [").append(o.optString("status", "-")).append("]\n");
            }
            sharePlainText(act, sb.toString().trim(), "Bagikan Rekap Pesanan");
        });

        reloadRef[0].run();
        showDialogScroll(act, "Mode Bisnis & Catat Pesanan", root);

        if (openAddFormImmediately) {
            showAddOrEditBusinessOrderDialog(act, prefs, -1, reloadRef[0]);
        }
    }

    private static void showAddOrEditBusinessOrderDialog(final Activity act, final SharedPreferences prefs,
                                                         final int editIdx, final Runnable onSaved) {
        final ArrayList<JSONObject> all = loadBusinessOrders(prefs);
        final JSONObject existing = (editIdx >= 0 && editIdx < all.size()) ? all.get(editIdx) : null;

        LinearLayout form = makeBaseLayout(act, prefs);
        form.addView(makeMutedLabel(act, prefs, "Isi detail pesanan. Kolom tanggal awal & berakhir bersifat opsional (boleh dikosongkan)."));

        TextView lCust = makeMutedLabel(act, prefs, "1. Nama Pemesan / Klien:");
        lCust.setTypeface(null, Typeface.BOLD);
        form.addView(lCust);
        final EditText etCust = new EditText(act);
        etCust.setHint("Contoh: Budi / Toko Maju");
        etCust.setContentDescription("Masukkan nama pemesan atau klien");
        if (existing != null) etCust.setText(existing.optString("customer", ""));
        form.addView(etCust);

        TextView lItem = makeMutedLabel(act, prefs, "2. Apa yang Dipesan (Barang / Jasa):");
        lItem.setTypeface(null, Typeface.BOLD);
        form.addView(lItem);
        final EditText etItem = new EditText(act);
        etItem.setHint("Contoh: Desain Logo / Paket Kaos 5 pcs");
        etItem.setContentDescription("Masukkan barang atau jasa yang dipesan");
        if (existing != null) etItem.setText(existing.optString("item", ""));
        form.addView(etItem);

        TextView lAmt = makeMutedLabel(act, prefs, "3. Nominal / Harga (Rp):");
        lAmt.setTypeface(null, Typeface.BOLD);
        form.addView(lAmt);
        final EditText etAmt = new EditText(act);
        etAmt.setInputType(InputType.TYPE_CLASS_NUMBER);
        etAmt.setHint("Contoh: 150000");
        etAmt.setContentDescription("Masukkan nominal rupiah pesanan tanpa titik atau koma");
        if (existing != null && existing.optLong("amount", 0L) > 0L) {
            etAmt.setText(String.valueOf(existing.optLong("amount", 0L)));
        }
        form.addView(etAmt);

        LinearLayout amtPresets = new LinearLayout(act);
        amtPresets.setOrientation(LinearLayout.HORIZONTAL);
        amtPresets.setPadding(0, UiKit.dp(act, 2), 0, UiKit.dp(act, 8));
        long[] presetVals = {50000L, 100000L, 250000L, 500000L};
        String[] presetLbls = {"50rb", "100rb", "250rb", "500rb"};
        for (int i = 0; i < presetVals.length; i++) {
            final long val = presetVals[i];
            Button pb = UiKit.makePillButton(act, presetLbls[i], UiKit.ACCENT_KB_ALT, Color.WHITE, v -> etAmt.setText(String.valueOf(val)));
            pb.setContentDescription("Isi nominal " + formatRupiah(val));
            amtPresets.addView(pb);
            if (i < presetVals.length - 1) amtPresets.addView(new View(act), new LinearLayout.LayoutParams(UiKit.dp(act, 6), 1));
        }
        form.addView(amtPresets);

        TextView lStatus = makeMutedLabel(act, prefs, "4. Status Pembayaran:");
        lStatus.setTypeface(null, Typeface.BOLD);
        form.addView(lStatus);
        final Spinner spStatus = new Spinner(act);
        spStatus.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, ORDER_STATUS_OPTIONS));
        spStatus.setContentDescription("Pilih status pembayaran pesanan");
        if (existing != null) {
            String curSt = existing.optString("status", ORDER_STATUS_OPTIONS[0]);
            for (int i = 0; i < ORDER_STATUS_OPTIONS.length; i++) {
                if (ORDER_STATUS_OPTIONS[i].equalsIgnoreCase(curSt)) spStatus.setSelection(i);
            }
        }
        form.addView(spStatus);

        TextView lStart = makeMutedLabel(act, prefs, "5. Tanggal Awal / Mulai (Opsional):");
        lStart.setTypeface(null, Typeface.BOLD);
        form.addView(lStart);
        final EditText etStart = new EditText(act);
        etStart.setHint("Opsional — boleh dikosongkan");
        etStart.setContentDescription("Tanggal awal pesanan opsional");
        if (existing != null) etStart.setText(existing.optString("start_date", ""));
        form.addView(etStart);

        LinearLayout startBtns = new LinearLayout(act);
        startBtns.setOrientation(LinearLayout.HORIZONTAL);
        startBtns.setPadding(0, UiKit.dp(act, 2), 0, UiKit.dp(act, 8));
        Button bStartPick = UiKit.makePillButton(act, "Pilih Kalender", UiKit.ACCENT_KB, Color.WHITE, v -> pickDateInto(act, etStart, 0));
        Button bStartToday = UiKit.makePillButton(act, "Hari Ini", UiKit.ACCENT_OFFICE, Color.WHITE, v -> etStart.setText(formatOffsetDate(0)));
        Button bStartClear = UiKit.makePillButton(act, "Kosongkan", UiKit.ACCENT_SYSTEM, Color.WHITE, v -> etStart.setText(""));
        startBtns.addView(bStartPick);
        startBtns.addView(new View(act), new LinearLayout.LayoutParams(UiKit.dp(act, 6), 1));
        startBtns.addView(bStartToday);
        startBtns.addView(new View(act), new LinearLayout.LayoutParams(UiKit.dp(act, 6), 1));
        startBtns.addView(bStartClear);
        form.addView(startBtns);

        TextView lEnd = makeMutedLabel(act, prefs, "6. Tanggal Berakhir / Tenggat (Opsional):");
        lEnd.setTypeface(null, Typeface.BOLD);
        form.addView(lEnd);
        final EditText etEnd = new EditText(act);
        etEnd.setHint("Opsional — boleh dikosongkan");
        etEnd.setContentDescription("Tanggal berakhir atau tenggat pesanan opsional");
        if (existing != null) etEnd.setText(existing.optString("end_date", ""));
        form.addView(etEnd);

        LinearLayout endBtns = new LinearLayout(act);
        endBtns.setOrientation(LinearLayout.HORIZONTAL);
        endBtns.setPadding(0, UiKit.dp(act, 2), 0, UiKit.dp(act, 8));
        Button bEndPick = UiKit.makePillButton(act, "Pilih Kalender", UiKit.ACCENT_KB, Color.WHITE, v -> pickDateInto(act, etEnd, 7));
        Button bEndPlus7 = UiKit.makePillButton(act, "Tambah 7 Hari", UiKit.ACCENT_WARM, Color.WHITE, v -> etEnd.setText(formatOffsetDate(7)));
        Button bEndClear = UiKit.makePillButton(act, "Kosongkan", UiKit.ACCENT_SYSTEM, Color.WHITE, v -> etEnd.setText(""));
        endBtns.addView(bEndPick);
        endBtns.addView(new View(act), new LinearLayout.LayoutParams(UiKit.dp(act, 6), 1));
        endBtns.addView(bEndPlus7);
        endBtns.addView(new View(act), new LinearLayout.LayoutParams(UiKit.dp(act, 6), 1));
        endBtns.addView(bEndClear);
        form.addView(endBtns);

        TextView lNote = makeMutedLabel(act, prefs, "7. Catatan Tambahan (Opsional):");
        lNote.setTypeface(null, Typeface.BOLD);
        form.addView(lNote);
        final EditText etNote = new EditText(act);
        etNote.setHint("DP, ukuran, alamat kirim, dll...");
        etNote.setMinLines(2);
        etNote.setContentDescription("Catatan tambahan pesanan opsional");
        if (existing != null) etNote.setText(existing.optString("note", ""));
        form.addView(etNote);

        ScrollView sc = new ScrollView(act);
        sc.addView(form);
        UiKit.dialog(act, prefs)
                .setTitle(existing == null ? "Catat Pesanan Baru" : "Ubah Catatan Pesanan")
                .setView(sc)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (d, w) -> {
                    String cust = etCust.getText().toString().trim();
                    String item = etItem.getText().toString().trim();
                    if (cust.isEmpty() && item.isEmpty()) {
                        Toast.makeText(act, "Isi minimal Nama Pemesan atau Apa yang Dipesan.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    long amt = 0L;
                    try {
                        String digits = etAmt.getText().toString().replaceAll("[^0-9]", "");
                        if (!digits.isEmpty()) amt = Long.parseLong(digits);
                    } catch (Throwable ignored) {}
                    try {
                        JSONObject obj = existing != null ? new JSONObject(existing.toString()) : new JSONObject();
                        if (!obj.has("id")) obj.put("id", System.currentTimeMillis());
                        obj.put("customer", cust.isEmpty() ? "Pelanggan" : cust);
                        obj.put("item", item.isEmpty() ? "-" : item);
                        obj.put("amount", amt);
                        obj.put("status", ORDER_STATUS_OPTIONS[spStatus.getSelectedItemPosition()]);
                        obj.put("start_date", etStart.getText().toString().trim());
                        obj.put("end_date", etEnd.getText().toString().trim());
                        obj.put("note", etNote.getText().toString().trim());
                        if (editIdx >= 0 && editIdx < all.size()) all.set(editIdx, obj);
                        else all.add(obj);
                        saveBusinessOrders(prefs, all);
                        Toast.makeText(act, "Pesanan tersimpan & sinkron dengan keyboard!", Toast.LENGTH_SHORT).show();
                        if (onSaved != null) onSaved.run();
                    } catch (Throwable ignored) {}
                }).show();
    }

    private static void showBusinessOrderActionDialog(final Activity act, final SharedPreferences prefs,
                                                      final int origIdx, final Runnable onChanged) {
        final ArrayList<JSONObject> all = loadBusinessOrders(prefs);
        if (origIdx < 0 || origIdx >= all.size()) return;
        final JSONObject o = all.get(origIdx);
        final String receipt = formatOrderReceiptText(o);

        LinearLayout box = makeBaseLayout(act, prefs);
        TextView msg = new TextView(act);
        msg.setText(receipt);
        msg.setTextColor(UiKit.textPrimary(prefs));
        msg.setTextSize(14);
        msg.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 8), UiKit.dp(act, 10), UiKit.dp(act, 10));
        msg.setBackground(UiKit.roundedBox(UiKit.surface(prefs), UiKit.border(prefs), UiKit.dp(act, 10), UiKit.dp(act, 1)));
        box.addView(msg);
        box.addView(new View(act), new LinearLayout.LayoutParams(1, UiKit.dp(act, 8)));

        final AlertDialog[] dlgRef = new AlertDialog[1];

        box.addView(UiKit.makeMenuButton(act, "Bagikan Nota Pesanan (WA / Lainnya)",
                "Kirim rincian pesanan sebagai pesan teks",
                UiKit.ACCENT_OFFICE, prefs, v -> {
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    sharePlainText(act, receipt, "Bagikan Nota Pesanan");
                }));

        box.addView(UiKit.makeMenuButton(act, "Salin Nota Pesanan", null,
                UiKit.ACCENT_KB, prefs, v -> {
                    copyText(act, receipt, "Nota pesanan disalin!");
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                }));

        boolean isLunas = "Lunas".equalsIgnoreCase(o.optString("status", ""));
        box.addView(UiKit.makeMenuButton(act, isLunas ? "Tandai Belum Lunas" : "Tandai Lunas",
                "Ubah status pembayaran pesanan ini",
                UiKit.ACCENT_WARM, prefs, v -> {
                    try {
                        o.put("status", isLunas ? "Menunggu / DP" : "Lunas");
                        all.set(origIdx, o);
                        saveBusinessOrders(prefs, all);
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        if (onChanged != null) onChanged.run();
                    } catch (Throwable ignored) {}
                }));

        box.addView(UiKit.makeMenuButton(act, "Ubah Detail Pesanan", null,
                UiKit.ACCENT_KB_ALT, prefs, v -> {
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    showAddOrEditBusinessOrderDialog(act, prefs, origIdx, onChanged);
                }));

        box.addView(UiKit.makeMenuButton(act, "Bacakan Detail dengan Suara (TTS)", null,
                UiKit.ACCENT_AI, prefs, v -> speakOnce(act, receipt)));

        box.addView(UiKit.makeMenuButton(act, "Hapus Pesanan Ini", null,
                Color.parseColor("#DC2626"), prefs, v -> {
                    all.remove(origIdx);
                    saveBusinessOrders(prefs, all);
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    if (onChanged != null) onChanged.run();
                    Toast.makeText(act, "Pesanan dihapus.", Toast.LENGTH_SHORT).show();
                }));

        ScrollView sc = new ScrollView(act);
        sc.addView(box);
        dlgRef[0] = UiKit.dialog(act, prefs)
                .setTitle("Detail & Aksi Pesanan")
                .setView(sc)
                .create();
        dlgRef[0].show();
    }

    private static String formatOffsetDate(int offsetDays) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, offsetDays);
        return new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID")).format(c.getTime());
    }

    private static void pickDateInto(Activity act, final EditText target, int defaultOffsetDays) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, defaultOffsetDays);
        new DatePickerDialog(act, (view, year, month, dayOfMonth) -> {
            Calendar chosen = Calendar.getInstance();
            chosen.set(year, month, dayOfMonth);
            target.setText(new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID")).format(chosen.getTime()));
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ====================== HELPERS ======================

    private static LinearLayout makeBaseLayout(Activity act, SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.bg(prefs));
        root.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 14), UiKit.dp(act, 16), UiKit.dp(act, 14));
        return root;
    }

    private static TextView makeMutedLabel(Activity act, SharedPreferences prefs, String txt) {
        TextView tv = new TextView(act);
        tv.setText(txt);
        tv.setTextColor(UiKit.textMuted(prefs));
        tv.setTextSize(13);
        tv.setPadding(0, UiKit.dp(act, 2), 0, UiKit.dp(act, 6));
        return tv;
    }

    private static void showDialogScroll(Activity act, String title, View content) {
        ScrollView sc = new ScrollView(act);
        sc.addView(content);
        UiKit.dialog(act, act.getSharedPreferences("AnnasKeyboardPrefs", Context.MODE_PRIVATE))
                .setTitle(title).setView(sc).show();
    }

    private static void copyText(Context ctx, String text, String toastMsg) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("AnasKeyboard", text));
            Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    private static String readClipboard(Context ctx) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx);
                return t == null ? "" : t.toString();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static void speakOnce(final Context ctx, final String text) {
        if (text == null || text.trim().isEmpty()) return;
        final TextToSpeech[] holder = new TextToSpeech[1];
        holder[0] = new TextToSpeech(ctx.getApplicationContext(), st -> {
            if (st == TextToSpeech.SUCCESS && holder[0] != null) {
                try {
                    int r = holder[0].setLanguage(new Locale("id", "ID"));
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) holder[0].setLanguage(Locale.getDefault());
                    holder[0].speak(text, TextToSpeech.QUEUE_FLUSH, null, "extra_tts");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try { if (holder[0] != null) holder[0].shutdown(); } catch (Throwable ignored) {}
                    }, 15000);
                } catch (Throwable ignored) {}
            }
        });
    }
}
