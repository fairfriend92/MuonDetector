package com.example.muondetector;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
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
import java.util.concurrent.LinkedBlockingQueue;
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
    private static final int PREVIEW_WIDTH = 1280;
    private static final int PREVIEW_HEIGHT = 720;
    private static final int IN_SAMPLE_SIZE = 1;
    private static final int FRAME_RATE = 16;
    private static final int FRAME_RATE_MIN = 16;
    private static final int NUM_OF_SAMPLES = FRAME_RATE_MIN * 240;
    private static final long NULL_VALUE = 0;

    private static boolean shutdown = false;

    private static final Object captureThreadLock = new Object();
    static Camera legacyCamera; // Use the old camera api in case the hardware does not support iso control using camera2

    /* Fields related to the threading of the application */

    // Handler for tasks that cannot be executed immediately due to buffer overflow
    private ThreadPoolExecutor.AbortPolicy rejectedExecutionHandler = new ThreadPoolExecutor.AbortPolicy();

    // Buffer for the tasks to be executed
    private BlockingQueue<Runnable> luminanceCalculatorQueue = new ArrayBlockingQueue<>(32);
    private BlockingQueue<Runnable> GPUschedulerQueue = new ArrayBlockingQueue<>(32);
    private BlockingQueue<Runnable> imageConverterQueue = new ArrayBlockingQueue<>(32);
    private BlockingQueue<Runnable> kurtosisCalculatorQueue = new ArrayBlockingQueue<>(32);

    // Executors and HandlerThread
    private ExecutorService captureThreadExecutor = Executors.newSingleThreadExecutor();
    private ExecutorService imageConverterExecutor =
            new ThreadPoolExecutor(1, 8, 1, TimeUnit.SECONDS, imageConverterQueue, rejectedExecutionHandler);
    private ThreadPoolExecutor luminanceCalculatorExec =
            new ThreadPoolExecutor(1, 8, 1, TimeUnit.SECONDS, luminanceCalculatorQueue, rejectedExecutionHandler);
    private ThreadPoolExecutor GPUkernelSchedulerExec =
            new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, GPUschedulerQueue, rejectedExecutionHandler);
    private ExecutorService kurtosisCalculatorExecutor =
            new ThreadPoolExecutor(1, 4, 1, TimeUnit.SECONDS, kurtosisCalculatorQueue, rejectedExecutionHandler);
    private ExecutorService jpegCreatorExecutor = Executors.newSingleThreadExecutor();
    private HandlerThread handlerThread;

    /* Objects pertaining the renderscript that converts the preview yuv image to bitmap */

    boolean renderScriptInUse = true;
    private ScriptIntrinsicYuvToRGB yuvToRgbIntrinsic;
    private Allocation allocatioIn, allocationOut;
    private Bitmap previewBitmap;

    /* Miscellanea */

    private NotificationCompat.Builder notificationBuilder;
    private boolean notificationShowed = false;
    private long openCLObject = 0;
    private volatile boolean bufferUnderPressure = false; // Flag that indicates that the buffer of the GPUscheduler is almost full
    private int previewImageSize = 0; // Size in buffer of the preview image if preview format is NV21 (default format)
    private double meanluminance = 0, meanSquaredLumi = 0, standardDeviation = 0, constantValue = 0, maxMeanLumi = 0, maxStandardDeviation = 0;

    /*
    Converts the image from YUV to JPEG and send the yuvData to be processed by the GPU or, if the GPU
    is busy, to a separate CPU thread.
     */

    private class ImageConverter implements Runnable {

        private byte[] yuvData;

        ImageConverter(byte[] b) {
            yuvData = b;
        }

        @Override
        public void run () {
            Bitmap lowResBitmap;
            byte[] data;

            // If possible use hardware accelaration through renderscript to convert the image
            if (!renderScriptInUse) { // TODO: Phase out software alternative?
                // Convert the YUV preview frame to jpeg
                YuvImage yuvImage = new YuvImage(yuvData, ImageFormat.NV21,PREVIEW_WIDTH, PREVIEW_HEIGHT, null);
                ByteArrayOutputStream byteArrayOS = new ByteArrayOutputStream();
                yuvImage.compressToJpeg(new Rect(0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT), 100, byteArrayOS);
                data = byteArrayOS.toByteArray();

                // Crate low res bitmap from jpeg yuvData
                BitmapFactory.Options lowResOptions = new BitmapFactory.Options();
                lowResOptions.inSampleSize = IN_SAMPLE_SIZE;
                lowResBitmap = BitmapFactory.decodeByteArray(data, 0, data.length, lowResOptions);
            } else {
                allocatioIn.copyFrom(yuvData);
                yuvToRgbIntrinsic.forEach(allocationOut);
                allocationOut.copyTo(previewBitmap);
                lowResBitmap = Bitmap.createScaledBitmap(previewBitmap, PREVIEW_WIDTH / IN_SAMPLE_SIZE, PREVIEW_HEIGHT / IN_SAMPLE_SIZE, false);
            }

            // Put the bitmap and the yuvData in the buffer of the separate thread computing the luminiscence
            try {
                int capacity = GPUschedulerQueue.remainingCapacity();
                int size = GPUschedulerQueue.size();
                // If the buffer of the GPU kernels scheduler is under pressure launch a thread on
                // the CPU to handle the processing
                if (capacity > size / 3 && !bufferUnderPressure || capacity > size) {
                    bufferUnderPressure = false;
                    GPUkernelSchedulerExec.execute(new GPUscheduler(lowResBitmap));
                } else if (capacity <= size / 3 && meanluminance != 0 || bufferUnderPressure) { // Can't launch the CPU thread it the maximum luminance has not be sampled yet
                    Log.e("DetectorService", "Buffer capacity is < 1/4th of size: Use software computation");
                    luminanceCalculatorExec.execute(new luminanceCalculator(lowResBitmap));
                    bufferUnderPressure = true;
                }
            } catch (RejectedExecutionException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DetectorService", stackTrace);
            }
        }

    }

    /*
    Runnable class which computes the luminance of a low res bitmap is GPU is under heavy stress
    and cannot handle the computation itself.
     */

    private class luminanceCalculator implements Runnable {
        private Bitmap bitmap;

        luminanceCalculator(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public void run () {
            int scaledWidth = bitmap.getWidth() / IN_SAMPLE_SIZE;
            int scaledHeight = bitmap.getHeight() / IN_SAMPLE_SIZE;
            int[] pixels = new int[scaledWidth * scaledHeight];
            bitmap.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight);
            float luminance, maxLuminance = 0;

            // Compute the luminance for each pixel
            for (int pixel : pixels) {
                luminance = 0.2126f * Color.red(pixel) + 0.7152f * Color.green(pixel) + 0.0722f * Color.blue(pixel);
                maxLuminance = luminance > maxLuminance ? luminance : maxLuminance;
            }

            if (maxLuminance > meanluminance + 5 * standardDeviation) {
                Bitmap highResBitmap = Bitmap.createBitmap(retrieveLuminance(openCLObject), PREVIEW_WIDTH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888);
                try {
                    jpegCreatorExecutor.execute(new jpegCreator(maxLuminance, highResBitmap));
                } catch (RejectedExecutionException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("DetectorService", stackTrace);
                }
            }
            /* [End of if] */
        }
        /* [End of run() ] */
    }
    
    /*
    Class which computes the kurtosis of the pixels luminance if the maximum luminance is outside
    the standard deviation
     */
    
    private class ComputeKurtosis implements Runnable {        
        private int[] pixels;
        private byte[] yuvData;
        
        ComputeKurtosis(int[] pixels, byte[] yuvData) {
            this.pixels = pixels;
            this.yuvData = yuvData;
        }
        
        @Override
        public void run() {
            int N = pixels.length;
            float K, x;

            float sumX = 0, sumX2 = 0, sumX3 = 0, sumX4 = 0;
            for (int pixel : pixels) {
                x = 0.2126f * Color.red(pixel) + 0.7152f * Color.green(pixel) + 0.0722f * Color.blue(pixel);
                sumX += x;
                float x2 = x * x;
                sumX2 += x2;
                sumX3 += x2 * x;
                sumX4 += x2 * x2;
            }
            float m = sumX / N;
            float m2 = m * m;
            K = ((N*m2*m2 - 4*m2*m*sumX + 6*m2*m2*sumX2 - 4*m*sumX3 + sumX4) / ((sumX2 - N*m2) * (sumX2 - N*m2)) - 3);
            Log.d("DetectorService", "Kurtosis " + K);
            if (K >= 0) {
                //jpegCreatorExecutor.execute(new jpegCreator(yuvData));
            }

        }
    }

    /*
    Class which sends the input yuvData to the native side of the application which populate the
    GPU buffer with said yuvData and schedule the OpenCL kernels.
     */

    double maxLumi = 0;

    private int samplesTaken = 0; // Times the luminance has been sampled during the setup phase

    private class GPUscheduler implements Runnable {
        private Bitmap bitmap;


        GPUscheduler(Bitmap bitmap)
        {
            this.bitmap = bitmap;
        }

        @Override
        public void run() {
            int scaledWidth = bitmap.getWidth() / IN_SAMPLE_SIZE;
            int scaledHeight = bitmap.getHeight() / IN_SAMPLE_SIZE;
            int[] pixels = new int[scaledWidth * scaledHeight];
            bitmap.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight);

            if (samplesTaken <= NUM_OF_SAMPLES) {
                double sampledLumi = computeluminance(openCLObject, pixels, (float)(meanluminance + 5 * standardDeviation));
                meanluminance += sampledLumi;
                meanSquaredLumi += sampledLumi * sampledLumi;
                maxLumi = maxLumi > sampledLumi ? maxLumi : sampledLumi;
                samplesTaken++;
            } else if (samplesTaken == NUM_OF_SAMPLES + 1) {
                meanluminance /= NUM_OF_SAMPLES;
                meanSquaredLumi /= NUM_OF_SAMPLES;
                standardDeviation = Math.sqrt((meanSquaredLumi - meanluminance * meanluminance) * NUM_OF_SAMPLES / (NUM_OF_SAMPLES - 1));
                samplesTaken++;
            }
            else {
                float luminance = computeluminance(openCLObject, pixels, (float)(meanluminance + 5 * standardDeviation));
                if (luminance >= meanluminance + 5 * standardDeviation) {
                    Bitmap highResBitmap = Bitmap.createBitmap(retrieveLuminance(openCLObject), PREVIEW_WIDTH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888);
                    try {
                        jpegCreatorExecutor.execute(new jpegCreator(luminance, highResBitmap));
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
    Class which creates a high res JPEG picture out of the bitmap yuvData if the luminance is above
    threshold, and save it to the storage memory of the device
     */

    private class jpegCreator implements Runnable {
        private float luminance;
        private Bitmap highResBitmap;

        jpegCreator(float luminance, Bitmap bitmap) {
            this.luminance = luminance;
            highResBitmap = bitmap;
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

                // Create the file that will store the image
                File imageFile = createImageFile(luminance);
                FileOutputStream fileOutputStream = new FileOutputStream(imageFile);

                // Just like in the ImageConverter class we need first to convert the image from yuv to jpeg...
                /*
                YuvImage yuvImage = new YuvImage(yuvData, ImageFormat.NV21,PREVIEW_WIDTH, PREVIEW_HEIGHT, null);
                ByteArrayOutputStream byteArrayOS = new ByteArrayOutputStream();
                yuvImage.compressToJpeg(new Rect(0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT), 100, byteArrayOS);
                byte[] data = byteArrayOS.toByteArray();

                // ...then, as before, create a bitmap from the data
                BitmapFactory.Options highResOptions = new BitmapFactory.Options();
                Bitmap highResBitmap = BitmapFactory.decodeByteArray(data, 0, data.length, highResOptions);
                */


                // Store the bitmap in the file
                highResBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
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
    private byte[] frontBuffer;
    private byte[] backBuffer;
    private String bufferUsed = "front";

    private Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            try {
                imageConverterExecutor.execute(new ImageConverter(data.clone()));
            } catch (RejectedExecutionException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DetectorService", stackTrace);
            }

            // Update the average frame rate every 1 sec
            if (++frame == FRAME_RATE) {
                float timeMs = (System.nanoTime() - time) / 1000000; // Time in ms since the last time the average frame rate was updated
                float framerate = (FRAME_RATE * 1000) / timeMs;
                Log.d("DetectorService", "Average frame rate " + framerate + " mean lumi " + meanluminance + " standard dev " + standardDeviation + " max lumi " + maxLumi);
                // TODO: Give the option to chose the threshold from the main menu
                /*
                if (framerate < 5) { // If the framerate is below a certain threshold try to empty the buffer of the GPU scheduler to force hardware acceleration
                    Log.e("DetectorService", "Framerate below minimum: clearing GPUscheduler buffer");
                    GPUschedulerQueue.clear();
                }
                */
                time = System.nanoTime();
                frame = 0;
            }

            // Swap the buffers that store the preview frame // TODO: Perhaps unnecessary? Maybe it would be better not to clone the data but to append a new byte[] everytime
            switch (bufferUsed) {
                case "front":
                    legacyCamera.addCallbackBuffer(backBuffer);
                    bufferUsed = "back";
                    break;
                case "back":
                    legacyCamera.addCallbackBuffer(frontBuffer);
                    bufferUsed = "front";
                    break;
                default:
                    break;
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
                        legacyCamera.setPreviewCallbackWithBuffer(previewCallback);
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

    String currentPhotoPath;

    private File createImageFile(float luminance) throws IOException {
        // Create an image file name
        String timeStamp = new SimpleDateFormat((int)luminance + "_yyyyMMdd_HHmmss", Locale.ITALY).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(
                imageFileName,  /* prefix */
                ".jpg",         /* suffix */
                storageDir      /* directory */
        );

        // Save a file: path for use with ACTION_VIEW intents
        currentPhotoPath = image.getAbsolutePath();
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
        openCLObject = initializeOpenCL(kernel, PREVIEW_WIDTH, PREVIEW_HEIGHT, IN_SAMPLE_SIZE);

        // Initialization of the rendescript used to convert the preview frame from yuv to jpeg
        RenderScript renderScript = RenderScript.create(this);
        yuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(renderScript, Element.U8_4(renderScript));
        previewImageSize = PREVIEW_WIDTH * PREVIEW_HEIGHT * ImageFormat.getBitsPerPixel(ImageFormat.NV21); // Since the default format for the preview is not changed we can assume it's NV21
        allocatioIn = Allocation.createSized(renderScript, Element.U8(renderScript), previewImageSize);
        previewBitmap = Bitmap.createBitmap(PREVIEW_WIDTH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888);
        allocationOut = Allocation.createFromBitmap(renderScript, previewBitmap);
        yuvToRgbIntrinsic.setInput(allocatioIn);

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
                    parameters.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
                    parameters.setPreviewFpsRange(FRAME_RATE_MIN * 1000, FRAME_RATE * 1000);
                    frontBuffer = new byte[previewImageSize];
                    backBuffer = new byte[previewImageSize];
                    legacyCamera.setParameters(parameters);
                    legacyCamera.setDisplayOrientation(90);
                    legacyCamera.addCallbackBuffer(frontBuffer);
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
        shutdown = true;
        synchronized (captureThreadLock) {
            captureThreadLock.notify();
        }
        captureThreadExecutor.shutdown();
        imageConverterExecutor.shutdown();
        GPUkernelSchedulerExec.shutdown();
        luminanceCalculatorExec.shutdown();
        jpegCreatorExecutor.shutdown();
        boolean executorsAreShutdown = true;
        try {
            executorsAreShutdown = captureThreadExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
            executorsAreShutdown &= imageConverterExecutor.awaitTermination(100, TimeUnit.MILLISECONDS);
            executorsAreShutdown &= GPUkernelSchedulerExec.awaitTermination(100, TimeUnit.MILLISECONDS);
            executorsAreShutdown &= luminanceCalculatorExec.awaitTermination(100, TimeUnit.MILLISECONDS);
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
        stopSelf();
    }

    public native long initializeOpenCL(String kernel, int previewWidth, int previewHeight, int inSampleSize);
    public native float computeluminance(long openCLObject, int[] pixels, float lumiThreshold);
    public native int[] retrieveLuminance(long openCLObject);
    public native void closeOpenCL(long openCLObject);
}
