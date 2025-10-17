package rs117.hd.renderer.zone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.opengl.uniforms.UBOGlobal;
import rs117.hd.utils.Mat4;

import static org.lwjgl.opengl.GL33C.*;

class VAO {
	// Zone vertex format
	// pos float vec3(x, y, z)
	// uvw short vec3(u, v, w)
	// normal short vec3(nx, ny, nz)
	// alphaBiasHsl int
	// materialData int
	// terrainData int
	static final int VERT_SIZE = 36;

	final VBO vbo;
	int vao;

	VAO(int size) {
		vbo = new VBO(size);
	}

	void initialize() {
		vao = glGenVertexArrays();
		glBindVertexArray(vao);

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

		// Alpha, bias & HSL
		glEnableVertexAttribArray(3);
		glVertexAttribIPointer(3, 1, GL_INT, VERT_SIZE, 24);

		// Material data
		glEnableVertexAttribArray(4);
		glVertexAttribIPointer(4, 1, GL_INT, VERT_SIZE, 28);

		// Terrain data
		glEnableVertexAttribArray(5);
		glVertexAttribIPointer(5, 1, GL_INT, VERT_SIZE, 32);

		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);
	}

	void destroy() {
		vbo.destroy();
		glDeleteVertexArrays(vao);
		vao = 0;
	}

	int[] lengths = new int[4];
	Projection[] projs = new Projection[4];
	Scene[] scenes = new Scene[4];
	int off = 0;

	void addRange(Projection projection, Scene scene) {
		assert vbo.mapped;

		if (off > 0 && lengths[off - 1] == vbo.vb.position()) {
			return;
		}

		if (lengths.length == off) {
			int l = lengths.length << 1;
			lengths = Arrays.copyOf(lengths, l);
			projs = Arrays.copyOf(projs, l);
			scenes = Arrays.copyOf(scenes, l);
		}

		lengths[off] = vbo.vb.position();
		projs[off] = projection;
		scenes[off] = scene;
		off++;
	}

	void draw(UBOGlobal ubo) {
		assert !vbo.mapped;

		int start = 0;
		for (int i = 0; i < off; ++i) {
			int end = lengths[i];
			Projection p = projs[i];
			Scene scene = scenes[i];

			int count = end - start;

			ubo.entityProjectionMatrix.set(p instanceof FloatProjection ? ((FloatProjection) p).getProjection() : Mat4.identity());
			ubo.upload();
//			glUniformMatrix4fv(
//				uniEntityProj,
//				false,
//				p instanceof FloatProjection ? ((FloatProjection) p).getProjection() : Mat4.identity()
//			);
//			glUniform4i(
//				uniEntityTint,
//				scene.getOverrideHue(),
//				scene.getOverrideSaturation(),
//				scene.getOverrideLuminance(),
//				scene.getOverrideAmount()
//			);
			glBindVertexArray(vao);
			glDrawArrays(GL_TRIANGLES, start / (VERT_SIZE / 4), count / (VAO.VERT_SIZE / 4));

			start = end;
		}
	}

	void reset() {
		Arrays.fill(projs, 0, off, null);
		Arrays.fill(scenes, 0, off, null);
		off = 0;
	}

	@Slf4j
	static class VAOList {
		// this needs to be larger than the largest single model
		private static final int VAO_SIZE = 4 * 1024 * 1024;

		private int curIdx;
		private final List<VAO> vaos = new ArrayList<>();

		VAO get(int size) {
			assert size <= VAO_SIZE;

			while (curIdx < vaos.size()) {
				VAO vao = vaos.get(curIdx);
				if (!vao.vbo.mapped) {
					vao.vbo.map();
				}

				int rem = vao.vbo.vb.remaining() * Integer.BYTES;
				if (size <= rem) {
					return vao;
				}

				curIdx++;
			}

			VAO vao = new VAO(VAO_SIZE);
			vao.initialize();
			vao.vbo.map();
			vaos.add(vao);
			log.debug("Allocated VAO {} request {}", vao.vao, size);
			return vao;
		}

		List<VAO> unmap() {
			int sz = 0;
			for (VAO vao : vaos) {
				if (vao.vbo.mapped) {
					++sz;
					vao.vbo.unmap();
				}
			}
			curIdx = 0;
			return vaos.subList(0, sz);
		}

		void free() {
			for (VAO vao : vaos) {
				vao.destroy();
			}
			vaos.clear();
			curIdx = 0;
		}

		void addRange(Projection projection, Scene scene) {
			for (int i = 0; i <= curIdx && i < vaos.size(); ++i) {
				VAO vao = vaos.get(i);
				if (vao.vbo.mapped) {
					vao.addRange(projection, scene);
				}
			}
		}

		void debug() {
			log.debug("{} vaos allocated", vaos.size());
			for (VAO vao : vaos) {
				log.debug(
					"vao {} mapped: {} num ranges: {} length: {}",
					vao,
					vao.vbo.mapped,
					vao.off,
					vao.vbo.mapped ? vao.vbo.vb.position() : -1
				);
				if (vao.off > 1) {
					for (int i = 0; i < vao.off; ++i) {
						log.debug("  {} {} {}", vao.lengths[i], vao.projs[i], vao.scenes[i]);
					}
				}
			}
		}
	}
}
