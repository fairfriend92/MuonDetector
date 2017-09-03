package com.example.muondetector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.hardware.Camera;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        Intent detectorIntent = new Intent(MainActivity.this, DetectorService.class);
        this.startService(detectorIntent);

        // Create surface to preview the capture
        SurfaceView preview = (SurfaceView)findViewById(R.id.previewView);
        SurfaceHolder previewHolder = preview.getHolder();
        previewHolder.addCallback(DetectorService.surfaceHolderCallback);
    }

    @Override
    protected void onDestroy () {
        super.onDestroy();
        DetectorService.legacyCamera.release();
    }

}
