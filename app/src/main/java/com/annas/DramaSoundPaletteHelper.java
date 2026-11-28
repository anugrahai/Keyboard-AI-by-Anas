package com.annas;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mesin Palet Kategori & Pemasangan Efek / Backsound (Live Drama & Film Studio).
 * - Sistem Klik Langsung: ketuk nama audio untuk langsung memasang di detik pemutar saat ini.
 *   Audio yang sama dapat diklik berkali-kali di detik berbeda (misal detik 1, 60, 80).
 * - Di samping nama audio ada tombol "Putar" untuk tes, dan tombol "Nggak Jadi" yang HANYA
 *   muncul apabila audio tersebut sudah dipilih/dipasang minimal 1 kali.
 * - Pembatalan selektif (tidak beruntun): jika audio dipasang di detik 1, 60, & 80 lalu
 *   menekan "Nggak Jadi", pengguna dapat memilih menghapus detik 80 saja tanpa menyentuh detik lain.
 */
public final class DramaSoundPaletteHelper {

    public static final int BGM_MODE_AT_PLAYHEAD = 0;

    private static final int INITIAL_RENDER_LIMIT = 20;

    public static final class CueItem {
        public String assetId = "";
        public Uri uri;
        public String title = "";
        public long atMs = 0L;
        public long endMs = 0L; // 0 = diputar terus sampai akhir audio/video utama
        public float volume = 1.0f;
        public boolean isBacksoundLoop = false;
        public long lastTriggeredPlaybackMs = -5000L;
        public transient MediaPlayer activeLoopPlayer;

        public CueItem copy() {
            CueItem c = new CueItem();
            c.assetId = assetId;
            c.uri = uri;
            c.title = title;
            c.atMs = atMs;
            c.endMs = endMs;
            c.volume = volume;
            c.isBacksoundLoop = isBacksoundLoop;
            return c;
        }
    }

    public interface Host {
        long getPlayheadMs();
        long getTotalDurationMs();
        void playTransitionFromMs(long cueAtMs);
        void stopPlaybackForPreviewSwitch();
        void triggerOneShotCue(CueItem cue);
        void onPickAudioFromPhone();
        void onPaletteAppliedCue(CueItem addedOrNull);
        void onCuesChanged(int totalCount);
        void onUndoRequested();
        void onRedoRequested();
        void onResetAllRequested();
        int getUndoCount();
        int getRedoCount();
    }

    private static final class ItemRowHolder {
        StudioGudangAudioManager.GudangItem item;
        Button btnPick;
        Button btnPlay;
        Button btnCancel;
        LinearLayout inlineBgmPanel;
        TextView tvBgmStatus;
        Button btnMarkStart;
        Button btnMarkEnd;
        Button btnResetEnd;
    }

    public static final class Controller {
        private final Activity act;
        private final SharedPreferences prefs;
        private final Host host;
        private final ArrayList<CueItem> cues;
        private final ArrayList<ArrayList<CueItem>> undoStack;
        private final ArrayList<ArrayList<CueItem>> redoStack;

        public int activeTabIdx = 0;
        public String searchQuery = "";
        public boolean autoPreRoll2Sec = true;
        private int renderLimit = INITIAL_RENDER_LIMIT;

        private final List<String> tabLabels = new ArrayList<>();
        private final List<String> tabFilterKeys = new ArrayList<>();
        private final List<StudioGudangAudioManager.GudangItem> filteredItems = new ArrayList<>();
        private final List<ItemRowHolder> renderedRows = new ArrayList<>();

        private Button btnCategoryPicker;
        private TextView tvContextGuide;
        private LinearLayout itemsListBox;
        private LinearLayout mountedListContainer;
        private Button btnUndoRef;
        private boolean mountedListExpanded = false;

        public Controller(Activity act, SharedPreferences prefs, Host host,
                          ArrayList<CueItem> cues,
                          ArrayList<ArrayList<CueItem>> undoStack,
                          ArrayList<ArrayList<CueItem>> redoStack) {
            this.act = act;
            this.prefs = prefs;
            this.host = host;
            this.cues = cues;
            this.undoStack = undoStack;
            this.redoStack = redoStack;
            buildTabDefinitions();
        }

        private void buildTabDefinitions() {
            tabLabels.clear();
            tabFilterKeys.clear();
            addTab("Seluruh Efek Singkat (Film, Kartun & Ketawa)", "ALL_SFX");
            addTab("Seluruh Backsound Musik (Semua Genre)", "ALL_BGM");
            addTab("Semua Audio (Efek + Backsound)", "ALL");
            for (String sfxCat : StudioGudangAudioManager.FILM_SFX_CATEGORIES) {
                addTab(sfxCat, sfxCat);
            }
            for (String bgmCat : StudioGudangAudioManager.BACKSOUND_CATEGORIES) {
                addTab(bgmCat, bgmCat);
            }
        }

        private void addTab(String label, String key) {
            tabLabels.add(label);
            tabFilterKeys.add(key);
        }

        private boolean isCurrentCategoryBacksound() {
            if (activeTabIdx < 0 || activeTabIdx >= tabFilterKeys.size()) return false;
            String key = tabFilterKeys.get(activeTabIdx);
            if ("ALL_BGM".equals(key)) return true;
            for (String b : StudioGudangAudioManager.BACKSOUND_CATEGORIES) {
                if (b.equals(key)) return true;
            }
            return false;
        }

        private List<CueItem> findCuesByAssetId(String assetId) {
            List<CueItem> matched = new ArrayList<>();
            if (assetId == null || assetId.isEmpty()) return matched;
            for (CueItem c : cues) {
                if (assetId.equals(c.assetId)) matched.add(c);
            }
            return matched;
        }

        private CueItem findLatestCueByAssetId(String assetId) {
            if (assetId == null || assetId.isEmpty()) return null;
            for (int i = cues.size() - 1; i >= 0; i--) {
                if (assetId.equals(cues.get(i).assetId)) return cues.get(i);
            }
            return null;
        }

        public void mountInto(LinearLayout parentRoot) {
            LinearLayout tabCard = UiKit.makeCard(act, prefs);
            tabCard.addView(UiKit.makeSectionLabel(act, "B. PILIH KATEGORI & KETUK AUDIO UNTUK PASANG", UiKit.ACCENT_OFFICE));

            btnCategoryPicker = UiKit.makeMenuButton(act,
                    "Pilih Kategori Efek / Backsound",
                    "Ketuk untuk memilih kategori Efek Singkat atau Backsound Musik",
                    UiKit.ACCENT_OFFICE, prefs, v -> showCategoryChooserDialog());
            tabCard.addView(btnCategoryPicker);

            final EditText etSearch = new EditText(act);
            etSearch.setHint("Cari cepat nama suara (contoh: ketawa, kaca, islami, horor, piano)...");
            etSearch.setText(searchQuery);
            etSearch.setContentDescription("Kotak cari cepat efek suara dan backsound musik");
            UiKit.styleEditText(act, etSearch, prefs);
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    searchQuery = s == null ? "" : s.toString().trim();
                    renderLimit = INITIAL_RENDER_LIMIT;
                    applyFilterAndBuildList();
                }
            });
            tabCard.addView(etSearch);

            tvContextGuide = new TextView(act);
            tvContextGuide.setTextSize(12f);
            tvContextGuide.setTextColor(UiKit.textMuted(prefs));
            tvContextGuide.setPadding(0, UiKit.dp(act, 2), 0, UiKit.dp(act, 8));
            tabCard.addView(tvContextGuide);

            itemsListBox = new LinearLayout(act);
            itemsListBox.setOrientation(LinearLayout.VERTICAL);
            tabCard.addView(itemsListBox);

            parentRoot.addView(tabCard);

            mountedListContainer = new LinearLayout(act);
            mountedListContainer.setOrientation(LinearLayout.VERTICAL);
            parentRoot.addView(mountedListContainer);

            refreshCategoryHeader();
            applyFilterAndBuildList();
            refreshMountedListUi();
            host.onCuesChanged(cues.size());
        }

        private void showCategoryChooserDialog() {
            final String[] choices = new String[tabLabels.size() + 1];
            for (int i = 0; i < tabLabels.size(); i++) {
                String prefix = (i == activeTabIdx) ? "[AKTIF] " : "";
                choices[i] = prefix + tabLabels.get(i);
            }
            choices[tabLabels.size()] = "+ Ambil Audio Sendiri dari Memori HP";

            UiKit.dialog(act, prefs)
                    .setTitle("Pilih Kategori Efek & Backsound")
                    .setItems(choices, (d, which) -> {
                        if (which == tabLabels.size()) {
                            host.onPickAudioFromPhone();
                            return;
                        }
                        activeTabIdx = which;
                        renderLimit = INITIAL_RENDER_LIMIT;
                        refreshCategoryHeader();
                        applyFilterAndBuildList();
                        Toast.makeText(act,
                                "Menampilkan: " + tabLabels.get(which) + " (" + filteredItems.size() + " audio)",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Tutup", null)
                    .show();
        }

        private void refreshCategoryHeader() {
            String activeLabel = (activeTabIdx >= 0 && activeTabIdx < tabLabels.size())
                    ? tabLabels.get(activeTabIdx)
                    : "Seluruh Efek Singkat";
            if (btnCategoryPicker != null) {
                btnCategoryPicker.setText("Kategori: " + activeLabel + "\nKetuk untuk ganti kategori atau ambil audio dari HP");
            }
            if (tvContextGuide != null) {
                if (isCurrentCategoryBacksound()) {
                    tvContextGuide.setText("Ketuk nama lagu untuk pasang di detik berjalan (bisa pasang beberapa segmen). Putar untuk tes, Nggak Jadi untuk batal per detik.");
                } else {
                    tvContextGuide.setText("Ketuk nama efek untuk langsung pasang di detik berjalan (bisa ketuk berkali-kali di detik berbeda). Putar untuk tes, Nggak Jadi untuk hapus detik tertentu.");
                }
            }
        }

        public void applyFilterAndBuildList() {
            filteredItems.clear();
            String key = (activeTabIdx >= 0 && activeTabIdx < tabFilterKeys.size()) ? tabFilterKeys.get(activeTabIdx) : "ALL_SFX";
            String q = searchQuery.toLowerCase(Locale.ROOT);
            for (StudioGudangAudioManager.GudangItem it : StudioGudangAudioManager.getAllBuiltinItems(act)) {
                if (StudioGudangAudioManager.getDeletedIds(prefs).contains(it.id)) continue;
                if (!q.isEmpty()) {
                    if (it.title.toLowerCase(Locale.ROOT).contains(q)
                            || it.categoryFolder.toLowerCase(Locale.ROOT).contains(q)) {
                        filteredItems.add(it);
                    }
                } else {
                    if ("ALL".equals(key)) filteredItems.add(it);
                    else if ("ALL_SFX".equals(key) && !it.isBacksound) filteredItems.add(it);
                    else if ("ALL_BGM".equals(key) && it.isBacksound) filteredItems.add(it);
                    else if (key.equals(it.categoryFolder)) filteredItems.add(it);
                }
            }
            buildClickListViews();
        }

        private void buildClickListViews() {
            if (itemsListBox == null) return;
            itemsListBox.removeAllViews();
            renderedRows.clear();

            if (filteredItems.isEmpty()) {
                TextView empty = new TextView(act);
                empty.setText("Tidak ada audio yang cocok dengan pencarian \"" + searchQuery + "\".");
                empty.setTextColor(UiKit.textMuted(prefs));
                empty.setPadding(UiKit.dp(act, 6), UiKit.dp(act, 10), UiKit.dp(act, 6), UiKit.dp(act, 10));
                itemsListBox.addView(empty);
                return;
            }

            int showCount = Math.min(filteredItems.size(), renderLimit);
            for (int i = 0; i < showCount; i++) {
                final StudioGudangAudioManager.GudangItem item = filteredItems.get(i);
                final ItemRowHolder holder = new ItemRowHolder();
                holder.item = item;

                LinearLayout cardRow = new LinearLayout(act);
                cardRow.setOrientation(LinearLayout.VERTICAL);
                cardRow.setPadding(UiKit.dp(act, 8), UiKit.dp(act, 6), UiKit.dp(act, 8), UiKit.dp(act, 6));
                cardRow.setBackground(UiKit.roundedBox(UiKit.surfaceAlt(prefs), UiKit.border(prefs), UiKit.dp(act, 10), UiKit.dp(act, 1)));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.bottomMargin = UiKit.dp(act, 6);
                cardRow.setLayoutParams(rlp);

                // Baris 1: [Nama Audio (Klik = Tambah di detik berjalan)] | [Putar] | [Nggak Jadi (muncul jika sudah dipasang)]
                LinearLayout topLine = new LinearLayout(act);
                topLine.setOrientation(LinearLayout.HORIZONTAL);
                topLine.setGravity(Gravity.CENTER_VERTICAL);

                Button btnPick = new Button(act);
                btnPick.setAllCaps(false);
                btnPick.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                btnPick.setBackgroundColor(Color.TRANSPARENT);
                btnPick.setPadding(UiKit.dp(act, 4), UiKit.dp(act, 6), UiKit.dp(act, 6), UiKit.dp(act, 6));
                btnPick.setOnClickListener(v -> attachAssetItemNow(item));
                topLine.addView(btnPick, new LinearLayout.LayoutParams(0, -2, 1f));
                holder.btnPick = btnPick;

                Button btnTest = new Button(act);
                btnTest.setAllCaps(false);
                btnTest.setTextSize(12f);
                btnTest.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 6), UiKit.dp(act, 10), UiKit.dp(act, 6));
                btnTest.setOnClickListener(v -> {
                    host.stopPlaybackForPreviewSwitch();
                    StudioGudangAudioManager.togglePlayPauseItem(act, item, () -> syncPlayButtonsInPlace());
                });
                topLine.addView(btnTest, new LinearLayout.LayoutParams(-2, -2));
                holder.btnPlay = btnTest;

                Button btnCancel = new Button(act);
                btnCancel.setAllCaps(false);
                btnCancel.setText("Nggak Jadi");
                btnCancel.setTextSize(11.5f);
                btnCancel.setTextColor(Color.WHITE);
                btnCancel.setBackground(UiKit.roundedBox(UiKit.ACCENT_DANGER, Color.TRANSPARENT, UiKit.dp(act, 8), 0));
                btnCancel.setPadding(UiKit.dp(act, 9), UiKit.dp(act, 6), UiKit.dp(act, 9), UiKit.dp(act, 6));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
                clp.leftMargin = UiKit.dp(act, 5);
                btnCancel.setVisibility(View.GONE);
                btnCancel.setOnClickListener(v -> handleSelectiveCancelForAsset(item));
                topLine.addView(btnCancel, clp);
                holder.btnCancel = btnCancel;

                cardRow.addView(topLine);

                // Baris 2 (KHUSUS BACKSOUND SAAT SUDAH TERPASANG): Tandai Awal & Tandai Akhir
                if (item.isBacksound) {
                    LinearLayout bgmBox = new LinearLayout(act);
                    bgmBox.setOrientation(LinearLayout.VERTICAL);
                    bgmBox.setPadding(UiKit.dp(act, 4), UiKit.dp(act, 4), UiKit.dp(act, 4), 0);
                    bgmBox.setVisibility(View.GONE);

                    TextView tvStat = new TextView(act);
                    tvStat.setTextSize(11.5f);
                    tvStat.setPadding(0, 0, 0, UiKit.dp(act, 4));
                    bgmBox.addView(tvStat);
                    holder.tvBgmStatus = tvStat;

                    LinearLayout btnLine = new LinearLayout(act);
                    btnLine.setOrientation(LinearLayout.HORIZONTAL);

                    Button btnStart = OfficeUtils.makeRowButton(act,
                            "Tandai Awal",
                            "Tandai detik pemutar saat ini sebagai titik awal untuk " + item.title,
                            UiKit.ACCENT_KB, Color.WHITE,
                            v -> handleMarkBacksoundStart(item));
                    Button btnEnd = OfficeUtils.makeRowButton(act,
                            "Tandai Akhir",
                            "Tandai detik pemutar saat ini sebagai titik akhir untuk " + item.title,
                            UiKit.ACCENT_WARM, Color.WHITE,
                            v -> handleMarkBacksoundEnd(item));
                    Button btnResetEnd = OfficeUtils.makeRowButton(act,
                            "Sampai Akhir",
                            "Hapus batas akhir agar " + item.title + " berputar sampai habis",
                            UiKit.ACCENT_AI, Color.WHITE,
                            v -> handleClearBacksoundEnd(item));

                    btnLine.addView(btnStart);
                    btnLine.addView(btnEnd);
                    btnLine.addView(btnResetEnd);
                    bgmBox.addView(btnLine);

                    holder.btnMarkStart = btnStart;
                    holder.btnMarkEnd = btnEnd;
                    holder.btnResetEnd = btnResetEnd;
                    holder.inlineBgmPanel = bgmBox;
                    cardRow.addView(bgmBox);
                }

                renderedRows.add(holder);
                itemsListBox.addView(cardRow);
            }

            syncAllRowHoldersInPlace();

            if (filteredItems.size() > showCount) {
                int sisa = filteredItems.size() - showCount;
                Button btnMore = UiKit.makeMenuButton(act,
                        "Tampilkan Lebih Banyak (Sisa " + sisa + " audio lagi)",
                        "Memuat daftar audio berikutnya dalam guliran yang sama",
                        UiKit.ACCENT_AI, prefs, v -> {
                            renderLimit += INITIAL_RENDER_LIMIT;
                            buildClickListViews();
                        });
                itemsListBox.addView(btnMore);
            }
        }

        private void syncPlayButtonsInPlace() {
            for (ItemRowHolder h : renderedRows) {
                boolean playingNow = StudioGudangAudioManager.isItemActivelyPlaying(h.item.id);
                h.btnPlay.setText(playingNow ? "Jeda" : "Putar");
                h.btnPlay.setContentDescription((playingNow ? "Jeda " : "Putar tes ") + h.item.title);
                h.btnPlay.setBackground(UiKit.roundedBox(
                        playingNow ? UiKit.ACCENT_WARM : UiKit.ACCENT_KB,
                        Color.TRANSPARENT, UiKit.dp(act, 8), 0));
                h.btnPlay.setTextColor(Color.WHITE);
            }
        }

        private void syncAllRowHoldersInPlace() {
            for (ItemRowHolder h : renderedRows) {
                List<CueItem> matched = findCuesByAssetId(h.item.id);
                boolean isMounted = !matched.isEmpty();

                if (isMounted) {
                    StringBuilder times = new StringBuilder();
                    for (int i = 0; i < matched.size(); i++) {
                        if (i > 0) times.append(", ");
                        times.append(AudioDspEngine.formatMsShort(matched.get(i).atMs));
                    }
                    String mainTitle = h.item.title;
                    String sub = "Terpasang " + matched.size() + "x (" + times + ") • Ketuk lagi untuk tambah di detik baru";
                    String full = mainTitle + "\n" + sub;
                    SpannableString span = new SpannableString(full);
                    span.setSpan(new StyleSpan(Typeface.BOLD), 0, mainTitle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    span.setSpan(new AbsoluteSizeSpan(13, true), 0, mainTitle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    span.setSpan(new ForegroundColorSpan(UiKit.ACCENT_OFFICE), 0, mainTitle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    span.setSpan(new AbsoluteSizeSpan(11, true), mainTitle.length() + 1, full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    span.setSpan(new ForegroundColorSpan(UiKit.textMuted(prefs)), mainTitle.length() + 1, full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    h.btnPick.setText(span);
                    h.btnPick.setContentDescription(mainTitle + ". Sudah terpasang " + matched.size() + " kali di detik " + times + ". Ketuk untuk pasang lagi di detik sekarang.");
                } else {
                    h.btnPick.setText(h.item.title);
                    h.btnPick.setTextSize(13.5f);
                    h.btnPick.setTypeface(null, Typeface.BOLD);
                    h.btnPick.setTextColor(UiKit.textPrimary(prefs));
                    h.btnPick.setContentDescription("Pasang " + h.item.title + " di detik pemutar sekarang");
                }

                // Tombol Nggak Jadi HANYA tampil kalau audio ini sudah dipilih minimal 1x!
                if (h.btnCancel != null) {
                    h.btnCancel.setVisibility(isMounted ? View.VISIBLE : View.GONE);
                    h.btnCancel.setContentDescription("Nggak jadi pakai " + h.item.title);
                }

                if (h.item.isBacksound && h.inlineBgmPanel != null) {
                    h.inlineBgmPanel.setVisibility(isMounted ? View.VISIBLE : View.GONE);
                    if (isMounted && h.tvBgmStatus != null) {
                        CueItem latest = matched.get(matched.size() - 1);
                        String endTxt = (latest.endMs > latest.atMs)
                                ? AudioDspEngine.formatMsShort(latest.endMs)
                                : "Sampai Akhir";
                        h.tvBgmStatus.setText("Terakhir: Mulai " + AudioDspEngine.formatMsShort(latest.atMs) + " -> " + endTxt);
                        h.tvBgmStatus.setTextColor(UiKit.ACCENT_KB_ALT);
                        h.tvBgmStatus.setTypeface(null, Typeface.BOLD);
                        if (h.btnResetEnd != null) {
                            h.btnResetEnd.setVisibility(latest.endMs > latest.atMs ? View.VISIBLE : View.GONE);
                        }
                    }
                }
            }
            syncPlayButtonsInPlace();
        }

        /**
         * Menghapus item secara selektif (TIDAK beruntun):
         * - Jika terpasang 1x -> langsung hapus yang 1 itu.
         * - Jika terpasang >1x (misal detik 1, detik 60, detik 80) -> munculkan daftar detik agar pengguna
         *   bisa menghapus detik 80 saja tanpa menyentuh detik 1 dan detik 60!
         */
        private void handleSelectiveCancelForAsset(final StudioGudangAudioManager.GudangItem item) {
            final List<CueItem> matched = findCuesByAssetId(item.id);
            if (matched.isEmpty()) return;

            if (matched.size() == 1) {
                CueItem target = matched.get(0);
                pushUndo();
                stopLoopPlayer(target);
                cues.remove(target);
                refreshAllInPlace();
                Toast.makeText(act, "Nggak jadi: " + item.title + " (" + AudioDspEngine.formatMsShort(target.atMs) + ")", Toast.LENGTH_SHORT).show();
                return;
            }

            final String[] choices = new String[matched.size() + 1];
            for (int i = 0; i < matched.size(); i++) {
                CueItem c = matched.get(i);
                choices[i] = "Hapus di detik " + AudioDspEngine.formatMsShort(c.atMs)
                        + (c.isBacksoundLoop && c.endMs > c.atMs ? (" -> " + AudioDspEngine.formatMsShort(c.endMs)) : "");
            }
            choices[matched.size()] = "Hapus Semua \"" + item.title + "\" (" + matched.size() + " item)";

            UiKit.dialog(act, prefs)
                    .setTitle("Nggak Jadi yang Mana? (" + item.title + ")")
                    .setItems(choices, (d, which) -> {
                        pushUndo();
                        if (which == matched.size()) {
                            for (CueItem c : matched) {
                                stopLoopPlayer(c);
                                cues.remove(c);
                            }
                            Toast.makeText(act, "Semua \"" + item.title + "\" dihapus.", Toast.LENGTH_SHORT).show();
                        } else {
                            CueItem target = matched.get(which);
                            stopLoopPlayer(target);
                            cues.remove(target);
                            Toast.makeText(act, "Dihapus: " + item.title + " di detik " + AudioDspEngine.formatMsShort(target.atMs), Toast.LENGTH_SHORT).show();
                        }
                        refreshAllInPlace();
                    })
                    .setNegativeButton("Batal", null)
                    .show();
        }

        private void handleMarkBacksoundStart(final StudioGudangAudioManager.GudangItem item) {
            final long pos = host.getPlayheadMs();
            StudioGudangAudioManager.stopPreview();
            CueItem existing = findLatestCueByAssetId(item.id);
            if (existing != null) {
                pushUndo();
                existing.atMs = pos;
                if (existing.endMs > 0 && existing.endMs <= pos + 300L) {
                    existing.endMs = 0L;
                }
                refreshAllInPlace();
                Toast.makeText(act, "Titik Awal \"" + item.title + "\" diset di " + AudioDspEngine.formatMsShort(pos), Toast.LENGTH_SHORT).show();
                if (autoPreRoll2Sec && host.getTotalDurationMs() > 0) host.playTransitionFromMs(existing.atMs);
            } else {
                attachBacksoundWithRange(item, pos, 0L);
            }
        }

        private void handleMarkBacksoundEnd(final StudioGudangAudioManager.GudangItem item) {
            final long pos = host.getPlayheadMs();
            StudioGudangAudioManager.stopPreview();
            CueItem existing = findLatestCueByAssetId(item.id);
            if (existing != null) {
                if (pos <= existing.atMs + 300L) {
                    Toast.makeText(act, "Titik Akhir (" + AudioDspEngine.formatMsShort(pos) + ") harus setelah Titik Awal ("
                            + AudioDspEngine.formatMsShort(existing.atMs) + ").", Toast.LENGTH_LONG).show();
                    return;
                }
                pushUndo();
                existing.endMs = pos;
                refreshAllInPlace();
                Toast.makeText(act, "Titik Akhir \"" + item.title + "\" ditandai di " + AudioDspEngine.formatMsShort(pos), Toast.LENGTH_SHORT).show();
            } else {
                if (pos <= 300L) {
                    Toast.makeText(act, "Putar atau geser dulu ke detik akhir yang dimau sebelum menekan Tandai Akhir.", Toast.LENGTH_SHORT).show();
                    return;
                }
                attachBacksoundWithRange(item, 0L, pos);
            }
        }

        private void handleClearBacksoundEnd(final StudioGudangAudioManager.GudangItem item) {
            CueItem existing = findLatestCueByAssetId(item.id);
            if (existing == null) return;
            pushUndo();
            existing.endMs = 0L;
            refreshAllInPlace();
            Toast.makeText(act, "\"" + item.title + "\" diputar terus sampai akhir.", Toast.LENGTH_SHORT).show();
        }

        private void attachBacksoundWithRange(final StudioGudangAudioManager.GudangItem item, final long startMs, final long endMs) {
            StudioGudangAudioManager.materializeItemAsync(act, item, file -> {
                pushUndo();
                CueItem cue = new CueItem();
                cue.assetId = item.id;
                cue.uri = Uri.fromFile(file);
                cue.title = item.title;
                cue.isBacksoundLoop = true;
                cue.volume = 0.30f;
                cue.atMs = startMs;
                cue.endMs = (endMs > startMs) ? endMs : 0L;
                cues.add(cue);
                refreshAllInPlace();
                host.onPaletteAppliedCue(cue);
                if (autoPreRoll2Sec && host.getTotalDurationMs() > 0) host.playTransitionFromMs(cue.atMs);
                else host.triggerOneShotCue(cue);
            });
        }

        public void addPhoneCue(Uri uri, String title, boolean asBacksound) {
            long curPlayhead = host.getPlayheadMs();
            StudioGudangAudioManager.stopPreview();
            pushUndo();
            CueItem cue = new CueItem();
            cue.assetId = uri.toString();
            cue.uri = uri;
            cue.title = title;
            cue.isBacksoundLoop = asBacksound;
            cue.volume = asBacksound ? 0.30f : 1.0f;
            cue.atMs = curPlayhead;
            cue.endMs = 0L;
            cues.add(cue);
            refreshAllInPlace();
            host.onPaletteAppliedCue(cue);
            if (autoPreRoll2Sec && host.getTotalDurationMs() > 0) host.playTransitionFromMs(cue.atMs);
            else host.triggerOneShotCue(cue);
        }

        private void pushUndo() {
            ArrayList<CueItem> snap = new ArrayList<>();
            for (CueItem c : cues) snap.add(c.copy());
            undoStack.add(snap);
            redoStack.clear();
        }

        private void attachAssetItemNow(final StudioGudangAudioManager.GudangItem item) {
            final long curPlayhead = host.getPlayheadMs();
            StudioGudangAudioManager.stopPreview();
            StudioGudangAudioManager.materializeItemAsync(act, item, file -> {
                pushUndo();
                CueItem cue = new CueItem();
                cue.assetId = item.id;
                cue.uri = Uri.fromFile(file);
                cue.title = item.title;
                cue.isBacksoundLoop = item.isBacksound;
                cue.volume = item.isBacksound ? 0.30f : 1.0f;
                cue.atMs = curPlayhead;
                cue.endMs = 0L;
                cues.add(cue);
                refreshAllInPlace();
                host.onPaletteAppliedCue(cue);
                if (autoPreRoll2Sec && host.getTotalDurationMs() > 0) {
                    host.playTransitionFromMs(cue.atMs);
                } else {
                    host.triggerOneShotCue(cue);
                }
            });
        }

        public void refreshAllInPlace() {
            syncAllRowHoldersInPlace();
            refreshMountedListUi();
            host.onCuesChanged(cues.size());
        }

        public void refreshMountedListUi() {
            if (mountedListContainer == null) return;
            mountedListContainer.removeAllViews();
            LinearLayout card = UiKit.makeCard(act, prefs);
            card.addView(UiKit.makeSectionLabel(act, "C. DAFTAR TERPASANG (" + cues.size() + " ITEM)", UiKit.ACCENT_KB));

            LinearLayout actRow = new LinearLayout(act);
            actRow.setOrientation(LinearLayout.HORIZONTAL);
            actRow.setPadding(0, 0, 0, UiKit.dp(act, 6));
            btnUndoRef = OfficeUtils.makeRowButton(act,
                    "Undo (" + host.getUndoCount() + ")",
                    "Batalkan langkah terakhir tanpa keluar layar",
                    UiKit.ACCENT_WARM, Color.WHITE, v -> host.onUndoRequested());
            actRow.addView(btnUndoRef);
            if (!cues.isEmpty()) {
                actRow.addView(OfficeUtils.makeRowButton(act,
                        mountedListExpanded ? "Tutup Daftar" : "Atur (" + cues.size() + ")",
                        "Tampilkan atau sembunyikan detail item terpasang",
                        UiKit.ACCENT_AI, Color.WHITE, v -> {
                            mountedListExpanded = !mountedListExpanded;
                            refreshMountedListUi();
                        }));
                actRow.addView(OfficeUtils.makeRowButton(act,
                        "Hapus Semua",
                        "Kosongkan seluruh efek dan backsound terpasang",
                        UiKit.ACCENT_DANGER, Color.WHITE, v -> host.onResetAllRequested()));
            }
            card.addView(actRow);

            if (cues.isEmpty()) {
                mountedListExpanded = false;
                TextView tv = new TextView(act);
                tv.setText("Belum ada efek/backsound terpasang.");
                tv.setTextSize(12);
                tv.setTextColor(UiKit.textMuted(prefs));
                card.addView(tv);
                mountedListContainer.addView(card);
                return;
            }

            if (!mountedListExpanded) {
                TextView tvSummary = new TextView(act);
                tvSummary.setText(cues.size() + " item terpasang siap dikonversi. Ketuk \"Atur (" + cues.size() + ")\" jika ingin geser detik, atur volume, atau hapus detik tertentu.");
                tvSummary.setTextSize(12);
                tvSummary.setTextColor(UiKit.textMuted(prefs));
                card.addView(tvSummary);
                mountedListContainer.addView(card);
                return;
            }

            int alt = UiKit.surfaceAlt(prefs), txt = UiKit.textPrimary(prefs);
            for (int i = 0; i < cues.size(); i++) {
                final int idx = i;
                final CueItem c = cues.get(i);
                LinearLayout rowCard = UiKit.makeCard(act, prefs);

                String timeInfo = c.isBacksoundLoop
                        ? ("[Backsound " + AudioDspEngine.formatMsShort(c.atMs) + " -> "
                        + (c.endMs > c.atMs ? AudioDspEngine.formatMsShort(c.endMs) : "Akhir") + "]")
                        : ("[Detik " + AudioDspEngine.formatMsShort(c.atMs) + "]");

                rowCard.addView(UiKit.makeMenuButton(act,
                        (i + 1) + ". " + timeInfo + " " + c.title + " (Vol " + Math.round(c.volume * 100) + "%)",
                        "Ketuk untuk memutar transisi dari 2 detik sebelum " + c.title,
                        alt, prefs, v -> host.playTransitionFromMs(c.atMs)));

                LinearLayout ctrlRow = new LinearLayout(act);
                ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
                ctrlRow.setPadding(0, UiKit.dp(act, 4), 0, 0);
                ctrlRow.addView(OfficeUtils.makeRowButton(act, "-0.5 dtk", "Geser 0.5 detik lebih awal", alt, txt, v -> {
                    pushUndo();
                    c.atMs = Math.max(0L, c.atMs - 500L);
                    refreshAllInPlace();
                }));
                ctrlRow.addView(OfficeUtils.makeRowButton(act, "+0.5 dtk", "Geser 0.5 detik lebih lambat", alt, txt, v -> {
                    pushUndo();
                    c.atMs = Math.min(Math.max(0L, host.getTotalDurationMs()), c.atMs + 500L);
                    refreshAllInPlace();
                }));
                ctrlRow.addView(OfficeUtils.makeRowButton(act, "Vol -", "Kecilkan volume", alt, txt, v -> {
                    c.volume = Math.max(0.05f, c.volume - 0.15f);
                    refreshMountedListUi();
                }));
                ctrlRow.addView(OfficeUtils.makeRowButton(act, "Vol +", "Besarkan volume", alt, txt, v -> {
                    c.volume = Math.min(2.0f, c.volume + 0.15f);
                    refreshMountedListUi();
                }));
                ctrlRow.addView(OfficeUtils.makeRowButton(act, "Hapus", "Hapus item ini saja", UiKit.ACCENT_DANGER, Color.WHITE, v -> {
                    pushUndo();
                    stopLoopPlayer(c);
                    cues.remove(idx);
                    refreshAllInPlace();
                }));
                rowCard.addView(ctrlRow);

                card.addView(rowCard);
            }
            mountedListContainer.addView(card);
        }
    }

    public static void stopLoopPlayer(CueItem cue) {
        if (cue != null && cue.activeLoopPlayer != null) {
            try { if (cue.activeLoopPlayer.isPlaying()) cue.activeLoopPlayer.stop(); } catch (Throwable ignored) {}
            try { cue.activeLoopPlayer.release(); } catch (Throwable ignored) {}
            cue.activeLoopPlayer = null;
        }
    }

    public static void stopAllLoopPlayers(List<CueItem> cues) {
        if (cues == null) return;
        for (CueItem c : cues) stopLoopPlayer(c);
    }

    public static void syncCuesWithPlayhead(Activity act, List<CueItem> cues, long prevPosMs, long curPosMs, boolean activelyPlaying) {
        if (!activelyPlaying || cues == null || cues.isEmpty()) return;
        for (CueItem c : cues) {
            if (c.isBacksoundLoop) {
                boolean inside = curPosMs >= c.atMs && (c.endMs <= c.atMs || curPosMs < c.endMs);
                if (inside && c.activeLoopPlayer == null) {
                    try {
                        MediaPlayer mp = new MediaPlayer();
                        mp.setDataSource(act, c.uri);
                        mp.prepare();
                        mp.setLooping(true);
                        mp.setVolume(Math.min(1.0f, c.volume), Math.min(1.0f, c.volume));
                        int dur = Math.max(1000, mp.getDuration());
                        int offset = (int) ((curPosMs - c.atMs) % dur);
                        if (offset > 0) mp.seekTo(offset);
                        mp.start();
                        c.activeLoopPlayer = mp;
                    } catch (Throwable ignored) {}
                } else if (!inside && c.activeLoopPlayer != null) {
                    stopLoopPlayer(c);
                }
            }
        }
    }
}
