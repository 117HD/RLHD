package rs117.hd.utils;

public interface Initializable {
	default void initialize() {}

	default void destroy() {}

	default void reinitialize() {
		destroy();
		initialize();
	}
}
