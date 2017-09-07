#define THRESHOLD 30.0f

__kernel __attribute__((vec_type_hint(int4)))
void compute_luminescence(__global int* restrict pixels, __global *bool restrict result)
{
  int4 pixelsVec = vload4(get_local_id(0), pixels);

  result = (is_pixel_on(pixelsVec.x) || is_pixel_on(pixelsVec.y)
	    || is_pixel_on(pixelsVec.z) || is_pixel_on(pixelsVec.w));	    
}

bool is_pixel_on(int pixel) {
 int R = (pixel >> 16) & 0xff;
 int G = (pixel >>  8) & 0xff;
 int B = (piexel) & 0xff;

 return (0.2126f * R + 0.7152 * G + 0.0722 * B) > THRESHOLD;  
}
  
