package com.drawback.auth.sms;

/** Delivery channel for one-time codes. Plug a real provider (Twilio, SNS, MSG91...) in by providing a bean of this type. */
public interface SmsSender {

    /** @param phoneDigits international number, digits only (no '+', spaces or dashes) */
    void send(String phoneDigits, String message);
}
