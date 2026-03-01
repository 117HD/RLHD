package rs117.hd.opengl;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static org.lwjgl.opengl.GL11.GL_BYTE;
import static org.lwjgl.opengl.GL11.GL_DOUBLE;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_INT;
import static org.lwjgl.opengl.GL11.GL_SHORT;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_SHORT;
import static org.lwjgl.opengl.GL30.GL_HALF_FLOAT;

@Slf4j
public class GLVertexLayout {
	public static final int MAX_ATTRIBUTES = 12;
	public static final String[] ARRAY_FIELD_NAMES = Arrays.stream(ArrayField.values()).map(ArrayField::name).toArray(String[]::new);

	@Getter
	private final String name;
	@Getter
	private final Attribute[] attributes = new Attribute[MAX_ATTRIBUTES];
	@Getter
	private int version;
	private int editIdx;

	public GLVertexLayout(String name) {
		this.name = name;
		for(int i = 0; i < MAX_ATTRIBUTES; i++)
			attributes[i] = new Attribute();
	}

	public GLVertexLayout edit(ArrayField field) {
		assert field != ArrayField.ELEMENT_BUFFER : "Element buffer cannot be edited";
		editIdx = field.field;
		return this;
	}

	public GLVertexLayout enabled() {
		attributes[editIdx].isEnabled = true;
		return this;
	}

	public GLVertexLayout disabled() {
		attributes[editIdx].isEnabled = false;
		return this;
	}

	public GLVertexLayout normalized(boolean isNormalized) {
		attributes[editIdx].isNormalized = isNormalized;
		return this;
	}

	public GLVertexLayout asFloat() {
		attributes[editIdx].isInteger = false;
		return this;
	}

	public GLVertexLayout asInteger() {
		attributes[editIdx].isInteger = true;
		return this;
	}

	public GLVertexLayout component(ComponentType component) {
		attributes[editIdx].component = component;
		return this;
	}

	public GLVertexLayout format(FormatType format) {
		attributes[editIdx].format = format;
		return this;
	}

	public GLVertexLayout offset(long offset) {
		attributes[editIdx].offset = offset;
		return this;
	}

	public GLVertexLayout stride(int stride) {
		attributes[editIdx].stride = stride;
		return this;
	}

	public GLVertexLayout divisor(int divisor) {
		attributes[editIdx].divisor = divisor;
		return this;
	}

	public GLVertexLayout finish() {
		editIdx = -1;
		version++;
		log.debug("{}", this);
		return this;
	}

	public String toString() {
		StringBuilder str = new StringBuilder();
		str.append("\nGLVertexLayout - ").append(name).append(" - Version: ").append(version).append("\n");
		for(int i = 0; i < MAX_ATTRIBUTES; i++) {
			Attribute attr = attributes[i];
			str.append("  * ARRAY_FIELD_").append(i).append(": ");
			if(attr.isEnabled) {
				str.append("ENABLED, isInteger: ")
					.append(attr.isInteger)
					.append(", isNormalized: ")
					.append(attr.isNormalized)
					.append(", component: ")
					.append(attr.component)
					.append(", format: ")
					.append(attr.format)
					.append(", stride: ")
					.append(attr.stride)
					.append(", divisor: ")
					.append(attr.divisor)
					.append(", offset: ")
					.append(attr.offset)
					.append("\n");
			} else {
				str.append("DISABLED\n");
			}
		}
		return str.toString();
	}

	@RequiredArgsConstructor
	public enum ArrayField {
		ELEMENT_BUFFER(-1),
		VERTEX_FIELD_0(0),
		VERTEX_FIELD_1(1),
		VERTEX_FIELD_2(2),
		VERTEX_FIELD_3(3),
		VERTEX_FIELD_4(4),
		VERTEX_FIELD_5(5),
		VERTEX_FIELD_6(6),
		VERTEX_FIELD_7(7),
		VERTEX_FIELD_8(8),
		VERTEX_FIELD_9(9),
		VERTEX_FIELD_10(10),
		VERTEX_FIELD_11(11);

		public final int field;
	}

	@RequiredArgsConstructor
	public enum ComponentType {
		R(1),
		RG(2),
		RGB(3),
		RGBA(4);

		public final int size;
	}

	@RequiredArgsConstructor
	public enum FormatType {
		FLOAT(GL_FLOAT),
		DOUBLE(GL_DOUBLE),
		HALF_FLOAT(GL_HALF_FLOAT),
		INT(GL_INT),
		SHORT(GL_SHORT),
		UNSIGNED_INT(GL_UNSIGNED_INT),
		UNSIGNED_SHORT(GL_UNSIGNED_SHORT),
		BYTE(GL_BYTE),
		UNSIGNED_BYTE(GL_UNSIGNED_BYTE),;

		public final int glFormatType;
	}

	public static final class Attribute {
		public ComponentType component;
		public FormatType format;
		public int stride;
		public int divisor;
		public long offset;

		public boolean isEnabled;
		public boolean isInteger; // TODO: Turn into Flags
		public boolean isNormalized;
	}
}
