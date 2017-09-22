#include "native_method.h"

size_t previewBufferSize;
int samplePixelCount;
size_t lumiBufferSize;
int pixelCount;


extern "C" jlong Java_com_example_muondetector_DetectorService_initializeOpenCL (
        JNIEnv *env, jobject thiz, jstring jKernel, jint jPreviewWidth, jint jPreviewHeight, jint jPictureWidth, jint jPictureHeight) {

    samplePixelCount = jPreviewHeight * jPreviewWidth;
    pixelCount = jPictureWidth * jPictureHeight;
    previewBufferSize = samplePixelCount * sizeof(cl_int);
    lumiBufferSize = pixelCount * sizeof(cl_int);

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)malloc(sizeof(struct OpenCLObject));

    const char *kernelString = env->GetStringUTFChars(jKernel, JNI_FALSE);

    /*
     * Initialize OpenCL
     */

    if (!createContext(&obj->context))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create an OpenCL context");
    }

    if (!createCommandQueue(obj->context, &obj->commandQueue, &obj->device))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create an OpenCL command queue");
    }

    if (!createProgram(obj->context, obj->device, kernelString, &obj->program))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
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

    /*
     * Load the kernels
     */

    obj->numberOfKernels = 2;
    obj->kernels[0] = clCreateKernel(obj->program, "compute_luminance", &obj->errorNumber);
    if (!checkSuccess(obj->errorNumber))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL kernel");
    }

    obj->kernels[1] = clCreateKernel(obj->program, "save_luminance_buffer", &obj->errorNumber);
    if (!checkSuccess(obj->errorNumber))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL kernel");
    }

    // Release the string containing the kernel since it has been passed already to createProgram
    env->ReleaseStringUTFChars(jKernel, kernelString);

    /*
     * Create memory objects and allocate buffers
     */

    bool createMemoryObjectsSuccess = true;
    obj->numberOfMemoryObjects= 5;

    // Ask the OpenCL implementation to allocate buffers to pass data to and from the kernel
    obj->memoryObjects[0] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY| CL_MEM_ALLOC_HOST_PTR,
                                           previewBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[1] = clCreateBuffer(obj->context, CL_MEM_READ_WRITE| CL_MEM_ALLOC_HOST_PTR,
                                           sizeof(cl_int), NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[2] = clCreateBuffer(obj->context, CL_MEM_WRITE_ONLY| CL_MEM_ALLOC_HOST_PTR,
                                           lumiBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[3] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY| CL_MEM_ALLOC_HOST_PTR,
                                           sizeof(cl_float), NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    obj->memoryObjects[4] = clCreateBuffer(obj->context, CL_MEM_READ_ONLY| CL_MEM_ALLOC_HOST_PTR,
                                           lumiBufferSize, NULL, &obj->errorNumber);
    createMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!createMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to create OpenCL buffer");
    }

    // Initialize the buffer holding the result of the computation to the default value
    bool mapMemoryObjectsSuccess = true;
    obj->result = (cl_int *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[1], CL_TRUE, CL_MAP_WRITE, 0,
                                               sizeof(cl_int), 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
    }

    obj->result[0] = 0;

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[1], obj->result, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    return (long) obj;
}

extern "C" jfloat Java_com_example_muondetector_DetectorService_computeluminance(
        JNIEnv *env, jobject thiz, jlong jOpenCLObject, jintArray jPixels) {

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)jOpenCLObject;

    jint *pixels = env->GetIntArrayElements(jPixels, JNI_FALSE);

    /* [Input initialization] */

    // Map the buffer
    bool mapMemoryObjectsSuccess = true;
    obj->pixels = (cl_int *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[0], CL_TRUE, CL_MAP_WRITE, 0,
                                               previewBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    // Catch eventual errors
    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
    }

    // Initialize the buffer
    for (int index = 0; index < samplePixelCount; index++)
    {
        obj->pixels[index] = (cl_int)pixels[index];
    }

    // Un-map the buffer
    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[0], obj->pixels, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    // Release the java array since the data has been passed to the memory buffer
    env->ReleaseIntArrayElements(jPixels, pixels, 0);

    /* [Input initialization] */

    /* [Set Kernel Arguments] */

    // Tell the kernel which data to use before it's scheduled
    bool setKernelArgumentSuccess = true;
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernels[0], 0, sizeof(cl_mem), &obj->memoryObjects[0]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernels[0], 1, sizeof(cl_mem), &obj->memoryObjects[1]));

    // Catch eventual errors
    if (!setKernelArgumentSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to set OpenCL kernel arguments");
    }

    /* [Set Kernel Arguments] */

    /* [Kernel execution] */

    // Number of kernel instances
    size_t globalWorksize[1] = {(size_t) (samplePixelCount / 4)};

    bool openCLFailed = false;

    // Enqueue the kernel
    if (!checkSuccess(clEnqueueNDRangeKernel(obj->commandQueue, obj->kernels[0], 1, NULL, globalWorksize, NULL, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to enqueue OpenCL kernel");
        openCLFailed = true;
    }

    // Wait for kernel execution completion
    if (!checkSuccess(clFinish(obj->commandQueue)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed waiting for kernel execution to finish");
        openCLFailed = true;
    }

    /* [Kernel execution] */

    /* [Output buffers mapping] */

    obj->result = (cl_int *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[1], CL_TRUE, CL_MAP_READ | CL_MAP_WRITE, 0,
                                                sizeof(cl_int), 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
    }

    float resultFloat = (float)(obj->result[0]) / 1048576.0f;

    // Result must be reset if we want the luminance comparison to be carried only among the pixels of the current image
    obj->result[0] = 0;

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[1], obj->result, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    /* [Output buffers mapping] */

    // If an error occurred return an empty byte array
    if (openCLFailed)
        return NULL;

    return resultFloat;
}

extern "C" jintArray Java_com_example_muondetector_DetectorService_luminanceMap(
        JNIEnv *env, jobject thiz, jlong jOpenCLObject, jfloat jLumiThreshold, jintArray jPixels) {

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *)jOpenCLObject;

    jint *pixels = env->GetIntArrayElements(jPixels, JNI_FALSE);

    /* [Map the input buffers] */

    // Map the input buffer storing the value of the luminance which serves as a parameter of the non linear transformation which generates the output map
    bool mapMemoryObjectsSuccess = true;
    obj->lumiThreshold = (cl_float *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[3], CL_TRUE, CL_MAP_WRITE, 0,
                                                        sizeof(cl_float), 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    // Catch eventual errors
    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
    }

    // Store the value in the buffer
    obj->lumiThreshold[0] = (cl_float)jLumiThreshold;

    // Un-map the memory object
    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[3], obj->lumiThreshold, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    // Map the buffer which stores the RGB values of the pixels from which the kernel must build the luminance map
    obj->fullPixels = (cl_int *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[4], CL_TRUE, CL_MAP_WRITE, 0,
                                               lumiBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    // Catch eventual errors
    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
    }

    // Initialize the buffer
    for (int index = 0; index < pixelCount; index++)
    {
        obj->fullPixels[index] = (cl_int)pixels[index];
    }

    // Un-map the buffer
    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[4], obj->fullPixels, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    /* [Map the input buffers] */

    // Now that the buffer containing the RGB values has been initialized, the array of pixels can be released
    env->ReleaseIntArrayElements(jPixels, pixels, 0);

    /* [Schedule and execute the kernel] */

    // Tell the kernels which data to use before they are scheduled
    bool setKernelArgumentSuccess = true;
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernels[1], 0, sizeof(cl_mem), &obj->memoryObjects[4]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernels[1], 1, sizeof(cl_mem), &obj->memoryObjects[3]));
    setKernelArgumentSuccess &= checkSuccess(clSetKernelArg(obj->kernels[1], 2, sizeof(cl_mem), &obj->memoryObjects[2]));

    // Catch eventual errors
    if (!setKernelArgumentSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to set OpenCL kernel arguments");
    }

    size_t globalWorksize[1] = {(size_t) (pixelCount / 4)}; // Global size is divided by four since the kerenl uses vector units 128-bit wide

    bool openCLFailed = false;

    // Enqueue the kernel
    if (!checkSuccess(clEnqueueNDRangeKernel(obj->commandQueue, obj->kernels[1], 1, NULL, globalWorksize, NULL, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to enqueue OpenCL kernel");
        openCLFailed = true;
    }

    // Wait for kernel execution completion
    if (!checkSuccess(clFinish(obj->commandQueue)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed waiting for kernel execution to finish");
        openCLFailed = true;
    }

    /* [Schedule and execute the kernel] */

    /* [Map the output buffer] */

    obj->luminance = (cl_int *)clEnqueueMapBuffer(obj->commandQueue, obj->memoryObjects[2], CL_TRUE, CL_MAP_READ, 0,
                                                  lumiBufferSize, 0, NULL, NULL, &obj->errorNumber);
    mapMemoryObjectsSuccess &= checkSuccess(obj->errorNumber);

    if (!mapMemoryObjectsSuccess)
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Failed to map buffer");
    }

    jintArray jLuminanceBuffer = env->NewIntArray(pixelCount);

    // Copy the content in the buffer to the java array, using the pointer created by the OpenCL implementation
    env->SetIntArrayRegion(jLuminanceBuffer, 0, pixelCount, reinterpret_cast<jint*>(obj->luminance));

    if (!checkSuccess(clEnqueueUnmapMemObject(obj->commandQueue, obj->memoryObjects[2], obj->luminance, 0, NULL, NULL)))
    {
        cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects);
        LOGE("Unmap memory objects failed");
    }

    /* [Map the output buffer] */


    if (openCLFailed)
        jLuminanceBuffer = NULL;

    return jLuminanceBuffer;
}

extern "C" void Java_com_example_muondetector_DetectorService_closeOpenCL(
        JNIEnv *env, jobject thiz,  jlong jOpenCLObject) {

    struct OpenCLObject *obj;
    obj = (struct OpenCLObject *) jOpenCLObject;

    if (!cleanUpOpenCL(obj->context, obj->commandQueue, obj->program, obj->kernels, obj->numberOfKernels, obj->memoryObjects, obj->numberOfMemoryObjects))
    {
        LOGE("Failed to clean-up OpenCL");
    };

    free(obj);
}