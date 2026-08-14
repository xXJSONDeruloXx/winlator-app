package com.winlator.runtime;

import android.os.Process;
import android.system.Os;

import com.winlator.core.FileUtils;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Dynamic Android identity and the guest passwd/group view derived from it. */
public final class SteamIdentity {
    private static final SecureRandom MACHINE_ID_RANDOM = new SecureRandom();

    public final int uid;
    public final int gid;
    public final List<Integer> supplementaryGroups;

    private SteamIdentity(int uid, int gid, List<Integer> supplementaryGroups) {
        this.uid = uid;
        this.gid = gid;
        this.supplementaryGroups = supplementaryGroups;
    }

    public static SteamIdentity capture() throws IOException {
        int uid = Process.myUid();
        int gid;
        try {
            gid = Os.getgid();
        }
        catch (Exception e) {
            throw new IOException("unable to read Android primary gid", e);
        }

        Set<Integer> groups = new LinkedHashSet<>();
        groups.add(gid);
        String status = FileUtils.readString(new File("/proc/self/status"));
        if (status == null) throw new IOException("unable to read Android supplementary groups");
        for (String line : status.split("\\n")) {
            if (!line.startsWith("Groups:")) continue;
            String[] values = line.substring("Groups:".length()).trim().split("\\s+");
            for (String value : values) {
                try {
                    groups.add(Integer.parseInt(value));
                }
                catch (NumberFormatException ignored) {}
            }
        }
        return new SteamIdentity(uid, gid, new ArrayList<>(groups));
    }

    public void writeGuestIdentityViews(File rootDir) throws IOException {
        File etc = new File(rootDir, "etc");
        if (!etc.isDirectory() && !etc.mkdirs()) throw new IOException("unable to create guest etc");

        StringBuilder passwd = new StringBuilder();
        passwd.append("root:x:0:0:root:/root:/bin/sh\n");
        // Preserve Steam's expected logical account name while mapping it to
        // the dynamic Android application UID/GID for this session.
        passwd.append("steam:x:").append(uid).append(':').append(gid)
            .append(":Steam:/home/steam:/bin/sh\n");
        // Holo's stock D-Bus system.conf asks dbus-daemon to drop to the
        // named dbus account. The minimal Holo release does not ship that
        // service account, so provide a session-local alias rather than
        // weakening the pinned configuration or mutating the release image.
        passwd.append("dbus:x:").append(uid).append(':').append(gid)
            .append(":D-Bus:/run/dbus:/usr/bin/nologin\n");
        if (!FileUtils.writeString(new File(etc, "passwd"), passwd.toString())) {
            throw new IOException("unable to write guest passwd view");
        }

        StringBuilder group = new StringBuilder();
        group.append("root:x:0:\n");
        group.append("steam:x:").append(gid).append(":steam\n");
        group.append("dbus:x:").append(gid).append(":dbus\n");
        for (int groupId : supplementaryGroups) {
            if (groupId == gid) continue;
            group.append("android_").append(groupId).append(":x:").append(groupId)
                .append(":steam\n");
        }
        if (!FileUtils.writeString(new File(etc, "group"), group.toString())) {
            throw new IOException("unable to write guest group view");
        }
        FileUtils.chmod(new File(etc, "passwd"), 0644);
        FileUtils.chmod(new File(etc, "group"), 0644);

        File machineIdFile = new File(etc, "machine-id");
        String machineId = machineIdFile.isFile() ? FileUtils.readString(machineIdFile) : null;
        if (machineId == null || !machineId.trim().matches("[0-9a-fA-F]{32}")) {
            byte[] bytes = new byte[16];
            MACHINE_ID_RANDOM.nextBytes(bytes);
            StringBuilder generated = new StringBuilder(32);
            for (byte value : bytes) generated.append(String.format("%02x", value & 0xff));
            machineId = generated.toString() + "\n";
            if (!FileUtils.writeString(machineIdFile, machineId)) {
                throw new IOException("unable to write guest machine-id");
            }
        }
        FileUtils.chmod(machineIdFile, 0644);

        File dbusDirectory = new File(rootDir, "var/lib/dbus");
        if (!dbusDirectory.isDirectory() && !dbusDirectory.mkdirs()) {
            throw new IOException("unable to create guest D-Bus state directory");
        }
        File dbusMachineId = new File(dbusDirectory, "machine-id");
        if (!dbusMachineId.exists() && !FileUtils.isSymlink(dbusMachineId)) {
            FileUtils.symlink("/etc/machine-id", dbusMachineId.getAbsolutePath());
        }
    }

    public String groupsCsv() {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < supplementaryGroups.size(); index++) {
            if (index > 0) result.append(',');
            result.append(supplementaryGroups.get(index));
        }
        return result.toString();
    }
}
