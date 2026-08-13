package com.winlator.xserver.requests;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.graphics.Color;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadName;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;
import java.util.Locale;

public abstract class ColorRequests {
    public static void allocNamedColor(XClient client, XInputStream inputStream,
                                       XOutputStream outputStream)
        throws IOException, XRequestError {
        inputStream.readInt(); // the embedded server exposes one TrueColor map
        int nameLength = inputStream.readUnsignedShort();
        inputStream.skip(2);
        String name = inputStream.readString8(nameLength);
        int rgb = parseColor(name);

        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        int pixel = (red << 16) | (green << 8) | blue;
        short exactRed = (short)(red * 257);
        short exactGreen = (short)(green * 257);
        short exactBlue = (short)(blue * 257);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(pixel);
            outputStream.writeShort(exactRed);
            outputStream.writeShort(exactGreen);
            outputStream.writeShort(exactBlue);
            outputStream.writeShort(exactRed);
            outputStream.writeShort(exactGreen);
            outputStream.writeShort(exactBlue);
            outputStream.writePad(8);
        }
    }

    private static int parseColor(String source) throws BadName {
        String value = source.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("rgb:")) {
            String[] components = value.substring(4).split("/", -1);
            if (components.length == 3) {
                int red = parseComponent(components[0]);
                int green = parseComponent(components[1]);
                int blue = parseComponent(components[2]);
                if (red >= 0 && green >= 0 && blue >= 0) {
                    return (red << 16) | (green << 8) | blue;
                }
            }
            throw new BadName();
        }

        if (value.startsWith("#")) {
            String digits = value.substring(1);
            try {
                if (digits.length() == 3) {
                    int red = Integer.parseInt(digits.substring(0, 1), 16) * 17;
                    int green = Integer.parseInt(digits.substring(1, 2), 16) * 17;
                    int blue = Integer.parseInt(digits.substring(2, 3), 16) * 17;
                    return (red << 16) | (green << 8) | blue;
                }
                if (digits.length() == 6) return Integer.parseInt(digits, 16);
                if (digits.length() == 12) {
                    int red = Integer.parseInt(digits.substring(0, 4), 16) >>> 8;
                    int green = Integer.parseInt(digits.substring(4, 8), 16) >>> 8;
                    int blue = Integer.parseInt(digits.substring(8, 12), 16) >>> 8;
                    return (red << 16) | (green << 8) | blue;
                }
            }
            catch (NumberFormatException ignored) {
                // Fall through to the protocol's BadName error.
            }
            throw new BadName();
        }

        try {
            return Color.parseColor(value) & 0x00ffffff;
        }
        catch (IllegalArgumentException e) {
            throw new BadName();
        }
    }

    private static int parseComponent(String value) {
        try {
            int parsed = Integer.parseInt(value, 16);
            if (value.length() == 1) return parsed * 17;
            if (value.length() == 2) return parsed;
            if (value.length() == 3) return parsed >>> 4;
            if (value.length() == 4) return parsed >>> 8;
        }
        catch (NumberFormatException ignored) {
            // Caller converts a negative component into BadName.
        }
        return -1;
    }
}
