/*
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
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
#version 330

#include <uniforms/global.glsl>

uniform float calibrationTimer;

#include <utils/constants.glsl>
#include <utils/color_utils.glsl>

in vec2 fUv;

out vec4 FragColor;

void main() {
    const int numDots = 3;
    const float minBrightness = .02;
    const float lineFeather = .02;
    const float dotRadius = .4;
    const float timerDotRadius = .1;
    const float timerMargin = .125;

    vec4 src = vec4(vec3(0), smoothstep(0, .05, calibrationTimer));

    vec2 uv = fUv - .5;
    uv.x *= numDots;

    float dotIndex = floor(mod(uv.x + numDots / 2. + 1, numDots + 1));
    vec2 dotUv = uv;
    dotUv.x = fract(dotUv.x + .5) - .5;
    float dot = smoothstep(dotRadius, dotRadius - lineFeather, length(dotUv));
    dot *= mix(minBrightness, 1, (dotIndex - 1) / (numDots - 1));
    dot = pow(dot, gammaCorrection);
    src.rgb += vec3(dot);

    vec2 cornerDotUv = uv + vec2(-numDots / 2., .5) + timerMargin * vec2(1, -1);
    float cornerDot = smoothstep(0, lineFeather, timerDotRadius - length(cornerDotUv));
    float angle = fract(.25 + atan(cornerDotUv.y, cornerDotUv.x) / (2 * PI));
    cornerDot *= mix(.1, 1, smoothstep(0, lineFeather, calibrationTimer - angle));
    src.rgb += vec3(cornerDot);

    FragColor = src;
}
