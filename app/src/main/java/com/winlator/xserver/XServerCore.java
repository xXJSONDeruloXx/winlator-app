package com.winlator.xserver;

/** Explicit lifecycle-neutral X server entry point for SteamDroid sessions. */
public final class XServerCore extends XServer {
    public XServerCore(ScreenInfo screenInfo) {
        super(XServerHost.NO_OP, screenInfo);
    }

    public XServerCore(XServerHost host, ScreenInfo screenInfo) {
        super(host, screenInfo);
    }
}
