package rs117.hd;

import java.util.ArrayList;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.shader.ShaderIncludes;

import static rs117.hd.HdPlugin.APPLE;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPlugin.INTEL_GPU;
import static rs117.hd.HdPlugin.NVIDIA_GPU;
import static rs117.hd.utils.buffer.GLBuffer.DEBUG_MAC_OS;

@Slf4j
public final class HdPluginFeatures {
	private static final ArrayList<FeatureCompat> FEATURES = new ArrayList<>();

	public static final FeatureCompat INDIRECT_DRAW =
		new FeatureCompat(
			"INDIRECT_DRAW",
			(config) -> config.indirectDraw().get(NVIDIA_GPU && !APPLE)
		);

	public static final FeatureCompat STORAGE_BUFFERS =
		new FeatureCompat(
			"STORAGE_BUFFERS",
			(config) ->  GL_CAPS.GL_ARB_buffer_storage && !DEBUG_MAC_OS && config.storageBuffers().get(!INTEL_GPU)
		);

	public static final FeatureCompat DRAW_INDIRECT =
		new FeatureCompat(
			"DRAW_ARRAYS_INDIRECT",
			(config) -> GL_CAPS.OpenGL40 && HdPluginFeatures.INDIRECT_DRAW.isSupported()
		);

	public static final FeatureCompat MULTI_DRAW_INDIRECT =
		new FeatureCompat(
			"MULTI_DRAW_INDIRECT",
			(config) -> GL_CAPS.OpenGL43 && HdPluginFeatures.INDIRECT_DRAW.isSupported()
		);

	public static final FeatureCompat MAP_BUFFER_RANGE =
		new FeatureCompat(
			"MAP_BUFFER_RANGE",
			(config) -> GL_CAPS.GL_ARB_map_buffer_range
		);

	public static final FeatureCompat TEXTURE_STORAGE =
		new FeatureCompat(
			"TEXTURE_STORAGE",
			(config) -> GL_CAPS.GL_ARB_texture_storage
		);

	public static final FeatureCompat TEXTURE_STORAGE_3D =
		new FeatureCompat(
			"TEXTURE_STORAGE_3D",
			(config) -> GL_CAPS.GL_ARB_texture_storage && GL_CAPS.glTextureStorage3D != 0
		);

	public static final FeatureCompat PROVOKING_VERTEX =
		new FeatureCompat(
			"PROVOKING_VERTEX",
			true,
			(config) -> GL_CAPS.GL_ARB_provoking_vertex
		);

	public static final FeatureCompat SHADER_IMAGE_STORE =
		new FeatureCompat(
			"SHADER_IMAGE_STORE",
			true,
			(config) -> GL_CAPS.GL_ARB_shader_image_load_store
		);

	public static void addShaderIncludes(ShaderIncludes includes) {
		for (int i = 0; i < FEATURES.size(); i++) {
			final FeatureCompat feature = FEATURES.get(i);
			if(feature.shaderDefine)
				includes.define(feature.name + "_SUPPORT", feature.supported);
		}
	}

	public static void evaluate(HdPluginConfig config) {
		int maxNameLen = 0;
		for(int i = 0; i < FEATURES.size(); i++)
			maxNameLen = Math.max(maxNameLen, FEATURES.get(i).name.length());
		maxNameLen++;

		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < FEATURES.size(); i++) {
			final FeatureCompat feature = FEATURES.get(i);
			feature.supported = feature.check.isSupported(config);
			if(i > 0)
				sb.append("\n");

			sb.append(" * ").append(feature.name);

			int padding = Math.max(0, maxNameLen - feature.name.length());
			if (padding > 0)
				sb.append(" ".repeat(padding));

			sb.append(": ").append(feature.supported);
		}
		log.info("Features:\n{}", sb);
	}

	public static final class FeatureCompat {
		@Getter
		private boolean supported;

		public final String name;
		public final boolean shaderDefine;
		public final CheckFunction check;

		private FeatureCompat(String name, boolean shaderDefine, CheckFunction check) {
			FEATURES.add(this);
			this.name = name;
			this.shaderDefine = shaderDefine;
			this.check = check;
		}

		private FeatureCompat(String name, CheckFunction check) {
			this(name, false, check);
		}

		@FunctionalInterface
		public interface CheckFunction { boolean isSupported(HdPluginConfig config); }
	}
}
