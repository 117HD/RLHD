/*
 * Copyright (c) 2021, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd.opengl.compute;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.OSType;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.*;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.slf4j.LoggerFactory;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.Template;
import rs117.hd.utils.buffer.GLBuffer;

import javax.inject.Singleton;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.opencl.APPLEGLSharing.CL_CGL_DEVICE_FOR_CURRENT_VIRTUAL_SCREEN_APPLE;
import static org.lwjgl.opencl.APPLEGLSharing.clGetGLContextInfoAPPLE;
import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opencl.CL12.CL_PROGRAM_BINARY_TYPE;
import static org.lwjgl.opencl.CL12.clReleaseDevice;
import static org.lwjgl.opencl.KHRGLSharing.*;
import static org.lwjgl.system.MemoryUtil.*;
import static rs117.hd.HdPlugin.MAX_TRIANGLE;
import static rs117.hd.HdPlugin.SMALL_TRIANGLE_COUNT;

@Singleton
@Slf4j
public class OpenCLManager {
	private static final String KERNEL_NAME_UNORDERED = "computeUnordered";
	private static final String KERNEL_NAME_LARGE = "computeLarge";

	private static final int MIN_WORK_GROUP_SIZE = 256;
	private static final int SMALL_SIZE = SMALL_TRIANGLE_COUNT;
	private static final int LARGE_SIZE = MAX_TRIANGLE;
	//  struct shared_data {
	//      int totalNum[12];
	//      int totalDistance[12];
	//      int totalMappedNum[18];
	//      int min10;
	//      int dfs[0];
	//  };
	private static final int SHARED_SIZE = 12 + 12 + 18 + 1; // in ints

	// The number of faces each worker processes in the two kernels
	private int largeFaceCount;
	private int smallFaceCount;

	private boolean initialized;

	private long device;
	@Getter
	private long context;
	private long commandQueue;

	private long programUnordered;
	private long programSmall;
	private long programLarge;

	private long kernelUnordered;
	private long kernelSmall;
	private long kernelLarge;

	static {
		Configuration.OPENCL_EXPLICIT_INIT.set(true);
	}

	public void startUp(AWTContext awtContext) {
		CL.create();
		initialized = true;

		if (OSType.getOSType() == OSType.MacOS) {
			initContextMacOS(awtContext);
		} else {
			initContext(awtContext);
		}
		ensureMinWorkGroupSize();
		initQueue();
	}

	public void shutDown() {
		if (!initialized)
			return;

		try {
			destroyPrograms();

			if (commandQueue != 0) {
				clReleaseCommandQueue(commandQueue);
				commandQueue = 0;
			}
			if (context != 0) {
				clReleaseContext(context);
				context = 0;
			}
			if (device != 0) {
				clReleaseDevice(device);
				device = 0;
			}
		} finally {
			CL.destroy();
			initialized = false;
		}
	}

	private void initContext(AWTContext awtContext) {
		try (var stack = MemoryStack.stackPush()) {
			IntBuffer pi = stack.mallocInt(1);
			checkCLError(clGetPlatformIDs(null, pi));
			if (pi.get(0) == 0)
				throw new RuntimeException("No OpenCL platforms found.");

			PointerBuffer platforms = stack.mallocPointer(pi.get(0));
			checkCLError(clGetPlatformIDs(platforms, (IntBuffer) null));

			PointerBuffer ctxProps = stack.mallocPointer(7);
			if (OSType.getOSType() == OSType.Windows) {
				ctxProps
					.put(CL_CONTEXT_PLATFORM)
					.put(0)
					.put(CL_GL_CONTEXT_KHR)
					.put(awtContext.getGLContext())
					.put(CL_WGL_HDC_KHR)
					.put(awtContext.getWGLHDC())
					.put(0)
					.flip();
			} else if (OSType.getOSType() == OSType.Linux) {
				ctxProps
					.put(CL_CONTEXT_PLATFORM)
					.put(0)
					.put(CL_GL_CONTEXT_KHR)
					.put(awtContext.getGLContext())
					.put(CL_GLX_DISPLAY_KHR)
					.put(awtContext.getGLXDisplay())
					.put(0)
					.flip();
			} else {
				throw new RuntimeException("unsupported platform");
			}

			if (platforms.limit() == 0)
				throw new RuntimeException("Unable to find compute platform");

			IntBuffer errcode_ret = stack.callocInt(1);
			for (int p = 0; p < platforms.limit(); p++) {
				try {
					long platform = platforms.get(p);
					ctxProps.put(1, platform);

					log.debug("Platform index {}:", p);
					log.debug("\tprofile: {}", getPlatformInfoStringUTF8(platform, CL_PLATFORM_PROFILE));
					log.debug("\tversion: {}", getPlatformInfoStringUTF8(platform, CL_PLATFORM_VERSION));
					log.debug("\tname: {}", getPlatformInfoStringUTF8(platform, CL_PLATFORM_NAME));
					log.debug("\tvendor: {}", getPlatformInfoStringUTF8(platform, CL_PLATFORM_VENDOR));
					log.debug("\textensions: {}", getPlatformInfoStringUTF8(platform, CL_PLATFORM_EXTENSIONS));

					int returnCode = clGetDeviceIDs(platform, CL_DEVICE_TYPE_ALL, null, pi);
					if (returnCode == CL_INVALID_DEVICE_TYPE) {
						log.debug("\tno devices");
						continue;
					}
					checkCLError(returnCode);

					PointerBuffer devices = stack.mallocPointer(pi.get(0));
					checkCLError(clGetDeviceIDs(platform, CL_DEVICE_TYPE_ALL, devices, (IntBuffer) null));

					for (int d = 0; d < devices.capacity(); d++) {
						long device = devices.get(d);
						long deviceType = getDeviceInfoLong(device, CL_DEVICE_TYPE);

						log.debug("\tdevice index {}:", d);
						log.debug("\t\tCL_DEVICE_NAME: {}", getDeviceInfoStringUTF8(device, CL_DEVICE_NAME));
						log.debug("\t\tCL_DEVICE_VENDOR: {}", getDeviceInfoStringUTF8(device, CL_DEVICE_VENDOR));
						log.debug("\t\tCL_DRIVER_VERSION: {}", getDeviceInfoStringUTF8(device, CL_DRIVER_VERSION));
						log.debug("\t\tCL_DEVICE_PROFILE: {}", getDeviceInfoStringUTF8(device, CL_DEVICE_PROFILE));
						log.debug("\t\tCL_DEVICE_VERSION: {}", getDeviceInfoStringUTF8(device, CL_DEVICE_VERSION));
						log.debug("\t\tCL_DEVICE_EXTENSIONS: {}", getDeviceInfoStringUTF8(device, CL_DEVICE_EXTENSIONS));
						log.debug("\t\tCL_DEVICE_TYPE: {}", deviceType);
						log.debug("\t\tCL_DEVICE_VENDOR_ID: {}", getDeviceInfoInt(device, CL_DEVICE_VENDOR_ID));
						log.debug("\t\tCL_DEVICE_MAX_COMPUTE_UNITS: {}", getDeviceInfoInt(device, CL_DEVICE_MAX_COMPUTE_UNITS));
						log.debug("\t\tCL_DEVICE_MAX_WORK_ITEM_DIMENSIONS: {}", getDeviceInfoInt(device, CL_DEVICE_MAX_WORK_ITEM_DIMENSIONS));
						log.debug("\t\tCL_DEVICE_MAX_WORK_GROUP_SIZE: {}", getDeviceInfoPointer(device, CL_DEVICE_MAX_WORK_GROUP_SIZE));
						log.debug("\t\tCL_DEVICE_MAX_CLOCK_FREQUENCY: {}", getDeviceInfoInt(device, CL_DEVICE_MAX_CLOCK_FREQUENCY));
						log.debug("\t\tCL_DEVICE_ADDRESS_BITS: {}", getDeviceInfoInt(device, CL_DEVICE_ADDRESS_BITS));
						log.debug("\t\tCL_DEVICE_AVAILABLE: {}", getDeviceInfoInt(device, CL_DEVICE_AVAILABLE) != 0);
						log.debug("\t\tCL_DEVICE_COMPILER_AVAILABLE: {}", (getDeviceInfoInt(device, CL_DEVICE_COMPILER_AVAILABLE) != 0));

						if (deviceType != CL_DEVICE_TYPE_GPU)
							continue;
						CLCapabilities platformCaps = CL.createPlatformCapabilities(platform);
						CLCapabilities deviceCaps = CL.createDeviceCapabilities(device, platformCaps);
						if (!deviceCaps.cl_khr_gl_sharing && !deviceCaps.cl_APPLE_gl_sharing)
							continue;

						if (this.context == 0) {
							try {
								var callback = CLContextCallback.create((errinfo, private_info, cb, user_data) ->
									log.error("[LWJGL] cl_context_callback: {}", memUTF8(errinfo)));
								long context = clCreateContext(ctxProps, device, callback, NULL, errcode_ret);
								checkCLError(errcode_ret);

								this.device = device;
								this.context = context;
							} catch (Exception ex) {
								log.error("Error while creating context:", ex);
							}
						}
					}
				} catch (Exception ex) {
					log.error("Error while checking platform:", ex);
				}
			}

			if (this.context == 0)
				throw new RuntimeException("Unable to create suitable compute context");
		}
	}

	private void initContextMacOS(AWTContext awtContext) {
		try (var stack = MemoryStack.stackPush()) {
			PointerBuffer ctxProps = stack.mallocPointer(3);
			ctxProps
				.put(APPLEGLSharing.CL_CONTEXT_PROPERTY_USE_CGL_SHAREGROUP_APPLE)
				.put(awtContext.getCGLShareGroup())
				.put(0)
				.flip();

			IntBuffer errcode_ret = stack.callocInt(1);
			var devices = stack.mallocPointer(0);
			long context = clCreateContext(ctxProps, devices, CLContextCallback.create((errinfo, private_info, cb, user_data) ->
				log.error("[LWJGL] cl_context_callback: {}", memUTF8(errinfo))), NULL, errcode_ret);
			checkCLError(errcode_ret);

			var deviceBuf = stack.mallocPointer(1);
			checkCLError(clGetGLContextInfoAPPLE(context, awtContext.getGLContext(), CL_CGL_DEVICE_FOR_CURRENT_VIRTUAL_SCREEN_APPLE, deviceBuf, null));
			long device = deviceBuf.get(0);

			log.debug("Got macOS CLGL compute device {}", device);
			this.context = context;
			this.device = device;
		}
	}

	private void ensureMinWorkGroupSize() {
		long[] maxWorkGroupSize = new long[1];
		clGetDeviceInfo(device, CL_DEVICE_MAX_WORK_GROUP_SIZE, maxWorkGroupSize, null);
		log.debug("Device CL_DEVICE_MAX_WORK_GROUP_SIZE: {}", maxWorkGroupSize[0]);

		if (maxWorkGroupSize[0] < MIN_WORK_GROUP_SIZE)
			throw new RuntimeException("Compute device does not support min work group size " + MIN_WORK_GROUP_SIZE);

		// Largest power of 2 less than or equal to maxWorkGroupSize
		int groupSize = 0x80000000 >>> Integer.numberOfLeadingZeros((int) maxWorkGroupSize[0]);
		largeFaceCount = LARGE_SIZE / (Math.min(groupSize, LARGE_SIZE));
		smallFaceCount = SMALL_SIZE / (Math.min(groupSize, SMALL_SIZE));

		log.debug("Face counts: small: {}, large: {}", smallFaceCount, largeFaceCount);
	}

	private void initQueue() {
		long[] l = new long[1];
		clGetDeviceInfo(device, CL_DEVICE_QUEUE_PROPERTIES, l, null);

		commandQueue = clCreateCommandQueue(context, device, l[0] & CL_QUEUE_OUT_OF_ORDER_EXEC_MODE_ENABLE, (int[]) null);
		log.debug("Created command_queue {}, properties {}", commandQueue, l[0] & CL_QUEUE_OUT_OF_ORDER_EXEC_MODE_ENABLE);
	}

	private long compileProgram(MemoryStack stack, String programSource) throws ShaderException {
		log.trace("Compiling program:\n {}", programSource);
		IntBuffer errcode_ret = stack.callocInt(1);
		long program = clCreateProgramWithSource(context, programSource, errcode_ret);
		checkCLError(errcode_ret);

		int err = clBuildProgram(program, device, "", null, 0);
		if (err != CL_SUCCESS)
			throw new ShaderException(getProgramBuildInfoStringASCII(program, device, CL_PROGRAM_BUILD_LOG));

		log.debug("Build status: {}", getProgramBuildInfoInt(program, device, CL_PROGRAM_BUILD_STATUS));
		log.debug("Binary type: {}", getProgramBuildInfoInt(program, device, CL_PROGRAM_BINARY_TYPE));
		log.debug("Build options: {}", getProgramBuildInfoStringASCII(program, device, CL_PROGRAM_BUILD_OPTIONS));
		log.debug("Build log: {}", getProgramBuildInfoStringASCII(program, device, CL_PROGRAM_BUILD_LOG));
		return program;
	}

	private long getKernel(MemoryStack stack, long program, String kernelName) {
		IntBuffer errcode_ret = stack.callocInt(1);
		long kernel = clCreateKernel(program, kernelName, errcode_ret);
		checkCLError(errcode_ret);
		log.debug("Loaded kernel {} for program {}", kernelName, program);
		return kernel;
	}

	public void initPrograms() throws ShaderException, IOException {
		try (var stack = MemoryStack.stackPush()) {
			var template = new Template().addIncludePath(OpenCLManager.class);
			programUnordered = compileProgram(stack, template.load("comp_unordered.cl"));
			programSmall = compileProgram(stack, template
				.copy()
				.define("FACE_COUNT", smallFaceCount)
				.load("comp.cl"));
			programLarge = compileProgram(stack, template
				.copy()
				.define("FACE_COUNT", largeFaceCount)
				.load("comp.cl"));

			kernelUnordered = getKernel(stack, programUnordered, KERNEL_NAME_UNORDERED);
			kernelSmall = getKernel(stack, programSmall, KERNEL_NAME_LARGE);
			kernelLarge = getKernel(stack, programLarge, KERNEL_NAME_LARGE);
		}
	}

	public void destroyPrograms() {
		if (kernelUnordered != 0) {
			clReleaseKernel(kernelUnordered);
			kernelUnordered = 0;
		}
		if (kernelSmall != 0) {
			clReleaseKernel(kernelSmall);
			kernelSmall = 0;
		}
		if (kernelLarge != 0) {
			clReleaseKernel(kernelLarge);
			kernelLarge = 0;
		}

		if (programUnordered != 0) {
			clReleaseProgram(programUnordered);
			programUnordered = 0;
		}
		if (programSmall != 0) {
			clReleaseProgram(programSmall);
			programSmall = 0;
		}
		if (programLarge != 0) {
			clReleaseProgram(programLarge);
			programLarge = 0;
		}
	}

	public void compute(
		GLBuffer uniformBufferCamera,
		int unorderedModels, int smallModels, int largeModels,
		GLBuffer modelBufferUnordered, GLBuffer modelBufferSmall, GLBuffer modelBufferLarge,
		GLBuffer stagingBufferVertices, GLBuffer stagingBufferUvs, GLBuffer stagingBufferNormals,
		GLBuffer renderBufferVertices, GLBuffer renderBufferUvs, GLBuffer renderBufferNormals
	) {
		try (var stack = MemoryStack.stackPush()) {
			PointerBuffer glBuffers = stack.mallocPointer(10)
				.put(uniformBufferCamera.clBuffer)
				.put(modelBufferUnordered.clBuffer)
				.put(modelBufferSmall.clBuffer)
				.put(modelBufferLarge.clBuffer)
				.put(stagingBufferVertices.clBuffer)
				.put(stagingBufferUvs.clBuffer)
				.put(stagingBufferNormals.clBuffer)
				.put(renderBufferVertices.clBuffer)
				.put(renderBufferUvs.clBuffer)
				.put(renderBufferNormals.clBuffer)
				.flip();

			PointerBuffer acquireEvent = stack.mallocPointer(1);
			CL10GL.clEnqueueAcquireGLObjects(commandQueue, glBuffers, null, acquireEvent);

			PointerBuffer computeEvents = stack.mallocPointer(3);
			if (unorderedModels > 0) {
				clSetKernelArg1p(kernelUnordered, 0, modelBufferUnordered.clBuffer);
				clSetKernelArg1p(kernelUnordered, 1, stagingBufferVertices.clBuffer);
				clSetKernelArg1p(kernelUnordered, 2, stagingBufferUvs.clBuffer);
				clSetKernelArg1p(kernelUnordered, 3, stagingBufferNormals.clBuffer);
				clSetKernelArg1p(kernelUnordered, 4, renderBufferVertices.clBuffer);
				clSetKernelArg1p(kernelUnordered, 5, renderBufferUvs.clBuffer);
				clSetKernelArg1p(kernelUnordered, 6, renderBufferNormals.clBuffer);

				// queue compute call after acquireGLBuffers
				clEnqueueNDRangeKernel(commandQueue, kernelUnordered, 1, null,
					stack.pointers(unorderedModels * 6L), stack.pointers(6),
					acquireEvent, computeEvents
				);
				computeEvents.position(computeEvents.position() + 1);
			}

			if (smallModels > 0) {
				clSetKernelArg(kernelSmall, 0, (SHARED_SIZE + SMALL_SIZE) * Integer.BYTES);
				clSetKernelArg1p(kernelSmall, 1, modelBufferSmall.clBuffer);
				clSetKernelArg1p(kernelSmall, 2, stagingBufferVertices.clBuffer);
				clSetKernelArg1p(kernelSmall, 3, stagingBufferUvs.clBuffer);
				clSetKernelArg1p(kernelSmall, 4, stagingBufferNormals.clBuffer);
				clSetKernelArg1p(kernelSmall, 5, renderBufferVertices.clBuffer);
				clSetKernelArg1p(kernelSmall, 6, renderBufferUvs.clBuffer);
				clSetKernelArg1p(kernelSmall, 7, renderBufferNormals.clBuffer);
				clSetKernelArg1p(kernelSmall, 8, uniformBufferCamera.clBuffer);

				clEnqueueNDRangeKernel(commandQueue, kernelSmall, 1, null,
					stack.pointers((long) smallModels * (SMALL_SIZE / smallFaceCount)),
					stack.pointers(SMALL_SIZE / smallFaceCount),
					acquireEvent, computeEvents
				);
				computeEvents.position(computeEvents.position() + 1);
			}

			if (largeModels > 0) {
				clSetKernelArg(kernelLarge, 0, (SHARED_SIZE + LARGE_SIZE) * Integer.BYTES);
				clSetKernelArg1p(kernelLarge, 1, modelBufferLarge.clBuffer);
				clSetKernelArg1p(kernelLarge, 2, stagingBufferVertices.clBuffer);
				clSetKernelArg1p(kernelLarge, 3, stagingBufferUvs.clBuffer);
				clSetKernelArg1p(kernelLarge, 4, stagingBufferNormals.clBuffer);
				clSetKernelArg1p(kernelLarge, 5, renderBufferVertices.clBuffer);
				clSetKernelArg1p(kernelLarge, 6, renderBufferUvs.clBuffer);
				clSetKernelArg1p(kernelLarge, 7, renderBufferNormals.clBuffer);
				clSetKernelArg1p(kernelLarge, 8, uniformBufferCamera.clBuffer);

				clEnqueueNDRangeKernel(commandQueue, kernelLarge, 1, null,
					stack.pointers((long) largeModels * (LARGE_SIZE / largeFaceCount)),
					stack.pointers(LARGE_SIZE / largeFaceCount),
					acquireEvent, computeEvents
				);
				computeEvents.position(computeEvents.position() + 1);
			}

			if (computeEvents.position() == 0) {
				CL10GL.clEnqueueReleaseGLObjects(commandQueue, glBuffers, null, null);
			} else {
				computeEvents.flip();
				CL10GL.clEnqueueReleaseGLObjects(commandQueue, glBuffers, computeEvents, null);
			}
		}
	}

	public void finish() {
		clFinish(commandQueue);
	}

	private static String getPlatformInfoStringUTF8(long cl_platform_id, int param_name) {
		try (var stack = MemoryStack.stackPush()) {
			PointerBuffer pp = stack.mallocPointer(1);
			checkCLError(clGetPlatformInfo(cl_platform_id, param_name, (ByteBuffer) null, pp));
			int bytes = (int) pp.get(0);

			ByteBuffer buffer = stack.malloc(bytes);
			checkCLError(clGetPlatformInfo(cl_platform_id, param_name, buffer, null));

			return memUTF8(buffer, bytes - 1);
		}
	}

	private static long getDeviceInfoLong(long cl_device_id, int param_name) {
		try (var stack = MemoryStack.stackPush()) {
			LongBuffer pl = stack.mallocLong(1);
			checkCLError(clGetDeviceInfo(cl_device_id, param_name, pl, null));
			return pl.get(0);
		}
	}

	private static int getDeviceInfoInt(long cl_device_id, int param_name) {
		try (var stack = MemoryStack.stackPush()) {
			IntBuffer pl = stack.mallocInt(1);
			checkCLError(clGetDeviceInfo(cl_device_id, param_name, pl, null));
			return pl.get(0);
		}
	}

	private static long getDeviceInfoPointer(long cl_device_id, int param_name) {
		try (var stack = MemoryStack.stackPush()) {
			PointerBuffer pp = stack.mallocPointer(1);
			checkCLError(clGetDeviceInfo(cl_device_id, param_name, pp, null));
			return pp.get(0);
		}
	}

	private static String getDeviceInfoStringUTF8(long cl_device_id, int param_name) {
		try (var stack = MemoryStack.stackPush()) {
			PointerBuffer pp = stack.mallocPointer(1);
			checkCLError(clGetDeviceInfo(cl_device_id, param_name, (ByteBuffer) null, pp));
			int bytes = (int) pp.get(0);

			ByteBuffer buffer = stack.malloc(bytes);
			checkCLError(clGetDeviceInfo(cl_device_id, param_name, buffer, null));

			return memUTF8(buffer, bytes - 1);
		}
	}

	private static int getProgramBuildInfoInt(long cl_program_id, long cl_device_id, int param_name) {
		try (var stack = MemoryStack.stackPush()) {
			IntBuffer pl = stack.mallocInt(1);
			checkCLError(clGetProgramBuildInfo(cl_program_id, cl_device_id, param_name, pl, null));
			return pl.get(0);
		}
	}

	private static String getProgramBuildInfoStringASCII(long cl_program_id, long cl_device_id, int param_name) {
		try (var stack = MemoryStack.stackPush()) {
			PointerBuffer pp = stack.mallocPointer(1);
			checkCLError(clGetProgramBuildInfo(cl_program_id, cl_device_id, param_name, (ByteBuffer) null, pp));
			int bytes = (int) pp.get(0);

			ByteBuffer buffer = stack.malloc(bytes);
			checkCLError(clGetProgramBuildInfo(cl_program_id, cl_device_id, param_name, buffer, null));

			return memASCII(buffer, bytes - 1);
		}
	}

	private static void checkCLError(IntBuffer errcode) {
		checkCLError(errcode.get(errcode.position()));
	}

	private static void checkCLError(int errcode) {
		if (errcode != CL_SUCCESS)
			throw new RuntimeException(String.format("OpenCL error [%d]", errcode));
	}
}
