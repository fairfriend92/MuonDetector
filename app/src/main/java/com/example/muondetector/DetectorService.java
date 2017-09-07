package com.example.muondetector;

import android.Manifest;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.IBinder;
import android.os.Process;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.Math.abs;

/**
 * Class which extends Service in order to run in the background. Opens the camera and regularly
 * takes pictures which are later processed by a separate thread in order to recognize eventual
 * particle traces.
 */

@SuppressWarnings("deprecation")
public class DetectorService extends Service {

    // The following constants are used whenever the camera cannot provide the range of available values
    private static final long MAX_EXPOSURE_TIME = 1000000000;
    private static final float MAX_LENS_APERTURE = (float)1.4;
    private static final int MAX_ISO = 1600;

    private static final int NOTIFICATION_ID = 0;
    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;
    private static final int IN_SAMPLE_SIZE = 4;

    private CameraDevice cameraDevice;
    private static boolean shutdown = false;
    private static StreamConfigurationMap streamConfigMap = null;
    private static List<Surface> outputsList = Collections.synchronizedList(new ArrayList<Surface>(3)); // List of surfaces to draw the capture onto
    static Camera legacyCamera; // Use the old camera api in case the hardware does not support iso control using camera2
    private static BlockingQueue<byte[]> captureDataQueue = new ArrayBlockingQueue<>(32);
    private static final Object captureThreadLock = new Object();

    private List<CaptureResult.Key<?>> captureResultKeys;
    private List<CaptureRequest.Key<?>> captureRequestKeys;
    private Range<Long> exposureTimeRange;
    private float[] apertureSizeRange;
    private Range<Integer> ISORange;
    private NotificationCompat.Builder notificationBuilder;
    private long time = 0;
    private ExecutorService imageConverterExecutor = Executors.newCachedThreadPool();
    private ExecutorService luminescenceCalculationExecutror = Executors.newSingleThreadExecutor();
    private ExecutorService jpegCreatorExecutor = Executors.newSingleThreadExecutor();
    private boolean notificationShowed = false;
    private long openCLObject;
    private BlockingQueue<ImageInfo> imageInfos = new ArrayBlockingQueue<>(32);

    private class ImageInfo {

        private Bitmap bitmap;
        private byte[] data;

        ImageInfo(Bitmap b, byte[] b1) {
            bitmap = b;
            data = b1;
        }

    }

    private class imageConverter implements Runnable {

        private byte[] yuvData;
        private Camera camera;

        imageConverter(byte[] b, Camera c) {
            yuvData = b;
            camera = c;
        }

        @Override
        public void run () {
            // Convert the YUV preview frame to jpeg
            Camera.Size previewSize = camera.getParameters().getPreviewSize();
            YuvImage yuvImage = new YuvImage(yuvData, ImageFormat.NV21, previewSize.width, previewSize.height, null);
            ByteArrayOutputStream byteArrayOS = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, previewSize.width, previewSize.height), 100, byteArrayOS);
            byte[] jdata = byteArrayOS.toByteArray();

            // Crate low res bitmap from jpeg yuvData
            BitmapFactory.Options lowResOptions = new BitmapFactory.Options();
            lowResOptions.inSampleSize = IN_SAMPLE_SIZE;
            Bitmap lowResBitmap = BitmapFactory.decodeByteArray(jdata, 0, jdata.length, lowResOptions);

            // Put the bitmap and the data in the buffer of the separate thread computing the luminiscence
            try {
                imageInfos.put(new ImageInfo(lowResBitmap, jdata));
                Log.d("DetectorService", " "  + imageInfos.remainingCapacity());
            } catch (InterruptedException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DetectorService", stackTrace);
            }
        }

    }

    private class luminescenceCalculcation implements Runnable {

        private ImageInfo imageInfo;
        private BlockingQueue<ImageInfo> imageInfos;

        luminescenceCalculcation(BlockingQueue<ImageInfo> b) {
            imageInfos = b;
        }

        @Override
        public void run() {
            while (!shutdown) {
                // Compute the luminance for each pixel
                try {
                    imageInfo = imageInfos.take();
                } catch (InterruptedException e) {
                    String stackTrace = Log.getStackTraceString(e);
                    Log.e("DetectorService", stackTrace);
                }
                Bitmap lowResBitmap = imageInfo.bitmap;
                int scaledWidth = lowResBitmap.getWidth() / IN_SAMPLE_SIZE;
                int scaledHeight = lowResBitmap.getHeight() / IN_SAMPLE_SIZE;
                int[] pixels = new int[scaledWidth * scaledHeight];
                lowResBitmap.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight);
                /*
                float luminance = 0;

                for (int pixel : pixels) {
                    luminance = 0.2126f * Color.red(pixel) + 0.7152f * Color.green(pixel) + 0.0722f * Color.blue(pixel);
                }

                //Log.d("DetectorService", "Luminescence " + luminance);
                if (luminance > 30)
                    jpegCreatorExecutor.submit(new jpegCreator(jdata));
                */

                if (computeLuminescence(openCLObject, pixels, PREVIEW_WIDTH, PREVIEW_HEIGHT, IN_SAMPLE_SIZE))
                    jpegCreatorExecutor.submit(new jpegCreator(imageInfo.data));
            }
        }

    }

    private class jpegCreator implements Runnable {

        private byte[] jdata;

        jpegCreator(byte[] b) {
            jdata = b;
        }

        @Override
        public void run() {
            try {
                if (!notificationShowed) {
                    NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
                    notificationShowed = true;
                }
                File imageFile = createImageFile();
                FileOutputStream fileOutputStream = new FileOutputStream(imageFile);
                BitmapFactory.Options highResOptions = new BitmapFactory.Options();
                Bitmap highResBitmap = BitmapFactory.decodeByteArray(jdata, 0, jdata.length, highResOptions);
                highResBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
                fileOutputStream.close();
            } catch (IOException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DetectorService", stackTrace);
            }
        }

    }

    private Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {

        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            imageConverterExecutor.submit(new imageConverter(data, camera));

            Log.d("DetectorService", " " + (System.nanoTime() - time) / 1000000 + " ms");
            time = System.nanoTime();
        }
    };

    ExecutorService captureThreadExecutor = Executors.newSingleThreadExecutor();
    private class CaptureThread implements Runnable {

        @Override
        public void run () {

            notificationBuilder = new NotificationCompat.Builder(DetectorService.this)
                    .setContentTitle("Trigger notification")
                    .setContentText("Trigger activated")
                    .setSmallIcon(R.drawable.trigger_event_icon);

            while (!shutdown) {
                synchronized (captureThreadLock) {
                    try {
                        captureThreadLock.wait();
                    } catch (InterruptedException e) {
                        String stackTrace = Log.getStackTraceString(e);
                        Log.e("DetectorService", stackTrace);
                    }
                }

                legacyCamera.setPreviewCallback(previewCallback);
                legacyCamera.startPreview();
            }
        }
    }

    static SurfaceHolder.Callback surfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            //Log.d("DetectorService", "surfaceHolderCallback surfaceCreated");
            if (streamConfigMap != null) { // Code executes only if camera2 is being used
                Size previewSurfaceSize = streamConfigMap.getOutputSizes(SurfaceHolder.class)[0]; // Use the highest resolution for the preview surface
                holder.setFixedSize(previewSurfaceSize.getWidth(), previewSurfaceSize.getHeight());
                outputsList.add(holder.getSurface());
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            //Log.d("DetectorService", "surfaceHolderCallback surfaceChanged");
            assert holder.getSurface() != null;
            try {
                legacyCamera.stopPreview();
            } catch (Exception e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DetectorService", stackTrace);
            }

            try {
                legacyCamera.setPreviewDisplay(holder);
                synchronized (captureThreadLock) {
                    captureThreadLock.notify();
                }
            } catch (Exception e){
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DetectorService", stackTrace);
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            //Log.d("DetectorService", "surfaceHolderCallback surfaceDestroyed");
        }
    };

    private CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            if (captureResultKeys.contains(CaptureResult.LENS_APERTURE))
                Log.i("DetectorService", "Lens aperture is " + result.get(CaptureResult.LENS_APERTURE));
            //Log.d("DetectorService", "captureCallback onCaptureCompleted");
        }
    };

    private CameraCaptureSession.StateCallback sessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onActive(@NonNull CameraCaptureSession session) {
            //Log.d("DetectorService", "sessionStateCallback onConfigured onActive");
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            //Log.d("DetectorService", "sessionStateCallback onConfigured onClosed");
        }

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            //Log.d("DetectorService", "sessionStateCallback onConfigured");
            try {
                CaptureRequest captureRequest = createCaptureRequestBuilder().build();
                session.capture(captureRequest, captureCallback, null);
            } catch (CameraAccessException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DetectorService", stackTrace);
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            //Log.e("DetectorService", "sessionStateCallback configuration failed");
        }

        @Override
        public void onReady(@NonNull CameraCaptureSession session) {
            //Log.d("DetectorService", "sessionStateCallback onReady");
        }

        @Override
        public void onSurfacePrepared(@NonNull CameraCaptureSession session, @NonNull Surface surface) {
            //Log.d("DetectorService", "sessionStateCallback onSurfacePrepared");
        }
    };

    private CaptureRequest.Builder createCaptureRequestBuilder () throws CameraAccessException {
        CaptureRequest.Builder captReqBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        captReqBuilder.addTarget(outputsList.get(0)); // Add the preview surface as a target
        captReqBuilder.addTarget(outputsList.get(1)); // Add JPEG surface

        // Manual settings
        captReqBuilder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF);
        captReqBuilder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF);
        captReqBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        captReqBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        captReqBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
        captReqBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF);
        captReqBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF);
        captReqBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
        captReqBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        captReqBuilder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF);
        captReqBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        captReqBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
        if (exposureTimeRange != null) {
            Log.i("DetectorService", "Upper exposure time is " + exposureTimeRange.getUpper());
            captReqBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeRange.getUpper());
        }
        else {
            Log.i("DetectorService", "Exposure time range is not available");
            captReqBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, MAX_EXPOSURE_TIME);
        }
        if (apertureSizeRange != null) {
            Log.i("DetectorService", "Maximum lens aperture is " + apertureSizeRange[apertureSizeRange.length - 1]);
            captReqBuilder.set(CaptureRequest.LENS_APERTURE, apertureSizeRange[apertureSizeRange.length - 1]);
        } else {
            Log.i("DetectorService", "List of lens apertures is not available");
            captReqBuilder.set(CaptureRequest.LENS_APERTURE, MAX_LENS_APERTURE);
        }
        if (ISORange != null) {
            Log.i("DetectorService", "Maximum ISO is " + ISORange.getUpper());
            captReqBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, ISORange.getUpper());
        } else {
            Log.i("DetectorService", "ISO range is not available");
            captReqBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, MAX_ISO);
        }
        return captReqBuilder;
    }

    String currentPhotoPath;

    private File createImageFile() throws IOException {
        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(new Date());
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

    private ImageReader jpegImageReader;

    private CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;

            assert streamConfigMap != null;
            int[] outputFormats = streamConfigMap.getOutputFormats();
            Size[] outputSize = null;

            // Create surfaces for the postprocessing
            for (int outputFormat : outputFormats) {
                if (outputFormat == ImageFormat.FLEX_RGB_888) {
                    outputSize = streamConfigMap.getOutputSizes(outputFormat);

                    // Create high resolution RGB surface
                    ImageReader higResRGBImgReader = ImageReader.newInstance(outputSize[0].getWidth(),
                            outputSize[0].getHeight(), ImageFormat.FLEX_RGB_888, 1);
                    //outputsList.add(higResRGBImgReader.getSurface());

                    // Create low resolution RGB surface
                    ImageReader lowResRGBImgReader = ImageReader.newInstance(outputSize[outputSize.length - 1].getWidth(),
                            outputSize[outputSize.length - 1].getHeight(), ImageFormat.FLEX_RGB_888, 1);
                    //outputsList.add(lowResRGBImgReader.getSurface());

                    break;
                } else if (outputFormat == ImageFormat.JPEG) {
                    outputSize = streamConfigMap.getOutputSizes(outputFormat);
                    jpegImageReader = ImageReader.newInstance(outputSize[outputSize.length - 1].getWidth(),
                            outputSize[outputSize.length - 1].getHeight(), ImageFormat.JPEG, 1);
                    outputsList.add(jpegImageReader.getSurface());
                }
            }

            try {
                //Log.d("DetectorService", "createCaptureSession onOpened");
                cameraDevice.createCaptureSession(outputsList, sessionStateCallback, null);
            } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DetectorService", stackTrace);
            }

            jpegImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    //Log.d("DetectorService", "JPEG image created");

                    // Save JPEG picture in internal storage
                    Image latestImage = reader.acquireLatestImage();
                    ByteBuffer imageBuffer = latestImage.getPlanes()[0].getBuffer();
                    byte[] imageBytes = new byte[imageBuffer.capacity()];
                    imageBuffer.get(imageBytes);
                    Bitmap bitmapImage = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, null);
                    try {
                        File imageFile = createImageFile();
                        FileOutputStream fileOutputStream = new FileOutputStream(imageFile);
                        bitmapImage.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);
                        fileOutputStream.close();
                    } catch (IOException e) {
                        String stackTrace = Log.getStackTraceString(e);
                        Log.e("DetectorService", stackTrace);
                    }
                }
            }, null);
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
        }
    };

    @Override
    public IBinder onBind (Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        assert intent != null;
        String kernel = intent.getStringExtra("Kernel");
        openCLObject = initializeOpenCL(kernel, PREVIEW_WIDTH, PREVIEW_HEIGHT, IN_SAMPLE_SIZE);

        luminescenceCalculationExecutror.submit(new luminescenceCalculcation(imageInfos));

        captureThreadExecutor.submit(new CaptureThread());
        Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED) {
            // TODO: request permissions in app, send broadcast to MainActivity to do that (must target 25)
            Log.e("DetectorService", "Missing CAMERA permission");
            return START_STICKY;
        }

        CameraManager cameraManager = (CameraManager) this.getSystemService(CAMERA_SERVICE);

        boolean manualControlAvailable = false;

        try {
            String[] cameraIDs = cameraManager.getCameraIdList();

            // Find the id of the back facing camera, get its characteristics and open it
            for (String cameraId : cameraIDs) {
                CameraCharacteristics cameraCharact =
                        cameraManager.getCameraCharacteristics(cameraId);
                Integer lensFacingKey = cameraCharact.get(CameraCharacteristics.LENS_FACING);
                assert lensFacingKey != null;
                switch (lensFacingKey) {
                    case CameraCharacteristics.LENS_FACING_FRONT:
                        Log.i("DetectorService", "Camera with id " + cameraId + " is front facing");
                        break;
                    case CameraCharacteristics.LENS_FACING_BACK:
                        Log.i("DetectorService", "Camera with id " + cameraId + " is back facing");

                        // Get the overall hardware level
                        Integer supportedHardwareLevel = cameraCharact.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                        assert supportedHardwareLevel != null;
                        switch (supportedHardwareLevel) {
                            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                                Log.i("DetectorService", "Legacy hardware");
                                break;
                            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                                Log.i("DetectorService", "Limited hardware");
                                break;
                            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                                Log.i("DetectorService", "Full hardware");
                                break;
                            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                                Log.i("DetectorService", "Level 3 hardware");
                                break;
                        }

                        // Get the available settings for the capture result and the capture request
                        captureResultKeys = cameraCharact.getAvailableCaptureResultKeys();
                        /*
                        for (CaptureResult.Key key : captureResultKeys) {
                            Log.i("DetectorService", "Capture result suppoerted key: " + key.getName());
                        }
                        */
                        captureRequestKeys = cameraCharact.getAvailableCaptureRequestKeys();
                        /*
                        for (CaptureRequest.Key key : captureRequestKeys) {
                            Log.i("DetectorService", "Capture request suppoerted key: " + key.getName());
                        }
                        */

                        manualControlAvailable = captureRequestKeys.contains(CaptureRequest.SENSOR_EXPOSURE_TIME) &&
                                captureRequestKeys.contains(CaptureRequest.SENSOR_SENSITIVITY);

                        // Use the new camera2 api only if the vendor has implemented basic manual control
                        if (manualControlAvailable) {
                            exposureTimeRange = cameraCharact.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                            apertureSizeRange = cameraCharact.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
                            ISORange = cameraCharact.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);

                            streamConfigMap = cameraCharact.get(
                                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                            cameraManager.openCamera(cameraId, cameraStateCallback, null);
                        }
                        break;
                    default:
                        break;
                }
            }

        } catch (CameraAccessException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("DetectorService", stackTrace);
        }

        // Use the old camera api if the vendor has NOT implemented basic manual control through camera2
        if (!manualControlAvailable) {
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
                parameters.setPreviewFpsRange(24000,24000);
                legacyCamera.setParameters(parameters);
                legacyCamera.setDisplayOrientation(90);
            } catch (Exception e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DetectorService", stackTrace);
            }
        }

        return START_STICKY;
    }

    public native long initializeOpenCL(String kernel, int previewWidth, int previewHeight, int inSampleSize);
    public native boolean computeLuminescence(long openCLObject, int[] pixels, int previewWidth, int previewHeight, int inSampleSize);
}
