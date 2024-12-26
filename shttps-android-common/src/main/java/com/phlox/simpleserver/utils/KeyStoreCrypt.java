package com.phlox.simpleserver.utils;

import android.content.Context;
import android.security.KeyPairGeneratorSpec;

import com.phlox.server.platform.Base64;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;

public class KeyStoreCrypt {
    private final Context context;
    private final KeyStore keyStore;

    public KeyStoreCrypt(Context context) {
        this.context = context;
        this.keyStore = initKeyStore();
    }

    public byte[] encryptBytes(byte[] value, String keyAlias) throws Exception {
        RSAPublicKey publicKey;
        if (keyStore.containsAlias(keyAlias)) {
            KeyStore.PrivateKeyEntry privateKeyEntry = (KeyStore.PrivateKeyEntry)keyStore.getEntry(keyAlias, null);
            publicKey = (RSAPublicKey) privateKeyEntry.getCertificate().getPublicKey();
        } else {
            KeyPair keyPair = generateKeyPair(keyAlias);
            publicKey = (RSAPublicKey) keyPair.getPublic();
        }

        Cipher input = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        input.init(Cipher.ENCRYPT_MODE, publicKey);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CipherOutputStream cipherOutputStream = new CipherOutputStream(
                outputStream, input);
        cipherOutputStream.write(value);
        cipherOutputStream.close();

        return outputStream.toByteArray();
    }

    public byte[] decryptBytes(byte[] vals, String keyAlias) throws Exception {
        KeyStore.PrivateKeyEntry privateKeyEntry = (KeyStore.PrivateKeyEntry)keyStore.getEntry(keyAlias, null);
        PrivateKey privateKey = privateKeyEntry.getPrivateKey();
        Cipher output = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        output.init(Cipher.DECRYPT_MODE, privateKey);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CipherOutputStream cipherOutputStream = new CipherOutputStream(
                outputStream, output);
        cipherOutputStream.write(vals);
        cipherOutputStream.close();
        return outputStream.toByteArray();
    }

    public String encryptBytesB64(byte[] value, String keyAlias) throws Exception {
        return Base64.encodeToString(encryptBytes(value, keyAlias));
    }

    public byte[] decryptBytesB64(String encryptedBase64, String keyAlias) throws Exception {
        byte[] vals = Base64.decode(encryptedBase64);
        return decryptBytes(vals, keyAlias);
    }

    public String encrypt(String value, String keyAlias) throws Exception {
        return encryptBytesB64(value.getBytes("utf-8"), keyAlias);
    }

    public String decrypt(String value, String keyAlias) throws Exception {
        return new String(decryptBytesB64(value, keyAlias),"utf-8");
    }

    private KeyStore initKeyStore() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            return keyStore;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private KeyPair generateKeyPair(String alias) throws NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException {
        KeyPairGeneratorSpec spec = new KeyPairGeneratorSpec.Builder(context)
                .setAlias(alias)
                .setSubject(new javax.security.auth.x500.X500Principal("CN=" + alias))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(new java.util.Date(System.currentTimeMillis() - 1000 * 60 * 60 * 24))//yesterday
                .setEndDate(new java.util.Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 1000))//1000 years
                .build();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
        generator.initialize(spec);

        return generator.generateKeyPair();
    }

    public KeyStore getKeyStore() {
        return keyStore;
    }
}
