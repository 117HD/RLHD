package rs117.hd.opengl.shader;

import java.io.IOException;
import org.lwjgl.opengl.*;

public class ModelSortingComputeProgram extends ShaderProgram {
	public final int threadCount, facesPerThread;

	public ModelSortingComputeProgram(int threadCount, int facesPerThread) {
		this.threadCount = threadCount;
		this.facesPerThread = facesPerThread;
		setShaderTemplate(new ShaderTemplate().add(GL43C.GL_COMPUTE_SHADER, "comp.glsl"));
	}

	@Override
	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		super.compile(includes.copy()
			.define("THREAD_COUNT", threadCount)
			.define("FACES_PER_THREAD", facesPerThread));
	}
}
