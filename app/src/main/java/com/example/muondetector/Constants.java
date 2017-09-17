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

    static  void computeAdditionalValues() {
        SCALED_WIDTH = PREVIEW_WIDTH / IN_SAMPLE_SIZE;
        SCALED_HEIGHT = PREVIEW_HEIGHT / IN_SAMPLE_SIZE;
        PREVIEW_IMAGE_SIZE = Constants.PREVIEW_WIDTH * Constants.PREVIEW_HEIGHT * ImageFormat.getBitsPerPixel(ImageFormat.NV21);

    }
}
