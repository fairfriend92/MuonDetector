package com.example.muondetector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;

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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/*
Activity that shows the pics that were take during the detection phase and asks the user to crop
them before sending them to the server.
 */

public class ResizePicsActivity extends AppCompatActivity {

    private ScaleGestureDetector myScaleGestureDetector;
    private MyImageView cropPictureView; // Custom ImageView

    // Constants
    private static final float MIN_ZOOM = 1.0f;
    private static final float MAX_ZOOM = 5.0f;
    private static final float MAX_AREA = 1000.0f; // Maximum area of the cropped pic

    // Static variables/objects that are used by the onDraw method of MyImageView
    private static float scaleFactor = 1.0f, translateX = 0.0f, translateY = 0.0f,
            startX = 0.0f, startY = 0.0f, endX = 0.0f, endY = 0.0f, rectArea = 0.0f;
    private static boolean croppingPic = false;
    private static Bitmap bitmap;
    private static RectF rectF = new RectF();

    private File[] pics; // Array containing all the pictures taken during the detection phase
    private BitmapFactory.Options options;
    private int currentPic = 0; // Index of the pic that is being displayed

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.resize_pics_activity);

        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (storageDir != null) {
            // Save the references to the picture taken during the detection phase
            String picsFolderPath = storageDir.getAbsolutePath();
            File picsFolder = new File(picsFolderPath);
            pics = picsFolder.listFiles();

            options = new BitmapFactory.Options();
            options.inMutable = true;

            if (LoadNextPic()) { // If there are some pics in the folder enter the block
                MyOnTouchListener myOnTouchListener = new MyOnTouchListener();
                myScaleGestureDetector = new ScaleGestureDetector(this, new MyScaleGestureDetector());

                // Setup the custom ImageView so that gestures are detected when touching it
                cropPictureView = (MyImageView) findViewById(R.id.cropPictureView);
                cropPictureView.setOnTouchListener(myOnTouchListener);
                cropPictureView.setImageDrawable(new BitmapDrawable(getResources(), bitmap));

                // The button for switching between scaling mode and cropping mode
                // Scaling: zoom in/out the picture
                // Cropping: crop a rect of area no greater than MAX_AREA and save it
                ToggleButton toggleButton = (ToggleButton) findViewById(R.id.cropRectButton);
                toggleButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        croppingPic = isChecked;

                        // When in cropping mode display the needed additional buttons
                        Button saveButton = (Button) findViewById(R.id.saveButton);
                        Button discardButton = (Button) findViewById(R.id.discardPicButton);
                        int visibility = croppingPic ? View.VISIBLE : View.INVISIBLE;
                        saveButton.setVisibility(visibility);
                        discardButton.setVisibility(visibility);
                    }
                });
            } // If no pics were take, shutdown gracefully
            /* [End of inner if] */
        } else  {
            CharSequence text = "A problem when reading the directory has occurred";
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        }
        /* [End of outer if] */
    }
    /* [End of onCreate method] */

    /*
    Called when the save pic button is pressed
     */

    public void SavePic (View view) throws IOException {
        // Proceed only if the cropped region is of the right size
        if (rectF != null & rectArea < MAX_AREA & rectArea > 0) {
            // Prepare the file for saving the cropped pic
            String imageFileName = "cropped";
            File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES + File.separator + "cropped");
            File image = File.createTempFile(
                    imageFileName,  /* prefix */
                    ".jpg",         /* suffix */
                    storageDir      /* directory */
            );
            FileOutputStream fileOutputStream = new FileOutputStream(image);

            // Create the cropped pic using the coordinate of the rectangle drawn on screen
            Bitmap croppedBmp = Bitmap.createBitmap(bitmap, (int) rectF.left, (int) rectF.top, (int) (rectF.right - rectF.left), (int) (rectF.bottom - rectF.top));
            croppedBmp.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);

            // Save the pic
            fileOutputStream.close();
            CharSequence text = "Pic saved";
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();

            // Now that the cropped pic has been saved, the original one can be deleted
            if (!pics[currentPic - 1].delete()) {
                text = "Error deleting original pic";
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            }

            LoadNextPic();
        } else if (rectArea > MAX_AREA){
            CharSequence text = "Cropped area is either too";
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        } else if (rectF == null | rectArea <= 0) {
            CharSequence text = "Cropped area is zero";
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        }
    }

    public void DiscardPic (View view) {
        LoadNextPic();
    }

    /*
    Called to prepare the next bitmap when the current one has either been discarded or cropped and
    saved
     */

    private boolean LoadNextPic () {
        // If the current file is, indeed, a directory, simply move over to the next one
        if (currentPic < pics.length && pics[currentPic].isDirectory()) {
            currentPic++;
        }

        // If there is still at least one pic left to review enter the block
        if (currentPic < pics.length) {
            // Update the reference of the pic showed by the custom ImageView
            bitmap = BitmapFactory.decodeFile(pics[currentPic].getAbsolutePath(), options);

            // Call the onDraw method of MyImageView
            if (cropPictureView != null) // TODO: the view should never be null
                ViewCompat.postInvalidateOnAnimation(cropPictureView);

            currentPic++;
            return true;
        } else { // If no pics are left to review, finish the activity
            CharSequence text = "All pics reviewed";
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
            return false;
        }

    }

    /*
    Listener which detects the gestures and uses the relative info to compute how much the pic
    should be translated or how big the rectangle containing the portion of the pic to be cropped
    should be.
     */

    private class MyOnTouchListener implements View.OnTouchListener {
        // Mode defined by the kind of gesture detected
        private static final int NONE = 0, DRAG = 1, ZOOM = 2;
        private int mode;

        private float previousTranslateX = 0.0f, previousTranslateY = 0.0f; // Last translation values
        private boolean dragged;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            // Proceed only when scaling mode is selected
            if (!croppingPic) {
                switch (event.getAction() & MotionEvent.ACTION_MASK) {
                    /* event.getX() gives us the screen coordinate. We need the coordinate of the
                    bitmap pixel which is being pointed at. Therefore the last translation values need
                    to be subtracted to the coordinates returned by getX(). In other words we change
                    the reference system from that of the device screen to that of the bitmap
                     */
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
            }
            /* When in scaling mode, simply save the coordinates of the pixel pointed at when the finger
            touches the screen and when it is raised.
             */
            else {
                // endX and endY are current coordinate of the pixel being pointed at
                endX = event.getX();
                endY = event.getY();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // startX and startY are the coordinate of the pixel pointed at when the
                        // finger first touches the screen
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

    /*
    Listener that update the scale factor whenever a "scale" gesture is detected
     */

    private class MyScaleGestureDetector extends ScaleGestureDetector.SimpleOnScaleGestureListener {

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            scaleFactor *= detector.getScaleFactor();
            scaleFactor = Math.max(MIN_ZOOM, Math.min(scaleFactor, MAX_ZOOM));
            return true;
        }
    }

    /*
    Custom ImageView which translates and scales the underlying bitmap and draws a rectangle on top
    of it
     */

    public static class MyImageView extends android.support.v7.widget.AppCompatImageView {
        private Paint paintBlack = new Paint(), paintGreen = new Paint();

        public MyImageView(Context context, AttributeSet attrs) {
            super(context, attrs);
            paintBlack.setColor(Color.BLACK);
            paintBlack.setStrokeWidth(2);
            paintBlack.setStyle(Paint.Style.STROKE);
            paintGreen.setColor(Color.GREEN);
            paintGreen.setStrokeWidth(2);
            paintGreen.setStyle(Paint.Style.STROKE);
        }

        @Override
        public void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(scaleFactor, scaleFactor);

            /* Clamp the translation within the border of this view */

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

            if (bitmap != null)
                canvas.drawBitmap(bitmap, 0, 0, null);

            /* If in cropping mode, update the vertices of the rectangle to be drawn on top of the
            bmp to delimit the area to be cropped
             */

            if (croppingPic) {
                rectF.left = Math.min((startX - translateX) / scaleFactor, (endX - translateX) / scaleFactor);
                rectF.top = Math.min((startY - translateY) / scaleFactor, (endY - translateY) / scaleFactor);
                rectF.right = Math.max((startX - translateX) / scaleFactor, (endX - translateX) / scaleFactor);
                rectF.bottom = Math.max((startY - translateY) / scaleFactor, (endY - translateY) / scaleFactor);
                rectArea = (rectF.right - rectF.left) * (rectF.bottom - rectF.top);
                if (rectArea < MAX_AREA)
                    canvas.drawRect(rectF, paintGreen);
                else
                    canvas.drawRect(rectF, paintBlack);
            }

            canvas.restore();
        }
    }

}
