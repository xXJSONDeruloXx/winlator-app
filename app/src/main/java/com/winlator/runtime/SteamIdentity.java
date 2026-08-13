package com.winlator.runtime;

import android.os.Process;
import android.system.Os;

import com.winlator.core.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Dynamic Android identity and the guest passwd/group view derived from it. */
public final class SteamIdentity {
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
        if (!FileUtils.writeString(new File(etc, "passwd"), passwd.toString())) {
            throw new IOException("unable to write guest passwd view");
        }

        StringBuilder group = new StringBuilder();
        group.append("root:x:0:\n");
        group.append("steam:x:").append(gid).append(":steam\n");
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
