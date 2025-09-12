package rs117.hd.utils;

import java.util.Properties;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.meta.When;

import static rs117.hd.utils.ResourcePath.path;

public class Props
{
	public static boolean DEVELOPMENT;

	private static final Properties env = new Properties();

	static {
		env.putAll(System.getProperties());
	}

	public static boolean has(String key)
	{
		return env.containsKey(key);
	}

	public static String get(String key)
	{
		return env.getProperty(key);
	}

	public static String getOrDefault(String key, String defaultValue)
	{
		String value = get(key);
		return value == null ? defaultValue : value;
	}

	public static String getOrDefault(String key, @Nonnull Supplier<String> defaultValueSupplier)
	{
		String value = get(key);
		return value == null ? defaultValueSupplier.get() : value;
	}

	public static boolean getBoolean(String key)
	{
		String value = get(key);
		if (value == null)
			return false;
		if (value.isEmpty())
			return true;
		value = value.toLowerCase();
		return value.equals("true") || value.equals("1") || value.equals("on") || value.equals("yes");
	}

	public static ResourcePath getFile(String key, @Nonnull Supplier<ResourcePath> fallback) {
		var path = get(key);
		return path != null ? path(path) : fallback.get();
	}

	@Nonnull(when = When.UNKNOWN) // Disable downstream null warnings, since they're not smart enough
	public static ResourcePath getFolder(String key, @Nonnull Supplier<ResourcePath> fallback) {
		var path = getFile(key, fallback);
		return path != null ? path.chroot() : null;
	}

	public static void set(String key, boolean value)
	{
		set(key, value ? "true" : "false");
	}

	public static void set(String key, String value)
	{
		if (value == null)
		{
			unset(key);
		}
		else
		{
			env.put(key, value);
		}
	}

	public static void unset(String key)
	{
		env.remove(key);
	}
}
