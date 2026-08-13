package com.winlator.runtime;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Length-delimited argv transport for a native ARM64 process launch. */
public final class SteamNativeExecRequest {
    private static final int MAGIC = 0x53444531; // SDE1
    private static final int VERSION = 1;
    private static final int MAX_ARGUMENTS = 4096;
    private static final int MAX_ARGUMENT_BYTES = 64 * 1024;

    private SteamNativeExecRequest() {}

    public static byte[] encode(List<String> arguments) throws IOException {
        if (arguments == null || arguments.isEmpty() || arguments.size() > MAX_ARGUMENTS) {
            throw new IOException("invalid native Steam argv");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeInt(arguments.size());
        int total = 0;
        for (String argument : arguments) {
            if (argument == null) throw new IOException("null native Steam argument");
            byte[] encoded = argument.getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_ARGUMENT_BYTES) throw new IOException("native Steam argument too long");
            total = Math.addExact(total, encoded.length);
            if (total > 1024 * 1024) throw new IOException("native Steam argv too large");
            output.writeInt(encoded.length);
            output.write(encoded);
        }
        output.flush();
        return bytes.toByteArray();
    }
}
