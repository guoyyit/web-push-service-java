package com.github.guoyyit.webpushservicejava.push.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

public final class CryptoUtils {

    public static final int P256_PUBLIC_KEY_LENGTH = 65;
    public static final int P256_PRIVATE_KEY_LENGTH = 32;

    private CryptoUtils() {
    }

    public static String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static byte[] base64UrlDecode(String data) {
        if (data == null) {
            throw new IllegalArgumentException("base64url value is null");
        }
        try {
            return Base64.getUrlDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid base64url value", e);
        }
    }

    public static PublicKey decodeP256PublicKey(String base64Url) {
        byte[] encoded = base64UrlDecode(base64Url);
        if (encoded.length != P256_PUBLIC_KEY_LENGTH || encoded[0] != 0x04) {
            throw new IllegalArgumentException(
                    "P-256 public key must be a 65-byte uncompressed point, got " + encoded.length + " bytes");
        }
        try {
            BigInteger x = new BigInteger(1, Arrays.copyOfRange(encoded, 1, 33));
            BigInteger y = new BigInteger(1, Arrays.copyOfRange(encoded, 33, 65));
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return keyFactory.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256Params()));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("invalid P-256 public key", e);
        }
    }

    public static String encodePublicKey(PublicKey publicKey) {
        java.security.interfaces.ECPublicKey ecKey = (java.security.interfaces.ECPublicKey) publicKey;
        byte[] x = toFixedLength(ecKey.getW().getAffineX(), 32);
        byte[] y = toFixedLength(ecKey.getW().getAffineY(), 32);
        byte[] encoded = new byte[P256_PUBLIC_KEY_LENGTH];
        encoded[0] = 0x04;
        System.arraycopy(x, 0, encoded, 1, 32);
        System.arraycopy(y, 0, encoded, 33, 32);
        return base64UrlEncode(encoded);
    }

    public static String encodePrivateKey(PrivateKey privateKey) {
        java.security.interfaces.ECPrivateKey ecKey = (java.security.interfaces.ECPrivateKey) privateKey;
        return base64UrlEncode(toFixedLength(ecKey.getS(), P256_PRIVATE_KEY_LENGTH));
    }

    public static PrivateKey decodeP256PrivateKey(String base64Url) {
        byte[] encoded = base64UrlDecode(base64Url);
        if (encoded.length != P256_PRIVATE_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "P-256 private key must be 32 bytes, got " + encoded.length + " bytes");
        }
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return keyFactory.generatePrivate(new ECPrivateKeySpec(new BigInteger(1, encoded), p256Params()));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("invalid P-256 private key", e);
        }
    }

    public static byte[] hkdfExtract(byte[] salt, byte[] ikm) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            return mac.doFinal(ikm);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] result = new byte[length];
            byte[] previous = new byte[0];
            int offset = 0;
            for (int counter = 1; offset < length; counter++) {
                mac.reset();
                mac.update(previous);
                mac.update(info);
                previous = mac.doFinal(new byte[]{(byte) counter});
                int copy = Math.min(previous.length, length - offset);
                System.arraycopy(previous, 0, result, offset, copy);
                offset += copy;
            }
            return result;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static ECParameterSpec p256Params() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("secp256r1 unavailable", e);
        }
    }

    public static byte[] toFixedLength(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        if (raw.length == length) {
            return raw;
        }
        if (raw.length > length) {
            return Arrays.copyOfRange(raw, raw.length - length, raw.length);
        }
        byte[] padded = new byte[length];
        System.arraycopy(raw, 0, padded, length - raw.length, raw.length);
        return padded;
    }
}
