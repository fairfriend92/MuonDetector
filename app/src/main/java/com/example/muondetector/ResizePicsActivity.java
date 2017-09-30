package com.example.muondetector;


import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.ImageView;

import java.io.File;

public class ResizePicsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.resize_pics_activity);

        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (storageDir != null) {
            String filePath = storageDir.getAbsolutePath() + File.separator + "3.jpg";
            Bitmap bmp = BitmapFactory.decodeFile(filePath);
            ImageView cropPictureView = (ImageView) findViewById(R.id.cropPictureView);
            cropPictureView.setImageBitmap(bmp);
        }
    }

}
