package com.winlator.runtime;

import android.content.Context;

import com.winlator.core.FileUtils;
import com.winlator.core.TarCompressorUtils;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Imports a pinned Holo ARM64 rootfs without touching Winlator's Wine image. */
public final class SteamHoloProvisioner {
    private final Context context;
    private final SteamArm64Channel channel;
    private final File rootDir;

    public SteamHoloProvisioner(Context context) throws IOException {
        this.context = context.getApplicationContext();
        channel = SteamArm64Channel.load(this.context);
        rootDir = new File(this.context.getFilesDir(), "steamdroid/holo-rootfs");
    }

    public File getRootDir() {
        return rootDir;
    }

    public boolean isInstalled() {
        return new File(rootDir, "usr/lib/ld-linux-aarch64.so.1").isFile() &&
            new File(rootDir, "usr/lib/libc.so.6").isFile() &&
            new File(rootDir, "usr/bin/sh").isFile();
    }

    public File downloadArchive() throws IOException {
        File cacheDir = new File(context.getFilesDir(), "steamdroid/downloads");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) throw new IOException("unable to create runtime cache");
        File archive = new File(cacheDir, "system.rootfs.zst");
        if (archive.isFile()) {
            verifySha256(archive, channel.holoRootfsSha256);
            return archive;
        }

        StorageBudget.require(cacheDir, channel.holoRootfsDownloadBytes,
            channel.holoRootfsDownloadBytes * 4L, channel.freeSpaceReserveBytes);
        File partial = new File(cacheDir, "system.rootfs.zst.partial");
        if (partial.exists()) throw new IOException("refusing to reuse partial Holo download");

        HttpURLConnection connection = (HttpURLConnection)new URL(channel.holoRootfsEndpoint).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        int responseCode = connection.getResponseCode();
        long contentLength = connection.getContentLengthLong();
        if (responseCode != HttpURLConnection.HTTP_OK || contentLength != channel.holoRootfsDownloadBytes) {
            connection.disconnect();
            throw new IOException("unvalidated Holo download response: code=" + responseCode +
                " length=" + contentLength);
        }
        try (java.io.InputStream input = new java.io.BufferedInputStream(connection.getInputStream());
             java.io.OutputStream output = new java.io.BufferedOutputStream(new java.io.FileOutputStream(partial))) {
            byte[] buffer = new byte[1024 * 1024];
            long copied = 0;
            int length;
            while ((length = input.read(buffer)) != -1) {
                copied += length;
                if (copied > contentLength) throw new IOException("Holo download exceeded Content-Length");
                output.write(buffer, 0, length);
            }
            if (copied != contentLength) throw new IOException("short Holo download: " + copied);
        }
        finally {
            connection.disconnect();
        }
        verifySha256(partial, channel.holoRootfsSha256);
        if (!partial.renameTo(archive)) {
            FileUtils.delete(partial);
            throw new IOException("unable to commit Holo download");
        }
        return archive;
    }

    public void downloadAndInstall(SteamIdentity identity) throws IOException {
        importArchive(downloadArchive(), identity);
    }

    public void importArchive(File archive, SteamIdentity identity) throws IOException {
        if (archive == null || !archive.isFile()) throw new IOException("Holo rootfs archive missing");
        verifySha256(archive, channel.holoRootfsSha256);

        File parent = rootDir.getParentFile();
        if (parent == null) throw new IOException("invalid Holo rootfs parent");
        parent.mkdirs();
        StorageBudget.require(parent, archive.length(), channel.holoRootfsDownloadBytes * 4L,
            channel.freeSpaceReserveBytes);

        if (isInstalled()) {
            identity.writeGuestIdentityViews(rootDir);
            return;
        }

        File partial = new File(parent, "holo-rootfs.partial");
        if (partial.exists()) throw new IOException("refusing to reuse incomplete Holo rootfs staging");
        if (!partial.mkdirs()) throw new IOException("unable to create Holo rootfs staging");
        boolean extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, partial);
        if (!extracted || !new File(partial, "usr/lib/ld-linux-aarch64.so.1").isFile() ||
            !new File(partial, "usr/lib/libc.so.6").isFile() ||
            !new File(partial, "usr/bin/sh").isFile()) {
            FileUtils.delete(partial);
            throw new IOException("Holo rootfs extraction failed validation");
        }
        if (!partial.renameTo(rootDir)) {
            FileUtils.delete(partial);
            throw new IOException("unable to commit Holo rootfs staging");
        }
        identity.writeGuestIdentityViews(rootDir);
    }

    private static void verifySha256(File file, String expected) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            try (java.io.InputStream input = new java.io.BufferedInputStream(new java.io.FileInputStream(file))) {
                int length;
                while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            }
            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest.digest()) actual.append(String.format("%02x", value));
            if (!expected.equals(actual.toString())) {
                throw new IOException("Holo rootfs SHA-256 mismatch: " + actual);
            }
        }
        catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }
}
