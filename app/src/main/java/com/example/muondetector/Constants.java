package com.example.muondetector;


import android.graphics.ImageFormat;

class Constants {
    static int FRAME_RATE = 0;
    static int FRAME_RATE_MIN = 0;
    static int PREVIEW_WIDTH = 0;
    static int PREVIEW_HEIGHT = 0;
    static int PICTURE_WIDTH = 0;
    static int PICTURE_HEIGHT = 0;
    static int PREVIEW_IMAGE_SIZE = 0;
    static int NUM_OF_SAMPLES = 0;
    static int CALIBRATION_DURATION = 0;
    static float CROP_FACTOR = 100;
    static int CROP_PREVIEW_WIDTH = 0;
    static int CROP_PREVIEW_HEIGHT = 0;
    static int CROP_PICTURE_WIDTH = 0;
    static int CROP_PICTURE_HEIGHT = 0;
    static int CROP_TOP_X = 0;
    static int CROP_TOP_Y = 0;
    static int CROP_PIC_TOP_X = 0;
    static int CROP_PIC_TOP_Y = 0;
    static int CROP_BOTTOM_X = 0;
    static int CROP_BOTTOM_Y = 0;
    static int CROP_PIC_BOTTOM_X = 0;
    static int CROP_PIC_BOTTOM_Y = 0;
    static int NUM_OF_SD = 5;

    static  void computeAdditionalValues() {
        CROP_PREVIEW_WIDTH = (int)(PREVIEW_WIDTH * CROP_FACTOR / 100);
        CROP_PREVIEW_HEIGHT = (int)(PREVIEW_HEIGHT * CROP_FACTOR / 100);
        CROP_PICTURE_WIDTH = (int)(PICTURE_WIDTH * CROP_FACTOR / 100);
        CROP_PICTURE_HEIGHT = (int)(PICTURE_HEIGHT * CROP_FACTOR / 100);
        CROP_TOP_X = (PREVIEW_WIDTH - CROP_PREVIEW_WIDTH) / 2;
        CROP_TOP_Y = (PREVIEW_HEIGHT - CROP_PREVIEW_HEIGHT) / 2;
        CROP_PIC_TOP_X = (PICTURE_WIDTH - CROP_PICTURE_WIDTH) / 2;
        CROP_PIC_TOP_Y = (PICTURE_HEIGHT - CROP_PICTURE_HEIGHT) / 2;
        CROP_BOTTOM_X = CROP_PREVIEW_WIDTH + CROP_TOP_X;
        CROP_BOTTOM_Y = CROP_PREVIEW_HEIGHT + CROP_TOP_Y;
        CROP_PIC_BOTTOM_X = CROP_PICTURE_WIDTH + CROP_PIC_TOP_X;
        CROP_PIC_BOTTOM_Y = CROP_PICTURE_HEIGHT + CROP_PIC_TOP_Y;
        PREVIEW_IMAGE_SIZE = Constants.PREVIEW_WIDTH * Constants.PREVIEW_HEIGHT * ImageFormat.getBitsPerPixel(ImageFormat.NV21);
        NUM_OF_SAMPLES = FRAME_RATE_MIN * CALIBRATION_DURATION;
    }
}
