// https://github.com/EaryChow/AgX/releases

#include <utils/color_utils.glsl>

float middleGray = 0.18;
float minExposure = -10;
float maxExposure = 15;
float mixPercent = 40;

const float normalized_log2_minimum = -10;
const float normalized_log2_maximum = 6.5;
const float x_pivot = abs(normalized_log2_minimum) / (normalized_log2_maximum - normalized_log2_minimum);
const float y_pivot = pow(0.18, 1.0 / 2.4);

const float fulcrum_input = x_pivot;
const float fulcrum_output = y_pivot;
const float fulcrum_slope = 2.4;
const float exponent_toe = 1.5;
const float exponent_shoulder = 1.5;

const mat3 inset_matrix = transpose(mat3(
    0.85662715628877917, 0.09512124540253490, 0.04825159830868580,
    0.13731897228355167, 0.76124198700908063, 0.10143904070736748,
    0.11189820804517953, 0.07679941456251757, 0.81130237739230304
));

const mat3 outset_matrix = transpose(mat3(
    1.127100569630119953, -0.110606638578260655, -0.016493931051859020,
    -0.141329754421353371, 1.157823685473212683, -0.016493931051859034,
    -0.141329754421353260, -0.110606638578260585, 1.251936392999613901
));

const mat3 bt2020_id65_to_xyz_id65 = transpose(mat3(
    0.6369535067850740, 0.1446191846692331, 0.1688558539228734,
    0.2626983389565560, 0.6780087657728165, 0.0592928952706273,
    0.0000000000000000, 0.0280731358475570, 1.0608272349505707
));
const mat3 xyz_id65_to_bt2020_id65 = transpose(mat3(
    1.7166634277958805, -0.3556733197301399, -0.2533680878902478,
    -0.6666738361988869, 1.6164557398246981, 0.0157682970961337,
    0.0176424817849772, -0.0427769763827532, 0.9422432810184308
));
const mat3 e_gamut_to_xyz_id65 = transpose(mat3(
    0.7053968501, 0.1640413283, 0.08101774865,
    0.2801307241, 0.8202066415, -0.1003373656,
    -0.1037815116, -0.07290725703, 1.265746519
));
const mat3 xyz_id65_to_e_gamut = transpose(mat3(
    1.52505277, -0.3159135109, -0.1226582646,
    -0.50915256, 1.333327409, 0.1382843651,
    0.09571534537, 0.05089744387, 0.7879557705
));
const mat3 xyz_id65_to_bt709_i65 = transpose(mat3(
    3.24100323297635872776822907326277, -1.53739896948878551619088739244035, -0.49861588199636291962590917137277,
    -0.96922425220251640087809619217296, 1.87592998369517593992839010752505, 0.04155422634008471699518239006466,
    0.05563941985197547179797794569822, -0.20401120612390993835916219723003, 1.05714897718753331190555400098674
));
const mat3 rgb_bt709_to_bt2020 = transpose(mat3(
    0.627403895934698919, 0.329283038377883697, 0.043313065687417301,
    0.069097289358232103, 0.919540395075458483, 0.011362315566309171,
    0.016391438875150228, 0.088013307877225749, 0.895595253247623901
));
const vec3 luminance_coeffs = vec3(0.2589235355689848, 0.6104985346066525, 0.13057792982436284);

float amax(vec3 c) {
    return max(max(c.r, c.g), c.b);
}

float amin(vec3 c) {
    return min(min(c.r, c.g), c.b);
}

vec3 lusRGB_compensate_low_side(vec3 rgb) {
    float Y = dot(rgb_bt709_to_bt2020 * rgb, luminance_coeffs);

    // Calculate luminance of the opponent color, and use it to compensate for negative luminance values
    vec3 inverse_rgb = amax(rgb) - rgb;
    float max_inverse = amax(inverse_rgb);
    float Y_inverse_RGB = dot(rgb_bt709_to_bt2020 * inverse_rgb, luminance_coeffs);
    float y_compensate_negative = max_inverse - Y_inverse_RGB + Y;
    Y = mix(y_compensate_negative, Y, clamp(pow(Y, 0.08), 0, 1));
    // the lerp was because unlike in the Rec.2020 version, if we use the compensate_negative value as-is the Rec.2020-
    // green will be offset upwards too much, so lerp it to limit the compensate_negative to small values

    // Offset the input tristimulus such that there are no negatives
    float min_rgb = amin(rgb);
    float offset = max(-min_rgb, 0.0);
    vec3 rgb_offset = rgb + offset;

    // Calculate luminance of the opponent color, and use it to compensate for negative luminance values
    vec3 inverse_rgb_offset = amax(rgb_offset) - rgb_offset;
    float max_inverse_rgb_offset = amax(inverse_rgb_offset);
    float Y_inverse_RGB_offset = dot(rgb_bt709_to_bt2020 * inverse_rgb_offset, luminance_coeffs);
    float Y_new = dot(rgb_bt709_to_bt2020 * rgb_offset, luminance_coeffs);
    float Y_new_compensate_negative = max_inverse_rgb_offset - Y_inverse_RGB_offset + Y_new;
    Y_new = mix(Y_new_compensate_negative, Y_new, clamp(pow(Y_new, 0.08), 0, 1));
    // the lerp was because unlike in the Rec.2020 version, if we use the compensate_negative value as-is the Rec.2020-
    // green will be offset upwards too much, so lerp it to limit the compensate_negative to small values

    // Compensate the intensity to match the original luminance
    float luminance_ratio = min(Y / max(Y_new, 1.e-100), 1.0);
    vec3 rgb_out = luminance_ratio * rgb_offset;
    return rgb_out;
}

vec3 lu2020_compensate_low_side(vec3 rgb) {
    // Calculate original luminance
    float Y = dot(rgb, luminance_coeffs);

    // Calculate luminance of the opponent color, and use it to compensate for negative luminance values
    vec3 inverse_rgb = amax(rgb) - rgb;
    float max_inverse = amax(inverse_rgb);
    float Y_inverse_RGB = dot(inverse_rgb, luminance_coeffs);
    float y_compensate_negative = max_inverse - Y_inverse_RGB + Y;

    // Offset the input tristimulus such that there are no negatives
    float min_rgb = amin(rgb);
    float offset = max(-min_rgb, 0.0);
    vec3 rgb_offset = rgb + offset;

    // Calculate luminance of the opponent color, and use it to compensate for negative luminance values
    vec3 inverse_rgb_offset = amax(rgb_offset) - rgb_offset;
    float max_inverse_rgb_offset = amax(inverse_rgb_offset);
    float Y_inverse_RGB_offset = dot(inverse_rgb_offset, luminance_coeffs);
    float Y_new = dot(rgb_offset, luminance_coeffs);
    Y_new = max_inverse_rgb_offset - Y_inverse_RGB_offset + Y_new;

    // Compensate the intensity to match the original luminance
    float luminance_ratio = min(y_compensate_negative / Y_new, 1.0);
    vec3 rgb_out = luminance_ratio * rgb_offset;
    return rgb_out;
}

float exp_curve(float x, float slope, float scale, float power, float x_t, float y_t) {
    float u = (slope * (x - x_t)) / scale;
    return scale * (u / pow(1.0 + pow(u, power), 1.0 / power)) + y_t;
}

vec3 calculate_sigmoid(vec3 x_in, vec2 pivots, float slope, vec2 powers) {
    float px = pivots.x;
    float py = pivots.y;
    float pt = powers.x;
    float ps = powers.y;

    float toe_scale = -pow(
        pow(slope * px, -pt) *
        (pow(slope * px / py, pt) - 1.0),
        -1.0 / pt
    );

    // Shoulder scale
    float shoulder_scale = pow(
        pow(slope * (1 - px), -ps) *
        (pow((slope * (1 - px)) / (1 - py), ps) - 1.0),
        -1.0 / ps
    );

    // Linear intercept
    float intercept = py - slope * px;

    // Apply component-wise
    vec3 result;
    for (int i = 0; i < 3; ++i) {
        float x = x_in[i];
        if (x < px) {
            result[i] = exp_curve(x, slope, toe_scale, pt, px, py);
        } else if (x <= px) {
            result[i] = slope * x + intercept;
        } else {
            result[i] = exp_curve(x, slope, shoulder_scale, ps, px, py);
        }
    }
    return result;
}

vec3 apply_sigmoid(vec3 x) {
//    vec3 x2 = x * x;
//    vec3 x4 = x2 * x2;
//    return
//        + 15.5     * x4 * x2
//        - 40.14    * x4 * x
//        + 31.96    * x4
//        - 6.868    * x2 * x
//        + 0.4298   * x2
//        + 0.1191   * x
//        - 0.00232;

    return calculate_sigmoid(
        x,
        vec2(fulcrum_input, fulcrum_output),
        fulcrum_slope,
        vec2(exponent_toe, exponent_shoulder)
    );
}

float lerp_chromaticity_angle(float h1, float h2, float t) {
    float delta = h2 - h1;
    if (delta > 0.5)
        h2 -= 1.0; // Go the reverse direction
    else if (delta < -0.5)
        h2 += 1.0; // Go the reverse direction
    float lerped = h1 + t * (h2 - h1);
    return fract(lerped);
}

vec3 AgX_Base_Rec2020(vec3 col) {
    // apply lower guard rail
    col = lu2020_compensate_low_side(col);

    // apply inset matrix
    col = inset_matrix * col;

    // record current chromaticity angle
    vec3 pre_form_hsv = srgbToHsv(col);

    // apply Log2 curve to prepare for sigmoid
    col = log2(col / middleGray);
    col = (col - minExposure) / (maxExposure - minExposure);

    // apply sigmoid
    col = apply_sigmoid(col);

    // Linearize
    col = pow(col, vec3(2.4));

    // record post-sigmoid chroma angle
    col = srgbToHsv(col);

    // mix pre-formation chroma angle with post formation chroma angle.
    col[0] = lerp_chromaticity_angle(pre_form_hsv[0], col[0], mixPercent / 100);

    col = hsvToSrgb(col);

    // apply outset to make the result more chroma-laden
    col = outset_matrix * col;

    return col;
}

vec3 agx_custom(vec3 c) {
    c = max(c, vec3(0));

    // Decode input primaries from E-Gamut to Rec. 2020
    c = e_gamut_to_xyz_id65 * c;

    c = xyz_id65_to_bt2020_id65 * c;

    // Form the image in Rec. 2020
    c = AgX_Base_Rec2020(c);

    // Convert formed image to Rec. 709
    c = bt2020_id65_to_xyz_id65 * c;
    c = xyz_id65_to_bt709_i65 * c;

    // Apply sRGB's lower Guard Rail
    c = lusRGB_compensate_low_side(c);

    return c;
}
