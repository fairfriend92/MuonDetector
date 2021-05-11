<h1>MuonDetector</h1>

This is an application for Android devices which uses the camera of the device, if available, to capture the traces of the particles that may collide with the sensor. The application analyzes in real-time the feed of the camera, saving snapshots of the feed that present pixels with a luminosity higher than average. The average luminosity may be computed using either the GPU or the CPU of the device. Software mode is much slower and only implemented as a fall-back solution, hence it's not recommended.

Once the capture session has been ended by the user, the pictures are stored locally and sent to an Overmind server, if the application was able to connect to one. For more information on the Overmind distributed platform for the simulation of spiking neural networks, check my other repositories. 
