package rs117.hd;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;
import rs117.hd.utils.Props;

@Slf4j
@SuppressWarnings("unchecked")
public class HdPluginTest
{
	public static void main(String[] args) throws Exception
	{
		log.warn(
			"If this is your first time using the HdPluginTest run configuration, " +
			"this window will filter on WARN-level logging by default"
		);

		Props.DEVELOPMENT = true;
		Props.set("rlhd.resource-path", "src/main/resources");
		ClassLoader.getSystemClassLoader().setDefaultAssertionStatus(true);
		useLatestPluginHub();
		ExternalPluginManager.loadBuiltin(buildPluginList());
		RuneLite.main(args);
	}

	private static void useLatestPluginHub()
	{
		if (System.getProperty("runelite.pluginhub.version") == null)
		{
			try
			{
				Properties props = new Properties();
				try (InputStream in = RuneLiteProperties.class.getResourceAsStream("runelite.properties"))
				{
					props.load(in);
				}

				String version = props.getProperty("runelite.pluginhub.version");
				String[] parts = version.split("[.-]");
				if (parts.length > 3 && parts[3].equals("SNAPSHOT"))
				{
					int patch = Integer.parseInt(parts[2]) - 1;
					version = parts[0] + "." + parts[1] + "." + patch;
					log.info("Detected SNAPSHOT version with no manually specified plugin-hub version. " +
							"Setting runelite.pluginhub.version to {}", version);
					System.setProperty("runelite.pluginhub.version", version);
				}
			}
			catch (Exception ex)
			{
				log.error("Failed to automatically use latest plugin-hub version", ex);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Class<? extends Plugin>[] buildPluginList() {
		List<Class<? extends Plugin>> plugins = new ArrayList<>();
		plugins.add(HdPlugin.class);
		try {
			plugins.add((Class<? extends Plugin>) Class.forName("rs117.hd.DeveloperPlugin"));
		} catch (ClassNotFoundException ex) {
			log.info("117 HD Developer plugin is not available in this checkout");
		}
		return plugins.toArray(new Class[0]);
	}
}
