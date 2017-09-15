#pragma OPENCL EXTENSION cl_khr_global_int32_extended_atomics : enable
#define ALPHA 255

float luminescence(int pixel) {
 int R = (pixel >> 16) & 0xff;
 int G = (pixel >>  8) & 0xff;
 int B = (pixel) & 0xff;

 float4 scaledColors  = (float4)(R, G, B, 0.0f) / 255.0f;
 float4 linearColors = scaledColors * (scaledColors * (scaledColors * 0.305306011f + 0.682171111f) + 0.012522878f); // Approximation of the non-linear transform 
 
 return (0.2126f * linearColors.x + 0.7152f * linearColors.y + 0.0722f * linearColors.z) * 255.0f;
}

__kernel __attribute__((vec_type_hint(int4)))
void compute_luminance(__global int* restrict pixels, __global int* restrict result, __global int* restrict luminance)
{
  int globalId = get_global_id(0);
  int4 pixelsVec = vload4(globalId, pixels);    
  float maxLuminescence = 0.0f;

  float lumiX = luminescence(pixelsVec.x);
  luminance[globalId * 4] = (ALPHA & 0xff) << 24 | ((int)(lumiX) & 0xff) << 16;

  float lumiY = luminescence(pixelsVec.y);
  luminance[globalId * 4 + 1] = (ALPHA & 0xff) << 24 | ((int)(lumiY) & 0xff) << 16;

  float lumiZ = luminescence(pixelsVec.z);
  luminance[globalId * 4 + 2] = (ALPHA & 0xff) << 24 | ((int)(lumiZ) & 0xff) << 16;

  float lumiW = luminescence(pixelsVec.w);
  luminance[globalId * 4 + 3] = (ALPHA & 0xff) << 24 | ((int)(lumiW) & 0xff) << 16;

  maxLuminescence = lumiX > lumiY ? lumiX : lumiY;
  maxLuminescence = maxLuminescence > lumiZ ? maxLuminescence : lumiZ;
  maxLuminescence = maxLuminescence > lumiW ? maxLuminescence : lumiW;
  maxLuminescence = maxLuminescence * 1048576.0f;
  
  int maxLumiInt = convert_int_rte(maxLuminescence);
    
  atom_max(&result[0], maxLuminescence);
}


  
