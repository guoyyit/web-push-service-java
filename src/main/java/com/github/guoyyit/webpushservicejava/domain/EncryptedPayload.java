package com.github.guoyyit.webpushservicejava.domain;

public class EncryptedPayload {
    private String salt;
    private String serverPublicKey;
    private String recipientKeyId;
    private String ciphertext;
    private String associatedData;

    public EncryptedPayload() {
    }

    public EncryptedPayload(String salt, String serverPublicKey, String recipientKeyId,
                            String ciphertext, String associatedData) {
        this.salt = salt;
        this.serverPublicKey = serverPublicKey;
        this.recipientKeyId = recipientKeyId;
        this.ciphertext = ciphertext;
        this.associatedData = associatedData;
    }

    public String getSalt() { return salt; }
    public void setSalt(String salt) { this.salt = salt; }

    public String getServerPublicKey() { return serverPublicKey; }
    public void setServerPublicKey(String serverPublicKey) { this.serverPublicKey = serverPublicKey; }

    public String getRecipientKeyId() { return recipientKeyId; }
    public void setRecipientKeyId(String recipientKeyId) { this.recipientKeyId = recipientKeyId; }

    public String getCiphertext() { return ciphertext; }
    public void setCiphertext(String ciphertext) { this.ciphertext = ciphertext; }

    public String getAssociatedData() { return associatedData; }
    public void setAssociatedData(String associatedData) { this.associatedData = associatedData; }
}
