package com.example.muondetector;

/*
Async task whose job is that of retrieving the IP of the server to which the candidate pics should
be sent
 */

import android.os.AsyncTask;
import android.util.Log;

class LookUpServerIP extends AsyncTask<Void, Void, String> {

    protected String doInBackground(Void... voids) {
        String ip = null;
        try (java.util.Scanner s = new java.util.Scanner(new java.net.URL("http://overmind.000webhostapp.com/overmindServerIP.html").openStream(), "UTF-8").useDelimiter("\n")) {
            ip = s.next();
        } catch (java.io.IOException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("LookUpServerIP", stackTrace);
        }
        assert ip != null;
        return ip;
    }

}

