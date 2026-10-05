package rs117.hd.opengl.shader;

import java.io.IOException;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SCENE_OPAQUE_DEPTH;

public class SceneDepthResolveShaderProgram extends ShaderProgram {
	private final UniformTexture uniSceneDepth = addUniformTexture("sceneDepth");
	public final Uniform1i uniSampleCount = addUniform1i("sampleCount");

	protected boolean reverseZ = false;

	public SceneDepthResolveShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "depth_resolve_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "depth_resolve_frag.glsl"));
	}

	@Override
	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		super.compile(includes.copy().define("REVERSE_Z", reverseZ));
	}

	@Override
	protected void initialize() {
		uniSceneDepth.set(TEXTURE_UNIT_SCENE_OPAQUE_DEPTH);
	}

	public static class ReverseZ extends SceneDepthResolveShaderProgram {
		public ReverseZ() { reverseZ = true; }
	}
}
