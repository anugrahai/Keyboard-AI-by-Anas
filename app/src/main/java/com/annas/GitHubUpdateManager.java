package com.annas;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Pemeriksa pembaruan GitHub untuk Keyboard AI by Anas.
 * Mengunduh APK dari aset rilis publik dan menyerahkan pemasangan kepada installer Android.
 */
public final class GitHubUpdateManager {
    private static final String REPO = "anugrahai/Keyboard-AI-by-Anas";
    private static final String API_RELEASES_URL =
            "https://api.github.com/repos/" + REPO + "/releases?per_page=10";
    private static final String PREFS = "VoiceInput_Settings";
    private static final String KEY_INSTALLED_TAG = "github_update_installed_tag";
    private static final String KEY_PENDING_TAG = "github_update_pending_tag";
    private static final String KEY_DOWNLOADED_TAG = "github_update_downloaded_tag";
    private static final String KEY_PENDING_BASE_CODE = "github_update_pending_base_code";
    private static final String KEY_OPEN_INSTALLER_AFTER_PERMISSION = "github_update_open_after_permission";
    private static final String INITIAL_RELEASE_TAG = "V4";

    private final MainActivity activity;
    private final SharedPreferences prefs;
    private volatile boolean downloading;
    private ProgressDialog progressDialog;
    private File downloadedApk;

    private static final class ReleaseInfo {
        String tag;
        String title;
        String notes;
        String apkUrl;
        String apkName;
        long apkSize;
    }

    public GitHubUpdateManager(MainActivity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        if (!prefs.contains(KEY_INSTALLED_TAG)) {
            // Gunakan versi yang benar-benar terpasang, bukan tag rilis lama yang ditulis tetap.
            String installedVersion = getInstalledVersionName();
            prefs.edit().putString(KEY_INSTALLED_TAG,
                    installedVersion.isEmpty() ? INITIAL_RELEASE_TAG : installedVersion).apply();
        }
        resolveSuccessfulPreviousInstall();
    }

    /** Pemeriksaan otomatis: tidak menampilkan pesan jika tidak ada update. */
    public void checkForUpdates() {
        checkForUpdates(false);
    }

    /** Pemeriksaan manual dari tombol menu: selalu memberi hasil kepada pengguna. */
    public void checkForUpdates(final boolean manual) {
        if (downloading || activity.isFinishing()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                ReleaseInfo release = null;
                Exception failure = null;
                try {
                    release = fetchLatestReleaseWithApk();
                } catch (Exception e) {
                    failure = e;
                }
                final ReleaseInfo result = release;
                final Exception error = failure;
                activity.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (activity.isFinishing()) return;
                        if (result != null) {
                            String installedVersion = getInstalledVersionName();
                            if (sameVersion(result.tag, installedVersion)) {
                                markReleaseInstalled(result.tag);
                                if (manual) {
                                    UiKit.dialog(activity, OfficeUtils.mainPrefs(activity))
                                            .setTitle("Tidak ada pembaruan")
                                            .setMessage("Kamu sudah menggunakan versi terbaru (" + installedVersion + ").")
                                            .setPositiveButton("Oke", null)
                                            .show();
                                }
                            } else {
                                showUpdateIfNeeded(result);
                            }
                        } else if (manual) {
                            String detail = error == null ?
                                    "Rilis publik yang memiliki file APK tidak ditemukan." :
                                    "Tidak dapat memeriksa GitHub. Periksa koneksi internet lalu coba lagi.";
                            UiKit.dialog(activity, prefs)
                                    .setTitle("Pemeriksaan pembaruan gagal")
                                    .setMessage(detail)
                                    .setPositiveButton("Coba Lagi", (dialog, which) -> checkForUpdates(true))
                                    .show();
                        }
                    }
                });
            }
        }, "github-update-check").start();
    }

    public void handleResume() {
        resolveSuccessfulPreviousInstall();
        if (prefs.getBoolean(KEY_OPEN_INSTALLER_AFTER_PERMISSION, false)) {
            File apk = getUpdateApkFile();
            if (apk.exists() && apk.length() > 0 && canInstallPackages()) {
                prefs.edit().putBoolean(KEY_OPEN_INSTALLER_AFTER_PERMISSION, false).apply();
                openInstaller(apk);
            }
        }
    }

    private ReleaseInfo fetchLatestReleaseWithApk() throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(API_RELEASES_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "Keyboard-AI-by-Anas-Updater");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new Exception("GitHub HTTP " + code);
            StringBuilder body = new StringBuilder();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) body.append(new String(buffer, 0, n, "UTF-8"));
            }
            JSONArray releases = new JSONArray(body.toString());
            // GitHub menyusun daftar rilis dari yang terbaru. Pilih rilis publik terbaru yang punya APK.
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.optJSONObject(i);
                if (release == null || release.optBoolean("draft", false)) continue;
                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) continue;
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.optJSONObject(j);
                    if (asset == null) continue;
                    String name = asset.optString("name", "");
                    String download = asset.optString("browser_download_url", "");
                    if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")
                            && download.startsWith("https://")) {
                        ReleaseInfo info = new ReleaseInfo();
                        info.tag = release.optString("tag_name", "");
                        info.title = release.optString("name", info.tag);
                        info.notes = release.optString("body", "").trim();
                        info.apkUrl = download;
                        info.apkName = name;
                        info.apkSize = asset.optLong("size", -1L);
                        if (!info.tag.isEmpty()) return info;
                    }
                }
            }
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void showUpdateIfNeeded(final ReleaseInfo release) {
        String installedVersion = getInstalledVersionName();
        String installedTag = installedVersion.isEmpty()
                ? prefs.getString(KEY_INSTALLED_TAG, INITIAL_RELEASE_TAG) : installedVersion;
        if (sameVersion(release.tag, installedVersion)) {
            markReleaseInstalled(release.tag);
            return;
        }

        final File existingApk = getUpdateApkFile();
        boolean alreadyDownloaded = existingApk.exists() && existingApk.length() > 1024
                && release.tag.equals(prefs.getString(KEY_DOWNLOADED_TAG, ""));
        if (alreadyDownloaded) {
            UiKit.dialog(activity, OfficeUtils.mainPrefs(activity))
                    .setTitle("Pembaruan siap dipasang")
                    .setMessage("APK versi " + release.tag + " sudah tersedia di perangkat. Kamu bisa langsung membuka installer Android.")
                    .setPositiveButton("Pasang Sekarang", (dialog, which) -> beginInstall(existingApk, release))
                    .setNegativeButton("Nanti", null)
                    .show();
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append("Versi saat ini: ").append(installedTag)
                .append("\nVersi terbaru: ").append(release.tag).append("\n\n");
        if (!release.notes.isEmpty()) {
            String notes = release.notes;
            if (notes.length() > 1800) notes = notes.substring(0, 1800) + "…";
            message.append("Yang diperbarui:\n").append(notes).append("\n\n");
        }
        message.append("Unduh dan pasang pembaruan sekarang?");
        UiKit.dialog(activity, OfficeUtils.mainPrefs(activity))
                .setTitle(release.title.isEmpty() ? "Pembaruan Smart Tools and Typing AI by Anas tersedia" :
                        "Pembaruan tersedia: " + release.title)
                .setMessage(message.toString())
                .setPositiveButton("Perbarui Sekarang", (dialog, which) -> downloadAndInstall(release))
                .setNegativeButton("Nanti", null)
                .setCancelable(true)
                .show();
    }

    private void downloadAndInstall(final ReleaseInfo release) {
        if (downloading) return;
        downloading = true;
        progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle("Mengunduh pembaruan");
        progressDialog.setMessage("Menyiapkan unduhan…");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setIndeterminate(true);
        progressDialog.setProgress(0);
        progressDialog.setCancelable(false);
        progressDialog.show();

        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection connection = null;
                File temp = null;
                try {
                    URL url = new URL(release.apkUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent", "Keyboard-AI-by-Anas-Updater");
                    int code = connection.getResponseCode();
                    if (code < 200 || code >= 300) throw new Exception("Unduhan gagal (HTTP " + code + ").");

                    long total = connection.getContentLengthLong();
                    if (total <= 0 && release.apkSize > 0) total = release.apkSize;
                    final long expected = total;
                    temp = getUpdateApkFile();
                    File parent = temp.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    long downloaded = 0;
                    long lastUiUpdate = 0;
                    try (InputStream in = connection.getInputStream();
                         FileOutputStream out = new FileOutputStream(temp, false)) {
                        byte[] buffer = new byte[16384];
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            out.write(buffer, 0, n);
                            downloaded += n;
                            long now = android.os.SystemClock.uptimeMillis();
                            if (now - lastUiUpdate >= 180L || (expected > 0 && downloaded >= expected)) {
                                lastUiUpdate = now;
                                final long current = downloaded;
                                if (expected > 0) {
                                    final int percent = (int) Math.min(100, (current * 100L) / expected);
                                    activity.runOnUiThread(() -> updateProgress(percent, current, expected));
                                } else {
                                    activity.runOnUiThread(() -> updateProgressIndeterminate(current));
                                }
                            }
                        }
                        out.flush();
                    }
                    if (temp.length() < 1024) throw new Exception("Berkas APK terlalu kecil atau tidak lengkap.");
                    if (release.apkSize > 0 && temp.length() != release.apkSize) {
                        throw new Exception("Ukuran APK tidak sesuai. Silakan coba unduh lagi.");
                    }
                    final File completed = temp;
                    activity.runOnUiThread(() -> {
                        downloading = false;
                        dismissProgress();
                        downloadedApk = completed;
                        prefs.edit().putString(KEY_DOWNLOADED_TAG, release.tag).apply();
                        // Setelah unduhan lengkap, langsung serahkan ke installer Android.
                        beginInstall(completed, release);
                    });
                } catch (Exception e) {
                    if (temp != null && temp.exists()) temp.delete();
                    final String error = e.getMessage() == null ? "Periksa koneksi internet lalu coba lagi." : e.getMessage();
                    activity.runOnUiThread(() -> {
                        downloading = false;
                        dismissProgress();
                        if (!activity.isFinishing()) {
                            UiKit.dialog(activity, prefs)
                                    .setTitle("Pembaruan gagal")
                                    .setMessage(error)
                                    .setPositiveButton("Coba Lagi", (d, w) -> downloadAndInstall(release))
                                    .show();
                        }
                    });
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        }, "github-apk-download").start();
    }

    private void updateProgress(int percent, long downloaded, long total) {
        if (progressDialog == null || !progressDialog.isShowing()) return;
        progressDialog.setIndeterminate(false);
        progressDialog.setProgress(percent);
        progressDialog.setMessage(
                "Mengunduh pembaruan: " + formatBytes(downloaded) + " / " + formatBytes(total) + " (" + percent + "%)"
        );
    }

    private void updateProgressIndeterminate(long downloaded) {
        if (progressDialog == null || !progressDialog.isShowing()) return;
        progressDialog.setIndeterminate(true);
        progressDialog.setMessage("Mengunduh pembaruan: " + formatBytes(downloaded));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void beginInstall(File apk, ReleaseInfo release) {
        if (apk == null || !apk.exists() || apk.length() < 1024) {
            Toast.makeText(activity, "APK pembaruan tidak ditemukan. Silakan unduh ulang.", Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit()
                .putString(KEY_PENDING_TAG, release.tag)
                .putInt(KEY_PENDING_BASE_CODE, getInstalledVersionCode())
                .apply();
        if (!canInstallPackages()) {
            prefs.edit().putBoolean(KEY_OPEN_INSTALLER_AFTER_PERMISSION, true).apply();
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            try {
                activity.startActivity(settings);
            } catch (Exception e) {
                prefs.edit().putBoolean(KEY_OPEN_INSTALLER_AFTER_PERMISSION, false).apply();
                Toast.makeText(activity, "Izinkan pemasangan aplikasi dari sumber ini di Setelan Android.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        openInstaller(apk);
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls();
    }

    private void openInstaller(File apk) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = Uri.parse("content://" + activity.getPackageName() + ".updateprovider/update.apk");
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "Installer tidak bisa dibuka. Coba pasang APK dari pengelola berkas.", Toast.LENGTH_LONG).show();
        }
    }

    private File getUpdateApkFile() {
        File dir = new File(activity.getCacheDir(), "updates");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "update.apk");
    }

    private int getInstalledVersionCode() {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) info.getLongVersionCode();
            //noinspection deprecation
            return info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private void resolveSuccessfulPreviousInstall() {
        String pendingTag = prefs.getString(KEY_PENDING_TAG, null);
        if (pendingTag == null) return;

        // Installer bisa memasang APK dengan versionCode yang sama. Cocokkan juga
        // versionName aplikasi yang sedang berjalan dengan tag yang diminta.
        String installedVersion = getInstalledVersionName();
        if (sameVersion(pendingTag, installedVersion)) {
            markReleaseInstalled(pendingTag);
        }
    }

    private String getInstalledVersionName() {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean sameVersion(String first, String second) {
        if (first == null || second == null) return false;
        String a = normalizeVersionTag(first);
        String b = normalizeVersionTag(second);
        return !a.isEmpty() && a.equalsIgnoreCase(b);
    }

    private String normalizeVersionTag(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.regionMatches(true, 0, "v", 0, 1)) s = s.substring(1).trim();
        while (s.endsWith(".0") && s.indexOf('.') != s.lastIndexOf('.')) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }

    /** Sinkronkan status updater saat versi rilis memang sudah terpasang. */
    private void markReleaseInstalled(String releaseTag) {
        prefs.edit()
                .putString(KEY_INSTALLED_TAG, releaseTag)
                .remove(KEY_PENDING_TAG)
                .remove(KEY_PENDING_BASE_CODE)
                .remove(KEY_DOWNLOADED_TAG)
                .putBoolean(KEY_OPEN_INSTALLER_AFTER_PERMISSION, false)
                .apply();
        File oldApk = getUpdateApkFile();
        if (oldApk.exists()) oldApk.delete();
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            try { if (progressDialog.isShowing()) progressDialog.dismiss(); } catch (Exception ignored) {}
            progressDialog = null;
        }
    }
}
