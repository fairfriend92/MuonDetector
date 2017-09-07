//
// Created by rodolfo on 01/12/16.
//

#include "common.h"

bool printProfilingInfo(cl_event event)
{

    cl_ulong queuedTime = 0;
    if (!checkSuccess(clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_QUEUED, sizeof(cl_ulong), &queuedTime, NULL)))
    {
        LOGE("Retrieving CL_PROFILING_COMMAND_QUEUED OpenCL profiling information failed");
        return false;
    }

    cl_ulong submittedTime = 0;
    if (!checkSuccess(clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_SUBMIT, sizeof(cl_ulong), &submittedTime, NULL)))
    {
        LOGE("Retrieving CL_PROFILING_COMMAND_SUBMIT OpenCL profiling information failed");
        return false;
    }

    cl_ulong startTime = 0;
    if (!checkSuccess(clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_START, sizeof(cl_ulong), &startTime, NULL)))
    {
        LOGE("Retrieving CL_PROFILING_COMMAND_START OpenCL profiling information failed");
        return false;
    }

    cl_ulong endTime = 0;
    if (!checkSuccess(clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_END, sizeof(cl_ulong), &endTime, NULL)))
    {
        LOGE("Retrieving CL_PROFILING_COMMAND_END OpenCL profiling information failed");
        return false;
    }

    LOGD("Profiling information \n");
    LOGD("Queued time: \t%lu ns\n", (unsigned long) (submittedTime - queuedTime));
    LOGD("Wait time: \t%lu ns\n", (unsigned long) (startTime - submittedTime));
    LOGD("Run time: \t%lu ns\n", (unsigned long) (endTime - startTime));

    return true;
}

bool cleanUpOpenCL(cl_context context, cl_command_queue commandQueue, cl_program program, cl_kernel kernel, cl_mem* memoryObjects, int numberOfMemoryObjects)
{
    bool returnValue = true;

    if (context!= 0)
    {
        if (!checkSuccess(clReleaseContext(context)))
        {
            LOGE("Releasing the OpenCL context failed");
            returnValue = false;
        }
    }

    if (commandQueue != 0)
    {
        if (!checkSuccess(clReleaseCommandQueue(commandQueue)))
        {
            LOGE("Releasing the OpenCL command queue failed");
            returnValue = false;
        }
    }

    if (kernel != 0)
    {
        if (!checkSuccess(clReleaseKernel(kernel)))
        {
            LOGE("Releasing the OpenCL kernel failed");
            returnValue = false;
        }
    }

    if (program != 0)
    {
        if (!checkSuccess(clReleaseProgram(program)))
        {
            LOGE("Releasing the OpenCL program failed");
            returnValue = false;
        }
    }

    for (int index = 0; index < numberOfMemoryObjects; index++)
    {
        if (memoryObjects[index] != 0)
        {
            if (!checkSuccess(clReleaseMemObject(memoryObjects[index])))
            {
                LOGE("Releasing the OpenCL memory object %d failed", index);
                return false;
            }
        }
    }

    return returnValue;
}

bool createCommandQueue(cl_context context, cl_command_queue* commandQueue, cl_device_id* device)
{
    cl_int errorNumber = 0;
    cl_device_id* devices = NULL;
    size_t deviceBufferSize = -1;

    // Retrieves the size of the buffer needed to contain information about the devices in this OpenCL context
    if (!checkSuccess(clGetContextInfo(context, CL_CONTEXT_DEVICES, 0, NULL, &deviceBufferSize)))
    {
        LOGE("Failed to get OpenCL context information");
        return false;
    }

    if (deviceBufferSize == 0)
    {
        LOGE("No OpenCL devices found");
        return false;
    }

    // Retrieves the list of devices available in this context
    devices = new cl_device_id[deviceBufferSize / sizeof(cl_device_id)];
    if (!checkSuccess(clGetContextInfo(context, CL_CONTEXT_DEVICES, deviceBufferSize, devices, NULL)))
    {
        LOGE("Failed to get the OpenCL context information");
        delete [] devices;
        return false;
    }

    // Use the first available device in this context
    *device = devices[0];
    delete [] devices;

    // Set up the command queue with the selected device
    *commandQueue = clCreateCommandQueue(context, *device, CL_QUEUE_PROFILING_ENABLE, &errorNumber);
    if (!checkSuccess(errorNumber))
    {
        LOGE("Failed to create OpenCL command queue");
        return false;
    }

    return true;
}

bool createProgram(cl_context context, cl_device_id device, const char* kernelString, cl_program* program)
{
    cl_int  errorNumber = 0;

    *program = clCreateProgramWithSource(context, 1, &kernelString, NULL, &errorNumber);
    if (!checkSuccess(errorNumber) || program == NULL)
    {
        LOGE("Failed to create OpenCL program");
    }

    // Try to build the OpenCL program

    char options[128] = "-cl-strict-aliasing -cl-fast-relaxed-math";

    bool buildSuccess = checkSuccess(clBuildProgram(*program, 0, NULL, NULL, NULL, NULL));

    // Get the size of the build log
    size_t logSize = 0;
    clGetProgramBuildInfo(*program, device, CL_PROGRAM_BUILD_LOG, 0, NULL, &logSize);

    /*
    * If the build succeeds with no log, an empty string is returned (logSize = 1),
    * we only want to print the message if it has some content (logSize > 1).
    */
    if (logSize > 1)
    {
        char* log = new char[logSize];
        clGetProgramBuildInfo(*program, device, CL_PROGRAM_BUILD_LOG, logSize, log, NULL);

        std::string* stringChars = new std::string(log, logSize);
        const char* cStringChars = stringChars->c_str();
        LOGD("Build log:\n %s", cStringChars);
        delete [] log;
        delete stringChars;
    }

    if (!buildSuccess)
    {
        clReleaseProgram(*program);
        LOGE("Failed to build OpenCL program");
        return false;
    }

    return true;
}

inline bool checkSuccess(cl_int errorNumber)
{
    if (errorNumber != CL_SUCCESS)
    {
        LOGE("%s", errorNumberToString(errorNumber).c_str());
        return false;
    }
    return true;
}

std::string errorNumberToString(cl_int errorNumber)
{
    switch (errorNumber)
    {
        case CL_SUCCESS:
            return "CL_SUCCESS";
        case CL_DEVICE_NOT_FOUND:
            return "CL_DEVICE_NOT_FOUND";
        case CL_DEVICE_NOT_AVAILABLE:
            return "CL_DEVICE_NOT_AVAILABLE";
        case CL_COMPILER_NOT_AVAILABLE:
            return "CL_COMPILER_NOT_AVAILABLE";
        case CL_MEM_OBJECT_ALLOCATION_FAILURE:
            return "CL_MEM_OBJECT_ALLOCATION_FAILURE";
        case CL_OUT_OF_RESOURCES:
            return "CL_OUT_OF_RESOURCES";
        case CL_OUT_OF_HOST_MEMORY:
            return "CL_OUT_OF_HOST_MEMORY";
        case CL_PROFILING_INFO_NOT_AVAILABLE:
            return "CL_PROFILING_INFO_NOT_AVAILABLE";
        case CL_MEM_COPY_OVERLAP:
            return "CL_MEM_COPY_OVERLAP";
        case CL_IMAGE_FORMAT_MISMATCH:
            return "CL_IMAGE_FORMAT_MISMATCH";
        case CL_IMAGE_FORMAT_NOT_SUPPORTED:
            return "CL_IMAGE_FORMAT_NOT_SUPPORTED";
        case CL_BUILD_PROGRAM_FAILURE:
            return "CL_BUILD_PROGRAM_FAILURE";
        case CL_MAP_FAILURE:
            return "CL_MAP_FAILURE";
        case CL_EXEC_STATUS_ERROR_FOR_EVENTS_IN_WAIT_LIST:
            return "CL_EXEC_STATUS_ERROR_FOR_EVENTS_IN_WAIT_LIST";
        case CL_INVALID_VALUE:
            return "CL_INVALID_VALUE";
        case CL_INVALID_DEVICE_TYPE:
            return "CL_INVALID_DEVICE_TYPE";
        case CL_INVALID_PLATFORM:
            return "CL_INVALID_PLATFORM";
        case CL_INVALID_DEVICE:
            return "CL_INVALID_DEVICE";
        case CL_INVALID_CONTEXT:
            return "CL_INVALID_CONTEXT";
        case CL_INVALID_QUEUE_PROPERTIES:
            return "CL_INVALID_QUEUE_PROPERTIES";
        case CL_INVALID_COMMAND_QUEUE:
            return "CL_INVALID_COMMAND_QUEUE";
        case CL_INVALID_HOST_PTR:
            return "CL_INVALID_HOST_PTR";
        case CL_INVALID_MEM_OBJECT:
            return "CL_INVALID_MEM_OBJECT";
        case CL_INVALID_IMAGE_FORMAT_DESCRIPTOR:
            return "CL_INVALID_IMAGE_FORMAT_DESCRIPTOR";
        case CL_INVALID_IMAGE_SIZE:
            return "CL_INVALID_IMAGE_SIZE";
        case CL_INVALID_SAMPLER:
            return "CL_INVALID_SAMPLER";
        case CL_INVALID_BINARY:
            return "CL_INVALID_BINARY";
        case CL_INVALID_BUILD_OPTIONS:
            return "CL_INVALID_BUILD_OPTIONS";
        case CL_INVALID_PROGRAM:
            return "CL_INVALID_PROGRAM";
        case CL_INVALID_PROGRAM_EXECUTABLE:
            return "CL_INVALID_PROGRAM_EXECUTABLE";
        case CL_INVALID_KERNEL_NAME:
            return "CL_INVALID_KERNEL_NAME";
        case CL_INVALID_KERNEL_DEFINITION:
            return "CL_INVALID_KERNEL_DEFINITION";
        case CL_INVALID_KERNEL:
            return "CL_INVALID_KERNEL";
        case CL_INVALID_ARG_INDEX:
            return "CL_INVALID_ARG_INDEX";
        case CL_INVALID_ARG_VALUE:
            return "CL_INVALID_ARG_VALUE";
        case CL_INVALID_ARG_SIZE:
            return "CL_INVALID_ARG_SIZE";
        case CL_INVALID_KERNEL_ARGS:
            return "CL_INVALID_KERNEL_ARGS";
        case CL_INVALID_WORK_DIMENSION:
            return "CL_INVALID_WORK_DIMENSION";
        case CL_INVALID_WORK_GROUP_SIZE:
            return "CL_INVALID_WORK_GROUP_SIZE";
        case CL_INVALID_WORK_ITEM_SIZE:
            return "CL_INVALID_WORK_ITEM_SIZE";
        case CL_INVALID_GLOBAL_OFFSET:
            return "CL_INVALID_GLOBAL_OFFSET";
        case CL_INVALID_EVENT_WAIT_LIST:
            return "CL_INVALID_EVENT_WAIT_LIST";
        case CL_INVALID_EVENT:
            return "CL_INVALID_EVENT";
        case CL_INVALID_OPERATION:
            return "CL_INVALID_OPERATION";
        case CL_INVALID_GL_OBJECT:
            return "CL_INVALID_GL_OBJECT";
        case CL_INVALID_BUFFER_SIZE:
            return "CL_INVALID_BUFFER_SIZE";
        case CL_INVALID_MIP_LEVEL:
            return "CL_INVALID_MIP_LEVEL";
        default:
            return "Unknown error";
    }
}

bool createContext(cl_context* context)
{
    cl_int errorNumber = 0;
    cl_uint numberOfPlatforms = 0;
    cl_platform_id firstPlatformID = 0;

    // Retrieve a single platform ID.
    if (!checkSuccess(clGetPlatformIDs(1, &firstPlatformID, &numberOfPlatforms)))
    {
        LOGE("Retrieving OpenCL platforms failed");
        return false;
    }

    if (numberOfPlatforms <= 0)
    {
        LOGE("No platforms found");
        return false;
    }

    // Get a context with a GPU device from the platform found above
    cl_context_properties contextProperties [] = {CL_CONTEXT_PLATFORM, (cl_context_properties)firstPlatformID, 0};
    *context = clCreateContextFromType(contextProperties, CL_DEVICE_TYPE_GPU, NULL, NULL, &errorNumber);
    if (!checkSuccess(errorNumber))
    {
        LOGE("Creating an OpenCL context failed");
        return false;
    }

    return true;
}

