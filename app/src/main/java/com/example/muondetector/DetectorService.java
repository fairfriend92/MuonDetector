package com.example.muondetector;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;

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

/**
 * Class which extends Service in order to run in the background. Opens the camera and regularly
 * takes pictures which are later processed by a separate thread in order to recognize eventual
 * particle traces.
 */

public class DetectorService extends Service {

    private CameraDevice cameraDevice;
    private static StreamConfigurationMap streamConfigMap = null;
    private static List<Surface> outputsList = Collections.synchronizedList(new ArrayList<Surface>(3)); // List of surfaces to draw the capture onto

    static SurfaceHolder.Callback surfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d("DetectorService", "surfaceHolderCallback surfaceCreated");
            Size previewSurfaceSize = streamConfigMap.getOutputSizes(SurfaceHolder.class)[0]; // Use the highest resolution for the preview surface
            holder.setFixedSize(previewSurfaceSize.getWidth(), previewSurfaceSize.getHeight());
            outputsList.add(holder.getSurface());
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d("DetectorService", "surfaceHolderCallback surfaceChanged");
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d("DetectorService", "surfaceHolderCallback surfaceDestroyed");
        }
    };

    private CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            Log.d("DetectorService", "captureCallback onCaptureCompleted");
        }
    };

    private CameraCaptureSession.StateCallback sessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onActive(@NonNull CameraCaptureSession session) {
            Log.d("DetectorService", "sessionStateCallback onConfigured onActive");
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            Log.d("DetectorService", "sessionStateCallback onConfigured onClosed");
        }

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            Log.d("DetectorService", "sessionStateCallback onConfigured");
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
            Log.e("DetectorService", "sessionStateCallback configuration failed");
        }

        @Override
        public void onReady(@NonNull CameraCaptureSession session) {
            Log.d("DetectorService", "sessionStateCallback onReady");
        }

        @Override
        public void onSurfacePrepared(@NonNull CameraCaptureSession session, @NonNull Surface surface) {
            Log.d("DetectorService", "sessionStateCallback onSurfacePrepared");
        }
    };

    private CaptureRequest.Builder createCaptureRequestBuilder () throws CameraAccessException {
        CaptureRequest.Builder captReqBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        captReqBuilder.addTarget(outputsList.get(0)); // Add the preview surface as a target
        captReqBuilder.addTarget(outputsList.get(1)); // Add JPEG surface
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
                if (outputFormat == ImageFormat.YUV_420_888) {
                    outputSize = streamConfigMap.getOutputSizes(outputFormat);

                    // Create high resolution YUV surface
                    ImageReader higResYUVImgReader = ImageReader.newInstance(outputSize[0].getWidth(),
                            outputSize[0].getHeight(), ImageFormat.YUV_420_888, 1);
                    //outputsList.add(higResYUVImgReader.getSurface());

                    // Create low resolution YUV surface
                    ImageReader lowResYUVImgReader = ImageReader.newInstance(outputSize[outputSize.length - 1].getWidth(),
                            outputSize[outputSize.length - 1].getHeight(), ImageFormat.YUV_420_888, 1);
                    //outputsList.add(lowResYUVImgReader.getSurface());

                    break;
                } else if (outputFormat == ImageFormat.JPEG) {
                    outputSize = streamConfigMap.getOutputSizes(outputFormat);
                    jpegImageReader = ImageReader.newInstance(outputSize[outputSize.length - 1].getWidth(),
                            outputSize[outputSize.length - 1].getHeight(), ImageFormat.JPEG, 1);
                    outputsList.add(jpegImageReader.getSurface());
                }
            }

            try {
                cameraDevice.createCaptureSession(outputsList, sessionStateCallback, null);
                Log.d("DetectorService", "createCaptureSession onOpened");
            } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DetectorService", stackTrace);
            }

            jpegImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Log.d("DetectorService", "JPEG image created");
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

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED) {
            // TODO: request permissions in app, send broadcast to MainActivity to do that (must target 25)
            Log.e("DetectorService", "Missing CAMERA permission");
            return START_NOT_STICKY;
        }

        CameraManager cameraManager = (CameraManager) this.getSystemService(CAMERA_SERVICE);

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
                        Log.d("DetectorService", "Camera with id " + cameraId + " is front facing");
                        break;
                    case CameraCharacteristics.LENS_FACING_BACK:
                        Log.d("DetectorService", "Camera with id " + cameraId + " is back facing");
                        streamConfigMap = cameraCharact.get(
                                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                        cameraManager.openCamera(cameraId, cameraStateCallback, null);
                        break;
                    default:
                        break;
                }
            }

        } catch (CameraAccessException e) {
            String stackTrace = Log.getStackTraceString(e);
            Log.e("DetectorService", stackTrace);
        }

        return START_NOT_STICKY;
    }

}
