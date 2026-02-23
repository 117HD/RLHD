package rs117.hd;

import java.io.InputStream;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.externalplugins.ExternalPluginManager;
import org.slf4j.LoggerFactory;
import rs117.hd.utils.Props;

@SuppressWarnings("unchecked")
@Slf4j
public class HdPluginTest
{
	public static void main(String[] args) throws Exception
	{
		Props.DEVELOPMENT = true;
		Props.set("rlhd.resource-path", "src/main/resources");
		ClassLoader.getSystemClassLoader().setDefaultAssertionStatus(true);
		useLatestPluginHub();
		postLogFilterReminder();
		ExternalPluginManager.loadBuiltin(HdPlugin.class);
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

	private static void postLogFilterReminder() {
		LoggerFactory.getLogger("rs117.hd.FilterReminder").warn(
			"If this is your first time this session using the HdPluginTest run-configuration, " +
			"this window will filter on WARN-level logging until you reset the filter."
		);
	}
}
