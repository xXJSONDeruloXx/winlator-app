package com.winlator.runtime;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Versioned metadata for Valve's public native ARM64 Steam channel. */
public final class SteamArm64Channel {
    private static final String ASSET = "steamdroid/steam-arm64-channel.properties";
    private static final Pattern PROPERTY = Pattern.compile("^([A-Za-z0-9]+)=(.*)$");

    public final String channel;
    public final String arch;
    public final String steamClientEndpoint;
    public final String steamClientPackage;
    public final String steamClientRoot;
    public final String steamClientExecutable;
    public final String holoRootfsEndpoint;
    public final String holoRootfsSha256;
    public final long holoRootfsDownloadBytes;
    public final String holoPackageBaseEndpoint;
    public final String holoPackageClosureSha256;
    public final String archLinuxArmLegacyPackageBaseEndpoint;
    public final int protonArm64AppId;
    public final int protonArm64DepotId;
    public final int runtimeArm64AppId;
    public final int runtimeArm64DepotId;
    public final String kgslDriverMesaRef;
    public final String kgslDriverSha256;
    public final long kgslDriverBytes;
    public final long freeSpaceReserveBytes;

    private SteamArm64Channel(Map<String, String> values) throws IOException {
        if (!"publicbeta".equals(values.get("channel")) ||
            !"linuxarm64".equals(values.get("arch"))) {
            throw new IOException("unsupported Steam ARM64 channel descriptor");
        }
        channel = values.get("channel");
        arch = values.get("arch");
        steamClientEndpoint = required(values, "steamClientEndpoint");
        steamClientPackage = required(values, "steamClientPackage");
        steamClientRoot = required(values, "steamClientRoot");
        steamClientExecutable = required(values, "steamClientExecutable");
        holoRootfsEndpoint = required(values, "holoRootfsEndpoint");
        holoRootfsSha256 = required(values, "holoRootfsSha256");
        holoRootfsDownloadBytes = number(values, "holoRootfsDownloadBytes");
        holoPackageBaseEndpoint = required(values, "holoPackageBaseEndpoint");
        holoPackageClosureSha256 = required(values, "holoPackageClosureSha256");
        archLinuxArmLegacyPackageBaseEndpoint = required(values, "archLinuxArmLegacyPackageBaseEndpoint");
        protonArm64AppId = (int)number(values, "protonArm64AppId");
        protonArm64DepotId = (int)number(values, "protonArm64DepotId");
        runtimeArm64AppId = (int)number(values, "runtimeArm64AppId");
        runtimeArm64DepotId = (int)number(values, "runtimeArm64DepotId");
        kgslDriverMesaRef = required(values, "kgslDriverMesaRef");
        kgslDriverSha256 = required(values, "kgslDriverSha256").toLowerCase();
        if (!kgslDriverSha256.matches("[0-9a-f]{64}")) {
            throw new IOException("invalid KGSL driver SHA-256");
        }
        kgslDriverBytes = number(values, "kgslDriverBytes");
        freeSpaceReserveBytes = number(values, "freeSpaceReserveBytes");
    }

    public static SteamArm64Channel load(Context context) throws IOException {
        Map<String, String> values = new HashMap<>();
        try (InputStream input = context.getAssets().open(ASSET)) {
            String text = new String(readAll(input), StandardCharsets.UTF_8);
            for (String line : text.split("\\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                Matcher matcher = PROPERTY.matcher(line);
                if (!matcher.matches()) throw new IOException("invalid channel descriptor line");
                values.put(matcher.group(1), matcher.group(2));
            }
        }
        if (!"1".equals(values.get("manifestVersion"))) {
            throw new IOException("unsupported channel descriptor version");
        }
        return new SteamArm64Channel(values);
    }

    /** Validates the installed live Proton tool manifest, never just an AppID guess. */
    public void validateProtonToolManifest(String manifest) throws IOException {
        requireManifestValue(manifest, "appid", protonArm64AppId);
        requireManifestValue(manifest, "depotid", protonArm64DepotId);
        requireManifestValue(manifest, "require_tool_appid", runtimeArm64AppId);
    }

    public void validateRuntimeToolManifest(String manifest) throws IOException {
        requireManifestValue(manifest, "appid", runtimeArm64AppId);
        requireManifestValue(manifest, "depotid", runtimeArm64DepotId);
    }

    private static void requireManifestValue(String manifest, String key, int expected) throws IOException {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s+\\\"([0-9]+)\\\"");
        Matcher matcher = pattern.matcher(manifest);
        if (!matcher.find() || Integer.parseInt(matcher.group(1)) != expected) {
            throw new IOException("unvalidated ARM64 tool manifest field: " + key);
        }
    }

    private static String required(Map<String, String> values, String key) throws IOException {
        String value = values.get(key);
        if (value == null || value.isEmpty()) throw new IOException("missing channel field: " + key);
        return value;
    }

    private static long number(Map<String, String> values, String key) throws IOException {
        try {
            return Long.parseLong(required(values, key));
        }
        catch (NumberFormatException e) {
            throw new IOException("invalid channel number: " + key, e);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
        return output.toByteArray();
    }
}
