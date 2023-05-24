package rs117.hd.utils;

import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import static rs117.hd.utils.ResourcePath.path;

public class Props
{
	public static boolean DEVELOPMENT;

	private static final Properties env = new Properties();

	static
	{
		env.putAll(System.getProperties());
	}

	public static boolean has(String variableName)
	{
		return env.containsKey(variableName);
	}

	public static boolean missing(String variableName)
	{
		return !has(variableName);
	}

	public static String get(String variableName)
	{
		return env.getProperty(variableName);
	}

	public static String getOrDefault(String variableName, String defaultValue)
	{
		String value = get(variableName);
		return value == null ? defaultValue : value;
	}

	public static String getOrDefault(String variableName, Supplier<String> defaultValueSupplier)
	{
		String value = get(variableName);
		return value == null ? defaultValueSupplier.get() : value;
	}

	@Nullable
	public static Boolean getBoolean(String variableName)
	{
		String value = get(variableName);
		if (value == null)
			return null;
		value = value.toLowerCase();
		return value.equals("true") || value.equals("1") || value.equals("on") || value.equals("yes");
	}

	public static boolean getBooleanOrDefault(String variableName, boolean defaultValue)
	{
		Boolean value = getBoolean(variableName);
		return value == null ? defaultValue : value;
	}

	public static ResourcePath getPathOrDefault(String variableName, Supplier<ResourcePath> fallback) {
		String path = get(variableName);
		if (path == null)
			return fallback.get();
		return path(path);
	}

	public static void set(String variableName, boolean value)
	{
		set(variableName, value ? "true" : "false");
	}

	public static void set(String variableName, Path value)
	{
		set(variableName, value.toAbsolutePath().toString());
	}

	public static void set(String variableName, String value)
	{
		if (value == null)
		{
			unset(variableName);
		}
		else
		{
			env.put(variableName, value);
		}
	}

	public static void unset(String variableName)
	{
		env.remove(variableName);
	}
}
