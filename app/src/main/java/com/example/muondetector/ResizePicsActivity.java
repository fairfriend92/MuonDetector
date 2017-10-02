package com.example.muondetector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;

import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.view.GestureDetectorCompat;
import android.support.v4.view.ViewCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.File;

public class ResizePicsActivity extends AppCompatActivity {

    private GestureDetectorCompat myGestureDetector;
    private ScaleGestureDetector myScaleGestureDetector;
    private MyImageView cropPictureView;
    static Bitmap bitmap;
    static Matrix drawMatrix = new Matrix();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.resize_pics_activity);

        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (storageDir != null) {
            String picsFolderPath = storageDir.getAbsolutePath();
            File picsFolder = new File(picsFolderPath);
            File[] pics = picsFolder.listFiles();

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inMutable = true;
            bitmap = BitmapFactory.decodeFile(pics[0].getAbsolutePath(), options);

            MyOnTouchListener myOnTouchListener = new MyOnTouchListener();
            myGestureDetector = new GestureDetectorCompat(this, new MyGestureDetector());
            myScaleGestureDetector = new ScaleGestureDetector(this, new MyScaleGestureDetector());

            cropPictureView = (MyImageView) findViewById(R.id.cropPictureView);
            cropPictureView.setOnTouchListener(myOnTouchListener);
            cropPictureView.setImageDrawable(new BitmapDrawable(getResources(), bitmap));
        }
    }

    private class MyOnTouchListener implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            return myScaleGestureDetector.onTouchEvent(event);
        }
    }

    private class MyGestureDetector extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent event) {
            return myScaleGestureDetector.onTouchEvent(event);
        }
    }

    private class MyScaleGestureDetector extends ScaleGestureDetector.SimpleOnScaleGestureListener {

        float lastFocusX;
        float lastFocusY;

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            lastFocusX = detector.getFocusX();
            lastFocusY = detector.getFocusY();
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            Matrix transformationMatrix = new Matrix();
            float focusX = detector.getFocusX();
            float focusY = detector.getFocusY();

            transformationMatrix.postTranslate(-focusX, -focusY);
            transformationMatrix.postScale(detector.getScaleFactor(), detector.getScaleFactor());
            float focusShiftX = focusX - lastFocusX;
            float focusShiftY = focusY - lastFocusY;
            transformationMatrix.postTranslate(focusX + focusShiftX, focusY + focusShiftY);
            drawMatrix.postConcat(transformationMatrix);
            lastFocusX = focusX;
            lastFocusY = focusY;

            ViewCompat.postInvalidateOnAnimation(cropPictureView);
            return true;
        }
    }

    public static class MyImageView extends android.support.v7.widget.AppCompatImageView {
        public MyImageView(Context context) {
            super(context);
        }

        public MyImageView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public MyImageView(Context context, AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
        }

        @Override
        public void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.drawBitmap(bitmap, drawMatrix, null);
            canvas.restore();
        }
    }

}
