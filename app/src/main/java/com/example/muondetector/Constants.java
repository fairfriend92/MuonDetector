package com.example.muondetector;

class Constants {
    static int FRAME_RATE = 0;
    static int FRAME_RATE_MIN = 0;
    static int PREVIEW_WIDTH = 0;
    static int PREVIEW_HEIGHT = 0;
    static int PICTURE_WIDTH = 0;
    static int PICTURE_HEIGHT = 0;
    static int NUM_OF_SAMPLES = 0;
    static int CALIBRATION_DURATION = 0;
    static float CROP_FACTOR = 100;
    static int CROP_PICTURE_WIDTH = 0;
    static int CROP_PICTURE_HEIGHT = 0;
    static int CROP_PIC_TOP_X = 0;
    static int CROP_PIC_TOP_Y = 0;
    static int CROP_PIC_BOTTOM_X = 0;
    static int CROP_PIC_BOTTOM_Y = 0;
    static int NUM_OF_SD = 5;
    static int IN_SAMPLE_SIZE = 1;
    static int SAMPLED_PIC_WIDTH = 0;
    static int SAMPLED_PIC_HEIGHT = 0;
    static boolean USE_HW_ACC = false;

    static  void computeAdditionalValues() {
        CROP_PICTURE_WIDTH = (int)(PICTURE_WIDTH * CROP_FACTOR / 100);
        CROP_PICTURE_HEIGHT = (int)(PICTURE_HEIGHT * CROP_FACTOR / 100);
        CROP_PIC_TOP_X = (PICTURE_WIDTH - CROP_PICTURE_WIDTH) / 2;
        CROP_PIC_TOP_Y = (PICTURE_HEIGHT - CROP_PICTURE_HEIGHT) / 2;
        CROP_PIC_BOTTOM_X = CROP_PICTURE_WIDTH + CROP_PIC_TOP_X;
        CROP_PIC_BOTTOM_Y = CROP_PICTURE_HEIGHT + CROP_PIC_TOP_Y;
        NUM_OF_SAMPLES = FRAME_RATE_MIN * CALIBRATION_DURATION;
        SAMPLED_PIC_WIDTH = CROP_PICTURE_WIDTH / IN_SAMPLE_SIZE;
        SAMPLED_PIC_HEIGHT = CROP_PICTURE_HEIGHT / IN_SAMPLE_SIZE;
    }
}
