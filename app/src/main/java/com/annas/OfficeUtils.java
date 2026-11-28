package com.annas;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Helper terpadu untuk seluruh modul Office (Word, Excel, PowerPoint):
 * XML/ZIP safe utilities, Share exporter aman via ExportFileProvider, serta
 * komponen tombol ramah pembaca layar TalkBack/Jieshuo.
 */
public final class OfficeUtils {

    private OfficeUtils() {}

    public static String xmlEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') out.append("&amp;");
            else if (c == '<') out.append("&lt;");
            else if (c == '>') out.append("&gt;");
            else if (c == '"') out.append("&quot;");
            else if (c == '\'') out.append("&apos;");
            else if (c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    public static String xmlUnescape(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#10;", "\n")
                .replace("&#13;", "\r");
    }

    public static String stripXml(String xml) {
        if (xml == null) return "";
        return xmlUnescape(xml.replaceAll("(?is)<[^>]+>", ""));
    }

    public static String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public static void putZip(ZipOutputStream zip, String name, String utf8) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write((utf8 == null ? "" : utf8).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    public static void putZipBytes(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        if (bytes != null && bytes.length > 0) zip.write(bytes);
        zip.closeEntry();
    }

    public static void announce(View anchor, String message) {
        if (anchor == null || message == null || message.trim().isEmpty()) return;
        try {
            anchor.announceForAccessibility(message);
        } catch (Throwable ignored) {}
    }

    public static Button makeChipButton(Activity act, String label, String contentDesc,
                                        int bgColor, int textColor, View.OnClickListener listener) {
        Button b = new Button(act);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(textColor);
        b.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 8), UiKit.dp(act, 12), UiKit.dp(act, 8));
        b.setBackground(UiKit.roundedBox(bgColor, Color.TRANSPARENT, UiKit.dp(act, 12), 0));
        b.setMinimumHeight(UiKit.dp(act, 44));
        b.setMinHeight(UiKit.dp(act, 44));
        b.setContentDescription(contentDesc == null || contentDesc.isEmpty() ? label : contentDesc);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(UiKit.dp(act, 3), UiKit.dp(act, 3), UiKit.dp(act, 3), UiKit.dp(act, 3));
        b.setLayoutParams(lp);
        return b;
    }

    public static Button makeRowButton(Activity act, String label, String contentDesc,
                                       int bgColor, int textColor, View.OnClickListener listener) {
        Button b = makeChipButton(act, label, contentDesc, bgColor, textColor, listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(UiKit.dp(act, 3), UiKit.dp(act, 3), UiKit.dp(act, 3), UiKit.dp(act, 3));
        b.setLayoutParams(lp);
        return b;
    }

    public static String mimeForFileName(String name) {
        if (name == null) return "*/*";
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".dotx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.template";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".odt")) return "application/vnd.oasis.opendocument.text";
        if (lower.endsWith(".txt")) return "text/plain";
        return "*/*";
    }

    /**
     * Membagikan berkas File lokal dengan menyalinnya terlebih dahulu ke cacheDir/shared_exports
     * agar selalu dapat dibaca aplikasi tujuan (WhatsApp, Email, Drive) via ExportFileProvider.
     */
    public static void shareLocalFile(Activity act, File sourceFile, String displayName) {
        if (act == null || sourceFile == null || !sourceFile.exists()) return;
        try {
            File exportDir = new File(act.getCacheDir(), "shared_exports");
            if (!exportDir.exists()) exportDir.mkdirs();
            // Bersihkan cache lama > 6 jam agar ruang tidak penuh
            long cutoff = System.currentTimeMillis() - (6L * 60L * 60L * 1000L);
            File[] oldFiles = exportDir.listFiles();
            if (oldFiles != null) {
                for (File old : oldFiles) {
                    if (old.isFile() && old.lastModified() < cutoff) old.delete();
                }
            }
            String safeName = (displayName == null || displayName.trim().isEmpty()
                    ? sourceFile.getName()
                    : displayName.trim()).replaceAll("[\\\\/:*?\"<>|]", "_");
            File staged = new File(exportDir, safeName);
            try (InputStream in = new FileInputStream(sourceFile);
                 FileOutputStream out = new FileOutputStream(staged, false)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            Uri shareUri = Uri.parse("content://" + ExportFileProvider.AUTHORITY + "/" + Uri.encode(safeName));
            String mime = mimeForFileName(safeName);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mime);
            share.putExtra(Intent.EXTRA_STREAM, shareUri);
            share.putExtra(Intent.EXTRA_SUBJECT, safeName);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.startActivity(Intent.createChooser(share, "Bagikan " + safeName));
        } catch (Exception e) {
            Toast.makeText(act, "Gagal membagikan berkas: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static void shareContentUri(Activity act, Uri contentUri, String fileName) {
        if (act == null || contentUri == null) return;
        try {
            if ("file".equalsIgnoreCase(contentUri.getScheme()) && contentUri.getPath() != null) {
                shareLocalFile(act, new File(contentUri.getPath()), fileName);
                return;
            }
            String mime = mimeForFileName(fileName);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mime);
            share.putExtra(Intent.EXTRA_STREAM, contentUri);
            if (fileName != null) share.putExtra(Intent.EXTRA_SUBJECT, fileName);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.startActivity(Intent.createChooser(share, "Bagikan dokumen"));
        } catch (Exception e) {
            Toast.makeText(act, "Gagal membagikan URI: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static InputStream openRead(android.content.Context ctx, Uri uri) throws Exception {
        if (uri == null) throw new IllegalArgumentException("URI kosong");
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            return new FileInputStream(new File(uri.getPath()));
        }
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("Berkas tidak bisa dibaca");
        return in;
    }

    public static java.io.OutputStream openWrite(android.content.Context ctx, Uri uri) throws Exception {
        if (uri == null) throw new IllegalArgumentException("URI kosong");
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            File target = new File(uri.getPath());
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            return new FileOutputStream(target, false);
        }
        java.io.OutputStream out = ctx.getContentResolver().openOutputStream(uri, "wt");
        if (out == null) throw new IllegalStateException("Izin tulis ke berkas ditolak sistem");
        return out;
    }

    public static SharedPreferences mainPrefs(Activity act) {
        return act.getSharedPreferences("VoiceInput_Settings", Activity.MODE_PRIVATE);
    }

    public static void launchFilePicker(Activity act, int reqCode, String filter, String title) {
        launchFilePicker(act, reqCode, filter, title, null);
    }

    public static void launchFilePicker(Activity act, int reqCode, String filter, String title, String backupSubfolder) {
        Intent i = new Intent(act, FileManagerActivity.class);
        i.putExtra(FileManagerActivity.EXTRA_MODE, FileManagerActivity.MODE_PICK_FILE);
        if (filter != null && !filter.isEmpty()) i.putExtra(FileManagerActivity.EXTRA_FILTER, filter);
        if (title != null && !title.isEmpty()) i.putExtra(FileManagerActivity.EXTRA_TITLE, title);
        if (backupSubfolder != null && !backupSubfolder.isEmpty()) {
            File targetDir = resolveBackupSubfolder(act, backupSubfolder);
            i.putExtra(FileManagerActivity.EXTRA_INITIAL_DIR, targetDir.getAbsolutePath());
        }
        act.startActivityForResult(i, reqCode);
    }

    public static void launchFolderPicker(Activity act, int reqCode, String title) {
        Intent i = new Intent(act, FileManagerActivity.class);
        i.putExtra(FileManagerActivity.EXTRA_MODE, FileManagerActivity.MODE_PICK_FOLDER);
        i.putExtra("pick_folder_only", true);
        if (title != null && !title.isEmpty()) i.putExtra(FileManagerActivity.EXTRA_TITLE, title);
        act.startActivityForResult(i, reqCode);
    }

    public static void launchFileSaver(Activity act, int reqCode, String defaultName, String title) {
        launchFileSaver(act, reqCode, defaultName, title, "Dokumen");
    }

    public static void launchFileSaver(Activity act, int reqCode, String defaultName, String title, String backupSubfolder) {
        Intent i = new Intent(act, FileManagerActivity.class);
        i.putExtra(FileManagerActivity.EXTRA_MODE, FileManagerActivity.MODE_SAVE_FILE);
        i.putExtra(FileManagerActivity.EXTRA_DEFAULT_NAME, defaultName);
        if (title != null && !title.isEmpty()) i.putExtra(FileManagerActivity.EXTRA_TITLE, title);
        File targetDir = resolveBackupSubfolder(act, (backupSubfolder == null || backupSubfolder.isEmpty()) ? "Ekspor" : backupSubfolder);
        i.putExtra(FileManagerActivity.EXTRA_INITIAL_DIR, targetDir.getAbsolutePath());
        act.startActivityForResult(i, reqCode);
    }

    public static final String WORKSPACE_FOLDER_NAME = "Smart Tools and Typing AI by Anas";
    public static final String LEGACY_WORKSPACE_FOLDER_NAME = "Keyboard AI by Anas";
    private static volatile boolean legacyMigrationInProgress = false;

    /** Mengecek akses tulis penyimpanan bersama (/storage/emulated/0). */
    public static boolean hasSharedStorageAccess(android.content.Context ctx) {
        if (ctx == null) return false;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            return android.os.Environment.isExternalStorageManager();
        }
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Akar folder backup & hasil kerja terpusat (`Smart Tools and Typing AI by Anas`). Tidak pernah berisi cache mentah atau kunci admin rahasia. */
    public static File resolveBackupRoot(android.content.Context ctx) {
        if (hasSharedStorageAccess(ctx)) {
            File dir = new File(android.os.Environment.getExternalStorageDirectory(), WORKSPACE_FOLDER_NAME);
            if (!dir.exists()) dir.mkdirs();
            if (dir.exists() && dir.canWrite()) return dir;
        }
        File base = ctx != null ? ctx.getExternalFilesDir(null) : null;
        File fallback = new File(base != null ? base : android.os.Environment.getExternalStorageDirectory(), WORKSPACE_FOLDER_NAME);
        if (!fallback.exists()) fallback.mkdirs();
        return fallback;
    }

    /**
     * Memindahkan seluruh isi folder lama (`Keyboard AI by Anas`) ke folder baru
     * (`Smart Tools and Typing AI by Anas`) secara otomatis setelah update aplikasi.
     * Aman untuk pengguna baru maupun pengguna lama (tidak menimpa file yang sudah ada).
     */
    public static void migrateLegacyWorkspaceIfNeeded(final android.content.Context ctx) {
        if (ctx == null || legacyMigrationInProgress) return;
        final android.content.Context appCtx = ctx.getApplicationContext();
        legacyMigrationInProgress = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (hasSharedStorageAccess(appCtx)) {
                        File ext = android.os.Environment.getExternalStorageDirectory();
                        File oldDir = new File(ext, LEGACY_WORKSPACE_FOLDER_NAME);
                        File newDir = new File(ext, WORKSPACE_FOLDER_NAME);
                        if (oldDir.exists() && oldDir.isDirectory()) {
                            mergeMoveDirectory(oldDir, newDir);
                        }
                    }
                    File privBase = appCtx.getExternalFilesDir(null);
                    if (privBase != null) {
                        File oldPriv = new File(privBase, LEGACY_WORKSPACE_FOLDER_NAME);
                        File newPriv = new File(privBase, WORKSPACE_FOLDER_NAME);
                        if (oldPriv.exists() && oldPriv.isDirectory()) {
                            mergeMoveDirectory(oldPriv, newPriv);
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    legacyMigrationInProgress = false;
                }
            }
        }, "workspace-legacy-migration").start();
    }

    private static void mergeMoveDirectory(File src, File dst) {
        if (src == null || !src.exists() || !src.isDirectory()) return;
        if (!dst.exists()) {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (src.renameTo(dst)) return;
            dst.mkdirs();
        }
        File[] children = src.listFiles();
        if (children != null) {
            for (File child : children) {
                File target = new File(dst, child.getName());
                if (child.isDirectory()) {
                    mergeMoveDirectory(child, target);
                } else {
                    if (!target.exists()) {
                        if (!child.renameTo(target)) {
                            copyFileSafe(child, target);
                            if (target.exists() && target.length() == child.length()) {
                                try { child.delete(); } catch (Throwable ignored) {}
                            }
                        }
                    } else if (target.length() == child.length()) {
                        // File identik sudah ada di folder baru, bersihkan salinan lama yang tertinggal
                        try { child.delete(); } catch (Throwable ignored) {}
                    }
                }
            }
        }
        String[] remaining = src.list();
        if (remaining == null || remaining.length == 0) {
            try { src.delete(); } catch (Throwable ignored) {}
        }
    }

    private static void copyFileSafe(File src, File dst) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst, false)) {
            byte[] buf = new byte[16384];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        } catch (Throwable ignored) {}
    }

    /** Subfolder rapi di dalam folder backup-an utama (Dokumen Word, Gemini TTS, Editor PDF, Excel, dll). */
    public static File resolveBackupSubfolder(android.content.Context ctx, String subName) {
        File dir = new File(resolveBackupRoot(ctx), subName);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Membuat seluruh peta folder terorganisir di penyimpanan utama agar langsung muncul rapi di File Manager. */
    public static void ensureAllWorkspaceFolders(android.content.Context ctx) {
        if (ctx == null) return;
        migrateLegacyWorkspaceIfNeeded(ctx);
        String[] folders = {
                "Cadangan",
                "Dokumen Word",
                "Editor PDF",
                "Excel",
                "PowerPoint",
                "Kode & Script",
                "Teks & Catatan AI",
                "Terjemahan",
                "Stiker",
                "Editor Audio",
                "Editor Video",
                "Status WA Media",
                "Gemini TTS/Gemini Audio",
                "Gemini TTS/Gemini Video",
                "Gemini TTS/Gemini Status WA",
                "Gemini TTS/Gemini Teks"
        };
        for (String f : folders) {
            try { resolveBackupSubfolder(ctx, f); } catch (Throwable ignored) {}
        }
    }

    /** Menyimpan teks langsung sebagai berkas .txt ke subfolder terorganisir di folder backup-an. */
    public static File saveTextToBackupFolder(android.content.Context ctx, String subfolder, String prefix, String content) throws Exception {
        File dir = resolveBackupSubfolder(ctx, (subfolder == null || subfolder.isEmpty()) ? "Teks & Catatan AI" : subfolder);
        String safePrefix = (prefix == null || prefix.trim().isEmpty())
                ? "Catatan" : prefix.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        File out = new File(dir, safePrefix + "_" + System.currentTimeMillis() + ".txt");
        try (FileOutputStream fos = new FileOutputStream(out, false)) {
            fos.write((content == null ? "" : content).getBytes("UTF-8"));
        }
        return out;
    }

    /** Membersihkan isi folder cache sementara agar tidak menumpuk sampah. */
    public static void clearDirectoryContents(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) clearDirectoryContents(f);
            try { f.delete(); } catch (Throwable ignored) {}
        }
    }
}
