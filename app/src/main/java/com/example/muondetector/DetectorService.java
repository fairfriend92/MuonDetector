package com.example.muondetector;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Class which extends Service in order to run in the background. Opens the camera and regularly
 * takes pictures which are later processed by a separate thread in order to recognize eventual
 * particle traces.
 */

@SuppressWarnings("deprecation")
public class DetectorService extends Service {

    private static final int NOTIFICATION_ID = 0;
    private static final int SERVICE_NOTIFICATION_ID = 1;
    private static final long NULL_VALUE = 0;

    private static boolean shutdown = false;

    private static final Object captureThreadLock = new Object();
    static Camera legacyCamera; // Use the old camera api

    /* Fields related to the threading of the application */

    // Handler for tasks that cannot be executed immediately due to buffer overflow
    private ThreadPoolExecutor.AbortPolicy rejectedExecutionHandler = new ThreadPoolExecutor.AbortPolicy();

    // Buffers for the tasks to be executed
    private BlockingQueue<Runnable> GPUschedulerQueue = new ArrayBlockingQueue<>(32);
    private BlockingQueue<Runnable> imageConverterQueue = new ArrayBlockingQueue<>(32);

    // Executors and HandlerThread
    private ExecutorService captureThreadExecutor = Executors.newSingleThreadExecutor();
    private ExecutorService imageConverterExecutor =
            new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS, imageConverterQueue, rejectedExecutionHandler);
    private ThreadPoolExecutor GPUkernelSchedulerExec =
            new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, GPUschedulerQueue, rejectedExecutionHandler);
    private ExecutorService jpegCreatorExecutor = Executors.newSingleThreadExecutor();
    private HandlerThread handlerThread;


    /* Miscellanea */

    private NotificationCompat.Builder notificationBuilder;
    private boolean notificationShowed = false;
    private long openCLObject = 0;
    private double meanMaxLumi = 0, meanSquaredMaxLumi = 0, standardDeviation = 0;
    private int samplesTaken = 0; // Times the luminance has been sampled during the setup phase


    /*
    Converts the image from YUV to JPEG
     */

    private class ImageConverter implements Runnable {

        private byte[] yuvData;

        ImageConverter(byte[] b) {
            yuvData = b;
        }

        @Override
        public void run () {
            // Convert the yuv image to jpeg
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            YuvImage yuv = new YuvImage(yuvData, ImageFormat.NV21, Constants.PREVIEW_WIDTH, Constants.PREVIEW_HEIGHT, null);
            yuv.compressToJpeg(new Rect(Constants.CROP_TOP_X, Constants.CROP_TOP_Y, Constants.CROP_BOTTOM_X, Constants.CROP_BOTTOM_Y), 100, out);

            // Create a low resolution bitmap from the jpeg
            byte[] bytes = out.toByteArray();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Constants.IN_SAMPLE_SIZE;
            Bitmap lowResBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);

            // Pass the bitmap to the GPU scheduler
            try {
                GPUkernelSchedulerExec.execute(new GPUscheduler(lowResBitmap, yuvData));
            } catch (RejectedExecutionException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DetectorService", stackTrace);
            }
        }
    }

    /*
    Class which sends the RGB values to the native side of the application which store them in the
    GPU memory to be processed by the openCL kernel
     */

    private class GPUscheduler implements Runnable {
        private Bitmap lowResBitmap;
        private byte[] yuvData;

        GPUscheduler(Bitmap lowResBitmap, byte[] yuvData)
        {
            this.lowResBitmap = lowResBitmap;
            this.yuvData = yuvData;
        }

        @Override
        public void run() {
            // Get from the bitmap the RGB values
            int[] pixels = new int[Constants.SCALED_WIDTH * Constants.SCALED_HEIGHT];
            lowResBitmap.getPixels(pixels, 0, Constants.SCALED_WIDTH, 0, 0, Constants.SCALED_WIDTH, Constants.SCALED_HEIGHT);

            // During the calibration phase compute the average maximum luminance (defined as the greatest value of the luminance for one picture instance)
            if (samplesTaken <= Constants.NUM_OF_SAMPLES) { // If the calibration phase has not endend...
                double maxLumi = computeluminance(openCLObject, pixels);
                meanMaxLumi += maxLumi;
                meanSquaredMaxLumi += maxLumi * maxLumi;
                samplesTaken++;
                yuvData = null;
            } else if (samplesTaken == Constants.NUM_OF_SAMPLES + 1) { // If the calibration phase has just ended...
                meanMaxLumi /= Constants.NUM_OF_SAMPLES;
                meanSquaredMaxLumi /= Constants.NUM_OF_SAMPLES;
                standardDeviation = Math.sqrt(Math.abs(meanSquaredMaxLumi - meanMaxLumi * meanMaxLumi) * Constants.NUM_OF_SAMPLES / (Constants.NUM_OF_SAMPLES - 1));
                samplesTaken++;
            } else { // When the calibration is done...
                float maxLumi = computeluminance(openCLObject, pixels);
                if (maxLumi >= meanMaxLumi + Constants.NUM_OF_SD * standardDeviation) {
                    try {
                        jpegCreatorExecutor.execute(new jpegCreator(maxLumi, yuvData, pixels));
                    } catch (RejectedExecutionException e) {
                        String stackTrace = Log.getStackTraceString(e);
                        Log.e("DetectorService", stackTrace);
                    }
                }
            }
            /* [End of if] */

        }
        /* [End of run()] */
    }

    /*
    Class which creates a high res JPEG picture out of the lowResBitamp yuvData if the luminance is above
    threshold, and save it to the storage memory of the device
     */

    private class jpegCreator implements Runnable {
        private int[] lowResPicPixels;
        private float maxLumi;
        private byte[] yuvData;

        jpegCreator(float maxLumi, byte[] yuvData, int[] lowResPicPixels) {
            this.maxLumi = maxLumi;
            this.yuvData = yuvData;
            this.lowResPicPixels = lowResPicPixels;
        }

        @Override
        public void run() {
            try {
                // If luminance is above threshold create a notification. Do that only the first time
                if (!notificationShowed) {
                    NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
                    notificationShowed = true;
                }

                /* Create high-resolution bitmap from the RGB values*/

                // Convert the yuv image to jpeg
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                YuvImage yuv = new YuvImage(yuvData, ImageFormat.NV21, Constants.PREVIEW_WIDTH, Constants.PREVIEW_HEIGHT, null);
                yuv.compressToJpeg(new Rect(Constants.CROP_TOP_X, Constants.CROP_TOP_Y, Constants.CROP_BOTTOM_X, Constants.CROP_BOTTOM_Y), 100, out);

                // Create a high resolution bitmap from the jpeg
                byte[] bytes = out.toByteArray();
                Bitmap highResBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

                /* Create a bitmap from the luminance map */

                // Create the array storing the full resolution preview picture
                int[] pixels = new int[Constants.CROP_WIDTH * Constants.CROP_HEIGHT];

                // Save in the array the RGB values
                highResBitmap.getPixels(pixels, 0, Constants.CROP_WIDTH, 0, 0, Constants.CROP_WIDTH, Constants.CROP_HEIGHT);

                // Compute the average luminance from the low res version of the picture;
                // TODO: Move to separate openCL kernel
                float meanLumi = 0.0f;
                for (int pixel : lowResPicPixels) {
                    float r = Color.red(pixel) / 255.0f;
                    float g = Color.green(pixel) / 255.0f;
                    float b = Color.blue(pixel) / 255.0f;

                    r = r * (r * (r * 0.305306011f + 0.682171111f) + 0.012522878f); // Linear approximation of the transform that linearizes color in the sRGB space
                    g = g * (g * (g * 0.305306011f + 0.682171111f) + 0.012522878f);
                    b = b * (b * (b * 0.305306011f + 0.682171111f) + 0.012522878f);

                    meanLumi += 0.2126f * r + 0.7152f * g + 0.0722f * b;
                }
                meanLumi /= lowResPicPixels.length;

                // Create the bitmap storing the luminance map
                Bitmap luminanceBitmap =
                        Bitmap.createBitmap(luminanceMap(openCLObject, maxLumi, meanLumi, pixels), Constants.CROP_WIDTH, Constants.CROP_HEIGHT, Bitmap.Config.ARGB_8888);

                // Rotate the bitmap
                Matrix rotationMatrix = new Matrix();
                rotationMatrix.postRotate(90);
                Bitmap rotatedLumuBmp = Bitmap.createBitmap(luminanceBitmap, 0, 0, luminanceBitmap.getWidth(), luminanceBitmap.getHeight(), rotationMatrix, true);

                // Create the file that will store the image
                File imageFile = createImageFile(maxLumi);
                FileOutputStream fileOutputStream = new FileOutputStream(imageFile);

                // Store the luminance map in the file
                rotatedLumuBmp.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
                fileOutputStream.close();
            } catch (IOException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DetectorService", stackTrace);
            }
        }
    }

    /*
    Callback classed whose methods are used to process the frame provided by the preview video feed
     */

    private int frame = 0;
    private long time = 0;

    private Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            try {
                imageConverterExecutor.execute(new ImageConverter(data));
            } catch (RejectedExecutionException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DetectorService", stackTrace);
            }

            // Update the average frame rate every 1 sec
            if (++frame == Constants.FRAME_RATE_MIN) {
                float timeMs = (System.nanoTime() - time) / 1000000; // Time in ms since the last time the average frame rate was updated
                float framerate = (Constants.FRAME_RATE_MIN * 1000) / timeMs;
                Log.d("DetectorService", "Average frame rate " + framerate + " mean lumi " + meanMaxLumi + " standard dev " + standardDeviation);
                time = System.nanoTime();
                frame = 0;
            }
        }
    };

    /*
    Separate thread whose only purpose is to restart the preview whenever the preview surface changes
    (for example when the application is not in focus anymore)

    We must use this separate thread since surface holder callback, which is called by the main activity,
    is static, while the preview callback called in this thread cannot be so.
     */

    private static SurfaceHolder surfaceHolder;

    private class CaptureThread implements Runnable {
        @Override
        public void run () {
            notificationBuilder = new NotificationCompat.Builder(DetectorService.this)
                    .setContentTitle("Trigger notification")
                    .setContentText("Trigger activated")
                    .setSmallIcon(R.drawable.trigger_event_icon);

            while (!shutdown) {
                // The thread is notified whenever the preview surface changes
                synchronized (captureThreadLock) {
                    try {
                        captureThreadLock.wait();
                    } catch (InterruptedException e) {
                        String stackTrace = Log.getStackTraceString(e);
                        Log.e("DetectorService", stackTrace);
                    }
                }

                // Proceed only if the application is not shutting down
                if (!shutdown) {
                    try {
                        legacyCamera.stopPreview();
                        legacyCamera.setPreviewDisplay(surfaceHolder);
                        legacyCamera.setPreviewCallback(previewCallback);
                        legacyCamera.startPreview();
                    } catch (IOException e) {
                        String stackTrace = Log.getStackTraceString(e);
                        Log.e("DetectorService", stackTrace);
                    }
                }
                /* [End of if (!shutdown)] */
            }
            /* [End of while] */
        }
        /* [End of run] */
    }

    /*
    Callback class whose methods allow monitor the state of the surface on which the preview frames are displayed
     */

    static SurfaceHolder.Callback surfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d("DetectorService", "surfaceHolderCallback surfaceCreated");
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d("DetectorService", "surfaceHolderCallback surfaceChanged");

            assert holder.getSurface() != null;
            surfaceHolder = holder;

            if (legacyCamera != null) // Whenever the surface is changed unlock the CaptureThread so that preview can be restarted
                try {
                    synchronized (captureThreadLock) {
                        captureThreadLock.notify();
                    }
                } catch (Exception e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("DetectorService", stackTrace);
                }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
        }
    };

    private File createImageFile(float luminance) throws IOException {
        // Create an image file name
        //String timeStamp = new SimpleDateFormat((int)luminance + "_yyyyMMdd_HHmmss", Locale.ITALY).format(new Date());
        String imageFileName = (int)luminance + "_" + luminance;
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(
                imageFileName,  /* prefix */
                ".jpg",         /* suffix */
                storageDir      /* directory */
        );

        return image;
    }

    @Override
    public IBinder onBind (Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // Put the service in the foreground so that there is less of a chance that it gets killed
        NotificationCompat.Builder serviceNotificationBuiler = new NotificationCompat.Builder(DetectorService.this)
                .setContentTitle("Muons detector")
                .setContentText("Muons detector started")
                .setSmallIcon(R.drawable.trigger_event_icon); // TODO: Change icon?
        this.startForeground(SERVICE_NOTIFICATION_ID, serviceNotificationBuiler.build());
        Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

        // Get the kernel from the UI thread and initialize the OpenCL context with it
        assert intent != null;
        String kernel = intent.getStringExtra("Kernel");
        openCLObject = initializeOpenCL(kernel, Constants.CROP_WIDTH, Constants.CROP_HEIGHT, Constants.IN_SAMPLE_SIZE);

        // Execute the runnable which will start the camera preview using the old api
        captureThreadExecutor.execute(new CaptureThread());

        /* Camera initialization */

        // previewCallback should be handled by a different thread than the main one. For this  we need a thread with a looper, hence the use of HandlerThread
        handlerThread = new HandlerThread("CaptureHandlerThread");
        handlerThread.start();
        Looper looper = handlerThread.getLooper();
        Handler handler = new Handler(looper);
        handler.post(new Runnable() { // In this runnable we simply open the camera, so that the previewCallback will be sent to the HandlerThread rather than the UI thread
            @Override
            public void run() {
                try {
                    legacyCamera = Camera.open();
                    Camera.Parameters parameters = legacyCamera.getParameters();
                    String parametersString = parameters.flatten();
                    Log.d("ParametersString", parametersString);
                    if (parametersString.contains("iso-speed")) {
                        parameters.set("iso-speed", 1600);
                    }
                    parameters.set("saturation", "high");
                    parameters.set("brightness", "high");
                    parameters.setAntibanding(Camera.Parameters.ANTIBANDING_OFF);
                    parameters.setColorEffect(Camera.Parameters.EFFECT_NONE);
                    parameters.setExposureCompensation(parameters.getMaxExposureCompensation());
                    parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                    parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_SHADE);
                    parameters.setJpegQuality(100);
                    parameters.setAutoExposureLock(true);
                    parameters.setAutoWhiteBalanceLock(true);
                    parameters.setPreviewSize(Constants.PREVIEW_WIDTH, Constants.PREVIEW_HEIGHT);
                    if (Constants.FPS_DYNAMIC)
                        parameters.setPreviewFpsRange(Constants.FRAME_RATE_MIN * 1000, Constants.FRAME_RATE * 1000);
                    else
                        parameters.setPreviewFrameRate(Constants.FRAME_RATE);
                    legacyCamera.setParameters(parameters);
                    legacyCamera.setDisplayOrientation(90);
                    try {
                        synchronized (captureThreadLock) {
                            captureThreadLock.notify();
                        }
                    } catch (Exception e) {
                        String stackTrace = Log.getStackTraceString(e);
                        Log.e("DetectorService", stackTrace);
                    }
                } catch (Exception e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("DetectorService", stackTrace);
                }
            }
        });

        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent intent) {
        onDestroy();
    }

    @Override
    public void onDestroy() {
        shutdown = true;
        synchronized (captureThreadLock) {
            captureThreadLock.notify();
        }
        captureThreadExecutor.shutdown();
        imageConverterExecutor.shutdown();
        GPUkernelSchedulerExec.shutdown();
        jpegCreatorExecutor.shutdown();
        boolean executorsAreShutdown = true;
        try {
            executorsAreShutdown = captureThreadExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
            executorsAreShutdown &= imageConverterExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
            executorsAreShutdown &= GPUkernelSchedulerExec.awaitTermination(100, TimeUnit.MILLISECONDS);
            executorsAreShutdown &= jpegCreatorExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("DetectorService", stackTrace);
        }
        if (executorsAreShutdown)
            Log.i("DetectorService", "All executors have shutdown properly");
        else
            Log.e("DetectorsService", "Not all executors have shutdown properly");
        if (openCLObject != NULL_VALUE)
            closeOpenCL(openCLObject);
        handlerThread.quit();
        shutdown = false;
        legacyCamera.release();
        stopSelf();
    }

    public native long initializeOpenCL(String kernel, int previewWidth, int previewHeight, int inSampleSize);
    public native float computeluminance(long openCLObject, int[] pixels);
    public native int[] luminanceMap(long openCLObject, float maxLumi, float meanLumi, int[] fullPixels);
    public native void closeOpenCL(long openCLObject);
}