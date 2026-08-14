package com.winlator.runtime;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import com.winlator.core.FileUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Seeds only Valve's native ARM64 Steam client; Steam owns later updates/tools. */
public final class SteamClientProvisioner {
    private static final String SYSV_SEM_SHIM_ASSET =
        "steamdroid/libsteamdroid_sysv_sem_shim.so";
    private static final String SYSV_SEM_SHIM_RELATIVE_PATH =
        "steamdroid/libsteamdroid_sysv_sem_shim.so";
    private static final String LSOF_ASSET = "steamdroid/lsof";
    private static final String LSOF_RELATIVE_PATH = "steamdroid/bin/lsof";
    private static final long MAX_MANIFEST_BYTES = 4L * 1024L * 1024L;
    private static final long MAX_ENTRY_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_UNPACKED_BYTES = 1024L * 1024L * 1024L;
    private static final long MAX_COMPAT_LIBRARY_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 4096;

    private final Context context;
    private final SteamArm64Channel channel;
    private final File holoRoot;
    private final File steamRoot;

    public SteamClientProvisioner(Context context) throws IOException {
        this.context = context.getApplicationContext();
        channel = SteamArm64Channel.load(this.context);
        holoRoot = new File(this.context.getFilesDir(), "steamdroid/holo-rootfs");
        steamRoot = new File(holoRoot, "home/steam/.local/share/Steam");
    }

    public boolean isInstalled() {
        File executable = new File(holoRoot, "home/steam/.local/share/Steam/" +
            channel.steamClientExecutable);
        return executable.isFile() && executable.canExecute() &&
            new File(steamRoot, channel.steamClientRoot + "/steamui.so").isFile() &&
            new File(steamRoot, channel.steamClientRoot + "/steamwebhelper").isFile();
    }

    public File getExecutable() {
        return new File(holoRoot, "home/steam/.local/share/Steam/" + channel.steamClientExecutable);
    }

    /**
     * Valve writes a channel-specific marker after the bootstrapper has
     * installed the live client. Keep the marker discovery generic because
     * the public ARM64 endpoint and the installed beta marker do not
     * necessarily use the same channel spelling.
     */
    public boolean hasCompletedBootstrap() {
        File[] markers = new File(steamRoot, "package").listFiles((directory, name) ->
            name.startsWith("steam_client_") && name.endsWith(".installed"));
        return markers != null && markers.length > 0;
    }

    public void downloadAndInstall(SteamIdentity identity) throws IOException {
        if (!new File(holoRoot, "usr/lib/ld-linux-aarch64.so.1").isFile()) {
            throw new IOException("Holo ARM64 rootfs is not installed");
        }
        if (isInstalled()) {
            identity.writeGuestIdentityViews(holoRoot);
            ensureSteamHomeLayout();
            ensureCompatibilityAssets();
            return;
        }

        File downloads = new File(context.getFilesDir(), "steamdroid/downloads");
        if (!downloads.isDirectory() && !downloads.mkdirs()) {
            throw new IOException("unable to create Steam client download cache");
        }

        File manifestFile = new File(downloads, "steam_client.manifest");
        downloadFile(channel.steamClientEndpoint, manifestFile, MAX_MANIFEST_BYTES, -1);
        String manifestText = readUtf8(manifestFile, MAX_MANIFEST_BYTES);
        SteamClientManifest manifest = SteamClientManifest.parse(manifestText, channel.steamClientPackage);
        SteamClientManifest.PackageEntry packageEntry = manifest.clientPackage;

        StorageBudget.require(downloads, packageEntry.size, packageEntry.size * 4L,
            channel.freeSpaceReserveBytes);
        File archive = new File(downloads, packageEntry.file);
        if (archive.isFile()) {
            verifySha256(archive, packageEntry.sha256);
            if (archive.length() != packageEntry.size) {
                throw new IOException("cached Steam client package has the wrong size");
            }
        }
        else {
            downloadFile("https://client-update.steamstatic.com/" + packageEntry.file,
                archive, packageEntry.size, packageEntry.size);
            verifySha256(archive, packageEntry.sha256);
        }

        extractPackage(archive, packageEntry, identity);
    }

    private void extractPackage(File archive, SteamClientManifest.PackageEntry packageEntry,
                                SteamIdentity identity) throws IOException {
        if (steamRoot.exists() && !steamRoot.isDirectory()) {
            throw new IOException("Steam root is not a directory");
        }
        if (!steamRoot.exists() && !steamRoot.mkdirs()) {
            throw new IOException("unable to create Steam root");
        }
        File stage = new File(steamRoot, channel.steamClientRoot + ".partial");
        if (stage.exists()) throw new IOException("refusing to reuse incomplete Steam client staging");
        if (!stage.mkdirs()) throw new IOException("unable to create Steam client staging");

        long unpackedBytes = 0;
        int entryCount = 0;
        try (ZipFile zip = new ZipFile(archive)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++entryCount > MAX_ENTRIES) throw new IOException("Steam client package has too many entries");
                String rawName = entry.getName();
                boolean directory = rawName.endsWith("/") || rawName.endsWith("\\");
                String normalized = normalizeEntryName(rawName);
                String prefix = channel.steamClientRoot + "/";
                if (!normalized.equals(channel.steamClientRoot) && !normalized.startsWith(prefix)) {
                    throw new IOException("Steam client package contains an unexpected root: " + rawName);
                }
                if (normalized.equals(channel.steamClientRoot)) continue;
                String relative = normalized.substring(prefix.length());
                File destination = resolveSafe(stage, relative);
                if (directory) {
                    if (!destination.isDirectory() && !destination.mkdirs()) {
                        throw new IOException("unable to create Steam client directory: " + relative);
                    }
                    chmod(destination, 0755);
                    continue;
                }

                long entrySize = entry.getSize();
                if (entrySize < 0 || entrySize > MAX_ENTRY_BYTES ||
                    unpackedBytes > MAX_UNPACKED_BYTES - entrySize) {
                    throw new IOException("Steam client package entry is too large: " + relative);
                }
                unpackedBytes += entrySize;
                File parent = destination.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("unable to create Steam client parent: " + relative);
                }

                if (rawName.indexOf('\\') >= 0) {
                    String linkTarget = readSymlinkTarget(zip, entry);
                    validateRelativeLink(linkTarget);
                    if (destination.exists() || FileUtils.isSymlink(destination)) destination.delete();
                    try {
                        Os.symlink(linkTarget, destination.getPath());
                    }
                    catch (ErrnoException e) {
                        throw new IOException("unable to create Steam client symlink: " + relative, e);
                    }
                }
                else {
                    try (InputStream input = new BufferedInputStream(zip.getInputStream(entry));
                         java.io.OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                        copy(input, output, entrySize);
                    }
                    chmod(destination, isExecutable(relative) ? 0755 : 0644);
                }
            }
        }
        catch (IOException e) {
            FileUtils.delete(stage);
            throw e;
        }

        File executable = new File(stage, "steam");
        if (!executable.isFile()) {
            FileUtils.delete(stage);
            throw new IOException("Steam client package has no native steam executable");
        }
        chmod(executable, 0755);
        File installed = new File(steamRoot, channel.steamClientRoot);
        if (installed.exists()) {
            FileUtils.delete(stage);
            throw new IOException("Steam client root already appeared during installation");
        }
        if (!stage.renameTo(installed)) {
            FileUtils.delete(stage);
            throw new IOException("unable to commit Steam client root");
        }
        ensureSteamHomeLayout();
        ensureCompatibilityAssets();
        identity.writeGuestIdentityViews(holoRoot);
    }

    public File getSysvSemaphoreShim() {
        return new File(steamRoot, SYSV_SEM_SHIM_RELATIVE_PATH);
    }

    /**
     * Installs the narrowly scoped glibc preload used for Steam's missing
     * System V semaphore syscalls. It lives in the app-owned Steam home and
     * is only injected by the native supervisor into the Steam process tree.
     */
    public void ensureCompatibilityAssets() throws IOException {
        // Launch can be requested after the Holo substrate was provisioned in
        // an earlier app process. Refresh the guest-facing names on every
        // launch so the numeric Android identity and Steam's logical `steam`
        // account cannot drift apart.
        SteamIdentity.capture().writeGuestIdentityViews(holoRoot);
        ensureSteamHomeLayout();
        stageAsset(SYSV_SEM_SHIM_ASSET, SYSV_SEM_SHIM_RELATIVE_PATH, 0755,
            MAX_COMPAT_LIBRARY_BYTES);
        stageAsset(LSOF_ASSET, LSOF_RELATIVE_PATH, 0755, 64 * 1024);
    }

    private void stageAsset(String assetName, String relativePath, int mode, long maxBytes)
        throws IOException {
        File target = new File(steamRoot, relativePath);
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("unable to create Steam compatibility directory");
        }
        File partial = new File(target.getPath() + ".partial");
        if (partial.exists()) throw new IOException("refusing to reuse partial Steam compatibility asset");
        try (InputStream input = context.getAssets().open(assetName);
             java.io.OutputStream output = new BufferedOutputStream(new FileOutputStream(partial))) {
            byte[] buffer = new byte[64 * 1024];
            long copied = 0;
            int length;
            while ((length = input.read(buffer)) != -1) {
                copied += length;
                if (copied > maxBytes) throw new IOException("Steam compatibility asset is too large");
                output.write(buffer, 0, length);
            }
            if (copied == 0) throw new IOException("Steam compatibility asset is empty");
        }
        chmod(partial, mode);
        if (target.exists() && !FileUtils.delete(target)) {
            FileUtils.delete(partial);
            throw new IOException("unable to replace Steam compatibility asset");
        }
        if (!partial.renameTo(target)) {
            FileUtils.delete(partial);
            throw new IOException("unable to commit Steam compatibility asset");
        }
    }

    private void ensureSteamHomeLayout() throws IOException {
        File home = new File(holoRoot, "home/steam");
        File dotSteam = new File(home, ".steam");
        File packageDir = new File(steamRoot, "package");
        if (!home.isDirectory() && !home.mkdirs()) throw new IOException("unable to create Steam home");
        if (!dotSteam.isDirectory() && !dotSteam.mkdirs()) throw new IOException("unable to create Steam dot directory");
        if (!packageDir.isDirectory() && !packageDir.mkdirs()) throw new IOException("unable to create Steam package directory");
        writeText(new File(packageDir, "beta"), "steamdeck_publicbeta\n");
        createRelativeSymlink(dotSteam, "steam", "../.local/share/Steam");
        createRelativeSymlink(dotSteam, "root", "../.local/share/Steam");
        createRelativeSymlink(dotSteam, "sdk32", "../.local/share/Steam/linux32");
        createRelativeSymlink(dotSteam, "sdk64", "../.local/share/Steam/linux64");
        createRelativeSymlink(dotSteam, "sdkarm64", "../.local/share/Steam/linuxarm64");
        // The ARM client resolves its traditional bin links through the
        // Steam Runtime trees. Pointing these at ubuntu12_* silently selects
        // the legacy x86 CEF/helper payload and leaves the native ARM client
        // with no usable SteamUI handoff.
        createRelativeSymlink(dotSteam, "bin32", "../.local/share/Steam/steamrt32");
        createRelativeSymlink(dotSteam, "bin64", "../.local/share/Steam/steamrt64");
        createRelativeSymlink(dotSteam, "steamrtarm64", "../.local/share/Steam/steamrtarm64");
        createRelativeSymlink(dotSteam, "steamrtarm32", "../.local/share/Steam/steamrtarm32");
        createRelativeSymlink(steamRoot, "steamrtarm32", "steamrtarm64");
    }

    private static boolean isExecutable(String relative) {
        String name = new File(relative).getName();
        return name.equals("steam") || name.equals("steam_monitor") || name.equals("steamwebhelper") ||
            name.endsWith(".sh") || name.equals("gldriverquery") || name.equals("vulkandriverquery") ||
            name.equals("reaper") || name.equals("steamerrorreporter") || name.equals("gameoverlayui") ||
            name.equals("fossilize_replay") || name.equals("streaming_client") || name.equals("vgui_panel_zoo");
    }

    private static String normalizeEntryName(String rawName) throws IOException {
        if (rawName == null || rawName.length() == 0 || rawName.length() > 4096 || rawName.indexOf('\0') >= 0) {
            throw new IOException("invalid Steam client package entry name");
        }
        String normalized = rawName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":") || normalized.contains("//")) {
            throw new IOException("unsafe Steam client package entry name: " + rawName);
        }
        String[] components = normalized.split("/");
        StringBuilder result = new StringBuilder();
        for (String component : components) {
            if (component.isEmpty() || component.equals(".")) continue;
            if (component.equals("..")) throw new IOException("Steam client package path traversal");
            if (result.length() > 0) result.append('/');
            result.append(component);
        }
        if (result.length() == 0) throw new IOException("empty Steam client package entry name");
        return result.toString();
    }

    private static File resolveSafe(File root, String relative) throws IOException {
        File candidate = new File(root, relative).getCanonicalFile();
        String prefix = root.getCanonicalPath() + File.separator;
        if (!candidate.getPath().startsWith(prefix)) throw new IOException("Steam client path escaped staging");
        return candidate;
    }

    private static String readSymlinkTarget(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(128);
            byte[] buffer = new byte[128];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (output.size() + length > 4096) throw new IOException("Steam client symlink target is too long");
                output.write(buffer, 0, length);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void validateRelativeLink(String target) throws IOException {
        if (target.isEmpty() || target.startsWith("/") || target.indexOf('\0') >= 0 ||
            target.indexOf('\\') >= 0 || target.contains("..")) {
            throw new IOException("unsafe Steam client symlink target");
        }
    }

    private static void createRelativeSymlink(File parent, String name, String target) throws IOException {
        File link = new File(parent, name);
        if (FileUtils.isSymlink(link)) {
            try {
                if (target.equals(Os.readlink(link.getPath()))) return;
            }
            catch (ErrnoException e) {
                throw new IOException("unable to inspect Steam home symlink: " + name, e);
            }
            // These links are app-owned compatibility layout, not user
            // content. Replace only the exact existing symlink; never remove
            // a regular file or directory that Steam may have created.
            if (!link.delete()) throw new IOException("unable to replace Steam home symlink: " + name);
        }
        else if (link.exists()) {
            return;
        }
        try {
            Os.symlink(target, link.getPath());
        }
        catch (ErrnoException e) {
            throw new IOException("unable to create Steam home symlink: " + name, e);
        }
    }

    private static void chmod(File file, int mode) throws IOException {
        try {
            Os.chmod(file.getPath(), mode);
        }
        catch (ErrnoException e) {
            throw new IOException("unable to chmod Steam client path: " + file, e);
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (java.io.Writer writer = new java.io.OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(text);
        }
        chmod(file, 0644);
    }

    private static void copy(InputStream input, java.io.OutputStream output, long expectedSize) throws IOException {
        byte[] buffer = new byte[1024 * 1024];
        long copied = 0;
        int length;
        while ((length = input.read(buffer)) != -1) {
            copied += length;
            if (copied > expectedSize) throw new IOException("Steam client package entry exceeded declared size");
            output.write(buffer, 0, length);
        }
        if (copied != expectedSize) throw new IOException("Steam client package entry was truncated");
    }

    private static String readUtf8(File file, long maxBytes) throws IOException {
        if (file.length() > maxBytes) throw new IOException("Steam client manifest is too large");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int)file.length());
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (output.size() + length > maxBytes) throw new IOException("Steam client manifest is too large");
                output.write(buffer, 0, length);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void downloadFile(String url, File destination, long maxBytes, long expectedBytes) throws IOException {
        File partial = new File(destination.getPath() + ".partial");
        if (partial.exists()) throw new IOException("refusing to reuse partial Steam download: " + partial.getName());
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        int responseCode = connection.getResponseCode();
        long contentLength = connection.getContentLengthLong();
        if (responseCode != HttpURLConnection.HTTP_OK || contentLength <= 0 || contentLength > maxBytes ||
            (expectedBytes > 0 && contentLength != expectedBytes)) {
            connection.disconnect();
            throw new IOException("unvalidated Steam download response: code=" + responseCode +
                " length=" + contentLength + " url=" + url);
        }
        try {
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 java.io.OutputStream output = new BufferedOutputStream(new FileOutputStream(partial))) {
                byte[] buffer = new byte[1024 * 1024];
                long copied = 0;
                int length;
                while ((length = input.read(buffer)) != -1) {
                    copied += length;
                    if (copied > contentLength) throw new IOException("Steam download exceeded Content-Length");
                    output.write(buffer, 0, length);
                }
                if (copied != contentLength) throw new IOException("short Steam download: " + copied);
            }
            if (!partial.renameTo(destination)) throw new IOException("unable to commit Steam download");
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
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[1024 * 1024];
                int length;
                while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            }
            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest.digest()) actual.append(String.format("%02x", value));
            if (!expected.equals(actual.toString())) throw new IOException("Steam client package SHA-256 mismatch: " + actual);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }
}
