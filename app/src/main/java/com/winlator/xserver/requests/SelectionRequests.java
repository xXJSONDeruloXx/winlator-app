package com.winlator.xserver.requests;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Atom;
import com.winlator.xserver.SelectionManager;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadAtom;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;
import com.winlator.xserver.events.SelectionNotify;
import com.winlator.xserver.events.SelectionRequest;

import java.io.IOException;

public abstract class SelectionRequests {
    public static void setSelectionOwner(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int atom = inputStream.readInt();
        int timestamp = inputStream.readInt();

        Window owner = windowId == 0 ? null : client.xServer.windowManager.getWindow(windowId);
        if (windowId != 0 && owner == null) throw new BadWindow(windowId);
        if (!Atom.isValid(atom)) throw new BadAtom(atom);

        client.xServer.selectionManager.setSelection(atom, owner, client, timestamp);
    }

    public static void getSelectionOwner(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int atom = inputStream.readInt();
        if (!Atom.isValid(atom)) throw new BadAtom(atom);
        Window owner = client.xServer.selectionManager.getSelection(atom).owner;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(owner != null ? owner.id : 0);
            outputStream.writePad(20);
        }
    }

    public static void convertSelection(XClient client, XInputStream inputStream,
                                        XOutputStream outputStream) throws IOException, XRequestError {
        int requestorId = inputStream.readInt();
        int selectionAtom = inputStream.readInt();
        int targetAtom = inputStream.readInt();
        int propertyAtom = inputStream.readInt();
        int timestamp = inputStream.readInt();

        Window requestor = client.xServer.windowManager.getWindow(requestorId);
        if (requestor == null) throw new BadWindow(requestorId);
        if (!Atom.isValid(selectionAtom)) throw new BadAtom(selectionAtom);
        if (!Atom.isValid(targetAtom)) throw new BadAtom(targetAtom);
        if (propertyAtom != 0 && !Atom.isValid(propertyAtom)) throw new BadAtom(propertyAtom);

        SelectionManager.Selection selection = client.xServer.selectionManager.getSelection(selectionAtom);
        if (selection.owner == null || selection.client == null) {
            // The protocol requires a SelectionNotify even when there is no
            // owner. Property=None tells the requestor that conversion failed.
            client.sendEvent(new SelectionNotify(timestamp, requestorId, selectionAtom,
                targetAtom, 0));
        }
        else {
            selection.client.sendEvent(new SelectionRequest(timestamp, selection.owner.id,
                requestorId, selectionAtom, targetAtom, propertyAtom));
        }
    }
}
