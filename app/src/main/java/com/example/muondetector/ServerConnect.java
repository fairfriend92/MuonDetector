package com.example.muondetector;

/*
Since network operations cannot be run on the main thread, this AsyncTask has the job of connecting
the application to the server to which the candidate pics should be sent. It returns an object
containing the socket, a stream directed to the server and a flag that signals eventual errors
that occurred in establishing the connection.
 */

import android.content.Context;
import android.os.AsyncTask;
import android.widget.Toast;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class ServerConnect extends AsyncTask<Context, Void, ConnectionResult> {
    private static final int SERVER_PORT_TCP = 4197;
    private static final int IPTOS_RELIABILITY = 0x04;

    @Override
    protected ConnectionResult doInBackground(Context... contexts) {
        Context context = contexts[0];
        Socket clientSocket = null;
        ObjectOutputStream socketOutputStream = null;
        boolean serverConnectionFailed = false;
        try {
            clientSocket = new Socket(MainActivity.ip, SERVER_PORT_TCP);
            clientSocket.setTrafficClass(IPTOS_RELIABILITY);
            clientSocket.setKeepAlive(true);
            socketOutputStream = new ObjectOutputStream(clientSocket.getOutputStream());
        } catch (IOException e) {
            CharSequence text = "Connection with the server failed: pics won't be uploaded";
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
            serverConnectionFailed= true;
        }

        return new ConnectionResult(clientSocket, socketOutputStream, serverConnectionFailed);
    }
}
