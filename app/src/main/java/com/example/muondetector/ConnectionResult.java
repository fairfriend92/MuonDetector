package com.example.muondetector;

/*
This class is returned by the AsyncTask ServerConnect and contains the socket, an object stream
 and a flag which signals whether an error has occurred while attempting the connection.
 */

import java.io.ObjectOutputStream;
import java.net.Socket;

class ConnectionResult {
    Socket clientSocket;
    ObjectOutputStream objectOutputStream;
    boolean connectionFailed;

    ConnectionResult(Socket clientSocket, ObjectOutputStream objectOutputStream, boolean connectionFailed) {
        this.clientSocket = clientSocket;
        this.objectOutputStream = objectOutputStream;
        this.connectionFailed = connectionFailed;
    }
}
