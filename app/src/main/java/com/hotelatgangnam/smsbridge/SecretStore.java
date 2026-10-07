package com.hotelatgangnam.smsbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores Slack credentials encrypted by a non-exportable Android Keystore key. */
final class SecretStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "hotel_sms_bridge_config_v2";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private final SharedPreferences preferences;

    SecretStore(Context context) {
        preferences = context.getSharedPreferences("bridge_secrets", Context.MODE_PRIVATE);
    }

    synchronized void put(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            preferences.edit().remove(name).apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] initializationVector = cipher.getIV();
            ByteBuffer payload =
                    ByteBuffer.allocate(1 + initializationVector.length + encrypted.length);
            payload.put((byte) initializationVector.length);
            payload.put(initializationVector);
            payload.put(encrypted);
            preferences.edit()
                    .putString(name, Base64.encodeToString(payload.array(), Base64.NO_WRAP))
                    .apply();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Slack 자격 증명을 암호화하지 못했습니다.", error);
        }
    }

    synchronized String get(String name) {
        String encoded = preferences.getString(name, null);
        if (encoded == null) {
            return "";
        }
        try {
            ByteBuffer payload = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP));
            int vectorLength = payload.get() & 0xff;
            if (vectorLength < 12 || vectorLength > 32 || payload.remaining() <= vectorLength) {
                throw new GeneralSecurityException("Invalid encrypted payload");
            }
            byte[] initializationVector = new byte[vectorLength];
            payload.get(initializationVector);
            byte[] encrypted = new byte[payload.remaining()];
            payload.get(encrypted);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    new GCMParameterSpec(GCM_TAG_BITS, initializationVector));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException error) {
            throw new IllegalStateException(
                    "Slack 자격 증명을 해독하지 못했습니다. 앱 데이터를 지우고 다시 설정하세요.",
                    error);
        }
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        try {
            keyStore.load(null);
        } catch (IOException | CertificateException error) {
            throw new GeneralSecurityException("Android Keystore is unavailable", error);
        }
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }

        KeyGenerator generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(
                new KeyGenParameterSpec.Builder(
                                KEY_ALIAS,
                                KeyProperties.PURPOSE_ENCRYPT
                                        | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build());
        return generator.generateKey();
    }
}
