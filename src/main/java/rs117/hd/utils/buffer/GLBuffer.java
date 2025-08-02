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
package rs117.hd.utils.buffer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.utils.HDUtils;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@RequiredArgsConstructor
public class GLBuffer
{
	public final String name;
	public final int target;
	public final int usage;

	public int id;
	public long size;

	public void initialize() {
		initialize(0);
	}

	public void initialize(long initialCapacity) {
		id = glGenBuffers();
		// Initialize both GL and CL buffers to buffers of a single byte or more,
		// to ensure that valid buffers are given to compute dispatches.
		// This is particularly important on Apple M2 Max, where an uninitialized buffer leads to a crash
		ensureCapacity(max(1, initialCapacity));
	}

	public void destroy() {
		size = 0;

		if (id != 0) {
			glDeleteBuffers(id);
			id = 0;
		}
	}

	public void ensureCapacity(long numBytes) {
		ensureCapacity(0, numBytes);
	}

	public void ensureCapacity(long byteOffset, long numBytes) {
		numBytes += byteOffset;
		if (numBytes <= size) {
			glBindBuffer(target, id);
			return;
		}

		numBytes = HDUtils.ceilPow2(numBytes);
		if (log.isDebugEnabled() && numBytes > 1e6)
			log.debug("Resizing buffer '{}'\t{}", name, String.format("%.2f MB -> %.2f MB", size / 1e6, numBytes / 1e6));

		if (byteOffset > 0) {
			// Create a new buffer and copy the old data to it
			int oldBuffer = id;
			id = glGenBuffers();
			glBindBuffer(target, id);
			glBufferData(target, numBytes, usage);

			glBindBuffer(GL_COPY_READ_BUFFER, oldBuffer);
			glCopyBufferSubData(GL_COPY_READ_BUFFER, target, 0, 0, byteOffset);
			glDeleteBuffers(oldBuffer);
		} else {
			glBindBuffer(target, id);
			glBufferData(target, numBytes, usage);
		}

		size = numBytes;

		if (log.isDebugEnabled() && HdPlugin.GL_CAPS.OpenGL43) {
			GL43C.glObjectLabel(GL43C.GL_BUFFER, id, name);
			checkGLErrors();
		}
	}

	public void upload(ByteBuffer data) {
		upload(data, 0);
	}

	public void upload(ByteBuffer data, long byteOffset) {
		long numBytes = data.remaining();
		ensureCapacity(byteOffset, numBytes);
		glBufferSubData(target, byteOffset, data);
	}

	public void upload(IntBuffer data) {
		upload(data, 0);
	}

	public void upload(IntBuffer data, long byteOffset) {
		long numBytes = 4L * data.remaining();
		ensureCapacity(byteOffset, numBytes);
		glBufferSubData(target, byteOffset, data);
	}

	public void upload(FloatBuffer data) {
		upload(data, 0);
	}

	public void upload(FloatBuffer data, long byteOffset) {
		long numBytes = 4L * data.remaining();
		ensureCapacity(byteOffset, numBytes);
		glBufferSubData(target, byteOffset, data);
	}

	public void upload(GpuIntBuffer data) {
		upload(data.getBuffer());
	}

	public void upload(GpuIntBuffer data, long byteOffset) {
		upload(data.getBuffer(), byteOffset);
	}

	public void upload(GpuFloatBuffer data) {
		upload(data.getBuffer());
	}

	public void upload(GpuFloatBuffer data, long byteOffset) {
		upload(data.getBuffer(), byteOffset);
	}
}
