package com.winlator.xserver;

/** Explicit lifecycle-neutral X server entry point for SteamDroid sessions. */
public final class XServerCore extends XServer {
    private static final XServerHost EMBEDDED_HOST = new XServerHost() {
        @Override
        public boolean autoMapTopLevelWindows() {
            return true;
        }
    };

    public XServerCore(ScreenInfo screenInfo) {
        super(EMBEDDED_HOST, screenInfo);
    }

    public XServerCore(XServerHost host, ScreenInfo screenInfo) {
        super(host, screenInfo);
    }
}
