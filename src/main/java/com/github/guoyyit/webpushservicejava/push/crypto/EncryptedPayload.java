package com.github.guoyyit.webpushservicejava.push.crypto;

public class EncryptedPayload {
    private int version;
    private String ephemeralPublicKey;
    private String salt;
    private String keyCheck;
    private long expiresAtEpochSeconds;
    private String ciphertext;

    public EncryptedPayload() {
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getEphemeralPublicKey() {
        return ephemeralPublicKey;
    }

    public void setEphemeralPublicKey(String ephemeralPublicKey) {
        this.ephemeralPublicKey = ephemeralPublicKey;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    public String getKeyCheck() {
        return keyCheck;
    }

    public void setKeyCheck(String keyCheck) {
        this.keyCheck = keyCheck;
    }

    public long getExpiresAtEpochSeconds() {
        return expiresAtEpochSeconds;
    }

    public void setExpiresAtEpochSeconds(long expiresAtEpochSeconds) {
        this.expiresAtEpochSeconds = expiresAtEpochSeconds;
    }

    public String getCiphertext() {
        return ciphertext;
    }

    public void setCiphertext(String ciphertext) {
        this.ciphertext = ciphertext;
    }
}
