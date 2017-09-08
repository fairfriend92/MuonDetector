//
// Created by rodolfo on 08/12/16.
//

#ifndef MYAPPLICATION_NATIVE_METHOD_H_H
#define MYAPPLICATION_NATIVE_METHOD_H_H

#endif //MYAPPLICATION_NATIVE_METHOD_H_H

#include <android/log.h>
#include <jni.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include "CL/cl.h"
#include "common.h"

struct OpenCLObject {
    // OpenCL implementation
    cl_context context = 0;
    cl_command_queue commandQueue = 0;
    cl_program program = 0;
    cl_device_id device = 0;
    cl_kernel kernel = 0;
    cl_int errorNumber = 0;
    int numberOfMemoryObjects = 2;
    cl_mem memoryObjects[2] = {0, 0};
    cl_uint intVectorWidth;

    // Pointers to the memory buffers
    cl_int *pixels;
    cl_int *result;
};

