#version 330

#include <uniforms/global.glsl>

#include <utils/color_utils.glsl>
#include <utils/agx_custom.glsl>
#include <utils/agx.glsl>
//#include <utils/agx_blender.glsl>
#include <utils/agx_alt.glsl>
#include <utils/pbr_neutral.glsl>
#include <utils/tone_map_hue_preserving.glsl>
#include <utils/tone_mapping_misc.glsl>

uniform sampler2D uniTexture;

in vec2 fUv;

out vec4 FragColor;

void main() {
    vec3 c = texture(uniTexture, fUv).rgb;

    #if TONE_MAPPING
//        c = agx_custom(c);
//
//        c = look(c);
//
//        c = uncharted2_filmic(c);
//
//        c = PBRNeutralToneMapping(c);
//
        c = tonemap_hue_preserving(c);
//
//        c = agx_tonemapping(c);
//
//        c = agxEotf(look(agx(c)));
//
//        c = aces(c);
//        c = ACESFitted(c);
    #endif

    c *= (1 + (COLOR_PICKER.a * 255 - 100) / 100);

    c = clamp(c, 0, 1);
    c = linearToSrgb(c);
    c = pow(c, vec3(gammaCorrection));
    FragColor = vec4(c, 1);
}
