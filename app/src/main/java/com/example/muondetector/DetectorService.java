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
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

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


    @Override
    protected void onHandleIntent(@Nullable Intent intent) {

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e("DetectorService", "Missing CAMERA permission");
            return;
        }

        CameraManager cameraManager = (CameraManager) this.getSystemService(CAMERA_SERVICE);
        StreamConfigurationMap streamConfigMap = null;

        // TODO: Populate the methods
        CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                cameraDevice = camera;
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {

            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {

            }
        };

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
                        streamConfigMap =
                                cameraCharact.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                        // TODO: Tailored handler?
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

        assert streamConfigMap != null;
        int[] outputFormats = streamConfigMap.getOutputFormats();
        Size[] outputSize = null;

        for (int outputFormat : outputFormats) {
            if (outputFormat == ImageFormat.YUV_420_888) {
                outputSize = streamConfigMap.getOutputSizes(outputFormat);
                break;
            }
        }

        // If the YUV_420_888 format is not available exit notifying the user
        if (outputSize == null) {
            // TODO: Handle error
            Log.e("DetectorService", "YUV_420_888 format is not avaiable");
            return;
        }

        for (Size size : outputSize) {
            Log.d("DetectorService", " " + size.getWidth() + " " + size.getHeight());
        }

        // Create a surface with the YUV format and with the highest resolution available
        // TODO: Take 2 pictures at high and low resolution
        ImageReader imageReader = ImageReader.newInstance(outputSize[0].getWidth(), outputSize[0].getHeight(), ImageFormat.YUV_420_888, 1);
        List<Surface> outputsList = new ArrayList<>(1);
        outputsList.add(imageReader.getSurface());

        // TODO: Populate the methods
        CameraCaptureSession.StateCallback sessionStateCallback = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {

            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {

            }
        };

        try {
            cameraDevice.createCaptureSession(outputsList, sessionStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

}
