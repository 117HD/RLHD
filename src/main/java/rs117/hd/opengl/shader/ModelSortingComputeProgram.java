package rs117.hd.opengl.shader;

import java.io.IOException;
import org.lwjgl.opengl.*;

import static rs117.hd.opengl.GLBinding.BINDING_TEX_TILE_HEIGHT_MAP;

public class ModelSortingComputeProgram extends ShaderProgram {
	public final int threadCount, facesPerThread;

	private final UniformTexture uniTileHeightMap = addUniformTexture("tileHeightMap");

	public ModelSortingComputeProgram(int threadCount, int facesPerThread) {
		super(t -> t.add(GL43C.GL_COMPUTE_SHADER, "comp.glsl"));
		this.threadCount = threadCount;
		this.facesPerThread = facesPerThread;
	}

	@Override
	protected void initialize() {
		uniTileHeightMap.set(BINDING_TEX_TILE_HEIGHT_MAP);
	}

	@Override
	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		super.compile(includes
			.define("THREAD_COUNT", threadCount)
			.define("FACES_PER_THREAD", facesPerThread));
	}
}
