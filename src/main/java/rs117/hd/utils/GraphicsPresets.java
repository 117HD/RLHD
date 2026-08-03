package rs117.hd.utils;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ExternalPluginsChanged;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.Presets;

import static rs117.hd.HdPluginConfig.*;

@Slf4j
@Singleton
public class GraphicsPresets {
	public static final QualityLevel[] QUALITY_LEVELS = QualityLevel.values();
	public static final int QUALITY_LEVEL_COUNT = QUALITY_LEVELS.length;

	@Inject
	private HdPlugin plugin;

	@Inject
	private EventBus eventBus;

	@Inject
	private ConfigManager configManager;

	@Inject
	private HdPluginConfig config;

	private QualityLevel currentQualityLevel;
	private GraphicsProperty[] graphicsProperties;

	public void startUp() {
		ArrayList<GraphicsProperty> properties = new ArrayList<>();
		for(Method configMethod : HdPluginConfig.class.getMethods()) {
			Presets configPresets = configMethod.getAnnotation(Presets.class);
			if(configPresets == null)
				continue;

			final String methodName = configMethod.getName();
			int boolCount = configPresets.bools() == null ? 0 : configPresets.bools().length;
			int intCount = configPresets.ints() == null ? 0 : configPresets.ints().length;
			int floatCount = configPresets.floats() == null ? 0 : configPresets.floats().length;
			int enumCount = configPresets.enums() == null ? 0 : configPresets.enums().length;

			assert boolCount + intCount + floatCount + enumCount == QUALITY_LEVEL_COUNT - 1 : "Invalid number of presets in " + methodName;

			ConfigItem configItem = configMethod.getAnnotation(ConfigItem.class);
			if(configItem == null)
				continue;

			GraphicsProperty property = new GraphicsProperty();
			property.key = configItem.keyName();

			if(boolCount > 0) {
				property.values = configPresets.bools();
				property.type = boolean.class;
			} else if(intCount > 0) {
				property.values = configPresets.ints();
				property.type = int.class;
			} else if(floatCount > 0) {
				property.values = configPresets.floats();
				property.type = float.class;
			} else if(enumCount > 0) {
				property.type = configMethod.getReturnType();
				assert property.type.isEnum() : "Config method must return an enum";

				String[] enums = configPresets.enums();
				Enum<?>[] enumValues = (Enum<?>[]) property.type.getEnumConstants();

				property.values = new Enum[enums.length];
				for (int i = 0; i < enums.length; i++) {
					for(int k = 0; k < enumValues.length; k++) {
						if(enums[i].equals(enumValues[k].name())) {
							((Enum[])property.values)[i] = enumValues[k];
							break;
						}
					}
				}
			}

			properties.add(property);
		}

		graphicsProperties = new GraphicsProperty[properties.size()];
		properties.toArray(graphicsProperties);

		currentQualityLevel = config.graphicsPreset();
	}

	public void processConfigChange(Set<String> keys) {
		QualityLevel currentLevel = config.graphicsPreset();
		if(currentLevel == QualityLevel.CUSTOM)
			return;

		if(keys.contains(KEY_GRAPHICS_PRESET)) {
			log.debug("Detected Graphics preset change, switching from: {} to: {}", currentQualityLevel, currentLevel);
			applyQualityLevel(currentLevel);
			return;
		}

		// Check if a config setting that has a preset value differs from the current set Quality Level
		boolean isCustomQualityLevel = false;
		for(GraphicsProperty property : graphicsProperties) {
			if(!keys.contains(property.key))
				continue;

			if(!property.isLevelValue(currentLevel)) {
				isCustomQualityLevel = true;
				break;
			}
		}

		if(isCustomQualityLevel) {
			log.debug("Detected custom quality level");
			configManager.setConfiguration(CONFIG_GROUP, KEY_GRAPHICS_PRESET, QualityLevel.CUSTOM);
			eventBus.post(new ExternalPluginsChanged());
		}
	}

	private void applyQualityLevel(QualityLevel level) {
		if(graphicsProperties == null || graphicsProperties.length == 0)
			return;

		boolean hasConfigChanged = false;
		for(GraphicsProperty property : graphicsProperties) {
			if(property.applyLevel(level))
				hasConfigChanged = true;
		}

		if(hasConfigChanged)
			eventBus.post(new ExternalPluginsChanged());
		currentQualityLevel = level;
	}

	public enum QualityLevel {
		LOW, MEDIUM, HIGH, ULTRA, CUSTOM
	}

	public class GraphicsProperty {
		public String key;
		public Class<?> type;
		public Object values;

		public Object getValue(QualityLevel level) {
			assert level != QualityLevel.CUSTOM;
			Object value = Array.get(values, level.ordinal());
			assert value != null : "Failed to get value for " + key + " level " + level.name();
			return value;
		}

		public Object readValue() {
			Object value = configManager.getConfiguration(CONFIG_GROUP, key, type);
			assert value != null : "Failed to read value for " + key;
			return value;
		}

		public boolean isLevelValue(QualityLevel level) {
			return getValue(level).equals(readValue());
		}

		public boolean applyLevel(QualityLevel level) {
			Object currentValue = readValue();
			Object presetValue = getValue(level);
			if(presetValue.equals(currentValue))
				return false;

			configManager.setConfiguration(CONFIG_GROUP, key, presetValue);

			if(Props.DEVELOPMENT) {
				Object newValue = readValue();
				assert newValue.equals(presetValue) : "Failed to set config value - expected " + presetValue + " but got " + newValue + " for " + key;
			}
			return true;
		}
	}
}
