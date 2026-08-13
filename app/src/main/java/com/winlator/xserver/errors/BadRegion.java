package com.winlator.xserver.errors;

/** XFixes BadRegion (extension error number zero). */
public class BadRegion extends XRequestError {
    public BadRegion(int id) {
        super(Byte.MIN_VALUE, id);
    }
}
