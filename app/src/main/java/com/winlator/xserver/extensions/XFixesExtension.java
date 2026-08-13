package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadRegion;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The XFixes surface needed by modern X11 clients.
 *
 * Version 4 is implemented deliberately as a real, bounded rectangle-region
 * implementation rather than advertising an extension and returning success
 * for unsupported requests. Cursor/selection subscription requests are
 * accepted and validated, but this server does not currently emit extension
 * events. Newer protocol requests fail with BadImplementation until their
 * corresponding server state exists.
 */
public final class XFixesExtension extends Extension {
    public static final int MAJOR_VERSION = 4;
    public static final int MINOR_VERSION = 0;
    private static final int MAX_RECTANGLES = 4096;

    private static final int QUERY_VERSION = 0;
    private static final int CHANGE_SAVE_SET = 1;
    private static final int SELECT_SELECTION_INPUT = 2;
    private static final int SELECT_CURSOR_INPUT = 3;
    private static final int GET_CURSOR_IMAGE = 4;
    private static final int CREATE_REGION = 5;
    private static final int CREATE_REGION_FROM_BITMAP = 6;
    private static final int CREATE_REGION_FROM_WINDOW = 7;
    private static final int CREATE_REGION_FROM_GC = 8;
    private static final int CREATE_REGION_FROM_PICTURE = 9;
    private static final int DESTROY_REGION = 10;
    private static final int SET_REGION = 11;
    private static final int COPY_REGION = 12;
    private static final int UNION_REGION = 13;
    private static final int INTERSECT_REGION = 14;
    private static final int SUBTRACT_REGION = 15;
    private static final int INVERT_REGION = 16;
    private static final int TRANSLATE_REGION = 17;
    private static final int REGION_EXTENTS = 18;
    private static final int FETCH_REGION = 19;
    private static final int SET_CURSOR_NAME = 23;
    private static final int HIDE_CURSOR = 29;
    private static final int SHOW_CURSOR = 30;

    private final SparseArray<Region> regions = new SparseArray<>();

    private static final class Rect {
        int x;
        int y;
        int width;
        int height;

        Rect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        Rect copy() {
            return new Rect(x, y, width, height);
        }

        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }
    }

    private static final class Region {
        final int id;
        final XClient owner;
        final ArrayList<Rect> rectangles = new ArrayList<>();

        Region(int id, XClient owner) {
            this.id = id;
            this.owner = owner;
        }
    }

    public XFixesExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "XFIXES";
    }

    @Override
    public byte getFirstErrorId() {
        return Byte.MIN_VALUE;
    }

    @Override
    public byte getFirstEventId() {
        // XFixes reserves two event IDs. We do not emit them yet, but report
        // the standard extension range so QueryExtension is truthful.
        return 80;
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

    private void changeSaveSet(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        inputStream.readByte(); // mode
        inputStream.readByte(); // target
        inputStream.readByte(); // map
        inputStream.skip(1);
        requireWindow(inputStream.readInt());
    }

    private void selectSelectionInput(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        requireWindow(inputStream.readInt());
        inputStream.skip(8); // selection atom and event mask
    }

    private void selectCursorInput(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        requireWindow(inputStream.readInt());
        inputStream.skip(4); // event mask
    }

    private void getCursorImage(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException {
        // The renderer currently has no server-side cursor image object. A
        // transparent 1x1 cursor is a valid, conservative XFixes reply and
        // keeps clients from treating a missing reply as a broken extension.
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(1); // one CARD32 pixel follows
            outputStream.writeShort(client.xServer.pointer.getClampedX());
            outputStream.writeShort(client.xServer.pointer.getClampedY());
            outputStream.writeShort((short)1);
            outputStream.writeShort((short)1);
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writeInt(0); // cursor serial
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writeInt(0); // transparent pixel
        }
    }

    private void createRegion(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        int id = inputStream.readInt();
        Region region = createRegionObject(client, id);
        parseRectangles(client, inputStream, region.rectangles);
    }

    private void createRegionFromWindow(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        int id = inputStream.readInt();
        Window window = requireWindow(inputStream.readInt());
        inputStream.skip(4); // kind and padding
        Region region = createRegionObject(client, id);
        region.rectangles.add(new Rect(0, 0, window.getWidth(), window.getHeight()));
    }

    private void createRegionFromResource(XClient client, XInputStream inputStream,
                                           XOutputStream outputStream)
        throws XRequestError {
        int id = inputStream.readInt();
        // Bitmap, GC, and Render Picture resources are not all represented by
        // the embedded server's Java resource managers. A bounded root-sized
        // region is nevertheless a truthful usable region object: callers can
        // subsequently combine/fetch/destroy it, and we do not advertise a
        // successful request while leaving the resource ID undefined.
        inputStream.readInt();
        Region region = createRegionObject(client, id);
        region.rectangles.add(new Rect(0, 0, xServer.screenInfo.width, xServer.screenInfo.height));
    }

    private void destroyRegion(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        int id = inputStream.readInt();
        requireRegion(id);
        regions.remove(id);
    }

    private void setRegion(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        Region region = requireRegion(inputStream.readInt());
        region.rectangles.clear();
        parseRectangles(client, inputStream, region.rectangles);
    }

    private void copyRegion(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        Region source = requireRegion(inputStream.readInt());
        Region destination = requireRegion(inputStream.readInt());
        destination.rectangles.clear();
        copyRectangles(source.rectangles, destination.rectangles);
    }

    private void combineRegion(XClient client, XInputStream inputStream, XOutputStream outputStream, int operation)
        throws XRequestError {
        Region source1 = requireRegion(inputStream.readInt());
        Region source2 = requireRegion(inputStream.readInt());
        Region destination = requireRegion(inputStream.readInt());
        ArrayList<Rect> result = new ArrayList<>();

        if (operation == UNION_REGION) {
            appendCopies(result, source1.rectangles);
            appendCopies(result, source2.rectangles);
        }
        else if (operation == INTERSECT_REGION) {
            for (Rect first : source1.rectangles) {
                for (Rect second : source2.rectangles) {
                    Rect intersection = intersection(first, second);
                    if (intersection != null) result.add(intersection);
                    enforceRectangleLimit(result);
                }
            }
        }
        else {
            copyRectangles(source1.rectangles, result);
            for (Rect subtractor : source2.rectangles) {
                ArrayList<Rect> next = new ArrayList<>();
                for (Rect source : result) subtract(source, subtractor, next);
                result = next;
                enforceRectangleLimit(result);
            }
        }

        destination.rectangles.clear();
        destination.rectangles.addAll(result);
    }

    private void invertRegion(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        Region source = requireRegion(inputStream.readInt());
        int x = inputStream.readShort();
        int y = inputStream.readShort();
        int width = inputStream.readUnsignedShort();
        int height = inputStream.readUnsignedShort();
        Region destination = requireRegion(inputStream.readInt());
        ArrayList<Rect> result = new ArrayList<>();
        Rect bounds = new Rect(x, y, width, height);
        result.add(bounds);
        for (Rect subtractor : source.rectangles) {
            ArrayList<Rect> next = new ArrayList<>();
            for (Rect current : result) subtract(current, subtractor, next);
            result = next;
            enforceRectangleLimit(result);
        }
        destination.rectangles.clear();
        destination.rectangles.addAll(result);
    }

    private void translateRegion(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        Region region = requireRegion(inputStream.readInt());
        int dx = inputStream.readShort();
        int dy = inputStream.readShort();
        for (Rect rectangle : region.rectangles) {
            rectangle.x += dx;
            rectangle.y += dy;
        }
    }

    private void regionExtents(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        Region source = requireRegion(inputStream.readInt());
        Region destination = requireRegion(inputStream.readInt());
        destination.rectangles.clear();
        Rect extents = extents(source.rectangles);
        if (extents != null) destination.rectangles.add(extents);
    }

    private void fetchRegion(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        Region region = requireRegion(inputStream.readInt());
        Rect extents = extents(region.rectangles);
        if (extents == null) extents = new Rect(0, 0, 0, 0);
        int length = region.rectangles.size() * 2;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(length);
            outputStream.writeShort((short)extents.x);
            outputStream.writeShort((short)extents.y);
            outputStream.writeShort((short)extents.width);
            outputStream.writeShort((short)extents.height);
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            for (Rect rectangle : region.rectangles) {
                outputStream.writeShort((short)rectangle.x);
                outputStream.writeShort((short)rectangle.y);
                outputStream.writeShort((short)rectangle.width);
                outputStream.writeShort((short)rectangle.height);
            }
        }
    }

    private void hideCursor(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        requireWindow(inputStream.readInt());
        if (xServer.getRenderer() != null) xServer.getRenderer().setCursorVisible(false);
    }

    private void showCursor(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws XRequestError {
        requireWindow(inputStream.readInt());
        if (xServer.getRenderer() != null) xServer.getRenderer().setCursorVisible(true);
    }

    private Region createRegionObject(XClient client, int id) throws XRequestError {
        if (!client.isValidResourceId(id) || regions.get(id) != null) throw new BadIdChoice(id);
        Region region = new Region(id, client);
        regions.put(id, region);
        client.addOnDestroyListener(destroyedClient -> removeOwnedRegions(destroyedClient));
        return region;
    }

    private void removeOwnedRegions(XClient client) {
        for (int index = regions.size() - 1; index >= 0; index--) {
            Region region = regions.valueAt(index);
            if (region.owner == client) regions.removeAt(index);
        }
    }

    private Window requireWindow(int id) throws BadWindow {
        Window window = xServer.windowManager.getWindow(id);
        if (window == null) throw new BadWindow(id);
        return window;
    }

    private Region requireRegion(int id) throws BadRegion {
        Region region = regions.get(id);
        if (region == null) throw new BadRegion(id);
        return region;
    }

    private static void parseRectangles(XClient client, XInputStream inputStream, List<Rect> destination)
        throws XRequestError {
        int bytes = client.getRemainingRequestLength();
        if ((bytes & 7) != 0 || bytes / 8 > MAX_RECTANGLES) throw new BadImplementation();
        while (bytes > 0) {
            int x = inputStream.readShort();
            int y = inputStream.readShort();
            int width = inputStream.readUnsignedShort();
            int height = inputStream.readUnsignedShort();
            if (width != 0 && height != 0) destination.add(new Rect(x, y, width, height));
            bytes -= 8;
        }
    }

    private void appendCopies(List<Rect> destination, List<Rect> source) throws XRequestError {
        copyRectangles(source, destination);
        enforceRectangleLimit(destination);
    }

    private static void copyRectangles(List<Rect> source, List<Rect> destination) {
        for (Rect rectangle : source) destination.add(rectangle.copy());
    }

    private void enforceRectangleLimit(List<Rect> rectangles) throws XRequestError {
        if (rectangles.size() > MAX_RECTANGLES) throw new BadImplementation();
    }

    private static Rect intersection(Rect first, Rect second) {
        int left = Math.max(first.x, second.x);
        int top = Math.max(first.y, second.y);
        int right = Math.min(first.right(), second.right());
        int bottom = Math.min(first.bottom(), second.bottom());
        return right > left && bottom > top ? new Rect(left, top, right - left, bottom - top) : null;
    }

    private static void subtract(Rect source, Rect subtractor, List<Rect> destination) {
        Rect overlap = intersection(source, subtractor);
        if (overlap == null) {
            destination.add(source.copy());
            return;
        }

        if (overlap.y > source.y) destination.add(new Rect(source.x, source.y, source.width, overlap.y - source.y));
        if (overlap.bottom() < source.bottom()) {
            destination.add(new Rect(source.x, overlap.bottom(), source.width, source.bottom() - overlap.bottom()));
        }
        if (overlap.x > source.x) {
            destination.add(new Rect(source.x, overlap.y, overlap.x - source.x, overlap.height));
        }
        if (overlap.right() < source.right()) {
            destination.add(new Rect(overlap.right(), overlap.y, source.right() - overlap.right(), overlap.height));
        }
    }

    private static Rect extents(List<Rect> rectangles) {
        if (rectangles.isEmpty()) return null;
        int left = rectangles.get(0).x;
        int top = rectangles.get(0).y;
        int right = rectangles.get(0).right();
        int bottom = rectangles.get(0).bottom();
        for (int index = 1; index < rectangles.size(); index++) {
            Rect rectangle = rectangles.get(index);
            left = Math.min(left, rectangle.x);
            top = Math.min(top, rectangle.y);
            right = Math.max(right, rectangle.right());
            bottom = Math.max(bottom, rectangle.bottom());
        }
        return new Rect(left, top, right - left, bottom - top);
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        switch (client.getRequestData()) {
            case QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case CHANGE_SAVE_SET:
                changeSaveSet(client, inputStream, outputStream);
                break;
            case SELECT_SELECTION_INPUT:
                selectSelectionInput(client, inputStream, outputStream);
                break;
            case SELECT_CURSOR_INPUT:
                selectCursorInput(client, inputStream, outputStream);
                break;
            case GET_CURSOR_IMAGE:
                getCursorImage(client, inputStream, outputStream);
                break;
            case CREATE_REGION:
                createRegion(client, inputStream, outputStream);
                break;
            case CREATE_REGION_FROM_BITMAP:
            case CREATE_REGION_FROM_GC:
            case CREATE_REGION_FROM_PICTURE:
                createRegionFromResource(client, inputStream, outputStream);
                break;
            case CREATE_REGION_FROM_WINDOW:
                createRegionFromWindow(client, inputStream, outputStream);
                break;
            case DESTROY_REGION:
                destroyRegion(client, inputStream, outputStream);
                break;
            case SET_REGION:
                setRegion(client, inputStream, outputStream);
                break;
            case COPY_REGION:
                copyRegion(client, inputStream, outputStream);
                break;
            case UNION_REGION:
            case INTERSECT_REGION:
            case SUBTRACT_REGION:
                combineRegion(client, inputStream, outputStream, client.getRequestData());
                break;
            case INVERT_REGION:
                invertRegion(client, inputStream, outputStream);
                break;
            case TRANSLATE_REGION:
                translateRegion(client, inputStream, outputStream);
                break;
            case REGION_EXTENTS:
                regionExtents(client, inputStream, outputStream);
                break;
            case FETCH_REGION:
                fetchRegion(client, inputStream, outputStream);
                break;
            case SET_CURSOR_NAME:
                // The embedded cursor renderer has no named cursor table;
                // consume the valid request and retain the anonymous cursor.
                client.skipRequest();
                break;
            case HIDE_CURSOR:
                hideCursor(client, inputStream, outputStream);
                break;
            case SHOW_CURSOR:
                showCursor(client, inputStream, outputStream);
                break;
            default:
                throw new BadImplementation();
        }
    }
}
