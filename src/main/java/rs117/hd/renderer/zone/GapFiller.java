package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryStack;
import rs117.hd.utils.Destructible;
import rs117.hd.utils.RenderState;
import rs117.hd.utils.buffer.GLBuffer;
import rs117.hd.utils.buffer.GLTextureBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;

@Slf4j
public class GapFiller implements Destructible {
	public int glVao;
	int vertexCount;
	int faceCount;

	@Nullable
	GLBuffer vboO;
	@Nullable
	GLBuffer vboM;
	@Nullable
	GLTextureBuffer tboF;

	public boolean hasGeometry() {
		return vertexCount > 0;
	}

	public void rebuild(SceneUploader uploader, WorldViewContext viewContext, ZoneSceneContext sceneContext) {
		destroy();

		GpuIntBuffer vb = new GpuIntBuffer();
		GpuIntBuffer fb = new GpuIntBuffer();
		try {
			uploader.fillGaps(sceneContext, vb, fb);
			vertexCount = vb.position() / (Zone.VERT_SIZE >> 2);
			faceCount = fb.position() / (Zone.TEXTURE_SIZE >> 2);
			if (vertexCount == 0)
				return;

			vb.flip();
			fb.flip();

			vboO = new GLBuffer("GapFiller::VBO", GL_ARRAY_BUFFER, GL_STATIC_DRAW);
			vboO.initialize(vertexCount * Zone.VERT_SIZE);
			vboO.upload(vb);

			tboF = new GLTextureBuffer("GapFiller::TBO", GL_STATIC_DRAW);
			tboF.initialize(faceCount * Zone.TEXTURE_SIZE);
			tboF.upload(fb);

			vboM = new GLBuffer("GapFiller::Metadata", GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW);
			vboM.initialize(Zone.METADATA_SIZE);

			glVao = glGenVertexArrays();
			setupVao(glVao, vboO.id, vboM.id);
			setMetadata(viewContext);
		} finally {
			vb.destroy();
			fb.destroy();
		}
	}

	private void setupVao(int vao, int buffer, int metadata) {
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, buffer);

		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_SHORT, false, Zone.VERT_SIZE, 0);

		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 4, GL_HALF_FLOAT, false, Zone.VERT_SIZE, 8);

		glEnableVertexAttribArray(2);
		glVertexAttribPointer(2, 4, GL_SHORT, false, Zone.VERT_SIZE, 16);

		glEnableVertexAttribArray(3);
		glVertexAttribIPointer(3, 1, GL_INT, Zone.VERT_SIZE, 24);

		glBindBuffer(GL_ARRAY_BUFFER, metadata);

		glEnableVertexAttribArray(6);
		glVertexAttribDivisor(6, 1);
		glVertexAttribIPointer(6, 1, GL_INT, Zone.METADATA_SIZE, 0);

		glEnableVertexAttribArray(7);
		glVertexAttribDivisor(7, 1);
		glVertexAttribIPointer(7, 2, GL_INT, Zone.METADATA_SIZE, 4);

		checkGLErrors();

		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	private void setMetadata(WorldViewContext viewContext) {
		if (vboM == null)
			return;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer buf = stack.mallocInt(3)
				.put(viewContext.uboWorldViewStruct != null ? viewContext.uboWorldViewStruct.worldViewIdx + 1 : 0)
				.put(0)
				.put(0);
			buf.flip();
			vboM.upload(buf);
		}
	}

	public void renderBeforeScene(RenderState renderState) {
		if (glVao == 0 || vertexCount == 0)
			return;

		renderState.disable.set(GL_DEPTH_TEST);
		renderState.depthMask.set(false);
		renderState.vao.setVao(glVao);
		renderState.apply();

		glActiveTexture(TEXTURE_UNIT_TEXTURED_FACES);
		glBindTexture(GL_TEXTURE_BUFFER, tboF.getTexId());
		glDrawArrays(GL_TRIANGLES, 0, vertexCount);
		glBindTexture(GL_TEXTURE_BUFFER, 0);

		renderState.enable.set(GL_DEPTH_TEST);
		renderState.depthMask.set(true);
		renderState.apply();
	}

	@Override
	public void destroy() {
		if (vboO != null) {
			vboO.destroy();
			vboO = null;
		}

		if (vboM != null) {
			vboM.destroy();
			vboM = null;
		}

		if (tboF != null) {
			tboF.destroy();
			tboF = null;
		}

		if (glVao != 0) {
			glDeleteVertexArrays(glVao);
			glVao = 0;
		}

		vertexCount = 0;
		faceCount = 0;
	}
}
