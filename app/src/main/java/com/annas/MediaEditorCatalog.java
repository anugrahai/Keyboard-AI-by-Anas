package com.annas;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Katalog deskripsi dan contoh konkret untuk setiap fitur di Studio Audio & Video Pro (Bebas Emoji).
 * Memastikan setiap fitur yang diketuk selalu menampilkan penjelasan fungsi + contoh pemakaian
 * yang mudah dipahami pengguna awas maupun tunanetra (TalkBack / Jieshuo).
 */
public final class MediaEditorCatalog {

    public static final class FeatureItem {
        public final String id;
        public final String title;
        public final String explanation;
        public final String example;

        public FeatureItem(String id, String title, String explanation, String example) {
            this.id = id;
            this.title = title;
            this.explanation = explanation;
            this.example = example;
        }
    }

    private MediaEditorCatalog() {}

    /** Membuat kartu Penjelasan & Contoh rapi di atas setiap dialog atau di bawah item menu. */
    public static LinearLayout makeGuideCard(Context ctx, SharedPreferences prefs, String title, String explanation, String example) {
        float dp = ctx.getResources().getDisplayMetrics().density;
        LinearLayout card = UiKit.makeCard(ctx, prefs);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(UiKit.textPrimary(prefs));
        card.addView(tvTitle);

        TextView tvBody = new TextView(ctx);
        String body = "Penjelasan Fungsi: " + explanation + "\n\nContoh Pemakaian: " + example;
        tvBody.setText(body);
        tvBody.setTextSize(13.5f);
        tvBody.setTextColor(UiKit.textMuted(prefs));
        tvBody.setPadding(0, Math.round(6 * dp), 0, 0);
        card.addView(tvBody);
        card.setContentDescription(title + ". " + body);
        return card;
    }

    // ==================== KATALOG FITUR AUDIO EDITOR PRO ====================

    public static final FeatureItem[] AUDIO_CUT_FEATURES = {
            new FeatureItem("a_trim", "Simpan Bagian Pilihan Saja (Trim IN sampai OUT)",
                    "Mengambil audio hanya dari titik Awal (IN) sampai titik Akhir (OUT), bagian luar dibuang.",
                    "Audio 60 detik. Tandai Awal = 00:10, Akhir = 00:35 -> hasilnya tinggal 25 detik inti."),
            new FeatureItem("a_cut", "Buang Bagian Tengah (Cut IN sampai OUT)",
                    "Menghapus bagian antara Awal (IN) hingga Akhir (OUT), lalu menyambung awal & akhir dengan anti-klik.",
                    "Rekaman ada salah ucap di 00:14 sampai 00:20 -> tandai 00:14–00:20 lalu Cut -> salah ucap hilang mulus."),
            new FeatureItem("a_split_2", "Belah Audio Jadi 2 Bagian di Titik IN",
                    "Memotong audio di detik Awal (IN) menjadi dua berkas terpisah (Bagian 1 & Bagian 2) siap ekspor.",
                    "Podcast 10 menit ingin dibagi di 05:00 -> tulis 05:00 atau ketuk Tandai Awal pas dengar -> langsung jadi 2 berkas."),
            new FeatureItem("a_merge", "Gabung (Merge) Banyak Audio",
                    "Menyambung berkas audio sekarang dengan 1 atau beberapa audio lain dari penyimpanan + jeda opsional.",
                    "Gabung Intro 5 detik + Rekaman Utama 2 menit + Outro 10 detik jadi 1 audio MP3 atau M4A utuh."),
            new FeatureItem("a_insert_silence", "Sisipkan Jeda Hening di Titik IN",
                    "Menambahkan ruang diam (misal 1–10 detik) tepat pada detik Awal (IN) untuk memberi napas pembicara.",
                    "Kalimat terlalu mepet di detik 00:08 -> tulis IN = 00:08, durasi jeda 2 detik -> muncul jeda tenang 2 detik."),
            new FeatureItem("a_remove_silence", "Hapus Bagian Diam Otomatis (Silence Remover)",
                    "Mendeteksi jeda hening panjang di rekaman ceramah/kuliah dan memotongnya otomatis agar padat.",
                    "Rekaman 30 menit banyak mikir/diam > 1.0 detik -> otomatis dipangkas jadi sekitar 22 menit tanpa diam lama."),
            new FeatureItem("a_reverse", "Putar Balik Audio (Reverse)",
                    "Membalik audio dari belakang ke depan untuk efek suara unik atau transisi kreatif.",
                    "Bunyi simbal atau suara bicara diputar terbalik menjadi efek intro video misterius."),
            new FeatureItem("a_loop", "Ulangi / Gandakan Klip (Loop 2x sampai 10x)",
                    "Mengulang audio saat ini berkali-kali secara sambung untuk memperpanjang durasi musik latar.",
                    "Beat musik pendek 15 detik diulang 4x -> langsung menjadi musik latar 1 menit.")
    };

    public static final FeatureItem[] AUDIO_VOLUME_FEATURES = {
            new FeatureItem("a_gain", "Naikkan atau Turunkan Volume (Gain dB)",
                    "Memperbesar (+dB) atau mengecilkan (-dB) suara dengan pengaman anti-pecah.",
                    "Rekaman mic pelan sekali -> naikkan +12 dB -> suara terdengar lantang."),
            new FeatureItem("a_normalize", "Ratakan & Maksimalkan Volume (Loudnorm Studio)",
                    "Menaikkan dan menyeimbangkan level audio otomatis ke standar siaran tanpa membuat suara pecah.",
                    "Audio terlalu pelan atau beda keras-pelannya jauh -> 1 ketuk langsung rata maksimal."),
            new FeatureItem("a_podcast", "Kejernihan Vokal Podcast (Compressor + Limiter)",
                    "Meratakan suara berbisik agar terdengar jelas sekaligus menahan suara teriakan agar tidak sakit di telinga.",
                    "Rekaman wawancara jarak mic berbeda -> setelah Compressor, kedua pembicara terdengar seimbang."),
            new FeatureItem("a_noisegate", "Kurangi Desis & Noise Hening (FFT Denoise + Filter Angin)",
                    "Membuang dengung angin/AC frekuensi rendah (High-Pass) dan meredam desis tipis saat diam.",
                    "Rekam di luar ruangan ada suara angin/kipas -> aktifkan pembersih noise -> vokal jauh lebih bersih."),
            new FeatureItem("a_fade", "Fade In & Fade Out Halus",
                    "Membuat suara masuk pelan-pelan di awal dan menghilang perlahan di akhir lagu/rekaman.",
                    "Beri Fade In 1.5 detik & Fade Out 3 detik supaya musik pengiring tidak kaget saat mulai dan berakhir."),
            new FeatureItem("a_8d", "Efek Audio 8D Berputar (Auto-Pan Stereo)",
                    "Membuat suara terasa berputar mengelilingi telinga kiri dan kanan saat memakai headset/earphone.",
                    "Putar nasyid/musik memakai headset setelah efek 8D -> suara bergerak mulus kiri ke kanan."),
            new FeatureItem("a_karaoke", "Kurangi Vokal Tengah (Mode Karaoke / Instrumental)",
                    "Mengurangi suara vokal yang berada tepat di kanal tengah stereo sehingga instrumen musik lebih menonjol.",
                    "Lagu stereo bervokal -> aktifkan Karaoke -> vokal mengecil, musik pengiring siap dipakai menyanyi."),
            new FeatureItem("a_pan", "Geser Arah Suara Kiri / Kanan (Stereo Balance)",
                    "Mengatur fokus suara dominan ke speaker kiri atau kanan.",
                    "Atur vokal ke Kanan 80% atau balik ke Kiri untuk efek dialog dua sisi.")
    };

    public static final FeatureItem[] AUDIO_DSP_FEATURES = {
            new FeatureItem("a_speed_pitch", "Ubah Kecepatan & Nada Suara (Speed & Pitch)",
                    "Mempercepat/melambatkan audio (0.25x–4x) atau menaikkan/menurunkan nada (-12 s.d +12 semitone).",
                    "Pilih Pitch -3 tanpa ubah kecepatan -> suara jadi berat berwibawa; atau +4 -> suara ceria."),
            new FeatureItem("a_eq_preset", "Equalizer 5-Band & 16 Preset Studio",
                    "Mengatur kekuatan Bass, Mid, dan Treble secara kustom atau langsung pilih preset studio.",
                    "Pilih preset 'Podcast Vokal Jernih' untuk ceramah, atau 'Bass Boost Mantap' untuk musik."),
            new FeatureItem("a_echo_kamar", "Gema Kamar & Gunung (Echo / Delay)",
                    "Menambahkan pantulan suara berulang seperti di kamar kosong atau tebing gunung.",
                    "Ubah ucapan biasa memakai Echo Gunung -> muncul pantulan epik berkali-kali."),
            new FeatureItem("a_reverb", "Gema Ruangan (Studio / Masjid Agung / Aula / Gua)",
                    "Memberikan efek ruangan luas yang alami mulai dari Studio rapat hingga Aula Masjid megah.",
                    "Rekaman bacaan Al-Qur'an atau pengumuman -> beri Reverb Masjid Agung -> terasa khidmat dan luas."),
            new FeatureItem("a_special_fx", "Efek Khusus (Chorus, Flanger, Tremolo, Radio Jadul, Telepon, Robot)",
                    "Mengubah karakter suara seperti efek telepon genggam, radio AM lawas, paduan suara (Chorus), atau bergetar.",
                    "Pilih 'Efek Suara Telepon' untuk adegan drama dialog menelepon di video.")
    };

    public static final FeatureItem[] AUDIO_MIX_FEATURES = {
            new FeatureItem("a_record_over", "Rekam Suara Baru / Gabung Voiceover",
                    "Merekam langsung dari mikrofon HP lalu menggabungkannya dengan proyek audio aktif.",
                    "Sudah ada musik latar di proyek -> rekam suara pembukaan lalu otomatis dicampur."),
            new FeatureItem("a_bgm_duck", "Tambah Musik Latar + Auto-Ducking Pintar",
                    "Mencampur rekaman vokal dengan musik pengiring; musik otomatis mengecil pelan saat Anda berbicara.",
                    "Pilih musik volume 30%, centang Auto-Ducking + Loop -> saat Anda bicara musik turun otomatis, saat jeda naik lagi."),
            new FeatureItem("a_extract_video", "Ekstrak Suara dari Berkas Video (MP4/MKV)",
                    "Mengambil trek audio asli dari video mana saja untuk disimpan ke MP3/M4A 64-320 kbps atau diedit lagi.",
                    "Punya video kajian 10 menit -> ekstrak audionya -> simpan jadi MP3 192 kbps.")
    };

    public static final FeatureItem[] AUDIO_SMART_EXPORT_FEATURES = {
            new FeatureItem("a_stats", "Analisis Detail Statistik Audio",
                    "Memeriksa durasi persis, frekuensi sample, bitrate, dan ukuran berkas.",
                    "Cek detail teknis rekaman sebelum diekspor."),
            new FeatureItem("a_ai_transcribe", "Transkrip Audio ke Teks / Lirik (AI Gemini)",
                    "Mengirim audio (< 12 MB) ke AI Gemini agar otomatis menjadi naskah teks rapi.",
                    "Rekam catatan 2 menit -> Transkrip AI -> teks siap disalin ke Word atau dijadikan caption."),
            new FeatureItem("a_status_split", "Potong Otomatis ke Status WA / Story (15s/30s/60s/90s)",
                    "Memecah audio panjang (misal 5 menit) otomatis menjadi Part 1, Part 2, dst siap kirim ke Status/Story.",
                    "Audio ceramah 3 menit dipilih potong 60 detik -> menghasilkan 3 berkas berurutan (Part 1, Part 2, Part 3)."),
            new FeatureItem("a_ringtone", "Buat Nada Dering (Auto Potong <= 30s + Fade Out)",
                    "Mengambil pilihan IN–OUT maksimal 30 detik dan menambahkan Fade Out 2 detik di akhir sebagai ringtone.",
                    "Tandai bagian reff lagu 00:45–01:12 -> Buat Nada Dering -> langsung jadi MP3/M4A jernih siap pasang."),
            new FeatureItem("a_export_m4a", "Ekspor Audio Akhir (MP3 / M4A / WAV / FLAC / OGG / OPUS 64-320 kbps)",
                    "Menyimpan hasil editan permanen ke folder 'Smart Tools and Typing AI by Anas/Editor Audio' dengan pilihan bitrate lengkap.",
                    "Pilih MP3 192 kbps atau 320 kbps untuk kualitas studio, atau 64-128 kbps untuk hemat ukuran.")
    };

    // ==================== KATALOG FITUR VIDEO EDITOR PRO (CAPCUT-STYLE) ====================

    public static final FeatureItem[] VIDEO_CLIP_FEATURES = {
            new FeatureItem("v_trim", "Potong Video Cepat (Trim IN sampai OUT)",
                    "Memotong video dari titik Awal (IN) ke Akhir (OUT) tanpa render ulang (super cepat hitungan detik).",
                    "Video 3 menit hanya butuh detik 00:20 sampai 01:05 -> tandai IN & OUT -> Trim langsung jadi dalam 2 detik."),
            new FeatureItem("v_cut", "Buang Bagian Tengah Video (Cut IN sampai OUT)",
                    "Membuang bagian tengah video yang salah/membosankan dan langsung menyambung awal & akhir.",
                    "Tengah video ada jeda di 00:40–00:50 -> tandai 00:40–00:50 lalu Cut -> mulus tersambung."),
            new FeatureItem("v_split2", "Belah Video Jadi 2 Berkas di Titik IN",
                    "Memecah 1 berkas video tepat pada detik IN menjadi 2 video MP4 terpisah.",
                    "Video rekaman rapat 10 menit dibelah di menit 05:00 menjadi Sesi 1 & Sesi 2."),
            new FeatureItem("v_merge", "Gabung (Merge) Beberapa Video Sekaligus",
                    "Menggabungkan 2 video atau lebih yang berurutan menjadi satu berkas MP4 panjang.",
                    "Gabung Video Intro + Video Konten + Video Penutup jadi 1 video utuh."),
            new FeatureItem("v_speed_rev", "Ubah Kecepatan Video (0.25x–4x) / Reverse Klip",
                    "Membuat video gerak lambat (Slowmo 0.5x), Timelapse cepat (2x–4x), atau memutar balik klip.",
                    "Ubah klip jalan kaki 10 detik jadi Timelapse 2.5x sehingga padat dalam 4 detik."),
            new FeatureItem("v_freeze", "Ambil Foto Frame ke PNG (Screenshot Detik IN)",
                    "Mengambil satu gambar jernih berkualitas penuh dari video tepat di detik Awal (IN).",
                    "Geser ke detik 00:12 lalu ketuk Ambil Foto -> tersimpan jadi gambar PNG buat thumbnail.")
    };

    public static final FeatureItem[] VIDEO_AUDIO_FEATURES = {
            new FeatureItem("v_mute", "Bisukan Suara Asli Video (Mute Instan)",
                    "Menghapus seluruh audio bising asli dari video secara instan tanpa mengubah gambar video.",
                    "Video jalan raya berisik di-Mute -> siap diisi musik baru atau suara narasi."),
            new FeatureItem("v_extract_edit", "Ekstrak Audio ke Studio Editor Audio Pro",
                    "Mengambil suara dari video ini lalu membukanya langsung di Studio Audio Pro untuk EQ/Reverb/Denoise.",
                    "Ekstrak audio video -> bersihkan noise & beri Compressor -> kembalikan ke video."),
            new FeatureItem("v_bgm", "Tambah / Ganti Musik Latar + Auto-Ducking",
                    "Mengganti audio video atau mencampur audio asli video dengan musik pilihan (mendukung Auto-Ducking).",
                    "Audio asli volume 100%, musik BGM volume 35% + Auto-Ducking -> musik otomatis pelan saat Anda bicara."),
            new FeatureItem("v_dubbing", "Rekam Dubbing / Voiceover di Atas Video",
                    "Merekam narasi suara Anda langsung lewat mic dan menempelkannya ke dalam video MP4.",
                    "Tampilkan video demonstrasi lalu rekam penjelasan suara Anda sendiri -> gabung ke video.")
    };

    public static final FeatureItem[] VIDEO_CANVAS_FEATURES = {
            new FeatureItem("v_ratio", "Ubah Rasio Layar (9:16 TikTok, 16:9 YouTube, 1:1 IG, 4:5, 21:9) + Blur BG",
                    "Menyesuaikan bingkai video untuk platform tujuan dengan latar Blur artistik ala CapCut, Hitam, Putih, atau Navy.",
                    "Video mendatar 16:9 diubah ke 9:16 Latar Blur -> langsung rapi untuk TikTok, Reels & WA Status."),
            new FeatureItem("v_crop_rot", "Crop Pinggiran, Putar 90/180/270 Derajat, Flip Cermin",
                    "Memotong sisi video yang tidak diinginkan, memutar orientasi video miring, atau membalik kiri-kanan.",
                    "Video terekam terbalik 90 derajat atau ada watermark di pinggir -> putar & crop 10% bawah -> bersih."),
            new FeatureItem("v_slideshow", "Buat Video Slideshow Foto + Musik Latar",
                    "Menggabungkan beberapa foto pilihan menjadi video MP4 lengkap dengan musik latar.",
                    "Pilih 5 foto produk + 1 lagu M4A -> tiap foto 3 detik -> jadi video promosi MP4.")
    };

    public static final FeatureItem[] VIDEO_COLOR_FEATURES = {
            new FeatureItem("v_filter_preset", "18 Preset Filter Warna (Cinema, Vintage, Noir B&W, Warm, Cool, Sharpen HD)",
                    "Memberikan nuansa warna profesional ke seluruh frame video sekaligus dukungan Fade In/Out layar.",
                    "Pilih Filter 'Cinema Film' + Fade In Hitam 1 detik -> video langsung terasa seperti cuplikan film."),
            new FeatureItem("v_adjust", "Atur Kecerahan, Kontras & Saturasi + Fade Layar",
                    "Menyetel pencahayaan video terlalu gelap, ketajaman kontras, warna pudar, dan transisi gelap awal/akhir.",
                    "Video malam agak gelap -> naikkan Brightness +0.15 & Contrast 1.2x -> gambar jauh lebih cerah.")
    };

    public static final FeatureItem[] VIDEO_TEXT_AI_FEATURES = {
            new FeatureItem("v_add_text", "Tambah Teks Judul / Caption di Rentang IN sampai OUT",
                    "Menempelkan tulisan teks lengkap dengan kotak latar belakang di video.",
                    "Tambahkan judul 'Pembukaan Materi' dari detik 00:00 sampai 00:06."),
            new FeatureItem("v_import_srt", "Tempel Subtitle dari Berkas .SRT",
                    "Membaca berkas teks subtitle (.srt) dan membakar teksnya langsung masuk ke video MP4.",
                    "Pilih berkas terjemahan.srt -> seluruh dialog otomatis muncul sinkron sesuai waktunya."),
            new FeatureItem("v_ai_sub", "Auto-Subtitle AI Otomatis (AI Gemini)",
                    "Mentranskrip suara video otomatis memakai AI menjadi daftar subtitle ber-timestamp lalu ditempel ke video.",
                    "Video kajian 45 detik tanpa teks -> AI membacakan dan menempelkan subtitle bahasa Indonesia otomatis."),
            new FeatureItem("v_watermark", "Tambah Watermark / Logo Gambar PNG di Sudut Video",
                    "Menempelkan logo toko atau stiker gambar dengan pilihan pojok.",
                    "Pilih logo PNG -> tempel di Kanan Atas atau Kiri Atas."),
            new FeatureItem("v_clear_overlays", "Hapus Semua Daftar Teks & Watermark Antrean",
                    "Mengosongkan antrean teks/subtitle dan logo sebelum merender ulang dari awal.",
                    "Salah tambah 3 caption? Bersihkan antrean lalu isi teks baru yang benar.")
    };

    public static final FeatureItem[] VIDEO_EXPORT_FEATURES = {
            new FeatureItem("v_status_wa", "Potong Otomatis ke Status WA / Story (15s / 30s / 60s / 90s / Custom)",
                    "Memecah video panjang (misal 5 menit) secara instan menjadi potongan berurutan Part 1, Part 2, dst.",
                    "Video ceramah/acara 5 menit dipotong per 60 detik -> langsung jadi 5 berkas MP4 rapi siap upload ke Status WA."),
            new FeatureItem("v_compress_export", "Render / Kompres MP4 Akhir (360p–1080p, 24–60 FPS, CRF, Audio 96–320 kbps)",
                    "Merender proyek dengan semua efek (Rasio, Filter, Crop, Teks, Watermark) atau mengecilkan ukuran MP4.",
                    "Video 120 MB dikompres ke 720p 30 FPS CRF 28 -> ukuran turun jadi sekitar 20 MB tetap jernih.")
    };
}
