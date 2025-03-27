
package rs117.hd.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MaxDynamicLights
{
	NONE("None", 0),
	FEW("Few (25)", 25),
	SOME("Some (50)", 50),
	MANY("Many (100)", 100);

	private final String name;
	private final int value;

	@Override
	public String toString()
	{
		return name;
	}
}
