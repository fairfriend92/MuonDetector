package com.example.muondetector;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;

import android.graphics.Paint;
import android.graphics.RectF;
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
import android.widget.CompoundButton;
import android.widget.ToggleButton;

import java.io.File;

public class ResizePicsActivity extends AppCompatActivity {

    private GestureDetectorCompat myGestureDetector;
    private ScaleGestureDetector myScaleGestureDetector;
    private MyImageView cropPictureView;

    private static final float MIN_ZOOM = 1.0f;
    private static final float MAX_ZOOM = 5.0f;
    private static float scaleFactor = 1.0f, translateX = 0.0f, translateY = 0.0f,
            startX = 0.0f, startY = 0.0f, endX = 0.0f, endY = 0.0f;
    private static boolean croppingPic = false;
    private static Bitmap bitmap;

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

            ToggleButton toggleButton = (ToggleButton) findViewById(R.id.cropRectButton);
            toggleButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    croppingPic = isChecked;
                }
            });
        }
    }

    private class MyOnTouchListener implements View.OnTouchListener {
        private static final int NONE = 0, DRAG = 1, ZOOM = 2;
        private int mode;

        private float previousTranslateX = 0.0f, previousTranslateY = 0.0f;

        private boolean dragged;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (!croppingPic) {
                switch (event.getAction() & MotionEvent.ACTION_MASK) {
                    case MotionEvent.ACTION_DOWN:
                        mode = DRAG;
                        startX = event.getX() - previousTranslateX;
                        startY = event.getY() - previousTranslateY;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        translateX = event.getX() - startX;
                        translateY = event.getY() - startY;
                        double distance = Math.sqrt(Math.pow(event.getX() - (startX + previousTranslateX), 2) +
                                Math.pow(event.getY() - (startY + previousTranslateY), 2));
                        if (distance > 0) {
                            dragged = true;
                        }
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        mode = ZOOM;
                        break;
                    case MotionEvent.ACTION_UP:
                        mode = NONE;
                        dragged = false;
                        previousTranslateX = translateX;
                        previousTranslateY = translateY;
                        break;
                    case MotionEvent.ACTION_POINTER_UP:
                        mode = DRAG;
                        previousTranslateX = translateX;
                        previousTranslateY = translateY;
                }

                myScaleGestureDetector.onTouchEvent(event);

                if ((mode == DRAG && scaleFactor != 1.0f && dragged) || mode == ZOOM) {
                    ViewCompat.postInvalidateOnAnimation(cropPictureView);
                }
            } else {
                endX = event.getX();
                endY = event.getY();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = endX;
                        startY = endY;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        break;
                    default:
                        return false;
                }
                ViewCompat.postInvalidateOnAnimation(cropPictureView);
            }

            return true;
        }
    }



    private class MyGestureDetector extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent event) {
            return myScaleGestureDetector.onTouchEvent(event);
        }
    }

    private class MyScaleGestureDetector extends ScaleGestureDetector.SimpleOnScaleGestureListener {

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            scaleFactor *= detector.getScaleFactor();
            scaleFactor = Math.max(MIN_ZOOM, Math.min(scaleFactor, MAX_ZOOM));
            return true;
        }
    }

    public static class MyImageView extends android.support.v7.widget.AppCompatImageView {
        private Paint paint = new Paint();
        private RectF rectF = new RectF();

        public MyImageView(Context context, AttributeSet attrs) {
            super(context, attrs);
            paint.setColor(Color.BLACK);
        }

        @Override
        public void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(scaleFactor, scaleFactor);
            if((translateX * -1) < 0) {
                translateX = 0;
            } else if((translateX * -1) > (scaleFactor - 1) * this.getWidth()) {
                translateX = (1 - scaleFactor) * this.getWidth();
            }

            if(translateY * -1 < 0) {
                translateY = 0;
            } else if((translateY * -1) > (scaleFactor - 1) * this.getHeight()) {
                translateY = (1 - scaleFactor) * this.getHeight();
            }
            canvas.translate(translateX / scaleFactor, translateY / scaleFactor);
            canvas.drawBitmap(bitmap, 0, 0, null);
            if (croppingPic) {
                Log.d("onDraw", " " + translateX);
                rectF.left = (startX - translateX) / scaleFactor;
                rectF.top = (startY - translateY) / scaleFactor;
                rectF.right = (endX - translateX) / scaleFactor;
                rectF.bottom = (endY - translateY) / scaleFactor;
                canvas.drawRect(rectF, paint);
            }
            canvas.restore();
        }
    }

}
