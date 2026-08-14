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
import java.util.Arrays;

/** Stages Winlator's GLX client for the embedded XServerCore GLX endpoint. */
public final class SteamGladioProvisioner {
    private static final String GLADIO_ASSET = "graphics_driver/gladio-1.0.tzst";
    private static final String GLX_COMPAT_LIBRARY = "libsteamdroid_glx_compat.so";
    private static final byte[] GLADIO_LEGACY_X11_SOCKET =
        "/data/data/com.winlator/files/rootfs/tmp/.X11-unix/X0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GLADIO_GUEST_X11_SOCKET =
        "/tmp/.X11-unix/X0".getBytes(StandardCharsets.US_ASCII);
    private static final long GLADIO_QUERY_EXTENSION_OFFSET = 0x56070L;
    private static final long GLADIO_GET_CONFIG_OFFSET = 0x55d70L;
    private static final byte[] GLADIO_QUERY_EXTENSION_PATCH = new byte[]{
        0x61, 0x00, 0x00, (byte)0xb4, // cbz x1, set_success
        0x03, 0x10, (byte)0x80, 0x52, // mov w3, #128
        0x23, 0x00, 0x00, (byte)0xb9, // str w3, [x1]
        0x42, 0x00, 0x00, (byte)0xb4, // cbz x2, return_success
        0x5f, 0x00, 0x00, (byte)0xb9, // str wzr, [x2]
        0x20, 0x00, (byte)0x80, 0x52, // mov w0, #1
        (byte)0xc0, 0x03, 0x5f, (byte)0xd6 // ret
    };
    private static final byte[] GLADIO_GET_CONFIG_PATCH = new byte[]{
        0x63, 0x00, 0x00, (byte)0xb4, // cbz x3, bad_attribute
        0x24, 0x00, (byte)0x80, 0x52, // mov w4, #1
        0x64, 0x00, 0x00, (byte)0xb9, // str w4, [x3]
        0x00, 0x00, (byte)0x80, 0x52, // mov w0, #0
        (byte)0xc0, 0x03, 0x5f, (byte)0xd6, // ret
        0x40, 0x00, (byte)0x80, 0x52, // bad_attribute: mov w0, #2
        (byte)0xc0, 0x03, 0x5f, (byte)0xd6 // ret
    };

    private final Context context;
    private final File stageRoot;
    private final File library;
    private final File libraryAlias;
    private final File compatLibrary;

    public SteamGladioProvisioner(Context context) {
        this.context = context.getApplicationContext();
        stageRoot = new File(this.context.getFilesDir(), "steamdroid/holo-rootfs/opt/steamdroid-gladio");
        library = new File(stageRoot, "usr/lib/libGL.so.1.7.0");
        libraryAlias = new File(stageRoot, "usr/lib/libGL.so.1");
        compatLibrary = new File(stageRoot, "usr/lib/" + GLX_COMPAT_LIBRARY);
    }

    public File getLibrary() {
        return library;
    }

    public boolean isInstalled() {
        return isExecutable(library) && isExecutable(libraryAlias) && isExecutable(compatLibrary);
    }

    public File ensureInstalled() throws IOException {
        // The Gladio asset may already have been provisioned by an earlier
        // build. Add the immutable APK-built interposer atomically without
        // replacing the existing renderer or touching the Holo rootfs.
        if (isExecutable(library)) {
            patchGladioDisplayPath(library);
            patchGladioQueryExtension(stageRoot);
            installLibraryAlias(stageRoot);
            installCompatLibrary(stageRoot);
            if (!isInstalled()) throw new IOException("Gladio compatibility staging is not executable");
            return library;
        }

        File parent = stageRoot.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("unable to create Gladio staging parent");
        }
        if (stageRoot.exists()) throw new IOException("refusing to reuse incomplete Gladio staging");
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
        patchGladioQueryExtension(partial);
        patchGladioDisplayPath(stagedLibrary);
        installLibraryAlias(partial);
        installCompatLibrary(partial);
        if (!partial.renameTo(stageRoot)) {
            FileUtils.delete(partial);
            throw new IOException("unable to commit Gladio staging");
        }
        if (!isInstalled()) throw new IOException("Gladio staging is not executable");
        return library;
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

    private static void patchGladioQueryExtension(File targetRoot) throws IOException {
        File target = new File(targetRoot, "usr/lib/libGL.so.1.7.0");
        if (!isExecutable(target)) throw new IOException("staged Gladio library is unavailable");

        patchGladioFunction(target, GLADIO_QUERY_EXTENSION_OFFSET,
            GLADIO_QUERY_EXTENSION_PATCH, "query-extension");
        patchGladioFunction(target, GLADIO_GET_CONFIG_OFFSET,
            GLADIO_GET_CONFIG_PATCH, "get-config");
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

            file.seek(legacyOffset);
            file.write(GLADIO_GUEST_X11_SOCKET);
            for (int i = GLADIO_GUEST_X11_SOCKET.length; i < GLADIO_LEGACY_X11_SOCKET.length; i++) {
                file.write(0);
            }
            file.getFD().sync();
        }
    }

    private static int findBytes(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || needle.length > haystack.length) return -1;
        for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
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

    private static void patchGladioFunction(File target, long offset, byte[] patch,
                                            String functionName) throws IOException {
        byte[] current = new byte[patch.length];
        try (RandomAccessFile file = new RandomAccessFile(target, "rw")) {
            if (file.length() < offset + current.length) {
                throw new IOException("Gladio library is too small for the " + functionName + " patch");
            }
            file.seek(offset);
            file.readFully(current);
            if (Arrays.equals(current, patch)) return;

            // The first instruction of both pinned Gladio stubs is
            // `sub sp, sp, #304`. Do not patch an unvalidated renderer build.
            if ((current[0] & 0xff) != 0xff || (current[1] & 0xff) != 0xc3 ||
                (current[2] & 0xff) != 0x04 || (current[3] & 0xff) != 0xd1) {
                throw new IOException("unsupported Gladio " + functionName + " implementation");
            }
            file.seek(offset);
            file.write(patch);
            file.getFD().sync();
        }
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
