package com.example.muondetector;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

@SuppressWarnings("deprecation")
public class MainActivity extends AppCompatActivity {

    SharedPreferences prefs;

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
            SharedPreferences.Editor editor = prefs.edit();
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

    private HandlerThread handlerThread;

    SurfaceView preview;
    SurfaceHolder previewHolder;
    List<int[]> supportedPreviewFpsRange;
    List<Camera.Size> supportedPictureSizes;
    Spinner fpsRangeSpinner, pictureSizeSpinner;
    EditText editSampleSize = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = this.getSharedPreferences("GPUinfo", Context.MODE_PRIVATE);

        // OpenGL surface view
        MyGLSurfaceView mGlSurfaceView = new MyGLSurfaceView(this);

        // Set on display the OpenGL surface view in order to call the OpenGL renderer and retrieve the GPU info
        setContentView(mGlSurfaceView);

        // Display the main view
        setContentView(R.layout.activity_main);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        /* Camera settings */

        // Check the camera settings that can be changed
        Camera tmpCamera = Camera.open(); // Camera object used solely to retrieve info about the camera parameters
        Camera.Parameters parameters = tmpCamera.getParameters();
        supportedPreviewFpsRange = parameters.getSupportedPreviewFpsRange();
        supportedPictureSizes = parameters.getSupportedPictureSizes();
        Constants.FRAME_RATE_MIN = supportedPreviewFpsRange.get(0)[0] / 1000;
        Constants.FRAME_RATE = supportedPreviewFpsRange.get(0)[1] / 1000;
        Constants.PREVIEW_WIDTH = supportedPictureSizes.get(0).width;
        Constants.PREVIEW_HEIGHT = supportedPictureSizes.get(0).height;
        tmpCamera.release();

        // Populate a spinner with the supported FPS ranges
        List<String> fpsRangeString = new ArrayList<>(supportedPreviewFpsRange.size());
        for (int i = 0; i < supportedPreviewFpsRange.size(); i++)
            fpsRangeString.add("(" + supportedPreviewFpsRange.get(i)[0] + ", " + supportedPreviewFpsRange.get(i)[1] + ")");
        fpsRangeSpinner = (Spinner) findViewById(R.id.preview_fps_range_spinner);
        ArrayAdapter<String> fpsRangesArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, fpsRangeString);
        fpsRangeSpinner.setAdapter(fpsRangesArrayAdapter);
        fpsRangeSpinner.setOnItemSelectedListener(new SpinnerListener());

        // Populate a spinner with the supported picture sizes
        List<String> pictureSizesString = new ArrayList<>(supportedPictureSizes.size());
        for (int i = 0; i < supportedPictureSizes.size(); i++)
            pictureSizesString.add(supportedPictureSizes.get(i).width + " X " + supportedPictureSizes.get(i).height);
        pictureSizeSpinner = (Spinner) findViewById(R.id.picture_size_spinner);
        ArrayAdapter<String> pictureSizesArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, pictureSizesString);
        pictureSizeSpinner.setAdapter(pictureSizesArrayAdapter);
        pictureSizeSpinner.setOnItemSelectedListener(new SpinnerListener());

        /* [End of camera settings] */

        editSampleSize = (EditText) findViewById(R.id.edit_in_sample_size);

        // Create the handler thread to load the appropriate kernel based on GPU model
        handlerThread = new HandlerThread("RendererRetrieverThread");
        handlerThread.start();
        Looper looper = handlerThread.getLooper();
        Handler handler = new Handler(looper);
        handler.post(new RendererRetriever());
    }

    String kernel;

    public void startService(View view) {
        // Read from the editable box the sample size used to downscale the picture
        Constants.IN_SAMPLE_SIZE = Integer.parseInt(editSampleSize.getText().toString());
        if (Constants.IN_SAMPLE_SIZE > 4 || Constants.IN_SAMPLE_SIZE == 0) { // If the number is out of range notifies the user and select the deafult value (1 - no downscale)
            CharSequence text = "Wrong number for sample size. Will use 1.";
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            Constants.IN_SAMPLE_SIZE = 1;
        }
        Constants.computeAdditionalValues();

        setContentView(R.layout.preview);

        // Create surface to preview the capture
        preview = (SurfaceView) findViewById(R.id.previewView);
        previewHolder = preview.getHolder();
        previewHolder.addCallback(DetectorService.surfaceHolderCallback);

        // Create the intent for the main service and bundle the string containing the OpenCL kernel
        Intent detectorIntent = new Intent(MainActivity.this, DetectorService.class);
        detectorIntent.putExtra("Kernel", kernel);
        MainActivity.this.startService(detectorIntent);
    }

    /*
     * Simple class that saves the preview settings depending of the spinner items selected
     */

    private class SpinnerListener implements AdapterView.OnItemSelectedListener {

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (parent.getId() == fpsRangeSpinner.getId()) {
                Constants.FRAME_RATE_MIN = supportedPreviewFpsRange.get(position)[0] / 1000;
                Constants.FRAME_RATE = supportedPreviewFpsRange.get(position)[1] / 1000;
            } else if (parent.getId() == pictureSizeSpinner.getId()) {
                Constants.PREVIEW_WIDTH = supportedPictureSizes.get(position).width;
                Constants.PREVIEW_HEIGHT = supportedPictureSizes.get(position).height;
            }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {

        }
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
        handlerThread.quit();
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
            String renderer = prefs.getString("RENDERER", null);
            assert renderer != null;

            // Load the appropriate OpenCL kernel based on GPU model
            switch (renderer) {
                case "Mali-T880":
                case "Mali-T720":
                    kernel = loadKernelFromAsset(getInputStream("compLumi_vec4.cl"));
                    break;
                default:
                    kernel = loadKernelFromAsset(getInputStream("compLumi_vec4.cl"));
                    break;
            }

            // Load the appropriate OpenCL library based on the GPU vendor
            loadGLLibrary();
        }
    }
}

