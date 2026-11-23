package com.annas;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Dialog Alat Cepat Keyboard (⚡) berbasis AlertDialog terlampir ke token jendela IME
 * (sama amannya seperti pemilih Emoji/Stiker sehingga bebas geser/klik di Jieshuo & TalkBack
 * tanpa menyentuh papan ketik huruf di bawahnya).
 */
public final class KeyboardQuickPanelHelper {

    public interface Actions {
        String getLastDeletedText();
        void restoreLastDeletedText();
        void commitSnippet(String text);
        void sendKeyCode(int keyCode);
        void selectAllText();
        void deleteLastWord();
        void transformCase(int mode); // 0=UPPER, 1=lower, 2=Title
        void speakFieldText();
        void speakText(String text);
        void openMainApp();
        void openBusinessOrderForm();
        void clearNonPinnedClips();
        void toggleOneHandedMode();
        void switchToBraille();
        void toggleScriptLanguage();
    }

    private KeyboardQuickPanelHelper() {}

    public static AlertDialog showDialog(final Context ctx, final IBinder windowToken,
                                         final SharedPreferences prefs,
                                         final ArrayList<String> recentClips,
                                         final Actions actions) {
        final boolean dark = UiKit.isDark(prefs);
        final int bgPanel = dark ? Color.parseColor("#131A29") : Color.parseColor("#F4F7FC");
        final int bgCard = dark ? Color.parseColor("#1D283D") : Color.parseColor("#FFFFFF");
        final int bgBtn = dark ? Color.parseColor("#263552") : Color.parseColor("#DCE6F5");
        final int bgAccent = Color.parseColor("#0EA5E9");
        final int bgPurple = Color.parseColor("#7C3AED");
        final int bgGreen = Color.parseColor("#059669");
        final int bgAmber = Color.parseColor("#D97706");
        final int bgDanger = Color.parseColor("#DC2626");
        final int txtPrimary = dark ? Color.parseColor("#F8FAFC") : Color.parseColor("#0F172A");
        final int txtSecondary = dark ? Color.parseColor("#94A3B8") : Color.parseColor("#475569");
        final int borderColor = dark ? Color.parseColor("#334466") : Color.parseColor("#CBD5E1");

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(bgPanel);
        box.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 6));

        // 6 Mode Alat Cepat
        final String[] tabLabels = {
            "Aksi & Kursor",
            "Kata Mutiara",
            "Bisnis & Order",
            "Hitung & Simbol",
            "Identitas & Template",
            "Papan Klip"
        };
        final Button[] tabBtns = new Button[tabLabels.length];
        HorizontalScrollView tabScroll = new HorizontalScrollView(ctx);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(ctx);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll.addView(tabRow);
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tsLp.bottomMargin = dp(ctx, 8);
        box.addView(tabScroll, tsLp);

        final TextView pageCounter = new TextView(ctx);
        pageCounter.setGravity(Gravity.CENTER);
        pageCounter.setTextColor(txtSecondary);
        pageCounter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pageCounter.setPadding(0, dp(ctx, 2), 0, dp(ctx, 6));
        box.addView(pageCounter);

        final ScrollView bodyScroll = new ScrollView(ctx);
        int scrollH = dp(ctx, 320);
        bodyScroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, scrollH));
        final LinearLayout bodyContainer = new LinearLayout(ctx);
        bodyContainer.setOrientation(LinearLayout.VERTICAL);
        bodyScroll.addView(bodyContainer);
        box.addView(bodyScroll);

        LinearLayout navRow = new LinearLayout(ctx);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.topMargin = dp(ctx, 8);
        box.addView(navRow, navLp);

        final Button btnPrev = makeNavBtn(ctx, "Sebelumnya", bgBtn, txtPrimary);
        final Button btnNext = makeNavBtn(ctx, "Berikutnya", bgBtn, txtPrimary);
        final Button btnMain = makeNavBtn(ctx, "Aplikasi Utama", bgPurple, Color.WHITE);
        navRow.addView(btnPrev, flexLp(ctx, 4));
        navRow.addView(btnNext, flexLp(ctx, 4));
        navRow.addView(btnMain, flexLp(ctx, 0));

        final AlertDialog dialog = UiKit.dialog(ctx, prefs)
                .setTitle("Alat Cepat Keyboard")
                .setView(box)
                .create();

        final int[] activeTab = {0};
        final int[] quoteCatIdx = {0};
        final int[] bizFilter = {0}; // 0=Semua, 1=Belum Lunas, 2=Lunas
        final int[] pageIdx = {0};
        final StringBuilder calcExpr = new StringBuilder();
        final Runnable[] renderRef = new Runnable[1];

        btnMain.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                actions.openMainApp();
            }
        });

        renderRef[0] = new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < tabBtns.length; i++) {
                    boolean sel = (i == activeTab[0]);
                    tabBtns[i].setBackground(roundBg(sel ? bgAccent : bgBtn, borderColor, dp(ctx, 14)));
                    tabBtns[i].setTextColor(sel ? Color.WHITE : txtPrimary);
                }
                bodyContainer.removeAllViews();
                bodyScroll.scrollTo(0, 0);
                btnPrev.setVisibility(View.GONE);
                btnNext.setVisibility(View.GONE);
                btnPrev.setOnClickListener(null);
                btnNext.setOnClickListener(null);

                switch (activeTab[0]) {
                    case 0:
                        pageCounter.setText("Navigasi Kursor, Sunting Cepat & Mode Keyboard");
                        buildActionsTab(ctx, prefs, bodyContainer, actions, dialog,
                                bgCard, bgBtn, bgAccent, bgPurple, bgGreen, bgAmber, bgDanger,
                                txtPrimary, txtSecondary, borderColor);
                        break;
                    case 1:
                        buildQuotesDialogTab(ctx, windowToken, prefs, bodyContainer, pageCounter, btnPrev, btnNext,
                                actions, dialog, quoteCatIdx, pageIdx, renderRef[0],
                                bgPanel, bgCard, bgBtn, bgAccent, bgPurple, bgGreen, txtPrimary, txtSecondary, borderColor);
                        break;
                    case 2:
                        buildBusinessDialogTab(ctx, windowToken, prefs, bodyContainer, pageCounter, btnPrev, btnNext,
                                actions, dialog, bizFilter, pageIdx, renderRef[0],
                                bgPanel, bgCard, bgBtn, bgAccent, bgPurple, bgGreen, bgAmber, bgDanger,
                                txtPrimary, txtSecondary, borderColor);
                        break;
                    case 3:
                        buildCalcSymbolsTab(ctx, bodyContainer, pageCounter, btnPrev, btnNext,
                                actions, dialog, calcExpr, pageIdx, renderRef[0],
                                bgCard, bgBtn, bgAccent, bgGreen, bgAmber, txtPrimary, txtSecondary, borderColor);
                        break;
                    case 4:
                        buildIdentitySnippetsTab(ctx, prefs, bodyContainer, pageCounter, btnPrev, btnNext,
                                actions, dialog, pageIdx, renderRef[0],
                                bgCard, bgBtn, bgAccent, bgGreen, txtPrimary, txtSecondary, borderColor);
                        break;
                    case 5:
                        buildClipboardTab(ctx, prefs, recentClips, bodyContainer, pageCounter, btnPrev, btnNext,
                                actions, dialog, pageIdx, renderRef[0],
                                bgCard, bgBtn, bgDanger, txtPrimary, txtSecondary, borderColor);
                        break;
                }
            }
        };

        for (int i = 0; i < tabLabels.length; i++) {
            final int idx = i;
            Button tb = makePillBtn(ctx, tabLabels[i], bgBtn, txtPrimary, 12);
            tb.setContentDescription("Mode " + tabLabels[i]);
            tb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    activeTab[0] = idx;
                    pageIdx[0] = 0;
                    renderRef[0].run();
                }
            });
            tabBtns[i] = tb;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36));
            lp.rightMargin = dp(ctx, 6);
            tabRow.addView(tb, lp);
        }

        renderRef[0].run();
        attachAndShowImDialog(ctx, windowToken, dialog, 430f);
        return dialog;
    }

    private static void attachAndShowImDialog(Context ctx, IBinder windowToken, AlertDialog dialog, float maxWidthDp) {
        Window w = dialog.getWindow();
        if (w != null) {
            w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG);
            WindowManager.LayoutParams attrs = w.getAttributes();
            if (windowToken != null) attrs.token = windowToken;
            w.setAttributes(attrs);
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            float dens = ctx.getResources().getDisplayMetrics().density;
            int widthDp = (int) Math.min(maxWidthDp, (ctx.getResources().getDisplayMetrics().widthPixels / dens) - 18f);
            dialog.getWindow().setLayout((int) (widthDp * dens), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    // ==================== TAB 1: AKSI & KURSOR ====================
    private static void buildActionsTab(final Context ctx, SharedPreferences prefs, LinearLayout out,
                                        final Actions actions, final AlertDialog dialog,
                                        int bgCard, int bgBtn, int bgAccent, int bgPurple,
                                        int bgGreen, int bgAmber, int bgDanger,
                                        int txtPrimary, int txtSecondary, int borderColor) {
        addSectionTitle(ctx, out, "Navigasi Kursor (Ketuk untuk Geser Kursor)", txtSecondary);
        addActionBtn(ctx, out, "Pindah Kursor ke Awal Baris", bgBtn, txtPrimary, borderColor,
                () -> actions.sendKeyCode(KeyEvent.KEYCODE_MOVE_HOME));
        addActionBtn(ctx, out, "Geser Kursor ke Kiri (1 Karakter)", bgBtn, txtPrimary, borderColor,
                () -> actions.sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT));
        addActionBtn(ctx, out, "Geser Kursor ke Kanan (1 Karakter)", bgBtn, txtPrimary, borderColor,
                () -> actions.sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT));
        addActionBtn(ctx, out, "Geser Kursor ke Atas (1 Baris)", bgBtn, txtPrimary, borderColor,
                () -> actions.sendKeyCode(KeyEvent.KEYCODE_DPAD_UP));
        addActionBtn(ctx, out, "Geser Kursor ke Bawah (1 Baris)", bgBtn, txtPrimary, borderColor,
                () -> actions.sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN));
        addActionBtn(ctx, out, "Pindah Kursor ke Akhir Baris", bgBtn, txtPrimary, borderColor,
                () -> actions.sendKeyCode(KeyEvent.KEYCODE_MOVE_END));

        addSectionTitle(ctx, out, "Sunting Cepat & Suara", txtSecondary);
        String lastDel = actions.getLastDeletedText();
        String undoTxt = (lastDel != null && !lastDel.isEmpty()) ? "Batalkan (Undo) Hapus Terakhir" : "Batalkan (Undo) Hapus";
        addActionBtn(ctx, out, undoTxt, bgAmber, Color.WHITE, borderColor, () -> {
            actions.restoreLastDeletedText();
            dialog.dismiss();
        });
        addActionBtn(ctx, out, "Hapus Satu Kata Sebelum Kursor", bgDanger, Color.WHITE, borderColor,
                actions::deleteLastWord);
        addActionBtn(ctx, out, "Pilih Semua Teks di Kolom", bgAccent, Color.WHITE, borderColor, () -> {
            actions.selectAllText();
            dialog.dismiss();
        });
        addActionBtn(ctx, out, "Bacakan Isi Kolom dengan Suara TTS", bgPurple, Color.WHITE, borderColor,
                actions::speakFieldText);

        addSectionTitle(ctx, out, "Ubah Huruf Besar / Kecil", txtSecondary);
        addActionBtn(ctx, out, "Ubah ke HURUF BESAR SEMUA", bgCard, txtPrimary, borderColor, () -> actions.transformCase(0));
        addActionBtn(ctx, out, "Ubah ke huruf kecil semua", bgCard, txtPrimary, borderColor, () -> actions.transformCase(1));
        addActionBtn(ctx, out, "Ubah ke Huruf Judul Kapital", bgCard, txtPrimary, borderColor, () -> actions.transformCase(2));

        addSectionTitle(ctx, out, "Mode Papan Ketik", txtSecondary);
        String oneHand = prefs != null ? prefs.getString("one_handed_mode", "off") : "off";
        String script = prefs != null ? prefs.getString("keyboard_script", "latin") : "latin";
        String handLabel = "off".equals(oneHand) ? "Mode 1-Tangan (Saat ini: Mati)"
                : ("right".equals(oneHand) ? "Mode 1-Tangan (Saat ini: Kanan)" : "Mode 1-Tangan (Saat ini: Kiri)");
        String scriptLabel = "arabic".equals(script) ? "Aksara Arab -> Ganti ke Latin" : "Aksara Latin -> Ganti ke Arab";

        addActionBtn(ctx, out, handLabel, bgCard, txtPrimary, borderColor, () -> {
            actions.toggleOneHandedMode();
            dialog.dismiss();
        });
        addActionBtn(ctx, out, "Pindah ke Papan Ketik Braille 6-Titik", bgGreen, Color.WHITE, borderColor, () -> {
            dialog.dismiss();
            actions.switchToBraille();
        });
        addActionBtn(ctx, out, scriptLabel, bgCard, txtPrimary, borderColor, () -> {
            actions.toggleScriptLanguage();
            dialog.dismiss();
        });
    }

    // ==================== TAB 2: KATA MUTIARA (18.750+ BERHALAMAN + BAGIKAN) ====================
    private static void buildQuotesDialogTab(final Context ctx, final IBinder windowToken,
                                             final SharedPreferences prefs,
                                             final LinearLayout out, TextView pageCounter,
                                             Button btnPrev, Button btnNext,
                                             final Actions actions, final AlertDialog parentDialog,
                                             final int[] catIdx, final int[] pageIdx,
                                             final Runnable refreshAll,
                                             final int bgPanel, final int bgCard, final int bgBtn,
                                             final int bgAccent, final int bgPurple, final int bgGreen,
                                             final int txtPrimary, final int txtSecondary, final int borderColor) {
        final int totalAll = ExtraToolsUI.getTotalBuiltinQuotesCount(ctx);

        String[] labels = new String[ExtraToolsUI.QUOTE_CATEGORIES.length];
        for (int i = 0; i < ExtraToolsUI.QUOTE_CATEGORIES.length; i++) {
            int cnt = ExtraToolsUI.getQuotesForCategory(ctx, prefs, i).size();
            labels[i] = ExtraToolsUI.QUOTE_CATEGORIES[i] + " (" + cnt + ")";
        }
        final Spinner catSpin = new Spinner(ctx);
        catSpin.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, labels));
        catSpin.setSelection(Math.max(0, Math.min(labels.length - 1, catIdx[0])));
        catSpin.setContentDescription("Pilih kategori kata mutiara, tersedia " + labels.length + " kategori");
        catSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean firstInit = true;
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (firstInit) { firstInit = false; return; }
                if (catIdx[0] != pos) {
                    catIdx[0] = pos;
                    pageIdx[0] = 0;
                    refreshAll.run();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        out.addView(catSpin, rowMargin(ctx));

        LinearLayout topTools = new LinearLayout(ctx);
        topTools.setOrientation(LinearLayout.HORIZONTAL);
        addFlexBtn(ctx, topTools, "Quote Acak", bgPurple, Color.WHITE, borderColor,
                "Pilih satu kata mutiara acak dari kategori ini",
                v -> showQuoteActionImDialog(ctx, windowToken,
                        ExtraToolsUI.getRandomQuoteFromCategory(ctx, prefs, catIdx[0]),
                        actions, parentDialog, bgPanel, bgCard, bgAccent, bgPurple, bgGreen, bgBtn, txtPrimary, borderColor));
        addFlexBtn(ctx, topTools, "Cari / Tambah Quote", bgAccent, Color.WHITE, borderColor,
                "Buka pencarian & buat quote kustom di Aplikasi Utama",
                v -> { parentDialog.dismiss(); actions.openMainApp(); });
        out.addView(topTools, rowMargin(ctx));

        ArrayList<String> list = ExtraToolsUI.getQuotesForCategory(ctx, prefs, catIdx[0]);
        if (list.isEmpty()) {
            pageCounter.setText("Total Bank: " + totalAll + " quote • Halaman 1 dari 1");
            TextView empty = new TextView(ctx);
            empty.setText("Belum ada Quote Kustom. Tambah lewat Aplikasi Utama -> Menu 7 -> Quote & Kata Mutiara.");
            empty.setTextColor(txtSecondary);
            empty.setPadding(dp(ctx, 8), dp(ctx, 12), dp(ctx, 8), dp(ctx, 12));
            out.addView(empty);
            return;
        }

        final int pageSize = 10;
        final int totalPages = Math.max(1, (list.size() + pageSize - 1) / pageSize);
        if (pageIdx[0] >= totalPages) pageIdx[0] = totalPages - 1;
        if (pageIdx[0] < 0) pageIdx[0] = 0;

        int from = pageIdx[0] * pageSize;
        int to = Math.min(list.size(), from + pageSize);
        pageCounter.setText("Halaman " + (pageIdx[0] + 1) + " dari " + totalPages + " • " + list.size() + " quote (Bank: " + totalAll + "+)");

        btnPrev.setVisibility(View.VISIBLE);
        btnNext.setVisibility(View.VISIBLE);
        btnPrev.setEnabled(pageIdx[0] > 0);
        btnNext.setEnabled(pageIdx[0] + 1 < totalPages);
        btnPrev.setOnClickListener(v -> { if (pageIdx[0] > 0) { pageIdx[0]--; refreshAll.run(); } });
        btnNext.setOnClickListener(v -> { if (pageIdx[0] + 1 < totalPages) { pageIdx[0]++; refreshAll.run(); } });

        for (int i = from; i < to; i++) {
            final String q = list.get(i);
            Button card = new Button(ctx);
            card.setText((i + 1) + ". " + q);
            card.setAllCaps(false);
            card.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            card.setTextColor(txtPrimary);
            card.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            card.setBackground(roundBg(bgCard, borderColor, dp(ctx, 8)));
            card.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
            card.setContentDescription("Quote " + (i + 1) + ": " + q + ". Ketuk untuk sisipkan, bagikan, salin, atau dengarkan.");
            card.setOnClickListener(v -> showQuoteActionImDialog(
                    ctx, windowToken, q, actions, parentDialog,
                    bgPanel, bgCard, bgAccent, bgPurple, bgGreen, bgBtn, txtPrimary, borderColor));
            out.addView(card, rowMargin(ctx));
        }
    }

    private static void showQuoteActionImDialog(final Context ctx, IBinder windowToken, final String quote,
                                                final Actions actions, final AlertDialog parentDialog,
                                                int bgPanel, int bgCard, int bgAccent, int bgPurple,
                                                int bgGreen, int bgBtn, int txtPrimary, int borderColor) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(bgPanel);
        box.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));

        TextView tv = new TextView(ctx);
        tv.setText("\"" + quote + "\"");
        tv.setTextColor(txtPrimary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setBackground(roundBg(bgCard, borderColor, dp(ctx, 8)));
        tv.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
        box.addView(tv, rowMargin(ctx));

        final AlertDialog actDlg = new AlertDialog.Builder(ctx)
                .setTitle("Aksi Kata Mutiara")
                .setView(box)
                .setNegativeButton("Kembali", null)
                .create();

        addActionBtn(ctx, box, "Sisipkan Langsung ke Kolom Ketik", bgAccent, Color.WHITE, borderColor, () -> {
            actions.commitSnippet(quote);
            actDlg.dismiss();
            parentDialog.dismiss();
        });
        addActionBtn(ctx, box, "Bagikan ke WA / TikTok / IG / Lainnya", bgGreen, Color.WHITE, borderColor, () -> {
            actDlg.dismiss();
            parentDialog.dismiss();
            ExtraToolsUI.sharePlainText(ctx, quote, "Bagikan Kata Mutiara");
        });
        addActionBtn(ctx, box, "Salin ke Papan Klip", bgBtn, txtPrimary, borderColor, () -> {
            copyToClipboard(ctx, quote, "Quote disalin!");
            actDlg.dismiss();
        });
        addActionBtn(ctx, box, "Bacakan dengan Suara (TTS)", bgPurple, Color.WHITE, borderColor,
                () -> actions.speakText(quote));

        attachAndShowImDialog(ctx, windowToken, actDlg, 400f);
    }

    // ==================== TAB 3: MODE BISNIS & CATAT PESANAN ====================
    private static void buildBusinessDialogTab(final Context ctx, final IBinder windowToken,
                                               final SharedPreferences prefs, LinearLayout out,
                                               TextView pageCounter, Button btnPrev, Button btnNext,
                                               final Actions actions, final AlertDialog parentDialog,
                                               final int[] filterIdx, final int[] pageIdx,
                                               final Runnable refreshAll,
                                               final int bgPanel, final int bgCard, final int bgBtn,
                                               final int bgAccent, final int bgPurple, final int bgGreen,
                                               final int bgAmber, final int bgDanger,
                                               final int txtPrimary, final int txtSecondary, final int borderColor) {
        final ArrayList<JSONObject> all = ExtraToolsUI.loadBusinessOrders(prefs);
        long totalPaid = 0L, totalPending = 0L;
        int cntPaid = 0, cntPending = 0;
        for (JSONObject o : all) {
            long amt = o.optLong("amount", 0L);
            if ("Lunas".equalsIgnoreCase(o.optString("status", ""))) {
                totalPaid += amt;
                cntPaid++;
            } else {
                totalPending += amt;
                cntPending++;
            }
        }
        final String sumText = "Total " + all.size() + " pesanan • Lunas (" + cntPaid + "): "
                + ExtraToolsUI.formatRupiah(totalPaid) + " • Belum Lunas (" + cntPending + "): "
                + ExtraToolsUI.formatRupiah(totalPending);

        TextView summaryBanner = new TextView(ctx);
        summaryBanner.setText(sumText);
        summaryBanner.setTextColor(txtPrimary);
        summaryBanner.setTypeface(null, Typeface.BOLD);
        summaryBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        summaryBanner.setBackground(roundBg(bgCard, borderColor, dp(ctx, 8)));
        summaryBanner.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
        summaryBanner.setContentDescription("Ringkasan bisnis: " + sumText);
        out.addView(summaryBanner, rowMargin(ctx));

        addActionBtn(ctx, out, "Catat Pesanan Baru (Form Lengkap & Kalender)", bgGreen, Color.WHITE, borderColor, () -> {
            parentDialog.dismiss();
            actions.openBusinessOrderForm();
        });

        LinearLayout actRow = new LinearLayout(ctx);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        addFlexBtn(ctx, actRow, "Bacakan Rekap", bgPurple, Color.WHITE, borderColor,
                "Bacakan ringkasan pesanan bisnis", v -> actions.speakText(sumText));
        addFlexBtn(ctx, actRow, "Bagikan Rekap", bgAmber, Color.WHITE, borderColor,
                "Bagikan rekap seluruh pesanan ke WhatsApp atau aplikasi lain", v -> {
                    if (all.isEmpty()) {
                        Toast.makeText(ctx, "Belum ada pesanan untuk dibagikan.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    StringBuilder sb = new StringBuilder("*REKAP PESANAN BISNIS*\n").append(sumText).append("\n\n");
                    int max = Math.min(25, all.size());
                    for (int i = all.size() - 1, no = 1; i >= all.size() - max; i--, no++) {
                        JSONObject o = all.get(i);
                        sb.append(no).append(". ").append(o.optString("customer", "-"))
                          .append(" | ").append(o.optString("item", "-"))
                          .append(" | ").append(ExtraToolsUI.formatRupiah(o.optLong("amount", 0L)))
                          .append(" [").append(o.optString("status", "-")).append("]\n");
                    }
                    parentDialog.dismiss();
                    ExtraToolsUI.sharePlainText(ctx, sb.toString().trim(), "Bagikan Rekap Pesanan");
                });
        out.addView(actRow, rowMargin(ctx));

        // Filter status
        LinearLayout filterRow = new LinearLayout(ctx);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] fNames = {"Semua", "Belum Lunas", "Lunas"};
        for (int i = 0; i < fNames.length; i++) {
            final int fi = i;
            boolean sel = (filterIdx[0] == i);
            addFlexBtn(ctx, filterRow, fNames[i], sel ? bgAccent : bgBtn, sel ? Color.WHITE : txtPrimary,
                    borderColor, "Filter " + fNames[i], v -> { filterIdx[0] = fi; pageIdx[0] = 0; refreshAll.run(); });
        }
        out.addView(filterRow, rowMargin(ctx));

        ArrayList<Integer> matched = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            boolean isLunas = "Lunas".equalsIgnoreCase(all.get(i).optString("status", ""));
            if (filterIdx[0] == 1 && isLunas) continue;
            if (filterIdx[0] == 2 && !isLunas) continue;
            matched.add(i);
        }

        if (matched.isEmpty()) {
            pageCounter.setText("Mode Bisnis • 0 pesanan");
            TextView empty = new TextView(ctx);
            empty.setText("Belum ada catatan pesanan di filter ini. Tekan '➕ Catat Pesanan Baru' untuk menambah.");
            empty.setTextColor(txtSecondary);
            empty.setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10));
            out.addView(empty);
            return;
        }

        final int pageSize = 8;
        final int totalPages = Math.max(1, (matched.size() + pageSize - 1) / pageSize);
        if (pageIdx[0] >= totalPages) pageIdx[0] = totalPages - 1;
        if (pageIdx[0] < 0) pageIdx[0] = 0;
        int from = pageIdx[0] * pageSize;
        int to = Math.min(matched.size(), from + pageSize);
        pageCounter.setText("Bisnis & Order • Halaman " + (pageIdx[0] + 1) + " dari " + totalPages + " (" + matched.size() + " pesanan)");

        if (totalPages > 1) {
            btnPrev.setVisibility(View.VISIBLE);
            btnNext.setVisibility(View.VISIBLE);
            btnPrev.setEnabled(pageIdx[0] > 0);
            btnNext.setEnabled(pageIdx[0] + 1 < totalPages);
            btnPrev.setOnClickListener(v -> { if (pageIdx[0] > 0) { pageIdx[0]--; refreshAll.run(); } });
            btnNext.setOnClickListener(v -> { if (pageIdx[0] + 1 < totalPages) { pageIdx[0]++; refreshAll.run(); } });
        }

        for (int k = from; k < to; k++) {
            final int origIdx = matched.get(k);
            final JSONObject o = all.get(origIdx);
            String cust = o.optString("customer", "Pelanggan");
            String item = o.optString("item", "-");
            long amt = o.optLong("amount", 0L);
            String st = o.optString("status", ExtraToolsUI.ORDER_STATUS_OPTIONS[0]);
            String sDate = o.optString("start_date", "");
            String eDate = o.optString("end_date", "");
            String dateTag = (!sDate.isEmpty() || !eDate.isEmpty())
                    ? ("\nTanggal: " + (sDate.isEmpty() ? "-" : sDate) + " s/d " + (eDate.isEmpty() ? "-" : eDate)) : "";
            String label = "[" + st + "] " + cust + " • " + ExtraToolsUI.formatRupiah(amt)
                    + "\n" + item + dateTag;

            addActionBtn(ctx, out, label, bgCard, txtPrimary, borderColor,
                    () -> showBusinessOrderActionImDialog(ctx, windowToken, prefs, origIdx, actions, parentDialog, refreshAll,
                            bgPanel, bgCard, bgBtn, bgAccent, bgPurple, bgGreen, bgAmber, bgDanger, txtPrimary, borderColor));
        }
    }

    private static void showBusinessOrderActionImDialog(final Context ctx, IBinder windowToken,
                                                        final SharedPreferences prefs, final int origIdx,
                                                        final Actions actions, final AlertDialog parentDialog,
                                                        final Runnable refreshAll,
                                                        int bgPanel, int bgCard, int bgBtn, int bgAccent,
                                                        int bgPurple, int bgGreen, int bgAmber, int bgDanger,
                                                        int txtPrimary, int borderColor) {
        final ArrayList<JSONObject> all = ExtraToolsUI.loadBusinessOrders(prefs);
        if (origIdx < 0 || origIdx >= all.size()) return;
        final JSONObject o = all.get(origIdx);
        final String receipt = ExtraToolsUI.formatOrderReceiptText(o);
        final boolean isLunas = "Lunas".equalsIgnoreCase(o.optString("status", ""));

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(bgPanel);
        box.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));

        TextView tv = new TextView(ctx);
        tv.setText(receipt);
        tv.setTextColor(txtPrimary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setBackground(roundBg(bgCard, borderColor, dp(ctx, 8)));
        tv.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
        box.addView(tv, rowMargin(ctx));

        final AlertDialog actDlg = new AlertDialog.Builder(ctx)
                .setTitle("Kelola Pesanan")
                .setView(box)
                .setNegativeButton("Kembali", null)
                .create();

        addActionBtn(ctx, box, "Sisipkan Nota Pesanan ke Kolom Ketik", bgAccent, Color.WHITE, borderColor, () -> {
            actions.commitSnippet(receipt);
            actDlg.dismiss();
            parentDialog.dismiss();
        });
        addActionBtn(ctx, box, "Bagikan Nota ke WA / TikTok / IG / Lainnya", bgGreen, Color.WHITE, borderColor, () -> {
            actDlg.dismiss();
            parentDialog.dismiss();
            ExtraToolsUI.sharePlainText(ctx, receipt, "Bagikan Nota Pesanan");
        });
        addActionBtn(ctx, box, "Salin Nota ke Papan Klip", bgBtn, txtPrimary, borderColor, () -> {
            copyToClipboard(ctx, receipt, "Nota pesanan disalin!");
            actDlg.dismiss();
        });
        addActionBtn(ctx, box, isLunas ? "Tandai Belum Lunas" : "Tandai Lunas", bgAmber, Color.WHITE, borderColor, () -> {
            try {
                o.put("status", isLunas ? "Menunggu / DP" : "Lunas");
                all.set(origIdx, o);
                ExtraToolsUI.saveBusinessOrders(prefs, all);
                actDlg.dismiss();
                refreshAll.run();
            } catch (Throwable ignored) {}
        });
        addActionBtn(ctx, box, "Bacakan Detail Pesanan (TTS)", bgPurple, Color.WHITE, borderColor,
                () -> actions.speakText(receipt));
        addActionBtn(ctx, box, "Hapus Pesanan Ini", bgDanger, Color.WHITE, borderColor, () -> {
            all.remove(origIdx);
            ExtraToolsUI.saveBusinessOrders(prefs, all);
            actDlg.dismiss();
            refreshAll.run();
            Toast.makeText(ctx, "Pesanan dihapus.", Toast.LENGTH_SHORT).show();
        });

        attachAndShowImDialog(ctx, windowToken, actDlg, 400f);
    }

    // ==================== TAB 4: KALKULATOR, TANGGAL & SIMBOL ====================
    private static void buildCalcSymbolsTab(final Context ctx, LinearLayout out, TextView pageCounter,
                                            Button btnPrev, Button btnNext,
                                            final Actions actions, final AlertDialog dialog,
                                            final StringBuilder expr, final int[] pageIdx,
                                            final Runnable refreshAll,
                                            int bgCard, int bgBtn, int bgAccent, int bgGreen, int bgAmber,
                                            int txtPrimary, int txtSecondary, int borderColor) {
        pageCounter.setText("Kalkulator Mini, Tanggal Otomatis & Simbol Populer");

        final TextView display = new TextView(ctx);
        display.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        display.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        display.setTextColor(txtPrimary);
        display.setBackground(roundBg(bgCard, borderColor, dp(ctx, 8)));
        display.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        final Runnable updateCalc = new Runnable() {
            @Override public void run() {
                if (expr.length() == 0) display.setText("0  (= 0)");
                else {
                    String res = evaluateMathExpression(expr.toString());
                    display.setText(expr.toString() + "  = " + (res != null ? res : "..."));
                }
            }
        };
        updateCalc.run();
        out.addView(display, rowMargin(ctx));

        String[][] rows = {
            {"7", "8", "9", "/", "Hapus"},
            {"4", "5", "6", "*", "("},
            {"1", "2", "3", "-", ")"},
            {"Bersih", "0", ".", "+", "%"}
        };
        for (String[] row : rows) {
            LinearLayout r = new LinearLayout(ctx);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (final String key : row) {
                int kBg = ("Bersih".equals(key) || "Hapus".equals(key)) ? bgAmber : bgBtn;
                int kTxt = ("Bersih".equals(key) || "Hapus".equals(key)) ? Color.WHITE : txtPrimary;
                addFlexBtn(ctx, r, key, kBg, kTxt, borderColor, "Tombol kalkulator " + key, v -> {
                    if ("Bersih".equals(key)) expr.setLength(0);
                    else if ("Hapus".equals(key)) { if (expr.length() > 0) expr.deleteCharAt(expr.length() - 1); }
                    else expr.append(key);
                    updateCalc.run();
                });
            }
            out.addView(r, rowMargin(ctx));
        }

        LinearLayout insertRow = new LinearLayout(ctx);
        insertRow.setOrientation(LinearLayout.HORIZONTAL);
        addFlexBtn(ctx, insertRow, "Sisip Hasil", bgGreen, Color.WHITE, borderColor, "Sisipkan hasil hitungan",
                v -> {
                    String res = evaluateMathExpression(expr.toString());
                    if (res != null) { actions.commitSnippet(res); dialog.dismiss(); }
                });
        addFlexBtn(ctx, insertRow, "Sisip Rumus = Hasil", bgAccent, Color.WHITE, borderColor, "Sisipkan rumus beserta hasilnya",
                v -> {
                    String res = evaluateMathExpression(expr.toString());
                    if (res != null) { actions.commitSnippet(expr.toString() + " = " + res); dialog.dismiss(); }
                });
        out.addView(insertRow, rowMargin(ctx));

        addSectionTitle(ctx, out, "Tanggal, Jam & Simbol", txtSecondary);
        addActionBtn(ctx, out, "Sisipkan Tanggal Hari Ini", bgCard, txtPrimary, borderColor, () -> {
            actions.commitSnippet(new SimpleDateFormat("dd MMMM yyyy", new Locale("id", "ID")).format(new Date()));
            dialog.dismiss();
        });
        addActionBtn(ctx, out, "Sisipkan Jam Sekarang (WIB)", bgCard, txtPrimary, borderColor, () -> {
            actions.commitSnippet(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()) + " WIB");
            dialog.dismiss();
        });
        addActionBtn(ctx, out, "Sisipkan Tanggal Hijriah Hari Ini", bgCard, txtPrimary, borderColor, () -> {
            actions.commitSnippet(ExtraToolsUI.getTodayHijriDate());
            dialog.dismiss();
        });

        String[] syms = {"Rp ", "✓", "✗", "★", "→", "←", "±", "×", "÷", "°", "•", "—", "²", "©", "™", "≈", "≠", "≤", "≥", "∞"};
        final int symPageSize = 10;
        final int totalSymPages = 2;
        if (pageIdx[0] >= totalSymPages) pageIdx[0] = 0;
        btnPrev.setVisibility(View.VISIBLE);
        btnNext.setVisibility(View.VISIBLE);
        btnPrev.setEnabled(pageIdx[0] > 0);
        btnNext.setEnabled(pageIdx[0] + 1 < totalSymPages);
        btnPrev.setOnClickListener(v -> { if (pageIdx[0] > 0) { pageIdx[0]--; refreshAll.run(); } });
        btnNext.setOnClickListener(v -> { if (pageIdx[0] + 1 < totalSymPages) { pageIdx[0]++; refreshAll.run(); } });

        int start = pageIdx[0] * symPageSize;
        int end = Math.min(syms.length, start + symPageSize);
        LinearLayout symRow = new LinearLayout(ctx);
        symRow.setOrientation(LinearLayout.HORIZONTAL);
        int col = 0;
        for (int i = start; i < end; i++) {
            final String sym = syms[i];
            if (col > 0 && col % 5 == 0) {
                out.addView(symRow, rowMargin(ctx));
                symRow = new LinearLayout(ctx);
                symRow.setOrientation(LinearLayout.HORIZONTAL);
            }
            addFlexBtn(ctx, symRow, sym.trim(), bgBtn, txtPrimary, borderColor, "Sisipkan simbol " + sym,
                    v -> { actions.commitSnippet(sym); dialog.dismiss(); });
            col++;
        }
        out.addView(symRow, rowMargin(ctx));
    }

    // ==================== TAB 5: IDENTITAS & TEMPLATE ====================
    private static void buildIdentitySnippetsTab(Context ctx, SharedPreferences prefs, LinearLayout out,
                                                 TextView pageCounter, Button btnPrev, Button btnNext,
                                                 final Actions actions, final AlertDialog dialog,
                                                 final int[] pageIdx, final Runnable refreshAll,
                                                 int bgCard, int bgBtn, int bgAccent, int bgGreen,
                                                 int txtPrimary, int txtSecondary, int borderColor) {
        addSectionTitle(ctx, out, "Brankas Identitas Cepat (1-Ketuk Sisip)", txtSecondary);
        String[][] fields = {
            {"Nama", "id_nama"},
            {"No. HP / WA", "id_hp"},
            {"Email", "id_email"},
            {"Alamat", "id_alamat"},
            {"Rekening / E-Wallet", "id_rekening"},
            {"Catatan Pribadi", "id_catatan"}
        };
        boolean hasAnyId = false;
        for (String[] f : fields) {
            final String val = prefs != null ? prefs.getString(f[1], "").trim() : "";
            if (!val.isEmpty()) {
                hasAnyId = true;
                addActionBtn(ctx, out, f[0] + ": " + val, bgGreen, Color.WHITE, borderColor, () -> {
                    actions.commitSnippet(val);
                    dialog.dismiss();
                });
            }
        }
        if (!hasAnyId) {
            addActionBtn(ctx, out, "Isi Data Identitas di Aplikasi Utama (Menu 7)", bgAccent, Color.WHITE, borderColor, () -> {
                dialog.dismiss();
                actions.openMainApp();
            });
        }

        addSectionTitle(ctx, out, "Template & Salam Cepat", txtSecondary);
        JSONArray snippets = loadOrDefaultSnippets(prefs);
        int total = snippets.length();
        final int pageSize = 8;
        final int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
        if (pageIdx[0] >= totalPages) pageIdx[0] = totalPages - 1;
        if (pageIdx[0] < 0) pageIdx[0] = 0;

        pageCounter.setText("Identitas & Template • Halaman " + (pageIdx[0] + 1) + " dari " + totalPages + " (" + total + " template)");
        if (totalPages > 1) {
            btnPrev.setVisibility(View.VISIBLE);
            btnNext.setVisibility(View.VISIBLE);
            btnPrev.setEnabled(pageIdx[0] > 0);
            btnNext.setEnabled(pageIdx[0] + 1 < totalPages);
            btnPrev.setOnClickListener(v -> { if (pageIdx[0] > 0) { pageIdx[0]--; refreshAll.run(); } });
            btnNext.setOnClickListener(v -> { if (pageIdx[0] + 1 < totalPages) { pageIdx[0]++; refreshAll.run(); } });
        }

        int from = pageIdx[0] * pageSize;
        int to = Math.min(total, from + pageSize);
        for (int i = from; i < to; i++) {
            final String s = snippets.optString(i, "").trim();
            if (!s.isEmpty()) {
                addActionBtn(ctx, out, "Template: " + s, bgCard, txtPrimary, borderColor, () -> {
                    actions.commitSnippet(s);
                    dialog.dismiss();
                });
            }
        }
    }

    // ==================== TAB 6: PAPAN KLIP ====================
    private static void buildClipboardTab(Context ctx, SharedPreferences prefs, ArrayList<String> recentClips,
                                          LinearLayout out, TextView pageCounter, Button btnPrev, Button btnNext,
                                          final Actions actions, final AlertDialog dialog,
                                          final int[] pageIdx, final Runnable refreshAll,
                                          int bgCard, int bgBtn, int bgDanger,
                                          int txtPrimary, int txtSecondary, int borderColor) {
        addSectionTitle(ctx, out, "Salinan Disematkan (Pin)", txtSecondary);
        ArrayList<String> pinned = new ArrayList<>();
        if (prefs != null) {
            try {
                JSONArray arr = new JSONArray(prefs.getString("pinned_clipboard_json", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "");
                    if (!s.isEmpty()) pinned.add(s);
                }
            } catch (Exception ignored) {}
        }
        if (pinned.isEmpty()) {
            TextView emptyPin = new TextView(ctx);
            emptyPin.setText("Belum ada klip yang di-pin (bisa di-pin di Aplikasi Utama -> Menu 3).");
            emptyPin.setTextColor(txtSecondary);
            emptyPin.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            out.addView(emptyPin, rowMargin(ctx));
        } else {
            for (final String p : pinned) {
                addActionBtn(ctx, out, "Pin Klip: " + p, bgCard, txtPrimary, borderColor, () -> {
                    actions.commitSnippet(p);
                    dialog.dismiss();
                });
            }
        }

        addSectionTitle(ctx, out, "Riwayat Salinan Terbaru", txtSecondary);
        addActionBtn(ctx, out, "Bersihkan Riwayat Salinan", bgDanger, Color.WHITE, borderColor, () -> {
            actions.clearNonPinnedClips();
            dialog.dismiss();
        });

        int total = recentClips != null ? recentClips.size() : 0;
        if (total == 0) {
            pageCounter.setText("Papan Klip • 0 riwayat terbaru");
            TextView empty = new TextView(ctx);
            empty.setText("Riwayat salinan masih kosong.");
            empty.setTextColor(txtSecondary);
            out.addView(empty);
            return;
        }

        final int pageSize = 8;
        final int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
        if (pageIdx[0] >= totalPages) pageIdx[0] = totalPages - 1;
        if (pageIdx[0] < 0) pageIdx[0] = 0;
        pageCounter.setText("Papan Klip • Halaman " + (pageIdx[0] + 1) + " dari " + totalPages + " (" + total + " item)");

        if (totalPages > 1) {
            btnPrev.setVisibility(View.VISIBLE);
            btnNext.setVisibility(View.VISIBLE);
            btnPrev.setEnabled(pageIdx[0] > 0);
            btnNext.setEnabled(pageIdx[0] + 1 < totalPages);
            btnPrev.setOnClickListener(v -> { if (pageIdx[0] > 0) { pageIdx[0]--; refreshAll.run(); } });
            btnNext.setOnClickListener(v -> { if (pageIdx[0] + 1 < totalPages) { pageIdx[0]++; refreshAll.run(); } });
        }

        int from = pageIdx[0] * pageSize;
        int to = Math.min(total, from + pageSize);
        for (int i = from; i < to; i++) {
            final String c = recentClips.get(i);
            if (c != null && !c.isEmpty()) {
                addActionBtn(ctx, out, "Salinan: " + c, bgBtn, txtPrimary, borderColor, () -> {
                    actions.commitSnippet(c);
                    dialog.dismiss();
                });
            }
        }
    }

    // ==================== HELPERS ====================
    public static JSONArray loadOrDefaultSnippets(SharedPreferences prefs) {
        JSONArray out = new JSONArray();
        if (prefs != null) {
            try {
                JSONArray arr = new JSONArray(prefs.getString("json_quick_snippets", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "").trim();
                    if (!s.isEmpty()) out.put(s);
                }
            } catch (Exception ignored) {}
        }
        if (out.length() == 0) {
            out.put("Assalamu'alaikum warahmatullahi wabarakatuh.");
            out.put("Wa'alaikumussalam warahmatullahi wabarakatuh.");
            out.put("Terima kasih banyak, mohon ditunggu sebentar ya.");
            out.put("Maaf baru sempat membalas pesan Anda.");
            out.put("Siap, aman dan langsung dikerjakan.");
        }
        return out;
    }

    private static void copyToClipboard(Context ctx, String text, String msg) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("AnasKeyboard", text));
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    private static void addActionBtn(Context ctx, LinearLayout out, String label, int bgColor, int txtColor,
                                     int borderColor, final Runnable onTap) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setMaxLines(3);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setTextColor(txtColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTypeface(null, Typeface.BOLD);
        b.setBackground(roundBg(bgColor, borderColor, dp(ctx, 8)));
        b.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        b.setMinimumHeight(dp(ctx, 40));
        b.setMinHeight(dp(ctx, 40));
        b.setContentDescription(label);
        b.setOnClickListener(v -> onTap.run());
        out.addView(b, rowMargin(ctx));
    }

    private static void addSectionTitle(Context ctx, LinearLayout out, String text, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 6);
        lp.bottomMargin = dp(ctx, 4);
        out.addView(tv, lp);
    }

    private static void addFlexBtn(Context ctx, LinearLayout row, String label, int bgColor, int txtColor,
                                   int borderColor, String cd, View.OnClickListener l) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setTextColor(txtColor);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTypeface(null, Typeface.BOLD);
        b.setBackground(roundBg(bgColor, borderColor, dp(ctx, 8)));
        b.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));
        b.setMinimumHeight(dp(ctx, 38));
        b.setMinHeight(dp(ctx, 38));
        b.setContentDescription(cd);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(ctx, 4);
        row.addView(b, lp);
    }

    private static Button makeNavBtn(Context ctx, String text, int bg, int fg) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setTypeface(null, Typeface.BOLD);
        b.setBackground(roundBg(bg, Color.TRANSPARENT, dp(ctx, 8)));
        b.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));
        b.setMinHeight(dp(ctx, 38));
        return b;
    }

    private static Button makePillBtn(Context ctx, String text, int bg, int fg, int sp) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTypeface(null, Typeface.BOLD);
        b.setBackground(roundBg(bg, Color.TRANSPARENT, dp(ctx, 14)));
        b.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
        b.setMinHeight(dp(ctx, 32));
        return b;
    }

    private static LinearLayout.LayoutParams flexLp(Context ctx, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(ctx, rightDp);
        return lp;
    }

    private static LinearLayout.LayoutParams rowMargin(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 6);
        return lp;
    }

    private static GradientDrawable roundBg(int fill, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (stroke != Color.TRANSPARENT) d.setStroke(1, stroke);
        return d;
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    /** Evaluasi ekspresi aritmetika (+ - * / % ^ kurung). Null jika bukan ekspresi valid. */
    public static String evaluateMathExpression(String expr) {
        if (expr == null) return null;
        String clean = expr.replaceAll("\\s+", "").replace("×", "*").replace("÷", "/").replace(",", ".");
        if (clean.isEmpty() || !clean.matches("[0-9.+\\-*/()%^]+")) return null;
        try {
            double val = new MathParser(clean).parse();
            if (Double.isNaN(val) || Double.isInfinite(val)) return null;
            if (Math.abs(val - Math.rint(val)) < 1e-9 && Math.abs(val) < 1e15) {
                return String.valueOf((long) Math.rint(val));
            }
            return String.format(Locale.US, "%.6f", val).replaceAll("0+$", "").replaceAll("\\.$", "");
        } catch (Exception e) {
            return null;
        }
    }

    private static final class MathParser {
        private final String s;
        private int pos = -1, ch;
        MathParser(String s) { this.s = s; }
        void nextChar() { ch = (++pos < s.length()) ? s.charAt(pos) : -1; }
        boolean eat(int charToEat) {
            while (ch == ' ') nextChar();
            if (ch == charToEat) { nextChar(); return true; }
            return false;
        }
        double parse() {
            nextChar();
            double x = parseExpression();
            if (pos < s.length()) throw new RuntimeException("Unexpected: " + (char) ch);
            return x;
        }
        double parseExpression() {
            double x = parseTerm();
            for (;;) {
                if (eat('+')) x += parseTerm();
                else if (eat('-')) x -= parseTerm();
                else return x;
            }
        }
        double parseTerm() {
            double x = parseFactor();
            for (;;) {
                if (eat('*')) x *= parseFactor();
                else if (eat('/')) x /= parseFactor();
                else if (eat('%')) x %= parseFactor();
                else return x;
            }
        }
        double parseFactor() {
            if (eat('+')) return parseFactor();
            if (eat('-')) return -parseFactor();
            double x;
            int startPos = this.pos;
            if (eat('(')) {
                x = parseExpression();
                if (!eat(')')) throw new RuntimeException("Missing )");
            } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                x = Double.parseDouble(s.substring(startPos, this.pos));
            } else {
                throw new RuntimeException("Unexpected");
            }
            if (eat('^')) x = Math.pow(x, parseFactor());
            return x;
        }
    }
}
