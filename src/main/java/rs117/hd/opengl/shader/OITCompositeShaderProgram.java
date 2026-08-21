package rs117.hd.opengl.shader;
import java.io.IOException;
import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_OIT_COLOR_ACCUM;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_OIT_NET_COVERAGE;

public class OITCompositeShaderProgram extends ShaderProgram {
	private final UniformTexture uniColorAccum;
	private final UniformTexture uniNetCoverage;

	protected boolean useSampleShading;

	public OITCompositeShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "ui_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "oit_composite_frag.glsl"));

		uniColorAccum = addUniformTexture("colorAccum");
		uniNetCoverage = addUniformTexture("netCoverage");
	}
	@Override
	protected void initialize() {
		uniColorAccum.set(TEXTURE_UNIT_OIT_COLOR_ACCUM);
		uniNetCoverage.set(TEXTURE_UNIT_OIT_NET_COVERAGE);
	}

	@Override
	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		super.compile(includes.copy().define("OIT_COMPOSITE_SAMPLE_SHADING", useSampleShading));
	}

	public static class SampleShading extends OITCompositeShaderProgram {
		public SampleShading() {useSampleShading = true;}
	}
}
