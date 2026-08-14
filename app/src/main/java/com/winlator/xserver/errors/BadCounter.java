package com.winlator.xserver.errors;

public class BadCounter extends XRequestError {
    public BadCounter(int id) {
        super(Byte.MIN_VALUE, id);
    }
}
