package rs117.hd.utils;

import java.util.Properties;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

import static rs117.hd.utils.ResourcePath.path;

public class Props
{
	public static boolean DEVELOPMENT;

	private static final Properties env = new Properties(System.getProperties());

	public static boolean has(String variableName)
	{
		return env.containsKey(variableName);
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

	public static String getOrDefault(String variableName, @Nonnull Supplier<String> defaultValueSupplier)
	{
		String value = get(variableName);
		return value == null ? defaultValueSupplier.get() : value;
	}

	public static boolean getBoolean(String variableName)
	{
		String value = get(variableName);
		if (value == null)
			return false;
		if (value.isEmpty())
			return true;
		value = value.toLowerCase();
		return value.equals("true") || value.equals("1") || value.equals("on") || value.equals("yes");
	}

	public static ResourcePath getPathOrDefault(String variableName, @Nonnull Supplier<ResourcePath> fallback) {
		String path = get(variableName);
		if (path == null)
			return fallback.get();
		return path(path);
	}

	public static void set(String variableName, boolean value)
	{
		set(variableName, value ? "true" : "false");
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
