package com.aurea.diagnostics;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class GitHubReporter {
    private static final String ALIAS = "aurea_github_report_token";
    private static final String REPO = "itztbnrpvpzlol-max/Kineticv2";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private GitHubReporter() {}

    public static void saveToken(Context context, String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        File target = new File(context.getFilesDir(), "github_token.enc");
        File temp = new File(context.getFilesDir(), "github_token.enc.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(cipher.getIV()); out.write(encrypted); out.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new IOException("Cannot replace token file");
        if (!temp.renameTo(target)) throw new IOException("Cannot save encrypted token");
    }

    private static SecretKey getKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build());
        return gen.generateKey();
    }

    private static String loadToken(Context context) throws Exception {
        File f = new File(context.getFilesDir(), "github_token.enc");
        if (!f.isFile()) return "";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) bytes.write(b, 0, n);
        }
        byte[] all = bytes.toByteArray();
        if (all.length <= 12) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, Arrays.copyOfRange(all, 0, 12)));
        return new String(cipher.doFinal(Arrays.copyOfRange(all, 12, all.length)), StandardCharsets.UTF_8);
    }

    private static boolean isFailure(String report) {
        return report != null && (report.contains("FAIL ") || report.contains("JAVA_EXCEPTION")
            || report.contains("DIAGNOSIS: Isolated native process stopped unexpectedly")
            || report.contains("PROBE_COMPLETE initialized=false"));
    }

    public static void uploadIfFailure(Context context, String report) {
        if (!isFailure(report)) return;
        EXECUTOR.execute(() -> upload(context.getApplicationContext(), report));
    }

    public static void uploadIfFailureBlocking(Context context, String report) {
        if (isFailure(report)) upload(context.getApplicationContext(), report);
    }

    private static void upload(Context context, String report) {
        HttpURLConnection conn = null;
        try {
            String token = loadToken(context);
            if (token.isEmpty()) return;
            String normalized = report.replaceAll("(?m)^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} ", "");
            String signature = hex(MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)));
            SharedPreferences prefs = context.getSharedPreferences("github_auto_report", Context.MODE_PRIVATE);
            if (signature.equals(prefs.getString("last_signature", ""))) return;
            String safe = redact(report).replace(token, "[REDACTED_TOKEN]");
            if (safe.length() > 55000) safe = safe.substring(0, 55000) + "\n[Report truncated]";
            String model = Build.MANUFACTURER + " " + Build.MODEL;
            JSONObject payload = new JSONObject();
            payload.put("title", "Aurea native test failure - " + model);
            payload.put("body", "Automatic diagnostic report from Aurea Native Tester.\n\nDevice: " + model
                + "\nAndroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
                + ")\nSignature: " + signature + "\n\n" + safe);
            conn = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/issues").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000); conn.setReadTimeout(20000); conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("User-Agent", "Aurea-Native-Tester");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) { out.write(body); }
            if (conn.getResponseCode() == 201) {
                String response;
                try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] b = new byte[2048]; int n;
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    response = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
                prefs.edit().putString("last_signature", signature)
                    .putString("last_issue_url", new JSONObject(response).optString("html_url", "")).apply();
            }
        } catch (Exception ignored) {
            // The local report remains available; refresh retries the upload.
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String redact(String s) {
        return s.replaceAll("(?i)\\b(?:ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|gho_[A-Za-z0-9]{20,}|ghs_[A-Za-z0-9]{20,})\\b", "[REDACTED_TOKEN]")
            .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[REDACTED_EMAIL]")
            .replaceAll("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b", "[REDACTED_IP]");
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return s.toString();
    }
}
