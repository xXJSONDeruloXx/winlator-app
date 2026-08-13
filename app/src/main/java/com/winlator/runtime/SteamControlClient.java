package com.winlator.runtime;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** A small framed client for the Android-only supervisor control socket. */
public final class SteamControlClient implements Closeable {
    private final String socketName;
    private final LocalSocketAddress.Namespace namespace;
    private final AtomicInteger requestId = new AtomicInteger(1);
    private LocalSocket socket;
    private DataInputStream input;
    private DataOutputStream output;

    public SteamControlClient(String socketName) {
        this(socketName, LocalSocketAddress.Namespace.ABSTRACT);
    }

    public SteamControlClient(String socketName, LocalSocketAddress.Namespace namespace) {
        this.socketName = socketName;
        this.namespace = namespace;
    }

    public synchronized void connect() throws IOException {
        if (socket != null && socket.isConnected()) return;

        socket = new LocalSocket();
        socket.connect(new LocalSocketAddress(socketName, namespace));
        input = new DataInputStream(socket.getInputStream());
        output = new DataOutputStream(socket.getOutputStream());
    }

    public synchronized Response request(short opcode) throws IOException {
        return request(opcode, new byte[0]);
    }

    public synchronized Response request(short opcode, byte[] payload) throws IOException {
        connect();
        try {
            if (payload == null) payload = new byte[0];
            if (payload.length > 1024 * 1024) throw new IOException("control payload too large");

            int id = requestId.getAndIncrement();
            output.writeInt(SteamControlProtocol.MAGIC);
            output.writeShort(SteamControlProtocol.VERSION);
            output.writeShort(opcode);
            output.writeInt(id);
            output.writeInt(payload.length);
            if (payload.length > 0) output.write(payload);
            output.flush();

            int magic = input.readInt();
            short version = input.readShort();
            short responseOpcode = input.readShort();
            int responseId = input.readInt();
            int status = input.readInt();
            int responseLength = input.readInt();

            if (magic != SteamControlProtocol.MAGIC || version != SteamControlProtocol.VERSION ||
                responseOpcode != opcode || responseId != id || responseLength < 0 || responseLength > 1024 * 1024) {
                throw new IOException("invalid supervisor response header");
            }

            byte[] responsePayload = new byte[responseLength];
            input.readFully(responsePayload);
            return new Response(status, responsePayload);
        }
        finally {
            // A request is a complete authenticated transaction. Closing the
            // stream lets the native supervisor return to its endpoint poll
            // loop and avoids reusing a peer that has already been reaped.
            close();
        }
    }

    @Override
    public synchronized void close() {
        if (socket != null) {
            try {
                socket.close();
            }
            catch (IOException ignored) {}
        }
        socket = null;
        input = null;
        output = null;
    }

    public static final class Response {
        public final int status;
        public final byte[] payload;

        private Response(int status, byte[] payload) {
            this.status = status;
            this.payload = payload;
        }

        public boolean isSuccess() {
            return status == 0;
        }

        public String payloadAsString() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }
}
