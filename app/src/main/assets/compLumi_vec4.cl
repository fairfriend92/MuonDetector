#pragma OPENCL EXTENSION cl_khr_global_int32_extended_atomics : enable
#define ALPHA 255
#define CUTOFF 50.0f

float computeLumi(int pixel) {
 int R = (pixel >> 16) & 0xff;
 int G = (pixel >>  8) & 0xff;
 int B = (pixel) & 0xff;

 float4 scaledColors  = (float4)(R, G, B, 0.0f) / 255.0f;
 float4 linearColors = scaledColors * (scaledColors * (scaledColors * 0.305306011f + 0.682171111f) + 0.012522878f); // Approximation of the non-linear transform 
 
 return 0.2126f * linearColors.x + 0.7152f * linearColors.y + 0.0722f * linearColors.z;
}

void savePixelLumi(float scaledLumi,__global int* restrict luminance, int id) {

  /* Luminance map uses a gradation of color, from red to white, through yellow */
  
  if (scaledLumi < 85) {
    luminance[id] = (ALPHA & 0xff) << 24 | ((int)(scaledLumi) & 0xff) << 16;
  } else if (scaledLumi < 170) {
    luminance[id] = (ALPHA & 0xff) << 24 | (255 & 0xff) << 16 | ((int)(scaledLumi) & 0xff) << 8;
  } else {
    luminance[id] = (ALPHA & 0xff) << 24 | (255 & 0xff) << 16 | (255 & 0xff) << 8 | ((int)(scaledLumi) & 0xff);
  }  
}

__kernel __attribute__((vec_type_hint(int4)))
void compute_luminance(__global int* restrict pixels, __global int* restrict result)
{
  int globalId = get_global_id(0);
  int4 pixelsVec = vload4(globalId, pixels);    
  float maxLuminance = 0.0f;
  
  float lumiX = computeLumi(pixelsVec.x) * 255.0f;  
  float lumiY = computeLumi(pixelsVec.y) * 255.0f;   
  float lumiZ = computeLumi(pixelsVec.z) * 255.0f; 
  float lumiW = computeLumi(pixelsVec.w) * 255.0f;

  /* Comput the maximum luminance among the values for the current picture */ 

  maxLuminance = lumiX > lumiY ? lumiX : lumiY;
  maxLuminance = maxLuminance > lumiZ ? maxLuminance : lumiZ;
  maxLuminance = maxLuminance > lumiW ? maxLuminance : lumiW;
  maxLuminance = maxLuminance * 1048576.0f;
  
  int maxLumiInt = convert_int_rte(maxLuminance);
    
  atom_max(&result[0], maxLuminance);
}

__kernel __attribute__((vec_type_hint(int4)))
void save_luminance_buffer(__global int* restrict pixels, __global float* restrict lumiThreshold, __global int* restrict luminance)
{
  int globalId = get_global_id(0);
  int pixelId = globalId * 4;
  int4 pixelsVec = vload4(globalId, pixels);
  float threshold = lumiThreshold[0] / 255.0f;
  
  float coeffBelowThreshold = CUTOFF / lumiThreshold[0];

  /* Store the luminace values in a buffer. Use non-linear scaling to emphasize values below threshold */

  float lumiX = computeLumi(pixelsVec.x);
  float scaledLumi = lumiX < threshold ? lumiX * coeffBelowThreshold : sqrt(lumiX) * 205.0f + CUTOFF;
  savePixelLumi(scaledLumi * 255.0f, luminance, pixelId);
  
  float lumiY = computeLumi(pixelsVec.y);
  scaledLumi = lumiY < threshold ? lumiY * coeffBelowThreshold : sqrt(lumiY) * 205.0f + CUTOFF;
  savePixelLumi(scaledLumi * 255.0f, luminance, pixelId + 1);
  
  float lumiZ = computeLumi(pixelsVec.z);
  scaledLumi = lumiZ < threshold ? lumiZ * coeffBelowThreshold : sqrt(lumiZ) * 205.0f + CUTOFF;
  savePixelLumi(scaledLumi * 255.0f, luminance, pixelId + 2);
 
  float lumiW = computeLumi(pixelsVec.w);
  scaledLumi = lumiW < threshold ? lumiW * coeffBelowThreshold : sqrt(lumiW) * 205.0f + CUTOFF;
  savePixelLumi(scaledLumi * 255.0f, luminance, pixelId + 3);
}


  
