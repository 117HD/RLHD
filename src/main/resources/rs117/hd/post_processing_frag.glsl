#version 330

#include <uniforms/global.glsl>

#include <utils/color_utils.glsl>
#include <utils/agx_custom.glsl>
#include <utils/agx.glsl>
#include <utils/agx_blender.glsl>
#include <utils/agx_alt.glsl>
#include <utils/pbr_neutral.glsl>
#include <utils/tone_map_hue_preserving.glsl>
#include <utils/tone_mapping_misc.glsl>

uniform sampler2D uniTexture;

in vec2 fUv;

out vec4 FragColor;

void main() {
    vec3 c = texture(uniTexture, fUv).rgb;

    switch (TONE_MAPPING) {
        case TONE_MAPPING_HUE_PRESERVING:
            c = tonemap_hue_preserving(c);
            break;
        case TONE_MAPPING_AGX_ORIGINAL:
            c = agx_tonemapping(c);
            break;
        case TONE_MAPPING_AGX_FITTED:
            c = agxEotf(look(agx(c)));
            break;
        case TONE_MAPPING_AGX_BLENDER:
            c = agxEotf_blender(look(agx_blender(c)));
            break;
        case TONE_MAPPING_AGX_CUSTOM:
            c = agx_custom(c);
            break;
        case TONE_MAPPING_ACES:
            c = aces(c);
            break;
        case TONE_MAPPING_ACES_FITTED:
            c = ACESFitted(c);
            break;
        case TONE_MAPPING_PBR_NEUTRAL:
            c = PBRNeutralToneMapping(c);
            break;
        case TONE_MAPPING_UNCHARTED_2:
            c = uncharted2_filmic(c);
            break;
        case TONE_MAPPING_CUSTOM:
            // Try something here
            break;
    }

    // Exposure adjustment
    c *= (1 + (COLOR_PICKER.a * 255 - 100) / 100);

    c = clamp(c, 0, 1);
    c = linearToSrgb(c);
    c = pow(c, vec3(gammaCorrection));
    FragColor = vec4(c, 1);
}
