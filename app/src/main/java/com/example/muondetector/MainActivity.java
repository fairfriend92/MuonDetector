package com.example.muondetector;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.CountDownTimer;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;
import java.io.InputStream;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity {

    /*
     * Class which provides GPU info
     */

    private class MyGLRenderer implements GLSurfaceView.Renderer {

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            // Get various GPU and GL information
            Log.d("GL info", "gl renderer: " + gl.glGetString(GL10.GL_RENDERER));
            Log.d("GL info", "gl vendor: " + gl.glGetString(GL10.GL_VENDOR));
            Log.d("GL info", "gl version: " + gl.glGetString(GL10.GL_VERSION));
            Log.d("GL info", "gl extensions: " + gl.glGetString(GL10.GL_EXTENSIONS));

            // Store the needed GPU info in the preferences
            SharedPreferences.Editor editor = getSharedPreferences("GPUinfo", Context.MODE_PRIVATE).edit();
            editor.putString("VENDOR", gl.glGetString(GL10.GL_VENDOR));
            editor.putString("RENDERER", gl.glGetString(GL10.GL_RENDERER));
            editor.apply();

            // Set the background frame color
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            // Redraw background color
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }
    }

    /*
     * OpenGL surface view called at app startup to retrieve GPU info
     */

    class MyGLSurfaceView extends GLSurfaceView {
        public MyGLRenderer mRenderer;

        public MyGLSurfaceView(Context context) {
            super(context);

            // Create an OpenGL ES 2.0 context
            setEGLContextClientVersion(2);

            mRenderer = new MyGLRenderer();

            // Set the Renderer for drawing on the GLSurfaceView
            setRenderer(mRenderer);
        }
    }

    // TODO: Make this method static?
    private void loadGLLibrary() {
        SharedPreferences prefs = getSharedPreferences("GPUinfo", Context.MODE_PRIVATE);
        String vendor = prefs.getString("VENDOR", null);
        assert vendor != null;
        switch (vendor) {
            case "ARM":
                try {
                    System.loadLibrary("ARM");
                } catch (UnsatisfiedLinkError linkError) {
                    Log.e("Unsatisfied link", "libGLES_mali.so not found");
                }
                break;
            case "Qualcomm":
                try {
                    System.loadLibrary("Qualcomm");
                } catch (UnsatisfiedLinkError linkError) {
                    Log.e("Unsatisfied link", "libOpenCL.so not found");
                }
                break;
            default:
                Intent broadcastError = new Intent("ErrorMessage");
                broadcastError.putExtra("ErrorNumber", 4);
                LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastError);
        }
    }

    public String kernel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // OpenGL surface view
        MyGLSurfaceView mGlSurfaceView = new MyGLSurfaceView(this);

        // Set on display the OpenGL surface view in order to call the OpenGL renderer and retrieve the GPU info
        setContentView(mGlSurfaceView);

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<?> future = executorService.submit(new RendererRetriever());

        try {
            future.get(2000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("MainActivity", stackTrace);
        }

        // Display the main view
        setContentView(R.layout.activity_main);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        // Create the intent for the main service and bundle the string containing the OpenCL kernel
        Intent detectorIntent = new Intent(MainActivity.this, DetectorService.class);
        detectorIntent.putExtra("Kernel", kernel);
        MainActivity.this.startService(detectorIntent);

        // Create surface to preview the capture
        SurfaceView preview = (SurfaceView) findViewById(R.id.previewView);
        SurfaceHolder previewHolder = preview.getHolder();
        previewHolder.addCallback(DetectorService.surfaceHolderCallback);
    }

    /*
     * Get an input stream from the .cl file
     */

    private InputStream getInputStream(String kernelName) {
        try {
            return getAssets().open(kernelName);
        } catch (IOException ioException) {
            Log.e("IO exception", "Cannot retrieve OpenCL kernel");
            return null;
        }
    }

    /*
     * Scan the .cl file and turn it into a string
     */

    private String loadKernelFromAsset(InputStream inputStream) {
        Scanner scanner = new Scanner(inputStream).useDelimiter("\\A");
        return scanner.hasNext() ? scanner.next() : " ";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        DetectorService.legacyCamera.release();
    }

    private class RendererRetriever implements Runnable {


        @Override
        public void run() {
            // The OpenGL context needs time to be initialized, therefore wait before retrieving the renderer
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("MainActivity", stackTrace);
            }

            // Get the GPU model
            SharedPreferences prefs = getSharedPreferences("GPUinfo", Context.MODE_PRIVATE);
            String renderer = prefs.getString("RENDERER", null);
            assert renderer != null;

            // Load the appropriate OpenCL kernel based on GPU model
            switch (renderer) {
                case "Mali-T880":
                case "Mali-T720":
                    kernel = loadKernelFromAsset(getInputStream("compLumi_vec4.cl"));
                    break;
                default:
                    kernel = loadKernelFromAsset(getInputStream("kernel.cl"));
                    break;
            }

            // Load the appropriate OpenCL library based on the GPU vendor
            loadGLLibrary();
        }
    }
}

