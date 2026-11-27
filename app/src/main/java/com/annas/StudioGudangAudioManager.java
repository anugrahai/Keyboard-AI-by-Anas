package com.annas;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Studio Gudang & Laboratorium Audio Nyata (100% MP3 Rekaman Asli dari assets/backsound).
 * - Seluruh audio synthesizer lama telah dihapus total.
 * - Memuat 98 file MP3 asli dalam 10 kategori: Islami/Religi, Audio 3D/8D Spatial, Piano Santai/Sedih,
 *   Jazz Cafe, Rock Action, Jedag-Jedug/Phonk/EDM, Tegang/Horor, Sinematik Film, Efek Suara Drama/Film,
 *   serta Backsound & Efek Kartun/Komedi/Suara Orang Ketawa.
 * - Laboratorium Audio memanjang ke bawah: klik Nama Audio langsung Putar/Jeda, sampingnya tombol Jeda,
 *   sampingnya tombol Hapus, dan tombol Simpan/Ekspor berada di paling bawah ujung sebelah kanan.
 */
public final class StudioGudangAudioManager {

    private static final String PREF_DELETED_IDS = "gudang_deleted_ids_json";
    private static final String ASSETS_ROOT = "backsound";

    private static MediaPlayer previewPlayer;
    private static String currentlyPlayingId = "";
    private static boolean previewPaused = false;

    private StudioGudangAudioManager() {}

    public interface AudioPickCallback {
        void onAudioPicked(Uri audioUri, String displayTitle, int durationSec, boolean isBacksoundMusic);
    }

    public static final class GudangItem {
        public final String id;
        public final boolean isBacksound; // true = Backsound Musik, false = Efek Suara Drama/Film/Kartun
        public final String categoryFolder;
        public final String title;
        public final String description;
        public final int durationSec;
        public final String assetPath; // mis. backsound/01_Backsound_Islami_dan_Religi/...mp3

        public GudangItem(String id, boolean isBacksound, String categoryFolder,
                          String title, String description, int durationSec, String assetPath) {
            this.id = id;
            this.isBacksound = isBacksound;
            this.categoryFolder = categoryFolder;
            this.title = title;
            this.description = description;
            this.durationSec = durationSec;
            this.assetPath = assetPath;
        }
    }

    public static final String[] BACKSOUND_CATEGORIES = {
            "Musik Islami, Nasheed & Religi",
            "Musik Audio 3D / 8D Spatial Surround",
            "Musik Piano Santai & Sedih",
            "Musik Jazz & Cafe Klasik",
            "Musik Rock & Energi Action",
            "Musik Jedag-Jedug, Phonk & EDM",
            "Musik Tegang, Horor & Misteri Film",
            "Musik Sinematik Film & Epik",
            "Musik Kartun, Kocak & Komedi"
    };

    public static final String[] FILM_SFX_CATEGORIES = {
            "Efek Film: Kaca Pecah, Rem Tabrakan & Ledakan",
            "Efek Film: Pukulan, Pedang, Tembakan & Laser",
            "Efek Film: Horor, Petir, Sirine & Alam",
            "Efek Film: Transisi Whoosh, Tepuk Tangan & Dramatis",
            "Efek Kartun, Komedi & Suara Orang Ketawa"
    };

    private static List<GudangItem> CACHED_ITEMS;
    private static final Map<String, String> ALIAS_MAP = new HashMap<>();

    static {
        // Alias agar tombol cepat Efek Film di Editor Audio/Video tetap langsung menemukan MP3 aslinya
        ALIAS_MAP.put("sfx_glass_shatter", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_01_Kaca_Pecah_Berantakan_1.m4a");
        ALIAS_MAP.put("sfx_car_crash", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_27_Tabrakan_Mobil_Keras.m4a");
        ALIAS_MAP.put("sfx_tire_screech", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_03_Rem_Mobil_Berdecit_Tajam_1.m4a");
        ALIAS_MAP.put("sfx_big_explosion", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_05_Ledakan_Bom_Besar_1.m4a");
        ALIAS_MAP.put("sfx_distant_blast", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_07_Ledakan_Granat_Besar.m4a");
        ALIAS_MAP.put("sfx_heavy_punch", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_08_Pukulan_Tinju_Keras_1.m4a");
        ALIAS_MAP.put("sfx_martial_kick", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_10_Pukulan_Tendangan_Telak_3.m4a");
        ALIAS_MAP.put("sfx_slap", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_09_Pukulan_Heavy_Action_2.m4a");
        ALIAS_MAP.put("sfx_sword_slash", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_11_Tebasan_Pedang_Tajam.m4a");
        ALIAS_MAP.put("sfx_gunshot", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_12_Tembakan_Senjata_Beruntun.m4a");
        ALIAS_MAP.put("sfx_laser_blast", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_13_Tembakan_Laser_SciFi.m4a");
        ALIAS_MAP.put("sfx_jumpscare", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_21_Jump_Scare_Kejutan_Horor_1.m4a");
        ALIAS_MAP.put("sfx_cinematic_boom", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_24_Dentuman_Dramatis_Dun_Dun_Duuun.m4a");
        ALIAS_MAP.put("sfx_thunder", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_14_Petir_Menggelegar_1.m4a");
        ALIAS_MAP.put("sfx_creaky_door", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_20_Pintu_Tua_Berderit_Horor.m4a");
        ALIAS_MAP.put("sfx_heartbeat", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_30_Gema_Lorong_Bawah_Tanah.m4a");
        ALIAS_MAP.put("sfx_whoosh", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_25_Transisi_Whoosh_Sinematik_1.m4a");
        ALIAS_MAP.put("sfx_applause", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_18_Tepuk_Tangan_Penonton_1.m4a");
        ALIAS_MAP.put("sfx_censor_beep", "backsound/09_Efek_Suara_Film_dan_Drama/SFX_16_Sirine_Polisi_Darurat.m4a");
        ALIAS_MAP.put("sfx_correct_ding", "backsound/10_Backsound_dan_Efek_Kartun_Komedi_Ketawa/Komedi_SFX_10_Drum_Joke_Ba_Dum_Tss.m4a");
        ALIAS_MAP.put("sfx_laugh_clap", "backsound/10_Backsound_dan_Efek_Kartun_Komedi_Ketawa/Komedi_SFX_01_Orang_Ketawa_dan_Tepuk_Tangan.m4a");
        ALIAS_MAP.put("sfx_laugh_funny", "backsound/10_Backsound_dan_Efek_Kartun_Komedi_Ketawa/Komedi_SFX_04_Ketawa_Ngakak_Lucu.m4a");
        ALIAS_MAP.put("sfx_laugh_crowd", "backsound/10_Backsound_dan_Efek_Kartun_Komedi_Ketawa/Komedi_SFX_03_Penonton_Ketawa_Sitcom_Laugh.m4a");
        ALIAS_MAP.put("sfx_baby_laugh", "backsound/10_Backsound_dan_Efek_Kartun_Komedi_Ketawa/Komedi_SFX_05_Bayi_Ketawa_Lucu.m4a");
        ALIAS_MAP.put("sfx_kartun_slip", "backsound/10_Backsound_dan_Efek_Kartun_Komedi_Ketawa/Komedi_SFX_07_Kartun_Terpeleset_Slip_Fall.m4a");
        ALIAS_MAP.put("sfx_kartun_bonk", "backsound/10_Backsound_dan_Efek_Kartun_Komedi_Ketawa/Komedi_SFX_08_Kartun_Bonk_Pukul_Kepala.m4a");
        ALIAS_MAP.put("sfx_sad_trombone", "backsound/10_Backsound_dan_Efek_Kartun_Komedi_Ketawa/Komedi_SFX_09_Sad_Trombone_Wah_Wah_Gagal_Lucu.m4a");
        ALIAS_MAP.put("bgm_islami_1", "backsound/01_Backsound_Islami_dan_Religi/Islami_01_Aahat_Khidmat_1.m4a");
        ALIAS_MAP.put("bgm_piano_1", "backsound/03_Backsound_Piano_Santai_dan_Sedih/Piano_01_Outer_Thoughts_Emosional.m4a");
        ALIAS_MAP.put("bgm_jazz_1", "backsound/04_Backsound_Jazz_dan_Cafe/Jazz_01_Night_in_Venice_Saxophone.m4a");
        ALIAS_MAP.put("bgm_rock_1", "backsound/05_Backsound_Rock_dan_Action/Rock_01_Upbeat_Rock_Guitar.m4a");
        ALIAS_MAP.put("bgm_jj_1", "backsound/06_Backsound_Jedag_Jedug_Phonk_EDM/JedagJedug_01_Phonk_Trap_Beat.m4a");
        ALIAS_MAP.put("bgm_tegang_1", "backsound/07_Backsound_Tegang_Horor_dan_Misteri/Horor_01_Scary_Background_Atmosphere.m4a");
        ALIAS_MAP.put("bgm_epic_1", "backsound/08_Backsound_Sinematik_Film_dan_Epik/Sinematik_01_Strength_of_the_Titans_Trailer.m4a");
        ALIAS_MAP.put("bgm_3d_1", "backsound/02_Backsound_Audio_3D_8D_Spatial/Audio3D_8D_01_Skyfall_8D_Surround.m4a");
        ALIAS_MAP.put("bgm_kartun_1", "backsound/10_Backsound_dan_Efek_Kartun_Komedi_Ketawa/Kartun_Musik_01_Monkeys_Spinning_Monkeys_Lucu.m4a");
    }

    public static synchronized List<GudangItem> getAllBuiltinItems(Context ctx) {
        if (CACHED_ITEMS != null && !CACHED_ITEMS.isEmpty()) return CACHED_ITEMS;
        List<GudangItem> list = new ArrayList<>();
        try {
            cleanLegacySynthesizedWavsIfAny(ctx);
            AssetManager am = ctx.getAssets();
            String[] folders = am.list(ASSETS_ROOT);
            if (folders != null) {
                List<String> sortedFolders = new ArrayList<>();
                Collections.addAll(sortedFolders, folders);
                Collections.sort(sortedFolders);

                for (String folder : sortedFolders) {
                    String[] files = am.list(ASSETS_ROOT + "/" + folder);
                    if (files == null) continue;
                    List<String> sortedFiles = new ArrayList<>();
                    Collections.addAll(sortedFiles, files);
                    Collections.sort(sortedFiles);

                    for (String fn : sortedFiles) {
                        String lowFn = fn.toLowerCase(Locale.ROOT);
                        if (!lowFn.endsWith(".mp3") && !lowFn.endsWith(".m4a")) continue;
                        String assetPath = ASSETS_ROOT + "/" + folder + "/" + fn;
                        boolean isBgm = isBacksoundAsset(folder, fn);
                        String cat = mapCategoryName(folder, fn);
                        String title = prettifyFileName(fn);
                        String desc = isBgm
                                ? "Backsound musik MP3 asli siap pakai (otomatis menyesuaikan durasi video/audio)"
                                : "Efek suara MP3 rekaman asli siap ditempel ke adegan film/video/drama";
                        list.add(new GudangItem(assetPath, isBgm, cat, title, desc, 0, assetPath));
                    }
                }
            }
        } catch (Throwable ignored) {}
        CACHED_ITEMS = list;
        return list;
    }

    private static boolean isBacksoundAsset(String folder, String fn) {
        if (folder.startsWith("09_")) return false;
        if (folder.startsWith("10_") && fn.startsWith("Komedi_SFX_")) return false;
        return true;
    }

    private static String mapCategoryName(String folder, String fn) {
        if (folder.startsWith("01_")) return BACKSOUND_CATEGORIES[0];
        if (folder.startsWith("02_")) return BACKSOUND_CATEGORIES[1];
        if (folder.startsWith("03_")) return BACKSOUND_CATEGORIES[2];
        if (folder.startsWith("04_")) return BACKSOUND_CATEGORIES[3];
        if (folder.startsWith("05_")) return BACKSOUND_CATEGORIES[4];
        if (folder.startsWith("06_")) return BACKSOUND_CATEGORIES[5];
        if (folder.startsWith("07_")) return BACKSOUND_CATEGORIES[6];
        if (folder.startsWith("08_")) return BACKSOUND_CATEGORIES[7];
        if (folder.startsWith("10_")) {
            return fn.startsWith("Komedi_SFX_") ? FILM_SFX_CATEGORIES[4] : BACKSOUND_CATEGORIES[8];
        }
        // Folder 09_Efek_Suara_Film_dan_Drama dipecah per jenis efek
        String l = fn.toLowerCase(Locale.ROOT);
        if (l.contains("kaca") || l.contains("rem_") || l.contains("tabrakan") || l.contains("ledakan")) {
            return FILM_SFX_CATEGORIES[0];
        }
        if (l.contains("pukulan") || l.contains("pedang") || l.contains("tembakan") || l.contains("laser")) {
            return FILM_SFX_CATEGORIES[1];
        }
        if (l.contains("petir") || l.contains("sirine") || l.contains("pintu") || l.contains("jump_scare")
                || l.contains("teriakan") || l.contains("angin") || l.contains("rantai") || l.contains("gema")
                || l.contains("kuda") || l.contains("singa")) {
            return FILM_SFX_CATEGORIES[2];
        }
        return FILM_SFX_CATEGORIES[3];
    }

    private static String prettifyFileName(String fn) {
        String base = fn.replaceFirst("(?i)\\.(mp3|m4a)$", "");
        return base.replace("_", " ").trim();
    }

    private static void cleanLegacySynthesizedWavsIfAny(Context ctx) {
        try {
            File root = OfficeUtils.resolveBackupSubfolder(ctx, "Studio Gudang Audio");
            deleteWavRecursively(root);
        } catch (Throwable ignored) {}
    }

    private static void deleteWavRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteWavRecursively(c);
            }
        } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".wav")) {
            try { f.delete(); } catch (Throwable ignored) {}
        }
    }

    public static Set<String> getDeletedIds(SharedPreferences prefs) {
        Set<String> set = new HashSet<>();
        if (prefs == null) return set;
        try {
            JSONArray arr = new JSONArray(prefs.getString(PREF_DELETED_IDS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "").trim();
                if (!id.isEmpty()) set.add(id);
            }
        } catch (Throwable ignored) {}
        return set;
    }

    public static void saveDeletedIds(SharedPreferences prefs, Set<String> deletedIds) {
        if (prefs == null) return;
        JSONArray arr = new JSONArray();
        if (deletedIds != null) {
            for (String id : deletedIds) arr.put(id);
        }
        prefs.edit().putString(PREF_DELETED_IDS, arr.toString()).apply();
    }

    public static List<GudangItem> getActiveItemsForCategory(Context ctx, SharedPreferences prefs, String categoryFolder) {
        Set<String> deleted = getDeletedIds(prefs);
        List<GudangItem> out = new ArrayList<>();
        for (GudangItem it : getAllBuiltinItems(ctx)) {
            if (deleted.contains(it.id)) continue;
            if (categoryFolder == null || categoryFolder.equals(it.categoryFolder)) {
                out.add(it);
            }
        }
        return out;
    }

    public static int getActiveCount(Context ctx, SharedPreferences prefs, boolean isBacksound) {
        Set<String> deleted = getDeletedIds(prefs);
        int c = 0;
        for (GudangItem it : getAllBuiltinItems(ctx)) {
            if (it.isBacksound == isBacksound && !deleted.contains(it.id)) c++;
        }
        return c;
    }

    public static GudangItem findById(Context ctx, String idOrAlias) {
        if (idOrAlias == null) return null;
        String target = ALIAS_MAP.containsKey(idOrAlias) ? ALIAS_MAP.get(idOrAlias) : idOrAlias;
        String altM4a = target.replaceFirst("(?i)\\.mp3$", ".m4a");
        for (GudangItem it : getAllBuiltinItems(ctx)) {
            if (target.equals(it.id) || altM4a.equals(it.id) || idOrAlias.equals(it.id)) return it;
        }
        return null;
    }

    // Overload lama tetap aman bila dipanggil tanpa Context setelah CACHED_ITEMS terisi
    public static GudangItem findById(String idOrAlias) {
        if (idOrAlias == null || CACHED_ITEMS == null) return null;
        String target = ALIAS_MAP.containsKey(idOrAlias) ? ALIAS_MAP.get(idOrAlias) : idOrAlias;
        String altM4a = target.replaceFirst("(?i)\\.mp3$", ".m4a");
        for (GudangItem it : CACHED_ITEMS) {
            if (target.equals(it.id) || altM4a.equals(it.id) || idOrAlias.equals(it.id)) return it;
        }
        return null;
    }

    public static void stopPreview() {
        currentlyPlayingId = "";
        previewPaused = false;
        if (previewPlayer != null) {
            try { if (previewPlayer.isPlaying()) previewPlayer.stop(); } catch (Throwable ignored) {}
            try { previewPlayer.release(); } catch (Throwable ignored) {}
            previewPlayer = null;
        }
    }

    public static boolean isItemActivelyPlaying(String id) {
        return id != null && id.equals(currentlyPlayingId) && !previewPaused && previewPlayer != null;
    }

    public static void togglePlayPauseItem(Activity act, GudangItem item, Runnable onUiRefresh) {
        try {
            if (item.id.equals(currentlyPlayingId) && previewPlayer != null) {
                if (previewPlayer.isPlaying()) {
                    previewPlayer.pause();
                    previewPaused = true;
                } else {
                    previewPlayer.start();
                    previewPaused = false;
                }
                if (onUiRefresh != null) onUiRefresh.run();
                return;
            }
            stopPreview();
            AssetFileDescriptor afd = act.getAssets().openFd(item.assetPath);
            previewPlayer = new MediaPlayer();
            previewPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            previewPlayer.prepare();
            previewPlayer.start();
            currentlyPlayingId = item.id;
            previewPaused = false;
            previewPlayer.setOnCompletionListener(mp -> {
                currentlyPlayingId = "";
                previewPaused = false;
                if (onUiRefresh != null) act.runOnUiThread(onUiRefresh);
            });
            if (onUiRefresh != null) onUiRefresh.run();
        } catch (Throwable t) {
            // Fallback ekstrak jika asset terkompresi
            materializeItemAsync(act, item, file -> {
                try {
                    stopPreview();
                    previewPlayer = new MediaPlayer();
                    previewPlayer.setDataSource(file.getAbsolutePath());
                    previewPlayer.prepare();
                    previewPlayer.start();
                    currentlyPlayingId = item.id;
                    previewPaused = false;
                    previewPlayer.setOnCompletionListener(mp -> {
                        currentlyPlayingId = "";
                        previewPaused = false;
                        if (onUiRefresh != null) act.runOnUiThread(onUiRefresh);
                    });
                    if (onUiRefresh != null) onUiRefresh.run();
                } catch (Throwable e2) {
                    Toast.makeText(act, "Gagal memutar MP3: " + e2.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private static void pauseOrStopCurrentPreview(Runnable onUiRefresh) {
        if (previewPlayer != null) {
            try {
                if (previewPlayer.isPlaying()) {
                    previewPlayer.pause();
                    previewPaused = true;
                } else {
                    stopPreview();
                }
            } catch (Throwable ignored) {
                stopPreview();
            }
        } else {
            stopPreview();
        }
        if (onUiRefresh != null) onUiRefresh.run();
    }

    // ==================== DIALOG PEMILIH SUMBER AUDIO (KOLEKSI APLIKASI vs MEMORI HP) ====================

    public static void showUniversalAudioSourceChooser(
            final Activity act, final SharedPreferences prefs, final String dialogTitle,
            final boolean allowBacksound, final boolean allowFilmSfx,
            final AudioPickCallback onPickedFromApp,
            final Runnable onPickFromPhoneFileManager,
            final Runnable onPickFromPhoneGallery
    ) {
        stopPreview();
        ScrollView sv = new ScrollView(act);
        sv.setBackgroundColor(UiKit.bg(prefs));
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(act, 14);
        box.setPadding(p, p, p, p);

        TextView desc = new TextView(act);
        desc.setText("Pilih sumber musik latar atau efek suara: langsung dari koleksi MP3 asli di dalam aplikasi (Islami, 3D/8D, Piano, Jazz, Rock, Jedag-Jedug, Horor, Sinematik, Kartun, Ketawa & Efek Film) atau pilih audio dari memori HP sendiri.");
        desc.setTextSize(13.5f);
        desc.setTextColor(UiKit.textMuted(prefs));
        desc.setPadding(0, 0, 0, UiKit.dp(act, 10));
        box.addView(desc);

        final AlertDialog[] dlgRef = new AlertDialog[1];

        if (allowBacksound) {
            int bgmCount = getActiveCount(act, prefs, true);
            box.addView(UiKit.makeMenuButton(act,
                    "1. Pilih Backsound Musik dari Aplikasi (" + bgmCount + " Lagu MP3 Asli)",
                    "9 Kategori: Islami & Nasheed, Audio 3D/8D, Piano Santai/Sedih, Jazz Cafe, Rock Action, Jedag-Jedug/Phonk, Tegang/Horor, Sinematik, dan Kartun Komedi.",
                    UiKit.ACCENT_AI, prefs, v -> {
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        showCategoryBrowserDialog(act, prefs, true, onPickedFromApp, false);
                    }));
        }

        if (allowFilmSfx) {
            int sfxCount = getActiveCount(act, prefs, false);
            box.addView(UiKit.makeMenuButton(act,
                    "2. Pilih Efek Suara Film, Kartun & Ketawa (" + sfxCount + " Efek MP3 Asli)",
                    "Kaca Pecah, Rem & Tabrakan Mobil, Ledakan Bom, Pukulan, Pedang, Tembakan, Petir, Jump Scare, Orang Ketawa, Kartun Terpeleset, Bonk & Tepuk Tangan.",
                    UiKit.ACCENT_WARM, prefs, v -> {
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        showCategoryBrowserDialog(act, prefs, false, onPickedFromApp, false);
                    }));
        }

        if (onPickFromPhoneFileManager != null) {
            box.addView(UiKit.makeMenuButton(act,
                    "3. Pilih Audio / Backsound Sendiri dari File Manager HP",
                    "Membuka pengelola berkas untuk memilih musik milik Anda sendiri dari penyimpanan perangkat.",
                    UiKit.ACCENT_KB, prefs, v -> {
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        onPickFromPhoneFileManager.run();
                    }));
        }

        if (onPickFromPhoneGallery != null) {
            box.addView(UiKit.makeMenuButton(act,
                    "4. Pilih Audio Sendiri dari Galeri / Musik HP",
                    "Membuka pemilih sistem Android untuk mengambil audio milik Anda sendiri.",
                    UiKit.ACCENT_SYSTEM, prefs, v -> {
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        onPickFromPhoneGallery.run();
                    }));
        }

        box.addView(UiKit.makeMenuButton(act,
                "5. Buka Studio Gudang & Laboratorium Audio",
                "Dengarkan langsung semua MP3, pilih, atau hapus audio yang tidak diinginkan agar aplikasi lebih ringkas.",
                UiKit.ACCENT_OFFICE, prefs, v -> {
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    showStudioGudangMainDialog(act, prefs, onPickedFromApp);
                }));

        sv.addView(box);
        dlgRef[0] = UiKit.dialog(act, prefs)
                .setTitle(dialogTitle)
                .setView(sv)
                .setNegativeButton("Tutup", (d, w) -> stopPreview())
                .show();
    }

    // ==================== STUDIO GUDANG & LABORATORIUM UTAMA ====================

    public static void showStudioGudangMainDialog(final Activity act, final SharedPreferences prefs,
                                                  final AudioPickCallback optionalPickCallback) {
        stopPreview();
        ScrollView sv = new ScrollView(act);
        sv.setBackgroundColor(UiKit.bg(prefs));
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(act, 14);
        box.setPadding(p, p, p, p);

        int activeBgm = getActiveCount(act, prefs, true);
        int activeSfx = getActiveCount(act, prefs, false);
        int deletedCount = getDeletedIds(prefs).size();

        TextView head = new TextView(act);
        head.setText("Studio Gudang & Laboratorium Audio (" + (activeBgm + activeSfx) + " MP3 Aktif • " + deletedCount + " Dihapus)");
        head.setTextSize(16.5f);
        head.setTypeface(null, Typeface.BOLD);
        head.setTextColor(UiKit.textPrimary(prefs));
        box.addView(head);

        TextView sub = new TextView(act);
        sub.setText("Klik kategori untuk memilih atau membuka Laboratorium Audio (daftar memanjang ke bawah: klik nama langsung Putar/Jeda, sampingnya Jeda, sampingnya Hapus, tombol Simpan di paling bawah ujung sebelah kanan).");
        sub.setTextSize(13.5f);
        sub.setTextColor(UiKit.textMuted(prefs));
        sub.setPadding(0, UiKit.dp(act, 4), 0, UiKit.dp(act, 10));
        box.addView(sub);

        final AlertDialog[] dlgRef = new AlertDialog[1];

        box.addView(UiKit.makeMenuButton(act,
                "1. Gudang Kategori Backsound Musik (" + activeBgm + " Lagu MP3 Asli)",
                "9 Kategori: Islami, Audio 3D/8D, Piano Santai/Sedih, Jazz Cafe, Rock, Jedag-Jedug/Phonk, Tegang/Horor, Sinematik & Kartun Komedi.",
                UiKit.ACCENT_AI, prefs, v -> {
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    showCategoryBrowserDialog(act, prefs, true, optionalPickCallback, true);
                }));

        box.addView(UiKit.makeMenuButton(act,
                "2. Gudang Kategori Efek Suara Film, Kartun & Ketawa (" + activeSfx + " Efek MP3)",
                "Kaca Pecah, Rem/Tabrakan Mobil, Ledakan, Pukulan, Pedang, Tembakan, Petir, Jump Scare, Orang Ketawa & Kartun.",
                UiKit.ACCENT_WARM, prefs, v -> {
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    showCategoryBrowserDialog(act, prefs, false, optionalPickCallback, true);
                }));

        box.addView(UiKit.makeMenuButton(act,
                "3. Buka Laboratorium Audio (Semua Audio Memanjang ke Bawah)",
                "Tampilan daftar memanjang ke bawah: klik nama audio langsung Putar, sampingnya Jeda, sampingnya Hapus, dan Simpan di kanan bawah.",
                UiKit.ACCENT_KB, prefs, v -> {
                    if (dlgRef[0] != null) dlgRef[0].dismiss();
                    showAudioLaboratoryDialog(act, prefs, optionalPickCallback);
                }));

        if (deletedCount > 0) {
            box.addView(UiKit.makeMenuButton(act,
                    "4. Pulihkan Kembali Audio yang Pernah Dihapus (" + deletedCount + " Audio)",
                    "Mengembalikan koleksi MP3 yang dihapus ke daftar aktif gudang.",
                    UiKit.ACCENT_OFFICE, prefs, v -> {
                        saveDeletedIds(prefs, new HashSet<String>());
                        Toast.makeText(act, "Seluruh audio gudang dipulihkan!", Toast.LENGTH_SHORT).show();
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        showStudioGudangMainDialog(act, prefs, optionalPickCallback);
                    }));
        }

        sv.addView(box);
        dlgRef[0] = UiKit.dialog(act, prefs)
                .setTitle("Studio Gudang & Laboratorium Audio")
                .setView(sv)
                .setNegativeButton("Tutup", (d, w) -> stopPreview())
                .show();
    }

    public static void showCategoryBrowserDialog(
            final Activity act, final SharedPreferences prefs, final boolean isBacksound,
            final AudioPickCallback onPicked, final boolean openedFromGudangManager
    ) {
        stopPreview();
        final String[] cats = isBacksound ? BACKSOUND_CATEGORIES : FILM_SFX_CATEGORIES;
        ScrollView sv = new ScrollView(act);
        sv.setBackgroundColor(UiKit.bg(prefs));
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(act, 14);
        box.setPadding(p, p, p, p);

        final AlertDialog[] dlgRef = new AlertDialog[1];

        for (int i = 0; i < cats.length; i++) {
            final String catName = cats[i];
            List<GudangItem> items = getActiveItemsForCategory(act, prefs, catName);
            int color = (i % 3 == 0) ? UiKit.ACCENT_AI : ((i % 3 == 1) ? UiKit.ACCENT_OFFICE : UiKit.ACCENT_KB);
            box.addView(UiKit.makeMenuButton(act,
                    (i + 1) + ". " + catName + " (" + items.size() + " Audio)",
                    "Ketuk untuk melihat daftar MP3 di kategori " + catName + ".",
                    color, prefs, v -> {
                        if (dlgRef[0] != null) dlgRef[0].dismiss();
                        showFolderItemsDialog(act, prefs, catName, isBacksound, onPicked, openedFromGudangManager);
                    }));
        }

        sv.addView(box);
        dlgRef[0] = UiKit.dialog(act, prefs)
                .setTitle(isBacksound ? "Pilih Kategori Backsound Musik" : "Pilih Kategori Efek Suara")
                .setView(sv)
                .setOnCancelListener(d -> {
                    stopPreview();
                    if (openedFromGudangManager) showStudioGudangMainDialog(act, prefs, onPicked);
                })
                .setNegativeButton("Kembali", (d, w) -> {
                    stopPreview();
                    if (openedFromGudangManager) showStudioGudangMainDialog(act, prefs, onPicked);
                })
                .show();
    }

    /**
     * Daftar audio memanjang ke bawah yang super bersih:
     * - Klik Nama Audio -> langsung terputar (klik lagi -> jeda).
     * - Sampingnya -> Jeda (atau Pilih jika dipanggil untuk memilih audio editor/TTS).
     * - Sampingnya -> Hapus.
     * - Paling bawah ujung sebelah kanan -> Simpan / Selesai.
     */
    public static void showFolderItemsDialog(
            final Activity act, final SharedPreferences prefs, final String categoryFolder,
            final boolean isBacksound, final AudioPickCallback onPicked, final boolean openedFromGudangManager
    ) {
        stopPreview();
        final Set<String> workingDeleted = new HashSet<>(getDeletedIds(prefs));

        LinearLayout outer = new LinearLayout(act);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(UiKit.bg(prefs));
        int p = UiKit.dp(act, 10);
        outer.setPadding(p, p, p, p);

        TextView guide = new TextView(act);
        guide.setText(onPicked != null
                ? "Ketuk Nama Audio untuk langsung Putar / Jeda, ketuk Pilih di sampingnya untuk memakai audio, atau Hapus untuk membuang."
                : "Ketuk Nama Audio untuk langsung Putar / Jeda, tombol Jeda di sampingnya untuk menjeda, atau tombol Hapus di kanan.");
        guide.setTextSize(12.5f);
        guide.setTextColor(UiKit.textMuted(prefs));
        guide.setPadding(0, 0, 0, UiKit.dp(act, 6));
        outer.addView(guide);

        ScrollView sv = new ScrollView(act);
        final LinearLayout listContainer = new LinearLayout(act);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listContainer);
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Baris bawah: tombol Simpan di paling bawah ujung sebelah kanan
        LinearLayout bottomBar = new LinearLayout(act);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(0, UiKit.dp(act, 8), 0, 0);

        final AlertDialog[] dlgRef = new AlertDialog[1];
        final Runnable[] renderList = new Runnable[1];

        Button btnSaveBottomRight = new Button(act);
        btnSaveBottomRight.setText("Simpan Gudang");
        btnSaveBottomRight.setContentDescription("Simpan perubahan daftar audio gudang");
        UiKit.styleButton(act, btnSaveBottomRight, UiKit.ACCENT_AI);
        btnSaveBottomRight.setOnClickListener(v -> {
            stopPreview();
            saveDeletedIds(prefs, workingDeleted);
            deletePhysicalFiles(act, workingDeleted);
            Toast.makeText(act, "Perubahan gudang audio disimpan!", Toast.LENGTH_SHORT).show();
            if (dlgRef[0] != null && dlgRef[0].isShowing()) dlgRef[0].dismiss();
            if (openedFromGudangManager) showStudioGudangMainDialog(act, prefs, onPicked);
        });
        bottomBar.addView(btnSaveBottomRight);
        outer.addView(bottomBar);

        renderList[0] = () -> {
            listContainer.removeAllViews();
            List<GudangItem> activeList = new ArrayList<>();
            for (GudangItem it : getAllBuiltinItems(act)) {
                if (categoryFolder.equals(it.categoryFolder) && !workingDeleted.contains(it.id)) {
                    activeList.add(it);
                }
            }
            if (activeList.isEmpty()) {
                TextView empty = new TextView(act);
                empty.setText("Semua audio di kategori ini telah dihapus. Tekan Simpan Gudang di kanan bawah untuk menerapkan.");
                empty.setTextColor(UiKit.textPrimary(prefs));
                empty.setPadding(0, UiKit.dp(act, 12), 0, UiKit.dp(act, 12));
                listContainer.addView(empty);
                return;
            }
            for (int i = 0; i < activeList.size(); i++) {
                final GudangItem item = activeList.get(i);
                listContainer.addView(buildCompactAudioRow(act, prefs, item, onPicked, dlgRef,
                        () -> renderList[0].run(),
                        () -> {
                            if (item.id.equals(currentlyPlayingId)) stopPreview();
                            workingDeleted.add(item.id);
                            Toast.makeText(act, "Dihapus dari daftar: " + item.title, Toast.LENGTH_SHORT).show();
                            renderList[0].run();
                        }));
            }
        };

        renderList[0].run();
        dlgRef[0] = UiKit.dialog(act, prefs)
                .setTitle(categoryFolder)
                .setView(outer)
                .setOnDismissListener(d -> stopPreview())
                .setOnCancelListener(d -> {
                    stopPreview();
                    showCategoryBrowserDialog(act, prefs, isBacksound, onPicked, openedFromGudangManager);
                })
                .setNegativeButton("Kembali", (d, w) -> {
                    stopPreview();
                    showCategoryBrowserDialog(act, prefs, isBacksound, onPicked, openedFromGudangManager);
                })
                .show();
    }

    /**
     * Laboratorium Audio Lengkap (Memanjang ke bawah):
     * - Hanya untuk mendengarkan (Putar / Jeda) dan Menghapus audio yang tidak diinginkan.
     * - Klik Nama Audio -> langsung terputar.
     * - Sampingnya -> Jeda.
     * - Sampingnya -> Hapus.
     * - Paling bawah ujung sebelah kanan -> Simpan & Ekspor Pengaturan.
     */
    public static void showAudioLaboratoryDialog(
            final Activity act, final SharedPreferences prefs, final AudioPickCallback optionalPickCallback
    ) {
        stopPreview();
        final Set<String> workingDeleted = new HashSet<>(getDeletedIds(prefs));

        LinearLayout outer = new LinearLayout(act);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(UiKit.bg(prefs));
        int p = UiKit.dp(act, 10);
        outer.setPadding(p, p, p, p);

        TextView guide = new TextView(act);
        guide.setText("Laboratorium Audio — Klik nama audio untuk langsung putar, sampingnya klik Jeda untuk jeda, sampingnya klik Hapus untuk membuang audio.");
        guide.setTextSize(12.5f);
        guide.setTextColor(UiKit.textMuted(prefs));
        guide.setPadding(0, 0, 0, UiKit.dp(act, 6));
        outer.addView(guide);

        ScrollView sv = new ScrollView(act);
        final LinearLayout listContainer = new LinearLayout(act);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listContainer);
        outer.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Paling bawah ujung sebelah kanan: tombol Kosongkan/Hapus Semua & Simpan/Ekspor
        LinearLayout bottomBar = new LinearLayout(act);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(0, UiKit.dp(act, 8), 0, 0);

        final AlertDialog[] dlgRef = new AlertDialog[1];
        final Runnable[] renderLab = new Runnable[1];

        Button btnDeleteAll = new Button(act);
        btnDeleteAll.setText("Hapus Semua");
        btnDeleteAll.setContentDescription("Tandai hapus semua audio agar gudang kosong dan ringan");
        UiKit.styleButton(act, btnDeleteAll, UiKit.ACCENT_DANGER);
        btnDeleteAll.setOnClickListener(v -> {
            stopPreview();
            for (GudangItem it : getAllBuiltinItems(act)) {
                workingDeleted.add(it.id);
            }
            renderLab[0].run();
            Toast.makeText(act, "Semua audio ditandai hapus. Tekan Simpan / Ekspor di kanan bawah.", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams lpDel = new LinearLayout.LayoutParams(-2, -2);
        lpDel.rightMargin = UiKit.dp(act, 8);
        bottomBar.addView(btnDeleteAll, lpDel);

        Button btnSaveExport = new Button(act);
        btnSaveExport.setText("Simpan / Ekspor");
        btnSaveExport.setContentDescription("Simpan perubahan penghapusan audio gudang secara permanen");
        UiKit.styleButton(act, btnSaveExport, UiKit.ACCENT_AI);
        btnSaveExport.setOnClickListener(v -> {
            stopPreview();
            saveDeletedIds(prefs, workingDeleted);
            deletePhysicalFiles(act, workingDeleted);
            Toast.makeText(act, "Perubahan Laboratorium Audio berhasil disimpan!", Toast.LENGTH_LONG).show();
            if (dlgRef[0] != null && dlgRef[0].isShowing()) dlgRef[0].dismiss();
            showStudioGudangMainDialog(act, prefs, optionalPickCallback);
        });
        bottomBar.addView(btnSaveExport);
        outer.addView(bottomBar);

        renderLab[0] = () -> {
            listContainer.removeAllViews();
            String lastCat = "";
            int count = 0;
            for (final GudangItem item : getAllBuiltinItems(act)) {
                if (workingDeleted.contains(item.id)) continue;
                count++;
                if (!item.categoryFolder.equals(lastCat)) {
                    lastCat = item.categoryFolder;
                    listContainer.addView(UiKit.makeSectionLabel(act, lastCat, UiKit.ACCENT_OFFICE));
                }
                listContainer.addView(buildCompactAudioRow(act, prefs, item, null, dlgRef,
                        () -> renderLab[0].run(),
                        () -> {
                            if (item.id.equals(currentlyPlayingId)) stopPreview();
                            workingDeleted.add(item.id);
                            renderLab[0].run();
                        }));
            }
            if (count == 0) {
                TextView tvEmpty = new TextView(act);
                tvEmpty.setText("Semua audio telah dihapus. Tekan Simpan / Ekspor di paling bawah ujung kanan untuk menerapkan.");
                tvEmpty.setTextColor(UiKit.textPrimary(prefs));
                tvEmpty.setPadding(0, UiKit.dp(act, 14), 0, UiKit.dp(act, 14));
                listContainer.addView(tvEmpty);
            }
        };

        renderLab[0].run();
        dlgRef[0] = UiKit.dialog(act, prefs)
                .setTitle("Laboratorium Audio (Putar / Jeda / Hapus)")
                .setView(outer)
                .setOnDismissListener(d -> stopPreview())
                .setOnCancelListener(d -> {
                    stopPreview();
                    showStudioGudangMainDialog(act, prefs, optionalPickCallback);
                })
                .setNegativeButton("Kembali", (d, w) -> {
                    stopPreview();
                    showStudioGudangMainDialog(act, prefs, optionalPickCallback);
                })
                .show();
    }

    /** Baris audio memanjang ke bawah: [ Nama Audio (Klik = Putar/Jeda) ] | [ Jeda / Pilih ] | [ Hapus ] */
    private static LinearLayout buildCompactAudioRow(
            final Activity act, final SharedPreferences prefs, final GudangItem item,
            final AudioPickCallback onPicked, final AlertDialog[] dlgRef,
            final Runnable onRefreshUi, final Runnable onDeleteClicked
    ) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.bottomMargin = UiKit.dp(act, 5);
        row.setLayoutParams(rlp);

        boolean isThisActive = item.id.equals(currentlyPlayingId);
        boolean isActivelyPlaying = isThisActive && !previewPaused && previewPlayer != null;

        Button btnNamePlay = new Button(act);
        btnNamePlay.setAllCaps(false);
        btnNamePlay.setTextSize(12.5f);
        btnNamePlay.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        btnNamePlay.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 8), UiKit.dp(act, 10), UiKit.dp(act, 8));
        btnNamePlay.setText(isActivelyPlaying ? (item.title + " (Diputar)") : item.title);
        btnNamePlay.setContentDescription("Putar atau jeda " + item.title);
        btnNamePlay.setBackground(UiKit.roundedBox(
                isActivelyPlaying ? UiKit.ACCENT_OFFICE : UiKit.surfaceAlt(prefs),
                Color.TRANSPARENT, UiKit.dp(act, 8), 0));
        btnNamePlay.setTextColor(isActivelyPlaying ? Color.WHITE : UiKit.textPrimary(prefs));
        btnNamePlay.setOnClickListener(v -> togglePlayPauseItem(act, item, onRefreshUi));
        LinearLayout.LayoutParams lpName = new LinearLayout.LayoutParams(0, -2, 1f);
        lpName.rightMargin = UiKit.dp(act, 4);
        row.addView(btnNamePlay, lpName);

        if (onPicked != null) {
            Button btnPick = new Button(act);
            btnPick.setAllCaps(false);
            btnPick.setText("Pilih");
            btnPick.setTextSize(12);
            btnPick.setContentDescription("Pakai audio " + item.title);
            btnPick.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 8), UiKit.dp(act, 10), UiKit.dp(act, 8));
            btnPick.setBackground(UiKit.roundedBox(UiKit.ACCENT_AI, Color.TRANSPARENT, UiKit.dp(act, 8), 0));
            btnPick.setTextColor(Color.WHITE);
            btnPick.setOnClickListener(v -> {
                stopPreview();
                materializeItemAsync(act, item, file -> {
                    int dur = measureFileDurationSec(file);
                    if (dlgRef != null && dlgRef[0] != null && dlgRef[0].isShowing()) dlgRef[0].dismiss();
                    onPicked.onAudioPicked(Uri.fromFile(file), item.title, dur, item.isBacksound);
                });
            });
            LinearLayout.LayoutParams lpPick = new LinearLayout.LayoutParams(-2, -2);
            lpPick.rightMargin = UiKit.dp(act, 4);
            row.addView(btnPick, lpPick);
        } else {
            Button btnPause = new Button(act);
            btnPause.setAllCaps(false);
            btnPause.setText(isThisActive && previewPaused ? "Lanjut" : "Jeda");
            btnPause.setTextSize(12);
            btnPause.setContentDescription("Jeda atau hentikan suara " + item.title);
            btnPause.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 8), UiKit.dp(act, 10), UiKit.dp(act, 8));
            btnPause.setBackground(UiKit.roundedBox(UiKit.ACCENT_WARM, Color.TRANSPARENT, UiKit.dp(act, 8), 0));
            btnPause.setTextColor(Color.WHITE);
            btnPause.setOnClickListener(v -> {
                if (item.id.equals(currentlyPlayingId)) {
                    pauseOrStopCurrentPreview(onRefreshUi);
                } else {
                    stopPreview();
                    onRefreshUi.run();
                }
            });
            LinearLayout.LayoutParams lpPause = new LinearLayout.LayoutParams(-2, -2);
            lpPause.rightMargin = UiKit.dp(act, 4);
            row.addView(btnPause, lpPause);
        }

        Button btnDel = new Button(act);
        btnDel.setAllCaps(false);
        btnDel.setText("Hapus");
        btnDel.setTextSize(12);
        btnDel.setContentDescription("Hapus " + item.title + " dari gudang");
        btnDel.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 8), UiKit.dp(act, 10), UiKit.dp(act, 8));
        btnDel.setBackground(UiKit.roundedBox(UiKit.ACCENT_DANGER, Color.TRANSPARENT, UiKit.dp(act, 8), 0));
        btnDel.setTextColor(Color.WHITE);
        btnDel.setOnClickListener(v -> onDeleteClicked.run());
        row.addView(btnDel, new LinearLayout.LayoutParams(-2, -2));

        return row;
    }

    // ==================== EKSTRAKSI MP3 ASLI DARI ASSETS APK ====================

    public interface FileReadyCallback {
        void onReady(File file);
    }

    private static File getCachedFileForItem(Context ctx, GudangItem it) {
        File root = OfficeUtils.resolveBackupSubfolder(ctx, "Studio Gudang Audio");
        String folder = it.categoryFolder.replaceAll("[\\\\/:*?\"<>|]+", "_");
        File dir = new File(root, folder);
        if (!dir.exists()) dir.mkdirs();
        String baseName = it.assetPath.substring(it.assetPath.lastIndexOf('/') + 1);
        return new File(dir, baseName);
    }

    private static void deletePhysicalFiles(Context ctx, Set<String> deletedIds) {
        for (GudangItem it : getAllBuiltinItems(ctx)) {
            if (deletedIds.contains(it.id)) {
                File f = getCachedFileForItem(ctx, it);
                if (f.exists()) { try { f.delete(); } catch (Throwable ignored) {} }
            }
        }
    }

    private static int measureFileDurationSec(File f) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(f.getAbsolutePath());
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) return Math.max(1, (int) Math.round(Long.parseLong(d) / 1000.0));
        } catch (Throwable ignored) {
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
        return 0;
    }

    @SuppressWarnings("deprecation")
    public static void materializeItemAsync(final Activity act, final GudangItem item, final FileReadyCallback cb) {
        final File target = getCachedFileForItem(act, item);
        if (target.exists() && target.length() > 400L) {
            cb.onReady(target);
            return;
        }
        final ProgressDialog pd = ProgressDialog.show(act, "Menyiapkan Audio Gudang...",
                "Menyiapkan " + item.title + "...", true, false);
        new Thread(() -> {
            try {
                File f = ensureItemMaterialized(act, item);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (pd.isShowing()) pd.dismiss();
                    cb.onReady(f);
                });
            } catch (Throwable t) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (pd.isShowing()) pd.dismiss();
                    Toast.makeText(act, "Gagal menyiapkan audio: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    public static synchronized File ensureItemMaterialized(Context ctx, GudangItem item) throws Exception {
        File target = getCachedFileForItem(ctx, item);
        if (target.exists() && target.length() > 400L) return target;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (InputStream in = ctx.getAssets().open(item.assetPath);
             FileOutputStream out = new FileOutputStream(target, false)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
        return target;
    }
}
