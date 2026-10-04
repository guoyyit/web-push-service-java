package com.github.guoyyit.webpushservicejava.vapid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.webpushservicejava.config.PushProperties;
import com.github.guoyyit.webpushservicejava.crypto.EcKeyUtils;
import com.github.guoyyit.webpushservicejava.domain.ErrorCode;
import com.github.guoyyit.webpushservicejava.persistence.PersistenceService;
import com.github.guoyyit.webpushservicejava.web.ServiceException;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class VapidService {

    static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final PersistenceService persistenceService;
    private final PushProperties properties;
    private final ObjectMapper objectMapper;

    private volatile KeyPair keyPair;

    public VapidService(PersistenceService persistenceService, PushProperties properties,
                        ObjectMapper objectMapper) {
        this.persistenceService = persistenceService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.keyPair = loadOrCreateKeyPair();
    }

    private synchronized KeyPair loadOrCreateKeyPair() {
        KeyPair stored = persistenceService.loadVapidKey();
        if (stored != null) {
            return stored;
        }
        KeyPair generated = EcKeyUtils.generateP256KeyPair();
        persistenceService.saveVapidKey(generated);
        return generated;
    }

    public String getPublicKeyBase64() {
        return URL_ENCODER.encodeToString(EcKeyUtils.encodeUncompressedPublic(keyPair.getPublic()));
    }

    public String signAuthorization(String audience, String subject) {
        long now = System.currentTimeMillis() / 1000L;
        long expiresAt = now + properties.getVapidTtlSeconds();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("typ", "JWT");
        header.put("alg", "ES256");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", normalizeAudience(audience));
        claims.put("exp", expiresAt);
        claims.put("iat", now);
        if (subject != null && !subject.isBlank()) {
            claims.put("sub", subject);
        }

        String headerPart = encodeJson(header);
        String claimsPart = encodeJson(claims);
        String signingInput = headerPart + "." + claimsPart;
        byte[] derSignature = es256Sign(signingInput.getBytes(StandardCharsets.US_ASCII), keyPair.getPrivate());
        byte[] rawSignature = derToRaw(derSignature);
        String signaturePart = URL_ENCODER.encodeToString(rawSignature);

        return "vapid t=" + signingInput + "." + signaturePart
                + ", k=" + getPublicKeyBase64();
    }

    public Map<String, Object> verify(String authorizationHeader, String expectedAudience) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new ServiceException(ErrorCode.VAPID_MISSING, "Authorization header is missing");
        }
        String token = extractToken(authorizationHeader);
        String[] segments = token.split("\\.");
        if (segments.length != 3) {
            throw new ServiceException(ErrorCode.VAPID_BAD_SIGNATURE, "JWT must contain three segments");
        }

        byte[] headerBytes = decodeSegment(segments[0], ErrorCode.VAPID_BAD_SIGNATURE);
        byte[] claimsBytes = decodeSegment(segments[1], ErrorCode.VAPID_BAD_SIGNATURE);
        byte[] signature = decodeSegment(segments[2], ErrorCode.VAPID_BAD_SIGNATURE);
        if (signature.length != 64) {
            throw new ServiceException(ErrorCode.VAPID_BAD_SIGNATURE, "ES256 signature must be 64 raw bytes");
        }

        Map<String, Object> header = readJson(headerBytes);
        if (!"ES256".equals(header.get("alg"))) {
            throw new ServiceException(ErrorCode.VAPID_BAD_SIGNATURE,
                    "Unsupported JWT alg: " + header.get("alg"));
        }

        byte[] signingInput = (segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII);
        if (!es256Verify(signingInput, rawToDer(signature), keyPair.getPublic())) {
            throw new ServiceException(ErrorCode.VAPID_BAD_SIGNATURE, "VAPID JWT signature verification failed");
        }

        Map<String, Object> claims = readJson(claimsBytes);
        long now = System.currentTimeMillis() / 1000L;
        Object exp = claims.get("exp");
        if (!(exp instanceof Number)) {
            throw new ServiceException(ErrorCode.VAPID_EXPIRED, "JWT exp claim is missing");
        }
        if (((Number) exp).longValue() <= now) {
            throw new ServiceException(ErrorCode.VAPID_EXPIRED, "VAPID JWT has expired");
        }

        String audience = Objects.toString(claims.get("aud"), "");
        if (!normalizeAudience(audience).equals(normalizeAudience(expectedAudience))) {
            throw new ServiceException(ErrorCode.VAPID_AUDIENCE_MISMATCH,
                    "JWT audience '" + audience + "' does not match '" + expectedAudience + "'");
        }
        return claims;
    }

    public KeyPair getKeyPair() {
        return keyPair;
    }

    public synchronized void rotate() {
        KeyPair rotated = EcKeyUtils.generateP256KeyPair();
        persistenceService.saveVapidKey(rotated);
        this.keyPair = rotated;
    }

    private String extractToken(String authorizationHeader) {
        String header = authorizationHeader.trim();
        String lower = header.toLowerCase();
        if (lower.startsWith("vapid ")) {
            return parseVapidScheme(header.substring("vapid".length()).trim());
        }
        if (lower.startsWith("bearer ")) {
            return header.substring("bearer".length()).trim();
        }
        if (header.chars().filter(c -> c == '.').count() == 2) {
            return header;
        }
        throw new ServiceException(ErrorCode.VAPID_MISSING, "Authorization header is not a VAPID JWT");
    }

    private String parseVapidScheme(String params) {
        String token = null;
        for (String part : params.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("t=")) {
                token = trimmed.substring(2);
            }
        }
        if (token == null) {
            throw new ServiceException(ErrorCode.VAPID_BAD_SIGNATURE, "VAPID scheme is missing t= token");
        }
        return token;
    }

    private String normalizeAudience(String audience) {
        if (audience == null || audience.isBlank()) {
            throw new ServiceException(ErrorCode.VAPID_AUDIENCE_MISMATCH, "Audience is missing");
        }
        String normalized = audience.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode JWT JSON", e);
        }
    }

    private Map<String, Object> readJson(byte[] bytes) {
        try {
            return objectMapper.readValue(bytes,
                    objectMapper.getTypeFactory()
                            .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.VAPID_BAD_SIGNATURE, "JWT segment is not valid JSON", e);
        }
    }

    private byte[] decodeSegment(String segment, ErrorCode errorCode) {
        try {
            return URL_DECODER.decode(segment);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(errorCode, "JWT segment is not valid base64url", e);
        }
    }

    private byte[] es256Sign(byte[] signingInput, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(privateKey);
            signature.update(signingInput);
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("ES256 signing failed", e);
        }
    }

    private boolean es256Verify(byte[] signingInput, byte[] derSignature, PublicKey publicKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(publicKey);
            signature.update(signingInput);
            return signature.verify(derSignature);
        } catch (java.security.SignatureException e) {
            return false;
        } catch (Exception e) {
            throw new ServiceException(ErrorCode.VAPID_BAD_SIGNATURE, "ES256 verification failed", e);
        }
    }

    private byte[] derToRaw(byte[] der) {
        if (der.length < 6 || der[0] != 0x30) {
            throw new ServiceException(ErrorCode.VAPID_BAD_SIGNATURE, "Invalid ECDSA DER signature");
        }
        int offset = 1;
        if ((der[offset] & 0x80) != 0) {
            offset += 1 + (der[offset] & 0x7F);
        } else {
            offset += 1;
        }
        if (der[offset] != 0x02) {
            throw new ServiceException(ErrorCode.VAPID_BAD_SIGNATURE, "Invalid ECDSA DER signature");
        }
        int rLength = der[offset + 1] & 0xFF;
        int rStart = offset + 2;
        int sTag = rStart + rLength;
        if (der[sTag] != 0x02) {
            throw new ServiceException(ErrorCode.VAPID_BAD_SIGNATURE, "Invalid ECDSA DER signature");
        }
        int sLength = der[sTag + 1] & 0xFF;
        int sStart = sTag + 2;
        byte[] r = stripLeadingZeros(Arrays.copyOfRange(der, rStart, sTag));
        byte[] s = stripLeadingZeros(Arrays.copyOfRange(der, sStart, sStart + sLength));
        if (r.length > 32 || s.length > 32) {
            throw new ServiceException(ErrorCode.VAPID_BAD_SIGNATURE, "ECDSA coordinate exceeds 32 bytes");
        }
        byte[] raw = new byte[64];
        System.arraycopy(r, 0, raw, 32 - r.length, r.length);
        System.arraycopy(s, 0, raw, 64 - s.length, s.length);
        return raw;
    }

    private byte[] rawToDer(byte[] raw) {
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(raw, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(raw, 32, 64));
        byte[] rBytes = r.toByteArray();
        byte[] sBytes = s.toByteArray();
        byte[] sequence = new byte[rBytes.length + sBytes.length + 4];
        int position = 0;
        sequence[position++] = 0x02;
        sequence[position++] = (byte) rBytes.length;
        System.arraycopy(rBytes, 0, sequence, position, rBytes.length);
        position += rBytes.length;
        sequence[position++] = 0x02;
        sequence[position++] = (byte) sBytes.length;
        System.arraycopy(sBytes, 0, sequence, position, sBytes.length);

        byte[] der = new byte[sequence.length + 2];
        der[0] = 0x30;
        der[1] = (byte) sequence.length;
        System.arraycopy(sequence, 0, der, 2, sequence.length);
        return der;
    }

    private byte[] stripLeadingZeros(byte[] value) {
        int start = 0;
        while (start < value.length - 1 && value[start] == 0) {
            start++;
        }
        return start == 0 ? value : Arrays.copyOfRange(value, start, value.length);
    }
}
