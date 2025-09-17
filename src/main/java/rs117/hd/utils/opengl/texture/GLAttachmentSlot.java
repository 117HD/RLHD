package rs117.hd.utils.opengl.texture;

import static org.lwjgl.opengl.GL11.GL_BACK_LEFT;
import static org.lwjgl.opengl.GL11.GL_BACK_RIGHT;
import static org.lwjgl.opengl.GL11.GL_FRONT_LEFT;
import static org.lwjgl.opengl.GL11.GL_FRONT_RIGHT;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT1;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT10;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT11;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT12;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT13;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT14;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT15;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT16;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT17;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT18;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT19;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT2;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT20;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT21;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT22;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT23;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT24;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT25;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT26;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT27;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT28;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT29;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT3;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT30;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT31;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT4;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT5;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT6;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT7;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT8;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT9;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_STENCIL_ATTACHMENT;

public enum GLAttachmentSlot {
	COLOR0(GL_COLOR_ATTACHMENT0),
	COLOR1(GL_COLOR_ATTACHMENT1),
	COLOR2(GL_COLOR_ATTACHMENT2),
	COLOR3(GL_COLOR_ATTACHMENT3),
	COLOR4(GL_COLOR_ATTACHMENT4),
	COLOR5(GL_COLOR_ATTACHMENT5),
	COLOR6(GL_COLOR_ATTACHMENT6),
	COLOR7(GL_COLOR_ATTACHMENT7),
	COLOR8(GL_COLOR_ATTACHMENT8),
	COLOR9(GL_COLOR_ATTACHMENT9),
	COLOR10(GL_COLOR_ATTACHMENT10),
	COLOR11(GL_COLOR_ATTACHMENT11),
	COLOR12(GL_COLOR_ATTACHMENT12),
	COLOR13(GL_COLOR_ATTACHMENT13),
	COLOR14(GL_COLOR_ATTACHMENT14),
	COLOR15(GL_COLOR_ATTACHMENT15),
	COLOR16(GL_COLOR_ATTACHMENT16),
	COLOR17(GL_COLOR_ATTACHMENT17),
	COLOR18(GL_COLOR_ATTACHMENT18),
	COLOR19(GL_COLOR_ATTACHMENT19),
	COLOR20(GL_COLOR_ATTACHMENT20),
	COLOR21(GL_COLOR_ATTACHMENT21),
	COLOR22(GL_COLOR_ATTACHMENT22),
	COLOR23(GL_COLOR_ATTACHMENT23),
	COLOR24(GL_COLOR_ATTACHMENT24),
	COLOR25(GL_COLOR_ATTACHMENT25),
	COLOR26(GL_COLOR_ATTACHMENT26),
	COLOR27(GL_COLOR_ATTACHMENT27),
	COLOR28(GL_COLOR_ATTACHMENT28),
	COLOR29(GL_COLOR_ATTACHMENT29),
	COLOR30(GL_COLOR_ATTACHMENT30),
	COLOR31(GL_COLOR_ATTACHMENT31),

	DEPTH(GL_DEPTH_ATTACHMENT, true),
	STENCIL(GL_STENCIL_ATTACHMENT),
	DEPTH_STENCIL(GL_DEPTH_STENCIL_ATTACHMENT),

	FRONT_LEFT(GL_FRONT_LEFT, true),
	FRONT_RIGHT(GL_FRONT_RIGHT, true),
	BACK_LEFT(GL_BACK_LEFT, true),
	BACK_RIGHT(GL_BACK_RIGHT, true);

	public final int glEnum;
	public final boolean defaultFrameBufferSupport;

	GLAttachmentSlot(int glEnum) {
		this.glEnum = glEnum;
		this.defaultFrameBufferSupport = false;
	}

	GLAttachmentSlot(int glEnum, boolean defaultFrameBufferSupport) {
		this.glEnum = glEnum;
		this.defaultFrameBufferSupport = defaultFrameBufferSupport;
	}

	public boolean isDepth() {
		return this == DEPTH || this == STENCIL || this == DEPTH_STENCIL;
	}

	public static GLAttachmentSlot fromGLEnum(int glEnum) {
		for (GLAttachmentSlot slot : values()) {
			if (slot.glEnum == glEnum) {
				return slot;
			}
		}
		return GLAttachmentSlot.COLOR0; // Default to Zero
	}
}
