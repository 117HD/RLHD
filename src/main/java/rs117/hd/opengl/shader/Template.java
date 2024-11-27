package rs117.hd.opengl.shader;

import java.util.function.Supplier;

public class Template {
	public Template addInclude(String key, String value) {
		// Implementation simplified for testing
		return this;
	}
	
	public Template define(String key, boolean value) {
		// Implementation simplified for testing
		return this;
	}
	
	public Template define(String key, int value) {
		// Implementation simplified for testing
		return this;
	}
	
	public Template define(String key, Enum<?> value) {
		// Implementation simplified for testing
		return this;
	}
	
	public Template define(String key, Supplier<?> supplier) {
		// Implementation simplified for testing
		return this;
	}
	
	public Template addIncludePath(Class<?> clazz) {
		// Implementation simplified for testing
		return this;
	}
	
	public Template addIncludePath(Object path) {
		// Implementation simplified for testing
		return this;
	}
	
	public Template copy() {
		// Implementation simplified for testing
		return new Template();
	}
	
	public String load(String filename) {
		// Implementation simplified for testing
		return "";
	}
}
