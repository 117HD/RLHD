// https://github.com/EaryChow/AgX/releases

// Source: https://iolite-engine.com/blog_posts/minimal_agx_implementation
//
// MIT License
//
// Copyright (c) 2024 Missing Deadlines (Benjamin Wrensch)
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

// All values used to derive this implementation are sourced from Troy’s initial
// AgX implementation/OCIO config file available here:
//   https://github.com/sobotka/AgX

//// Mean error^2: 3.6705141e-06
vec3 agxDefaultContrastApprox_blender(vec3 x) {
    vec3 x2 = x * x;
    vec3 x4 = x2 * x2;
    return
        + 15.5     * x4 * x2
        - 40.14    * x4 * x
        + 31.96    * x4
        - 6.868    * x2 * x
        + 0.4298   * x2
        + 0.1191   * x
        - 0.00232;
}

vec3 agx_blender(vec3 val) {
    // Values below zero cause artifacts since log2 then doesn't exist
    val = abs(val);

    const mat3 agx_mat = mat3(
        0.856627,   0.137319,  0.111898,
        0.0951212,  0.761242,  0.0767994,
        0.0482516,  0.101439,  0.811302
    );

    const float min_ev = -12.47393f;
    const float max_ev = 4.026069f;

    // Input transform (inset)
    val = agx_mat * val;

    // Log2 space encoding
    val = clamp(log2(val), min_ev, max_ev);
    val = (val - min_ev) / (max_ev - min_ev);

    // Apply sigmoid function approximation
    val = agxDefaultContrastApprox_blender(val);

    return val;
}

vec3 agxEotf_blender(vec3 val) {
    const mat3 agx_mat_inv = mat3(
          1.1271,     -0.14133,    -0.14133,
         -0.110607,    1.15782,    -0.110607,
         -0.0164939,  -0.0164939,   1.25194
    );

    // Inverse input transform (outset)
    val = agx_mat_inv * val;

    val = srgbToLinear(val);

    return val;
}
