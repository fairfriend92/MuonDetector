package com.example.muondetector;

/*
Class that contains a picture of a possible particle trace plus the tentative particle type
 */

import java.io.Serializable;

public class Candidate implements Serializable {

    public int particleTag;
    public int[] bmpPixels;

    public Candidate (int particleTag, int[] bmpPixels) {
        this.particleTag = particleTag;
        this.bmpPixels = new int[bmpPixels.length];
        System.arraycopy(bmpPixels, 0, this.bmpPixels, 0, bmpPixels.length);
    }

}
