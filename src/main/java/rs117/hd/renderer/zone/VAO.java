package rs117.hd.renderer.zone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

	final VBO vbo;
	final GLTextureBuffer tboF;
	int vao;
	int vboMetadata;
	boolean used;
	int renderThreadId;

	VAO(int size) {
		vbo = new VBO(size);
		tboF = new GLTextureBuffer("Textured Faces", GL_DYNAMIC_DRAW);
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

		bindMetadata(vboMetadata);

		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);

		tboF.initialize(VAOList.TBO_SIZE);
	}

	void bindMetadata(@Nonnull VBO vboMetadata) {
		glBindVertexArray(vao);
		this.vboMetadata = vboMetadata.bufId;
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
	}

	void destroy() {
		vbo.destroy();
		tboF.destroy();
		glDeleteVertexArrays(vao);
		vao = 0;
	}

	int[] lengths = new int[4];
	Scene[] scenes = new Scene[4];
	int off = 0;

	void addRange(Scene scene) {
		assert vbo.mapped;

		if (off > 0 && lengths[off - 1] == vbo.vb.position()) {
			return;
		}

		if (lengths.length == off) {
			int l = lengths.length << 1;
			lengths = Arrays.copyOf(lengths, l);
			scenes = Arrays.copyOf(scenes, l);
		}

		lengths[off] = vbo.vb.position();
		scenes[off] = scene;
		off++;
	}

	void draw(CommandBuffer cmd) {
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

	void reset() {
		Arrays.fill(scenes, 0, off, null);
		off = 0;
		used = false;
		renderThreadId = -1;
	}

	@Slf4j
	@RequiredArgsConstructor
	static class VAOList {
		// this needs to be larger than the largest single model
		private static final int VAO_SIZE = (int) (4 * MiB);
		private static final int TBO_SIZE = ceil(VAO_SIZE / (3f * VERT_SIZE)) * 9 * Integer.BYTES;

		private final List<VAO> vaos = new ArrayList<>();
		private final VBO vboMetadata;
		private final int eboAlpha;

		synchronized void map() {
			for (VAO vao : vaos) {
				if(vao.vao == 0)
					vao.initialize(eboAlpha, vboMetadata);

				if(!vao.vbo.mapped) {
					vao.vbo.map();
					vao.tboF.map();
				}

				vao.reset();
			}
		}

		synchronized VAO get(int size, int renderThreadId) {
			assert size <= VAO_SIZE;

			for(VAO vao : vaos) {
				if (!vao.vbo.mapped || (vao.used && vao.renderThreadId != renderThreadId))
					continue; // only use VAOs which have already been mapped

				int rem = vao.vbo.vb.remaining() * Integer.BYTES;
				if (size <= rem) {
					vao.used = true;
					vao.renderThreadId = renderThreadId;
					return vao;
				}
			}

			VAO vao = new VAO(VAO_SIZE);
			vaos.add(new VAO(VAO_SIZE));

			if(renderThreadId >= 0)
				return null; // Render Thread cant allocate or map, so we'll add the VAO so it'll be available next time around

			vao.used = true;
			vao.initialize(eboAlpha, vboMetadata);
			vao.vbo.map();
			vao.tboF.map();
			log.debug("Allocated VAO {} request {}", vao.vao, size);
			return vao;
		}

		synchronized void unmap() {
			// Unmap all VAOs which have been used so they can be drawn safely
			for (VAO vao : vaos) {
				if (vao.used) {
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

		void addRange(Scene scene) {
			for (VAO vao : vaos) {
				if (vao.used)
					vao.addRange(scene);
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
