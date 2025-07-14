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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.utils.HDUtils;

import static org.lwjgl.opengl.GL31C.*;
import static org.lwjgl.opengl.GL43C.*;
import static rs117.hd.HdPlugin.checkGLErrors;

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
		ensureCapacity(Math.max(1, initialCapacity));
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

	public void ensureCapacity(int offset, long numBytes) {
		long size = 4L * (offset + numBytes);
		if (size <= this.size) {
			glBindBuffer(target, id);
			return;
		}

		size = HDUtils.ceilPow2(size);
		if (log.isTraceEnabled())
			log.trace("Buffer resize: {} {}", this, String.format("%.2f MB -> %.2f MB", this.size / 1e6, size / 1e6));

		if (offset > 0) {
			// Create a new buffer and copy the old data to it
			int oldBuffer = id;
			id = glGenBuffers();
			glBindBuffer(target, id);
			glBufferData(target, size, usage);

			glBindBuffer(GL_COPY_READ_BUFFER, oldBuffer);
			glCopyBufferSubData(GL_COPY_READ_BUFFER, target, 0, 0, offset * 4L);
			glDeleteBuffers(oldBuffer);
		} else {
			glBindBuffer(target, id);
			glBufferData(target, size, usage);
		}

		this.size = size;

		if (HdPlugin.glCaps.OpenGL43 && log.isDebugEnabled()) {
			glObjectLabel(GL_BUFFER, id, name);
			checkGLErrors();
		}
	}
}
