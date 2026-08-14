package com.winlator.xserver;

/**
 * The server-side state created by a Render CreatePicture request.
 *
 * The Android renderer does not execute XRender compositing, but clients still
 * expect picture IDs and their clip/attribute state to have normal X resource
 * lifetime semantics while they create an X11 browser surface.
 */
public class Picture extends XResource {
    public final Drawable drawable;
    public final int format;
    private int attributesMask;
    private short clipXOrigin;
    private short clipYOrigin;
    private int clipRectangleCount;

    public Picture(int id, Drawable drawable, int format) {
        super(id);
        this.drawable = drawable;
        this.format = format;
    }

    public int getAttributesMask() {
        return attributesMask;
    }

    public void setAttributesMask(int attributesMask) {
        this.attributesMask = attributesMask;
    }

    public void setClip(short xOrigin, short yOrigin, int rectangleCount) {
        clipXOrigin = xOrigin;
        clipYOrigin = yOrigin;
        clipRectangleCount = rectangleCount;
    }

    public short getClipXOrigin() {
        return clipXOrigin;
    }

    public short getClipYOrigin() {
        return clipYOrigin;
    }

    public int getClipRectangleCount() {
        return clipRectangleCount;
    }
}
