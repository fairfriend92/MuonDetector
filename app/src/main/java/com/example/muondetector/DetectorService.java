package com.example.muondetector;

import android.Manifest;
import android.app.IntentService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.util.Log;

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

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {

        CameraManager cameraManager = (CameraManager) this.getSystemService(CAMERA_SERVICE);

        CameraDevice cameraDevice;

        try {
            String[] cameraIDs = cameraManager.getCameraIdList();
            for (String cameraId : cameraIDs) {
                CameraCharacteristics cameraCharacteristics =
                        cameraManager.getCameraCharacteristics(cameraId);
                Integer lensFacingKey = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                assert lensFacingKey != null;
                switch (lensFacingKey) {
                    case CameraCharacteristics.LENS_FACING_FRONT:
                        Log.d("DetectorService", "Camera with id " + cameraId + " is front facing");
                        break;
                    case CameraCharacteristics.LENS_FACING_BACK:
                        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {

                            return;
                        }
                        Log.d("DetectorService", "Camera with id " + cameraId + " is back facing");
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
