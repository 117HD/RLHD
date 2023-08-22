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
import rs117.hd.config.Contrast;
import rs117.hd.config.DefaultSkyColor;
import rs117.hd.config.FogDepthMode;
import rs117.hd.config.MaxDynamicLights;
import rs117.hd.config.Saturation;
import rs117.hd.config.ShadowDistance;
import rs117.hd.config.ShadowMode;
import rs117.hd.config.ShadowResolution;
import rs117.hd.config.TextureResolution;
import rs117.hd.config.UIScalingMode;

import static rs117.hd.HdPlugin.MAX_DISTANCE;
import static rs117.hd.HdPlugin.MAX_FOG_DEPTH;
import static rs117.hd.HdPluginConfig.CONFIG_GROUP;

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
			"The maximum number of tiles to draw in either direction from the camera.<br>" +
			"Depending on where the scene was loaded from, you might only see as far as 16 tiles in some directions.",
		position = 1,
		section = generalSettings
	)
	default int drawDistance()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "antiAliasingMode",
		name = "Anti-Aliasing",
		description =
			"Improves jagged/shimmering edges at the cost of GPU performance.<br>" +
			"16x MSAA is highly expensive, so 8x is recommended if anti-aliasing is desired.",
		position = 2,
		section = generalSettings
	)
	default AntiAliasingMode antiAliasingMode()
	{
		return AntiAliasingMode.DISABLED;
	}

	String KEY_UI_SCALING_MODE = "uiScalingMode";
	@ConfigItem(
		keyName = KEY_UI_SCALING_MODE,
		name = "UI Scaling Mode",
		description =
			"The sampling function to use when the Stretched Mode plugin is enabled.<br>" +
			"Affects how the UI looks with non-integer scaling.",
		position = 3,
		section = generalSettings
	)
	default UIScalingMode uiScalingMode()
	{
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
		position = 4,
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
		position = 5,
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
		position = 6,
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
		position = 7,
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
		position = 8,
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
		position = 9,
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
		position = 10,
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
		position = 11,
		section = generalSettings
	)
	@Units(Units.PERCENT)
	@Range(min = -500, max = 500)
	default int saturation()
	{
		return Math.round(oldSaturationDropdown().getAmount() * 100);
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
		position = 12,
		section = generalSettings
	)
	@Units(Units.PERCENT)
	@Range(min = -500, max = 500)
	default int contrast()
	{
		return Math.round(oldContrastDropdown().getAmount() * 100);
	}
	@ConfigItem(keyName = "contrast", hidden = true, name = "", description = "")
	default Contrast oldContrastDropdown()
	{
		return Contrast.DEFAULT;
	}

	@Range(
		min = 1,
		max = 50
	)
	@ConfigItem(
		keyName = "brightness2",
		name = "Brightness",
		description = "Controls the brightness of environmental lighting.<br>" +
			"A brightness value of 20 is recommended.",
		position = 13,
		section = generalSettings
	)
	default int brightness() { return 20; }


	/*====== Lighting settings ======*/

	@ConfigSection(
		name = "Lighting",
		description = "Lighting settings",
		position = 1
	)
	String lightingSettings = "lightingSettings";

	String KEY_MAX_DYNAMIC_LIGHTS = "maxDynamicLights";
	@ConfigItem(
		keyName = KEY_MAX_DYNAMIC_LIGHTS,
		name = "Dynamic Lights",
		description =
			"The maximum number of dynamic lights visible at once.<br>" +
			"Reducing this may improve performance.",
		position = 1,
		section = lightingSettings
	)
	default MaxDynamicLights maxDynamicLights()
	{
		return MaxDynamicLights.SOME;
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
		keyName = "enableShadowTransparency",
		name = "Shadow Transparency",
		description =
			"Enables partial support for shadows that take transparency into account.",
		position = 6,
		section = lightingSettings
	)
	default boolean enableShadowTransparency()
	{
		return true;
	}

	String KEY_SHADOW_RESOLUTION = "shadowResolution";
	@ConfigItem(
		keyName = KEY_SHADOW_RESOLUTION,
		name = "Shadow Quality",
		description =
			"The resolution of the shadow map.<br>" +
			"Higher resolutions result in higher quality shadows, at the cost of GPU performance.",
		position = 7,
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
			"shadows for a larger portion of the scene, at the cost of performance.",
		position = 10,
		section = lightingSettings
	)
	default boolean expandShadowDraw()
	{
		return false;
	}

	String KEY_HIDE_FAKE_SHADOWS = "hideBakedEffects";
	@ConfigItem(
		keyName = KEY_HIDE_FAKE_SHADOWS,
		name = "Hide Fake Shadows",
		description =
			"Hide fake shadows and lighting which is often built into models by Jagex.<br>" +
			"This does not affect the hitbox of NPCs, so you can still click where the fake shadow would normally be.",
		position = 11,
		section = lightingSettings
	)
	default boolean hideFakeShadows() {
		return true;
	}

	String KEY_NORMAL_MAPPING = "normalMapping";
	@ConfigItem(
		keyName = KEY_NORMAL_MAPPING,
		name = "Normal Mapping",
		description = "Affects how light interacts with certain materials. Barely affects performance.",
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
		description = "Adds more depth to supported materials, at the cost of performance.",
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
		position = 2,
		closedByDefault = false
	)
	String environmentSettings = "environmentSettings";

	@ConfigItem(
		keyName = "fogDepthMode",
		name = "Fog Depth Mode",
		description =
			"Determines how the fog amount is controlled.<br>" +
			"'Dynamic' changes fog depth based on the area, while<br>" +
			"'Static' respects the manually defined fog depth.",
		position = 1,
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
		position = 2,
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
		position = 3,
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
			"If set to 'RuneLite Skybox', the sky color from RuneLite's Skybox plugin will be used.<br>" +
			"If set to 'Old School Black', the sky will be black and water will remain blue, but for any<br>" +
			"other option, the water color will be influenced by the sky color.",
		position = 4,
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
		position = 5,
		section = environmentSettings
	)
	default boolean overrideSky() {
		return false;
	}

	String KEY_MODEL_TEXTURES = "objectTextures";
	@ConfigItem(
		keyName = KEY_MODEL_TEXTURES,
		name = "Model Textures",
		description = "Adds textures to some models.",
		position = 6,
		section = environmentSettings
	)
	default boolean modelTextures() {
		return true;
	}

	String KEY_GROUND_TEXTURES = "groundTextures";
	@ConfigItem(
		keyName = KEY_GROUND_TEXTURES,
		name = "Ground Textures",
		description = "Adds textures to some ground tiles.",
		position = 7,
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
		position = 8,
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
		position = 9,
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
		position = 10,
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
		position = 11,
		section = environmentSettings
	)
	default boolean hdTzHaarReskin() {
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
		position = 1,
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
		position = 2,
		section = miscellaneousSettings
	)
	default boolean hdInfernalTexture()
	{
		return true;
	}

	String KEY_WINTER_THEME = "winterTheme0";
	@ConfigItem(
		keyName = KEY_WINTER_THEME,
		name = "Winter Theme",
		description = "Covers the Gielinor overworld with a layer of snow!",
		position = 3,
		section = miscellaneousSettings
	)
	default boolean winterTheme()
	{
		return false;
	}

	String KEY_LEGACY_GREY_COLORS = "reduceOverExposure";
	@ConfigItem(
		keyName = KEY_LEGACY_GREY_COLORS,
		name = "Legacy Grey Colors",
		description =
			"Previously, HD attempted to reduce over-exposure by capping the maximum color brightness,<br>" +
			"which changed white colors into dull shades of grey. This option brings back that old behaviour.",
		position = 4,
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
		position = 5,
		section = miscellaneousSettings
	)
	default boolean vanillaColorBanding() {
		return false;
	}


	/*====== Experimental settings ======*/

//	@ConfigSection(
//		name = "Experimental",
//		description = "Experimental features - if you're experiencing issues you should consider disabling these",
//		position = 5,
//		closedByDefault = true
//	)
//	String experimentalSettings = "experimentalSettings";


	/*====== Internal settings ======*/

	@ConfigItem(keyName = "pluginUpdateMessage", hidden = true, name = "", description = "")
	void setPluginUpdateMessage(int version);
	@ConfigItem(keyName = "pluginUpdateMessage", hidden = true, name = "", description = "")
	default int getPluginUpdateMessage() {
		return 0;
	}
}
