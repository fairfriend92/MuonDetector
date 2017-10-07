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
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.Socket;

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

    // Possible tags that the user can assign to the pics
    private static final int UNDETERMINED = 0;
    private static final int TRACK = 1;
    private static final int WORM = 2;
    private static final int SPOT = 3;
    private static final int HOT_SPOT = 4;

    // Static variables/objects that are used by the onDraw method of MyImageView
    private static float scaleFactor = 1.0f, translateX = 0.0f, translateY = 0.0f,
            startX = 0.0f, startY = 0.0f, endX = 0.0f, endY = 0.0f, rectArea = 0.0f;
    private static boolean croppingPic = false;
    private static Bitmap bitmap;
    private static RectF rectF = new RectF();

    private File[] pics; // Array containing all the pictures taken during the detection phase
    private BitmapFactory.Options options;
    private int currentPic = 0; // Index of the pic that is being displayed

    // Various objects/constants pertaining the server that must receive the pics
    private Socket clientSocket;
    private ObjectOutputStream socketOutputStream;
    private boolean serverConnectionFailed = false;
    private static final int IPTOS_RELIABILITY = 0x04;
    private static final int SERVER_PORT_TCP = 4197;

    private PopupWindow popupWindow;
    private RelativeLayout resizePicsLayout;
    private int selectedTag;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.resize_pics_activity);

        // Set up the popup window that gives the user the option to tag the pics
        resizePicsLayout = (RelativeLayout) findViewById(R.id.resizePicsLayout);
        LayoutInflater layoutInflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        View popupView = layoutInflater.inflate(R.layout.pic_tag_popup, null);
        int popupWidth = RelativeLayout.LayoutParams.WRAP_CONTENT;
        int popupHeight = RelativeLayout.LayoutParams.WRAP_CONTENT;
        popupWindow = new PopupWindow(popupView, popupWidth, popupHeight, false);

        // Establish a connection with the server to which the pics should be sent
        try {
            clientSocket = new Socket(MainActivity.ip, SERVER_PORT_TCP);
            clientSocket.setTrafficClass(IPTOS_RELIABILITY);
            clientSocket.setKeepAlive(true);
            socketOutputStream = new ObjectOutputStream(clientSocket.getOutputStream());
        } catch (IOException e) {
            CharSequence text = "Connection with the server failed: pics won't be uploaded";
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            serverConnectionFailed= true;
        }

        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (storageDir != null) { // If some pics wer taken during detection phase, then this dir must have been created

            /*
            Firstly, if there are cropped pics belonging to a previous section that could not be
            sent to the server, and if the server is online right now, send them.
             */

            // Enter the block only if the server connection has been established
            if (!serverConnectionFailed) {
                File croppedPicsStorageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES + File.separator + "cropped");

                // The dir was created only if the cropped pics could not be sent before
                if (croppedPicsStorageDir != null) {
                    /* Save the references to the files containing the pics and the associated tags */
                    String croppedPicsFolderPath = croppedPicsStorageDir.getAbsolutePath();
                    File croppedPicsFolder = new File(croppedPicsFolderPath);
                    File[] croppedPics = croppedPicsFolder.listFiles();

                    ObjectInputStream objectInputStream = null; // To read the Candidate object from the file stream
                    FileInputStream fileInputStream = null; // To read from the file

                    for (File file : croppedPics) {
                        try {
                            fileInputStream = new FileInputStream(file);
                            objectInputStream = new ObjectInputStream(fileInputStream);
                            Candidate candidate = (Candidate) objectInputStream.readObject();

                            // Send the Candidate object containing the pic and the tag to the server
                            socketOutputStream.writeObject(candidate);
                            socketOutputStream.flush();
                            socketOutputStream.close();
                        } catch (IOException | ClassNotFoundException e) {
                            String stackTrace = Log.getStackTraceString(e);
                            Log.e("ResizePicsActivity", stackTrace);
                        }
                        /* [End of try] */
                    }
                    /* [End of for] */

                    assert fileInputStream != null;
                    assert objectInputStream != null;

                    try {
                        fileInputStream.close();
                        objectInputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                }
                /* [End of if(croppedPicsStorageDir != null)] */
            }
            /* [End of if (!serverConnectionFailed)] */

            // Save the references to the pictures taken during the detection phase
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
            } // If no pics were taken, shutdown gracefully
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
    Method that belongs to the popup window layout and allows the user to select a tag for the
    candidate picture
     */

    public void onRadioButtonClicked(View view) {
        // Is the button now checked?
        boolean checked = ((RadioButton) view).isChecked();

        // Check which radio button was clicked
        switch(view.getId()) {
            case R.id.radio_undetermined:
                if (checked)
                    selectedTag = UNDETERMINED;
                break;
            case R.id.radio_track:
                if (checked)
                    selectedTag = TRACK;
                break;
            case R.id.radio_worm:
                if (checked)
                    selectedTag = WORM;
                break;
            case R.id.radio_spot:
                if (checked)
                    selectedTag = SPOT;
                break;
            case R.id.radio_hot_spot:
                if (checked)
                    selectedTag = HOT_SPOT;
                break;
        }
    }

    /*
    Method that belongs to the popup window layout. When called, it creates a bitmap from the region
    selected by the user, tags the bitmap with the chosen tag, and finally sends the resulting object
    to the server if it is online, otherwise stores it locally on the terminal.
     */

    public void SaveTag(View view) throws IOException {
        // Create the cropped pic using the coordinate of the rectangle drawn on screen
        Bitmap croppedBmp = Bitmap.createBitmap(bitmap, (int) rectF.left, (int) rectF.top, (int) (rectF.right - rectF.left), (int) (rectF.bottom - rectF.top));

        // Get the array of pixels which form the bitmap
        int[] pixels = new int[croppedBmp.getWidth() * croppedBmp.getHeight()];
        croppedBmp.getPixels(pixels, 0, croppedBmp.getWidth(), 0, 0, croppedBmp.getWidth(), croppedBmp.getHeight());

        Candidate candidate = new Candidate(selectedTag, pixels);

        // If the connection with the server is available, send the  Candidate objects...
        if (!serverConnectionFailed) {
            socketOutputStream.writeObject(candidate);
            socketOutputStream.flush();
        } else { // Otherwise store them locally
            SavePic(candidate);
        }

        // Now that the cropped pic has been taken care of, the original one can be deleted
        if (!pics[currentPic - 1].delete()) {
            CharSequence text = "Error deleting original pic";
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        }

        popupWindow.dismiss();
        LoadNextPic();
    }

    /*
    Called when the save pic button is pressed. If the server is available the pic is immediately sent,
    otherwise the SavePic method is called to store the pic locally so that it can be sent later when
    the sever is back online
     */

    public void SendPic(View view) throws IOException {
        // Proceed only if the cropped region is of the right size
        if (rectF != null & rectArea < MAX_AREA & rectArea > 0) {
            //Show the popup that gives the user the option to tag the candidate pic
            selectedTag = UNDETERMINED; // Set the default tag
            popupWindow.showAtLocation(resizePicsLayout, Gravity.CENTER, 0, 0);
        } else if (rectArea > MAX_AREA){
            CharSequence text = "Cropped area is too big";
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        } else if (rectF == null | rectArea <= 0) {
            CharSequence text = "Cropped area is zero";
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        }
    }

    /*
    This method is called if the server is not available and thus the cropped pic can't be sent. In
    this cas the pic is saved locally so that it can be sent at a later time.
     */

    private void SavePic (Candidate candidate) throws IOException {
        // Prepare the file for saving the cropped pic
        String imageFileName = "cropped";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES + File.separator + "cropped");
        File image = File.createTempFile(
                imageFileName,  /* prefix */
                ".cnd",         /* suffix */
                storageDir      /* directory */
        );
        FileOutputStream fileOutputStream = new FileOutputStream(image);

        ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);
        objectOutputStream.writeObject(candidate);

        // Save the pic
        fileOutputStream.close();
        objectOutputStream.close();
        CharSequence text = "Pic saved";
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
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
            try {
                socketOutputStream.close();
                clientSocket.close();
            } catch (IOException e) {
                String stackTrace = Log.getStackTraceString(e);
                Log.e("ResizePicsActivity", stackTrace);
            }
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
