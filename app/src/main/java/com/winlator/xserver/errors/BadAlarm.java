package com.winlator.xserver.errors;

public class BadAlarm extends XRequestError {
    public BadAlarm(int id) {
        super(Byte.MIN_VALUE + 1, id);
    }
}
