#pragma OPENCL EXTENSION cl_khr_global_int32_extended_atomics : enable
#define ALPHA 255
#define CUTOFF 85.0f
#define SCALE 170.0f

/* Linearize the RGB values and compute the luminance */ 
float computeLumi(int pixel) {
 float4 scaledColors  = (float4)((pixel >> 16) & 0xff,
				 (pixel >>  8) & 0xff,
				 (pixel) & 0xff, 0.0f) / 255.0f;
 float4 linearColors = scaledColors * (scaledColors * (scaledColors * 0.305306011f + 0.682171111f) + 0.012522878f); // Approximation of the non-linear transform 
 
 return 0.2126f * linearColors.x + 0.7152f * linearColors.y + 0.0722f * linearColors.z;
}

/* Apply a non linear filter to emphasize values above a certain threshold and create a colour map of the luminance */
int compScaledLumi(float lumi, __global float* restrict lumiThreshold) {

  // Non-linear scaling 
  float scaledLumi = lumi < lumiThreshold[0] ? CUTOFF * pown(lumi / lumiThreshold[0], 2) : native_sqrt(lumi) * SCALE + CUTOFF;

  // Luminance map uses a gradation of color, from red to white, through yellow  
  if (scaledLumi < 85) {
    return (ALPHA & 0xff) << 24 | ((int)(scaledLumi) & 0xff) << 16;
  } else if (scaledLumi < 170) {
    return (ALPHA & 0xff) << 24 | (255 & 0xff) << 16 | ((int)(scaledLumi) & 0xff) << 8;
  } else {
    return (ALPHA & 0xff) << 24 | (255 & 0xff) << 16 | (255 & 0xff) << 8 | ((int)(scaledLumi) & 0xff);
  }  
}

/* Compute the maximum luminance for a given picture */
__kernel __attribute__((vec_type_hint(int4)))
void compute_luminance(__global int* restrict pixels, __global int* restrict result)
{
  int globalId = get_global_id(0);
  int4 pixelsVec = vload4(globalId, pixels);    
  
  float lumiX = computeLumi(pixelsVec.x) * 255.0f;  
  float lumiY = computeLumi(pixelsVec.y) * 255.0f;   
  float lumiZ = computeLumi(pixelsVec.z) * 255.0f; 
  float lumiW = computeLumi(pixelsVec.w) * 255.0f;

  float maxLuminance = max(max(lumiX, lumiY), max(lumiZ, lumiW)) * 1048576.0f; // Moltiplication used in preparation of int conversion 
  int maxLumiInt = convert_int_rte(maxLuminance); // Atomic operations do not support float 
    
  atom_max(&result[0], maxLuminance);
}

/* Create a colour map representing the luminance of a given picture */
__kernel __attribute__((vec_type_hint(int4)))
void save_luminance_buffer(__global int* restrict pixels, __global float* restrict lumiThreshold, __global int* restrict luminance)
{
  int globalId = get_global_id(0);
  int4 pixelsVec = vload4(globalId, pixels);

  int4 lumiVec = (int4)(compScaledLumi(computeLumi(pixelsVec.x), lumiThreshold),
			compScaledLumi(computeLumi(pixelsVec.y), lumiThreshold),
			compScaledLumi(computeLumi(pixelsVec.z), lumiThreshold),
			compScaledLumi(computeLumi(pixelsVec.w), lumiThreshold));

  vstore4(lumiVec, globalId, luminance);
  
}


  
