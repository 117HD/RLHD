package rs117.hd.renderer.zone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import javax.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.buffer.GLTextureBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPlugin.NVIDIA_GPU;
import static rs117.hd.HdPlugin.SUPPORTS_INDIRECT_DRAW;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;
import static rs117.hd.utils.MathUtils.*;

class VAO {
	// Temp vertex format
	// pos float vec3(x, y, z)
	// uvw short vec3(u, v, w)
	// normal short vec3(nx, ny, nz)
	static final int VERT_SIZE = 28;

	// Metadata format
	// worldViewIndex int
	// dummy sceneOffset ivec2 for macOS workaround
	static final int METADATA_SIZE = 12;

	final VAOList list;
	final VBO vbo;
	final GLTextureBuffer tboF;
	int vao;
	boolean used;

	VAO(VAOList list, int size) {
		this.list = list;
		this.vbo = new VBO(size);
		this.tboF = new GLTextureBuffer("Textured Faces", GL_DYNAMIC_DRAW);
	}

	void initialize(int ebo, @Nonnull VBO vboMetadata) {
		vao = glGenVertexArrays();
		glBindVertexArray(vao);

		// The element buffer is part of VAO state
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);

		vbo.initialize(GL_DYNAMIC_DRAW);
		glBindBuffer(GL_ARRAY_BUFFER, vbo.bufId);

		// Position
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, VERT_SIZE, 0);

		// UVs
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 3, GL_HALF_FLOAT, false, VERT_SIZE, 12);

		// Normals
		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 3, GL_SHORT, false, VERT_SIZE, 18);

		// TextureFaceIdx
		glEnableVertexAttribArray(3);
		glVertexAttribIPointer(3, 1, GL_INT, VERT_SIZE, 24);

		glBindBuffer(GL_ARRAY_BUFFER, vboMetadata.bufId);

		// WorldView index (not ID)
		glEnableVertexAttribArray(6);
		glVertexAttribDivisor(6, 1);
		glVertexAttribIPointer(6, 1, GL_INT, METADATA_SIZE, 0);

		if (!NVIDIA_GPU) {
			// Workaround for incorrect implementations of disabled vertex attribs, particularly on macOS
			glEnableVertexAttribArray(7);
			glVertexAttribDivisor(7, 1);
			glVertexAttribIPointer(7, 2, GL_INT, METADATA_SIZE, 4);
		}

		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);

		tboF.initialize(VAOList.TBO_SIZE);
	}

	void destroy() {
		vbo.destroy();
		tboF.destroy();
		glDeleteVertexArrays(vao);
		vao = 0;
	}

	int[] lengths = new int[4];
	int off = 0;

	void addRange() {
		assert vbo.mapped;

		if (off > 0 && lengths[off - 1] == vbo.vb.position()) {
			return;
		}

		if (lengths.length == off) {
			int l = lengths.length << 1;
			lengths = Arrays.copyOf(lengths, l);
		}

		lengths[off] = vbo.vb.position();
		off++;
	}

	void draw(CommandBuffer cmd) {
		if(!used)
			return;

		cmd.BindVertexArray(vao);
		cmd.BindTextureUnit(GL_TEXTURE_BUFFER, tboF.getTexId(), TEXTURE_UNIT_TEXTURED_FACES);

		int start = 0;
		for (int i = 0; i < off; ++i) {
			int end = lengths[i];
			int count = end - start;

			if (GL_CAPS.OpenGL40 && SUPPORTS_INDIRECT_DRAW) {
				cmd.DrawArraysIndirect(
					GL_TRIANGLES,
					start / (VERT_SIZE / 4),
					count / (VAO.VERT_SIZE / 4),
					ZoneRenderer.indirectDrawCmdsStaging
				);
			} else {
				cmd.DrawArrays(GL_TRIANGLES, start / (VERT_SIZE / 4), count / (VAO.VERT_SIZE / 4));
			}

			start = end;
		}
	}

	void unlock() {
		list.available.add(this);
	}

	void reset() {
		off = 0;
		used = false;
	}

	@Slf4j
	@RequiredArgsConstructor
	static class VAOList {
		// this needs to be larger than the largest single model
		private static final int VAO_SIZE = (int) (4 * MiB);
		private static final int TBO_SIZE = ceil(VAO_SIZE / (3f * VERT_SIZE)) * 9 * Integer.BYTES;

		private final List<VAO> vaos = Collections.synchronizedList(new ArrayList<>());
		private final ConcurrentLinkedDeque<VAO> available = new ConcurrentLinkedDeque<>();
		private final VBO vboMetadata;
		private final int eboAlpha;
		private final Client client;

		void preAllocate(int count) {
			for (int i = 0; i < count; i++) {
				VAO newVao = new VAO(this, VAO_SIZE);
				newVao.initialize(eboAlpha, vboMetadata);
				vaos.add(newVao);
			}
			log.debug("Pre-allocated {} VAO(s)", count);
		}

		void map() {
			available.clear();
			for (VAO vao : vaos) {
				if(vao.vao == 0)
					vao.initialize(eboAlpha, vboMetadata);

				if(!vao.vbo.mapped) {
					vao.vbo.map();
					vao.tboF.map();
				}

				if(vao.vbo.mapped)
					available.add(vao);

				vao.reset();
			}
		}

		synchronized VAO get(int size) {
			assert size <= VAO_SIZE;

			// Recycles VAO if sufficient space is available, otherwise creates a new one
			// We don't re-add if there isn't enough space for the current model, to avoid checking the same VAO each time
			VAO vao;
			while ((vao = available.poll()) != null) {
				final int rem = vao.vbo.vb.remaining() * Integer.BYTES;
				if (size <= rem) {
					vao.used = true;
					return vao;
				}
			}

			vao = new VAO(this, VAO_SIZE);
			if(!client.isClientThread()) {
				vaos.add(vao);
				return null; // Render Thread cant allocate or map, so we'll add the VAO so it'll be available next time around
			}

			vao.used = true;
			vao.initialize(eboAlpha, vboMetadata);
			vao.vbo.map();
			vao.tboF.map();
			vaos.add(vao);
			log.debug("Allocated VAO {} request {}", vao.vao, size);
			return vao;
		}

		void unmap() {
			// Unmap all VAOs which have been used so they can be drawn safely
			for (VAO vao : vaos) {
				if (vao.used && vao.vbo.mapped) {
					vao.vbo.unmap();
					vao.tboF.unmap();
				}
			}
		}

		void free() {
			for (VAO vao : vaos)
				vao.destroy();
			vaos.clear();
		}

		void addRange() {
			for (VAO vao : vaos) {
				if (vao.used && vao.vbo.mapped)
					vao.addRange();
			}
		}

		void drawAll(CommandBuffer cmd) {
			// Draw all used VAOs
			for (VAO vao : vaos) {
				if(vao.used)
					vao.draw(cmd);
			}
		}
	}
}
