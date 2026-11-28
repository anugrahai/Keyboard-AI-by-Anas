# Smart Tools and Typing AI by Anas (`Keyboard AI by Anas`)

**Versi Terbaru:** v36.2  
**Developer:** Anugerah Anas  
**Nama Layanan Keyboard & Aksesibilitas:** `Keyboard AI by Anas`  
**Nama Aplikasi (Launcher & Suite Alat Cerdas):** `Smart Tools and Typing AI by Anas`

Aplikasi all-in-one Android yang menggabungkan **Keyboard AI & Braille Aksesibel**, **Asisten AI Multi-Provider**, **Studio Office & OCR PDF Offline**, **Studio Editor Audio & Video Pro (FFmpeg 8.1)**, **Editor Kode + AI Agent**, serta **Studio Pengunduh Lagu, Video, Playlist & Channel (`yt-dlp` + YouTube Innertube + FFmpeg 8.1)** dalam satu aplikasi ramah pembaca layar (**TalkBack / Jieshuo**).

---

## Pembaruan Utama v36.0 – v36.2
- **Pratinjau 1–3 Detik & Download 6-Thread Range (v36.2)**: `LocalStreamProxy` pre-buffer 256 KB + chunked Range 512 KB anti-throttle & anti-403, plus jalur unduh cepat 6-thread paralel langsung ke `YouTubeFastBridge`.
- **Keyboard Arab Standar Awas + Simbol & Teks Bertajwid Lengkap (v36.2)**: Layout standar Arabic 101 di QWERTY lengkap (`ض ص ... ج د`, `ذ`, baris Harakat + Shift Tajwid/Waqaf/Hamzah), halaman simbol khusus Arab saat mode Arab aktif, serta Braille Arab + panel `ع+ Tajwid` (34 halaman) lengkap dengan pembacaan TTS tiap harakat & tajwid.
- **Putar Pratinjau 3–5 Detik Multi-Platform (v36.1)**: Streaming langsung via `LocalStreamProxy` & header forwarding untuk **YouTube, Facebook, SoundCloud, TikTok, Instagram**, dan situs lain tanpa lag atau error 403.
- **Editor Drama Klik Langsung & Batal Selektif (v36.1)**: Efek/backsound yang sama bisa dipasang berkali-kali di detik berbeda, tombol **"Nggak Jadi"** hanya muncul setelah audio dipilih, dan bisa membatalkan detik tertentu secara selektif tanpa menghapus titik lainnya.
- **Nama Aplikasi Baru**: Launcher & suite utama bernama **Smart Tools and Typing AI by Anas**, sedangkan nama di **Setelan Keyboard** dan **Aksesibilitas HP** tetap **Keyboard AI by Anas**.
- **Migrasi Folder Otomatis**: Penyimpanan berpindah otomatis dari `/storage/emulated/0/Keyboard AI by Anas` ke `/storage/emulated/0/Smart Tools and Typing AI by Anas` saat pembaruan pertama kali dibuka.
- **Studio Pengunduh Lagu & Video Pro** *(Tab Kantor — Khusus Premium)*:
  - Ditenagai **YouTube Innertube (~1 detik) + `yt-dlp` + FFmpeg 8.1** (tanpa perlu API Key).
  - Mendukung unduhan **1 lagu/video**, **Playlist (100–500 item)**, **per Channel**, dan **Pencarian YouTube langsung** di dalam aplikasi.
  - Mendukung **YouTube, SoundCloud, TikTok, Instagram, Facebook**, dan platform lain dengan folder output dinamis (`Downloader/<Platform>/Audio | Video | Playlist | Channel`) yang hanya dibuat saat diunduh.
  - **Putar Pratinjau** sebelum mengunduh (streaming audio/video langsung).
  - **3 Halaman Geser Kiri/Kanan & Tab Aksesibel**: *Link & Cari* → *Sedang Diunduh (Background Foreground Service + Batal 1/Banyak/Semua)* → *Sudah Terunduh (Putar / Edit di Studio / Bagikan / Hapus)*.
  - Tombol **Update yt-dlp** langsung dari dalam aplikasi.

---

## Ringkasan Fitur Lengkap (5 Tab Utama)

### 1. Keyboard & Aksesibilitas (`Keyboard AI by Anas`)
- Mode **QWERTY** & **Keyboard Braille 6-Titik** dengan kalibrasi titik, chord lengkap, serta dukungan Layanan Aksesibilitas agar gestur TalkBack/Jieshuo tidak bentrok.
- **Input Suara Final**: Whisper Offline (`whisper.cpp` JNI) & Google Speech Recognition dengan penguat suara pelan (AGC).
- **Panel Cepat Petir**: Chat AI, Revisi Teks, Terjemahan instan, Snippet, Papan Klip (Pin & Riwayat), serta 169+ preset gaya suara.
- **Gaya 6 Mode Gemini TTS**: 19 preset naskah ekspresif (`Pembicara1:` / `Pembicara2:`) lengkap dengan tag ekspresi dan deteksi niat otomatis.

### 2. Asisten AI Studio
- Multi-Provider API (OpenAI, Gemini, DeepSeek, Grok) + dukungan Bonus Key.
- Chat AI dengan lampiran berkas/folder, Gemini TTS + Video Status WA, AI Translator Studio, Asisten Tulis & Bisnis CS Toko, AI Vision (foto/struk/screenshot error), AI Notulen Rapat, serta Studio Agen Kustom & Memori Profil.

### 3. Coding, Script & Berkas
- **Editor Kode & Agen AI** (`.lua`, `.java`, `.py`, `.js`, `.json`, `.xml`, dll) dengan cek kurung/blok Lua.
- **File Manager Internal 100% Aksesibel**, Papan Klip (Pin & Riwayat), dan Template Snippet Cepat.

### 4. Kantor, Studio Media & Pengunduh
- **Microsoft Word Pro**: Editor DOCX/TXT/PDF, gambar ke DOCX/PDF, & OCR Foto.
- **Keyboard PDF Anas**: Pembaca PDF & dokumen + OCR Offline 15+ bahasa (Tesseract 5.5.1), Baca Novel TTS, & AI Bedah PDF.
- **Microsoft Excel Pro**: Multi-sheet 60x26 sel, 30+ rumus, ekspor XLSX/PDF.
- **Microsoft PowerPoint Pro**: Buka/ekspor PPTX, catatan pembicara, navigasi tombol Volume & TTS.
- **Studio Editor Audio Pro (36 Fitur)**: FFmpeg 8.1 Native C/NEON, Audio ke Video MP4, Potong sambil Putar + Undo/Redo, Live Efek Film & Backsound, EQ, Reverb Masjid, Status WA.
- **Studio Pengunduh Lagu & Video Pro**: YouTube (Single/Playlist/Channel/Search), SoundCloud, TikTok, IG, FB, Play Pratinjau, Background Queue & Batal Fleksibel.
- **Studio Editor Video Pro (36 Fitur)**: Video ke MP3/WAV, Potong sambil Putar + Undo/Redo, Live Efek Suara & Backsound, Rasio 9:16 Blur, Status WA.
- **Studio Gudang & Laboratorium Audio**: 98 MP3 rekaman asli dalam 10 kategori (Islami, 3D/8D Spatial, Piano, Jazz, Rock, Jedag-Jedug/Phonk, Horor, Sinematik, Efek Film/Drama, Kartun/Komedi/Ketawa).

### 5. Produktivitas, Bisnis & Sistem
- 18.750+ Quote & Kata Mutiara, Mode Bisnis & Catat Pesanan, Brankas Identitas, Konverter Satuan/Umur/Hijriah, Timer Fokus & Catatan Kilat.
- Cadangkan & Pulihkan Lengkap, Statistik Mengetik Lokal, Pembaruan Otomatis via GitHub Releases, serta Login Google & Verifikasi Perangkat Firebase.
