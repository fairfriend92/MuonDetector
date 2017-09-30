package com.example.muondetector;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.CountDownTimer;
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
import android.widget.TextView;
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

    private class MyCountownTimer extends CountDownTimer {
        private  Context context;

        MyCountownTimer (long millisInFuture, long countDownInterval, Context context) {
            super (millisInFuture, countDownInterval);
            this.context = context;
        }

        @Override
        public void onTick(long millisUntilFinished) {

        }

        @Override
        public void onFinish() {
            // Display the main view
            setContentView(R.layout.activity_main);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

            /* Camera settings */

            // Check the camera settings that can be changed
            Camera tmpCamera = Camera.open(); // Camera object used solely to retrieve info about the camera parameters
            Camera.Parameters parameters = tmpCamera.getParameters();
            supportedPreviewFpsRange = parameters.getSupportedPreviewFpsRange();
            supportedFps = parameters.getSupportedPreviewFrameRates();
            supportedPictureSizes = parameters.getSupportedPreviewSizes();
            Constants.FRAME_RATE_MIN = supportedPreviewFpsRange.get(0)[0] / 1000;
            Constants.FRAME_RATE = supportedPreviewFpsRange.get(0)[1] / 1000;
            Constants.PREVIEW_WIDTH = supportedPictureSizes.get(0).width;
            Constants.PREVIEW_HEIGHT = supportedPictureSizes.get(0).height;
            tmpCamera.release();

            // Populate a spinner with the supported FPS (Static)
            List<String> fpsString = new ArrayList<>(supportedFps.size());
            for (int i = 0; i < supportedFps.size(); i++)
                fpsString.add("" + supportedFps.get(i));
            fpsSpinner = (Spinner) findViewById(R.id.fps_spinner);
            ArrayAdapter<String> fpsArrayAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, fpsString);


            // Populate a spinner with the supported FPS ranges
            List<String> fpsRangeString = new ArrayList<>(supportedPreviewFpsRange.size());
            for (int i = 0; i < supportedPreviewFpsRange.size(); i++)
                fpsRangeString.add("(" + supportedPreviewFpsRange.get(i)[0] + ", " + supportedPreviewFpsRange.get(i)[1] + ")");
            fpsRangeSpinner = (Spinner) findViewById(R.id.preview_fps_range_spinner);
            ArrayAdapter<String> fpsRangesArrayAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, fpsRangeString);
            fpsRangeSpinner.setAdapter(fpsRangesArrayAdapter);
            fpsRangeSpinner.setOnItemSelectedListener(new SpinnerListener());

            fpsSpinner.setAdapter(fpsArrayAdapter);
            fpsSpinner.setOnItemSelectedListener(new SpinnerListener());

            // Populate a spinner with the supported picture sizes
            List<String> pictureSizesString = new ArrayList<>(supportedPictureSizes.size());
            for (int i = 0; i < supportedPictureSizes.size(); i++)
                pictureSizesString.add(supportedPictureSizes.get(i).width + " X " + supportedPictureSizes.get(i).height);
            pictureSizeSpinner = (Spinner) findViewById(R.id.picture_size_spinner);
            ArrayAdapter<String> pictureSizesArrayAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, pictureSizesString);
            pictureSizeSpinner.setAdapter(pictureSizesArrayAdapter);
            pictureSizeSpinner.setOnItemSelectedListener(new SpinnerListener());

              /* [End of camera settings] */

            editSampleSize = (EditText) findViewById(R.id.edit_in_sample_size);
            editCalibrationDuration = (EditText) findViewById(R.id.edit_calibration_duration);
            editCropFactor = (EditText) findViewById(R.id.edit_crop_factor);
            editNumOfSd = (EditText) findViewById(R.id.edit_num_of_sd);
        }
    }

    SurfaceView preview;
    SurfaceHolder previewHolder;
    List<int[]> supportedPreviewFpsRange;
    List<Integer> supportedFps;
    List<Camera.Size> supportedPictureSizes;
    Spinner fpsRangeSpinner, pictureSizeSpinner, fpsSpinner;
    EditText editSampleSize = null, editCalibrationDuration = null, editCropFactor = null, editNumOfSd = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = this.getSharedPreferences("GPUinfo", Context.MODE_PRIVATE);

        // OpenGL surface view
        MyGLSurfaceView mGlSurfaceView = new MyGLSurfaceView(this);

        // Set on display the OpenGL surface view in order to call the OpenGL renderer and retrieve the GPU info
        setContentView(mGlSurfaceView);

        MyCountownTimer myCountownTimer = new MyCountownTimer(2000, 1000, this);
        myCountownTimer.start();
    }

    String kernel;
    Intent detectorIntent;

    public void startService(View view) {
        // Read from the editable box the sample size used to downscale the picture
        try {
            Constants.IN_SAMPLE_SIZE = Integer.parseInt(editSampleSize.getText().toString());
        } catch (NumberFormatException e) {
            Log.e("MainActivity", "editSampleSize does not contain a parsable string. Default value (1) is used");
            Constants.IN_SAMPLE_SIZE = 1;
        }
        if (Constants.IN_SAMPLE_SIZE > 4 || Constants.IN_SAMPLE_SIZE == 0) { // If the number is out of range notifies the user and select the deafult value (1 - no downscale)
            CharSequence text = "Wrong number for sample size. Will use 1.";
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            Constants.IN_SAMPLE_SIZE = 1;
        }

        // Read the calibration phase length
        try {
            Constants.CALIBRATION_DURATION = Integer.parseInt(editCalibrationDuration.getText().toString());
        } catch (NumberFormatException e) {
            Log.e("MainActivity", "editCalibrationDuration does not contain a parsable string. Default value (240) is used");
            Constants.CALIBRATION_DURATION = 240;
        }

        // Read the crop factor
        try {
            Constants.CROP_FACTOR = Integer.parseInt(editCropFactor.getText().toString());
        } catch (NumberFormatException e) {
            Log.e("MainActivity", "editCropFactor does not contain a parsable string. Default value (100) is used");
            Constants.CROP_FACTOR = 100;
        }
        if (Constants.CROP_FACTOR > 100 || Constants.CROP_FACTOR == 0) {
            CharSequence text = "Wrong number for crop factor. Will use 100.";
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            Constants.CROP_FACTOR = 100;
        }

        // Read number of standard deviations
        try {
            Constants.NUM_OF_SD = Integer.parseInt(editNumOfSd.getText().toString());
        } catch (NumberFormatException e) {
            Log.e("MainActivity", "editNumOfSd does not contain a parsable string. Default value (5) is used");
            Constants.NUM_OF_SD = 5;
        }
        if (Constants.NUM_OF_SD > 10 || Constants.NUM_OF_SD == 0) {
            CharSequence text = "Wrong number for standard deviations. Will use 5.";
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            Constants.CROP_FACTOR = 5;
        }

        Constants.computeAdditionalValues();

        setContentView(R.layout.preview);

        // Create surface to preview the capture
        preview = (SurfaceView) findViewById(R.id.previewView);
        previewHolder = preview.getHolder();
        previewHolder.addCallback(DetectorService.surfaceHolderCallback);

        // Create the intent for the main service and bundle the string containing the OpenCL kernel
        detectorIntent = new Intent(MainActivity.this, DetectorService.class);
        detectorIntent.putExtra("Kernel", kernel);
        MainActivity.this.startService(detectorIntent);
    }

    public void StopCapture (View view) {
        MainActivity.this.stopService(detectorIntent);

        Intent resizePicsIntent = new Intent(MainActivity.this, ResizePicsActivity.class);
        MainActivity.this.startActivity(resizePicsIntent);
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
                Constants.FPS_DYNAMIC = true;
                TextView fpsMode = (TextView) findViewById(R.id.fps_mode);
                String dynamicMode = getResources().getString(R.string.fps_mode_dynamic);
                fpsMode.setText(dynamicMode);
            } else if (parent.getId() == pictureSizeSpinner.getId()) {
                Constants.PREVIEW_WIDTH = supportedPictureSizes.get(position).width;
                Constants.PREVIEW_HEIGHT = supportedPictureSizes.get(position).height;
            } else if (parent.getId() == fpsSpinner.getId()) {
                Constants.FRAME_RATE_MIN = supportedFps.get(position);
                Constants.FRAME_RATE = Constants.FRAME_RATE_MIN;
                Constants.FPS_DYNAMIC = false;
                TextView fpsMode = (TextView) findViewById(R.id.fps_mode);
                String staticMode = getResources().getString(R.string.fps_mode_static);
                fpsMode.setText(staticMode);
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
    }

}
