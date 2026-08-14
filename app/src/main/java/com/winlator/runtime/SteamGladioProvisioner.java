package com.winlator.runtime;

import android.content.Context;

import com.winlator.core.FileUtils;
import com.winlator.core.TarCompressorUtils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;

/** Stages Winlator's GLX client for the embedded XServerCore GLX endpoint. */
public final class SteamGladioProvisioner {
    private static final String GLADIO_ASSET = "graphics_driver/gladio-1.0.tzst";
    private static final String GLADIO_BUILD_ID = "gladio-steamdroid-glx-lazy-init-fbconfig-bounds-fd0-all-socket-copies";
    private static final String GLADIO_BUILD_MARKER = ".steamdroid-build";
    private static final String GLX_COMPAT_LIBRARY = "libsteamdroid_glx_compat.so";
    private static final byte[] GLADIO_LEGACY_X11_SOCKET =
        "/data/data/com.winlator/files/rootfs/tmp/.X11-unix/X0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GLADIO_GUEST_X11_SOCKET =
        "/tmp/.X11-unix/X0".getBytes(StandardCharsets.US_ASCII);
    private final Context context;
    private final File stageRoot;
    private final File library;
    private final File libraryAlias;
    private final File compatLibrary;
    private final File buildMarker;

    public SteamGladioProvisioner(Context context) {
        this.context = context.getApplicationContext();
        stageRoot = new File(this.context.getFilesDir(), "steamdroid/holo-rootfs/opt/steamdroid-gladio");
        library = new File(stageRoot, "usr/lib/libGL.so.1.7.0");
        libraryAlias = new File(stageRoot, "usr/lib/libGL.so.1");
        compatLibrary = new File(stageRoot, "usr/lib/" + GLX_COMPAT_LIBRARY);
        buildMarker = new File(stageRoot, GLADIO_BUILD_MARKER);
    }

    public File getLibrary() {
        return library;
    }

    public boolean isInstalled() {
        return isExecutable(library) && isExecutable(libraryAlias) && isExecutable(compatLibrary);
    }

    private boolean isCurrentBuildInstalled() throws IOException {
        if (!isInstalled() || !buildMarker.isFile()) return false;
        byte[] bytes = Files.readAllBytes(buildMarker.toPath());
        return GLADIO_BUILD_ID.equals(new String(bytes, StandardCharsets.US_ASCII).trim());
    }

    public File ensureInstalled() throws IOException {
        // The Gladio asset may already have been provisioned by an earlier
        // build. Add the immutable APK-built interposer atomically without
        // replacing the existing renderer or touching the Holo rootfs.
        if (isCurrentBuildInstalled()) {
            patchGladioDisplayPath(library);
            installLibraryAlias(stageRoot);
            installCompatLibrary(stageRoot);
            if (!isInstalled()) throw new IOException("Gladio compatibility staging is not executable");
            return library;
        }

        File parent = stageRoot.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("unable to create Gladio staging parent");
        }
        File previousStage = null;
        File existingPreviousStage = new File(stageRoot.getPath() + ".previous");
        if (existingPreviousStage.exists() && !stageRoot.exists() &&
            !existingPreviousStage.renameTo(stageRoot)) {
            throw new IOException("unable to restore previous Gladio staging");
        }
        if (stageRoot.exists()) {
            previousStage = new File(stageRoot.getPath() + ".previous");
            if (previousStage.exists()) {
                throw new IOException("refusing to replace Gladio staging with an unresolved previous build");
            }
            if (!stageRoot.renameTo(previousStage)) {
                throw new IOException("unable to preserve previous Gladio staging");
            }
        }
        File partial = new File(stageRoot.getPath() + ".partial");
        if (partial.exists() && !FileUtils.delete(partial)) {
            throw new IOException("unable to discard incomplete Gladio staging");
        }

        try {
            if (!partial.mkdirs()) throw new IOException("unable to create Gladio staging");

            boolean extracted = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, context, GLADIO_ASSET, partial);
            File stagedLibrary = new File(partial, "usr/lib/libGL.so.1.7.0");
            if (!extracted || !stagedLibrary.isFile() || !stagedLibrary.canRead()) {
                throw new IOException("Gladio asset extraction failed validation");
            }
            patchGladioDisplayPath(stagedLibrary);
            installLibraryAlias(partial);
            installCompatLibrary(partial);
            writeBuildMarker(partial);
            if (!partial.renameTo(stageRoot)) {
                throw new IOException("unable to commit Gladio staging");
            }
            if (!isInstalled()) throw new IOException("Gladio staging is not executable");
            if (previousStage != null) FileUtils.delete(previousStage);
            return library;
        }
        catch (IOException e) {
            FileUtils.delete(partial);
            if (previousStage != null && !stageRoot.exists() && previousStage.exists()) {
                previousStage.renameTo(stageRoot);
            }
            throw e;
        }
    }

    private static void writeBuildMarker(File targetRoot) throws IOException {
        File marker = new File(targetRoot, GLADIO_BUILD_MARKER);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(marker)) {
            output.write(GLADIO_BUILD_ID.getBytes(StandardCharsets.US_ASCII));
            output.write('\n');
            output.getFD().sync();
        }
        FileUtils.chmod(marker, 0644);
    }

    private static void installLibraryAlias(File targetRoot) throws IOException {
        File source = new File(targetRoot, "usr/lib/libGL.so.1.7.0");
        if (!isExecutable(source)) throw new IOException("staged Gladio library is unavailable");

        File target = new File(targetRoot, "usr/lib/libGL.so.1");
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("unable to create Gladio library directory");
        }
        File partial = new File(target.getPath() + ".partial");
        if (partial.exists()) throw new IOException("refusing to reuse incomplete Gladio alias staging");
        if (!FileUtils.copy(source, partial)) {
            FileUtils.delete(partial);
            throw new IOException("unable to copy Gladio library alias");
        }
        FileUtils.chmod(partial, 0755);
        if (!replaceAtomically(partial, target)) {
            FileUtils.delete(partial);
            throw new IOException("unable to commit Gladio library alias");
        }
    }

    /**
     * The pinned Winlator Gladio client was built with an absolute socket path
     * for Winlator's package. SteamDroid executes it inside Holo, where the
     * substrate bind is intentionally exposed at the guest-relative path.
     * Patch only the validated legacy string; reject an unknown Gladio build.
     */
    private static void patchGladioDisplayPath(File target) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(target, "rw")) {
            if (file.length() > Integer.MAX_VALUE) {
                throw new IOException("Gladio library is too large to validate");
            }

            byte[] image = new byte[(int)file.length()];
            file.readFully(image);
            int legacyOffset = findBytes(image, GLADIO_LEGACY_X11_SOCKET);
            if (legacyOffset < 0) {
                if (findBytes(image, GLADIO_GUEST_X11_SOCKET) >= 0) return;
                throw new IOException("unsupported Gladio X11 socket path");
            }

            // The compiler may emit more than one copy of the macro literal
            // (for example, one for the socket address and one for logging).
            // Patch every validated copy so the connection path cannot remain
            // pointed at Winlator's host namespace.
            while (legacyOffset >= 0) {
                file.seek(legacyOffset);
                file.write(GLADIO_GUEST_X11_SOCKET);
                for (int i = GLADIO_GUEST_X11_SOCKET.length; i < GLADIO_LEGACY_X11_SOCKET.length; i++) {
                    file.write(0);
                }
                legacyOffset = findBytes(image, GLADIO_LEGACY_X11_SOCKET,
                    legacyOffset + GLADIO_LEGACY_X11_SOCKET.length);
            }
            file.getFD().sync();
        }
    }

    private static int findBytes(byte[] haystack, byte[] needle) {
        return findBytes(haystack, needle, 0);
    }

    private static int findBytes(byte[] haystack, byte[] needle, int startOffset) {
        if (needle.length == 0 || needle.length > haystack.length) return -1;
        for (int offset = Math.max(0, startOffset); offset <= haystack.length - needle.length; offset++) {
            boolean match = true;
            for (int index = 0; index < needle.length; index++) {
                if (haystack[offset + index] != needle[index]) {
                    match = false;
                    break;
                }
            }
            if (match) return offset;
        }
        return -1;
    }

    private void installCompatLibrary(File targetRoot) throws IOException {
        File source = new File(context.getApplicationInfo().nativeLibraryDir, GLX_COMPAT_LIBRARY);
        if (!isExecutable(source)) throw new IOException("packaged GLX compatibility library is unavailable");

        File target = new File(targetRoot, "usr/lib/" + GLX_COMPAT_LIBRARY);
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("unable to create Gladio compatibility directory");
        }
        File partial = new File(target.getPath() + ".partial");
        if (partial.exists()) throw new IOException("refusing to reuse incomplete GLX compatibility staging");
        if (!FileUtils.copy(source, partial)) {
            FileUtils.delete(partial);
            throw new IOException("unable to copy packaged GLX compatibility library");
        }
        FileUtils.chmod(partial, 0755);
        if (!replaceAtomically(partial, target)) {
            FileUtils.delete(partial);
            throw new IOException("unable to commit GLX compatibility staging");
        }
    }

    private static boolean replaceAtomically(File source, File target) {
        try {
            Files.move(source.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
        catch (AtomicMoveNotSupportedException e) {
            return source.renameTo(target);
        }
        catch (IOException e) {
            return false;
        }
    }

    private static boolean isExecutable(File file) {
        return file.isFile() && file.canRead() && file.canExecute();
    }
}
