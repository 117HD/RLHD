package rs117.hd.overlays;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.lwjgl.BufferUtils;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderIncludes;
import rs117.hd.opengl.shader.ShaderProgram;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.overlays.ItemIconModel.FLOATS_PER_VERTEX;
import static rs117.hd.overlays.ItemIconModel.ICON_HEIGHT;
import static rs117.hd.overlays.ItemIconModel.ICON_WIDTH;

/**
 * Draws item icons from their models on the GPU, into layers of an array texture, using only the texture unit it's given.
 */
public class ItemIconRenderer {
	private static final int MAX_SAMPLES = 16;
	private static final int TEXTURE_SIZE = 128;
	private static final int MAX_TEXTURES = 15;
	private static final int[] VERTEX_ATTRIBUTE_SIZES = { 2, 1, 3, 4 };

	@RequiredArgsConstructor
	public static class Layer {
		final ItemIconModel model;
		final int border;
		// The game's icon of it without outline, which see-through textures are only drawn where the game drew them, or null
		@Nullable
		final int[] gameIcon;
		// Drawn smaller and moved within the icon, in the game's pixels
		final float scale, shiftX, shiftY;
	}

	private static class ModelShader extends ShaderProgram {
		final UniformTexture textures = addUniformTexture("textures");
		final Uniform1f brightness = addUniform1f("brightness");
		final Uniform1i iconRow = addUniform1i("iconRow");
		final Uniform1f margin = addUniform1f("margin");
		final Uniform3f placement = addUniform3f("placement");

		ModelShader() {
			super(t -> t
				.add(GL_VERTEX_SHADER, "item_icon_model_vert.glsl")
				.add(GL_FRAGMENT_SHADER, "item_icon_model_frag.glsl"));
		}
	}

	private static class ResolveShader extends ShaderProgram {
		final UniformTexture samples = addUniformTexture("samples");
		final Uniform1i layer = addUniform1i("layer");
		final Uniform1i sampleCount = addUniform1i("sampleCount");

		ResolveShader() {
			super(t -> t
				.add(GL_VERTEX_SHADER, "item_icon_pass_vert.glsl")
				.add(GL_FRAGMENT_SHADER, "item_icon_resolve_frag.glsl"));
		}
	}

	private static class FinishShader extends ShaderProgram {
		final UniformTexture layers = addUniformTexture("layers");
		final Uniform1i layerCount = addUniform1i("layerCount");
		final Uniform2i borders = addUniform2i("borders");
		final Uniform2f scale = addUniform2f("scale");

		FinishShader() {
			super(t -> t
				.add(GL_VERTEX_SHADER, "item_icon_pass_vert.glsl")
				.add(GL_FRAGMENT_SHADER, "item_icon_finish_frag.glsl"));
		}
	}

	private final int textureUnit;
	private final int margin;
	private final ModelShader modelShader = new ModelShader();
	private final ResolveShader resolveShader = new ResolveShader();
	private final FinishShader finishShader = new FinishShader();

	private int fbo;
	private int vao;
	private int vbo;
	private int vboCapacity;
	private int emptyVao;
	private int texSamples;
	private int texLayers;
	private int texTextures;
	private int samples;
	private int width;
	private int height;
	private FloatBuffer vertices = BufferUtils.createFloatBuffer(0);
	private final int[] iconPixels = new int[ICON_WIDTH * ICON_HEIGHT];
	private final int[] viewport = new int[4];
	private final ByteBuffer colorMask = BufferUtils.createByteBuffer(16);
	private static final int[] BOTH_ATTACHMENTS = { GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1 };

	public ItemIconRenderer(int textureUnit, int margin) {
		this.textureUnit = textureUnit;
		this.margin = margin;
	}

	public void startUp(ShaderIncludes includes) throws ShaderException, IOException {
		compile(includes);

		samples = Math.min(MAX_SAMPLES, Math.min(glGetInteger(GL_MAX_COLOR_TEXTURE_SAMPLES), glGetInteger(GL_MAX_SAMPLES)));
		fbo = glGenFramebuffers();
		emptyVao = glGenVertexArrays();
		vao = glGenVertexArrays();
		vbo = glGenBuffers();
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		int offset = 0;
		for (int i = 0; i < VERTEX_ATTRIBUTE_SIZES.length; i++) {
			glVertexAttribPointer(i, VERTEX_ATTRIBUTE_SIZES[i], GL_FLOAT, false, FLOATS_PER_VERTEX * Float.BYTES, (long) offset * Float.BYTES);
			glEnableVertexAttribArray(i);
			offset += VERTEX_ATTRIBUTE_SIZES[i];
		}
		vboCapacity = 0;

		texTextures = glGenTextures();
		glActiveTexture(textureUnit);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texTextures);
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, TEXTURE_SIZE, TEXTURE_SIZE, MAX_TEXTURES + 1, 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
		setFilter(GL_TEXTURE_2D_ARRAY, GL_NEAREST);
		width = height = 0;
	}

	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		modelShader.compile(includes);
		resolveShader.compile(includes);
		finishShader.compile(includes);
	}

	public void shutDown() {
		modelShader.destroy();
		resolveShader.destroy();
		finishShader.destroy();
		glDeleteFramebuffers(fbo);
		glDeleteVertexArrays(vao);
		glDeleteVertexArrays(emptyVao);
		glDeleteBuffers(vbo);
		glDeleteTextures(texSamples);
		glDeleteTextures(texLayers);
		glDeleteTextures(texTextures);
		fbo = vao = emptyVao = vbo = texSamples = texLayers = texTextures = 0;
		width = height = 0;
	}

	/**
	 * Draws an icon into a layer of the given array texture, and how near each of its pixels is to the icon into a channel
	 * of another layer. The array texture is unbound from the texture unit afterwards. Leaves GL state as it found it,
	 * apart from the active texture unit, the program, the vertex array, the array buffer and blending.
	 */
	public boolean draw(List<Layer> layers, int texture, int iconLayer, int surroundingsLayer, int surroundingsChannel, int cellWidth, int cellHeight, float brightness) {
		if (layers.isEmpty() || layers.size() > 2)
			return false;
		for (var layer : layers)
			if (!canDraw(layer.model))
				return false;

		int framebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		glGetIntegerv(GL_VIEWPORT, viewport);
		glGetBooleanv(GL_COLOR_WRITEMASK, colorMask);
		boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
		boolean cullFace = glIsEnabled(GL_CULL_FACE);
		boolean scissorTest = glIsEnabled(GL_SCISSOR_TEST);
		boolean multisample = glIsEnabled(GL_MULTISAMPLE);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glDisable(GL_SCISSOR_TEST);
		glEnable(GL_MULTISAMPLE);
		glColorMask(true, true, true, true);

		if (cellWidth != width || cellHeight != height)
			resize(cellWidth, cellHeight);
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glViewport(0, 0, width, height);
		glActiveTexture(textureUnit);

		// The model of each layer, sampled. The game's icons are kept in the last layer of textures.
		glBindTexture(GL_TEXTURE_2D_ARRAY, texTextures);
		for (int i = 0; i < layers.size(); i++) {
			var layer = layers.get(i);
			if (layer.gameIcon != null) {
				for (int p = 0; p < iconPixels.length; p++)
					iconPixels[p] = layer.gameIcon[p] == 0 ? 0 : -1;
				glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, i * ICON_HEIGHT, MAX_TEXTURES, ICON_WIDTH, ICON_HEIGHT, 1, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, iconPixels);
			}
		}
		modelShader.use();
		modelShader.textures.set(textureUnit);
		modelShader.brightness.set(brightness);
		modelShader.margin.set(margin);
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE);
		for (int i = 0; i < layers.size(); i++) {
			var layer = layers.get(i);
			var textures = layer.model.textures;
			for (int t = 0; t < textures.size() && t < MAX_TEXTURES; t++) {
				int[] pixels = textures.get(t);
				int size = (int) Math.sqrt(pixels.length);
				glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, t, size, size, 1, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
			}
			float[] triangles = new float[layer.model.vertexCount() * FLOATS_PER_VERTEX];
			layer.model.putTriangles(triangles, 0);
			if (triangles.length > vboCapacity) {
				vboCapacity = Math.max(triangles.length, vboCapacity * 2);
				vertices = BufferUtils.createFloatBuffer(vboCapacity);
				glBufferData(GL_ARRAY_BUFFER, (long) vboCapacity * Float.BYTES, GL_STREAM_DRAW);
			}
			vertices.clear();
			glBufferSubData(GL_ARRAY_BUFFER, 0, vertices.put(triangles).flip());

			glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texSamples, 0, i);
			glDrawBuffers(GL_COLOR_ATTACHMENT0);
			glClearColor(0, 0, 0, 0);
			glClear(GL_COLOR_BUFFER_BIT);
			modelShader.iconRow.set(layer.gameIcon == null ? -1 : i * ICON_HEIGHT);
			modelShader.placement.set(layer.scale, layer.shiftX, layer.shiftY);
			glDrawArrays(GL_TRIANGLES, 0, layer.model.vertexCount());
		}
		glDisable(GL_BLEND);

		// Their samples as coverage and color
		glBindTexture(GL_TEXTURE_2D_MULTISAMPLE_ARRAY, texSamples);
		glBindVertexArray(emptyVao);
		resolveShader.use();
		resolveShader.samples.set(textureUnit);
		resolveShader.sampleCount.set(samples);
		for (int i = 0; i < layers.size(); i++) {
			glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texLayers, 0, i);
			resolveShader.layer.set(i);
			glDrawArrays(GL_TRIANGLES, 0, 3);
		}
		glBindTexture(GL_TEXTURE_2D_MULTISAMPLE_ARRAY, 0);

		// Put together with outlines into the icon texture
		glBindTexture(GL_TEXTURE_2D_ARRAY, texLayers);
		glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texture, 0, iconLayer);
		glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, texture, 0, surroundingsLayer);
		glDrawBuffers(BOTH_ATTACHMENTS);
		glColorMaski(1, surroundingsChannel == 0, surroundingsChannel == 1, surroundingsChannel == 2, surroundingsChannel == 3);
		finishShader.use();
		finishShader.layers.set(textureUnit);
		finishShader.layerCount.set(layers.size());
		finishShader.borders.set(layers.get(0).border, layers.size() > 1 ? layers.get(1).border : 0);
		finishShader.scale.set((float) width / (ICON_WIDTH + 2 * margin), (float) height / (ICON_HEIGHT + 2 * margin));
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glColorMaski(1, true, true, true, true);
		glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, 0, 0, 0);
		glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, 0, 0, 0);
		glDrawBuffers(GL_COLOR_ATTACHMENT0);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);

		glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
		glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
		glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
		if (!multisample)
			glDisable(GL_MULTISAMPLE);
		if (depthTest)
			glEnable(GL_DEPTH_TEST);
		if (cullFace)
			glEnable(GL_CULL_FACE);
		if (scissorTest)
			glEnable(GL_SCISSOR_TEST);
		return true;
	}

	public static boolean canDraw(ItemIconModel model) {
		return model.isDrawable() && model.textures.size() <= MAX_TEXTURES;
	}

	private void resize(int cellWidth, int cellHeight) {
		width = cellWidth;
		height = cellHeight;
		glActiveTexture(textureUnit);
		glDeleteTextures(texSamples);
		texSamples = glGenTextures();
		glBindTexture(GL_TEXTURE_2D_MULTISAMPLE_ARRAY, texSamples);
		glTexImage3DMultisample(GL_TEXTURE_2D_MULTISAMPLE_ARRAY, samples, GL_RGBA8, width, height, 2, true);
		// Which can be more than asked for
		samples = glGetTexLevelParameteri(GL_TEXTURE_2D_MULTISAMPLE_ARRAY, 0, GL_TEXTURE_SAMPLES);
		glBindTexture(GL_TEXTURE_2D_MULTISAMPLE_ARRAY, 0);

		glDeleteTextures(texLayers);
		texLayers = glGenTextures();
		glBindTexture(GL_TEXTURE_2D_ARRAY, texLayers);
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, width, height, 2, 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
		setFilter(GL_TEXTURE_2D_ARRAY, GL_NEAREST);
	}

	private static void setFilter(int target, int filter) {
		glTexParameteri(target, GL_TEXTURE_MIN_FILTER, filter);
		glTexParameteri(target, GL_TEXTURE_MAG_FILTER, filter);
		glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	}
}
