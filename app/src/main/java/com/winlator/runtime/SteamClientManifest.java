package com.winlator.runtime;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The small subset of Valve's client VDF needed to seed the native client. */
public final class SteamClientManifest {
    private static final Pattern VERSION = Pattern.compile("\\\"version\\\"\\s+\\\"([0-9]+)\\\"");

    public final String architecture;
    public final long version;
    public final PackageEntry clientPackage;

    private SteamClientManifest(String architecture, long version, PackageEntry clientPackage) {
        this.architecture = architecture;
        this.version = version;
        this.clientPackage = clientPackage;
    }

    public static SteamClientManifest parse(String text, String packageName) throws IOException {
        if (text == null || text.length() > 4 * 1024 * 1024) {
            throw new IOException("Steam client manifest is missing or too large");
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("\"linuxarm64\"")) {
            throw new IOException("Steam client manifest architecture is not linuxarm64");
        }
        Matcher versionMatcher = VERSION.matcher(text);
        if (!versionMatcher.find()) throw new IOException("Steam client manifest has no version");
        long version;
        try {
            version = Long.parseLong(versionMatcher.group(1));
        }
        catch (NumberFormatException e) {
            throw new IOException("invalid Steam client manifest version", e);
        }

        String block = findPackageBlock(text, packageName);
        String file = value(block, "file");
        long size = number(block, "size");
        String sha256 = value(block, "sha2");
        if (file.indexOf('/') >= 0 || file.indexOf('\\') >= 0 || file.indexOf('\0') >= 0) {
            throw new IOException("Steam client package filename is not a basename");
        }
        if (!sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("invalid Steam client package SHA-256");
        }
        return new SteamClientManifest("linuxarm64", version,
            new PackageEntry(packageName, file, size, sha256.toLowerCase()));
    }

    private static String findPackageBlock(String text, String packageName) throws IOException {
        if (packageName == null || packageName.isEmpty() ||
            packageName.indexOf('"') >= 0 || packageName.indexOf('\\') >= 0) {
            throw new IOException("invalid Steam client package name");
        }
        Pattern pattern = Pattern.compile("(?s)\\\"" + Pattern.quote(packageName) +
            "\\\"\\s*\\{(.*?)\\n\\s*\\}");
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) throw new IOException("Steam client package is absent from live manifest: " + packageName);
        return matcher.group(1);
    }

    private static String value(String block, String key) throws IOException {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s+\\\"([^\\\"]*)\\\"");
        Matcher matcher = pattern.matcher(block);
        if (!matcher.find() || matcher.group(1).isEmpty()) {
            throw new IOException("Steam client manifest package field is missing: " + key);
        }
        return matcher.group(1);
    }

    private static long number(String block, String key) throws IOException {
        try {
            long value = Long.parseLong(value(block, key));
            if (value <= 0) throw new NumberFormatException("not positive");
            return value;
        }
        catch (NumberFormatException e) {
            throw new IOException("invalid Steam client manifest package number: " + key, e);
        }
    }

    public static final class PackageEntry {
        public final String name;
        public final String file;
        public final long size;
        public final String sha256;

        private PackageEntry(String name, String file, long size, String sha256) {
            this.name = name;
            this.file = file;
            this.size = size;
            this.sha256 = sha256;
        }
    }
}
