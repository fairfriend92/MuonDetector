//
// Created by rodolfo on 01/12/16.
//

#ifndef MYAPPLICATION_COMMON_H
#define MYAPPLICATION_COMMON_H

#endif //MYAPPLICATION_COMMON_H

#include "CL/cl.h"
#include <android/log.h>
#include <string>
#include <iostream>
#include <fstream>
#include <sstream>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "OpenCL debug", __VA_ARGS__)
#define LOGE(x...) do { \
  char buf[512]; \
  sprintf(buf, x); \
  __android_log_print(ANDROID_LOG_ERROR,"OpenCL error", "%s | %s:%i", buf, __FILE__, __LINE__); \
} while (0)

bool printProfilingInfo(cl_event event);
bool cleanUpOpenCL(cl_context context, cl_command_queue commandQueue, cl_program program, cl_kernel kernel, cl_mem* memoryObjects, int numberOfMemoryObjects);
bool createContext(cl_context* context);
bool createCommandQueue(cl_context context, cl_command_queue* commandQueue, cl_device_id* device);
bool createProgram(cl_context context, cl_device_id device, const char* kernelString, cl_program* program);
std::string errorNumberToString(cl_int errorNumber);
bool checkSuccess(cl_int errorNumber);