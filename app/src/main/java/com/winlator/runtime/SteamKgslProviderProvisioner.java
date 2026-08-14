package com.winlator.runtime;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import com.winlator.core.FileUtils;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Installs the glibc KGSL Turnip provider required by Thor's Android GPU
 * device. Holo's packaged Mesa is built for msm DRM and cannot see KGSL, so
 * the provider is kept beside the Holo image and selected by an absolute ICD
 * path from the native supervisor.
 */
public final class SteamKgslProviderProvisioner {
    private static final String DRIVER_ASSET =
        "steamdroid/kgsl/libvulkan_freedreno.so";
    private static final String ICD_ASSET =
        "steamdroid/kgsl/freedreno-kgsl.icd.json";
    private static final String DRIVER_RELATIVE_PATH =
        "opt/steamdroid-kgsl-driver/libvulkan_freedreno.so";
    private static final String ICD_RELATIVE_PATH =
        "opt/steamdroid-kgsl-driver/freedreno-kgsl.icd.json";
    private static final String DRIVER_LIBRARY_PATH =
        "/opt/steamdroid-kgsl-driver/libvulkan_freedreno.so";
    private static final long MAX_DRIVER_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_ICD_BYTES = 16L * 1024L;
    private static final int ELF_CLASS_64 = 2;
    private static final int ELF_DATA_LSB = 1;
    private static final int ELF_TYPE_DYN = 3;
    private static final int ELF_MACHINE_AARCH64 = 183;

    private final Context context;
    private final SteamArm64Channel channel;
    private final File holoRoot;

    public SteamKgslProviderProvisioner(Context context) throws IOException {
        this.context = context.getApplicationContext();
        channel = SteamArm64Channel.load(this.context);
        holoRoot = new File(this.context.getFilesDir(), "steamdroid/holo-rootfs");
    }

    public File getDriverFile() {
        return new File(holoRoot, DRIVER_RELATIVE_PATH);
    }

    public File getIcdFile() {
        return new File(holoRoot, ICD_RELATIVE_PATH);
    }

    public boolean isInstalled() {
        return getDriverFile().isFile() && getIcdFile().isFile() &&
            getDriverFile().length() == channel.kgslDriverBytes;
    }

    /** Installs or verifies the immutable-in-session driver closure. */
    public void ensureInstalled() throws IOException {
        if (!new File(holoRoot, "usr/lib/ld-linux-aarch64.so.1").isFile()) {
            throw new IOException("Holo ARM64 rootfs is not installed");
        }

        File destination = new File(holoRoot, "opt/steamdroid-kgsl-driver");
        File driver = getDriverFile();
        File icd = getIcdFile();
        if (driver.isFile() && icd.isFile()) {
            verifyDriver(driver);
            verifyIcd(readUtf8(icd, MAX_ICD_BYTES));
            chmod(driver, 0755);
            chmod(icd, 0644);
            return;
        }

        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("unable to create KGSL provider parent");
        }
        File partial = new File(parent, destination.getName() + ".partial");
        if (partial.exists()) {
            throw new IOException("refusing to reuse incomplete KGSL provider staging");
        }
        if (!partial.mkdirs()) throw new IOException("unable to create KGSL provider staging");

        try {
            File stagedDriver = new File(partial, "libvulkan_freedreno.so");
            copyAsset(DRIVER_ASSET, stagedDriver, channel.kgslDriverBytes, MAX_DRIVER_BYTES);
            chmod(stagedDriver, 0755);
            File stagedIcd = new File(partial, "freedreno-kgsl.icd.json");
            copyAsset(ICD_ASSET, stagedIcd, -1, MAX_ICD_BYTES);
            chmod(stagedIcd, 0644);

            verifyDriver(stagedDriver);
            verifyIcd(readUtf8(stagedIcd, MAX_ICD_BYTES));
            if (destination.exists() && !FileUtils.delete(destination)) {
                throw new IOException("unable to replace KGSL provider");
            }
            if (!partial.renameTo(destination)) {
                throw new IOException("unable to commit KGSL provider");
            }
        }
        catch (IOException e) {
            if (partial.exists()) FileUtils.delete(partial);
            throw e;
        }
    }

    private void verifyDriver(File file) throws IOException {
        if (!file.isFile() || file.length() != channel.kgslDriverBytes) {
            throw new IOException("KGSL provider has an unexpected size");
        }
        byte[] header = new byte[20];
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            int offset = 0;
            while (offset < header.length) {
                int length = input.read(header, offset, header.length - offset);
                if (length < 0) break;
                offset += length;
            }
            if (offset != header.length || header[0] != 0x7f || header[1] != 'E' ||
                header[2] != 'L' || header[3] != 'F' ||
                (header[4] & 0xff) != ELF_CLASS_64 ||
                (header[5] & 0xff) != ELF_DATA_LSB ||
                littleEndianShort(header, 16) != ELF_TYPE_DYN ||
                littleEndianShort(header, 18) != ELF_MACHINE_AARCH64) {
                throw new IOException("KGSL provider is not an AArch64 PIE-compatible ELF");
            }
        }
        verifySha256(file, channel.kgslDriverSha256);
    }

    private static void verifyIcd(String text) throws IOException {
        if (!text.contains("\"file_format_version\"") ||
            !text.contains("\"library_arch\": \"64\"") ||
            !text.contains("\"library_path\": \"" + DRIVER_LIBRARY_PATH + "\"") ||
            text.contains("..") || text.indexOf('\0') >= 0) {
            throw new IOException("KGSL ICD is not the pinned SteamDroid descriptor");
        }
    }

    private void copyAsset(String asset, File destination, long expectedBytes, long maxBytes)
        throws IOException {
        try (InputStream input = new BufferedInputStream(context.getAssets().open(asset));
             java.io.OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[1024 * 1024];
            long copied = 0;
            int length;
            while ((length = input.read(buffer)) != -1) {
                copied += length;
                if (copied > maxBytes || (expectedBytes >= 0 && copied > expectedBytes)) {
                    throw new IOException("KGSL provider asset is too large");
                }
                output.write(buffer, 0, length);
            }
            if (expectedBytes >= 0 && copied != expectedBytes) {
                throw new IOException("KGSL provider asset has the wrong size");
            }
            if (copied == 0) throw new IOException("KGSL provider asset is empty");
        }
    }

    private static int littleEndianShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static String readUtf8(File file, long maxBytes) throws IOException {
        if (file.length() > maxBytes) throw new IOException("KGSL ICD is too large");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int)file.length());
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (output.size() + length > maxBytes) throw new IOException("KGSL ICD is too large");
                output.write(buffer, 0, length);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void verifySha256(File file, String expected) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[1024 * 1024];
                int length;
                while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            }
            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest.digest()) actual.append(String.format("%02x", value));
            if (!expected.equals(actual.toString())) {
                throw new IOException("KGSL provider SHA-256 mismatch: " + actual);
            }
        }
        catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static void chmod(File file, int mode) throws IOException {
        try {
            Os.chmod(file.getPath(), mode);
        }
        catch (ErrnoException e) {
            throw new IOException("unable to chmod KGSL provider path: " + file, e);
        }
    }
}
