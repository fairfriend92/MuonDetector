package com.example.muondetector;

import android.Manifest;
import android.app.IntentService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Class which extends IntentService in order to run in the background. This is the main thread
 * where the detection of the muons takes place
 */

public class DetectorService extends IntentService {

    private static boolean shutdown = false;

    public DetectorService() {
        super("DetectorService");
    }

    public static void shutdownService() {
        shutdown = true;
    }

    private CameraDevice cameraDevice;
    private static StreamConfigurationMap streamConfigMap = null;
    private static List<Surface> outputsList = new ArrayList<>(3); // List of surfaces to draw the capture onto

    public static SurfaceHolder.Callback surfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Size previewSurfaceSize = streamConfigMap.getOutputSizes(SurfaceHolder.class)[0]; // Use the highest resolution for the preview surface
            holder.setFixedSize(previewSurfaceSize.getWidth(), previewSurfaceSize.getHeight());
            outputsList.add(holder.getSurface());
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {

        }
    };

    CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            Log.d("DetectorService", "test");
        }
    };

    CameraCaptureSession.StateCallback sessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onActive(@NonNull CameraCaptureSession session) {
            Log.d("DetectorService", "test");
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            Log.d("DetectorService", "test");
        }

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            Log.d("DetectorService", "test1");
            try {
                CaptureRequest.Builder captReqBuilder =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                captReqBuilder.addTarget(outputsList.get(0)); // Add the preview surface as a target
                CaptureRequest captureRequest = captReqBuilder.build();
                session.capture(captureRequest, captureCallback, null);
            } catch (CameraAccessException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DetectorService", stackTrace);
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e("DetectorService", "CameraCaptureSession configuration failed");
        }

        @Override
        public void onReady(@NonNull CameraCaptureSession session) {
            Log.d("DetectorService", "test");
        }

        @Override
        public void onSurfacePrepared(@NonNull CameraCaptureSession session, Surface surface) {
            Log.d("DetectorService", "test");
        }
    };

    CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;

            assert streamConfigMap != null;
            int[] outputFormats = streamConfigMap.getOutputFormats();
            Size[] outputSize = null;

            Intent previewSurfaceBroadcast = new Intent("CreatePreviewSurface");
            LocalBroadcastManager.getInstance(DetectorService.this).sendBroadcast(previewSurfaceBroadcast);

            // Create surfaces for the postprocessing
            for (int outputFormat : outputFormats) {
                if (outputFormat == ImageFormat.YUV_420_888) {
                    outputSize = streamConfigMap.getOutputSizes(outputFormat);

                    // Create high resolution surface
                    ImageReader imageReader = ImageReader.newInstance(outputSize[0].getWidth(),
                            outputSize[0].getHeight(), ImageFormat.YUV_420_888, 1);
                    outputsList.add(imageReader.getSurface());

                    // Create low resolution surface
                    imageReader = ImageReader.newInstance(outputSize[outputSize.length - 1].getWidth(),
                            outputSize[outputSize.length - 1].getHeight(), ImageFormat.YUV_420_888, 1);
                    outputsList.add(imageReader.getSurface());

                    break;
                }
            }

            // If the YUV_420_888 format is not available exit notifying the user
            if (outputSize == null) {
                Log.e("DetectorService", "YUV_420_888 format is not avaiable");
                return;
            }

            try {
                cameraDevice.createCaptureSession(outputsList, sessionStateCallback, null);
                Log.d("DetectorService", "test opened");
            } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("DetectorService", stackTrace);
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
        }
    };

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED) {
            // TODO: request permissions in app, send broadcast to MainActivity to do that (must target 25)
            Log.e("DetectorService", "Missing CAMERA permission");
            return;
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

    }

}
