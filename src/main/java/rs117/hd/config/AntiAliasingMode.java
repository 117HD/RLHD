
package rs117.hd.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
@Getter
@RequiredArgsConstructor
public enum AntiAliasingMode
{
	DISABLED("Disabled", 0),
	MSAA_2("MSAA x2", 2),
	MSAA_4("MSAA x4", 4),
	MSAA_8("MSAA x8", 8),
	MSAA_16("MSAA x16", 16);

	private final String name;
	private final int samples;

	/**
	 * Returns the name of the AntiAliasingMode.
	 */
	public String getName()
	{
		return name;
	}

	/**
	 * Gets the sample count associated with the AntiAliasingMode.
	 */
	public int getSamples()
	{
		return samples;
	}

	/**
	 * Returns a string representation of the AntiAliasingMode.
	 * @return the name of the mode
	 */
	public String toString()
	{
		// Explaining variable for the name of the mode
		String modeName = getName();
		return modeName;
	}
}
