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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.util.OSType;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.*;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.Template;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opencl.APPLEGLSharing.*;
import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opencl.CL10GL.*;
import static org.lwjgl.opencl.CL12.*;
import static org.lwjgl.opencl.KHRGLSharing.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memASCII;
import static org.lwjgl.system.MemoryUtil.memUTF8;

@Singleton
@Slf4j
public class OpenCLManager {
	private static final String KERNEL_NAME_PASSTHROUGH = "passthroughModel";
	private static final String KERNEL_NAME_SORT = "sortModel";

	//  struct shared_data {
	//      int totalNum[12];
	//      int totalDistance[12];
	//      int totalMappedNum[18];
	//      int min10;
	//      int dfs[0];
	//  };
	private static final int SHARED_SIZE = 12 + 12 + 18 + 1; // in ints

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	private boolean initialized;

	private long device;
	private long context;
	private long commandQueue;

	private long passthroughProgram;
	private long[] sortingPrograms;

	private long passthroughKernel;
	private long[] sortingKernels;

	private long tileHeightMap;

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
		log.debug("Device CL_DEVICE_MAX_WORK_GROUP_SIZE: {}", getMaxWorkGroupSize());
		initQueue();
	}

	public void shutDown() {
		if (!initialized)
			return;

		try {
			if (tileHeightMap != 0)
				clReleaseMemObject(tileHeightMap);
			tileHeightMap = 0;

			destroyPrograms();

			if (commandQueue != 0)
				clReleaseCommandQueue(commandQueue);
			commandQueue = 0;
			if (context != 0)
				clReleaseContext(context);
			context = 0;
			if (device != 0)
				clReleaseDevice(device);
			device = 0;
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
			checkCLError(clGetGLContextInfoAPPLE(
				context,
				awtContext.getGLContext(),
				CL_CGL_DEVICE_FOR_CURRENT_VIRTUAL_SCREEN_APPLE,
				deviceBuf,
				null
			));
			long device = deviceBuf.get(0);

			log.debug("Got macOS CLGL compute device {}", device);
			this.context = context;
			this.device = device;
		}
	}

	public int getMaxWorkGroupSize() {
		long[] maxWorkGroupSize = new long[1];
		clGetDeviceInfo(device, CL_DEVICE_MAX_WORK_GROUP_SIZE, maxWorkGroupSize, null);
		return (int) maxWorkGroupSize[0];
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
			var template = new Template()
				.define("UNDO_VANILLA_SHADING", config.undoVanillaShadingInCompute())
				.define("LEGACY_GREY_COLORS", config.legacyGreyColors())
				.addIncludePath(OpenCLManager.class);
			passthroughProgram = compileProgram(stack, template.load("comp_unordered.cl"));
			passthroughKernel = getKernel(stack, passthroughProgram, KERNEL_NAME_PASSTHROUGH);

			sortingPrograms = new long[plugin.numSortingBins];
			sortingKernels = new long[plugin.numSortingBins];
			for (int i = 0; i < plugin.numSortingBins; i++) {
				int faceCount = plugin.modelSortingBinFaceCounts[i];
				int threadCount = plugin.modelSortingBinThreadCounts[i];
				int facesPerThread = (int) Math.ceil((float) faceCount / threadCount);
				sortingPrograms[i] = compileProgram(stack, template
					.copy()
					.define("THREAD_COUNT", threadCount)
					.define("FACES_PER_THREAD", facesPerThread)
					.load("comp.cl")
				);
				sortingKernels[i] = getKernel(stack, sortingPrograms[i], KERNEL_NAME_SORT);
			}
		}
	}

	public void destroyPrograms() {
		if (passthroughKernel != 0)
			clReleaseKernel(passthroughKernel);
		passthroughKernel = 0;

		if (passthroughProgram != 0)
			clReleaseProgram(passthroughProgram);
		passthroughProgram = 0;

		if (sortingKernels != null)
			for (var kernel : sortingKernels)
				if (kernel != 0)
					clReleaseKernel(kernel);
		sortingKernels = null;

		if (sortingPrograms != null)
			for (var program : sortingPrograms)
				if (program != 0)
					clReleaseProgram(program);
		sortingPrograms = null;
	}

	public void uploadTileHeights(Scene scene) {
		if (tileHeightMap != 0)
			clReleaseMemObject(tileHeightMap);
		tileHeightMap = 0;

		final int TILEHEIGHT_BUFFER_SIZE = Constants.MAX_Z * Constants.EXTENDED_SCENE_SIZE * Constants.EXTENDED_SCENE_SIZE * Short.BYTES;
		ShortBuffer tileBuffer = BufferUtils.createShortBuffer(TILEHEIGHT_BUFFER_SIZE);
		int[][][] tileHeights = scene.getTileHeights();
		for (int z = 0; z < Constants.MAX_Z; ++z) {
			for (int y = 0; y < Constants.EXTENDED_SCENE_SIZE; ++y) {
				for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE; ++x) {
					int h = tileHeights[z][x][y];
					assert (h & 0b111) == 0;
					h >>= 3;
					tileBuffer.put((short) h);
				}
			}
		}
		tileBuffer.flip();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			CLImageFormat imageFormat = CLImageFormat.calloc(stack);
			imageFormat.image_channel_order(CL_R);
			imageFormat.image_channel_data_type(CL_SIGNED_INT16);

			IntBuffer errcode_ret = stack.callocInt(1);
			tileHeightMap = clCreateImage3D(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, imageFormat,
				Constants.EXTENDED_SCENE_SIZE, Constants.EXTENDED_SCENE_SIZE, Constants.MAX_Z,
				0L, 0L,
				tileBuffer,
				errcode_ret
			);
			checkCLError(errcode_ret);
		}
	}

	public void compute(
		GLBuffer uniformBufferCamera,
		int numPassthroughModels, int[] numSortingBinModels,
		GLBuffer modelPassthroughBuffer, GLBuffer[] modelSortingBuffers,
		GLBuffer stagingBufferVertices, GLBuffer stagingBufferUvs, GLBuffer stagingBufferNormals,
		GLBuffer renderBufferVertices, GLBuffer renderBufferUvs, GLBuffer renderBufferNormals
	) {
		try (var stack = MemoryStack.stackPush()) {
			PointerBuffer glBuffers = stack.mallocPointer(8 + modelSortingBuffers.length)
				.put(uniformBufferCamera.clBuffer)
				.put(modelPassthroughBuffer.clBuffer)
				.put(stagingBufferVertices.clBuffer)
				.put(stagingBufferUvs.clBuffer)
				.put(stagingBufferNormals.clBuffer)
				.put(renderBufferVertices.clBuffer)
				.put(renderBufferUvs.clBuffer)
				.put(renderBufferNormals.clBuffer);
			for (var buffer : modelSortingBuffers)
				glBuffers.put(buffer.clBuffer);
			glBuffers.flip();

			PointerBuffer acquireEvent = stack.mallocPointer(1);
			CL10GL.clEnqueueAcquireGLObjects(commandQueue, glBuffers, null, acquireEvent);

			PointerBuffer computeEvents = stack.mallocPointer(1 + modelSortingBuffers.length);
			if (numPassthroughModels > 0) {
				clSetKernelArg1p(passthroughKernel, 0, modelPassthroughBuffer.clBuffer);
				clSetKernelArg1p(passthroughKernel, 1, stagingBufferVertices.clBuffer);
				clSetKernelArg1p(passthroughKernel, 2, stagingBufferUvs.clBuffer);
				clSetKernelArg1p(passthroughKernel, 3, stagingBufferNormals.clBuffer);
				clSetKernelArg1p(passthroughKernel, 4, renderBufferVertices.clBuffer);
				clSetKernelArg1p(passthroughKernel, 5, renderBufferUvs.clBuffer);
				clSetKernelArg1p(passthroughKernel, 6, renderBufferNormals.clBuffer);

				// queue compute call after acquireGLBuffers
				clEnqueueNDRangeKernel(commandQueue, passthroughKernel, 1, null,
					stack.pointers(numPassthroughModels * 6L), stack.pointers(6),
					acquireEvent, computeEvents
				);
				computeEvents.position(computeEvents.position() + 1);
			}

			for (int i = 0; i < numSortingBinModels.length; i++) {
				int numModels = numSortingBinModels[i];
				if (numModels == 0)
					continue;

				int faceCount = plugin.modelSortingBinFaceCounts[i];
				int threadCount = plugin.modelSortingBinThreadCounts[i];
				long kernel = sortingKernels[i];

				clSetKernelArg(kernel, 0, (long) (SHARED_SIZE + faceCount) * Integer.BYTES);
				clSetKernelArg1p(kernel, 1, modelSortingBuffers[i].clBuffer);
				clSetKernelArg1p(kernel, 2, stagingBufferVertices.clBuffer);
				clSetKernelArg1p(kernel, 3, stagingBufferUvs.clBuffer);
				clSetKernelArg1p(kernel, 4, stagingBufferNormals.clBuffer);
				clSetKernelArg1p(kernel, 5, renderBufferVertices.clBuffer);
				clSetKernelArg1p(kernel, 6, renderBufferUvs.clBuffer);
				clSetKernelArg1p(kernel, 7, renderBufferNormals.clBuffer);
				clSetKernelArg1p(kernel, 8, uniformBufferCamera.clBuffer);
				clSetKernelArg1p(kernel, 9, tileHeightMap);

				clEnqueueNDRangeKernel(commandQueue, kernel, 1, null,
					stack.pointers((long) numModels * threadCount),
					stack.pointers(threadCount),
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
		if (commandQueue != 0)
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

	public void recreateCLBuffer(GLBuffer glBuffer, long clFlags) {
		// cleanup previous buffer
		if (glBuffer.clBuffer != 0)
			clReleaseMemObject(glBuffer.clBuffer);

		// allocate new
		if (glBuffer.size == 0) {
			// opencl does not allow 0-size gl buffers, it will segfault on macOS
			glBuffer.clBuffer = 0;
		} else {
			assert glBuffer.size > 0 : "Size <= 0 should not reach this point";
			glBuffer.clBuffer = clCreateFromGLBuffer(context, clFlags, glBuffer.glBufferId, (IntBuffer) null);
		}
	}
}
