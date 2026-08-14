package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadValue;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * A single-output RandR 1.3 provider for the embedded X server.
 *
 * The Android surface is one stable logical screen. RandR therefore exposes
 * one connected output, one CRTC and one mode derived from ScreenInfo. The
 * resource and query replies are real protocol replies; mutating operations
 * that would require resizing or reconfiguring the Android surface fail
 * explicitly instead of claiming a change that did not happen.
 */
public final class RandRExtension extends Extension {
    public static final int MAJOR_VERSION = 1;
    public static final int MINOR_VERSION = 3;

    private static final int QUERY_VERSION = 0;
    private static final int SET_SCREEN_CONFIG = 2;
    private static final int SELECT_INPUT = 4;
    private static final int GET_SCREEN_INFO = 5;
    private static final int GET_SCREEN_SIZE_RANGE = 6;
    private static final int SET_SCREEN_SIZE = 7;
    private static final int GET_SCREEN_RESOURCES = 8;
    private static final int GET_OUTPUT_INFO = 9;
    private static final int LIST_OUTPUT_PROPERTIES = 10;
    private static final int GET_CRTC_INFO = 20;
    private static final int SET_CRTC_CONFIG = 21;
    private static final int GET_CRTC_GAMMA_SIZE = 22;
    private static final int GET_CRTC_GAMMA = 23;
    private static final int SET_CRTC_GAMMA = 24;
    private static final int GET_CRTC_TRANSFORM = 27;
    private static final int GET_SCREEN_RESOURCES_CURRENT = 25;
    private static final int SET_OUTPUT_PRIMARY = 30;
    private static final int GET_OUTPUT_PRIMARY = 31;

    private static final int OUTPUT_ID = 0x71000001;
    private static final int CRTC_ID = 0x71000002;
    private static final int MODE_ID = 0x71000003;
    private static final String OUTPUT_NAME = "Virtual-1";

    private final SparseArray<Integer> inputSelections = new SparseArray<>();

    public RandRExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "RANDR";
    }

    @Override
    public byte getFirstErrorId() {
        return Byte.MIN_VALUE;
    }

    @Override
    public byte getFirstEventId() {
        return 88;
    }

    private ScreenInfo screen() {
        return xServer.screenInfo;
    }

    private int timestamp() {
        return (int)(System.currentTimeMillis() & 0xffffffffL);
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException {
        int requestedMajor = inputStream.readInt();
        int requestedMinor = inputStream.readInt();
        int major = Math.min(requestedMajor, MAJOR_VERSION);
        int minor = requestedMajor == MAJOR_VERSION
            ? Math.min(requestedMinor, MINOR_VERSION)
            : requestedMajor < MAJOR_VERSION ? requestedMinor : MINOR_VERSION;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(major);
            outputStream.writeInt(minor);
            outputStream.writePad(16);
        }
    }

    private void getScreenInfo(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        requireWindow(inputStream.readInt());
        ScreenInfo screen = screen();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0x0f); // rotations 0, 90, 180, 270
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(3); // one size (8 bytes), one rate (4 padded)
            outputStream.writeInt(xServer.windowManager.rootWindow.id);
            outputStream.writeInt(timestamp());
            outputStream.writeInt(timestamp());
            outputStream.writeShort((short)1); // nSizes
            outputStream.writeShort((short)0); // size ID
            outputStream.writeShort((short)1); // current rotation
            outputStream.writeShort((short)60);
            outputStream.writeShort((short)1); // nrateEnts
            outputStream.writeShort((short)0);
            outputStream.writeShort(screen.width);
            outputStream.writeShort(screen.height);
            outputStream.writeShort(screen.getWidthInMillimeters());
            outputStream.writeShort(screen.getHeightInMillimeters());
            outputStream.writeShort((short)60);
            outputStream.writeShort((short)0);
        }
    }

    private void getScreenSizeRange(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        requireWindow(inputStream.readInt());
        ScreenInfo screen = screen();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort(ScreenInfo.MIN_WIDTH);
            outputStream.writeShort(ScreenInfo.MIN_HEIGHT);
            outputStream.writeShort(screen.width);
            outputStream.writeShort(screen.height);
            outputStream.writePad(16);
        }
    }

    private void getScreenResources(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        requireWindow(inputStream.readInt());
        String modeName = screen().toString();
        int paddedNameLength = (modeName.length() + 3) & ~3;
        int replyLength = (4 + 4 + 32 + paddedNameLength) / 4;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(replyLength);
            outputStream.writeInt(timestamp());
            outputStream.writeInt(timestamp());
            outputStream.writeShort((short)1); // CRTCs
            outputStream.writeShort((short)1); // outputs
            outputStream.writeShort((short)1); // modes
            outputStream.writeShort((short)modeName.length());
            // xRRGetScreenResourcesReply reserves two CARD32 words before
            // the CRTC, output, and mode arrays. Without this padding the
            // client reads the arrays at the wrong offsets and concludes
            // that the connected RandR output has no usable modes.
            outputStream.writePad(8);
            outputStream.writeInt(CRTC_ID);
            outputStream.writeInt(OUTPUT_ID);
            writeModeInfo(outputStream, modeName);
            outputStream.writeString8(modeName);
        }
    }

    private void writeModeInfo(XOutputStream outputStream, String modeName) {
        ScreenInfo screen = screen();
        outputStream.writeInt(MODE_ID);
        outputStream.writeShort(screen.width);
        outputStream.writeShort(screen.height);
        outputStream.writeInt(screen.width * screen.height * 60);
        outputStream.writeShort(screen.width);
        outputStream.writeShort((short)(screen.width + 40));
        outputStream.writeShort((short)(screen.width + 160));
        outputStream.writeShort((short)0);
        outputStream.writeShort(screen.height);
        outputStream.writeShort((short)(screen.height + 5));
        outputStream.writeShort((short)(screen.height + 45));
        outputStream.writeShort((short)modeName.length());
        outputStream.writeInt(0); // mode flags
    }

    private void getOutputInfo(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        requireOutput(inputStream.readInt());
        inputStream.skip(4); // config timestamp
        int nameLength = OUTPUT_NAME.length();
        // The fixed reply structure is 36 bytes, so four bytes of it count
        // toward the length field beyond X11's first 32-byte reply block.
        // Add the one CRTC ID, one mode ID, and the padded output name.
        int paddedNameLength = (nameLength + 3) & ~3;
        int replyLength = (4 + 4 + 4 + paddedNameLength) / 4;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0); // RR_Status_Success
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(replyLength);
            outputStream.writeInt(timestamp());
            outputStream.writeInt(CRTC_ID);
            outputStream.writeInt(screen().getWidthInMillimeters());
            outputStream.writeInt(screen().getHeightInMillimeters());
            // RR_Connection_Connected is 1. Reporting Unknown (0) makes
            // Chromium conclude that the X server has no usable displays.
            outputStream.writeByte((byte)1); // connection
            outputStream.writeByte((byte)0); // subpixel unknown
            outputStream.writeShort((short)1); // nCrtcs
            outputStream.writeShort((short)1); // nModes
            outputStream.writeShort((short)1); // nPreferred
            outputStream.writeShort((short)0); // nClones
            outputStream.writeShort((short)nameLength);
            outputStream.writeInt(CRTC_ID);
            outputStream.writeInt(MODE_ID);
            outputStream.writeString8(OUTPUT_NAME);
        }
    }

    private void listOutputProperties(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        requireOutput(inputStream.readInt());
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writePad(20);
        }
    }

    private void getCrtcInfo(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        requireCrtc(inputStream.readInt());
        inputStream.skip(4); // config timestamp
        ScreenInfo screen = screen();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(2); // output and possible-output lists
            outputStream.writeInt(timestamp());
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writeShort(screen.width);
            outputStream.writeShort(screen.height);
            outputStream.writeInt(MODE_ID);
            outputStream.writeShort((short)1); // rotation
            outputStream.writeShort((short)0x0f);
            outputStream.writeShort((short)1);
            outputStream.writeShort((short)1);
            outputStream.writeInt(OUTPUT_ID);
            outputStream.writeInt(OUTPUT_ID);
        }
    }

    private void getCrtcGammaSize(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        requireCrtc(inputStream.readInt());
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writePad(20);
        }
    }

    private void getCrtcGamma(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        requireCrtc(inputStream.readInt());
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writePad(20);
        }
    }

    private void getCrtcTransform(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        requireCrtc(inputStream.readInt());

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(16); // fixed 96-byte reply
            writeIdentityTransform(outputStream);
            outputStream.writeByte((byte)1); // hasTransforms
            outputStream.writeByte((byte)0);
            outputStream.writeShort((short)0);
            writeIdentityTransform(outputStream);
            outputStream.writeInt(0);
            outputStream.writeShort((short)0); // pending filter bytes
            outputStream.writeShort((short)0); // pending filter params
            outputStream.writeShort((short)0); // current filter bytes
            outputStream.writeShort((short)0); // current filter params
        }
    }

    private void writeIdentityTransform(XOutputStream outputStream) {
        outputStream.writeInt(0x00010000);
        outputStream.writeInt(0);
        outputStream.writeInt(0);
        outputStream.writeInt(0);
        outputStream.writeInt(0x00010000);
        outputStream.writeInt(0);
        outputStream.writeInt(0);
        outputStream.writeInt(0);
        outputStream.writeInt(0x00010000);
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        int windowId = inputStream.readInt();
        requireWindow(windowId);
        inputSelections.put(windowId, inputStream.readInt());
    }

    private void getOutputPrimary(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        requireWindow(inputStream.readInt());
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(OUTPUT_ID);
            outputStream.writePad(20);
        }
    }

    private void setOutputPrimary(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        requireWindow(inputStream.readInt());
        requireOutput(inputStream.readInt());
    }

    private void unsupported(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        throw new BadImplementation();
    }

    private Window requireWindow(int id) throws BadWindow {
        Window window = xServer.windowManager.getWindow(id);
        if (window == null) throw new BadWindow(id);
        return window;
    }

    private int requireOutput(int id) throws BadValue {
        if (id != OUTPUT_ID) throw new BadValue(id);
        return id;
    }

    private int requireCrtc(int id) throws BadValue {
        if (id != CRTC_ID) throw new BadValue(id);
        return id;
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        switch (client.getRequestData()) {
            case QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case GET_SCREEN_INFO:
                getScreenInfo(client, inputStream, outputStream);
                break;
            case GET_SCREEN_SIZE_RANGE:
                getScreenSizeRange(client, inputStream, outputStream);
                break;
            case GET_SCREEN_RESOURCES:
            case GET_SCREEN_RESOURCES_CURRENT:
                getScreenResources(client, inputStream, outputStream);
                break;
            case GET_OUTPUT_INFO:
                getOutputInfo(client, inputStream, outputStream);
                break;
            case LIST_OUTPUT_PROPERTIES:
                listOutputProperties(client, inputStream, outputStream);
                break;
            case GET_CRTC_INFO:
                getCrtcInfo(client, inputStream, outputStream);
                break;
            case GET_CRTC_GAMMA_SIZE:
                getCrtcGammaSize(client, inputStream, outputStream);
                break;
            case GET_CRTC_GAMMA:
                getCrtcGamma(client, inputStream, outputStream);
                break;
            case GET_CRTC_TRANSFORM:
                getCrtcTransform(client, inputStream, outputStream);
                break;
            case SELECT_INPUT:
                selectInput(client, inputStream, outputStream);
                break;
            case GET_OUTPUT_PRIMARY:
                getOutputPrimary(client, inputStream, outputStream);
                break;
            case SET_OUTPUT_PRIMARY:
                setOutputPrimary(client, inputStream, outputStream);
                break;
            case SET_SCREEN_CONFIG:
            case SET_SCREEN_SIZE:
            case SET_CRTC_CONFIG:
            case SET_CRTC_GAMMA:
                unsupported(client, inputStream, outputStream);
                break;
            default:
                throw new BadImplementation();
        }
    }
}
