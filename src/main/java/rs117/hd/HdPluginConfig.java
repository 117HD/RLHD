/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;
import rs117.hd.config.AntiAliasingMode;
import rs117.hd.config.ColorBlindMode;
import rs117.hd.config.ColorFilter;
import rs117.hd.config.Contrast;
import rs117.hd.config.DefaultSkyColor;
import rs117.hd.config.DynamicLights;
import rs117.hd.config.FishingSpotStyle;
import rs117.hd.config.FogDepthMode;
import rs117.hd.config.Saturation;
import rs117.hd.config.SceneScalingMode;
import rs117.hd.config.SeasonalHemisphere;
import rs117.hd.config.SeasonalTheme;
import rs117.hd.config.ShadingMode;
import rs117.hd.config.ShadowDistance;
import rs117.hd.config.ShadowMode;
import rs117.hd.config.ShadowResolution;
import rs117.hd.config.TextureResolution;
import rs117.hd.config.UIScalingMode;
import rs117.hd.config.VanillaShadowMode;

import static rs117.hd.HdPlugin.MAX_DISTANCE;
import static rs117.hd.HdPlugin.MAX_FOG_DEPTH;
import static rs117.hd.HdPluginConfig.*;
import static rs117.hd.utils.MathUtils.*;

@ConfigGroup(CONFIG_GROUP)
public interface HdPluginConfig extends Config
{
	String CONFIG_GROUP = "hd";

	/*====== General settings ======*/

	@ConfigSection(
		name = "General",
		description = "General settings",
		position = 0
	)
	String generalSettings = "generalSettings";

	@Range(
		max = MAX_DISTANCE
	)
	@ConfigItem(
		keyName = "drawDistance",
		name = "Draw Distance",
		description =
			"The number of tiles to draw in either direction from the camera, up to a maximum of 184.<br>" +
			"Depending on where the scene is centered, you might only see 16 tiles in one direction, unless you extend map loading.",
		position = 1,
		section = generalSettings
	)
	default int drawDistance() {
		return 50;
	}

	String KEY_EXPANDED_MAP_LOADING_CHUNKS = "expandedMapLoadingChunks";
	@Range(
		max = 5
	)
	@ConfigItem(
		keyName = KEY_EXPANDED_MAP_LOADING_CHUNKS,
		name = "Extended map loading",
		description =
			"How much further the map should be loaded. The maximum is 5 extra chunks.<br>" +
			"Note, extending the map can have a very high impact on performance.",
		position = 2,
		section = generalSettings
	)
	default int expandedMapLoadingChunks() {
		return 3;
	}

	String KEY_ANTI_ALIASING_MODE = "antiAliasingMode";
	@ConfigItem(
		keyName = KEY_ANTI_ALIASING_MODE,
		name = "Anti-Aliasing",
		description =
			"Improves pixelated edges at the cost of significantly higher GPU usage.<br>" +
			"MSAA x16 is very expensive, so x8 is recommended if anti-aliasing is desired.",
		position = 3,
		section = generalSettings
	)
	default AntiAliasingMode antiAliasingMode()
	{
		return AntiAliasingMode.DISABLED;
	}

	String KEY_SCENE_RESOLUTION_SCALE = "sceneResolutionScale";
	@ConfigItem(
		keyName = KEY_SCENE_RESOLUTION_SCALE,
		name = "Game Resolution",
		description =
			"Render the game at a different resolution and stretch it to fit the screen.<br>" +
			"Reducing this can improve performance, particularly on very high resolution displays.",
		position = 4,
		section = generalSettings
	)
	@Units(Units.PERCENT)
	@Range(min = 1, max = 200)
	default int sceneResolutionScale() {
		return 100;
	}

	@ConfigItem(
		keyName = "sceneScalingMode",
		name = "Game Scaling Mode",
		description = "The sampling function to use when upscaling the above reduced game resolution.",
		position = 5,
		section = generalSettings
	)
	default SceneScalingMode sceneScalingMode()
	{
		return SceneScalingMode.LINEAR;
	}

	String KEY_UI_SCALING_MODE = "uiScalingMode";
	@ConfigItem(
		keyName = KEY_UI_SCALING_MODE,
		name = "UI Scaling Mode",
		description =
			"The sampling function to use when the Stretched Mode plugin is enabled.<br>" +
			"Affects how the UI looks with non-integer scaling.",
		position = 6,
		section = generalSettings
	)
	default UIScalingMode uiScalingMode() {
		return UIScalingMode.LINEAR;
	}

	String KEY_ANISOTROPIC_FILTERING_LEVEL = "anisotropicFilteringLevel";
	@Range(
		min = 0,
		max = 16
	)
	@ConfigItem(
		keyName = KEY_ANISOTROPIC_FILTERING_LEVEL,
		name = "Anisotropic Filtering",
		description =
			"Configures whether mipmapping and anisotropic filtering should be used.<br>" +
			"At zero, mipmapping is disabled and textures look the most pixelated.<br>" +
			"At 1 through 16, mipmapping is enabled, and textures look more blurry and smoothed out.<br>" +
			"The higher you go beyond 1, the less blurry textures will look, up to a certain extent.",
		position = 7,
		section = generalSettings
	)
	default int anisotropicFilteringLevel()
	{
		return 16;
	}

	String KEY_UNLOCK_FPS = "unlockFps";
	@ConfigItem(
		keyName = KEY_UNLOCK_FPS,
		name = "Unlock FPS",
		description = "Removes the 50 FPS cap for some game content, such as camera movement and dynamic lighting.",
		position = 8,
		section = generalSettings
	)
	default boolean unlockFps()
	{
		return false;
	}

	enum SyncMode
	{
		OFF,
		ON,
		ADAPTIVE
	}

	String KEY_VSYNC_MODE = "vsyncMode";
	@ConfigItem(
		keyName = KEY_VSYNC_MODE,
		name = "VSync Mode",
		description =
			"Controls whether the frame rate should be synchronized with your monitor's refresh rate.<br>" +
			"If set to 'off', the FPS Target option will be used instead.<br>" +
			"If set to 'adaptive', FPS will be limited to your monitor's refresh rate, which saves power.<br>" +
			"If set to 'on', the game will attempt to match your monitor's refresh rate <b>exactly</b>,<br>" +
			"but if it can't keep up, FPS will be <u>halved until it catches up</u>. This option is rarely desired.<br>" +
			"Note, GPUs that don't support Adaptive VSync will silently fall back to 'on'.",
		position = 9,
		section = generalSettings
	)
	default SyncMode syncMode()
	{
		return SyncMode.ADAPTIVE;
	}

	String KEY_FPS_TARGET = "fpsTarget";
	@ConfigItem(
		keyName = KEY_FPS_TARGET,
		name = "FPS Target",
		description =
			"Controls the maximum number of frames per second.<br>" +
			"This setting only applies if Unlock FPS is enabled, and VSync Mode is set to 'off'.",
		position = 10,
		section = generalSettings
	)
	@Range(
		min = 0,
		max = 999
	)
	default int fpsTarget()
	{
		return 60;
	}

	String KEY_COLOR_BLINDNESS = "colorBlindMode";
	@ConfigItem(
		keyName = KEY_COLOR_BLINDNESS,
		name = "Color Blindness",
		description = "Adjust colors to make them more distinguishable for people with a certain type of color blindness.",
		position = 11,
		section = generalSettings
	)
	default ColorBlindMode colorBlindness()
	{
		return ColorBlindMode.NONE;
	}

	@ConfigItem(
		keyName = "colorBlindnessIntensity",
		name = "Blindness Intensity",
		description = "Specifies how intense the color blindness adjustment should be.",
		position = 12,
		section = generalSettings
	)
	@Units(Units.PERCENT)
	@Range(max = 100)
	default int colorBlindnessIntensity()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "flashingEffects",
		name = "Flashing Effects",
		description = "Whether to show rapid flashing effects, such as lightning, in certain areas.",
		position = 13,
		section = generalSettings
	)
	default boolean flashingEffects()
	{
		return false;
	}

	@ConfigItem(
		keyName = "fSaturation",
		name = "Saturation",
		description = "Controls the saturation of the final rendered image.<br>" +
			"Intended to be kept between 0% and 120%.",
		position = 14,
		section = generalSettings
	)
	@Units(Units.PERCENT)
	@Range(min = -500, max = 500)
	default int saturation()
	{
		return round(oldSaturationDropdown().getAmount() * 100);
	}
	@ConfigItem(keyName = "saturation", hidden = true, name = "", description = "")
	default Saturation oldSaturationDropdown()
	{
		return Saturation.DEFAULT;
	}

	@ConfigItem(
		keyName = "fContrast",
		name = "Contrast",
		description = "Controls the contrast of the final rendered image.<br>" +
			"Intended to be kept between 90% and 110%.",
		position = 15,
		section = generalSettings
	)
	@Units(Units.PERCENT)
	@Range(min = -500, max = 500)
	default int contrast()
	{
		return round(oldContrastDropdown().getAmount() * 100);
	}
	@ConfigItem(keyName = "contrast", hidden = true, name = "", description = "")
	default Contrast oldContrastDropdown()
	{
		return Contrast.DEFAULT;
	}

	String KEY_BRIGHTNESS = "screenBrightness";
	@Range(
		min = 25,
		max = 400
	)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = KEY_BRIGHTNESS,
		name = "Brightness",
		description =
			"Controls the brightness of the game, excluding UI.<br>" +
			"Adjust until the disk on the left is barely visible.",
		position = 16,
		section = generalSettings
	)
	default int brightness() {
		return 100;
	}

	@ConfigItem(
		keyName = "useLegacyBrightness",
		name = "Enable Legacy Brightness",
		description =
			"Whether the legacy brightness option below should be applied.<br>" +
			"We recommend leaving this disabled.",
		position = 17,
		section = generalSettings
	)
	default boolean useLegacyBrightness() {
		return false;
	}

	@Range(
		min = 1,
		max = 50
	)
	@ConfigItem(
		keyName = "brightness2",
		name = "Legacy Brightness",
		description =
			"Controls the strength of the sun and ambient lighting.<br>" +
			"A brightness value of 20 is recommended.",
		position = 18,
		section = generalSettings
	)
	default int legacyBrightness() {
		return 20;
	}


	/*====== Lighting settings ======*/

	@ConfigSection(
		name = "Lighting",
		description = "Lighting settings",
		position = 1
	)
	String lightingSettings = "lightingSettings";

	String KEY_DYNAMIC_LIGHTS = "dynamicLights";
	@ConfigItem(
		keyName = KEY_DYNAMIC_LIGHTS,
		name = "Dynamic Lights",
		description =
			"The maximum number of dynamic lights visible at once.<br>" +
			"Reducing this may improve performance.",
		position = 0,
		section = lightingSettings
	)
	default DynamicLights dynamicLights()
	{
		return DynamicLights.SOME;
	}

	String KEY_TILED_LIGHTING = "tiledLighting";
	@ConfigItem(
		keyName = KEY_TILED_LIGHTING,
		name = "Tiled Lighting",
		description = "Allows rendering <b>a lot</b> more lights simultaneously.",
		section = lightingSettings,
		position = 1
	)
	default boolean tiledLighting() {
		return true;
	}

	String KEY_PROJECTILE_LIGHTS = "projectileLights";
	@ConfigItem(
		keyName = KEY_PROJECTILE_LIGHTS,
		name = "Projectile Lights",
		description = "Adds dynamic lights to some projectiles.",
		position = 2,
		section = lightingSettings
	)
	default boolean projectileLights() {
		return true;
	}

	String KEY_NPC_LIGHTS = "npcLights";
	@ConfigItem(
		keyName = KEY_NPC_LIGHTS,
		name = "NPC Lights",
		description = "Adds dynamic lights to some NPCs.",
		position = 3,
		section = lightingSettings
	)
	default boolean npcLights() {
		return true;
	}

	String KEY_ATMOSPHERIC_LIGHTING = "environmentalLighting";
	@ConfigItem(
		keyName = KEY_ATMOSPHERIC_LIGHTING,
		name = "Atmospheric Lighting",
		description = "Change environmental lighting based on the current area.",
		position = 4,
		section = lightingSettings
	)
	default boolean atmosphericLighting() {
		return true;
	}

	String KEY_SHADOW_MODE = "shadowMode";
	@ConfigItem(
		keyName = KEY_SHADOW_MODE,
		name = "Shadows",
		description =
			"Render fully dynamic shadows.<br>" +
			"'Off' completely disables shadows.<br>" +
			"'Fast' enables fast shadows without any texture detail.<br>" +
			"'Detailed' enables slower shadows with support for texture detail.",
		position = 5,
		section = lightingSettings
	)
	default ShadowMode shadowMode()
	{
		return ShadowMode.DETAILED;
	}

	String KEY_SHADOW_TRANSPARENCY = "enableShadowTransparency";
	@ConfigItem(
		keyName = KEY_SHADOW_TRANSPARENCY,
		name = "Shadow Transparency",
		description = "Enables partial support for shadows that take transparency into account.",
		position = 6,
		section = lightingSettings
	)
	default boolean enableShadowTransparency()
	{
		return true;
	}

	String KEY_PIXELATED_SHADOWS = "pixelatedShadows";
	@ConfigItem(
		keyName = KEY_PIXELATED_SHADOWS,
		name = "Pixelated Shadows",
		description = "Give shadows a slightly pixelated look.",
		position = 7,
		section = lightingSettings
	)
	default boolean pixelatedShadows() {
		return false;
	}

	String KEY_SHADOW_RESOLUTION = "shadowResolution";
	@ConfigItem(
		keyName = KEY_SHADOW_RESOLUTION,
		name = "Shadow Quality",
		description =
			"The resolution of the shadow map.<br>" +
			"Higher resolutions result in higher quality shadows, at the cost of higher GPU usage.",
		position = 8,
		section = lightingSettings
	)
	default ShadowResolution shadowResolution()
	{
		return ShadowResolution.RES_4096;
	}

	@ConfigItem(
		keyName = "shadowDistance",
		name = "Shadow Distance",
		description =
			"The maximum draw distance for shadows.<br>" +
			"Shorter distances result in higher quality shadows.",
		position = 9,
		section = lightingSettings
	)
	default ShadowDistance shadowDistance()
	{
		return ShadowDistance.DISTANCE_50;
	}

	String KEY_EXPAND_SHADOW_DRAW = "expandShadowDraw";
	@ConfigItem(
		keyName = KEY_EXPAND_SHADOW_DRAW,
		name = "Expand Shadow Draw",
		description =
			"Reduces shadows popping in and out at the edge of the screen by rendering<br>" +
			"shadows for a larger portion of the scene, at the cost of higher GPU usage.",
		position = 10,
		section = lightingSettings
	)
	default boolean expandShadowDraw()
	{
		return false;
	}

	String KEY_VANILLA_SHADOW_MODE = "vanillaShadowMode";
	@ConfigItem(
		keyName = KEY_VANILLA_SHADOW_MODE,
		name = "Vanilla Shadows",
		description =
			"Choose whether shadows built into models by Jagex should be hidden. This does not affect clickboxes.<br>" +
			"'Show in PvM' will retain shadows for falling crystals during the Olm fight and other useful cases.<br>" +
			"'Prefer in PvM' will do the above and also disable 117 HD's dynamic shadows in such cases.",
		position = 11,
		section = lightingSettings
	)
	default VanillaShadowMode vanillaShadowMode() {
		return VanillaShadowMode.SHOW_IN_PVM;
	}

	String KEY_NORMAL_MAPPING = "normalMapping";
	@ConfigItem(
		keyName = KEY_NORMAL_MAPPING,
		name = "Normal Mapping",
		description = "Affects how light interacts with certain materials. Barely impacts performance.",
		position = 12,
		section = lightingSettings
	)
	default boolean normalMapping() {
		return true;
	}

	String KEY_PARALLAX_OCCLUSION_MAPPING = "parallaxOcclusionMappingToggle";
	@ConfigItem(
		keyName = KEY_PARALLAX_OCCLUSION_MAPPING,
		name = "Parallax Occlusion Mapping",
		description = "Adds more depth to some materials, at the cost of higher GPU usage.",
		position = 13,
		section = lightingSettings
	)
	default boolean parallaxOcclusionMapping() {
		return true;
	}


	/*====== Environment settings ======*/

	@ConfigSection(
		name = "Environment",
		description = "Environment settings",
		position = 2
	)
	String environmentSettings = "environmentSettings";

	String KEY_SEASONAL_THEME = "seasonalTheme";
	@ConfigItem(
		keyName = KEY_SEASONAL_THEME,
		name = "Seasonal Theme",
		description = "Festive themes for Gielinor.",
		position = 0,
		section = environmentSettings
	)
	default SeasonalTheme seasonalTheme() {
		return SeasonalTheme.AUTOMATIC;
	}

	String KEY_SEASONAL_HEMISPHERE = "seasonalHemisphere";
	@ConfigItem(
		keyName = KEY_SEASONAL_HEMISPHERE,
		name = "Seasonal Hemisphere",
		description = "Determines which hemisphere the 'Automatic' Seasonal Theme should consider.",
		position = 1,
		section = environmentSettings
	)
	default SeasonalHemisphere seasonalHemisphere() {
		return SeasonalHemisphere.NORTHERN;
	}

	@ConfigItem(
		keyName = "fogDepthMode",
		name = "Fog Depth Mode",
		description =
			"Determines how the fog amount is controlled.<br>" +
			"'Dynamic' changes fog depth based on the area, while<br>" +
			"'Static' respects the manually defined fog depth.",
		position = 2,
		section = environmentSettings
	)
	default FogDepthMode fogDepthMode()
	{
		return FogDepthMode.DYNAMIC;
	}

	@Range(
		max = MAX_FOG_DEPTH
	)
	@ConfigItem(
		keyName = "fogDepth",
		name = "Static Fog Depth",
		description =
			"Specify how far from the edge fog should reach.<br>" +
			"This applies only when 'Fog Depth Mode' is set to 'Static'.",
		position = 3,
		section = environmentSettings
	)
	default int fogDepth()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "groundFog",
		name = "Ground Fog",
		description = "Enables a height-based fog effect that covers the ground in certain areas.",
		position = 4,
		section = environmentSettings
	)
	default boolean groundFog() {
		return true;
	}

	@ConfigItem(
		keyName = "defaultSkyColor",
		name = "Default Sky",
		description =
			"Specify a sky color to use when the current area doesn't have a sky color defined.<br>" +
			"This only applies when the default summer seasonal theme is active.<br>" +
			"If set to 'RuneLite Skybox', the sky color from RuneLite's Skybox plugin will be used.<br>" +
			"If set to 'Old School Black', the sky will be black and water will remain blue, but for any<br>" +
			"other option, the water color will be influenced by the sky color.",
		position = 5,
		section = environmentSettings
	)
	default DefaultSkyColor defaultSkyColor()
	{
		return DefaultSkyColor.DEFAULT;
	}

	@ConfigItem(
		keyName = "overrideSky",
		name = "Override Sky Color",
		description = "Forces the default sky color to be used in all environments.",
		position = 6,
		section = environmentSettings
	)
	default boolean overrideSky() {
		return false;
	}

	String KEY_MODEL_TEXTURES = "objectTextures";
	@ConfigItem(
		keyName = KEY_MODEL_TEXTURES,
		name = "Model Textures",
		description =
			"Adds new textures to most models. If disabled, the standard game textures will be used instead.<br>" +
			"Note, this requires model caching in order to apply to animated models.",
		position = 7,
		section = environmentSettings
	)
	default boolean modelTextures() {
		return true;
	}

	String KEY_GROUND_TEXTURES = "groundTextures";
	@ConfigItem(
		keyName = KEY_GROUND_TEXTURES,
		name = "Ground Textures",
		description = "Adds new textures to most ground tiles.",
		position = 8,
		section = environmentSettings
	)
	default boolean groundTextures()
	{
		return true;
	}

	String KEY_TEXTURE_RESOLUTION = "textureResolution";
	@ConfigItem(
		keyName = KEY_TEXTURE_RESOLUTION,
		name = "Texture Resolution",
		description = "Controls the resolution used for all in-game textures.",
		position = 9,
		section = environmentSettings
	)
	default TextureResolution textureResolution()
	{
		return TextureResolution.RES_256;
	}

	String KEY_GROUND_BLENDING = "groundBlending";
	@ConfigItem(
		keyName = KEY_GROUND_BLENDING,
		name = "Ground Blending",
		description = "Controls whether ground tiles should blend into each other, or have distinct edges.",
		position = 10,
		section = environmentSettings
	)
	default boolean groundBlending()
	{
		return true;
	}

	@ConfigItem(
		keyName = "underwaterCaustics",
		name = "Underwater Caustics",
		description = "Apply underwater lighting effects to imitate sunlight passing through waves on the surface.",
		position = 11,
		section = environmentSettings
	)
	default boolean underwaterCaustics()
	{
		return true;
	}

	String KEY_HD_TZHAAR_RESKIN = "tzhaarHD";
	@ConfigItem(
		keyName = KEY_HD_TZHAAR_RESKIN,
		name = "HD TzHaar Reskin",
		description = "Recolors the TzHaar city of Mor Ul Rek to give it an appearance similar to that of its 2008 HD variant.",
		position = 12,
		section = environmentSettings
	)
	default boolean hdTzHaarReskin() {
		return true;
	}

	String KEY_WIND_DISPLACEMENT = "windDisplacement";
	@ConfigItem(
		keyName = KEY_WIND_DISPLACEMENT,
		name = "Wind Displacement",
		description = "Controls whether things like grass and leaves should be affected by wind.",
		position = 13,
		section = environmentSettings
	)
	default boolean windDisplacement() {
		return true;
	}

	String KEY_CHARACTER_DISPLACEMENT = "characterDisplacement";
	@ConfigItem(
		keyName = KEY_CHARACTER_DISPLACEMENT,
		name = "Character Displacement",
		description = "Let players & NPCs affect things like grass whilst walking around.",
		position = 14,
		section = environmentSettings
	)
	default boolean characterDisplacement() {
		return true;
	}

	/*====== Model caching settings ======*/

	@ConfigSection(
		name = "Model caching",
		description = "Improve performance by reusing model data",
		position = 3,
		closedByDefault = true
	)
	String modelCachingSettings = "modelCachingSettings";

	String KEY_MODEL_BATCHING = "useModelBatching";
	@ConfigItem(
		keyName = KEY_MODEL_BATCHING,
		name = "Model Batching",
		description =
			"Model batching improves performance by reusing identical models within the same frame.<br>" +
			"May cause instability and graphical bugs, particularly if Jagex makes engine changes.",
		position = 1,
		section = modelCachingSettings
	)
	default boolean modelBatching() {return true;}

	String KEY_MODEL_CACHING = "useModelCaching";
	@ConfigItem(
		keyName = KEY_MODEL_CACHING,
		name = "Model Caching",
		description =
			"Model caching improves performance by saving and reusing model data from previous frames.<br>" +
			"May cause instability or graphical bugs, particularly if Jagex makes engine changes.",
		position = 2,
		section = modelCachingSettings
	)
	default boolean modelCaching() {return true;}

	String KEY_MODEL_CACHE_SIZE = "modelCacheSizeMiBv2";
	@Range(
		min = 64,
		max = 16384
	)
	@ConfigItem(
		keyName = KEY_MODEL_CACHE_SIZE,
		name = "Cache Size (MiB)",
		description =
			"Size of the model cache in mebibytes (slightly more than megabytes).<br>" +
			"Generally, 512 MiB is plenty, with diminishing returns the higher you go.<br>" +
			"Minimum=64 MiB, maximum=16384 MiB",
		position = 3,
		section = modelCachingSettings
	)
	default int modelCacheSizeMiB() {
		return modelCacheSizeMiBv1() / 4;
	}
	@ConfigItem(keyName = "modelCacheSizeMiB", hidden = true, name = "", description = "")
	default int modelCacheSizeMiBv1()
	{
		return 2048;
	}


	/*====== Miscellaneous settings ======*/

	@ConfigSection(
		name = "Miscellaneous",
		description = "Miscellaneous settings",
		position = 4,
		closedByDefault = true
	)
	String miscellaneousSettings = "miscellaneousSettings";

	String KEY_MACOS_INTEL_WORKAROUND = "macosIntelWorkaround";
	@ConfigItem(
		keyName = KEY_MACOS_INTEL_WORKAROUND,
		name = "Fix white color issue on Macs",
		description = "Workaround for visual artifacts found on some Intel GPU drivers on macOS.",
		warning =
			"This setting can cause RuneLite to crash, and it can be difficult to undo.\n" +
			"Only enable it if you are seeing broken colors. Are you sure you want to enable this setting?",
		section = miscellaneousSettings
	)
	default boolean macosIntelWorkaround()
	{
		return false;
	}

	String KEY_HD_INFERNAL_CAPE = "hdInfernalTexture";
	@ConfigItem(
		keyName = KEY_HD_INFERNAL_CAPE,
		name = "HD Infernal Cape",
		description =
			"Replace the infernal cape texture with a more detailed version.<br>" +
			"Note, with Anisotropic Filtering above zero, the cape may look blurry when zoomed out.",
		section = miscellaneousSettings
	)
	default boolean hdInfernalTexture() {
		return true;
	}

	String KEY_LEGACY_GREY_COLORS = "reduceOverExposure";
	@ConfigItem(
		keyName = KEY_LEGACY_GREY_COLORS,
		name = "Legacy Grey Colors",
		description =
			"Previously, HD attempted to reduce over-exposure by capping the maximum color brightness,<br>" +
			"which changed white colors into dull shades of grey. This option brings back that old behaviour.",
		section = miscellaneousSettings
	)
	default boolean legacyGreyColors() {
		return false;
	}

	String KEY_VANILLA_COLOR_BANDING = "vanillaColorBanding";
	@ConfigItem(
		keyName = KEY_VANILLA_COLOR_BANDING,
		name = "Vanilla Color Banding",
		description =
			"Blend between colors similarly to how it works in vanilla, with clearly defined bands of color.<br>" +
			"This isn't really noticeable on textured surfaces, and is intended to be used without ground textures.",
		section = miscellaneousSettings
	)
	default boolean vanillaColorBanding() {
		return false;
	}

	String KEY_LOW_MEMORY_MODE = "lowMemoryMode";
	@ConfigItem(
		keyName = KEY_LOW_MEMORY_MODE,
		name = "Low Memory Mode",
		description = "Turns off features which require extra memory, such as model caching, faster scene loading & extended scene loading.",
		warning =
			"<html>This <b>will not</b> result in better performance. It is recommended only if you are unable to install<br>" +
			"the 64-bit version of RuneLite, or if your computer has a very low amount of memory available.</html>",
		section = miscellaneousSettings
	)
	default boolean lowMemoryMode() {
		return false;
	}

	String KEY_FISHING_SPOT_STYLE = "fishingSpotStyle";
	@ConfigItem(
		keyName = KEY_FISHING_SPOT_STYLE,
		name = "Fishing spot style",
		description = "Choose the appearance of most fishing spots. Bubbles are the easiest to see on top of 117 HD's water style.",
		section = miscellaneousSettings
	)
	default FishingSpotStyle fishingSpotStyle() {
		return FishingSpotStyle.HD;
	}

	String KEY_COLOR_FILTER = "colorFilter";
	@ConfigItem(
		keyName = KEY_COLOR_FILTER,
		name = "Color Filter",
		description = "Apply a color filter to the game as a post-processing effect.",
		section = miscellaneousSettings
	)
	default ColorFilter colorFilter() {
		return ColorFilter.NONE;
	}

	String KEY_REMOVE_VERTEX_SNAPPING = "removeVertexSnapping";
	@ConfigItem(
		keyName = KEY_REMOVE_VERTEX_SNAPPING,
		name = "Remove vertex snapping",
		description =
			"Removes vertex snapping from most animations.<br>" +
			"Most animations are barely affected by this, and it only has an effect if the animation smoothing plugin is turned off.<br>" +
			"To see quite clearly what impact this option has, a good example is the godsword idle animation.",
		section = miscellaneousSettings
	)
	default boolean removeVertexSnapping() {
		return true;
	}

	String KEY_FILL_GAPS_IN_TERRAIN = "fillGapsInTerrain";
	@ConfigItem(
		keyName = KEY_FILL_GAPS_IN_TERRAIN,
		name = "Fill gaps in terrain",
		description = "Attempt to patch all holes in the ground, such as around trapdoors and ladders.",
		section = miscellaneousSettings
	)
	default boolean fillGapsInTerrain() {
		return true;
	}

	String KEY_FLAT_SHADING = "flatShading";
	@ConfigItem(
		keyName = KEY_FLAT_SHADING,
		name = "Flat shading",
		description = "Gives a more low-poly look to the game.",
		section = miscellaneousSettings
	)
	default boolean flatShading() {
		return false;
	}


	/*====== Experimental settings ======*/

	@ConfigSection(
		name = "Experimental",
		description = "Experimental features - if you're experiencing issues you should consider disabling these",
		position = 5,
		closedByDefault = true
	)
	String experimentalSettings = "experimentalSettings";

	String KEY_FASTER_MODEL_HASHING = "experimentalFasterModelHashing";
	@ConfigItem(
		keyName = KEY_FASTER_MODEL_HASHING,
		name = "Use faster model hashing",
		description = "Should increase performance at the expense of potential graphical issues.",
		section = experimentalSettings
	)
	default boolean fasterModelHashing() {
		return true;
	}

	String KEY_PRESERVE_VANILLA_NORMALS = "experimentalPreserveVanillaNormals";
	@ConfigItem(
		keyName = KEY_PRESERVE_VANILLA_NORMALS,
		name = "Preserve vanilla normals",
		description = "Originally, 117 HD would respect vanilla normals, but these are often less accurate.",
		section = experimentalSettings
	)
	default boolean preserveVanillaNormals() {
		return false;
	}

	String KEY_SHADING_MODE = "experimentalShadingMode";
	@ConfigItem(
		keyName = KEY_SHADING_MODE,
		name = "Shading mode",
		description =
			"If you prefer playing without shadows, maybe you'll prefer vanilla shading or no shading as well.<br>" +
			"Keep in mind, with vanilla shading used alongside shadows, you can end up with double shading.",
		section = experimentalSettings
	)
	default ShadingMode shadingMode() {
		return ShadingMode.DEFAULT;
	}

	String KEY_DECOUPLE_WATER_FROM_SKY_COLOR = "experimentalDecoupleWaterFromSkyColor";
	@ConfigItem(
		keyName = KEY_DECOUPLE_WATER_FROM_SKY_COLOR,
		name = "Decouple water from sky color",
		description = "Some people prefer the water staying blue even with a different sky color active.",
		section = experimentalSettings
	)
	default boolean decoupleSkyAndWaterColor() {
		return false;
	}

	String KEY_HIDE_UNRELATED_AREAS = "hideUnrelatedAreas";
	@ConfigItem(
		keyName = KEY_HIDE_UNRELATED_AREAS,
		name = "Hide unrelated areas",
		description = "Hide unrelated areas which you shouldn't see from your current position.",
		section = experimentalSettings
	)
	default boolean hideUnrelatedAreas() {
		return true;
	}

	String KEY_WIREFRAME = "wireframe";
	@ConfigItem(
		keyName = KEY_WIREFRAME,
		name = "Wireframe",
		description = "Show the edges of individual triangles in the scene.",
		section = experimentalSettings
	)
	default boolean wireframe() {
		return false;
	}

	String KEY_ASYNC_UI_COPY = "experimentalAsyncUICopy";
	@ConfigItem(
		keyName = KEY_ASYNC_UI_COPY,
		name = "Perform UI copy asynchronously",
		description = "Slightly improves performance by delaying the UI by one frame.",
		section = experimentalSettings
	)
	default boolean asyncUICopy() {
		return false;
	}

	/*====== Internal settings ======*/

	@ConfigItem(keyName = "pluginUpdateMessage", hidden = true, name = "", description = "")
	void setPluginUpdateMessage(int version);
	@ConfigItem(keyName = "pluginUpdateMessage", hidden = true, name = "", description = "")
	default int getPluginUpdateMessage() {
		return 0;
	}
}
