package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * The XI2 control plane used by native desktop clients.
 *
 * This server has one Android-backed pointer and keyboard. XI2 events are
 * still delivered through the existing core input path; these requests make
 * the device hierarchy and selection contract visible to clients that use
 * XI2 for discovery and event registration.
 */
public final class XInputExtension extends Extension {
    // The legacy XInput ABI is still queried by SDL before it negotiates XI2.
    // Keep this small compatibility reply alongside the XI2 control plane.
    private static final int GET_EXTENSION_VERSION = 1;
    private static final int LIST_INPUT_DEVICES = 2;
    private static final int GET_CLIENT_POINTER = 45;
    private static final int XI_SELECT_EVENTS = 46;
    private static final int XI_QUERY_VERSION = 47;
    private static final int XI_QUERY_DEVICE = 48;
    private static final int XI_LIST_PROPERTIES = 56;
    private static final int XI_GET_PROPERTY = 59;
    private static final int XI_GET_SELECTED_EVENTS = 60;

    private static final int XI_ALL_DEVICES = 0;
    private static final int XI_ALL_MASTER_DEVICES = 1;

    private static final int XI_MAJOR_VERSION = 2;
    private static final int XI_MINOR_VERSION = 0;

    private static final int MASTER_POINTER = 1;
    private static final int MASTER_KEYBOARD = 2;

    public XInputExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "XInputExtension";
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    private void getExtensionVersion(XClient client, XInputStream inputStream,
                                     XOutputStream outputStream) throws IOException {
        // The request contains the legacy extension name. Consume the complete
        // request, including protocol padding, before writing the reply.
        int nameLength = inputStream.readUnsignedShort();
        inputStream.skip(2);
        if (nameLength > 0) inputStream.readString8(nameLength);
        inputStream.skip(client.getRemainingRequestLength());

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)2);
            outputStream.writeShort((short)0);
            outputStream.writeByte((byte)1); // present
            outputStream.writePad(19);
        }
    }

    private void getClientPointer(XClient client, XInputStream inputStream,
                                  XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeByte((byte)1); // present
            outputStream.writeByte((byte)0);
            outputStream.writeShort((short)MASTER_KEYBOARD);
            outputStream.writePad(20);
        }
    }

    private void listInputDevices(XClient client, XInputStream inputStream,
                                  XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());

        // The legacy device ABI is only used as a capability probe by the
        // current Steam/SDL path. XI2 is the authoritative device interface;
        // report a valid empty legacy list rather than returning an extension
        // error that makes clients discard the whole input connection.
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeByte((byte)0); // ndevices
            outputStream.writePad(23);
        }
    }

    private void emptyPropertyReply(XClient client, XOutputStream outputStream)
        throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0); // format
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0); // no property data
            outputStream.writeInt(0); // type
            outputStream.writeInt(0); // bytes_after
            outputStream.writeInt(0); // num_items
            outputStream.writeByte((byte)0);
            outputStream.writePad(11);
        }
    }

    private void listDeviceProperties(XClient client, XInputStream inputStream,
                                      XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)0); // no properties
            outputStream.writeShort((short)0);
            outputStream.writePad(20);
        }
    }

    private void getDeviceProperty(XClient client, XInputStream inputStream,
                                   XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());
        emptyPropertyReply(client, outputStream);
    }

    private void getSelectedEvents(XClient client, XInputStream inputStream,
                                   XOutputStream outputStream) throws IOException,
                                   XRequestError {
        int windowId = inputStream.readInt();
        if (xServer.windowManager.getWindow(windowId) == null) throw new BadWindow(windowId);
        inputStream.skip(client.getRemainingRequestLength());

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)0); // no selected masks
            outputStream.writeShort((short)0);
            outputStream.writePad(20);
        }
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException {
        int requestedMajor = inputStream.readUnsignedShort();
        int requestedMinor = inputStream.readUnsignedShort();

        // XI2 clients negotiate the highest version common to both peers.
        // Keep the advertised feature set at the level implemented here.
        int major = Math.min(requestedMajor, XI_MAJOR_VERSION);
        int minor = requestedMajor == XI_MAJOR_VERSION
            ? Math.min(requestedMinor, XI_MINOR_VERSION)
            : requestedMajor < XI_MAJOR_VERSION ? requestedMinor : XI_MINOR_VERSION;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)major);
            outputStream.writeShort((short)minor);
            outputStream.writePad(20);
        }
    }

    private void writeDevice(XOutputStream outputStream, int deviceId, int use, int attachment,
                             String name) {
        outputStream.writeShort((short)deviceId);
        outputStream.writeShort((short)use);
        outputStream.writeShort((short)attachment);
        outputStream.writeShort((short)0); // no XI device classes yet
        outputStream.writeShort((short)name.length());
        outputStream.writeByte((byte)1); // enabled
        outputStream.writeByte((byte)0);
        outputStream.writeString8(name);
    }

    private void queryDevice(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        int requestedDevice = inputStream.readUnsignedShort();
        inputStream.skip(2);

        if (requestedDevice != XI_ALL_DEVICES && requestedDevice != XI_ALL_MASTER_DEVICES &&
            requestedDevice != MASTER_POINTER && requestedDevice != MASTER_KEYBOARD) {
            // XIQueryDevice reports an invalid device through BadDevice. The
            // generic XRequestError type used by this server does not expose
            // that legacy error yet, so return an empty, well-formed list for
            // unknown IDs rather than corrupting the reply stream.
            writeEmptyDeviceReply(client, outputStream);
            return;
        }

        boolean includePointer = requestedDevice == XI_ALL_DEVICES ||
            requestedDevice == XI_ALL_MASTER_DEVICES || requestedDevice == MASTER_POINTER;
        boolean includeKeyboard = requestedDevice == XI_ALL_DEVICES ||
            requestedDevice == XI_ALL_MASTER_DEVICES || requestedDevice == MASTER_KEYBOARD;
        int count = (includePointer ? 1 : 0) + (includeKeyboard ? 1 : 0);
        int pointerNameBytes = "Virtual core pointer".length();
        int keyboardNameBytes = "Virtual core keyboard".length();
        int payloadBytes = (includePointer ? 12 + ((pointerNameBytes + 3) & ~3) : 0) +
            (includeKeyboard ? 12 + ((keyboardNameBytes + 3) & ~3) : 0);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(payloadBytes / 4);
            outputStream.writeShort((short)count);
            outputStream.writeShort((short)0);
            outputStream.writePad(20);
            if (includePointer) writeDevice(outputStream, MASTER_POINTER, 1, MASTER_KEYBOARD,
                "Virtual core pointer");
            if (includeKeyboard) writeDevice(outputStream, MASTER_KEYBOARD, 2, MASTER_POINTER,
                "Virtual core keyboard");
        }
    }

    private void writeEmptyDeviceReply(XClient client, XOutputStream outputStream) throws IOException {
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

    private void selectEvents(XClient client, XInputStream inputStream) throws XRequestError {
        int windowId = inputStream.readInt();
        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        int maskCount = inputStream.readUnsignedShort();
        inputStream.skip(2);
        for (int i = 0; i < maskCount; i++) {
            inputStream.skip(2); // deviceid
            int maskLength = inputStream.readUnsignedShort();
            inputStream.skip(maskLength * 4);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        switch (client.getRequestData()) {
            case GET_EXTENSION_VERSION:
                getExtensionVersion(client, inputStream, outputStream);
                break;
            case LIST_INPUT_DEVICES:
                listInputDevices(client, inputStream, outputStream);
                break;
            case GET_CLIENT_POINTER:
                getClientPointer(client, inputStream, outputStream);
                break;
            case XI_QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case XI_QUERY_DEVICE:
                queryDevice(client, inputStream, outputStream);
                break;
            case XI_LIST_PROPERTIES:
                listDeviceProperties(client, inputStream, outputStream);
                break;
            case XI_GET_PROPERTY:
                getDeviceProperty(client, inputStream, outputStream);
                break;
            case XI_GET_SELECTED_EVENTS:
                getSelectedEvents(client, inputStream, outputStream);
                break;
            case XI_SELECT_EVENTS:
                selectEvents(client, inputStream);
                break;
            default:
                throw new com.winlator.xserver.errors.BadImplementation();
        }
    }
}
