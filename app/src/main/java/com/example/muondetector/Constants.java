package com.example.muondetector;


import android.graphics.ImageFormat;

class Constants {
    static int FRAME_RATE = 0;
    static int FRAME_RATE_MIN = 0;
    static int PREVIEW_WIDTH = 0;
    static int PREVIEW_HEIGHT = 0;
    static int SCALED_WIDTH = 0;
    static int SCALED_HEIGHT = 0;
    static int IN_SAMPLE_SIZE = 0;
    static int PREVIEW_IMAGE_SIZE = 0;
    static int NUM_OF_SAMPLES = 0;
    static int CALIBRATION_DURATION = 0;
    static float CROP_FACTOR = 100;
    static int CROP_WIDTH = 0;
    static int CROP_HEIGHT = 0;
    static int CROP_TOP_X = 0;
    static int CROP_TOP_Y = 0;
    static int CROP_BOTTOM_X = 0;
    static int CROP_BOTTOM_Y = 0;
    static int NUM_OF_SD = 5;

    static  void computeAdditionalValues() {
        CROP_WIDTH = (int)(PREVIEW_WIDTH * CROP_FACTOR / 100);
        CROP_HEIGHT = (int)(PREVIEW_HEIGHT * CROP_FACTOR / 100);
        CROP_TOP_X = (PREVIEW_WIDTH - CROP_WIDTH) / 2;
        CROP_TOP_Y = (PREVIEW_HEIGHT - CROP_HEIGHT) / 2;
        CROP_BOTTOM_X = CROP_WIDTH + CROP_TOP_X;
        CROP_BOTTOM_Y = CROP_HEIGHT + CROP_TOP_Y;
        SCALED_WIDTH = CROP_WIDTH / IN_SAMPLE_SIZE;
        SCALED_HEIGHT = CROP_HEIGHT / IN_SAMPLE_SIZE;
        PREVIEW_IMAGE_SIZE = Constants.PREVIEW_WIDTH * Constants.PREVIEW_HEIGHT * ImageFormat.getBitsPerPixel(ImageFormat.NV21);
        NUM_OF_SAMPLES = FRAME_RATE_MIN * CALIBRATION_DURATION;
    }
}
