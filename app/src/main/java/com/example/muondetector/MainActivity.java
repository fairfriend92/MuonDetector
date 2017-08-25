package com.example.muondetector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
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

    private BroadcastReceiver previewSurfaceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Create surface to preview the capture
            SurfaceView preview = (SurfaceView)findViewById(R.id.previewView);
            SurfaceHolder previewHolder = preview.getHolder();
            previewHolder.addCallback(DetectorService.surfaceHolderCallback);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Example of a call to a native method
        TextView tv = (TextView) findViewById(R.id.sample_text);
        tv.setText(stringFromJNI());

        // Register a local broadcast receiver to create the preview surface
        LocalBroadcastManager.getInstance(this).registerReceiver(previewSurfaceReceiver,
                new IntentFilter("CreatePreviewSurface"));

        Intent detectorIntent = new Intent(MainActivity.this, DetectorService.class);
        this.startService(detectorIntent);

    }

    @Override
    protected void onDestroy () {
        super.onDestroy();

        // Unregister the local broadcast
        LocalBroadcastManager.getInstance(this).unregisterReceiver(previewSurfaceReceiver);

    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();
}
