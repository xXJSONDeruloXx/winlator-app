package com.winlator.runtime;

import android.os.StatFs;

import java.io.File;
import java.io.IOException;

/** Transactional free-space gate for downloads followed by unpacking. */
public final class StorageBudget {
    private StorageBudget() {}

    public static long availableBytes(File path) {
        StatFs statFs = new StatFs(path.getAbsolutePath());
        return statFs.getAvailableBlocksLong() * statFs.getBlockSizeLong();
    }

    public static void require(File path, long downloadBytes, long unpackedBytes,
                               long reserveBytes) throws IOException {
        if (downloadBytes < 0 || unpackedBytes < 0 || reserveBytes < 0) {
            throw new IOException("negative storage budget");
        }
        long required;
        try {
            required = Math.addExact(Math.addExact(downloadBytes, unpackedBytes), reserveBytes);
        }
        catch (ArithmeticException e) {
            throw new IOException("storage budget overflow", e);
        }
        long available = availableBytes(path);
        if (available < required) {
            throw new IOException("insufficient storage: available=" + available + " required=" + required);
        }
    }
}
