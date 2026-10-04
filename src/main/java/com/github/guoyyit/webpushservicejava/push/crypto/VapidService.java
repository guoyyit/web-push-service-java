package com.github.guoyyit.webpushservicejava.push.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.guoyyit.webpushservicejava.push.exception.ErrorCode;
import com.github.guoyyit.webpushservicejava.push.exception.PushException;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;

public class VapidService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long CLOCK_SKEW_LEEWAY_SECONDS = 60;
    private static final int ES256_SIGNATURE_LENGTH = 64;

    public VapidService() {
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot generate P-256 key pair", e);
        }
    }

    public String sign(String audience, Instant expiresAt, String subject, PrivateKey privateKey) {
        ObjectNode header = MAPPER.createObjectNode();
        header.put("typ", "JWT");
        header.put("alg", "ES256");
        ObjectNode claims = MAPPER.createObjectNode();
        claims.put("aud", audience);
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("sub", subject);
        String signingInput = CryptoUtils.base64UrlEncode(encodeJson(header)) + "."
                + CryptoUtils.base64UrlEncode(encodeJson(claims));
        byte[] derSignature = sign(signingInput.getBytes(StandardCharsets.UTF_8), privateKey);
        return signingInput + "." + CryptoUtils.base64UrlEncode(derToRaw(derSignature));
    }

    public void verify(String token, String expectedAudience, PublicKey publicKey, Instant now) {
        if (token == null || token.isBlank()) {
            throw new PushException(ErrorCode.VAPID_MISSING, "VAPID token is missing");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new PushException(ErrorCode.VAPID_MALFORMED, "VAPID token must have three segments");
        }
        JsonNode header = parseJsonPart(parts[0], "header");
        JsonNode claims = parseJsonPart(parts[1], "claims");
        String alg = header.path("alg").asText(null);
        if (!"ES256".equals(alg)) {
            throw new PushException(ErrorCode.VAPID_MALFORMED, "unsupported alg: " + alg);
        }
        byte[] signature;
        try {
            signature = CryptoUtils.base64UrlDecode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new PushException(ErrorCode.VAPID_MALFORMED, "signature is not valid base64url", e);
        }
        if (signature.length != ES256_SIGNATURE_LENGTH) {
            throw new PushException(ErrorCode.VAPID_SIGNATURE_INVALID,
                    "ES256 signature must be 64 bytes, got " + signature.length);
        }
        String signingInput = parts[0] + "." + parts[1];
        if (!verifySignature(signingInput.getBytes(StandardCharsets.UTF_8), rawToDer(signature), publicKey)) {
            throw new PushException(ErrorCode.VAPID_SIGNATURE_INVALID, "VAPID signature verification failed");
        }
        JsonNode expNode = claims.path("exp");
        if (!expNode.isNumber()) {
            throw new PushException(ErrorCode.VAPID_MALFORMED, "VAPID token is missing numeric exp claim");
        }
        long expiresAt = expNode.longValue();
        if (now.getEpochSecond() > expiresAt + CLOCK_SKEW_LEEWAY_SECONDS) {
            throw new PushException(ErrorCode.VAPID_EXPIRED,
                    "VAPID token expired at " + Instant.ofEpochSecond(expiresAt));
        }
        String audience = claims.path("aud").asText(null);
        if (audience == null) {
            throw new PushException(ErrorCode.VAPID_MALFORMED, "VAPID token is missing aud claim");
        }
        if (!expectedAudience.equals(audience)) {
            throw new PushException(ErrorCode.VAPID_AUDIENCE_MISMATCH,
                    "VAPID audience '" + audience + "' does not match expected '" + expectedAudience + "'");
        }
    }

    private static byte[] encodeJson(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException("cannot encode JWT part", e);
        }
    }

    private static JsonNode parseJsonPart(String part, String name) {
        try {
            return MAPPER.readTree(CryptoUtils.base64UrlDecode(part));
        } catch (Exception e) {
            throw new PushException(ErrorCode.VAPID_MALFORMED, "VAPID " + name + " is not valid JSON", e);
        }
    }

    private static byte[] sign(byte[] data, PrivateKey privateKey) {
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(data);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign VAPID token", e);
        }
    }

    private static boolean verifySignature(byte[] data, byte[] derSignature, PublicKey publicKey) {
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(data);
            return verifier.verify(derSignature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    static byte[] derToRaw(byte[] der) {
        if (der.length < 8 || der[0] != 0x30) {
            throw new IllegalStateException("unexpected DER signature format");
        }
        int offset = 3;
        int rLength = der[offset];
        byte[] r = Arrays.copyOfRange(der, offset + 1, offset + 1 + rLength);
        offset += rLength + 2;
        int sLength = der[offset];
        byte[] s = Arrays.copyOfRange(der, offset + 1, offset + 1 + sLength);
        byte[] raw = new byte[ES256_SIGNATURE_LENGTH];
        byte[] rFixed = CryptoUtils.toFixedLength(new BigInteger(1, r), 32);
        byte[] sFixed = CryptoUtils.toFixedLength(new BigInteger(1, s), 32);
        System.arraycopy(rFixed, 0, raw, 0, 32);
        System.arraycopy(sFixed, 0, raw, 32, 32);
        return raw;
    }

    static byte[] rawToDer(byte[] raw) {
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(raw, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(raw, 32, 64));
        byte[] rBytes = r.toByteArray();
        byte[] sBytes = s.toByteArray();
        int totalLength = 2 + rBytes.length + 2 + sBytes.length;
        byte[] der = new byte[2 + totalLength];
        der[0] = 0x30;
        der[1] = (byte) totalLength;
        der[2] = 0x02;
        der[3] = (byte) rBytes.length;
        System.arraycopy(rBytes, 0, der, 4, rBytes.length);
        int sOffset = 4 + rBytes.length;
        der[sOffset] = 0x02;
        der[sOffset + 1] = (byte) sBytes.length;
        System.arraycopy(sBytes, 0, der, sOffset + 2, sBytes.length);
        return der;
    }
}
