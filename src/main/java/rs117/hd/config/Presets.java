package rs117.hd.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Presets {
	int[] ints() default {};
	double[] floats() default {};
	String[] enums() default {};
	boolean[] bools() default {};
}
