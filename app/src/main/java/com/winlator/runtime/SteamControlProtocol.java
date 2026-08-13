package com.winlator.runtime;

/**
 * Versioned control-plane protocol shared by the Android service and the
 * immutable native supervisor. Payloads are length-delimited and never contain
 * shell command strings.
 */
public final class SteamControlProtocol {
    public static final int MAGIC = 0x53445031; // SDP1
    public static final short VERSION = 1;

    public static final short GET_CONTROL_STATUS = 1;
    public static final short PREPARE_SESSION = 2;
    public static final short DESTROY_SESSION = 3;
    public static final short CREATE_UINPUT = 4;
    public static final short DESTROY_UINPUT = 5;
    public static final short GET_SESSION_STATUS = 6;
    public static final short EXEC_RUNTIME_BWRAP = 7;
    public static final short EXEC_NATIVE_STEAM = 8;
    public static final short INSTALL_HOLO_PACKAGES = 9;

    private SteamControlProtocol() {}
}
