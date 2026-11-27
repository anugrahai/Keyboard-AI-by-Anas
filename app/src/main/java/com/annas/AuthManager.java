package com.annas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pengelola Login Google, Sinkronisasi Server Firestore, dan Kunci Fitur AI Premium.
 * Sumber kebenaran status langganan berada di Cloud Firestore; cache lokal hanya untuk akses
 * cepat KeyboardService saat offline sementara dan dilindungi hash integritas.
 */
public final class AuthManager {

    public static final String FALLBACK_WEB_CLIENT_ID =
            "151337638691-d1j4nmq20a0dopo4k7s4q50ofvsk2pf0.apps.googleusercontent.com";
    public static final String ADMIN_WA_INTL = "6282119059741";

    private static final String AUTH_CACHE_PREFS = "Anas_Auth_Cache";
    private static final String MIGRATION_PREFS = "Anas_Migration_Flags";
    private static final String KEY_WIPED_V25 = "prefs_wiped_v25_fresh";
    private static final String INTEGRITY_PEPPER = "AnasKb_2026_ServerAuth_Guard_v1";
    private static final long OFFLINE_GRACE_MS = TimeUnit.HOURS.toMillis(48);
    private static final long LOGIN_LOG_COOLDOWN_MS = TimeUnit.HOURS.toMillis(6);

    private AuthManager() {}

    /**
     * Hapus satu kali seluruh preferensi lama (termasuk API Key lama dari instalasi v24 ke bawah)
     * agar semua pengguna memulai dengan preferensi bersih yang baru.
     */
    public static void ensureFreshPreferences(Context ctx) {
        if (ctx == null) return;
        try {
            SharedPreferences mig = ctx.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE);
            if (mig.getBoolean(KEY_WIPED_V25, false)) return;
            ctx.getSharedPreferences("VoiceInput_Settings", Context.MODE_PRIVATE).edit().clear().commit();
            ctx.getSharedPreferences("document_studio", Context.MODE_PRIVATE).edit().clear().commit();
            ctx.getSharedPreferences("document_sender_profile", Context.MODE_PRIVATE).edit().clear().commit();
            mig.edit().putBoolean(KEY_WIPED_V25, true).commit();
        } catch (Throwable ignored) {}
    }

    public interface SyncCallback {
        void onSynced(AccountStatus status);
        void onError(String message);
    }

    public static final class AccountStatus {
        public final boolean signedIn;
        public final String uid;
        public final String email;
        public final String displayName;
        public final String plan;
        public final boolean isPremium;
        public final long expiresAt;
        public final boolean blocked;
        public final long lastSyncAt;
        public final String deviceId;
        public final boolean bonusGrokAllowed;
        public final boolean deviceAuthorized;

        public AccountStatus(boolean signedIn, String uid, String email, String displayName,
                             String plan, boolean isPremium, long expiresAt, boolean blocked,
                             long lastSyncAt, String deviceId, boolean bonusGrokAllowed) {
            this(signedIn, uid, email, displayName, plan, isPremium, expiresAt, blocked,
                    lastSyncAt, deviceId, bonusGrokAllowed, true);
        }

        public AccountStatus(boolean signedIn, String uid, String email, String displayName,
                             String plan, boolean isPremium, long expiresAt, boolean blocked,
                             long lastSyncAt, String deviceId, boolean bonusGrokAllowed,
                             boolean deviceAuthorized) {
            this.signedIn = signedIn;
            this.uid = uid == null ? "" : uid;
            this.email = email == null ? "" : email;
            this.displayName = displayName == null ? "" : displayName;
            this.plan = plan == null || plan.isEmpty() ? "free" : plan;
            this.isPremium = isPremium;
            this.expiresAt = expiresAt;
            this.blocked = blocked;
            this.lastSyncAt = lastSyncAt;
            this.deviceId = deviceId == null ? "" : deviceId;
            this.bonusGrokAllowed = bonusGrokAllowed;
            this.deviceAuthorized = deviceAuthorized;
        }

        public boolean isBonusGrokEligibleNow() {
            return isActivePremiumNow() && bonusGrokAllowed;
        }

        public boolean isActivePremiumNow() {
            if (!signedIn || blocked || !isPremium || !deviceAuthorized) return false;
            return expiresAt > System.currentTimeMillis();
        }

        public String getReadablePlanLabel() {
            if (blocked) return "Diblokir Admin";
            if (!deviceAuthorized) return "Device ID Belum Terdaftar";
            if (!isPremium) return "Gratis (Free)";
            long now = System.currentTimeMillis();
            if (expiresAt <= now) return "Premium Habis (Expired)";
            long daysLeft = Math.max(1L, (expiresAt - now + 86399999L) / 86400000L);
            String dateStr = new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID"))
                    .format(new Date(expiresAt));
            return planDisplayName(plan) + " (sisa " + daysLeft + " hari, s/d " + dateStr + ")";
        }
    }

    public static String planDisplayName(String plan) {
        if ("monthly".equalsIgnoreCase(plan)) return "1 Bulan";
        if ("2_months".equalsIgnoreCase(plan)) return "2 Bulan";
        if ("3_months".equalsIgnoreCase(plan)) return "3 Bulan";
        if ("6_months".equalsIgnoreCase(plan)) return "6 Bulan";
        if ("yearly".equalsIgnoreCase(plan)) return "1 Tahun";
        if ("2_years".equalsIgnoreCase(plan)) return "2 Tahun";
        if ("3_years".equalsIgnoreCase(plan)) return "3 Tahun";
        if ("custom".equalsIgnoreCase(plan)) return "Paket Kustom";
        return "Gratis";
    }

    public static String getDeviceId(Context ctx) {
        try {
            String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            return id == null ? "unknown" : id.trim();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    public static boolean isSignedIn() {
        try {
            return FirebaseAuth.getInstance().getCurrentUser() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static FirebaseUser getCurrentUser() {
        try {
            return FirebaseAuth.getInstance().getCurrentUser();
        } catch (Throwable t) {
            return null;
        }
    }

    public static GoogleSignInClient buildGoogleSignInClient(Context ctx) {
        String clientId = FALLBACK_WEB_CLIENT_ID;
        try {
            int resId = ctx.getResources().getIdentifier("default_web_client_id", "string", ctx.getPackageName());
            if (resId != 0) {
                String fromRes = ctx.getString(resId);
                if (fromRes != null && fromRes.contains(".apps.googleusercontent.com")) {
                    clientId = fromRes.trim();
                }
            }
        } catch (Throwable ignored) {}

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(clientId)
                .requestEmail()
                .build();
        return GoogleSignIn.getClient(ctx, gso);
    }

    public static void signOut(Context ctx, Runnable onDone) {
        stopAllRealtimeGuards();
        clearCache(ctx);
        try {
            FirebaseAuth.getInstance().signOut();
        } catch (Throwable ignored) {}
        try {
            buildGoogleSignInClient(ctx).signOut().addOnCompleteListener(task -> {
                if (onDone != null) onDone.run();
            });
        } catch (Throwable t) {
            if (onDone != null) onDone.run();
        }
    }

    /** Cek cepat (synchronous) untuk KeyboardService & UI gate. */
    public static boolean isPremiumActive(Context ctx) {
        AccountStatus status = getCachedStatus(ctx);
        if (!status.signedIn || status.blocked || !status.deviceAuthorized) return false;
        if (!status.isActivePremiumNow()) return false;
        // Lindungi dari penggunaan cache offline terlalu lama tanpa verifikasi server (48 jam)
        long age = System.currentTimeMillis() - status.lastSyncAt;
        return status.lastSyncAt > 0 && age <= OFFLINE_GRACE_MS;
    }

    /** Pesan alasan singkat (ramah Toast / pengumuman TalkBack) saat fitur AI terkunci. */
    public static String getAccessDeniedToast(Context ctx) {
        AccountStatus st = getCachedStatus(ctx);
        if (!st.signedIn) {
            return "Belum masuk akun Google. Buka aplikasi Keyboard AI by Anas untuk masuk.";
        }
        if (st.blocked) {
            return "Akun " + st.email + " dinonaktifkan Admin. Hubungi Admin Anas.";
        }
        if (!st.deviceAuthorized) {
            return "Device ID kamu (" + st.deviceId + ") belum terdaftar untuk akun "
                    + st.email + ". Hubungi Admin untuk mendaftarkan HP ini.";
        }
        if (st.isPremium && st.expiresAt > System.currentTimeMillis()
                && (st.lastSyncAt <= 0 || (System.currentTimeMillis() - st.lastSyncAt) > OFFLINE_GRACE_MS)) {
            return "Butuh verifikasi internet (>48 jam offline). Nyalakan data sebentar untuk cek Premium.";
        }
        return "Fitur AI khusus akun Premium. Buka Keyboard AI by Anas untuk aktivasi.";
    }

    public static AccountStatus getCachedStatus(Context ctx) {
        FirebaseUser user = getCurrentUser();
        String deviceId = getDeviceId(ctx);
        if (user == null) {
            return new AccountStatus(false, "", "", "", "free", false, 0L, false, 0L, deviceId, false, true);
        }
        String uid = user.getUid();
        String email = user.getEmail() == null ? "" : user.getEmail().trim();
        String name = user.getDisplayName() == null ? "" : user.getDisplayName().trim();

        SharedPreferences sp = ctx.getSharedPreferences(AUTH_CACHE_PREFS, Context.MODE_PRIVATE);
        String cachedUid = sp.getString("uid", "");
        String plan = sp.getString("plan", "free");
        boolean isPrem = sp.getBoolean("isPremium", false);
        long exp = sp.getLong("expiresAt", 0L);
        boolean blocked = sp.getBoolean("blocked", false);
        boolean bonusGrok = sp.getBoolean("bonusGrokAllowed", false);
        boolean deviceAuth = sp.getBoolean("deviceAuthorized", true);
        long lastSync = sp.getLong("lastSyncAt", 0L);
        String sig = sp.getString("sig", "");

        if (!uid.equals(cachedUid) || !verifySignature(ctx, uid, email, plan, isPrem, exp, blocked, deviceAuth, lastSync, sig)) {
            return new AccountStatus(true, uid, email, name, "free", false, 0L, false, 0L, deviceId, false, true);
        }
        return new AccountStatus(true, uid, email, name, plan, isPrem, exp, blocked, lastSync, deviceId, bonusGrok, deviceAuth);
    }

    public static boolean hasBonusGrok(Context ctx) {
        return hasBonusKey(ctx, "Grok (xAI)");
    }

    /** Ambil kunci Grok bonus (kompatibel dengan sistem lama maupun multi-provider). */
    public static String getBonusGrokKey(Context ctx) {
        return getBonusKey(ctx, "Grok (xAI)");
    }

    public static String getBonusGrokDefaultModel(Context ctx) {
        String m = getBonusDefaultModel(ctx, "Grok (xAI)");
        return m.isEmpty() ? "grok-3-mini" : m;
    }

    private static JSONObject readBonusProvidersCache(Context ctx) {
        if (ctx == null || !isPremiumActive(ctx)) return new JSONObject();
        SharedPreferences sp = ctx.getSharedPreferences(AUTH_CACHE_PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString("bonusProvidersJson", "");
        if (raw == null || raw.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public static boolean hasAnyBonus(Context ctx) {
        if (ctx == null || !isPremiumActive(ctx)) return false;
        JSONObject obj = readBonusProvidersCache(ctx);
        if (obj.length() > 0) return true;
        return hasBonusKey(ctx, "Grok (xAI)");
    }

    public static String getBonusSummaryLabel(Context ctx) {
        if (ctx == null || !isPremiumActive(ctx)) return "";
        JSONObject obj = readBonusProvidersCache(ctx);
        List<String> names = new ArrayList<>();
        java.util.Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONObject entry = obj.optJSONObject(k);
            if (entry != null && !entry.optString("keyObf", "").isEmpty()) {
                String lbl = entry.optString("label", "");
                names.add(lbl.isEmpty() ? k : (k + " [" + lbl + "]"));
            }
        }
        if (names.isEmpty()) {
            SharedPreferences sp = ctx.getSharedPreferences(AUTH_CACHE_PREFS, Context.MODE_PRIVATE);
            if (sp.getBoolean("bonusGrokEnabled", false) && !sp.getString("bonusGrokKeyObf", "").isEmpty()) {
                names.add("Grok (xAI)");
            }
        }
        return android.text.TextUtils.join(", ", names);
    }

    public static boolean hasBonusKey(Context ctx, String provider) {
        String k = getBonusKey(ctx, provider);
        return k != null && !k.isEmpty();
    }

    /** Mengambil Bonus API Key terdekripsi untuk provider mana pun jika dialokasikan Admin ke user. */
    public static String getBonusKey(Context ctx, String provider) {
        if (ctx == null || provider == null || !isPremiumActive(ctx)) return "";
        AccountStatus st = getCachedStatus(ctx);
        if (st.uid.isEmpty()) return "";

        JSONObject obj = readBonusProvidersCache(ctx);
        JSONObject entry = obj.optJSONObject(provider);
        if (entry != null) {
            String obf = entry.optString("keyObf", "");
            if (!obf.isEmpty()) {
                String dec = deobfuscateKey(obf, st.uid);
                if (!dec.isEmpty()) return dec;
            }
        }

        // Fallback cache lama khusus Grok
        if ("Grok (xAI)".equals(provider) && st.isBonusGrokEligibleNow()) {
            SharedPreferences sp = ctx.getSharedPreferences(AUTH_CACHE_PREFS, Context.MODE_PRIVATE);
            if (sp.getBoolean("bonusGrokEnabled", false)) {
                String obf = sp.getString("bonusGrokKeyObf", "");
                if (obf != null && !obf.isEmpty()) return deobfuscateKey(obf, st.uid);
            }
        }
        return "";
    }

    public static String getBonusDefaultModel(Context ctx, String provider) {
        if (ctx == null || provider == null) return "";
        JSONObject obj = readBonusProvidersCache(ctx);
        JSONObject entry = obj.optJSONObject(provider);
        if (entry != null) {
            String m = entry.optString("defaultModel", "").trim();
            if (!m.isEmpty()) return m;
        }
        if ("Grok (xAI)".equals(provider)) {
            String m = ctx.getSharedPreferences(AUTH_CACHE_PREFS, Context.MODE_PRIVATE)
                    .getString("bonusGrokModel", "grok-3-mini");
            return (m == null || m.trim().isEmpty()) ? "grok-3-mini" : m.trim();
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static List<String> readStringList(DocumentSnapshot doc, String field) {
        List<String> out = new ArrayList<>();
        if (doc == null) return out;
        Object raw = doc.get(field);
        if (raw instanceof List<?>) {
            for (Object o : (List<?>) raw) {
                if (o != null) {
                    String s = o.toString().trim();
                    if (!s.isEmpty() && !out.contains(s)) out.add(s);
                }
            }
        }
        return out;
    }

    private static List<String> readBonusKeyIds(DocumentSnapshot doc) {
        return readStringList(doc, "bonusKeyIds");
    }

    // =========================================================================
    // REAL-TIME SILENT GUARD (Aktif HANYA saat Keyboard / Pengaturan tampil)
    // =========================================================================
    private static final Object REALTIME_LOCK = new Object();
    private static final Map<String, Runnable> REALTIME_GUARDS = new LinkedHashMap<>();
    private static ListenerRegistration realtimeUserReg;
    private static ListenerRegistration realtimePreReg;
    private static String realtimeUid = "";
    private static DocumentSnapshot latestRealtimeUserDoc;
    private static DocumentSnapshot latestRealtimePreDoc;
    private static String lastRealtimeStateKey = "";

    public static void startRealtimePremiumGuard(final Context ctx, final String tag, final Runnable onStateChanged) {
        if (ctx == null || tag == null || tag.isEmpty()) return;
        final FirebaseUser user = getCurrentUser();
        if (user == null) {
            stopAllRealtimeGuards();
            return;
        }
        final Context appCtx = ctx.getApplicationContext();
        final String uid = user.getUid();

        boolean needAttach;
        synchronized (REALTIME_LOCK) {
            REALTIME_GUARDS.put(tag, onStateChanged);
            needAttach = (realtimeUserReg == null || !uid.equals(realtimeUid));
            if (needAttach) {
                detachRealtimeListenersLocked();
                realtimeUid = uid;
                latestRealtimeUserDoc = null;
                latestRealtimePreDoc = null;
            }
        }
        if (!needAttach) return;

        try {
            final FirebaseFirestore db = FirebaseFirestore.getInstance();
            final String email = user.getEmail() == null ? "" : user.getEmail().trim();
            final String emailLower = email.toLowerCase(Locale.ROOT);
            final String deviceId = getDeviceId(appCtx);
            final String deviceModel = Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")";

            synchronized (REALTIME_LOCK) {
                if (REALTIME_GUARDS.isEmpty() || !uid.equals(realtimeUid)) return;
                realtimeUserReg = db.collection("users").document(uid)
                        .addSnapshotListener((snap, err) -> {
                            if (err != null || snap == null) return;
                            if (!snap.exists()) {
                                // Akun baru belum punya dokumen Firestore -> sinkron sekali di background
                                syncUserFromServer(appCtx, false, new SyncCallback() {
                                    @Override public void onSynced(AccountStatus status) { notifyRealtimeGuards(); }
                                    @Override public void onError(String message) {}
                                });
                                return;
                            }
                            synchronized (REALTIME_LOCK) {
                                latestRealtimeUserDoc = snap;
                            }
                            applyRealtimeSnapshot(appCtx, db, user, uid, email, deviceId, deviceModel);
                        });

                if (!emailLower.isEmpty()) {
                    realtimePreReg = db.collection("preapproved").document(emailLower)
                            .addSnapshotListener((snap, err) -> {
                                if (err != null) return;
                                synchronized (REALTIME_LOCK) {
                                    latestRealtimePreDoc = snap;
                                }
                                applyRealtimeSnapshot(appCtx, db, user, uid, email, deviceId, deviceModel);
                            });
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void stopRealtimePremiumGuard(final String tag) {
        if (tag == null || tag.isEmpty()) return;
        synchronized (REALTIME_LOCK) {
            REALTIME_GUARDS.remove(tag);
            if (REALTIME_GUARDS.isEmpty()) {
                detachRealtimeListenersLocked();
            }
        }
    }

    public static void stopAllRealtimeGuards() {
        synchronized (REALTIME_LOCK) {
            REALTIME_GUARDS.clear();
            detachRealtimeListenersLocked();
        }
    }

    private static void detachRealtimeListenersLocked() {
        try { if (realtimeUserReg != null) realtimeUserReg.remove(); } catch (Throwable ignored) {}
        try { if (realtimePreReg != null) realtimePreReg.remove(); } catch (Throwable ignored) {}
        realtimeUserReg = null;
        realtimePreReg = null;
        realtimeUid = "";
        latestRealtimeUserDoc = null;
        latestRealtimePreDoc = null;
    }

    private static void notifyRealtimeGuards() {
        final List<Runnable> callbacks;
        synchronized (REALTIME_LOCK) {
            if (REALTIME_GUARDS.isEmpty()) return;
            callbacks = new ArrayList<>();
            for (Runnable r : REALTIME_GUARDS.values()) {
                if (r != null) callbacks.add(r);
            }
        }
        if (callbacks.isEmpty()) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            for (Runnable r : callbacks) {
                try { r.run(); } catch (Throwable ignored) {}
            }
        });
    }

    private static void applyRealtimeSnapshot(final Context appCtx, final FirebaseFirestore db,
                                              final FirebaseUser user, final String uid, final String email,
                                              final String deviceId, final String deviceModel) {
        final DocumentSnapshot userDoc;
        final DocumentSnapshot preDoc;
        synchronized (REALTIME_LOCK) {
            userDoc = latestRealtimeUserDoc;
            preDoc = latestRealtimePreDoc;
        }
        if (userDoc == null || !userDoc.exists()) return;

        // Jangan perpanjang jam offline dari cache lokal; hanya snapshot server asli yang reset lastSyncAt.
        boolean fromServer = !userDoc.getMetadata().isFromCache()
                && (preDoc == null || !preDoc.getMetadata().isFromCache());

        ResolvedAccountState state = resolveMergedAccountState(appCtx, uid, email, deviceId, userDoc, preDoc);
        long syncAt;
        if (fromServer) {
            syncAt = System.currentTimeMillis();
        } else {
            SharedPreferences sp = appCtx.getSharedPreferences(AUTH_CACHE_PREFS, Context.MODE_PRIVATE);
            syncAt = uid.equals(sp.getString("uid", "")) ? sp.getLong("lastSyncAt", 0L) : 0L;
        }

        // Jika dokumen lama belum punya allowedDeviceIds saat snapshot server tiba, inisialisasi 1x
        if (fromServer && state.needsSeedAllowedIds && !state.seededAllowedIds.isEmpty()) {
            Map<String, Object> seedPatch = new HashMap<>();
            seedPatch.put("allowedDeviceIds", state.seededAllowedIds);
            if (!state.deviceAuthorized) {
                seedPatch.put("unregisteredDeviceId", deviceId);
                seedPatch.put("unregisteredDeviceModel", deviceModel);
                seedPatch.put("unregisteredAt", System.currentTimeMillis());
            }
            db.collection("users").document(uid).set(seedPatch, SetOptions.merge());
        } else if (fromServer && !state.deviceAuthorized) {
            String prevUnreg = safeString(userDoc.getString("unregisteredDeviceId"), "");
            if (!deviceId.equals(prevUnreg)) {
                Map<String, Object> unregPatch = new HashMap<>();
                unregPatch.put("unregisteredDeviceId", deviceId);
                unregPatch.put("unregisteredDeviceModel", deviceModel);
                unregPatch.put("unregisteredAt", System.currentTimeMillis());
                db.collection("users").document(uid).set(unregPatch, SetOptions.merge());
            }
        }

        saveCache(appCtx, uid, email, state.plan, state.isPrem, state.exp,
                state.blocked, state.bonusGrokAllowed, state.deviceAuthorized, syncAt);

        LinkedHashSet<String> fetchIds = new LinkedHashSet<>(state.assignedKeyIds);
        if (state.bonusGrokAllowed) fetchIds.add("grok_yearly");

        String stateKey = uid + "|" + state.plan + "|" + state.isPrem + "|" + state.exp + "|"
                + state.blocked + "|" + state.deviceAuthorized + "|" + state.bonusGrokAllowed + "|" + fetchIds;

        boolean changed;
        synchronized (REALTIME_LOCK) {
            changed = !stateKey.equals(lastRealtimeStateKey);
            lastRealtimeStateKey = stateKey;
        }

        if (!state.isActiveNow()) {
            saveBonusProvidersCache(appCtx, new JSONObject());
            saveBonusGrokCache(appCtx, uid, false, "", "grok-3-mini");
            if (changed) notifyRealtimeGuards();
            return;
        }

        if (changed) {
            fetchAndSaveBonusKeys(appCtx, db, uid, fetchIds, AuthManager::notifyRealtimeGuards);
        }
    }

    private static final class ResolvedAccountState {
        String plan;
        boolean isPrem;
        long exp;
        boolean blocked;
        boolean bonusGrokAllowed;
        boolean deviceAuthorized;
        boolean needsSeedAllowedIds;
        List<String> seededAllowedIds;
        List<String> assignedKeyIds;

        boolean isActiveNow() {
            return !blocked && isPrem && deviceAuthorized && exp > System.currentTimeMillis();
        }
    }

    private static ResolvedAccountState resolveMergedAccountState(Context ctx, String uid, String email,
                                                                String deviceId, DocumentSnapshot userDoc,
                                                                DocumentSnapshot preDoc) {
        ResolvedAccountState out = new ResolvedAccountState();
        String basePlan = safeString(userDoc.getString("plan"), "free");
        boolean basePrem = Boolean.TRUE.equals(userDoc.getBoolean("isPremium"));
        long baseExp = readTimestampMillis(userDoc, "expiresAt");
        boolean baseBlocked = Boolean.TRUE.equals(userDoc.getBoolean("blocked"));
        boolean baseBonus = Boolean.TRUE.equals(userDoc.getBoolean("bonusGrokAllowed"));
        List<String> baseKeyIds = readStringList(userDoc, "bonusKeyIds");

        List<String> docAllowed = readStringList(userDoc, "allowedDeviceIds");
        String legacyDev = safeString(userDoc.getString("deviceId"), "");
        out.needsSeedAllowedIds = docAllowed.isEmpty();
        out.seededAllowedIds = new ArrayList<>();
        if (docAllowed.isEmpty()) {
            String ownerId = !legacyDev.isEmpty() ? legacyDev : deviceId;
            if (!ownerId.isEmpty()) out.seededAllowedIds.add(ownerId);
        }

        LinkedHashSet<String> allowedSet = new LinkedHashSet<>(docAllowed.isEmpty() ? out.seededAllowedIds : docAllowed);

        String finalPlan = basePlan;
        boolean finalPrem = basePrem;
        long finalExp = baseExp;
        boolean finalBlocked = baseBlocked;
        boolean finalBonus = baseBonus;
        List<String> finalKeys = new ArrayList<>(baseKeyIds);

        if (preDoc != null && preDoc.exists()) {
            if (Boolean.TRUE.equals(preDoc.getBoolean("blocked"))) finalBlocked = true;
            allowedSet.addAll(readStringList(preDoc, "allowedDeviceIds"));

            String prePlan = safeString(preDoc.getString("plan"), "free");
            boolean prePrem = Boolean.TRUE.equals(preDoc.getBoolean("isPremium"));
            long preExp = readTimestampMillis(preDoc, "expiresAt");
            boolean preBonus = Boolean.TRUE.equals(preDoc.getBoolean("bonusGrokAllowed"));
            List<String> preKeyIds = readStringList(preDoc, "bonusKeyIds");
            long now = System.currentTimeMillis();

            boolean preValid = !finalBlocked && prePrem && preExp > now;
            boolean baseValid = !baseBlocked && basePrem && baseExp > now;
            if (preValid && (!baseValid || preExp > baseExp)) {
                finalPlan = prePlan;
                finalPrem = true;
                finalExp = preExp;
                finalBonus = preBonus;
                for (String kId : preKeyIds) {
                    if (!finalKeys.contains(kId)) finalKeys.add(kId);
                }
            }
        }

        out.plan = finalPlan;
        out.isPrem = finalPrem;
        out.exp = finalExp;
        out.blocked = finalBlocked;
        out.bonusGrokAllowed = finalBonus;
        out.assignedKeyIds = finalKeys;
        out.deviceAuthorized = allowedSet.isEmpty() || allowedSet.contains(deviceId);
        return out;
    }

    /** Sinkronisasi status akun dari Firestore (`users/{uid}` dan `preapproved/{emailLower}`). */
    public static void syncUserFromServer(final Context ctx, final boolean recordLoginEvent, final SyncCallback cb) {
        final FirebaseUser user = getCurrentUser();
        if (user == null) {
            clearCache(ctx);
            if (cb != null) cb.onError("Belum login.");
            return;
        }

        final String uid = user.getUid();
        final String email = user.getEmail() == null ? "" : user.getEmail().trim();
        final String emailLower = email.toLowerCase(Locale.ROOT);
        final String name = user.getDisplayName() == null ? "Pengguna" : user.getDisplayName().trim();
        final String photo = user.getPhotoUrl() != null ? user.getPhotoUrl().toString() : "";
        final String deviceId = getDeviceId(ctx);
        final String deviceModel = Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")";

        final FirebaseFirestore db = FirebaseFirestore.getInstance();
        final DocumentReference userRef = db.collection("users").document(uid);

        userRef.get().addOnSuccessListener(userDoc -> {
            long now = System.currentTimeMillis();
            if (!userDoc.exists()) {
                Map<String, Object> newUser = new HashMap<>();
                newUser.put("uid", uid);
                newUser.put("email", email);
                newUser.put("emailLower", emailLower);
                newUser.put("displayName", name);
                newUser.put("photoUrl", photo);
                newUser.put("plan", "free");
                newUser.put("isPremium", false);
                newUser.put("blocked", false);
                newUser.put("expiresAt", null);
                newUser.put("bonusGrokAllowed", false);
                newUser.put("bonusKeyIds", new ArrayList<String>());
                newUser.put("deviceId", deviceId);
                newUser.put("allowedDeviceIds", Collections.singletonList(deviceId));
                newUser.put("deviceModel", deviceModel);
                newUser.put("createdAt", now);
                newUser.put("lastLoginAt", now);
                userRef.set(newUser).addOnCompleteListener(t ->
                        checkPreapprovedAndFinish(ctx, db, user, uid, email, emailLower, name,
                                deviceId, deviceModel, "free", false, 0L, false, false,
                                new ArrayList<>(), Collections.singletonList(deviceId), recordLoginEvent, cb));
            } else {
                String docPlan = safeString(userDoc.getString("plan"), "free");
                boolean docPrem = Boolean.TRUE.equals(userDoc.getBoolean("isPremium"));
                long docExp = readTimestampMillis(userDoc, "expiresAt");
                boolean docBlocked = Boolean.TRUE.equals(userDoc.getBoolean("blocked"));
                boolean docBonusGrok = Boolean.TRUE.equals(userDoc.getBoolean("bonusGrokAllowed"));
                List<String> docBonusKeyIds = readBonusKeyIds(userDoc);
                List<String> docAllowedIds = readStringList(userDoc, "allowedDeviceIds");
                String legacyDev = safeString(userDoc.getString("deviceId"), "");
                boolean needsSeed = docAllowedIds.isEmpty();
                if (needsSeed) {
                    docAllowedIds = new ArrayList<>();
                    docAllowedIds.add(!legacyDev.isEmpty() ? legacyDev : deviceId);
                }
                boolean devAuthorizedHere = docAllowedIds.contains(deviceId);

                Map<String, Object> patch = new HashMap<>();
                patch.put("emailLower", emailLower);
                patch.put("lastLoginAt", now);
                if (needsSeed) {
                    patch.put("allowedDeviceIds", docAllowedIds);
                }
                if (devAuthorizedHere) {
                    patch.put("displayName", name);
                    patch.put("photoUrl", photo);
                    patch.put("deviceId", deviceId);
                    patch.put("deviceModel", deviceModel);
                } else {
                    patch.put("unregisteredDeviceId", deviceId);
                    patch.put("unregisteredDeviceModel", deviceModel);
                    patch.put("unregisteredAt", now);
                }
                userRef.set(patch, SetOptions.merge());

                checkPreapprovedAndFinish(ctx, db, user, uid, email, emailLower, name,
                        deviceId, deviceModel, docPlan, docPrem, docExp, docBlocked, docBonusGrok,
                        docBonusKeyIds, docAllowedIds, recordLoginEvent, cb);
            }
        }).addOnFailureListener(e -> {
            if (cb != null) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "Gagal terhubung ke server.");
            }
        });
    }

    private static void checkPreapprovedAndFinish(final Context ctx, final FirebaseFirestore db,
                                                  final FirebaseUser user, final String uid,
                                                  final String email, final String emailLower,
                                                  final String name, final String deviceId,
                                                  final String deviceModel, final String basePlan,
                                                  final boolean basePrem, final long baseExp,
                                                  final boolean baseBlocked, final boolean baseBonusGrok,
                                                  final List<String> baseBonusKeyIds,
                                                  final List<String> baseAllowedDeviceIds,
                                                  final boolean recordLoginEvent, final SyncCallback cb) {
        if (emailLower.isEmpty()) {
            boolean devAuth = baseAllowedDeviceIds.isEmpty() || baseAllowedDeviceIds.contains(deviceId);
            finalizeSync(ctx, db, user, uid, email, name, deviceId, deviceModel,
                    basePlan, basePrem, baseExp, baseBlocked, baseBonusGrok, baseBonusKeyIds, devAuth, recordLoginEvent, cb);
            return;
        }
        db.collection("preapproved").document(emailLower).get()
                .addOnSuccessListener(preDoc -> {
                    String finalPlan = basePlan;
                    boolean finalPrem = basePrem;
                    long finalExp = baseExp;
                    boolean finalBlocked = baseBlocked;
                    boolean finalBonusGrok = baseBonusGrok;
                    List<String> finalKeyIds = new ArrayList<>(baseBonusKeyIds);
                    LinkedHashSet<String> allowedSet = new LinkedHashSet<>(baseAllowedDeviceIds);

                    if (preDoc.exists()) {
                        boolean preBlocked = Boolean.TRUE.equals(preDoc.getBoolean("blocked"));
                        if (preBlocked) finalBlocked = true;
                        allowedSet.addAll(readStringList(preDoc, "allowedDeviceIds"));

                        String prePlan = safeString(preDoc.getString("plan"), "free");
                        boolean prePrem = Boolean.TRUE.equals(preDoc.getBoolean("isPremium"));
                        long preExp = readTimestampMillis(preDoc, "expiresAt");
                        boolean preBonus = Boolean.TRUE.equals(preDoc.getBoolean("bonusGrokAllowed"));
                        List<String> preKeyIds = readBonusKeyIds(preDoc);
                        long now = System.currentTimeMillis();

                        boolean preValid = !preBlocked && prePrem && preExp > now;
                        boolean baseValid = !baseBlocked && basePrem && baseExp > now;

                        if (preValid && (!baseValid || preExp > baseExp)) {
                            finalPlan = prePlan;
                            finalPrem = true;
                            finalExp = preExp;
                            finalBonusGrok = preBonus;
                            for (String kId : preKeyIds) {
                                if (!finalKeyIds.contains(kId)) finalKeyIds.add(kId);
                            }
                        }
                    }

                    boolean devAuth = allowedSet.isEmpty() || allowedSet.contains(deviceId);
                    finalizeSync(ctx, db, user, uid, email, name, deviceId, deviceModel,
                            finalPlan, finalPrem, finalExp, finalBlocked, finalBonusGrok, finalKeyIds, devAuth, recordLoginEvent, cb);
                })
                .addOnFailureListener(ignored -> {
                    boolean devAuth = baseAllowedDeviceIds.isEmpty() || baseAllowedDeviceIds.contains(deviceId);
                    finalizeSync(ctx, db, user, uid, email, name, deviceId, deviceModel,
                            basePlan, basePrem, baseExp, baseBlocked, baseBonusGrok, baseBonusKeyIds, devAuth, recordLoginEvent, cb);
                });
    }

    private static void finalizeSync(final Context ctx, final FirebaseFirestore db,
                                     final FirebaseUser user, final String uid,
                                     final String email, final String name, final String deviceId,
                                     final String deviceModel, String plan, boolean isPrem,
                                     long exp, boolean blocked, boolean bonusGrokAllowed,
                                     final List<String> assignedKeyIds, boolean deviceAuthorized,
                                     boolean recordLoginEvent, final SyncCallback cb) {
        long now = System.currentTimeMillis();
        saveCache(ctx, uid, email, plan, isPrem, exp, blocked, bonusGrokAllowed, deviceAuthorized, now);

        final AccountStatus status = new AccountStatus(true, uid, email, name, plan, isPrem, exp,
                blocked, now, deviceId, bonusGrokAllowed, deviceAuthorized);

        if (shouldWriteLoginLog(ctx, uid, recordLoginEvent)) {
            writeLoginLogAndNotify(ctx, db, user, status, deviceId, deviceModel, now);
        }

        if (!status.isActivePremiumNow()) {
            saveBonusProvidersCache(ctx, new JSONObject());
            saveBonusGrokCache(ctx, uid, false, "", "grok-3-mini");
            if (cb != null) new Handler(Looper.getMainLooper()).post(() -> cb.onSynced(status));
            return;
        }

        LinkedHashSet<String> fetchIds = new LinkedHashSet<>(assignedKeyIds);
        if (bonusGrokAllowed) fetchIds.add("grok_yearly");

        fetchAndSaveBonusKeys(ctx, db, uid, fetchIds, () -> {
            if (cb != null) new Handler(Looper.getMainLooper()).post(() -> cb.onSynced(status));
        });
    }

    private static void fetchAndSaveBonusKeys(final Context ctx, final FirebaseFirestore db, final String uid,
                                              final LinkedHashSet<String> fetchIds, final Runnable onDone) {
        if (fetchIds == null || fetchIds.isEmpty()) {
            saveBonusProvidersCache(ctx, new JSONObject());
            saveBonusGrokCache(ctx, uid, false, "", "grok-3-mini");
            if (onDone != null) onDone.run();
            return;
        }

        final JSONObject providersMap = new JSONObject();
        final AtomicInteger remaining = new AtomicInteger(fetchIds.size());
        final String[] grokFallback = {"", "grok-3-mini"};

        for (final String keyId : fetchIds) {
            db.collection("bonus_keys").document(keyId).get()
                    .addOnCompleteListener(task -> {
                        try {
                            if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                                DocumentSnapshot kDoc = task.getResult();
                                boolean enabled = Boolean.TRUE.equals(kDoc.getBoolean("enabled"));
                                String apiKey = safeString(kDoc.getString("apiKey"), "");
                                if (enabled && !apiKey.isEmpty()) {
                                    String prov = safeString(kDoc.getString("provider"),
                                            "grok_yearly".equals(keyId) ? "Grok (xAI)" : "Google Gemini");
                                    String label = safeString(kDoc.getString("label"), keyId);
                                    String defModel = safeString(kDoc.getString("defaultModel"), "");
                                    JSONObject entry = new JSONObject();
                                    entry.put("keyId", keyId);
                                    entry.put("label", label);
                                    entry.put("keyObf", obfuscateKey(apiKey, uid));
                                    entry.put("defaultModel", defModel);
                                    synchronized (providersMap) {
                                        providersMap.put(prov, entry);
                                        if ("Grok (xAI)".equals(prov)) {
                                            grokFallback[0] = apiKey;
                                            if (!defModel.isEmpty()) grokFallback[1] = defModel;
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}

                        if (remaining.decrementAndGet() == 0) {
                            saveBonusProvidersCache(ctx, providersMap);
                            saveBonusGrokCache(ctx, uid, !grokFallback[0].isEmpty(), grokFallback[0], grokFallback[1]);
                            if (onDone != null) onDone.run();
                        }
                    });
        }
    }

    private static void saveBonusProvidersCache(Context ctx, JSONObject map) {
        if (ctx == null) return;
        ctx.getSharedPreferences(AUTH_CACHE_PREFS, Context.MODE_PRIVATE).edit()
                .putString("bonusProvidersJson", map == null ? "{}" : map.toString())
                .apply();
    }

    private static boolean shouldWriteLoginLog(Context ctx, String uid, boolean forced) {
        SharedPreferences sp = ctx.getSharedPreferences(AUTH_CACHE_PREFS, Context.MODE_PRIVATE);
        long lastLogMs = sp.getLong("lastLogMs", 0L);
        String lastLogUid = sp.getString("lastLogUid", "");
        long now = System.currentTimeMillis();
        if (forced || !uid.equals(lastLogUid) || (now - lastLogMs) > LOGIN_LOG_COOLDOWN_MS) {
            sp.edit().putLong("lastLogMs", now).putString("lastLogUid", uid).apply();
            return true;
        }
        return false;
    }

    private static void writeLoginLogAndNotify(final Context ctx, final FirebaseFirestore db,
                                               final FirebaseUser user, final AccountStatus status,
                                               final String deviceId, final String deviceModel, final long now) {
        Map<String, Object> log = new HashMap<>();
        log.put("uid", status.uid);
        log.put("email", status.email);
        log.put("emailLower", status.email.toLowerCase(Locale.ROOT));
        log.put("displayName", status.displayName);
        log.put("plan", status.plan);
        log.put("isPremium", status.isActivePremiumNow());
        log.put("expiresAt", status.expiresAt > 0 ? status.expiresAt : null);
        log.put("deviceId", deviceId);
        log.put("deviceModel", deviceModel);
        log.put("timestamp", now);
        db.collection("login_logs").add(log);
    }

    private static void saveCache(Context ctx, String uid, String email, String plan,
                                  boolean isPrem, long exp, boolean blocked, boolean bonusGrokAllowed,
                                  boolean deviceAuthorized, long syncAt) {
        String sig = computeSignature(ctx, uid, email, plan, isPrem, exp, blocked, deviceAuthorized, syncAt);
        ctx.getSharedPreferences(AUTH_CACHE_PREFS, Context.MODE_PRIVATE).edit()
                .putString("uid", uid)
                .putString("email", email)
                .putString("plan", plan)
                .putBoolean("isPremium", isPrem)
                .putLong("expiresAt", exp)
                .putBoolean("blocked", blocked)
                .putBoolean("bonusGrokAllowed", bonusGrokAllowed)
                .putBoolean("deviceAuthorized", deviceAuthorized)
                .putLong("lastSyncAt", syncAt)
                .putString("sig", sig)
                .apply();
    }

    private static void saveBonusGrokCache(Context ctx, String uid, boolean enabled, String key, String model) {
        String obf = (enabled && key != null && !key.isEmpty()) ? obfuscateKey(key, uid) : "";
        ctx.getSharedPreferences(AUTH_CACHE_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("bonusGrokEnabled", enabled && !obf.isEmpty())
                .putString("bonusGrokKeyObf", obf)
                .putString("bonusGrokModel", model == null ? "grok-3-mini" : model)
                .apply();
    }

    private static String obfuscateKey(String raw, String uid) {
        byte[] data = raw.getBytes(StandardCharsets.UTF_8);
        byte[] k = (uid + INTEGRITY_PEPPER).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) out[i] = (byte) (data[i] ^ k[i % k.length]);
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP);
    }

    private static String deobfuscateKey(String obf, String uid) {
        try {
            byte[] data = android.util.Base64.decode(obf, android.util.Base64.NO_WRAP);
            byte[] k = (uid + INTEGRITY_PEPPER).getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[data.length];
            for (int i = 0; i < data.length; i++) out[i] = (byte) (data[i] ^ k[i % k.length]);
            return new String(out, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static void clearCache(Context ctx) {
        try {
            ctx.getSharedPreferences(AUTH_CACHE_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Throwable ignored) {}
    }

    private static boolean verifySignature(Context ctx, String uid, String email, String plan,
                                           boolean isPrem, long exp, boolean blocked, boolean deviceAuthorized,
                                           long syncAt, String actualSig) {
        if (actualSig == null || actualSig.isEmpty()) return false;
        return actualSig.equals(computeSignature(ctx, uid, email, plan, isPrem, exp, blocked, deviceAuthorized, syncAt));
    }

    private static String computeSignature(Context ctx, String uid, String email, String plan,
                                           boolean isPrem, long exp, boolean blocked, boolean deviceAuthorized, long syncAt) {
        String androidId = "";
        try {
            androidId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {}
        String raw = uid + "|" + email + "|" + plan + "|" + isPrem + "|" + exp + "|"
                + blocked + "|" + deviceAuthorized + "|" + syncAt + "|" + androidId + "|" + INTEGRITY_PEPPER;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(raw.hashCode());
        }
    }

    private static long readTimestampMillis(DocumentSnapshot doc, String field) {
        Object val = doc.get(field);
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof Timestamp) return ((Timestamp) val).toDate().getTime();
        return 0L;
    }

    private static String safeString(String v, String def) {
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    /** Dialog ramah TalkBack/Jieshuo saat user Free atau Device ID belum terdaftar mengakses fitur AI. */
    public static void showPremiumRequiredDialog(final Activity act, final String featureName,
                                                 final Runnable onRefreshedIfActive) {
        if (act == null || act.isFinishing()) return;
        final AccountStatus st = getCachedStatus(act);

        if (!st.deviceAuthorized) {
            showDeviceNotRegisteredDialog(act, st, onRefreshedIfActive);
            return;
        }

        String msg;
        if (st.blocked) {
            msg = "Akun Google Anda (" + st.email + ") sedang dinonaktifkan oleh Admin.\n\n"
                    + "Silakan hubungi Admin Anugerah Anas jika ini keliru.";
        } else {
            msg = "Fitur \"" + featureName + "\" khusus untuk akun AI Premium.\n\n"
                    + "• Mengetik keyboard, Braille, Makro, Kamus & Editor teks biasa tetap GRATIS.\n"
                    + "• Kunci API AI menggunakan kunci pribadi masing-masing (bonus Grok hanya untuk promo tertentu dari Admin).\n\n"
                    + "Paket Akses AI (Rp 20.000 / bulan):\n"
                    + "• 1 Bulan: Rp 20.000\n"
                    + "• 2 Bulan: Rp 40.000\n"
                    + "• 3 Bulan: Rp 60.000\n"
                    + "• 6 Bulan: Rp 120.000\n"
                    + "• 1 Tahun: Rp 200.000\n"
                    + "• 2 Tahun: Rp 400.000\n"
                    + "• 3 Tahun: Rp 600.000\n\n"
                    + "Akun: " + st.email + "\n"
                    + "ID Akun: " + st.uid + "\n"
                    + "ID Perangkat: " + st.deviceId + "\n"
                    + "Status: " + st.getReadablePlanLabel();
        }

        UiKit.dialog(act, act.getSharedPreferences("AnnasKeyboardPrefs", Context.MODE_PRIVATE))
                .setTitle(st.blocked ? "Akun Dibatasi" : "Fitur AI Premium")
                .setMessage(msg)
                .setNeutralButton("Segarkan Status", (d, w) -> {
                    Toast.makeText(act, "Mengecek status akun ke server...", Toast.LENGTH_SHORT).show();
                    syncUserFromServer(act, false, new SyncCallback() {
                        @Override public void onSynced(AccountStatus status) {
                            if (status.isActivePremiumNow()) {
                                Toast.makeText(act, "Mantap! Akun Premium lu sudah aktif!", Toast.LENGTH_LONG).show();
                                if (onRefreshedIfActive != null) onRefreshedIfActive.run();
                            } else if (!status.deviceAuthorized) {
                                showDeviceNotRegisteredDialog(act, status, onRefreshedIfActive);
                            } else {
                                Toast.makeText(act, "Status masih: " + status.getReadablePlanLabel(), Toast.LENGTH_LONG).show();
                            }
                        }
                        @Override public void onError(String message) {
                            Toast.makeText(act, "Gagal cek server: " + message, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setPositiveButton("Request via WhatsApp", (d, w) -> showWhatsAppRequestPicker(act, st))
                .show();
    }

    public static void showDeviceNotRegisteredDialog(final Activity act, final AccountStatus st,
                                                     final Runnable onAuthorizedNow) {
        if (act == null || act.isFinishing() || st == null) return;
        final String brandModel = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        final String msg = "Device ID kamu belum terdaftar untuk akun ini (" + st.email + ").\n\n"
                + "Agar akun yang sama bisa dipakai di HP ini, daftarkan Device ID kamu dengan menghubungi Admin/Member. Kirimkan data di bawah ini ke Admin biar tinggal dipaste ke panel akun kamu:\n\n"
                + "• Akun Google: " + st.email + "\n"
                + "• Nama: " + st.displayName + "\n"
                + "• Device ID Baru: " + st.deviceId + "\n"
                + "• Tipe HP: " + brandModel + "\n"
                + "• UID Akun: " + st.uid;

        final String copyPayload = "Pendaftaran Device ID Tambahan Keyboard AI by Anas:\n"
                + "Akun: " + st.email + " (" + st.displayName + ")\n"
                + "UID: " + st.uid + "\n"
                + "Device ID Baru: " + st.deviceId + "\n"
                + "Tipe HP: " + brandModel;

        UiKit.dialog(act, OfficeUtils.mainPrefs(act))
                .setTitle("Device ID Belum Terdaftar")
                .setMessage(msg)
                .setPositiveButton("Hubungi Admin (WA)", (d, w) -> openWhatsAppDeviceRegistration(act, st))
                .setNeutralButton("Salin Device ID", (d, w) -> {
                    ClipboardManager cb = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cb != null) {
                        cb.setPrimaryClip(ClipData.newPlainText("Device ID Baru", copyPayload));
                    }
                    Toast.makeText(act, "Akun & Device ID (" + st.deviceId + ") disalin ke Clipboard! Tinggal kirim ke Admin.", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cek Status Ulang", (d, w) -> {
                    Toast.makeText(act, "Memeriksa apakah Device ID sudah didaftarkan...", Toast.LENGTH_SHORT).show();
                    syncUserFromServer(act, false, new SyncCallback() {
                        @Override public void onSynced(AccountStatus updated) {
                            if (updated.deviceAuthorized) {
                                Toast.makeText(act, "Device ID terdaftar! Status: " + updated.getReadablePlanLabel(), Toast.LENGTH_LONG).show();
                                if (onAuthorizedNow != null) onAuthorizedNow.run();
                            } else {
                                Toast.makeText(act, "Device ID masih belum terdaftar untuk " + updated.email, Toast.LENGTH_LONG).show();
                                showDeviceNotRegisteredDialog(act, updated, onAuthorizedNow);
                            }
                        }
                        @Override public void onError(String message) {
                            Toast.makeText(act, "Gagal cek server: " + message, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .show();
    }

    private static void openWhatsAppDeviceRegistration(Activity act, AccountStatus st) {
        String brandModel = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        String androidVer = "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")";
        String text = "Halo Admin Anas, saya mau daftarkan Device ID HP tambahan untuk akun yang sama di Keyboard AI by Anas:\n\n"
                + "• Akun Google: " + st.email + "\n"
                + "• Nama Google: " + st.displayName + "\n"
                + "• UID Akun: " + st.uid + "\n"
                + "• Device ID Baru: " + st.deviceId + "\n"
                + "• Merek & Tipe HP: " + brandModel + "\n"
                + "• Versi Sistem: " + androidVer;

        ClipboardManager cb = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) {
            cb.setPrimaryClip(ClipData.newPlainText("Daftar Device ID", text));
        }

        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/" + ADMIN_WA_INTL + "?text=" + Uri.encode(text)));
            act.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(act,
                    "WhatsApp tidak ditemukan. Teks Akun & Device ID sudah disalin ke Clipboard — kirim ke 082119059741.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static void showWhatsAppRequestPicker(final Activity act, final AccountStatus st) {
        final String[] paketList = {
                "1 Bulan — Rp 20.000",
                "2 Bulan — Rp 40.000",
                "3 Bulan — Rp 60.000",
                "6 Bulan — Rp 120.000",
                "1 Tahun — Rp 200.000",
                "2 Tahun — Rp 400.000",
                "3 Tahun — Rp 600.000",
                "Konsultasi / Paket Lain"
        };
        UiKit.dialog(act, OfficeUtils.mainPrefs(act))
                .setTitle("Pilih Paket untuk Konfirmasi WA")
                .setItems(paketList, (d, which) -> openWhatsAppRequest(act, st, paketList[which]))
                .setNegativeButton("Batal", null)
                .show();
    }

    private static void openWhatsAppRequest(Activity act, AccountStatus st, String chosenPlan) {
        String brandModel = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        String androidVer = "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")";
        String text = "Halo Admin Anas, saya mau request aktivasi Fitur AI Premium Keyboard AI by Anas:\n\n"
                + "• Paket Dipilih: " + chosenPlan + "\n"
                + "• Nama Google: " + st.displayName + "\n"
                + "• Email Google: " + st.email + "\n"
                + "• UID Akun: " + st.uid + "\n"
                + "• ID Perangkat: " + st.deviceId + "\n"
                + "• Merek & Tipe HP: " + brandModel + "\n"
                + "• Versi Sistem: " + androidVer + "\n"
                + "• Status Saat Ini: " + st.getReadablePlanLabel();

        ClipboardManager cb = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null) {
            cb.setPrimaryClip(ClipData.newPlainText("Konfirmasi Premium", text));
        }

        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/" + ADMIN_WA_INTL + "?text=" + Uri.encode(text)));
            act.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(act,
                    "WhatsApp tidak ditemukan. Data konfirmasi sudah disalin ke Clipboard — kirim ke 082119059741.",
                    Toast.LENGTH_LONG).show();
        }
    }

    public static void openLoginScreen(Context ctx) {
        Intent i = new Intent(ctx, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ctx.startActivity(i);
    }
}
