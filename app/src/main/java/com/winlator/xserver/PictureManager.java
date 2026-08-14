package com.winlator.xserver;

import android.util.SparseArray;

public class PictureManager extends XResourceManager {
    private final SparseArray<Picture> pictures = new SparseArray<>();

    public Picture getPicture(int id) {
        return pictures.get(id);
    }

    public Picture createPicture(int id, Drawable drawable, int format) {
        if (pictures.indexOfKey(id) >= 0) return null;
        Picture picture = new Picture(id, drawable, format);
        pictures.put(id, picture);
        triggerOnCreateResourceListener(picture);
        return picture;
    }

    public void freePicture(int id) {
        Picture picture = pictures.get(id);
        if (picture != null) triggerOnFreeResourceListener(picture);
        pictures.remove(id);
    }
}
