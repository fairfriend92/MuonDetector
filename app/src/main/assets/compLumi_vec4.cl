#pragma OPENCL EXTENSION cl_khr_global_int32_extended_atomics : enable

float luminescence(int pixel) {
 int R = (pixel >> 16) & 0xff;
 int G = (pixel >>  8) & 0xff;
 int B = (pixel) & 0xff;

 return 0.2126f * R + 0.7152f * G + 0.0722f * B;
}

__kernel __attribute__((vec_type_hint(int4)))
void compute_luminescence(__global int* restrict pixels, __global int* restrict result)
{
  int4 pixelsVec = vload4(get_local_id(0), pixels);    
  float maxLuminescence = 0.0f;
  float lumiX = luminescence(pixelsVec.x);
  float lumiY = luminescence(pixelsVec.y);
  float lumiZ = luminescence(pixelsVec.z);
  float lumiW = luminescence(pixelsVec.w);
  maxLuminescence = lumiX > lumiY ? lumiX : lumiY;
  maxLuminescence = maxLuminescence > lumiZ ? maxLuminescence : lumiZ;
  maxLuminescence = maxLuminescence > lumiW ? maxLuminescence : lumiW;
  maxLuminescence = maxLuminescence * 32768.0f;
  
  int maxLumiInt = convert_int_rte(maxLuminescence);
    
  atom_max(&result[0], maxLuminescence);
}


  
