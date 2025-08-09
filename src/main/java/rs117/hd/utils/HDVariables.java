package rs117.hd.utils;

import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.SeasonalTheme;

@Singleton
public class HDVariables implements VariableSupplier {
	public static final String VAR_SEASONAL_THEME = "season";
	public static final String VAR_MODEL_TEXTURES = "modelTextures";
	public static final String VAR_GROUND_TEXTURES = "groundTextures";
	public static final String VAR_GROUND_BLENDING = "blending";
	public static final String VAR_HD_INFERNAL_TEXTURE = "hdInfernalCape";

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	private static final List<Class<? extends Enum<? extends Enum<?>>>> VAR_ENUMS = List.of(
		SeasonalTheme.class
	);

	@Override
	public Object get(String name) {
		switch (name) {
			case VAR_SEASONAL_THEME:
				return plugin.configSeasonalTheme.ordinal();
			case VAR_MODEL_TEXTURES:
				return plugin.configModelTextures;
			case VAR_GROUND_TEXTURES:
				return plugin.configGroundTextures;
			case VAR_GROUND_BLENDING:
				return plugin.configGroundBlending;
			case VAR_HD_INFERNAL_TEXTURE:
				return config.hdInfernalTexture();
		}

		int i = name.indexOf('.');
		if (i > 0 && i < name.length() - 1) {
			var enumName = name.substring(0, i);
			for (var varEnum : VAR_ENUMS) {
				if (!enumName.equals(varEnum.getSimpleName()))
					continue;

				var enumKey = name.substring(i + 1);
				var enumConstants = varEnum.getEnumConstants();
				for (var enumConstant : enumConstants)
					if (enumKey.equals(enumConstant.name()))
						return enumConstant.ordinal();

				break;
			}
		}

		return null;
	}
}
