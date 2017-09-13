#include "native_method.h"

extern "C" jlong Java_com_example_muondetector_DetectorService_initializeOpenCL (
        JNIEnv *env, jobject thiz, jstring jKernel, jint jPreviewWidth, jint jPreviewHeight, jint jInSampleSize) {

    size_t previewBufferSize = jPreviewHeight * jPreviewWidth / (jInSampleSize * jInSampleSize) * sizeof(cl_int);

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)malloc(sizeof(struct OpenCLObject));

    const char *kernelString = env->GetStringUTFChars(jKernel, JNI_FALSE);

    /*
     * Initialize OpenCL
     */

    if (!createContext(&obj->context))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create an OpenCL context");
    }

    if (!createCommandQueue(obj->context, &obj->commandQueue, &obj->device))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create an OpenCL command queue");
    }

    if (!createProgram(obj->context, obj->device, kernelString, &obj->program))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL program");
    }

    /*
     * Query the device to find various information. These are merely indicative since we must account for the
     * complexity of the Kernel. For more information look at clGetKernelWorkGroupInfo in the computeluminance method.
     */

    size_t maxWorkItems[3];
    size_t maxWorkGroupSize;
    cl_uint maxWorkItemDimension;
    cl_uint maxComputeUnits;
    cl_bool compilerAvailable;
    cl_uint addressBits;
    char deviceName[256];

    clGetDeviceInfo(obj->device, CL_DEVICE_PREFERRED_VECTOR_WIDTH_FLOAT, sizeof(cl_uint), &obj->intVectorWidth, NULL);
    LOGD("Device info: Preferred vector width for integers: %d ", obj->intVectorWidth);

    clGetDeviceInfo(obj->device, CL_DEVICE_MAX_WORK_GROUP_SIZE, sizeof(size_t), &maxWorkGroupSize, NULL);
    LOGD("Device info: Maximum work group size: %d ", (int)maxWorkGroupSize);

    clGetDeviceInfo(obj->device, CL_DEVICE_MAX_WORK_ITEM_DIMENSIONS, sizeof(cl_uint), &maxWorkItemDimension, NULL);
    LOGD("Device info: Maximum work item dimension: %d", maxWorkItemDimension);

    clGetDeviceInfo(obj->device, CL_DEVICE_MAX_WORK_ITEM_SIZES, sizeof(size_t[3]), maxWorkItems, NULL);
    LOGD("Device info: Maximum work item sizes for each dimension: %d, %d, %d", (int)maxWorkItems[0], (int)maxWorkItems[1], (int)maxWorkItems[2]);

    clGetDeviceInfo(obj->device, CL_DEVICE_NAME, sizeof(char[256]), deviceName, NULL);
    LOGD("Device info: Device name: %s", deviceName);

    clGetDeviceInfo(obj->device, CL_DEVICE_MAX_COMPUTE_UNITS, sizeof(cl_uint), &maxComputeUnits, NULL);
    LOGD("Device info: Maximum compute units: %d", maxComputeUnits);

    clGetDeviceInfo(obj->device, CL_DEVICE_COMPILER_AVAILABLE, sizeof(cl_bool), &compilerAvailable, NULL);
    LOGD("Device info: Device compiler available: %d", compilerAvailable);

    clGetDeviceInfo(obj->device, CL_DEVICE_ADDRESS_BITS, sizeof(cl_uint), &addressBits, NULL);
    LOGD("Device info: Device address bits: %d", addressBits);

    obj->kernel = clCreateKernel(obj->program, "compute_luminance", &obj->errorNumber);
    if (!checkSuccess(obj->errorNumber))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL kernel");
    }

    // Release the string containing the kernel since it has been passed already to createProgram
    env->ReleaseStringUTFChars(jKernel, kernelString);

    bool createMemoryObjectsSuccess = true;
    obj->numberOfMemoryObjects= 2;

    // Ask the OpenCL implementation to allocate buffers to pass data to and from the kernel
    obj->memoryObjects[0] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY| CL_MEM_ALLOC_HOST_PTR,
                                           previewBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[1] = clCreateBuffer(obj->context, CL_MEM_READ_WRITE| CL_MEM_ALLOC_HOST_PTR,
                                           sizeof(cl_int), NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!createMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL buffer");
    }

    // Initialize the buffer holding the result of the computation to the default value
    bool mapMemoryObjectsSuccess = true;
    obj->result = (cl_int *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[1], CL_TRUE, CL_MAP_WRITE, 0,
                                               sizeof(cl_int), 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
    }

    obj->result[0] = 0;

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[1], obj->result, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    return (long) obj;
}

extern "C" jfloat Java_com_example_muondetector_DetectorService_computeluminance(
        JNIEnv *env, jobject thiz, jlong jOpenCLObject, jintArray jPixels, jint jPreviewWidth, jint jPreviewHeight,
        jint inSampleSize) {

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)jOpenCLObject;

    jboolean triggerActivated;
    int pixelCount = jPreviewHeight * jPreviewWidth / (inSampleSize * inSampleSize);
    size_t previewBufferSize = pixelCount * sizeof(cl_int);

    jint *pixels = env->GetIntArrayElements(jPixels, JNI_FALSE);

    /* [Input initialization] */

    /*
     * Map the buffer, initialize it and finally unmap it
     */

    // Map the buffer
    bool mapMemoryObjectsSuccess = true;
    obj->pixels = (cl_int *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[0], CL_TRUE, CL_MAP_WRITE, 0,
                                               previewBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    // Catch eventual errors
    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
    }

    // Initialize the buffer
    for (int index = 0; index < pixelCount; index++)
    {
        obj->pixels[index] = (cl_int)pixels[index];
    }

    // Un-map the buffer
    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[0], obj->pixels, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    // Release the java array since the data has been passed to the memory buffer
    env->ReleaseIntArrayElements(jPixels, pixels, 0);

    /* [Input initialization] */

    /* [Set Kernel Arguments] */

    // Tell the kernels which data to use before they are scheduled
    bool setKernelArgumentSuccess = true;
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 0, sizeof(cl_mem), &obj->memoryObjects[0]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernel, 1, sizeof(cl_mem), &obj->memoryObjects[1]));

    // Catch eventual errors
    if (!setKernelArgumentSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to set OpenCL kernel arguments");
    }

    /* [Set Kernel Arguments] */

    /* [Kernel execution] */

    // Uncomment for more information on the kernel
    /*
    size_t compileWorkGroupSize[3];
    cl_ulong localMemorySize;
    cl_ulong privateMemorySize;
    */

    // Get the maximum number of work items allowed based on scheduled kernel
    size_t kernelWorkGroupSize;
    clGetKernelWorkGroupInfo(obj->kernel, obj->device, CL_KERNEL_WORK_GROUP_SIZE, sizeof(size_t), &kernelWorkGroupSize, NULL);

    /*
    LOGD("Kernel info: maximum work group size: %d", kernelWorkGroupSize);
    clGetKernelWorkGroupInfo(obj->kernel, obj->device, CL_KERNEL_COMPILE_WORK_GROUP_SIZE, sizeof(size_t[3]), &compileWorkGroupSize, NULL);
    LOGD("Kernel info: compile work group size: %d %d %d", compileWorkGroupSize[0], compileWorkGroupSize[1], compileWorkGroupSize[2]);
    clGetKernelWorkGroupInfo(obj->kernel, obj->device, CL_KERNEL_LOCAL_MEM_SIZE, sizeof(cl_ulong), &localMemorySize, NULL);
    LOGD("Kernel info: local memory size in B: %lu", localMemorySize);
    clGetKernelWorkGroupInfo(obj->kernel, obj->device, CL_KERNEL_PRIVATE_MEM_SIZE, sizeof(cl_ulong), &privateMemorySize, NULL);
    LOGD("Kernel info: private memory size in B: %lu", privateMemorySize);
     */

    // Number of kernel instances
    size_t globalWorksize[1] = {(size_t) pixelCount};

    bool openCLFailed = false;

    // Uncomment the following for profiling info
    /*
    //  An event to associate with the kernel. Allows us to to retrieve profiling information later
    cl_event event = 0;
    // Enqueue the kernel
    if (!checkSuccess(clEnqueueNDRangeKernel(obj->commandQueue, obj->kernel, 1, NULL, globalWorksize, NULL, 0, NULL, &event)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to enqueue OpenCL kernel");
        openCLFailed = true;
    }
    */

    // Comment out this clEnqueueNDRangeKernel when profiling
    // Enqueue the kernel
    if (!checkSuccess(clEnqueueNDRangeKernel(obj->commandQueue, obj->kernel, 1, NULL, globalWorksize, NULL, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to enqueue OpenCL kernel");
        openCLFailed = true;
    }

    // Wait for kernel execution completion
    if (!checkSuccess(clFinish(obj->commandQueue)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed waiting for kernel execution to finish");
        openCLFailed = true;
    }

    // Print the profiling information for the event
    /*
    if(!printProfilingInfo(event))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to print profiling info");
        openCLFailed = true;
    }

    // Release the event object
    if (!checkSuccess(clReleaseEvent(event)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed releasing the event");
        openCLFailed = true;
    }
    */

    /* [Kernel execution] */

    /*
     * Map the result buffer so that the flag triggerActivated can be read and passed to the Java side
     */

    obj->result = (cl_int *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[1], CL_TRUE, CL_MAP_READ | CL_MAP_WRITE, 0,
                                                sizeof(cl_int), 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
    }

    float resultFloat = (float)(obj->result[0]) / 32768.0f;
    obj->result[0] = 0;

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[1], obj->result, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    // If an error occurred return an empty byte array
    if (openCLFailed)
        return NULL;

    return resultFloat;
}

extern "C" void Java_com_example_muondetector_DetectorService_closeOpenCL(
        JNIEnv *env, jobject thiz,  jlong jOpenCLObject) {

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *) jOpenCLObject;

    if (!cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernel, obj->memoryObjects, obj->numberOfMemoryObjects))
    {
        LOGE("Failed to clean-up OpenCL");
    };

    free(obj);
}