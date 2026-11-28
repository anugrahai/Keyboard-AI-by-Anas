package com.annas;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Palet warna & komponen UI terpadu berstandar profesional (Eye-Friendly Slate / Clean Light).
 * - Kartu menu menggunakan permukaan tenang dengan strip aksen kategori di sisi kiri dan hierarki
 *   judul + subjudul yang jelas agar nyaman dipandang, tidak menyilaukan mata, & ramah TalkBack.
 */
public final class UiKit {

    private UiKit() {}

    // Palet Dark Mode (Midnight Slate lembut, anti-silau)
    public static final int DARK_BG = Color.parseColor("#0B1120");
    public static final int DARK_SURFACE = Color.parseColor("#151E32");
    public static final int DARK_SURFACE_ALT = Color.parseColor("#1E293B");
    public static final int DARK_BORDER = Color.parseColor("#26354D");
    public static final int DARK_TEXT = Color.parseColor("#F1F5F9");
    public static final int DARK_MUTED = Color.parseColor("#94A3B8");

    // Palet Light Mode (Clean Neutral)
    public static final int LIGHT_BG = Color.parseColor("#F3F6FA");
    public static final int LIGHT_SURFACE = Color.parseColor("#FFFFFF");
    public static final int LIGHT_SURFACE_ALT = Color.parseColor("#E6ECF4");
    public static final int LIGHT_BORDER = Color.parseColor("#D0DAE6");
    public static final int LIGHT_TEXT = Color.parseColor("#0F172A");
    public static final int LIGHT_MUTED = Color.parseColor("#475569");

    // Warna Aksen Semantik Profesional (Halus & Seimbang)
    public static final int ACCENT_AI = Color.parseColor("#2563EB");       // Royal Blue
    public static final int ACCENT_AI_ALT = Color.parseColor("#4F46E5");   // Soft Indigo
    public static final int ACCENT_KB = Color.parseColor("#0F766E");       // Deep Calm Teal
    public static final int ACCENT_KB_ALT = Color.parseColor("#0284C7");   // Calm Ocean
    public static final int ACCENT_OFFICE = Color.parseColor("#047857");   // Calm Emerald
    public static final int ACCENT_WARM = Color.parseColor("#B45309");     // Muted Amber
    public static final int ACCENT_SYSTEM = Color.parseColor("#334155");   // Neutral Slate
    public static final int ACCENT_DANGER = Color.parseColor("#B91C1C");   // Controlled Crimson

    public static boolean isDark(SharedPreferences prefs) {
        return prefs == null || !"Light".equals(prefs.getString("ui_theme", "Dark"));
    }

    public static int bg(SharedPreferences prefs) { return isDark(prefs) ? DARK_BG : LIGHT_BG; }
    public static int surface(SharedPreferences prefs) { return isDark(prefs) ? DARK_SURFACE : LIGHT_SURFACE; }
    public static int surfaceAlt(SharedPreferences prefs) { return isDark(prefs) ? DARK_SURFACE_ALT : LIGHT_SURFACE_ALT; }
    public static int border(SharedPreferences prefs) { return isDark(prefs) ? DARK_BORDER : LIGHT_BORDER; }
    public static int textPrimary(SharedPreferences prefs) { return isDark(prefs) ? DARK_TEXT : LIGHT_TEXT; }
    public static int textMuted(SharedPreferences prefs) { return isDark(prefs) ? DARK_MUTED : LIGHT_MUTED; }

    public static int dp(Context ctx, int dpVal) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return Math.round(dpVal * d);
    }

    /** Terapkan warna bar sistem (status bar & navigation bar) sesuai tema Dark/Light. */
    public static void applySystemBars(Activity act, SharedPreferences prefs) {
        if (act == null) return;
        try {
            Window w = act.getWindow();
            if (w == null) return;
            boolean dark = isDark(prefs);
            int bgColor = bg(prefs);
            w.setStatusBarColor(bgColor);
            w.setNavigationBarColor(surface(prefs));
            if (Build.VERSION.SDK_INT >= 23) {
                View decor = w.getDecorView();
                int flags = decor.getSystemUiVisibility();
                if (dark) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (Build.VERSION.SDK_INT >= 26) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                decor.setSystemUiVisibility(flags);
            }
        } catch (Throwable ignored) {}
    }

    /** Builder AlertDialog yang selalu selaras dengan tema Dark/Light aplikasi. */
    public static AlertDialog.Builder dialog(Context ctx, SharedPreferences prefs) {
        int themeRes = isDark(prefs)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        return new AlertDialog.Builder(ctx, themeRes);
    }

    public static boolean isActivityAlive(Context ctx) {
        if (!(ctx instanceof Activity)) return ctx != null;
        Activity a = (Activity) ctx;
        return !a.isFinishing() && !a.isDestroyed();
    }

    public static GradientDrawable roundedBox(int fillColor, int strokeColor, int radiusPx, int strokePx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        gd.setCornerRadius(radiusPx);
        if (strokePx > 0) gd.setStroke(strokePx, strokeColor);
        return gd;
    }

    /** Kartu menu profesional dengan garis tepi lembut & strip aksen warna di sisi kiri. */
    public static Drawable makeAccentCardDrawable(Context ctx, SharedPreferences prefs, int accentColor) {
        int rad = dp(ctx, 14);
        GradientDrawable accentLayer = new GradientDrawable();
        accentLayer.setShape(GradientDrawable.RECTANGLE);
        accentLayer.setColor(accentColor);
        accentLayer.setCornerRadius(rad);

        GradientDrawable cardLayer = new GradientDrawable();
        cardLayer.setShape(GradientDrawable.RECTANGLE);
        cardLayer.setColor(surface(prefs));
        cardLayer.setCornerRadius(rad);
        cardLayer.setStroke(dp(ctx, 1), border(prefs));

        LayerDrawable ld = new LayerDrawable(new Drawable[]{accentLayer, cardLayer});
        ld.setLayerInset(1, dp(ctx, 5), 0, 0, 0);
        return ld;
    }

    public static LinearLayout makeCard(Context ctx, SharedPreferences prefs) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 14);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(roundedBox(surface(prefs), border(prefs), dp(ctx, 16), dp(ctx, 1)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(ctx, 12));
        card.setLayoutParams(lp);
        return card;
    }

    public static Button makeMenuButton(Context ctx, String title, String subtitle, int accentColor,
                                        SharedPreferences prefs, View.OnClickListener listener) {
        Button btn = new Button(ctx);
        String t = title == null ? "" : title;
        String s = subtitle == null ? "" : subtitle;
        if (!s.isEmpty()) {
            String full = t + "\n" + s;
            SpannableString span = new SpannableString(full);
            int split = t.length();
            span.setSpan(new StyleSpan(Typeface.BOLD), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new AbsoluteSizeSpan(15, true), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new ForegroundColorSpan(textPrimary(prefs)), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            span.setSpan(new StyleSpan(Typeface.NORMAL), split + 1, full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new AbsoluteSizeSpan(12, true), split + 1, full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new ForegroundColorSpan(textMuted(prefs)), split + 1, full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btn.setText(span);
        } else {
            btn.setText(t);
            btn.setTextColor(textPrimary(prefs));
            btn.setTextSize(15);
            btn.setTypeface(null, Typeface.BOLD);
        }

        btn.setAllCaps(false);
        btn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        btn.setLineSpacing(dp(ctx, 3), 1.05f);
        btn.setPadding(dp(ctx, 18), dp(ctx, 13), dp(ctx, 16), dp(ctx, 13));
        btn.setBackground(makeAccentCardDrawable(ctx, prefs, accentColor));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(ctx, 10));
        btn.setLayoutParams(lp);
        btn.setMinimumHeight(dp(ctx, 56));
        btn.setContentDescription(!s.isEmpty() ? (t + ". " + s) : t);
        btn.setOnClickListener(listener);
        return btn;
    }

    public static Button makePillButton(Context ctx, String label, int bgColor, int textColor, View.OnClickListener listener) {
        Button btn = new Button(ctx);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setTextColor(textColor);
        btn.setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 9));
        btn.setBackground(roundedBox(bgColor, Color.TRANSPARENT, dp(ctx, 12), 0));
        btn.setOnClickListener(listener);
        return btn;
    }

    public static TextView makeSectionLabel(Context ctx, String text, int accentColor) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(lightenIfTooDark(accentColor));
        tv.setTextSize(12);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setLetterSpacing(0.08f);
        tv.setPadding(dp(ctx, 4), dp(ctx, 14), dp(ctx, 4), dp(ctx, 8));
        return tv;
    }

    private static int lightenIfTooDark(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[2] < 0.72f) hsv[2] = 0.78f;
        if (hsv[1] > 0.65f) hsv[1] = 0.60f;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    public static TextView makeHeader(Context ctx, String text, int accentColor, SharedPreferences prefs) {
        int col = isDark(prefs) ? Color.parseColor("#93C5FD") : accentColor;
        return makeSectionLabel(ctx, text, col);
    }

    public static void styleButton(Context ctx, Button btn, int bgColor) {
        btn.setBackground(roundedBox(bgColor, Color.TRANSPARENT, dp(ctx, 12), 0));
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setTextSize(14);
        btn.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(ctx, 5), 0, dp(ctx, 5));
        btn.setLayoutParams(lp);
    }

    public static void styleEditText(Context ctx, EditText et, SharedPreferences prefs) {
        et.setTextColor(textPrimary(prefs));
        et.setHintTextColor(textMuted(prefs));
        et.setBackground(roundedBox(surfaceAlt(prefs), border(prefs), dp(ctx, 10), dp(ctx, 1)));
        int p = dp(ctx, 12);
        et.setPadding(p, dp(ctx, 10), p, dp(ctx, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(ctx, 4), 0, dp(ctx, 8));
        et.setLayoutParams(lp);
    }

    public static void styleSwitch(Context ctx, Switch sw, SharedPreferences prefs) {
        sw.setTextColor(textPrimary(prefs));
        sw.setTextSize(14);
        sw.setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8));
    }

    public static void styleCheckBox(Context ctx, CheckBox cb, SharedPreferences prefs) {
        cb.setTextColor(textPrimary(prefs));
        cb.setTextSize(14);
        cb.setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6));
    }

    // Helper baca SharedPreferences anti-ClassCastException
    public static int readIntPref(SharedPreferences prefs, String key, int def) {
        if (prefs == null || !prefs.contains(key)) return def;
        try { return prefs.getInt(key, def); } catch (ClassCastException e) {
            try {
                String s = prefs.getString(key, "");
                if (s != null) {
                    String digits = s.replaceAll("[^0-9-]", "");
                    if (!digits.isEmpty()) return Integer.parseInt(digits);
                }
            } catch (Throwable ignored) {}
            try { return Math.round(prefs.getFloat(key, def)); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return def;
    }

    public static float readFloatPref(SharedPreferences prefs, String key, float def) {
        if (prefs == null || !prefs.contains(key)) return def;
        try { return prefs.getFloat(key, def); } catch (ClassCastException e) {
            try { return prefs.getInt(key, Math.round(def)); } catch (Throwable ignored) {}
            try {
                String s = prefs.getString(key, "");
                if (s != null && !s.trim().isEmpty()) return Float.parseFloat(s.trim());
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return def;
    }

    public static String readStringPref(SharedPreferences prefs, String key, String def) {
        if (prefs == null || !prefs.contains(key)) return def;
        try {
            String s = prefs.getString(key, def);
            return s != null ? s : def;
        } catch (ClassCastException e) {
            try { return String.valueOf(prefs.getInt(key, 0)); } catch (Throwable ignored) {}
            try { return String.valueOf(prefs.getFloat(key, 0f)); } catch (Throwable ignored) {}
            try { return String.valueOf(prefs.getBoolean(key, false)); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return def;
    }
}
