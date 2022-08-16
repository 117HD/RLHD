package rs117.hd.scene.lighting;

import com.google.common.collect.ListMultimap;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ResourcePath;

import java.io.IOException;
import java.util.ArrayList;

@Slf4j
public class LightConfig
{
	public static void load(
		ResourcePath path,
		ArrayList<SceneLight> worldLights,
		ListMultimap<Integer, Light> npcLights,
		ListMultimap<Integer, Light> objectLights,
		ListMultimap<Integer, Light> projectileLights
	)
	{
		try
		{
			Light[] lights;
			try {
				lights = path.loadJson(Light[].class);
			} catch (IOException ex) {
				log.error("Failed to load lights:", ex);
				return;
			}

			for (Light l : lights)
			{
				// Map values from [0, 255] in gamma color space to [0, 1] in linear color space
				// Also ensure that each color always has 4 components with sensible defaults
				float[] linearRGBA = { 0, 0, 0, 1 };
				for (int i = 0; i < Math.min(l.color.length, linearRGBA.length); i++)
				{
					linearRGBA[i] = HDUtils.srgbToLinear(l.color[i] /= 255f);
				}
				l.color = linearRGBA;

				if (l.worldX != null && l.worldY != null)
				{
					worldLights.add(new SceneLight(l));
				}
				l.npcIds.forEach(id -> npcLights.put(id, l));
				l.objectIds.forEach(id -> objectLights.put(id, l));
				l.projectileIds.forEach(id -> projectileLights.put(id, l));
			}

			log.info("Loaded {} lights", lights.length);
		}
		catch (Exception ex)
		{
			log.error("Failed to parse light configuration", ex);
		}
	}
}
