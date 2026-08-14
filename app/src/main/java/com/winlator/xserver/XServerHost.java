package com.winlator.xserver;

import com.winlator.inputcontrols.ControlsProfile;

/** Activity/service adapter for optional desktop integration around XServer. */
public interface XServerHost {
    XServerHost NO_OP = new XServerHost() {};

    default void debugPrint(String line) {}
    default void bringToFront(String processName, long handle) {}
    default void relativeMouseEvent(int flags, int dx, int dy, int wheelDelta) {}
    default void sendGamepadState(ControlsProfile profile) {}
    default void midiShortMessage(byte status, byte data1, byte data2, byte data3) {}
    default String nativeLibraryDir() { return null; }

    /**
     * An embedded display has no external window manager to map root-level
     * client windows. Legacy Winlator sessions keep the normal X11 contract.
     */
    default boolean autoMapTopLevelWindows() { return false; }
}
