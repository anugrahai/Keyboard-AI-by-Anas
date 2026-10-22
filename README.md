# Keyboard AI by Anas

**Pengembang:** Anugrah Anas (SonarLab Indonesia)  
**Versi dokumentasi:** menyesuaikan rilis terbaru di [Releases](https://github.com/anugrahai/Keyboard-AI-by-Anas/releases)  
**Platform:** Android 7.0 (API 24) ke atas  

Papan ketik Android yang menggabungkan tata letak QWERTY modern, asisten multi-AI, input suara, dan mode Braille layar penuh. Dirancang untuk **pengguna awas** dan **pengguna tunanetra / pembaca layar** (TalkBack, Jieshuo, dan sejenisnya).

---

## Unduhan

- **Versi terbaru (disarankan):**  
  https://github.com/anugrahai/Keyboard-AI-by-Anas/releases/latest  

- Rilis lama (contoh v6.0):  
  https://github.com/anugrahai/Keyboard-AI-by-Anas/releases/tag/v6.0  

Unduh berkas `.apk` dari halaman rilis, lalu pasang di perangkat. Setelah memperbarui, pastikan `versionCode` pada APK baru lebih tinggi agar sistem Android mengenali sebagai pembaruan.

---

## Instalasi dan aktivasi

1. Unduh APK dari halaman **Releases**, buka file tersebut di ponsel.
2. Izinkan instalasi dari sumber yang tidak dikenal jika diminta, lalu pasang.
3. Buka aplikasi **Keyboard AI**, ikuti wizard awal:
   - Aktifkan keyboard di **Pengaturan sistem → Bahasa & masukan → Keyboard virtual**.
   - Pilih **Keyboard AI by Anas** sebagai metode masukan aktif.
   - (Disarankan) Matikan optimasi baterai untuk aplikasi ini agar tidak dimatikan sistem.
   - (Opsional) Aktifkan layanan aksesibilitas **Smart Reply** jika ingin fitur baca konteks chat.
4. Buka aplikasi chat atau catatan, ketuk kotak tulis — keyboard siap dipakai.

---

## Ringkasan fitur

| Area | Isi singkat |
|------|-------------|
| Tata letak | QWERTY, simbol, numpad, emoji, font bergaya |
| AI | Gemini, Groq, DeepSeek, OpenAI, Grok, API eksternal |
| Suara | Google Speech + Whisper, perapian teks otomatis |
| Produktivitas | Kamus, makro, tempel cepat, kapitalisasi otomatis |
| Aksesibilitas | Braille 6 titik fullscreen, kalibrasi jari, rotor, TTS |
| Stabilitas | Penanganan khusus kotak edit (mis. pencarian WhatsApp, Gemini) |

---

## 1. Tata letak dan pengalaman mengetik (pengguna awas)

### Mode papan ketik
- **QWERTY** — huruf standar, Shift / Caps Lock.
- **Simbol 1** — angka 0–9 dan tanda baca / operator umum.
- **Simbol 2** — kurung, karakter khusus, mata uang, dan simbol lanjutan.
- **Numpad** — tata letak angka untuk input cepat (telepon, OTP, kalkulasi sederhana).

### Bantuan mengetik
- **Tempel cepat (Quick Paste)** — tempel isi clipboard tanpa menahan lama.
- **Kapitalisasi otomatis** — huruf besar di awal kalimat (dapat dimatikan di pengaturan).
- **Getaran (haptic)** dan **efek suara ketik** — dapat diatur atau dimatikan.
- **Popup karakter** saat tombol disentuh; **tahan Backspace** untuk hapus beruntun.
- **Tema antarmuka** — mode gelap / terang pada menu aplikasi.

### Emoji
- Penjelajah emoji multi-halaman (puluhan kategori).
- Navigasi halaman sebelumnya / berikutnya.
- Label yang ramah pembaca layar.

### Font bergaya
- Beberapa gaya huruf (misalnya gaya “fashion” / Unicode) yang dapat dipilih dari bilah keyboard, tanpa mengubah aplikasi tujuan.

---

## 2. Asisten multi-AI

AI dipanggil **langsung dari keyboard**, tanpa berpindah aplikasi. Teks yang sedang ditulis (atau hasil suara) dapat diproses dengan satu ketukan aksi.

### Provider yang didukung
- **Google Gemini** (mis. Flash / Pro)
- **Groq** (model cepat, misalnya Llama / Mixtral)
- **DeepSeek**
- **OpenAI** (ChatGPT / model yang tersedia di akun Anda)
- **xAI Grok**
- **API eksternal / Copilot-style** — endpoint sendiri, termasuk deteksi daftar model (mis. via parameter `?action=models`)

API key disimpan **lokal di perangkat** (SharedPreferences), tidak dikirim ke server Keyboard AI. Anda memasukkan kunci masing-masing provider di menu pengaturan API.

### Aksi teks siap pakai
- **Perbaiki tata bahasa** — perbaiki typo dan struktur kalimat.
- **Terjemahkan** — misalnya ke Bahasa Indonesia atau Inggris.
- **Ringkas teks** — rangkuman dari teks panjang atau yang disalin.
- **Lanjutkan tulisan** — draft email, pesan, status, dan sejenisnya.
- **Prompt kustom** — instruksi bebas (“ubah jadi formal”, “buat 3 opsi balasan”, dll.).

### Contoh cara memakai AI

**Contoh A — perbaiki pesan WhatsApp**  
1. Ketik atau tempel: `besok aku datang jam 3 ya kalo sempet`  
2. Pilih aksi **Perbaiki tata bahasa**.  
3. Hasil contoh: `Besok aku datang jam 3 ya, kalau sempat.`  
4. Tinjau, lalu kirim.

**Contoh B — terjemahan**  
1. Ketik teks bahasa sumber.  
2. Pilih **Terjemahkan** (sesuai arah bahasa di pengaturan / prompt).  
3. Ganti teks di kotak tulis dengan keluaran AI, atau sisipkan sesuai alur aplikasi.

**Contoh C — prompt bebas**  
1. Ketik atau tempel teks acuan.  
2. Buka **Custom prompt**, misalnya: `Buat 3 balasan singkat, sopan, untuk menolak undangan tanpa menyinggung.`  
3. Jalankan; pilih salah satu hasil yang cocok.

**Contoh D — dari suara**  
1. Ketuk mikrofon → bicara.  
2. Setelah teks muncul (Google Speech atau Whisper), jalankan aksi AI (perbaiki / terjemah / ringkas) seperti di atas.

Pastikan provider dan API key sudah diisi di pengaturan sebelum memakai aksi berbayar / berkuota.

---

## 3. Input suara

- **Google Speech Recognizer** — pengenalan cepat, cocok untuk percakapan sehari-hari.
- **OpenAI Whisper** (atau model transkripsi yang dikonfigurasi) — lebih tahan kebisingan dan pengucapan cepat; memerlukan kunci / endpoint yang sesuai.
- **Perapian teks otomatis** — kapitalisasi dan tanda baca dasar pada hasil suara (bergantung pipeline AI / logika lokal).

Alur umum: ketuk ikon mikrofon → izinkan mikrofon jika diminta → bicara → teks masuk ke kotak tulis → opsional lanjutkan dengan aksi AI.

---

## 4. Produktivitas: kamus, makro, Smart Reply

### Kamus pengganti
- Ubah singkatan atau kesalahan sering, misalnya `yg` → `yang`.
- Opsi dukungan pola (regex) untuk penggantian lebih lanjut.
- Diaktifkan lewat sakelar di pengaturan.

### Makro
- Pemicu pendek untuk teks panjang, misalnya `/alamat` → alamat lengkap.
- Cocok untuk template balasan, signature, atau data yang sering diketik.

### Smart Reply (aksesibilitas)
- Layanan aksesibilitas opsional untuk membaca **konteks chat** (peran pengirim / lawan, cuplikan pesan).
- Digunakan agar saran balasan lebih relevan dengan percakapan yang sedang dibuka.
- **Gaya balasan** dapat dipilih (contoh: natural santai, formal, singkat, lucu, dll.).
- Perlu mengaktifkan layanan di **Pengaturan aksesibilitas** sistem; tanpa itu, keyboard tetap jalan, hanya fitur baca konteks yang tidak aktif.

---

## 5. Mode Braille dan aksesibilitas (pengguna tunanetra)

Mode Braille ditujukan untuk mengetik dengan **enam titik** di layar penuh (portrait), dengan umpan balik TTS.

### Kalibrasi jari
- Tempelkan **enam jari sekaligus**, atau sentuh titik satu per satu (Titik 1–6).
- Opsi **Gunakan susunan standar**: baris atas titik 1–2–3, baris bawah titik 4–5–6.
- Kalibrasi dapat diulang jika posisi tangan berubah.

### Mengetik dan mengedit
- Kombinasi titik (chording) untuk huruf, angka, dan sejumlah simbol.
- Gesture hapus, spasi, enter, serta **sembunyikan keyboard** (misalnya geser dua jari) sesuai implementasi gesture di versi terpasang.
- **TTS internal** membacakan umpan balik aksi; kecepatan bicara dapat diatur.
- Pembacaan teks di kotak edit dirancang untuk teks panjang (banyak paragraf), bukan hanya cuplikan pendek, hingga dihentikan oleh pengguna.

### Rotor granularitas (navigasi teks)
- Ganti unit navigasi (karakter → kata → baris → paragraf) dengan gesture multi-jari sesuai dokumentasi di aplikasi.
- Geser sesuai unit aktif untuk pindah kursor atau hapus adaptif.

### Kompatibilitas pembaca layar
- Keyboard dapat dipakai bersama TalkBack / screen reader lain.
- Pada mode Braille, region **touch exploration passthrough** digunakan agar gesture satu jari tidak “dicuri” oleh pembaca layar saat mengetik.
- Saat keyboard disembunyikan, passthrough dilepas agar navigasi layar kembali normal.
- Layanan Smart Reply dan mode Braille dirancang agar tidak saling mengunci fokus pada kotak edit aplikasi host (termasuk skenario sensitif seperti **pencarian WhatsApp** dan **Gemini**).

### Pengguna awas + Braille
- Mode Braille dapat dijadikan default atau diganti ke QWERTY dari gesture / menu.
- Kedua mode berbagi pengaturan suara, getar, dan AI yang sama.

---

## 6. Pengaturan utama (dari aplikasi)

Buka aplikasi Keyboard AI atau pintasan pengaturan dari keyboard (tergantung versi: ikon roda gigi atau gesture). Item penting:

- Provider AI, API key, dan model per provider  
- Mode input suara (Google / Whisper)  
- Kamus dan makro  
- Gaya Smart Reply  
- Tema UI, getar, suara ketik  
- Mode Braille default, kalibrasi, kecepatan TTS  
- Kapitalisasi otomatis, opsi kirim / gaya menempel teks  

Wizard pertama kali juga mengarahkan aktivasi keyboard, pengecualian baterai, dan (opsional) aksesibilitas.

---

## 7. Catatan stabilitas kotak edit

Beberapa aplikasi memulai ulang atau menyembunyikan IME saat konten berubah (contoh: hasil pencarian WhatsApp yang terus diperbarui, atau fokus di Gemini). Versi terbaru menyesuaikan:

- **Pencarian WhatsApp** — keyboard tetap dapat tampil dan dipakai mengetik; state dijaga saat daftar hasil di-refresh; gesture mengetik pada mode Braille mengikuti pola yang sama seperti di chat bila memungkinkan.  
- **Gemini** — setelah keyboard disembunyikan secara sengaja, pembukaan ulang otomatis dibatasi agar tidak “muncul sendiri” saat pembaca layar hanya membaca kotak edit.  

Perilaku exact bergantung pada versi APK yang terpasang; selalu unduh dari **Releases → latest**.

---

## 8. Izin yang biasa diminta

- **Mikrofon** — input suara.  
- **Internet** — panggilan API AI / Whisper.  
- **Aksesibilitas** (opsional) — Smart Reply / baca konteks.  
- **Notifikasi / abaikan optimasi baterai** (disarankan) — agar IME tidak dimatikan sistem.  

Tidak diperlukan root.

---

## 9. Privasi singkat

- API key disimpan di perangkat.  
- Teks yang dikirim ke Gemini, OpenAI, Groq, dll. tunduk pada kebijakan masing-masing provider.  
- Tidak ada “akun Keyboard AI” wajib; aplikasi beroperasi sebagai IME lokal + klien API yang Anda konfigurasi sendiri.

---

## 10. Pengembangan dan kontribusi

- **Pengembang:** Anugrah Anas ([@anugrahai](https://github.com/anugrahai))  
- **Komunitas:** SonarLab Indonesia  
- **Email:** anugrahannas677@gmail.com  
- **Repositori:** https://github.com/anugrahai/Keyboard-AI-by-Anas  

Untuk distribusi APK baru: naikkan `versionCode` / `versionName`, build APK, lalu buat **Release GitHub baru** (tag baru, misalnya `v6.1`) dan unggah berkas `.apk`. Jangan menimpa aset pada tag lama jika ingin riwayat versi tetap jelas.

---

## 11. Lisensi dan tujuan

Proyek ini diarahkan untuk kemaslahatan aksesibilitas dan teknologi inklusif di Indonesia. Rincian lisensi mengikuti pernyataan di repositori / rilis; jika belum ada berkas LICENSE, hubungi pengembang sebelum redistribusi komersial.

---

## Tautan cepat

| Keperluan | URL |
|-----------|-----|
| Repo | https://github.com/anugrahai/Keyboard-AI-by-Anas |
| Unduh terbaru | https://github.com/anugrahai/Keyboard-AI-by-Anas/releases/latest |
| Profil pengembang | https://github.com/anugrahai |
