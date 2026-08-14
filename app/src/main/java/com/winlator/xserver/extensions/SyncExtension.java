package com.winlator.xserver.extensions;

import android.util.SparseBooleanArray;
import android.util.SparseLongArray;
import android.util.SparseArray;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadAlarm;
import com.winlator.xserver.errors.BadCounter;
import com.winlator.xserver.errors.BadFence;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

public class SyncExtension extends Extension {
    public static final int MAJOR_VERSION = 3;
    public static final int MINOR_VERSION = 1;
    private final SparseLongArray counters = new SparseLongArray();
    private final SparseArray<XClient> counterOwners = new SparseArray<>();
    private final SparseBooleanArray alarms = new SparseBooleanArray();
    private final SparseArray<XClient> alarmOwners = new SparseArray<>();
    private final SparseBooleanArray fences = new SparseBooleanArray();
    private final SparseArray<XClient> fenceOwners = new SparseArray<>();
    private final java.util.HashSet<XClient> registeredClients = new java.util.HashSet<>();

    private static abstract class ClientOpcodes {
        private static final byte INITIALIZE = 0;
        private static final byte LIST_SYSTEM_COUNTERS = 1;
        private static final byte CREATE_COUNTER = 2;
        private static final byte SET_COUNTER = 3;
        private static final byte CHANGE_COUNTER = 4;
        private static final byte QUERY_COUNTER = 5;
        private static final byte DESTROY_COUNTER = 6;
        private static final byte AWAIT = 7;
        private static final byte CREATE_ALARM = 8;
        private static final byte CHANGE_ALARM = 9;
        private static final byte QUERY_ALARM = 10;
        private static final byte DESTROY_ALARM = 11;
        private static final byte CREATE_FENCE = 14;
        private static final byte TRIGGER_FENCE = 15;
        private static final byte RESET_FENCE = 16;
        private static final byte DESTROY_FENCE = 17;
        private static final byte AWAIT_FENCE = 19;
    }

    public SyncExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "SYNC";
    }

    @Override
    public byte getFirstErrorId() {
        return Byte.MIN_VALUE;
    }

    public void setTriggered(int id) {
        synchronized (fences) {
            if (fences.indexOfKey(id) >= 0) fences.put(id, true);
        }
    }

    /**
     * SYNC resources use normal X client resource IDs. Keep their ownership
     * here because they are not represented by the drawable resource
     * managers, and release them when the owning connection disappears.
     * Otherwise a crashed CEF/helper client can leave a counter behind and
     * make the next Steam connection fail CreateCounter with BadIDChoice.
     */
    private void registerClient(XClient client) {
        synchronized (registeredClients) {
            if (!registeredClients.add(client)) return;
            client.addOnDestroyListener(thisClient -> releaseClient(thisClient));
        }
    }

    private void releaseClient(XClient client) {
        synchronized (counters) {
            for (int index = counters.size() - 1; index >= 0; index--) {
                int id = counters.keyAt(index);
                if (counterOwners.get(id) == client) {
                    counters.removeAt(index);
                    counterOwners.remove(id);
                }
            }
        }
        synchronized (alarms) {
            for (int index = alarms.size() - 1; index >= 0; index--) {
                int id = alarms.keyAt(index);
                if (alarmOwners.get(id) == client) {
                    alarms.delete(id);
                    alarmOwners.remove(id);
                }
            }
        }
        synchronized (fences) {
            for (int index = fences.size() - 1; index >= 0; index--) {
                int id = fences.keyAt(index);
                if (fenceOwners.get(id) == client) {
                    fences.delete(id);
                    fenceOwners.remove(id);
                }
            }
        }
        synchronized (registeredClients) {
            registeredClients.remove(client);
        }
    }

    private void initialize(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException {
        int requestedMajor = inputStream.readByte() & 0xff;
        int requestedMinor = inputStream.readByte() & 0xff;
        inputStream.skip(2);

        int major = Math.min(requestedMajor, MAJOR_VERSION);
        int minor = requestedMajor == MAJOR_VERSION
            ? Math.min(requestedMinor, MINOR_VERSION)
            : requestedMajor < MAJOR_VERSION ? requestedMinor : MINOR_VERSION;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte)1);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeByte((byte)major);
            outputStream.writeByte((byte)minor);
            outputStream.writePad(22);
        }
    }

    private void listSystemCounters(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte)1);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writePad(20);
        }
    }

    private void createCounter(XClient client, XInputStream inputStream) throws IOException, XRequestError {
        synchronized (counters) {
            int id = inputStream.readInt();
            long value = inputStream.readLong();
            if (!client.isValidResourceId(id) || counters.indexOfKey(id) >= 0) {
                throw new BadIdChoice(id);
            }
            registerClient(client);
            counters.put(id, value);
            counterOwners.put(id, client);
        }
    }

    private long requireCounter(int id) throws BadCounter {
        if (counters.indexOfKey(id) < 0) throw new BadCounter(id);
        return counters.get(id);
    }

    private void requireCounterOwner(XClient client, int id) throws BadCounter {
        requireCounter(id);
        if (counterOwners.get(id) != client) throw new BadCounter(id);
    }

    private void setCounter(XClient client, XInputStream inputStream) throws IOException, XRequestError {
        synchronized (counters) {
            int id = inputStream.readInt();
            requireCounterOwner(client, id);
            counters.put(id, inputStream.readLong());
        }
    }

    private void changeCounter(XClient client, XInputStream inputStream) throws IOException, XRequestError {
        synchronized (counters) {
            int id = inputStream.readInt();
            requireCounterOwner(client, id);
            long current = counters.get(id);
            counters.put(id, current + inputStream.readLong());
        }
    }

    private void queryCounter(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        synchronized (counters) {
            int id = inputStream.readInt();
            requireCounterOwner(client, id);
            long value = counters.get(id);

            try (XStreamLock lock = outputStream.lock()) {
                outputStream.writeByte((byte)1);
                outputStream.writeByte((byte)0);
                outputStream.writeShort(client.getSequenceNumber());
                outputStream.writeInt(0);
                outputStream.writeLong(value);
                outputStream.writePad(16);
            }
        }
    }

    private void destroyCounter(XClient client, XInputStream inputStream, XOutputStream outputStream)
        throws IOException, XRequestError {
        synchronized (counters) {
            int id = inputStream.readInt();
            requireCounterOwner(client, id);
            long value = counters.get(id);
            counters.delete(id);
            counterOwners.remove(id);

            try (XStreamLock lock = outputStream.lock()) {
                outputStream.writeByte((byte)1);
                outputStream.writeByte((byte)0);
                outputStream.writeShort(client.getSequenceNumber());
                outputStream.writeInt(0);
                outputStream.writeLong(value);
                outputStream.writePad(16);
            }
        }
    }

    private static void skipAlarmValues(XInputStream inputStream, int mask) {
        if ((mask & 1) != 0) inputStream.skip(4); // counter
        if ((mask & 2) != 0) inputStream.skip(4); // value type
        if ((mask & 4) != 0) inputStream.skip(8); // wait value
        if ((mask & 8) != 0) inputStream.skip(4); // test type
        if ((mask & 16) != 0) inputStream.skip(8); // delta
        if ((mask & 32) != 0) inputStream.skip(4); // events
    }

    private void createAlarm(XClient client, XInputStream inputStream) throws IOException, XRequestError {
        synchronized (alarms) {
            int id = inputStream.readInt();
            int mask = inputStream.readInt();
            if (!client.isValidResourceId(id) || alarms.indexOfKey(id) >= 0) throw new BadIdChoice(id);
            registerClient(client);
            skipAlarmValues(inputStream, mask);
            alarms.put(id, true);
            alarmOwners.put(id, client);
        }
    }

    private void changeAlarm(XClient client, XInputStream inputStream) throws IOException, XRequestError {
        synchronized (alarms) {
            int id = inputStream.readInt();
            int mask = inputStream.readInt();
            if (alarms.indexOfKey(id) < 0 || alarmOwners.get(id) != client) throw new BadAlarm(id);
            skipAlarmValues(inputStream, mask);
        }
    }

    private void destroyAlarm(XClient client, XInputStream inputStream) throws IOException, XRequestError {
        synchronized (alarms) {
            int id = inputStream.readInt();
            if (alarms.indexOfKey(id) < 0 || alarmOwners.get(id) != client) throw new BadAlarm(id);
            alarms.delete(id);
            alarmOwners.remove(id);
        }
    }

    private void createFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            inputStream.skip(4);
            int id = inputStream.readInt();

            if (!client.isValidResourceId(id) || fences.indexOfKey(id) >= 0) throw new BadIdChoice(id);

            boolean initiallyTriggered = inputStream.readByte() == 1;
            inputStream.skip(3);

            registerClient(client);
            fences.put(id, initiallyTriggered);
            fenceOwners.put(id, client);
        }
    }

    private void triggerFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0 || fenceOwners.get(id) != client) throw new BadFence(id);
            fences.put(id, true);
        }
    }

    private void resetFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0 || fenceOwners.get(id) != client) throw new BadFence(id);

            boolean triggered = fences.get(id);
            if (!triggered) throw new BadMatch();

            fences.put(id, false);
        }
    }

    private void destroyFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0 || fenceOwners.get(id) != client) throw new BadFence(id);
            fences.delete(id);
            fenceOwners.remove(id);
        }
    }

    private void awaitFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int length = client.getRemainingRequestLength();
            int[] ids = new int[length / 4];
            int i = 0;

            while (length != 0) {
                ids[i++] = inputStream.readInt();
                length -= 4;
            }

            boolean anyTriggered = false;
            do {
                for (int id : ids) {
                    if (fences.indexOfKey(id) < 0 || fenceOwners.get(id) != client) {
                        throw new BadFence(id);
                    }
                    anyTriggered = fences.get(id);
                    if (anyTriggered) break;
                }

                Thread.yield();
            }
            while (!anyTriggered);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.INITIALIZE:
                initialize(client, inputStream, outputStream);
                break;
            case ClientOpcodes.LIST_SYSTEM_COUNTERS:
                listSystemCounters(client, outputStream);
                break;
            case ClientOpcodes.CREATE_COUNTER:
                createCounter(client, inputStream);
                break;
            case ClientOpcodes.SET_COUNTER:
                setCounter(client, inputStream);
                break;
            case ClientOpcodes.CHANGE_COUNTER:
                changeCounter(client, inputStream);
                break;
            case ClientOpcodes.QUERY_COUNTER:
                queryCounter(client, inputStream, outputStream);
                break;
            case ClientOpcodes.DESTROY_COUNTER:
                destroyCounter(client, inputStream, outputStream);
                break;
            case ClientOpcodes.AWAIT:
                inputStream.skip(client.getRemainingRequestLength());
                break;
            case ClientOpcodes.CREATE_ALARM:
                createAlarm(client, inputStream);
                break;
            case ClientOpcodes.CHANGE_ALARM:
                changeAlarm(client, inputStream);
                break;
            case ClientOpcodes.QUERY_ALARM:
                throw new BadAlarm(inputStream.readInt());
            case ClientOpcodes.DESTROY_ALARM:
                destroyAlarm(client, inputStream);
                break;
            case ClientOpcodes.CREATE_FENCE :
                createFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.TRIGGER_FENCE:
                triggerFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.RESET_FENCE:
                resetFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.DESTROY_FENCE:
                destroyFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.AWAIT_FENCE:
                awaitFence(client, inputStream, outputStream);
                break;
            default:
                throw new BadImplementation();
        }
    }
}
