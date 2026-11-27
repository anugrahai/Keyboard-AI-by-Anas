package com.annas;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.GoogleAuthProvider;

/**
 * Gerbang Login Google wajib sebelum masuk ke Keyboard AI by Anas.
 * Dirancang ramah mata & pembaca layar (TalkBack / Jieshuo).
 */
public class LoginActivity extends Activity {

    private static final int RC_GOOGLE_SIGN_IN = 9401;

    private SharedPreferences prefs;
    private GoogleSignInClient googleClient;
    private Button btnSignIn;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AuthManager.ensureFreshPreferences(this);
        prefs = OfficeUtils.mainPrefs(this);
        googleClient = AuthManager.buildGoogleSignInClient(this);

        if (AuthManager.isSignedIn()) {
            goToMain();
            return;
        }
        buildUi();
    }

    private void buildUi() {
        int bg = UiKit.bg(prefs);
        int txt = UiKit.textPrimary(prefs);
        int muted = UiKit.textMuted(prefs);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 32), UiKit.dp(this, 24), UiKit.dp(this, 32));

        LinearLayout card = UiKit.makeCard(this, prefs);

        TextView badge = new TextView(this);
        badge.setText("KEYBOARD AI BY ANAS");
        badge.setTextColor(UiKit.ACCENT_KB_ALT);
        badge.setTextSize(12);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setLetterSpacing(0.1f);
        card.addView(badge);

        TextView title = new TextView(this);
        title.setText("Masuk dengan Akun Google");
        title.setTextColor(txt);
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 10));
        card.addView(title);

        TextView desc = new TextView(this);
        desc.setText(
                "Untuk keamanan akun dan verifikasi akses fitur AI, silakan masuk menggunakan akun Google Anda.\n\n"
                + "• Mengetik keyboard, Braille, Kamus, Makro & Editor Teks tetap bisa dipakai gratis.\n"
                + "• Akses fitur AI (Chat, Revisi, Input Suara AI, Smart Reply) memakai API Key pribadi & membutuhkan status Premium.\n"
                + "• Status akun disimpan aman di server dan otomatis sinkron di perangkat Anda."
        );
        desc.setTextColor(muted);
        desc.setTextSize(14);
        desc.setLineSpacing(UiKit.dp(this, 3), 1.1f);
        desc.setPadding(0, 0, 0, UiKit.dp(this, 18));
        card.addView(desc);

        btnSignIn = UiKit.makeMenuButton(
                this,
                "Masuk dengan Google",
                "Pilih akun Google dari perangkat ini untuk melanjutkan",
                UiKit.ACCENT_AI,
                prefs,
                v -> startGoogleSignIn()
        );
        card.addView(btnSignIn);

        statusText = new TextView(this);
        statusText.setText("Siap masuk. Tekan tombol Masuk dengan Google di atas.");
        statusText.setTextColor(muted);
        statusText.setTextSize(13);
        statusText.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 10), UiKit.dp(this, 4), 0);
        card.addView(statusText);

        root.addView(card);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void startGoogleSignIn() {
        setBusy(true, "Membuka pemilih akun Google...");
        // Sign out Google client lokal dulu agar user selalu bisa memilih akun dengan jelas
        googleClient.signOut().addOnCompleteListener(t -> {
            Intent signInIntent = googleClient.getSignInIntent();
            startActivityForResult(signInIntent, RC_GOOGLE_SIGN_IN);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_GOOGLE_SIGN_IN) return;

        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
        try {
            GoogleSignInAccount account = task.getResult(ApiException.class);
            if (account == null || account.getIdToken() == null) {
                setBusy(false, "Gagal membaca token akun Google. Coba lagi.");
                return;
            }
            setBusy(true, "Memverifikasi akun & mengecek status server...");
            AuthCredential credential = GoogleAuthProvider.getCredential(account.getIdToken(), null);
            FirebaseAuth.getInstance().signInWithCredential(credential)
                    .addOnSuccessListener(authResult ->
                            AuthManager.syncUserFromServer(LoginActivity.this, true, new AuthManager.SyncCallback() {
                                @Override
                                public void onSynced(AuthManager.AccountStatus status) {
                                    OfficeUtils.announce(btnSignIn, "Berhasil masuk sebagai " + status.email);
                                    String msg = status.deviceAuthorized
                                            ? ("Selamat datang, " + status.displayName + " (" + status.getReadablePlanLabel() + ")")
                                            : ("Masuk sebagai " + status.email + ". Device ID HP ini belum terdaftar — hubungi Admin.");
                                    Toast.makeText(LoginActivity.this, msg, Toast.LENGTH_LONG).show();
                                    goToMain();
                                }

                                @Override
                                public void onError(String message) {
                                    // Tetap lanjut jika Firebase Auth sudah login, sinkron ulang nanti di MainActivity
                                    Toast.makeText(LoginActivity.this,
                                            "Masuk berhasil (sinkron server tertunda: " + message + ")",
                                            Toast.LENGTH_LONG).show();
                                    goToMain();
                                }
                            }))
                    .addOnFailureListener(e ->
                            setBusy(false, "Autentikasi Firebase gagal: " + e.getMessage()));
        } catch (ApiException e) {
            setBusy(false, "Login Google dibatalkan atau gagal (kode " + e.getStatusCode() + ").");
        }
    }

    private void setBusy(boolean busy, String message) {
        if (btnSignIn != null) btnSignIn.setEnabled(!busy);
        if (statusText != null) {
            statusText.setText(message);
            OfficeUtils.announce(statusText, message);
        }
    }

    private void goToMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }
}
