package com.winlator.runtime;

import android.content.Context;

import com.winlator.core.FileUtils;
import com.winlator.core.TarCompressorUtils;

import java.io.File;
import java.io.IOException;

/** Stages Winlator's GLX client for the embedded XServerCore GLX endpoint. */
public final class SteamGladioProvisioner {
    private static final String GLADIO_ASSET = "graphics_driver/gladio-1.0.tzst";

    private final Context context;
    private final File stageRoot;
    private final File library;

    public SteamGladioProvisioner(Context context) {
        this.context = context.getApplicationContext();
        stageRoot = new File(this.context.getFilesDir(), "steamdroid/holo-rootfs/opt/steamdroid-gladio");
        library = new File(stageRoot, "usr/lib/libGL.so.1.7.0");
    }

    public File getLibrary() {
        return library;
    }

    public boolean isInstalled() {
        return library.isFile() && library.canRead() && library.canExecute();
    }

    public File ensureInstalled() throws IOException {
        if (isInstalled()) return library;

        File parent = stageRoot.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("unable to create Gladio staging parent");
        }
        File partial = new File(stageRoot.getPath() + ".partial");
        if (partial.exists()) throw new IOException("refusing to reuse incomplete Gladio staging");
        if (!partial.mkdirs()) throw new IOException("unable to create Gladio staging");

        boolean extracted = TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD, context, GLADIO_ASSET, partial);
        File stagedLibrary = new File(partial, "usr/lib/libGL.so.1.7.0");
        if (!extracted || !stagedLibrary.isFile() || !stagedLibrary.canRead()) {
            FileUtils.delete(partial);
            throw new IOException("Gladio asset extraction failed validation");
        }
        if (!partial.renameTo(stageRoot)) {
            FileUtils.delete(partial);
            throw new IOException("unable to commit Gladio staging");
        }
        if (!isInstalled()) throw new IOException("Gladio staging is not executable");
        return library;
    }
}
