package rs117.hd.utils.buffer;

import java.nio.IntBuffer;
import rs117.hd.opengl.compute.OpenCLManager;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opencl.CL10GL.clCreateFromGLBuffer;

public class SharedGLBuffer extends GLBuffer {
	public final int clUsage;

	public long clId;

	public SharedGLBuffer(String name, int target, int glUsage, int clUsage) {
		super(name, target, glUsage);
		this.clUsage = clUsage;
	}

	private void releaseCLBuffer() {
		if (clId != 0)
			clReleaseMemObject(clId);
		clId = 0;
	}

	@Override
	public void destroy() {
		releaseCLBuffer();
		super.destroy();
	}

	@Override
	public void ensureCapacity(long byteOffset, long numBytes) {
		super.ensureCapacity(byteOffset, numBytes);
		if (OpenCLManager.context == 0)
			return;

		releaseCLBuffer();

		// OpenCL does not allow 0-size GL buffers, it will segfault on macOS
		if (size != 0)
			clId = clCreateFromGLBuffer(OpenCLManager.context, clUsage, id, (IntBuffer) null);
	}
}
