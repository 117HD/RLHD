package rs117.hd.utils;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.function.Supplier;

import static rs117.hd.utils.ResourcePath.path;

public class Env
{
	private static final HashMap<String, String> env = new HashMap<>(System.getenv());

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
		return env.get(variableName);
	}

	public static String getOrDefault(String variableName, String defaultValue)
	{
		String value = env.get(variableName);
		return value == null ? defaultValue : value;
	}

	public static String getOrDefault(String variableName, Supplier<String> defaultValueSupplier)
	{
		String value = env.get(variableName);
		return value == null ? defaultValueSupplier.get() : value;
	}

	@Nullable
	public static Boolean getBoolean(String variableName)
	{
		String value = env.get(variableName);
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

	public static ResourcePath getPathOrDefault(String variableName, ResourcePath fallback) {
		String path = env.get(variableName);
		if (path == null)
			return fallback;
		return path(path);
	}

	public static ResourcePath getPathOrDefault(String variableName, Supplier<ResourcePath> fallback) {
		String path = env.get(variableName);
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
