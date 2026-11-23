package com.annas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asisten AI Premium terpadu untuk Microsoft Word Pro, Excel Pro, PowerPoint Pro, & PDF Reader.
 * - Khusus Word Pro: seluruh output AI dibersihkan otomatis dari sintaks Markdown (**, ###, *, ```, ---)
 *   maupun kalimat basa-basi pembuka/penutup ("Berikut adalah...", "Semoga bermanfaat...", dll).
 */
public final class OfficeAiHelper {

    public interface TextResultAction {
        void apply(String text, boolean replaceAll);
    }

    public interface JsonResultAction {
        void apply(String rawText);
    }

    private OfficeAiHelper() {}

    private static final String WORD_PLAIN_RULE =
            " ATURAN MUTLAK FORMAT KELUARAN: Keluarkan HANYA teks dokumen polos yang bersih dan langsung siap tempel ke Microsoft Word. "
                    + "DILARANG KERAS memakai sintaks Markdown (jangan tulis **, __, *, ###, ##, #, ```, `, >, atau garis ---). "
                    + "Untuk daftar berpoin gunakan angka (1., 2.) atau tanda titik bulat (• ). "
                    + "DILARANG KERAS menulis kalimat pengantar atau penutup AI seperti 'Berikut adalah...', 'Tentu...', 'Baik, ini...', 'Semoga membantu...', atau catatan tambahan apa pun.";

    /**
     * Menghasilkan konteks waktu real-time (Hari, Tanggal, Bulan, Tahun) beserta aturan cerdas:
     * hanya menyertakan tanggal/tahun pada dokumen yang memang membutuhkan penanggalan.
     */
    public static String buildRealtimeDateInstruction() {
        java.util.Date now = new java.util.Date();
        Locale id = new Locale("id", "ID");
        String hari = new java.text.SimpleDateFormat("EEEE", id).format(now);
        String tanggalLengkap = new java.text.SimpleDateFormat("dd MMMM yyyy", id).format(now);
        String tahun = new java.text.SimpleDateFormat("yyyy", id).format(now);
        String jam = new java.text.SimpleDateFormat("HH:mm", id).format(now);
        return " KONTEKS KALENDER REAL-TIME SAAT INI: Hari ini adalah hari " + hari
                + ", tanggal " + tanggalLengkap + " (Tahun " + tahun + ", pukul " + jam + "). "
                + "ATURAN PENANGGALAN CERDAS: "
                + "(1) Jika dokumen yang ditulis adalah dokumen resmi, surat-menyurat, akademik, atau bisnis yang lazim memakai tanggal/tahun "
                + "(seperti Surat Resmi, Undangan, Surat Izin, Lamaran Kerja, Surat Perjanjian, Berita Acara, Notulen Rapat, Laporan, Proposal, "
                + "Cover/Kata Pengantar Makalah atau Skripsi, Invoice/Kwitansi, atau CV), OTOMATIS gunakan hari, tanggal, bulan, dan tahun real-time tersebut "
                + "(serta hitung otomatis jika user menyebut 'besok', 'lusa', atau 'minggu depan' dari tanggal " + tanggalLengkap + ") "
                + "tanpa pernah menulis placeholder kosong seperti [Tanggal] atau [Tahun]. "
                + "(2) Jika dokumen yang ditulis adalah naskah yang TIDAK memakai tanggal/tahun "
                + "(seperti Novel, Cerpen, Puisi, Esai/Artikel umum, Materi bacaan, Jawaban soal biasa, atau lanjutan paragraf), "
                + "JANGAN menyisipkan tanggal atau tahun kecuali diminta secara eksplisit oleh pengguna.";
    }

    public static void showWordAiDialog(final Activity act, final SharedPreferences prefs,
                                        final String currentDocText, final TextResultAction onApply) {
        if (!AuthManager.isPremiumActive(act)) {
            AuthManager.showPremiumRequiredDialog(act, "AI Microsoft Word Pro", null);
            return;
        }
        final String[] modes = {
                "Buat Draf Dokumen Baru (Surat, Proposal, Laporan, Artikel, Novel)",
                "Lanjutkan / Kembangkan Isi Dokumen Ini",
                "Rapikan Ejaan, Tata Bahasa & Struktur Dokumen Ini",
                "Ubah Dokumen Ini Jadi Resmi / Formal Bisnis",
                "Ringkas Dokumen Ini Jadi Poin Penting",
                "Terjemahkan Dokumen Ini"
        };
        UiKit.dialog(act, OfficeUtils.mainPrefs(act))
                .setTitle("Asisten AI — Word Pro")
                .setItems(modes, (d, which) -> promptWithInput(act, prefs, modes[which],
                        which == 0
                                ? "Tulis jenis dokumen, topik, penerima/tujuan, atau poin yang ingin dibuat:"
                                : (which == 5
                                        ? "Masukkan bahasa tujuan (misal: Bahasa Inggris, Arab, Jepang) & catatan:"
                                        : "Tambahkan instruksi khusus (opsional, boleh kosong):"),
                        extra -> {
                            String sys;
                            String user;
                            String clip = currentDocText == null ? "" : currentDocText;
                            if (clip.length() > 14000) clip = clip.substring(0, 14000);

                            String dateRule = buildRealtimeDateInstruction();
                            if (which == 0) {
                                sys = "Kamu penulis dokumen profesional berbahasa Indonesia." + WORD_PLAIN_RULE + dateRule;
                                user = extra.isEmpty() ? "Buatkan contoh surat resmi yang rapi." : extra;
                            } else if (which == 1) {
                                sys = "Kamu asisten penulis yang melanjutkan dokumen secara mulus sesuai gaya bahasa dokumen yang sudah ada. Keluarkan HANYA bagian lanjutan tambahannya saja tanpa mengulang isi lama."
                                        + WORD_PLAIN_RULE + dateRule;
                                user = "Lanjutkan isi dokumen berikut" + (extra.isEmpty() ? "." : " dengan arahan: " + extra)
                                        + "\n\nTeks Dokumen Saat Ini:\n" + clip;
                            } else {
                                sys = "Kamu editor dokumen profesional. Ikuti perintah user dan kembalikan hasil akhir dokumen lengkap yang rapi."
                                        + WORD_PLAIN_RULE + dateRule;
                                user = "Tugas: " + modes[which]
                                        + (extra.isEmpty() ? "" : "\nInstruksi tambahan: " + extra)
                                        + "\n\nIsi Dokumen:\n" + clip;
                            }

                            runAndPreviewWord(act, prefs, "Hasil AI Word Pro", sys, user, result -> {
                                final String clean = cleanWordDocumentOutput(result);
                                if (which == 0 || which == 1) {
                                    UiKit.dialog(act, OfficeUtils.mainPrefs(act))
                                            .setTitle("Terapkan ke Word")
                                            .setMessage("Sisipkan di posisi kursor atau ganti seluruh isi dokumen?")
                                            .setPositiveButton("Sisipkan di Kursor", (d2, w2) -> onApply.apply(clean, false))
                                            .setNeutralButton("Ganti Semua", (d2, w2) -> onApply.apply(clean, true))
                                            .setNegativeButton("Batal", null)
                                            .show();
                                } else {
                                    onApply.apply(clean, true);
                                }
                            });
                        }))
                .show();
    }

    /**
     * Membersihkan keluaran AI agar bebas sintaks Markdown dan kalimat basa-basi AI
     * sebelum ditampilkan atau dimasukkan ke dokumen Word.
     */
    public static String cleanWordDocumentOutput(String raw) {
        if (raw == null) return "";
        String t = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        t = stripCodeFence(t);

        // Hapus basa-basi pembuka AI hingga 3 baris awal jika cocok
        Pattern preamblePat = Pattern.compile(
                "(?i)^(?:tentu(?:nya)?|baik(?:lah)?|siap|oke|berikut(?:\\s+(?:adalah|ini))?|ini(?:\\s+adalah)?|"
                        + "sure|certainly|here\\s+(?:is|are)|below\\s+(?:is|are))\\b[^\\n]{0,140}(?::|\\.\\.\\.|!)\\s*$");
        for (int pass = 0; pass < 3; pass++) {
            String[] lines = t.split("\n", 2);
            String first = lines[0].trim();
            if (first.isEmpty() && lines.length > 1) {
                t = lines[1].trim();
                continue;
            }
            if (lines.length > 1 && (preamblePat.matcher(first).matches()
                    || (first.toLowerCase(Locale.ROOT).startsWith("berikut") && first.endsWith(":")))) {
                t = lines[1].trim();
            } else {
                break;
            }
        }

        // Hapus basa-basi penutup AI di akhir dokumen
        Pattern outroPat = Pattern.compile(
                "(?i)^(?:semoga\\s+(?:bermanfaat|membantu)|silakan\\s+(?:sesuaikan|beri\\s+tahu|kabari)|"
                        + "jika\\s+(?:ada|ingin|butuh)\\s+[^\\n]{0,120}|catatan\\s*:|note\\s*:|hope\\s+this\\s+helps)\\b.*$");
        String[] rawLines = t.split("\n", -1);
        int endIdx = rawLines.length;
        while (endIdx > 1) {
            String last = rawLines[endIdx - 1].trim();
            if (last.isEmpty()) {
                endIdx--;
                continue;
            }
            if (outroPat.matcher(last).matches()) {
                endIdx--;
            } else {
                break;
            }
        }

        List<String> cleanedLines = new ArrayList<>();
        Pattern mdHeading = Pattern.compile("^\\s{0,3}#{1,6}\\s+");
        Pattern mdQuote = Pattern.compile("^\\s{0,3}>\\s?");
        Pattern mdHr = Pattern.compile("^\\s{0,3}(?:[-*_]\\s*){3,}$");
        Pattern mdBullet = Pattern.compile("^(\\s*)[*+]\\s+");
        Pattern mdDashBullet = Pattern.compile("^(\\s*)-\\s+");
        Pattern mdTableSep = Pattern.compile("^\\s*\\|?(?:\\s*:?-{2,}:?\\s*\\|)+\\s*:?-{2,}:?\\s*\\|?\\s*$");
        Pattern mdLink = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)");

        for (int i = 0; i < endIdx; i++) {
            String line = rawLines[i];
            String trimmed = line.trim();
            if ("```".equals(trimmed) || trimmed.startsWith("```")) continue;
            if (mdHr.matcher(trimmed).matches()) continue;
            if (mdTableSep.matcher(trimmed).matches()) continue;

            // Rapikan baris tabel markdown: | Kolom 1 | Kolom 2 | -> Kolom 1 | Kolom 2
            if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 2) {
                line = trimmed.substring(1, trimmed.length() - 1).replaceAll("\\s*\\|\\s*", "  |  ").trim();
            }

            line = mdHeading.matcher(line).replaceFirst("");
            line = mdQuote.matcher(line).replaceFirst("");
            line = mdBullet.matcher(line).replaceFirst("$1• ");
            line = mdDashBullet.matcher(line).replaceFirst("$1• ");
            line = mdLink.matcher(line).replaceAll("$1 ($2)");

            // Bersihkan format bold/italic/code markdown tanpa merusak teks biasa
            line = line.replace("`", "");
            line = line.replaceAll("\\*\\*([^*\\n]+)\\*\\*", "$1");
            line = line.replaceAll("__([^_\\n]+)__", "$1");
            line = line.replaceAll("~~([^~\\n]+)~~", "$1");
            line = line.replaceAll("(?<!\\w)\\*([^\\s*][^*\\n]*?[^\\s*])\\*(?!\\w)", "$1");
            line = line.replaceAll("(?<!\\w)\\*([^\\s*])\\*(?!\\w)", "$1");
            line = line.replaceAll("(?<!\\w)_([^\\s_][^_\\n]*?[^\\s_])_(?!\\w)", "$1");
            // Bersihkan sisa simbol ** atau ### yang mungkin yatim
            line = line.replace("**", "");
            cleanedLines.add(line);
        }

        StringBuilder out = new StringBuilder();
        int blankRun = 0;
        for (String l : cleanedLines) {
            if (l.trim().isEmpty()) {
                blankRun++;
                if (blankRun <= 1 && out.length() > 0) out.append('\n');
            } else {
                blankRun = 0;
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
                out.append(l);
            }
        }
        return out.toString().trim();
    }

    public static void showExcelAiDialog(final Activity act, final SharedPreferences prefs,
                                         final String sheetCsvPreview, final JsonResultAction onApplyCsv) {
        if (!AuthManager.isPremiumActive(act)) {
            AuthManager.showPremiumRequiredDialog(act, "AI Excel & Spreadsheet Pro", null);
            return;
        }
        final String[] modes = {
                "Buat Tabel + Rumus Excel Otomatis dari Deskripsi",
                "Analisis & Ringkas Isi Tabel Saat Ini",
                "Buatkan / Jelaskan Rumus Excel"
        };
        UiKit.dialog(act, OfficeUtils.mainPrefs(act))
                .setTitle("Asisten AI — Excel Pro")
                .setItems(modes, (d, which) -> promptWithInput(act, prefs, modes[which],
                        which == 0 ? "Contoh: Tabel pengeluaran bulanan 10 baris dengan kolom Tanggal, Item, Kategori, Nominal, dan baris Total pakai rumus =SUM(...)"
                                : "Tulis pertanyaan atau rumus yang diinginkan:",
                        extra -> {
                            if (which == 0) {
                                String sys = "Kamu pembuat spreadsheet Excel profesional. Keluarkan HANYA data format CSV murni (pisahkan kolom dengan koma ',', baris baru dengan Enter). Gunakan rumus Excel standar seperti =SUM(D2:D10), =AVERAGE(...), =IF(...) bila perlu. Tanpa penjelasan dan tanpa tanda ```.";
                                runAndPreview(act, prefs, "Pratinjau Tabel CSV", sys, extra, result -> {
                                    String clean = stripCodeFence(result);
                                    onApplyCsv.apply(clean);
                                });
                            } else {
                                String sys = "Kamu analis data Excel profesional. Jawab padat, jelas, tanpa format markdown berlebihan, dan langsung sebutkan sel/rumus yang tepat.";
                                String user = extra + "\n\nData Sheet Saat Ini (CSV):\n" + sheetCsvPreview;
                                runAndPreview(act, prefs, "Analisis AI Excel", sys, user, null);
                            }
                        }))
                .show();
    }

    public static void showPptAiDialog(final Activity act, final SharedPreferences prefs,
                                       final JsonResultAction onApplySlidesJson) {
        if (!AuthManager.isPremiumActive(act)) {
            AuthManager.showPremiumRequiredDialog(act, "AI PowerPoint Pro", null);
            return;
        }
        promptWithInput(act, prefs, "Buat Slide Presentasi AI",
                "Masukkan topik, target audiens, & jumlah slide (misal: Presentasi Bisnis Kopi Aceh, 6 slide):",
                topic -> {
                    if (topic.isEmpty()) return;
                    String sys = "Kamu pembuat materi PowerPoint profesional. Keluarkan HANYA JSON valid berbentuk array: "
                            + "[{\"title\":\"Judul Slide\",\"body\":\"• Poin 1\\n• Poin 2\",\"notes\":\"Catatan pembicara\",\"layout\":1}]. "
                            + "layout: 0=Sampul, 1=Judul+Poin, 2=Dua Kolom (pisah isi dengan ---), 3=Kesimpulan. Jangan gunakan sintaks markdown ** atau ### di dalam isi slide. Tanpa teks lain di luar JSON.";
                    runAndPreview(act, prefs, "Kerangka Slide AI", sys, topic, result -> {
                        String clean = stripCodeFence(result);
                        try {
                            new JSONArray(clean);
                            onApplySlidesJson.apply(clean);
                        } catch (Exception e) {
                            Toast.makeText(act, "Format JSON slide kurang tepat, hasil disalin ke Clipboard.", Toast.LENGTH_LONG).show();
                            copyText(act, clean);
                        }
                    });
                });
    }

    public static void showPdfAiDialog(final Activity act, final SharedPreferences prefs,
                                       final String currentPageText, final String docTitle,
                                       final int currentPageNum, final int totalPages) {
        if (!AuthManager.isPremiumActive(act)) {
            AuthManager.showPremiumRequiredDialog(act, "AI Bedah PDF", null);
            return;
        }
        if (currentPageText == null || currentPageText.trim().isEmpty()) {
            Toast.makeText(act, "Teks halaman PDF masih kosong. Tunggu OCR selesai atau pindah ke halaman berisi teks.", Toast.LENGTH_LONG).show();
            return;
        }
        final String[] modes = {
                "Ringkas Halaman Ini (" + currentPageNum + "/" + totalPages + ")",
                "Jelaskan Isi Halaman dengan Bahasa Sederhana",
                "Terjemahkan Halaman Ini ke Bahasa Indonesia",
                "Tanya Jawab Spesifik tentang Halaman Ini"
        };
        UiKit.dialog(act, OfficeUtils.mainPrefs(act))
                .setTitle("AI Bedah PDF — " + docTitle)
                .setItems(modes, (d, which) -> {
                    if (which == 3) {
                        promptWithInput(act, prefs, "Tanya AI tentang PDF", "Tulis pertanyaan lu:", q -> {
                            if (q.isEmpty()) return;
                            String clip = currentPageText.length() > 14000 ? currentPageText.substring(0, 14000) : currentPageText;
                            runAndPreviewWord(act, prefs, "Jawaban AI PDF",
                                    "Jawab pertanyaan berdasarkan teks PDF berikut secara akurat dan ringkas tanpa format Markdown (** atau ###).",
                                    "Pertanyaan: " + q + "\n\nTeks PDF Halaman " + currentPageNum + ":\n" + clip, null);
                        });
                    } else {
                        String task = modes[which];
                        String clip = currentPageText.length() > 14000 ? currentPageText.substring(0, 14000) : currentPageText;
                        runAndPreviewWord(act, prefs, task,
                                "Kamu asisten pembaca dokumen PDF yang jelas, terstruktur, ramah dibacakan TTS, dan tanpa sintaks Markdown.",
                                "Tugas: " + task + "\n\nTeks PDF Halaman " + currentPageNum + ":\n" + clip, null);
                    }
                })
                .show();
    }

    private interface StrCb { void onOk(String val); }

    private static void promptWithInput(Activity act, SharedPreferences prefs, String title,
                                        String hint, final StrCb cb) {
        final EditText et = new EditText(act);
        et.setHint(hint);
        et.setMinLines(3);
        UiKit.dialog(act, OfficeUtils.mainPrefs(act))
                .setTitle(title)
                .setView(et)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Proses AI", (d, w) -> cb.onOk(et.getText().toString().trim()))
                .show();
    }

    private static void runAndPreviewWord(final Activity act, final SharedPreferences prefs,
                                          final String title, final String sys, final String user,
                                          final JsonResultAction onApplyOptional) {
        runAndPreviewInternal(act, prefs, title, sys, user, true, onApplyOptional);
    }

    private static void runAndPreview(final Activity act, final SharedPreferences prefs,
                                      final String title, final String sys, final String user,
                                      final JsonResultAction onApplyOptional) {
        runAndPreviewInternal(act, prefs, title, sys, user, false, onApplyOptional);
    }

    private static void runAndPreviewInternal(final Activity act, final SharedPreferences prefs,
                                              final String title, final String sys, final String user,
                                              final boolean sanitizeWordMarkdown,
                                              final JsonResultAction onApplyOptional) {
        final AlertDialog waitDlg = UiKit.dialog(act, OfficeUtils.mainPrefs(act))
                .setTitle(title)
                .setMessage("AI sedang memproses (" + AiEngine.getActiveProvider(prefs) + ")...")
                .setCancelable(false)
                .create();
        waitDlg.show();

        AiEngine.singlePrompt(act, prefs, sys, user, new AiEngine.TextCallback() {
            @Override public void onSuccess(String rawText) {
                try { waitDlg.dismiss(); } catch (Throwable ignored) {}
                if (act.isFinishing()) return;

                final String displayText = sanitizeWordMarkdown ? cleanWordDocumentOutput(rawText) : rawText;

                ScrollView sv = new ScrollView(act);
                TextView tv = new TextView(act);
                tv.setText(displayText);
                tv.setTextSize(14);
                tv.setTextIsSelectable(true);
                int p = UiKit.dp(act, 14);
                tv.setPadding(p, p, p, p);
                sv.addView(tv);

                AlertDialog.Builder b = UiKit.dialog(act, prefs)
                        .setTitle(title)
                        .setView(sv)
                        .setNeutralButton("Salin", (d, w) -> copyText(act, displayText));
                if (onApplyOptional != null) {
                    b.setPositiveButton("Terapkan ke Dokumen", (d, w) -> onApplyOptional.apply(displayText));
                }
                b.show();
            }

            @Override public void onError(String err) {
                try { waitDlg.dismiss(); } catch (Throwable ignored) {}
                if (act.isFinishing()) return;
                UiKit.dialog(act, prefs)
                        .setTitle("Gagal AI")
                        .setMessage(err)
                        .show();
            }
        });
    }

    public static String stripCodeFence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static void copyText(Context ctx, String txt) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("AI Office", txt));
            Toast.makeText(ctx, "Hasil disalin ke Clipboard!", Toast.LENGTH_SHORT).show();
        }
    }
}
