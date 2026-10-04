package com.github.guoyyit.webpushservicejava.web.dto;

public class SendResponse {
    private String deliveryId;
    private String coalescedDeliveryId;

    public SendResponse() {
    }

    public SendResponse(String deliveryId, String coalescedDeliveryId) {
        this.deliveryId = deliveryId;
        this.coalescedDeliveryId = coalescedDeliveryId;
    }

    public String getDeliveryId() { return deliveryId; }
    public void setDeliveryId(String deliveryId) { this.deliveryId = deliveryId; }

    public String getCoalescedDeliveryId() { return coalescedDeliveryId; }
    public void setCoalescedDeliveryId(String coalescedDeliveryId) { this.coalescedDeliveryId = coalescedDeliveryId; }
}
