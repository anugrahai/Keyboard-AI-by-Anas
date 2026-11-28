package com.annas;

import java.util.HashMap;
import java.util.Map;

public class BrailleHelper {

    // Bitmask representasi 6 titik standar Braille:
    // Titik 1 = bit 0 (1)  - Kiri Atas
    // Titik 2 = bit 1 (2)  - Kiri Tengah
    // Titik 3 = bit 2 (4)  - Kiri Bawah
    // Titik 4 = bit 3 (8)  - Kanan Atas
    // Titik 5 = bit 4 (16) - Kanan Tengah
    // Titik 6 = bit 5 (32) - Kanan Bawah

    public static final int DOT_1 = 1;
    public static final int DOT_2 = 2;
    public static final int DOT_3 = 4;
    public static final int DOT_4 = 8;
    public static final int DOT_5 = 16;
    public static final int DOT_6 = 32;

    public static final int SIGN_NUMBER = DOT_3 | DOT_4 | DOT_5 | DOT_6; // Titik 3-4-5-6 = 60
    public static final int SIGN_CAPITAL = DOT_6; // Titik 6 = 32

    private static final Map<Integer, Character> LETTER_MAP = new HashMap<>();
    private static final Map<Integer, Character> NUMBER_MAP = new HashMap<>();
    private static final Map<Integer, Character> PUNCT_MAP = new HashMap<>();
    private static final Map<Integer, String> PHONETIC_MAP = new HashMap<>();

    // Unified Arabic Braille (Grade 1) — standar resmi (Saudi 2002 / Liblouis / Mada) + Tajwid Qur'an
    private static final Map<Integer, String> ARABIC_LETTER_MAP = new HashMap<>();
    private static final Map<Integer, Character> ARABIC_DIACRITIC_MAP = new HashMap<>();
    private static final Map<Integer, Character> ARABIC_PUNCT_MAP = new HashMap<>();
    private static final Map<String, String> ARABIC_SPOKEN_MAP = new HashMap<>();

    static {
        // Alfabet Grade 1 Standar Braille Indonesia & Internasional
        LETTER_MAP.put(DOT_1, 'a');                                  // 1
        LETTER_MAP.put(DOT_1 | DOT_2, 'b');                          // 1-2
        LETTER_MAP.put(DOT_1 | DOT_4, 'c');                          // 1-4
        LETTER_MAP.put(DOT_1 | DOT_4 | DOT_5, 'd');                  // 1-4-5
        LETTER_MAP.put(DOT_1 | DOT_5, 'e');                          // 1-5
        LETTER_MAP.put(DOT_1 | DOT_2 | DOT_4, 'f');                  // 1-2-4
        LETTER_MAP.put(DOT_1 | DOT_2 | DOT_4 | DOT_5, 'g');          // 1-2-4-5
        LETTER_MAP.put(DOT_1 | DOT_2 | DOT_5, 'h');                  // 1-2-5
        LETTER_MAP.put(DOT_2 | DOT_4, 'i');                          // 2-4
        LETTER_MAP.put(DOT_2 | DOT_4 | DOT_5, 'j');                  // 2-4-5

        LETTER_MAP.put(DOT_1 | DOT_3, 'k');                          // 1-3
        LETTER_MAP.put(DOT_1 | DOT_2 | DOT_3, 'l');                  // 1-2-3
        LETTER_MAP.put(DOT_1 | DOT_3 | DOT_4, 'm');                  // 1-3-4
        LETTER_MAP.put(DOT_1 | DOT_3 | DOT_4 | DOT_5, 'n');          // 1-3-4-5
        LETTER_MAP.put(DOT_1 | DOT_3 | DOT_5, 'o');                  // 1-3-5
        LETTER_MAP.put(DOT_1 | DOT_2 | DOT_3 | DOT_4, 'p');          // 1-2-3-4
        LETTER_MAP.put(DOT_1 | DOT_2 | DOT_3 | DOT_4 | DOT_5, 'q');  // 1-2-3-4-5
        LETTER_MAP.put(DOT_1 | DOT_2 | DOT_3 | DOT_5, 'r');          // 1-2-3-5
        LETTER_MAP.put(DOT_2 | DOT_3 | DOT_4, 's');                  // 2-3-4
        LETTER_MAP.put(DOT_2 | DOT_3 | DOT_4 | DOT_5, 't');          // 2-3-4-5

        LETTER_MAP.put(DOT_1 | DOT_3 | DOT_6, 'u');                  // 1-3-6
        LETTER_MAP.put(DOT_1 | DOT_2 | DOT_3 | DOT_6, 'v');          // 1-2-3-6
        LETTER_MAP.put(DOT_2 | DOT_4 | DOT_5 | DOT_6, 'w');          // 2-4-5-6
        LETTER_MAP.put(DOT_1 | DOT_3 | DOT_4 | DOT_6, 'x');          // 1-3-4-6
        LETTER_MAP.put(DOT_1 | DOT_3 | DOT_4 | DOT_5 | DOT_6, 'y');  // 1-3-4-5-6
        LETTER_MAP.put(DOT_1 | DOT_3 | DOT_5 | DOT_6, 'z');          // 1-3-5-6

        // Angka Standar Braille (Ketika tanda angka aktif)
        NUMBER_MAP.put(DOT_1, '1');                                  // 1
        NUMBER_MAP.put(DOT_1 | DOT_2, '2');                          // 1-2
        NUMBER_MAP.put(DOT_1 | DOT_4, '3');                          // 1-4
        NUMBER_MAP.put(DOT_1 | DOT_4 | DOT_5, '4');                  // 1-4-5
        NUMBER_MAP.put(DOT_1 | DOT_5, '5');                          // 1-5
        NUMBER_MAP.put(DOT_1 | DOT_2 | DOT_4, '6');                  // 1-2-4
        NUMBER_MAP.put(DOT_1 | DOT_2 | DOT_4 | DOT_5, '7');          // 1-2-4-5
        NUMBER_MAP.put(DOT_1 | DOT_2 | DOT_5, '8');                  // 1-2-5
        NUMBER_MAP.put(DOT_2 | DOT_4, '9');                          // 2-4
        NUMBER_MAP.put(DOT_2 | DOT_4 | DOT_5, '0');                  // 2-4-5

        // Tanda Baca Standar Braille Indonesia, UEB & Matematika (Super Komplit)
        PUNCT_MAP.put(DOT_2 | DOT_5 | DOT_6, '.');                   // Titik (2-5-6)
        PUNCT_MAP.put(DOT_2, ',');                                   // Koma (2)
        PUNCT_MAP.put(DOT_2 | DOT_5, ':');                           // Titik dua (2-5)
        PUNCT_MAP.put(DOT_2 | DOT_3, ';');                           // Titik koma (2-3)
        PUNCT_MAP.put(DOT_2 | DOT_3 | DOT_5, '!');                   // Tanda seru (2-3-5)
        PUNCT_MAP.put(DOT_2 | DOT_3 | DOT_6, '?');                   // Tanda tanya (2-3-6)
        PUNCT_MAP.put(DOT_3 | DOT_6, '-');                           // Tanda hubung / minus (3-6)
        PUNCT_MAP.put(DOT_3, '\'');                                  // Petik tunggal (3)
        PUNCT_MAP.put(DOT_2 | DOT_3 | DOT_5 | DOT_6, '"');           // Petik ganda (2-3-5-6)
        PUNCT_MAP.put(DOT_1 | DOT_2 | DOT_6, '(');                   // Kurung buka (1-2-6)
        PUNCT_MAP.put(DOT_3 | DOT_4 | DOT_5, ')');                   // Kurung tutup (3-4-5)
        PUNCT_MAP.put(DOT_1 | DOT_5 | DOT_6, '[');                   // Kurung siku buka (1-5-6)
        PUNCT_MAP.put(DOT_1 | DOT_2 | DOT_4 | DOT_5 | DOT_6, ']');   // Kurung siku tutup (1-2-4-5-6)
        PUNCT_MAP.put(DOT_2 | DOT_3 | DOT_4 | DOT_6, '{');           // Kurung kurawal buka (2-3-4-6)
        PUNCT_MAP.put(DOT_1 | DOT_2 | DOT_3 | DOT_5 | DOT_6, '}');   // Kurung kurawal tutup (1-2-3-5-6)
        PUNCT_MAP.put(DOT_3 | DOT_4, '/');                           // Garis miring (3-4)
        PUNCT_MAP.put(DOT_1 | DOT_2 | DOT_5 | DOT_6, '\\');          // Garis miring terbalik (1-2-5-6)
        PUNCT_MAP.put(DOT_4 | DOT_5 | DOT_6, '_');                   // Garis bawah (4-5-6)
        PUNCT_MAP.put(DOT_3 | DOT_5, '*');                           // Tanda bintang / kali (3-5)
        PUNCT_MAP.put(DOT_3 | DOT_4 | DOT_6, '+');                   // Tanda tambah (3-4-6)
        PUNCT_MAP.put(DOT_1 | DOT_2 | DOT_3 | DOT_4 | DOT_5 | DOT_6, '='); // Sama dengan (1-2-3-4-5-6)
        PUNCT_MAP.put(DOT_2 | DOT_4 | DOT_6, '<');                   // Lebih kecil (2-4-6)
        PUNCT_MAP.put(DOT_2 | DOT_3 | DOT_4 | DOT_5 | DOT_6, '>');   // Lebih besar (2-3-4-5-6)
        PUNCT_MAP.put(DOT_1 | DOT_2 | DOT_3 | DOT_4 | DOT_6, '&');   // Tanda dan ampersand (1-2-3-4-6)
        PUNCT_MAP.put(DOT_1 | DOT_4 | DOT_6, '%');                   // Tanda persen (1-4-6)
        PUNCT_MAP.put(DOT_1 | DOT_2 | DOT_4 | DOT_6, '$');           // Tanda dolar (1-2-4-6)
        PUNCT_MAP.put(DOT_4, '@');                                   // Tanda at (4)
        PUNCT_MAP.put(DOT_4 | DOT_5, '^');                           // Tanda pangkat caret (4-5)
        PUNCT_MAP.put(DOT_4 | DOT_6, '~');                           // Tanda gelombang tilde (4-6)
        PUNCT_MAP.put(DOT_1 | DOT_4 | DOT_5 | DOT_6, '#');           // Tanda pagar / hash (1-4-5-6)
        PUNCT_MAP.put(DOT_3 | DOT_5 | DOT_6, '°');                   // Derajat (3-5-6)
        PUNCT_MAP.put(DOT_1 | DOT_6, '×');                           // Tanda kali silang (1-6) — UEB
        // DOT 2-6: di UEB sering dipakai sebagai tanda petik buka / indikator;
        // jangan dipetakan ke '?' (yang resmi adalah 2-3-6) agar struktur resmi terjaga.
        PUNCT_MAP.put(DOT_2 | DOT_6, '«');                           // Petik buka (2-6) — struktur resmi
        PUNCT_MAP.put(DOT_5, '`');                                   // Backtick / aksen (5)
        PUNCT_MAP.put(DOT_5 | DOT_6, '|');                           // Garis pipa tegak (5-6)

        // Fonetik TTS Bahasa Indonesia Jelas & Alami
        PHONETIC_MAP.put((int) '.', "Titik");
        PHONETIC_MAP.put((int) ',', "Koma");
        PHONETIC_MAP.put((int) ':', "Titik dua");
        PHONETIC_MAP.put((int) ';', "Titik koma");
        PHONETIC_MAP.put((int) '!', "Tanda seru");
        PHONETIC_MAP.put((int) '?', "Tanda tanya");
        PHONETIC_MAP.put((int) '-', "Tanda hubung");
        PHONETIC_MAP.put((int) '\'', "Petik tunggal");
        PHONETIC_MAP.put((int) '"', "Petik ganda");
        PHONETIC_MAP.put((int) '(', "Buka kurung");
        PHONETIC_MAP.put((int) ')', "Tutup kurung");
        PHONETIC_MAP.put((int) '[', "Buka kurung siku");
        PHONETIC_MAP.put((int) ']', "Tutup kurung siku");
        PHONETIC_MAP.put((int) '{', "Buka kurung kurawal");
        PHONETIC_MAP.put((int) '}', "Tutup kurung kurawal");
        PHONETIC_MAP.put((int) '/', "Garis miring");
        PHONETIC_MAP.put((int) '\\', "Garis miring terbalik");
        PHONETIC_MAP.put((int) '_', "Garis bawah");
        PHONETIC_MAP.put((int) '*', "Bintang");
        PHONETIC_MAP.put((int) '+', "Tambah");
        PHONETIC_MAP.put((int) '=', "Sama dengan");
        PHONETIC_MAP.put((int) '<', "Lebih kecil");
        PHONETIC_MAP.put((int) '>', "Lebih besar");
        PHONETIC_MAP.put((int) '&', "Tanda dan");
        PHONETIC_MAP.put((int) '%', "Persen");
        PHONETIC_MAP.put((int) '$', "Dolar");
        PHONETIC_MAP.put((int) '@', "Tanda et");
        PHONETIC_MAP.put((int) '^', "Pangkat");
        PHONETIC_MAP.put((int) '~', "Tilde");
        PHONETIC_MAP.put((int) '#', "Tanda pagar");
        PHONETIC_MAP.put((int) '°', "Derajat");
        PHONETIC_MAP.put((int) '×', "Kali");
        PHONETIC_MAP.put((int) '«', "Petik buka");
        PHONETIC_MAP.put((int) '`', "Petik terbalik");
        PHONETIC_MAP.put((int) '|', "Garis tegak");

        initArabicBraille();
    }

    private static void initArabicBraille() {
        // =======================================================
        // UNIFIED ARABIC BRAILLE GRADE 1 (resmi)
        // =======================================================
        // Huruf dasar
        ARABIC_LETTER_MAP.put(DOT_1, "ا");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_2, "ب");
        ARABIC_LETTER_MAP.put(DOT_2 | DOT_3 | DOT_4 | DOT_5, "ت");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_4 | DOT_5 | DOT_6, "ث");
        ARABIC_LETTER_MAP.put(DOT_2 | DOT_4 | DOT_5, "ج");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_5 | DOT_6, "ح");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_3 | DOT_4 | DOT_6, "خ");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_4 | DOT_5, "د");
        ARABIC_LETTER_MAP.put(DOT_2 | DOT_3 | DOT_4 | DOT_6, "ذ");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_2 | DOT_3 | DOT_5, "ر");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_3 | DOT_5 | DOT_6, "ز");
        ARABIC_LETTER_MAP.put(DOT_2 | DOT_3 | DOT_4, "س");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_4 | DOT_6, "ش");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_2 | DOT_3 | DOT_4 | DOT_6, "ص");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_2 | DOT_4 | DOT_6, "ض");
        ARABIC_LETTER_MAP.put(DOT_2 | DOT_3 | DOT_4 | DOT_5 | DOT_6, "ط");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_2 | DOT_3 | DOT_4 | DOT_5 | DOT_6, "ظ");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_2 | DOT_3 | DOT_5 | DOT_6, "ع");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_2 | DOT_6, "غ");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_2 | DOT_4, "ف");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_2 | DOT_3 | DOT_4 | DOT_5, "ق");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_3, "ك");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_2 | DOT_3, "ل");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_3 | DOT_4, "م");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_3 | DOT_4 | DOT_5, "ن");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_2 | DOT_5, "ه");
        ARABIC_LETTER_MAP.put(DOT_2 | DOT_4 | DOT_5 | DOT_6, "و");
        ARABIC_LETTER_MAP.put(DOT_2 | DOT_4, "ي");

        // Bentuk tambahan, hamza & Tajwid Al-Qur'an
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_2 | DOT_3 | DOT_6, "لا"); // ligature lam-alef
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_3 | DOT_5, "ى");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_6, "ة");
        ARABIC_LETTER_MAP.put(DOT_3, "ء");
        ARABIC_LETTER_MAP.put(DOT_3 | DOT_4, "أ");
        ARABIC_LETTER_MAP.put(DOT_4 | DOT_6, "إ");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_2 | DOT_5 | DOT_6, "ؤ");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_3 | DOT_4 | DOT_5 | DOT_6, "ئ");
        ARABIC_LETTER_MAP.put(DOT_3 | DOT_4 | DOT_5, "آ");
        ARABIC_LETTER_MAP.put(DOT_1 | DOT_2 | DOT_4 | DOT_5, "ٱ"); // Alif Wasal (Tajwid)

        // Syakal / harakat / tanda tajwid Qur'an
        ARABIC_DIACRITIC_MAP.put(DOT_2, 'َ');                           // fatha
        ARABIC_DIACRITIC_MAP.put(DOT_2 | DOT_3, 'ً');                   // fathatan
        ARABIC_DIACRITIC_MAP.put(DOT_1 | DOT_5, 'ِ');                   // kasra
        ARABIC_DIACRITIC_MAP.put(DOT_3 | DOT_5, 'ٍ');                   // kasratan
        ARABIC_DIACRITIC_MAP.put(DOT_1 | DOT_3 | DOT_6, 'ُ');           // damma
        ARABIC_DIACRITIC_MAP.put(DOT_2 | DOT_6, 'ٌ');                   // dammatan
        ARABIC_DIACRITIC_MAP.put(DOT_2 | DOT_5, 'ْ');                   // sukun
        ARABIC_DIACRITIC_MAP.put(DOT_6, 'ّ');                           // shadda
        ARABIC_DIACRITIC_MAP.put(DOT_2 | DOT_4 | DOT_6, 'ٓ');           // maddah (mad panjang)
        ARABIC_DIACRITIC_MAP.put(DOT_4 | DOT_5, 'ٰ');                   // alif khanjariyah (mad tegak)
        ARABIC_DIACRITIC_MAP.put(DOT_1 | DOT_4, 'ٔ');                   // hamza di atas (kecil)
        ARABIC_DIACRITIC_MAP.put(DOT_3 | DOT_4 | DOT_6, 'ٕ');           // hamza di bawah (kecil)
        ARABIC_DIACRITIC_MAP.put(DOT_1 | DOT_2 | DOT_4 | DOT_5 | DOT_6, 'ۢ'); // Mim kecil Iqlab (Tajwid)

        // Tanda baca Arab (bebas tabrakan bitmask!)
        ARABIC_PUNCT_MAP.put(DOT_5, '،');                              // koma Arab
        ARABIC_PUNCT_MAP.put(DOT_5 | DOT_6, '؛');                      // titik koma Arab
        ARABIC_PUNCT_MAP.put(DOT_1 | DOT_2 | DOT_3 | DOT_4, ':');      // titik dua
        ARABIC_PUNCT_MAP.put(DOT_2 | DOT_5 | DOT_6, '.');              // titik
        ARABIC_PUNCT_MAP.put(DOT_2 | DOT_3 | DOT_6, '؟');              // tanya Arab
        ARABIC_PUNCT_MAP.put(DOT_2 | DOT_3 | DOT_5, '!');              // seru
        ARABIC_PUNCT_MAP.put(DOT_3 | DOT_6, '-');                      // hubung
        ARABIC_PUNCT_MAP.put(DOT_2 | DOT_3 | DOT_5 | DOT_6, '"');      // petik
        ARABIC_PUNCT_MAP.put(DOT_3 | DOT_5 | DOT_6, '»');              // petik tutup
        ARABIC_PUNCT_MAP.put(DOT_4, '«');                              // petik buka
        ARABIC_PUNCT_MAP.put(DOT_4 | DOT_5 | DOT_6, 'ـ');              // tatweel (kashida)
        // Tanda angka 3456 = SIGN_NUMBER

        // Label suara TTS jelas untuk harakat, tajwid, waqaf, hamzah, & simbol Arab
        ARABIC_SPOKEN_MAP.put("َ", "Fathah");
        ARABIC_SPOKEN_MAP.put("ً", "Fathatain");
        ARABIC_SPOKEN_MAP.put("ِ", "Kasrah");
        ARABIC_SPOKEN_MAP.put("ٍ", "Kasratain");
        ARABIC_SPOKEN_MAP.put("ُ", "Dhammah");
        ARABIC_SPOKEN_MAP.put("ٌ", "Dhammatain");
        ARABIC_SPOKEN_MAP.put("ْ", "Sukun");
        ARABIC_SPOKEN_MAP.put("ّ", "Tasydid");
        ARABIC_SPOKEN_MAP.put("ٓ", "Maddah panjang");
        ARABIC_SPOKEN_MAP.put("ٰ", "Alif Khanjariyah");
        ARABIC_SPOKEN_MAP.put("ٔ", "Hamzah kecil atas");
        ARABIC_SPOKEN_MAP.put("ٕ", "Hamzah kecil bawah");
        ARABIC_SPOKEN_MAP.put("ٱ", "Alif Wasal");
        ARABIC_SPOKEN_MAP.put("ۢ", "Mim Iqlab atas");
        ARABIC_SPOKEN_MAP.put("ۭ", "Mim Iqlab bawah");
        ARABIC_SPOKEN_MAP.put("ۡ", "Sukun Mushaf");
        ARABIC_SPOKEN_MAP.put("۟", "Shifr Mustadir");
        ARABIC_SPOKEN_MAP.put("۠", "Shifr Mustatil");
        ARABIC_SPOKEN_MAP.put("ۤ", "Mad Mushaf panjang");
        ARABIC_SPOKEN_MAP.put("ۥ", "Waw kecil Mad");
        ARABIC_SPOKEN_MAP.put("ۦ", "Ya kecil Mad");
        ARABIC_SPOKEN_MAP.put("ۖ", "Waqaf Shali, wasal lebih utama");
        ARABIC_SPOKEN_MAP.put("ۗ", "Waqaf Qali, berhenti lebih utama");
        ARABIC_SPOKEN_MAP.put("ۘ", "Waqaf Lazim Mim, wajib berhenti");
        ARABIC_SPOKEN_MAP.put("ۙ", "Tanda Laa, dilarang berhenti");
        ARABIC_SPOKEN_MAP.put("ۚ", "Waqaf Jaiz Jim, boleh berhenti");
        ARABIC_SPOKEN_MAP.put("ۛ", "Waqaf Mu'anaqah, berhenti di salah satu");
        ARABIC_SPOKEN_MAP.put("ۜ", "Saktah, berhenti sejenak tanpa napas");
        ARABIC_SPOKEN_MAP.put("۝", "Tanda akhir ayat");
        ARABIC_SPOKEN_MAP.put("۞", "Tanda Rub el Hizb, awal maqra");
        ARABIC_SPOKEN_MAP.put("۩", "Tanda ayat Sajdah");
        ARABIC_SPOKEN_MAP.put("﴾", "Kurung tutup ayat");
        ARABIC_SPOKEN_MAP.put("﴿", "Kurung buka ayat");
        ARABIC_SPOKEN_MAP.put("،", "Koma Arab");
        ARABIC_SPOKEN_MAP.put("؛", "Titik koma Arab");
        ARABIC_SPOKEN_MAP.put("؟", "Tanda tanya Arab");
        ARABIC_SPOKEN_MAP.put("٪", "Persen Arab");
        ARABIC_SPOKEN_MAP.put("٫", "Koma desimal Arab");
        ARABIC_SPOKEN_MAP.put("٬", "Pemisah ribuan Arab");
        ARABIC_SPOKEN_MAP.put("ـ", "Garis sambung kasidah");
        ARABIC_SPOKEN_MAP.put("«", "Petik buka Arab");
        ARABIC_SPOKEN_MAP.put("»", "Petik tutup Arab");
        ARABIC_SPOKEN_MAP.put("ﷺ", "Shallallahu alaihi wasallam");
        ARABIC_SPOKEN_MAP.put("ﷻ", "Jalla Jalaluhu");
        ARABIC_SPOKEN_MAP.put("﷽", "Bismillahirrahmanirrahim");
        ARABIC_SPOKEN_MAP.put("﷼", "Simbol Riyal");
        ARABIC_SPOKEN_MAP.put("ﷲ", "Lafaz Allah");
    }

    public static String getArabicSymbolSpokenName(String s) {
        if (s == null || s.isEmpty()) return "";
        String mapped = ARABIC_SPOKEN_MAP.get(s);
        return mapped != null ? mapped : s;
    }

    public static class ParseResult {
        public String text = "";
        public String spokenText = "";
        public boolean isNumberToggle = false;
        public boolean isCapitalToggle = false;
    }

    public static String getDotListString(int mask) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if ((mask & (1 << i)) != 0) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(i + 1);
            }
        }
        return sb.toString();
    }

    public static ParseResult parseBraille(int mask, boolean isNumberMode, boolean isCaps) {
        return parseBraille(mask, isNumberMode, isCaps, false);
    }

    /**
     * Parse chord Braille.
     * @param isArabicMode true = gunakan Unified Arabic Braille Grade 1 (huruf + syakal + tanda baca Arab)
     */
    public static ParseResult parseBraille(int mask, boolean isNumberMode, boolean isCaps, boolean isArabicMode) {
        ParseResult res = new ParseResult();

        if (mask == SIGN_NUMBER) {
            res.isNumberToggle = true;
            res.spokenText = isNumberMode ? "Mode huruf aktif" : "Mode angka aktif";
            return res;
        }

        // Di mode Arab, titik 6 = shadda (bukan capital Latin)
        if (!isArabicMode && mask == SIGN_CAPITAL) {
            res.isCapitalToggle = true;
            res.spokenText = isCaps ? "Huruf kecil" : "Huruf besar shift";
            return res;
        }

        if (isNumberMode) {
            Character numChar = NUMBER_MAP.get(mask);
            if (numChar != null) {
                if (isArabicMode) {
                    // Arab-Indic digits ٠١٢٣٤٥٦٧٨٩
                    char[] arDigits = {'٠','١','٢','٣','٤','٥','٦','٧','٨','٩'};
                    int d = (numChar == '0') ? 0 : (numChar - '0');
                    if (d >= 0 && d <= 9) {
                        res.text = String.valueOf(arDigits[d]);
                        res.spokenText = String.valueOf(arDigits[d]);
                    } else {
                        res.text = String.valueOf(numChar);
                        res.spokenText = String.valueOf(numChar);
                    }
                } else {
                    res.text = String.valueOf(numChar);
                    res.spokenText = "Angka " + numChar;
                }
                return res;
            }
            if (isArabicMode && mask == (DOT_1 | DOT_4 | DOT_6)) {
                res.text = "٪";
                res.spokenText = "Persen Arab";
                return res;
            }
            if (isArabicMode && mask == (DOT_2 | DOT_5)) {
                res.text = "٫";
                res.spokenText = "Koma desimal Arab";
                return res;
            }
            if (isArabicMode && mask == DOT_2) {
                res.text = "٬";
                res.spokenText = "Pemisah ribuan Arab";
                return res;
            }
            if (mask == (DOT_3 | DOT_6)) {
                res.text = "-";
                res.spokenText = "Kurang";
                return res;
            }
            if (mask == (DOT_3 | DOT_4 | DOT_6)) {
                res.text = "+";
                res.spokenText = "Tambah";
                return res;
            }
            if (mask == (DOT_3 | DOT_5) || mask == (DOT_1 | DOT_6)) {
                res.text = "×";
                res.spokenText = "Kali";
                return res;
            }
            if (mask == (DOT_3 | DOT_4)) {
                res.text = "/";
                res.spokenText = "Bagi";
                return res;
            }
            if (mask == (DOT_1 | DOT_2 | DOT_3 | DOT_4 | DOT_5 | DOT_6)) {
                res.text = "=";
                res.spokenText = "Sama dengan";
                return res;
            }
            if (mask == (DOT_2 | DOT_4 | DOT_6)) {
                res.text = "<";
                res.spokenText = "Lebih kecil";
                return res;
            }
            if (mask == (DOT_2 | DOT_3 | DOT_4 | DOT_5 | DOT_6)) {
                res.text = ">";
                res.spokenText = "Lebih besar";
                return res;
            }
        }

        if (isArabicMode) {
            // Prioritas: diacritic → huruf → tanda baca Arab
            Character diac = ARABIC_DIACRITIC_MAP.get(mask);
            if (diac != null) {
                res.text = String.valueOf(diac);
                res.spokenText = getArabicSymbolSpokenName(res.text);
                return res;
            }
            String arLetter = ARABIC_LETTER_MAP.get(mask);
            if (arLetter != null) {
                res.text = arLetter;
                res.spokenText = getArabicSymbolSpokenName(arLetter);
                return res;
            }
            Character arPunct = ARABIC_PUNCT_MAP.get(mask);
            if (arPunct != null) {
                res.text = String.valueOf(arPunct);
                res.spokenText = getArabicSymbolSpokenName(res.text);
                return res;
            }
        } else {
            Character letter = LETTER_MAP.get(mask);
            if (letter != null) {
                if (isCaps) {
                    res.text = String.valueOf(Character.toUpperCase(letter));
                    res.spokenText = "Huruf besar " + letter;
                } else {
                    res.text = String.valueOf(letter);
                    res.spokenText = String.valueOf(letter);
                }
                return res;
            }

            Character punct = PUNCT_MAP.get(mask);
            if (punct != null) {
                res.text = String.valueOf(punct);
                String phon = PHONETIC_MAP.get((int) punct);
                res.spokenText = (phon != null) ? phon : String.valueOf(punct);
                return res;
            }
        }

        String dots = getDotListString(mask);
        res.spokenText = dots.isEmpty() ? "Tidak ada titik" : "Titik " + dots + " belum terdaftar";
        return res;
    }

    /**
     * Hanya karakter emoji mentah — tanpa label "wajah …".
     * Biarkan TTS/screen reader sistem yang mendeskripsikan.
     */
    public static String getEmojiName(String emoji, int pageIndex) {
        if (emoji == null || emoji.isEmpty()) return "";
        return emoji;
    }
}
