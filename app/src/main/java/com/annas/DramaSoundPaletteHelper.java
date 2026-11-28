package com.annas;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mesin Palet Kategori & Centang Efek / Backsound (Live Drama & Film Studio).
 * - Seluruh tampilan menyatu dalam 1 ScrollView utama (TIDAK ada ListView bersarang).
 * - Update baris centang/putar/tandai dilakukan IN-PLACE tanpa menghancurkan View daftar,
 *   sehingga fokus TalkBack & posisi gulir tidak pernah meloncat atau berkedip.
 * - Tombol "Tandai Awal" & "Tandai Akhir" Backsound menempel langsung pada baris Backsound
 *   yang bersangkutan (di bawah judul & tombol Putar lagu tersebut), BUKAN ditaruh di atas layar.
 * - Untuk Efek Singkat, hanya tampil CheckBox + tombol Putar agar bersih & bebas bingung.
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
        CheckBox cb;
        Button btnPlay;
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
        private boolean suppressCheckCallback = false;

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

        private CueItem findCueByAssetId(String assetId) {
            if (assetId == null || assetId.isEmpty()) return null;
            for (int i = cues.size() - 1; i >= 0; i--) {
                if (assetId.equals(cues.get(i).assetId)) return cues.get(i);
            }
            return null;
        }

        public void mountInto(LinearLayout parentRoot) {
            // KARTU B: PILIH KATEGORI + CARI + DAFTAR CENTANG LANGSUNG LURUS KE BAWAH
            LinearLayout tabCard = UiKit.makeCard(act, prefs);
            tabCard.addView(UiKit.makeSectionLabel(act, "B. PILIH KATEGORI & CENTANG EFEK / BACKSOUND", UiKit.ACCENT_OFFICE));

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
            tvContextGuide.setTextSize(12.5f);
            tvContextGuide.setTextColor(UiKit.textMuted(prefs));
            tvContextGuide.setPadding(0, UiKit.dp(act, 4), 0, UiKit.dp(act, 8));
            tabCard.addView(tvContextGuide);

            itemsListBox = new LinearLayout(act);
            itemsListBox.setOrientation(LinearLayout.VERTICAL);
            tabCard.addView(itemsListBox);

            parentRoot.addView(tabCard);

            // KARTU C: DAFTAR TERPASANG & UNDO NGGAK JADI
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
                    tvContextGuide.setText(
                            "Putar untuk tes • Centang di detik tujuan untuk pasang (tombol Tandai Awal/Akhir muncul setelah dicentang).");
                } else {
                    tvContextGuide.setText(
                            "Putar untuk tes • Centang di detik tujuan untuk pasang efek.");
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
            buildCheckListViews();
        }

        private void buildCheckListViews() {
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
                cardRow.setPadding(UiKit.dp(act, 8), UiKit.dp(act, 7), UiKit.dp(act, 8), UiKit.dp(act, 7));
                cardRow.setBackground(UiKit.roundedBox(UiKit.surfaceAlt(prefs), Color.TRANSPARENT, UiKit.dp(act, 8), 0));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.bottomMargin = UiKit.dp(act, 6);
                cardRow.setLayoutParams(rlp);

                // Baris 1: CheckBox Judul (kiri) + Tombol Putar/Jeda (kanan)
                LinearLayout topLine = new LinearLayout(act);
                topLine.setOrientation(LinearLayout.HORIZONTAL);
                topLine.setGravity(Gravity.CENTER_VERTICAL);

                CheckBox cb = new CheckBox(act);
                cb.setTextSize(13.5f);
                cb.setTextColor(UiKit.textPrimary(prefs));
                cb.setText(item.title);
                cb.setOnCheckedChangeListener((btn, checked) -> {
                    if (suppressCheckCallback) return;
                    if (checked) {
                        attachAssetItemNow(item, holder);
                    } else {
                        removeAssetItemAsCancel(item);
                    }
                });
                topLine.addView(cb, new LinearLayout.LayoutParams(0, -2, 1f));
                holder.cb = cb;

                Button btnTest = new Button(act);
                btnTest.setAllCaps(false);
                btnTest.setTextSize(12.5f);
                btnTest.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 7), UiKit.dp(act, 12), UiKit.dp(act, 7));
                btnTest.setOnClickListener(v -> {
                    host.stopPlaybackForPreviewSwitch();
                    StudioGudangAudioManager.togglePlayPauseItem(act, item, () -> syncPlayButtonsInPlace());
                });
                topLine.addView(btnTest, new LinearLayout.LayoutParams(-2, -2));
                holder.btnPlay = btnTest;
                cardRow.addView(topLine);

                // Baris 2 (KHUSUS BACKSOUND): Tandai Awal & Tandai Akhir menempel langsung pada baris lagunya!
                if (item.isBacksound) {
                    LinearLayout bgmBox = new LinearLayout(act);
                    bgmBox.setOrientation(LinearLayout.VERTICAL);
                    bgmBox.setPadding(UiKit.dp(act, 4), UiKit.dp(act, 4), UiKit.dp(act, 4), 0);

                    TextView tvStat = new TextView(act);
                    tvStat.setTextSize(11.5f);
                    tvStat.setPadding(0, 0, 0, UiKit.dp(act, 4));
                    bgmBox.addView(tvStat);
                    holder.tvBgmStatus = tvStat;

                    LinearLayout btnLine = new LinearLayout(act);
                    btnLine.setOrientation(LinearLayout.HORIZONTAL);

                    Button btnStart = OfficeUtils.makeRowButton(act,
                            "Tandai Awal",
                            "Tandai detik pemutar saat ini sebagai titik masuk awal untuk " + item.title,
                            UiKit.ACCENT_KB, Color.WHITE,
                            v -> handleMarkBacksoundStart(item));
                    Button btnEnd = OfficeUtils.makeRowButton(act,
                            "Tandai Akhir",
                            "Tandai detik pemutar saat ini sebagai titik berhenti akhir untuk " + item.title,
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
                            buildCheckListViews();
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
            suppressCheckCallback = true;
            try {
                for (ItemRowHolder h : renderedRows) {
                    CueItem mounted = findCueByAssetId(h.item.id);
                    boolean isMounted = (mounted != null);
                    if (h.cb.isChecked() != isMounted) {
                        h.cb.setChecked(isMounted);
                    }
                    h.cb.setContentDescription((isMounted ? "Terpasang: " : "") + h.item.title
                            + ". Centang untuk menempel di detik pemutar sekarang, lepas centang jika nggak jadi.");

                    if (h.item.isBacksound && h.inlineBgmPanel != null) {
                        h.inlineBgmPanel.setVisibility(isMounted ? View.VISIBLE : View.GONE);
                    }
                    if (h.item.isBacksound && h.tvBgmStatus != null && isMounted) {
                        String endTxt = (mounted.endMs > mounted.atMs)
                                ? AudioDspEngine.formatMsShort(mounted.endMs)
                                : "Sampai Akhir";
                        h.tvBgmStatus.setText("Terpasang: Mulai " + AudioDspEngine.formatMsShort(mounted.atMs)
                                + " -> " + endTxt);
                        h.tvBgmStatus.setTextColor(UiKit.ACCENT_KB_ALT);
                        h.tvBgmStatus.setTypeface(null, Typeface.BOLD);
                        if (h.btnResetEnd != null) {
                            h.btnResetEnd.setVisibility(mounted.endMs > mounted.atMs ? View.VISIBLE : View.GONE);
                        }
                    }
                }
            } finally {
                suppressCheckCallback = false;
            }
            syncPlayButtonsInPlace();
        }

        private void handleMarkBacksoundStart(final StudioGudangAudioManager.GudangItem item) {
            final long pos = host.getPlayheadMs();
            StudioGudangAudioManager.stopPreview();
            CueItem existing = findCueByAssetId(item.id);
            if (existing != null) {
                pushUndo();
                existing.atMs = pos;
                if (existing.endMs > 0 && existing.endMs <= pos + 300L) {
                    existing.endMs = 0L;
                }
                refreshAllInPlace();
                Toast.makeText(act,
                        "Titik Awal \"" + item.title + "\" diset di " + AudioDspEngine.formatMsShort(pos),
                        Toast.LENGTH_SHORT).show();
                if (autoPreRoll2Sec && host.getTotalDurationMs() > 0) {
                    host.playTransitionFromMs(existing.atMs);
                }
            } else {
                attachBacksoundWithRange(item, pos, 0L);
            }
        }

        private void handleMarkBacksoundEnd(final StudioGudangAudioManager.GudangItem item) {
            final long pos = host.getPlayheadMs();
            StudioGudangAudioManager.stopPreview();
            CueItem existing = findCueByAssetId(item.id);
            if (existing != null) {
                if (pos <= existing.atMs + 300L) {
                    Toast.makeText(act,
                            "Titik Akhir (" + AudioDspEngine.formatMsShort(pos) + ") harus setelah Titik Awal ("
                                    + AudioDspEngine.formatMsShort(existing.atMs) + ").",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                pushUndo();
                existing.endMs = pos;
                refreshAllInPlace();
                Toast.makeText(act,
                        "Titik Akhir \"" + item.title + "\" ditandai di " + AudioDspEngine.formatMsShort(pos),
                        Toast.LENGTH_SHORT).show();
            } else {
                if (pos <= 300L) {
                    Toast.makeText(act,
                            "Putar atau geser dulu ke detik akhir yang dimau sebelum menekan Tandai Akhir.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                attachBacksoundWithRange(item, 0L, pos);
            }
        }

        private void handleClearBacksoundEnd(final StudioGudangAudioManager.GudangItem item) {
            CueItem existing = findCueByAssetId(item.id);
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
                if (autoPreRoll2Sec && host.getTotalDurationMs() > 0) {
                    host.playTransitionFromMs(cue.atMs);
                } else {
                    host.triggerOneShotCue(cue);
                }
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
            if (autoPreRoll2Sec && host.getTotalDurationMs() > 0) {
                host.playTransitionFromMs(cue.atMs);
            } else {
                host.triggerOneShotCue(cue);
            }
        }

        private void pushUndo() {
            ArrayList<CueItem> snap = new ArrayList<>();
            for (CueItem c : cues) snap.add(c.copy());
            undoStack.add(snap);
            redoStack.clear();
        }

        private void attachAssetItemNow(final StudioGudangAudioManager.GudangItem item, ItemRowHolder holder) {
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

        private void removeAssetItemAsCancel(StudioGudangAudioManager.GudangItem item) {
            boolean removedAny = false;
            pushUndo();
            for (int i = cues.size() - 1; i >= 0; i--) {
                if (item.id.equals(cues.get(i).assetId)) {
                    stopLoopPlayer(cues.get(i));
                    cues.remove(i);
                    removedAny = true;
                }
            }
            if (!removedAny && !undoStack.isEmpty()) {
                undoStack.remove(undoStack.size() - 1);
            }
            if (removedAny) {
                Toast.makeText(act, "Nggak jadi pakai: " + item.title, Toast.LENGTH_SHORT).show();
            }
            refreshAllInPlace();
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
                tvSummary.setText(cues.size() + " item terpasang siap dikonversi. Ketuk \"Atur (" + cues.size() + ")\" di atas jika ingin geser detik atau volume.");
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
                ctrlRow.addView(OfficeUtils.makeRowButton(act, "Hapus", "Nggak jadi pakai item ini", UiKit.ACCENT_DANGER, Color.WHITE, v -> {
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

    /**
     * Memastikan Backsound Musik yang punya jam masuk tertentu hanya berbunyi
     * saat posisi putar memasuki rentangnya [atMs, endMs), bukan sejak 00:00.
     */
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
