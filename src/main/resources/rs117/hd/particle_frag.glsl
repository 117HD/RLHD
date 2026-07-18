#version 330

#include <uniforms/global.glsl>
#include GLOBAL_PARTICLE_AMBIENT_LIGHT
#include <utils/fog.glsl>

uniform sampler2DArray uParticleTexture;

in vec4 vColor;
in vec2 vUV;
in float vLayer;
in vec3 vFlipbook;
in float vUseSceneAmbientLight;
in vec3 vWorldPos;

layout (location = 0) out vec4 outColor;

void main() {
	vec2 uv = vUV;
	float cols = vFlipbook.x;
	float rows = vFlipbook.y;
	float frameVal = vFlipbook.z;
	if (cols > 0.0 && rows > 0.0) {
		float numFrames = cols * rows;
		float frame = frameVal >= 1.0 ? floor(frameVal - 1.0) : floor(frameVal * numFrames);
		frame = clamp(frame, 0.0, numFrames - 1.0);
		float col = mod(frame, cols);
		float row = floor(frame / cols);
		uv = (uv + vec2(col, row)) / vec2(cols, rows);
	}
	vec4 tex = texture(uParticleTexture, vec3(uv, vLayer));
	if (tex.a <= 0.004) {
		discard;
	}
	tex.rgb *= tex.a;
	vec4 particleColor = vec4(tex.rgb * vColor.rgb, tex.a * vColor.a);
	if (particleColor.a <= 0.004) {
		discard;
	}

	#if GLOBAL_PARTICLE_AMBIENT_LIGHT
		if (vUseSceneAmbientLight != 0.0) {
			vec3 ambientLight = ambientColor * ambientStrength;
			particleColor.rgb *= mix(vec3(1.0), ambientLight, 0.35);
		}
	#endif

	if (!underwaterEnvironment) {
		float dist = distance(vWorldPos, cameraPos);
		float groundFog = 1.0 - clamp((vWorldPos.y - groundFogStart) / (groundFogEnd - groundFogStart), 0.0, 1.0);
		groundFog = mix(0.0, groundFogOpacity, groundFog);
		groundFog *= clamp(dist / 1500.0, 0.0, 1.0);
		float fogAmount = calculateFogAmount(vWorldPos);
		float combinedFog = 1.0 - (1.0 - fogAmount) * (1.0 - groundFog);
		vec3 foggedRgb = mix(particleColor.rgb / max(particleColor.a, 1e-4), fogColor, combinedFog);
		particleColor.rgb = foggedRgb * particleColor.a;
	}

	vec3 rgb = particleColor.rgb / max(particleColor.a, 1e-4);
	rgb = pow(rgb, vec3(gammaCorrection));
	particleColor.rgb = rgb * particleColor.a;
	outColor = particleColor;
}
