package rs117.hd.opengl.buffer;

import java.nio.ByteBuffer;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.BufferUtils;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL30.GL_R32I;
import static org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER;
import static org.lwjgl.opengl.GL31.glTexBuffer;

@Slf4j
public class TextureStructuredBuffer extends StructuredBuffer<GLBuffer> {
	private int textureId;
	private int currentCapacity;

	public TextureStructuredBuffer() {
		super(GL_TEXTURE_BUFFER, GL_DYNAMIC_DRAW);
		this.currentCapacity = 0;
	}

	@Override
	public void initialize() {
		super.initialize();

		// Create the texture buffer
		textureId = glGenTextures();
		glBindTexture(GL_TEXTURE_BUFFER, textureId);
		glTexBuffer(GL_TEXTURE_BUFFER, GL_R32I, glBuffer.id);
		glBindTexture(GL_TEXTURE_BUFFER, 0);
	}

	@Override
	protected void preUpload() {
		if (size > currentCapacity) {
			resize(size * 2);
		}
	}

	public void resize(int newSize) {
		if (newSize <= currentCapacity) return;

		log.info("{} resizing from {} -> {}", glBuffer.name, currentCapacity, newSize);

		// Create a new larger buffer
		ByteBuffer newData = BufferUtils.createByteBuffer(newSize);
		if (data != null) {
			data.position(0);
			newData.put(data);
			newData.flip();
		}

		glBindBuffer(glBuffer.target, glBuffer.id);
		glBufferData(glBuffer.target, newSize, GL_DYNAMIC_DRAW);
		glBindBuffer(glBuffer.target, 0);

		glBindTexture(GL_TEXTURE_BUFFER, textureId);
		glTexBuffer(GL_TEXTURE_BUFFER, GL_R32I, glBuffer.id);
		glBindTexture(GL_TEXTURE_BUFFER, 0);

		data = newData;
		dataInt = data.asIntBuffer();
		dataFloat = data.asFloatBuffer();

		currentCapacity = newSize;
	}

	@Override
	public void destroy() {
		super.destroy();

		if (textureId != 0) {
			glDeleteTextures(textureId);
			textureId = 0;
		}

		currentCapacity = 0;
	}
}

