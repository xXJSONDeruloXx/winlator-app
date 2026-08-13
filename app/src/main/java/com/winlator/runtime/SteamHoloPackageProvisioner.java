package com.winlator.runtime;

import android.content.Context;

import com.winlator.core.FileUtils;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Downloads the pinned Holo X11/GPU closure for installation by the root supervisor. */
public final class SteamHoloPackageProvisioner {
    private static final String PACKAGE_ASSET = "steamdroid/holo-direct-termux-x11.packages.tsv";
    private static final long MAX_PACKAGE_BYTES = 512L * 1024L * 1024L;

    private final Context context;
    private final SteamArm64Channel channel;
    private final File packageDir;

    public SteamHoloPackageProvisioner(Context context) throws IOException {
        this.context = context.getApplicationContext();
        channel = SteamArm64Channel.load(this.context);
        packageDir = new File(this.context.getFilesDir(), "steamdroid/holo-packages");
    }

    public File getPackageDir() {
        return packageDir;
    }

    public List<PackageEntry> readManifest() throws IOException {
        byte[] asset = readAsset();
        String actualSha256 = sha256(asset);
        if (!actualSha256.equals(channel.holoPackageClosureSha256)) {
            throw new IOException("Holo package closure SHA-256 does not match channel descriptor: actual=" +
                actualSha256 + " expected=" + channel.holoPackageClosureSha256);
        }
        List<PackageEntry> entries = new ArrayList<>();
        Set<String> manifestFiles = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new java.io.ByteArrayInputStream(asset), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split("\\t", -1);
                if (fields.length != 5 || fields[0].isEmpty() || fields[1].isEmpty() ||
                    fields[2].isEmpty() || !fields[3].matches("[0-9a-fA-F]{64}") ||
                    !isBasename(fields[4]) || !isSupportedSource(fields[0]) ||
                    !manifestFiles.add(fields[4])) {
                    throw new IOException("invalid Holo package closure record");
                }
                entries.add(new PackageEntry(fields[0], fields[1], fields[2], fields[3].toLowerCase(), fields[4]));
            }
        }
        if (entries.isEmpty() || entries.size() > 128) throw new IOException("invalid Holo package closure size");
        return entries;
    }

    public void downloadAll() throws IOException {
        List<PackageEntry> entries = readManifest();
        if (!packageDir.isDirectory() && !packageDir.mkdirs()) {
            throw new IOException("unable to create Holo package staging directory");
        }
        Set<String> expectedFiles = new HashSet<>();
        for (PackageEntry entry : entries) expectedFiles.add(entry.file);
        File[] existing = packageDir.listFiles();
        if (existing != null) {
            for (File file : existing) {
                if (file.isFile() && (file.getName().endsWith(".pkg.tar.zst") ||
                    file.getName().endsWith(".pkg.tar.xz")) && !expectedFiles.contains(file.getName())) {
                    FileUtils.delete(file);
                }
            }
        }

        for (PackageEntry entry : entries) {
            File destination = new File(packageDir, entry.file);
            if (destination.isFile()) {
                verifySha256(destination, entry.sha256);
                continue;
            }
            String url = packageUrl(entry);
            download(url, destination, entry.sha256);
        }
    }

    public boolean isInstalled() {
        File root = new File(context.getFilesDir(), "steamdroid/holo-rootfs");
        return new File(root, "usr/lib/libX11.so.6").isFile() &&
            new File(root, "usr/lib/libXfixes.so.3").isFile() &&
            new File(root, "usr/lib/libXrandr.so.2").isFile() &&
            new File(root, "usr/lib/libvulkan_freedreno.so").isFile() &&
            new File(root, "usr/lib/libatk-1.0.so.0").isFile() &&
            new File(root, "usr/lib/libgtk-x11-2.0.so.0").isFile() &&
            new File(root, "var/lib/pacman/local").isDirectory();
    }

    private String packageUrl(PackageEntry entry) throws IOException {
        if ("archlinuxarm".equals(entry.repo)) {
            return channel.archLinuxArmLegacyPackageBaseEndpoint + "/" + entry.file;
        }
        if ("core".equals(entry.repo) || "extra".equals(entry.repo)) {
            return channel.holoPackageBaseEndpoint + "/" + entry.repo + "/os/aarch64/" + entry.file;
        }
        throw new IOException("unsupported Holo package source: " + entry.repo);
    }

    private static boolean isSupportedSource(String source) {
        return "core".equals(source) || "extra".equals(source) || "archlinuxarm".equals(source);
    }

    private byte[] readAsset() throws IOException {
        try (InputStream input = context.getAssets().open(PACKAGE_ASSET)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            return output.toByteArray();
        }
    }

    private static boolean isBasename(String value) {
        return value.length() <= 256 && value.indexOf('/') < 0 && value.indexOf('\\') < 0 &&
            value.indexOf('\0') < 0 && !value.equals(".") && !value.equals("..");
    }

    private void download(String url, File destination, String expectedSha256) throws IOException {
        File partial = new File(destination.getPath() + ".partial");
        if (partial.exists()) throw new IOException("refusing to reuse partial Holo package: " + partial.getName());
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        int responseCode = connection.getResponseCode();
        long contentLength = connection.getContentLengthLong();
        if (responseCode != HttpURLConnection.HTTP_OK || contentLength <= 0 || contentLength > MAX_PACKAGE_BYTES) {
            connection.disconnect();
            throw new IOException("unvalidated Holo package response: code=" + responseCode +
                " length=" + contentLength + " url=" + url);
        }
        StorageBudget.require(destination.getParentFile(), contentLength, contentLength * 4L,
            channel.freeSpaceReserveBytes);
        try {
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new java.io.BufferedOutputStream(new FileOutputStream(partial))) {
                byte[] buffer = new byte[1024 * 1024];
                long copied = 0;
                int length;
                while ((length = input.read(buffer)) != -1) {
                    copied += length;
                    if (copied > contentLength) throw new IOException("Holo package exceeded Content-Length");
                    output.write(buffer, 0, length);
                }
                if (copied != contentLength) throw new IOException("short Holo package download: " + copied);
            }
            verifySha256(partial, expectedSha256);
            if (!partial.renameTo(destination)) throw new IOException("unable to commit Holo package");
        }
        catch (IOException e) {
            FileUtils.delete(partial);
            throw e;
        }
        finally {
            connection.disconnect();
        }
    }

    private static void verifySha256(File file, String expected) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            if (!hex(digest.digest()).equals(expected)) {
                throw new IOException("Holo package SHA-256 mismatch: " + file.getName());
            }
        }
        catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest(bytes);
            return hex(result);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format("%02x", item));
        return value.toString();
    }

    public static final class PackageEntry {
        public final String repo;
        public final String name;
        public final String version;
        public final String sha256;
        public final String file;

        private PackageEntry(String repo, String name, String version, String sha256, String file) {
            this.repo = repo;
            this.name = name;
            this.version = version;
            this.sha256 = sha256;
            this.file = file;
        }
    }
}
