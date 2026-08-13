package com.winlator.xserver;

import android.util.Log;

import com.winlator.xconnector.ConnectedClient;
import com.winlator.xconnector.ConnectionHandler;

public class XClientConnectionHandler implements ConnectionHandler {
    private static final String TAG = "SteamDroid.XServer";
    private final XServer xServer;

    public XClientConnectionHandler(XServer xServer) {
        this.xServer = xServer;
    }

    @Override
    public ConnectedClient newConnectedClient(long clientPtr, int fd) {
        return new XClient(clientPtr, fd, xServer);
    }

    @Override
    public void handleNewConnection(ConnectedClient client) {
        Log.d(TAG, "X client connected fd=" + client.fd);
    }

    @Override
    public void handleConnectionShutdown(ConnectedClient client) {
        Log.d(TAG, "X client disconnected fd=" + client.fd);
        XClient xClient = (XClient)client;
        xServer.releaseServerGrab(xClient);
        xClient.freeResources();
    }
}
