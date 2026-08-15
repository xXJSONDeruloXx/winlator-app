package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Visual;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Cursor;
import com.winlator.xserver.Picture;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadDrawable;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadValue;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * The format-discovery portion of the XRender protocol used by Chromium and
 * ANGLE while selecting an ARGB X11 visual.
 *
 * The embedded renderer composites X windows itself, so this extension keeps
 * the picture-resource lifecycle needed by browser clients but does not claim
 * to implement XRender pixel compositing. Unsupported operations fail through
 * the normal request path instead of being acknowledged without creating the
 * requested server state.
 */
public final class RenderExtension extends Extension {
    public static final int MAJOR_VERSION = 0;
    public static final int MINOR_VERSION = 11;

    private static final int QUERY_VERSION = 0;
    private static final int QUERY_PICT_FORMATS = 1;
    private static final int CREATE_PICTURE = 4;
    private static final int CHANGE_PICTURE = 5;
    private static final int SET_PICTURE_CLIP_RECTANGLES = 6;
    private static final int FREE_PICTURE = 7;
    private static final int CREATE_CURSOR = 27;

    // Picture format IDs are server-owned protocol objects, not client
    // resource IDs. Keep them stable for the lifetime of this X server.
    private static final int FORMAT_RGB24 = 0x10000001;
    private static final int FORMAT_ARGB32 = 0x10000002;
    private static final int FORMAT_A8 = 0x10000003;
    private static final int FORMAT_A1 = 0x10000004;

    private static final int PICT_FORMAT_BYTES = 28;
    private static final int PICT_SCREEN_BYTES = 8;
    private static final int PICT_DEPTH_BYTES = 8;
    private static final int PICT_VISUAL_BYTES = 8;

    private static final int PICTURE_ATTRIBUTE_BITS = 13;

    public RenderExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "RENDER";
    }

    private void queryVersion(XClient client, XInputStream inputStream,
                              XOutputStream outputStream) throws IOException {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(MAJOR_VERSION);
            outputStream.writeInt(MINOR_VERSION);
            outputStream.writePad(16);
        }
    }

    private void writeDirectFormat(XOutputStream outputStream, int id, int depth,
                                   int red, int redMask, int green, int greenMask,
                                   int blue, int blueMask, int alpha, int alphaMask) {
        outputStream.writeInt(id);
        outputStream.writeByte((byte)1); // PictTypeDirect
        outputStream.writeByte((byte)depth);
        outputStream.writeShort((short)0);
        outputStream.writeShort((short)red);
        outputStream.writeShort((short)redMask);
        outputStream.writeShort((short)green);
        outputStream.writeShort((short)greenMask);
        outputStream.writeShort((short)blue);
        outputStream.writeShort((short)blueMask);
        outputStream.writeShort((short)alpha);
        outputStream.writeShort((short)alphaMask);
        outputStream.writeInt(0); // no colormap for direct formats
    }

    private void queryPictFormats(XClient client, XInputStream inputStream,
                                  XOutputStream outputStream) throws IOException {
        inputStream.skip(client.getRemainingRequestLength());

        Visual visual = client.xServer.pixmapManager.visual;
        int numFormats = 4;
        int numScreens = 1;
        int numDepths = 2;
        int numVisuals = 1;
        int numSubpixel = 0;
        int replyBytes = numFormats * PICT_FORMAT_BYTES +
            PICT_SCREEN_BYTES +
            numDepths * PICT_DEPTH_BYTES +
            numVisuals * PICT_VISUAL_BYTES;
        // The six CARD32 fields below are part of the fixed 32-byte X11
        // reply header. reply.length therefore counts only the variable
        // format/screen/depth/visual payload that follows it.
        int replyLength = replyBytes / 4;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(replyLength);
            outputStream.writeInt(numFormats);
            outputStream.writeInt(numScreens);
            outputStream.writeInt(numDepths);
            outputStream.writeInt(numVisuals);
            outputStream.writeInt(numSubpixel);
            outputStream.writeInt(0);

            // Standard direct formats used by XRenderFindStandardFormat and
            // XRenderFindVisualFormat.
            writeDirectFormat(outputStream, FORMAT_RGB24, 24,
                16, 0xff, 8, 0xff, 0, 0xff, 0, 0);
            writeDirectFormat(outputStream, FORMAT_ARGB32, 32,
                16, 0xff, 8, 0xff, 0, 0xff, 24, 0xff);
            writeDirectFormat(outputStream, FORMAT_A8, 8,
                0, 0, 0, 0, 0, 0, 0, 0xff);
            writeDirectFormat(outputStream, FORMAT_A1, 1,
                0, 0, 0, 0, 0, 0, 0, 0x01);

            // One screen with a depth-24 entry and a depth-32 entry that maps
            // the server's ARGB visual to FORMAT_ARGB32.
            outputStream.writeInt(numDepths);
            outputStream.writeInt(0); // fallback format

            outputStream.writeByte((byte)24);
            outputStream.writeByte((byte)0);
            outputStream.writeShort((short)0);
            outputStream.writeInt(0);

            outputStream.writeByte((byte)32);
            outputStream.writeByte((byte)0);
            outputStream.writeShort((short)1);
            outputStream.writeInt(0);
            outputStream.writeInt(visual.id);
            outputStream.writeInt(FORMAT_ARGB32);
        }
    }

    private static void skipPictureAttributes(XClient client, XInputStream inputStream,
                                              int mask) throws IOException, XRequestError {
        if ((mask >>> PICTURE_ATTRIBUTE_BITS) != 0) throw new BadValue(mask);
        for (int bit = 0; bit < PICTURE_ATTRIBUTE_BITS; bit++) {
            if ((mask & (1 << bit)) != 0) inputStream.skip(4);
        }
        client.skipRequest();
    }

    private static boolean isKnownFormat(int format) {
        return format == FORMAT_RGB24 || format == FORMAT_ARGB32 ||
            format == FORMAT_A8 || format == FORMAT_A1;
    }

    private void createPicture(XClient client, XInputStream inputStream)
        throws IOException, XRequestError {
        int pictureId = inputStream.readInt();
        int drawableId = inputStream.readInt();
        int format = inputStream.readInt();
        int mask = inputStream.readInt();

        if (!client.isValidResourceId(pictureId) ||
            xServer.pictureManager.getPicture(pictureId) != null) {
            throw new BadIdChoice(pictureId);
        }
        Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        if (!isKnownFormat(format)) throw new BadValue(format);

        skipPictureAttributes(client, inputStream, mask);
        try (XLock lock = xServer.lock(XServer.Lockable.PICTURE_MANAGER)) {
            Picture picture = xServer.pictureManager.createPicture(pictureId, drawable, format);
            if (picture == null) throw new BadIdChoice(pictureId);
            picture.setAttributesMask(mask);
            client.registerAsOwnerOfResource(picture);
        }
    }

    private void changePicture(XClient client, XInputStream inputStream)
        throws IOException, XRequestError {
        int pictureId = inputStream.readInt();
        int mask = inputStream.readInt();
        Picture picture = xServer.pictureManager.getPicture(pictureId);
        if (picture == null) throw new BadValue(pictureId);

        skipPictureAttributes(client, inputStream, mask);
        picture.setAttributesMask(mask);
    }

    private void setPictureClipRectangles(XClient client, XInputStream inputStream)
        throws IOException, XRequestError {
        int pictureId = inputStream.readInt();
        short xOrigin = inputStream.readShort();
        short yOrigin = inputStream.readShort();
        Picture picture = xServer.pictureManager.getPicture(pictureId);
        if (picture == null) throw new BadValue(pictureId);

        int remaining = client.getRemainingRequestLength();
        if (remaining < 0 || (remaining & 7) != 0) throw new BadValue(remaining);
        int rectangleCount = remaining / 8;
        inputStream.skip(remaining);
        picture.setClip(xOrigin, yOrigin, rectangleCount);
    }

    private void freePicture(XClient client, XInputStream inputStream)
        throws IOException, XRequestError {
        int pictureId = inputStream.readInt();
        Picture picture = xServer.pictureManager.getPicture(pictureId);
        if (picture == null) throw new BadValue(pictureId);
        client.skipRequest();
        xServer.pictureManager.freePicture(pictureId);
    }

    private void createCursor(XClient client, XInputStream inputStream)
        throws IOException, XRequestError {
        int cursorId = inputStream.readInt();
        int pictureId = inputStream.readInt();
        short x = inputStream.readShort();
        short y = inputStream.readShort();
        Picture picture = xServer.pictureManager.getPicture(pictureId);
        if (!client.isValidResourceId(cursorId)) throw new BadIdChoice(cursorId);
        if (picture == null) throw new BadValue(pictureId);

        Cursor cursor = xServer.cursorManager.createCursorFromPicture(cursorId, x, y, picture);
        if (cursor == null) throw new BadIdChoice(cursorId);
        client.registerAsOwnerOfResource(cursor);
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream,
                              XOutputStream outputStream) throws IOException, XRequestError {
        switch (client.getRequestData()) {
            case QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case QUERY_PICT_FORMATS:
                queryPictFormats(client, inputStream, outputStream);
                break;
            case CREATE_PICTURE:
                createPicture(client, inputStream);
                break;
            case CHANGE_PICTURE:
                changePicture(client, inputStream);
                break;
            case SET_PICTURE_CLIP_RECTANGLES:
                setPictureClipRectangles(client, inputStream);
                break;
            case FREE_PICTURE:
                freePicture(client, inputStream);
                break;
            case CREATE_CURSOR:
                createCursor(client, inputStream);
                break;
            default:
                throw new UnsupportedOperationException("RENDER picture operation is not implemented");
        }
    }
}
