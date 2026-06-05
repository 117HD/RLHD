package rs117.hd.opengl.shader;

import java.io.IOException;
import rs117.hd.renderer.zone.passes.DebugDrawPass.PrimitiveDrawType;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;

public abstract class DebugDrawShaderProgram extends ShaderProgram {
	private final PrimitiveDrawType type;

	public DebugDrawShaderProgram(PrimitiveDrawType type) {
		super(t -> t
			.add(GL_VERTEX_SHADER, "debug_draw_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "debug_draw_frag.glsl"));
		this.type = type;
	}

	@Override
	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		super.compile(includes.copy().define("PRIMITIVE_TYPE", type.ordinal()));
	}

	public static class DebugDrawCubeShaderProgram extends DebugDrawShaderProgram {
		public DebugDrawCubeShaderProgram() {
			super(PrimitiveDrawType.AABB);
		}
	}

	public static class DebugDrawSphereShaderProgram extends DebugDrawShaderProgram {
		public DebugDrawSphereShaderProgram() {
			super(PrimitiveDrawType.SPHERE);
		}
	}

	public static class DebugDrawLineShaderProgram extends DebugDrawShaderProgram {
		public DebugDrawLineShaderProgram() {
			super(PrimitiveDrawType.LINE);
		}
	}

	public static class DebugDrawTextShaderProgram extends DebugDrawShaderProgram {
		public final Uniform1f uniCharScale = addUniform1f("charScale");

		public DebugDrawTextShaderProgram() {
			super(PrimitiveDrawType.TEXT);
		}
	}
}
