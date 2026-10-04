package com.github.guoyyit.webpushservicejava.crypto;

import java.security.KeyPair;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.AlgorithmParameters;
import java.util.Arrays;

public final class EcKeyUtils {

    private EcKeyUtils() {
    }

    private static final ECParameterSpec P256_PARAMS = p256Params();

    private static ECParameterSpec p256Params() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve P-256 parameters", e);
        }
    }

    public static KeyPair generateP256KeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate P-256 key pair", e);
        }
    }

    public static byte[] encodeUncompressedPublic(PublicKey publicKey) {
        ECPoint point = ((java.security.interfaces.ECPublicKey) publicKey).getW();
        byte[] x = fixedLength(point.getAffineX().toByteArray(), 32);
        byte[] y = fixedLength(point.getAffineY().toByteArray(), 32);
        byte[] encoded = new byte[65];
        encoded[0] = 0x04;
        System.arraycopy(x, 0, encoded, 1, 32);
        System.arraycopy(y, 0, encoded, 33, 32);
        return encoded;
    }

    public static PublicKey decodeUncompressedPublic(byte[] uncompressed) {
        if (uncompressed == null || uncompressed.length != 65 || uncompressed[0] != 0x04) {
            throw new IllegalArgumentException("Public key must be a 65-byte uncompressed P-256 point");
        }
        try {
            java.math.BigInteger x = new java.math.BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
            java.math.BigInteger y = new java.math.BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
            ECPublicKeySpec spec = new ECPublicKeySpec(new ECPoint(x, y), P256_PARAMS);
            return KeyFactory.getInstance("EC").generatePublic(spec);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid P-256 public point", e);
        }
    }

    public static PrivateKey decodePkcs8(byte[] pkcs8) {
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid PKCS#8 EC private key", e);
        }
    }

    public static byte[] encodePkcs8(PrivateKey privateKey) {
        return privateKey.getEncoded();
    }

    private static byte[] fixedLength(byte[] value, int length) {
        if (value.length == length) {
            return value;
        }
        if (value.length == length + 1 && value[0] == 0) {
            return Arrays.copyOfRange(value, 1, value.length);
        }
        if (value.length < length) {
            byte[] padded = new byte[length];
            System.arraycopy(value, 0, padded, length - value.length, value.length);
            return padded;
        }
        throw new IllegalArgumentException("Coordinate too large for field size");
    }
}
