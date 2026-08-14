package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * Minimal XTEST support for Steam's Linux input-generator capability probe.
 *
 * XTEST is also useful for controller bindings that are expressed as desktop
 * key/button actions. The Android input path remains authoritative; fake
 * input is translated into the same server-side pointer and keyboard events
 * used by Activity input.
 */
public final class XTestExtension extends Extension {
    private static final int GET_VERSION = 0;
    private static final int COMPARE_CURSOR = 1;
    private static final int FAKE_INPUT = 2;
    private static final int GRAB_CONTROL = 3;

    private static final int MAJOR_VERSION = 2;
    private static final int MINOR_VERSION = 2;

    private static final int KEY_PRESS = 2;
    private static final int KEY_RELEASE = 3;
    private static final int BUTTON_PRESS = 4;
    private static final int BUTTON_RELEASE = 5;
    private static final int MOTION_NOTIFY = 6;

    public XTestExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "XTEST";
    }

    private void getVersion(XClient client, XInputStream inputStream,
                            XOutputStream outputStream) throws IOException {
        int requestedMajor = inputStream.readUnsignedByte();
        inputStream.skip(1);
        int requestedMinor = inputStream.readUnsignedShort();

        int major = Math.min(requestedMajor, MAJOR_VERSION);
        int minor = requestedMajor == MAJOR_VERSION
            ? Math.min(requestedMinor, MINOR_VERSION)
            : requestedMajor < MAJOR_VERSION ? requestedMinor : MINOR_VERSION;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)major);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)minor);
            outputStream.writePad(22);
        }
    }

    private void compareCursor(XClient client, XInputStream inputStream,
                               XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)1); // current cursor matches
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writePad(24);
        }
    }

    private static Pointer.Button pointerButton(int detail) {
        switch (detail) {
            case 1: return Pointer.Button.BUTTON_LEFT;
            case 2: return Pointer.Button.BUTTON_MIDDLE;
            case 3: return Pointer.Button.BUTTON_RIGHT;
            case 4: return Pointer.Button.BUTTON_SCROLL_UP;
            case 5: return Pointer.Button.BUTTON_SCROLL_DOWN;
            case 6: return Pointer.Button.BUTTON_SCROLL_CLICK_LEFT;
            case 7: return Pointer.Button.BUTTON_SCROLL_CLICK_RIGHT;
            default: return null;
        }
    }

    private static XKeycode keycode(int detail) {
        for (XKeycode keycode : XKeycode.values()) {
            if ((keycode.id & 0xff) == detail) return keycode;
        }
        return null;
    }

    private void fakeInput(XClient client, XInputStream inputStream)
        throws IOException, XRequestError {
        int type = inputStream.readUnsignedByte();
        int detail = inputStream.readUnsignedByte();
        inputStream.skip(2); // pad0
        inputStream.skip(4); // time
        inputStream.skip(4); // root
        inputStream.skip(8); // pad1/pad2
        int rootX = inputStream.readShort();
        int rootY = inputStream.readShort();
        inputStream.skip(4); // pad3
        inputStream.skip(2); // pad4
        inputStream.skip(1); // pad5
        inputStream.skip(1); // deviceid
        client.skipRequest();

        switch (type) {
            case KEY_PRESS: {
                XKeycode keycode = keycode(detail);
                if (keycode != null) xServer.injectKeyPress(keycode);
                break;
            }
            case KEY_RELEASE: {
                XKeycode keycode = keycode(detail);
                if (keycode != null) xServer.injectKeyRelease(keycode);
                break;
            }
            case BUTTON_PRESS: {
                Pointer.Button button = pointerButton(detail);
                if (button != null) xServer.injectPointerButtonPress(button);
                break;
            }
            case BUTTON_RELEASE: {
                Pointer.Button button = pointerButton(detail);
                if (button != null) xServer.injectPointerButtonRelease(button);
                break;
            }
            case MOTION_NOTIFY:
                xServer.injectPointerMove(rootX, rootY);
                break;
            default:
                // XTEST permits other core event types. Consume the request
                // and leave unsupported events inert rather than corrupting
                // the connection or claiming a false input transition.
                break;
        }
    }

    private void grabControl(XClient client, XInputStream inputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream,
                              XOutputStream outputStream) throws IOException, XRequestError {
        switch (client.getRequestData()) {
            case GET_VERSION:
                getVersion(client, inputStream, outputStream);
                break;
            case COMPARE_CURSOR:
                compareCursor(client, inputStream, outputStream);
                break;
            case FAKE_INPUT:
                fakeInput(client, inputStream);
                break;
            case GRAB_CONTROL:
                grabControl(client, inputStream);
                break;
            default:
                throw new UnsupportedOperationException("XTEST request is not implemented");
        }
    }
}
