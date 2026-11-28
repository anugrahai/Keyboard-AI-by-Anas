package com.annas;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground Service Pengunduh Media Latar Belakang.
 * - Tetap berjalan walau layar dimatikan atau aplikasi ditutup.
 * - Otomatis membersihkan setiap item dari daftar aktif seketika saat selesai atau dibatalkan.
 * - Menyediakan ringkasan progress keseluruhan (BatchSnapshot) untuk slider UI & bar notifikasi.
 */
public class MediaDownloadService extends Service {

    public static final String ACTION_START_QUEUE = "com.annas.action.START_MEDIA_DOWNLOAD_QUEUE";
    public static final String ACTION_CANCEL_ALL = "com.annas.action.CANCEL_ALL_MEDIA_DOWNLOAD";

    private static final String CHANNEL_ID = "media_downloader_ch_v1";
    private static final int NOTIF_ID = 3601;
    private static final String HIST_PREFS = "MediaDownloaderHistoryPrefs";
    private static final String KEY_HISTORY_JSON = "completed_items_json";
    private static final int MAX_HISTORY = 500;

    public static final int STATUS_QUEUED = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_CANCELLED = 3;
    public static final int STATUS_FAILED = 4;

    public static final class DownloadTask {
        public String taskId;
        public MediaDownloaderEngine.MediaItem item;
        public String mode;
        public int bitrateKbps;
        public volatile int status = STATUS_QUEUED;
        public volatile int progress = 0;
        public volatile String statusText = "Menunggu antrean...";
        public volatile boolean cancelRequested = false;
        public volatile long activeFfmpegSessionId = -1L;
    }

    public static final class BatchSnapshot {
        public int totalInBatch = 0;
        public int completedInBatch = 0;
        public int failedInBatch = 0;
        public int remainingCount = 0;
        public int overallPercent = 0;
        public String activeHeadline = "";
        public String lastFailureMsg = "";
    }

    public static final class CompletedEntry {
        public String taskId = "";
        public String title = "";
        public String uploader = "";
        public String sourceKind = "YouTube";
        public String containerKind = "Single";
        public String modeLabel = "";
        public String filePath = "";
        public long fileSizeBytes = 0L;
        public long finishedAt = 0L;
    }

    public interface QueueListener {
        void onQueueUpdated();
    }

    private static final Object LOCK = new Object();
    private static final List<DownloadTask> activeTasks = new ArrayList<>();
    private static final List<CompletedEntry> completedCache = new ArrayList<>();
    private static boolean historyLoaded = false;
    private static final Set<QueueListener> listeners = new HashSet<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static volatile long lastUiNotifyMs = 0L;
    private static volatile boolean uiNotifyScheduled = false;

    private static int batchTotal = 0;
    private static int batchDone = 0;
    private static int batchFailed = 0;
    private static String lastFailure = "";

    private ExecutorService workerPool;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
        if (workerPool == null || workerPool.isShutdown()) {
            workerPool = Executors.newFixedThreadPool(4);
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartToolsAI:MediaDownloaderLock");
                wakeLock.setReferenceCounted(false);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL_ALL.equals(intent.getAction())) {
            cancelAllActive();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        BatchSnapshot snap = getBatchSnapshot();
        startForeground(NOTIF_ID, buildProgressNotification(snap));
        dispatchQueuedTasks();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        if (workerPool != null && !workerPool.isShutdown()) {
            workerPool.shutdownNow();
        }
    }

    // =========================================================================
    // API STATIS UNTUK UI
    // =========================================================================

    public static void addListener(QueueListener l) {
        if (l == null) return;
        synchronized (LOCK) { listeners.add(l); }
    }

    public static void removeListener(QueueListener l) {
        if (l == null) return;
        synchronized (LOCK) { listeners.remove(l); }
    }

    public static void enqueueItems(Context ctx, List<MediaDownloaderEngine.MediaItem> items,
                                    String mode, int bitrateKbps) {
        if (ctx == null || items == null || items.isEmpty()) return;
        ensureHistoryLoaded(ctx);
        synchronized (LOCK) {
            if (activeTasks.isEmpty()) {
                batchTotal = 0;
                batchDone = 0;
                batchFailed = 0;
                lastFailure = "";
            }
            for (MediaDownloaderEngine.MediaItem src : items) {
                if (src == null) continue;
                MediaDownloaderEngine.MediaItem copy = new MediaDownloaderEngine.MediaItem();
                copy.id = src.id;
                copy.title = src.title;
                copy.uploader = src.uploader;
                copy.durationText = src.durationText;
                copy.durationSec = src.durationSec;
                copy.webpageUrl = src.webpageUrl;
                copy.sourceKind = src.sourceKind;
                copy.containerKind = src.containerKind;
                copy.containerName = src.containerName;

                DownloadTask t = new DownloadTask();
                t.taskId = "dl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                t.item = copy;
                t.mode = mode;
                t.bitrateKbps = bitrateKbps;
                t.status = STATUS_QUEUED;
                t.progress = 0;
                t.statusText = "Menunggu antrean...";
                activeTasks.add(t);
                batchTotal++;
            }
        }
        notifyListenersImmediate();
        startServiceCompat(ctx);
    }

    public static void cancelTask(String taskId) {
        if (taskId == null || taskId.isEmpty()) return;
        cancelTasks(Collections.singletonList(taskId));
    }

    public static void cancelTasks(Collection<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) return;
        Set<String> idSet = new HashSet<>(taskIds);
        List<DownloadTask> toDestroy = new ArrayList<>();
        synchronized (LOCK) {
            for (int i = activeTasks.size() - 1; i >= 0; i--) {
                DownloadTask t = activeTasks.get(i);
                if (idSet.contains(t.taskId)) {
                    t.cancelRequested = true;
                    t.status = STATUS_CANCELLED;
                    t.statusText = "Dibatalkan";
                    activeTasks.remove(i);
                    if (batchTotal > 0) batchTotal = Math.max(activeTasks.size() + batchDone + batchFailed, batchTotal - 1);
                    toDestroy.add(t);
                }
            }
            if (activeTasks.isEmpty()) {
                batchTotal = 0;
                batchDone = 0;
                batchFailed = 0;
            }
        }
        for (DownloadTask t : toDestroy) {
            killUnderlyingProcess(t);
        }
        notifyListenersImmediate();
    }

    public static void cancelAllActive() {
        List<DownloadTask> toDestroy;
        synchronized (LOCK) {
            toDestroy = new ArrayList<>(activeTasks);
            for (DownloadTask t : toDestroy) {
                t.cancelRequested = true;
                t.status = STATUS_CANCELLED;
                t.statusText = "Dibatalkan";
            }
            activeTasks.clear();
            batchTotal = 0;
            batchDone = 0;
            batchFailed = 0;
            lastFailure = "";
        }
        for (DownloadTask t : toDestroy) {
            killUnderlyingProcess(t);
        }
        notifyListenersImmediate();
    }

    public static List<DownloadTask> getActiveSnapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(activeTasks);
        }
    }

    public static BatchSnapshot getBatchSnapshot() {
        BatchSnapshot s = new BatchSnapshot();
        synchronized (LOCK) {
            s.remainingCount = activeTasks.size();
            s.totalInBatch = Math.max(batchTotal, activeTasks.size() + batchDone + batchFailed);
            s.completedInBatch = batchDone;
            s.failedInBatch = batchFailed;
            s.lastFailureMsg = lastFailure;

            int activePctSum = 0;
            String firstRunning = "";
            for (DownloadTask t : activeTasks) {
                activePctSum += Math.max(0, Math.min(100, t.progress));
                if (firstRunning.isEmpty() && t.status == STATUS_RUNNING) {
                    firstRunning = t.item.title + " (" + t.progress + "%)";
                }
            }
            if (firstRunning.isEmpty() && !activeTasks.isEmpty()) {
                firstRunning = activeTasks.get(0).item.title;
            }
            s.activeHeadline = firstRunning;

            if (s.totalInBatch > 0 && s.remainingCount > 0) {
                long num = ((long) (batchDone + batchFailed) * 100L) + activePctSum;
                s.overallPercent = (int) Math.min(99L, Math.max(0L, num / s.totalInBatch));
            } else {
                s.overallPercent = 0;
            }
        }
        return s;
    }

    public static int getPendingOrRunningCount() {
        synchronized (LOCK) {
            return activeTasks.size();
        }
    }

    public static List<CompletedEntry> getCompletedSnapshot(Context ctx) {
        ensureHistoryLoaded(ctx);
        synchronized (LOCK) {
            return new ArrayList<>(completedCache);
        }
    }

    public static void removeCompletedEntry(Context ctx, String taskId, boolean deleteFile) {
        ensureHistoryLoaded(ctx);
        String fileToDelete = null;
        synchronized (LOCK) {
            for (int i = completedCache.size() - 1; i >= 0; i--) {
                CompletedEntry e = completedCache.get(i);
                if (e.taskId.equals(taskId)) {
                    fileToDelete = e.filePath;
                    completedCache.remove(i);
                    break;
                }
            }
            saveHistoryLocked(ctx);
        }
        if (deleteFile && fileToDelete != null && !fileToDelete.isEmpty()) {
            try {
                File f = new File(fileToDelete);
                if (f.exists()) f.delete();
            } catch (Throwable ignored) {}
        }
        notifyListenersImmediate();
    }

    private static void killUnderlyingProcess(DownloadTask t) {
        MediaDownloaderEngine.destroyProcessSafe(t.taskId);
        MediaDownloaderEngine.destroyProcessSafe(t.taskId + "_a");
        long sid = t.activeFfmpegSessionId;
        if (sid > 0) {
            try { FFmpegKit.cancel(sid); } catch (Throwable ignored) {}
        }
    }

    private static void startServiceCompat(Context ctx) {
        Intent i = new Intent(ctx.getApplicationContext(), MediaDownloadService.class);
        i.setAction(ACTION_START_QUEUE);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getApplicationContext().startForegroundService(i);
        } else {
            ctx.getApplicationContext().startService(i);
        }
    }

    // =========================================================================
    // EKSEKUSI WORKER ANTREAN
    // =========================================================================

    private final Set<String> submittedIds = Collections.synchronizedSet(new HashSet<>());

    private void dispatchQueuedTasks() {
        List<DownloadTask> toRun = new ArrayList<>();
        synchronized (LOCK) {
            for (DownloadTask t : activeTasks) {
                if (t.status == STATUS_QUEUED && !t.cancelRequested && !submittedIds.contains(t.taskId)) {
                    submittedIds.add(t.taskId);
                    toRun.add(t);
                }
            }
        }
        if (!toRun.isEmpty()) acquireWakeLock();
        for (final DownloadTask task : toRun) {
            workerPool.submit(() -> processSingleTask(task));
        }
        checkStopIfAllFinished();
    }

    private void processSingleTask(final DownloadTask task) {
        try {
            if (task.cancelRequested || task.status == STATUS_CANCELLED) return;
            task.status = STATUS_RUNNING;
            task.statusText = "Menyiapkan...";
            notifyListenersThrottled();
            updateProgressNotification();

            File outFile = MediaDownloaderEngine.downloadMediaItem(
                    getApplicationContext(),
                    task.item,
                    task.mode,
                    task.bitrateKbps,
                    task.taskId,
                    new MediaDownloaderEngine.ProgressListener() {
                        @Override public void onProgress(int percent, String detail) {
                            if (task.cancelRequested) return;
                            task.progress = Math.max(0, Math.min(100, percent));
                            task.statusText = detail;
                            notifyListenersThrottled();
                            updateProgressNotification();
                        }

                        @Override public boolean isCancelled() {
                            return task.cancelRequested || task.status == STATUS_CANCELLED;
                        }

                        @Override public void onFfmpegSessionStarted(long sessionId) {
                            task.activeFfmpegSessionId = sessionId;
                        }
                    });

            if (task.cancelRequested) {
                try { if (outFile != null && outFile.exists()) outFile.delete(); } catch (Throwable ignored) {}
                removeFinishedTask(task, false, false, "");
            } else {
                CompletedEntry ce = new CompletedEntry();
                ce.taskId = task.taskId;
                ce.title = task.item.title;
                ce.uploader = task.item.uploader;
                ce.sourceKind = task.item.sourceKind;
                ce.containerKind = task.item.containerKind;
                ce.modeLabel = MediaDownloaderEngine.getModeDisplayLabel(task.mode);
                ce.filePath = outFile.getAbsolutePath();
                ce.fileSizeBytes = outFile.length();
                ce.finishedAt = System.currentTimeMillis();
                addCompletedEntry(getApplicationContext(), ce);
                removeFinishedTask(task, true, false, "");
            }
        } catch (InterruptedException ie) {
            Log.i("MediaDownloadService", "Task cancelled: " + task.item.title);
            removeFinishedTask(task, false, false, "");
        } catch (Throwable err) {
            if (task.cancelRequested) {
                removeFinishedTask(task, false, false, "");
            } else {
                String msg = err.getMessage() == null ? "Gagal mengunduh" : err.getMessage();
                Log.e("MediaDownloadService", "Task failed: " + task.item.title + " -> " + msg, err);
                removeFinishedTask(task, false, true, task.item.title + ": " + msg);
            }
        } finally {
            submittedIds.remove(task.taskId);
            notifyListenersImmediate();
            updateProgressNotification();
            checkStopIfAllFinished();
        }
    }

    private static void removeFinishedTask(DownloadTask task, boolean succeeded, boolean failed, String errMsg) {
        synchronized (LOCK) {
            activeTasks.remove(task);
            if (succeeded) batchDone++;
            if (failed) {
                batchFailed++;
                lastFailure = errMsg;
            }
        }
    }

    private void checkStopIfAllFinished() {
        if (getPendingOrRunningCount() == 0) {
            releaseWakeLock();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID + 1, buildFinishedSummaryNotification());
            }
            stopForegroundCompat();
            stopSelf();
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(60 * 60 * 1000L);
            }
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
    }

    // =========================================================================
    // PERSISTENSI RIWAYAT TERUNDUH
    // =========================================================================

    private static void ensureHistoryLoaded(Context ctx) {
        synchronized (LOCK) {
            if (historyLoaded) return;
            historyLoaded = true;
            try {
                SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(HIST_PREFS, MODE_PRIVATE);
                String raw = sp.getString(KEY_HISTORY_JSON, "[]");
                JSONArray arr = new JSONArray(raw);
                completedCache.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    CompletedEntry e = new CompletedEntry();
                    e.taskId = o.optString("taskId", "");
                    e.title = o.optString("title", "Media");
                    e.uploader = o.optString("uploader", "");
                    e.sourceKind = o.optString("sourceKind", "YouTube");
                    e.containerKind = o.optString("containerKind", "Single");
                    e.modeLabel = o.optString("modeLabel", "Audio MP3");
                    e.filePath = o.optString("filePath", "");
                    e.fileSizeBytes = o.optLong("fileSizeBytes", 0L);
                    e.finishedAt = o.optLong("finishedAt", 0L);
                    if (!e.filePath.isEmpty()) completedCache.add(e);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void addCompletedEntry(Context ctx, CompletedEntry entry) {
        ensureHistoryLoaded(ctx);
        synchronized (LOCK) {
            completedCache.add(0, entry);
            while (completedCache.size() > MAX_HISTORY) {
                completedCache.remove(completedCache.size() - 1);
            }
            saveHistoryLocked(ctx);
        }
    }

    private static void saveHistoryLocked(Context ctx) {
        try {
            JSONArray arr = new JSONArray();
            for (CompletedEntry e : completedCache) {
                JSONObject o = new JSONObject();
                o.put("taskId", e.taskId);
                o.put("title", e.title);
                o.put("uploader", e.uploader);
                o.put("sourceKind", e.sourceKind);
                o.put("containerKind", e.containerKind);
                o.put("modeLabel", e.modeLabel);
                o.put("filePath", e.filePath);
                o.put("fileSizeBytes", e.fileSizeBytes);
                o.put("finishedAt", e.finishedAt);
                arr.put(o);
            }
            ctx.getApplicationContext().getSharedPreferences(HIST_PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_HISTORY_JSON, arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    // =========================================================================
    // NOTIFIKASI SLIDER PROGRES & BROADCAST UI
    // =========================================================================

    private static void notifyListenersImmediate() {
        lastUiNotifyMs = System.currentTimeMillis();
        mainHandler.post(() -> {
            List<QueueListener> copy;
            synchronized (LOCK) { copy = new ArrayList<>(listeners); }
            for (QueueListener l : copy) {
                try { l.onQueueUpdated(); } catch (Throwable ignored) {}
            }
        });
    }

    private static void notifyListenersThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastUiNotifyMs >= 300L) {
            notifyListenersImmediate();
            return;
        }
        if (uiNotifyScheduled) return;
        uiNotifyScheduled = true;
        mainHandler.postDelayed(() -> {
            uiNotifyScheduled = false;
            notifyListenersImmediate();
        }, 300L);
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Pengunduh Lagu & Video Pro", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Status unduhan musik, video, playlist & channel di latar belakang");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void updateProgressNotification() {
        BatchSnapshot snap = getBatchSnapshot();
        if (snap.remainingCount <= 0) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildProgressNotification(snap));
    }

    private Notification buildProgressNotification(BatchSnapshot snap) {
        Intent openIntent = new Intent(this, MediaDownloaderActivity.class);
        openIntent.putExtra("open_tab", 1);
        PendingIntent piOpen = PendingIntent.getActivity(this, 10, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent cancelIntent = new Intent(this, MediaDownloadService.class);
        cancelIntent.setAction(ACTION_CANCEL_ALL);
        PendingIntent piCancel = PendingIntent.getService(this, 11, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = snap.totalInBatch > 1
                ? ("Mengunduh " + snap.totalInBatch + " item • " + snap.overallPercent + "%")
                : ("Mengunduh • " + snap.overallPercent + "%");
        String text = snap.activeHeadline.isEmpty()
                ? ("Sisa " + snap.remainingCount + " item")
                : (snap.completedInBatch + "/" + snap.totalInBatch + " selesai • " + snap.activeHeadline);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, snap.overallPercent, snap.overallPercent <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(piOpen)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Batalkan Semua", piCancel);
        return b.build();
    }

    private Notification buildFinishedSummaryNotification() {
        Intent openIntent = new Intent(this, MediaDownloaderActivity.class);
        openIntent.putExtra("open_tab", 2);
        PendingIntent piOpen = PendingIntent.getActivity(this, 12, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Unduhan Selesai")
                .setContentText("Semua lagu/video selesai diunduh. Ketuk untuk membuka.")
                .setAutoCancel(true)
                .setContentIntent(piOpen);
        return b.build();
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }
}
