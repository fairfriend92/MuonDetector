#pragma OPENCL EXTENSION cl_khr_global_int32_extended_atomics : enable
#define ALPHA 255
#define CUTOFF 50.0f
#define COEFF_BELOW_THRESHOLD 500.0f
#define COEFF_ABOVE_THRESHOLD 0.8042f

float luminescence(int pixel) {
 int R = (pixel >> 16) & 0xff;
 int G = (pixel >>  8) & 0xff;
 int B = (pixel) & 0xff;

 float4 scaledColors  = (float4)(R, G, B, 0.0f) / 255.0f;
 float4 linearColors = scaledColors * (scaledColors * (scaledColors * 0.305306011f + 0.682171111f) + 0.012522878f); // Approximation of the non-linear transform 
 
 return (0.2126f * linearColors.x + 0.7152f * linearColors.y + 0.0722f * linearColors.z) * 255.0f; // Luminance scaled up to 255
}

void savePixelLumi(float scaledLumi,__global int* restrict luminance) {
  int globalId = get_global_id(0);
  
  if (scaledLumi < 85) {
    luminance[globalId * 4] = (ALPHA & 0xff) << 24 | ((int)(scaledLumi) & 0xff) << 16;
  } else if (scaledLumi < 170) {
    luminance[globalId * 4] = (ALPHA & 0xff) << 24 | (255 & 0xff) << 16 | ((int)(scaledLumi) & 0xff) << 8;
  } else {
    luminance[globalId * 4] = (ALPHA & 0xff) << 24 | (255 & 0xff) << 16 | (255 & 0xff) << 8 | ((int)(scaledLumi) & 0xff);
  }  
}

__kernel __attribute__((vec_type_hint(int4)))
void compute_luminance(__global int* restrict pixels, __global int* restrict result, __global int* restrict luminance,
		       __global float* restrict lumiThreshold)
{
  int globalId = get_global_id(0);
  int4 pixelsVec = vload4(globalId, pixels);    
  float maxLuminescence = 0.0f;

  /* Store the luminace values in a buffer. Use non-linear scaling to emphasize values below threshold */
  
  float lumiX = luminescence(pixelsVec.x);
  float scaledLumi = lumiX < lumiThreshold[0] ? lumiX * COEFF_BELOW_THRESHOLD : (lumiX - lumiThreshold[0]) * COEFF_ABOVE_THRESHOLD + CUTOFF;
  savePixelLumi(scaledLumi, luminance);
  
  float lumiY = luminescence(pixelsVec.y);
  scaledLumi = lumiY < lumiThreshold[0] ? lumiY * COEFF_BELOW_THRESHOLD : (lumiY - lumiThreshold[0]) * COEFF_ABOVE_THRESHOLD + CUTOFF;
  savePixelLumi(scaledLumi, luminance);
  
  float lumiZ = luminescence(pixelsVec.z);
  scaledLumi = lumiZ < lumiThreshold[0] ? lumiZ * COEFF_BELOW_THRESHOLD : (lumiZ - lumiThreshold[0]) * COEFF_ABOVE_THRESHOLD + CUTOFF;
  savePixelLumi(scaledLumi, luminance);
 
  float lumiW = luminescence(pixelsVec.w);
  scaledLumi = lumiW < lumiThreshold[0] ? lumiW * COEFF_BELOW_THRESHOLD : (lumiW - lumiThreshold[0]) * COEFF_ABOVE_THRESHOLD + CUTOFF;
  savePixelLumi(scaledLumi, luminance);

  /* Comput the maximum luminance among the values for the current picture */ 

  maxLuminescence = lumiX > lumiY ? lumiX : lumiY;
  maxLuminescence = maxLuminescence > lumiZ ? maxLuminescence : lumiZ;
  maxLuminescence = maxLuminescence > lumiW ? maxLuminescence : lumiW;
  maxLuminescence = maxLuminescence * 1048576.0f;
  
  int maxLumiInt = convert_int_rte(maxLuminescence);
    
  atom_max(&result[0], maxLuminescence);
}


  
