#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform vec4 LightPosition0;
uniform vec4 LightPosition1;
uniform vec4 LightColor0;
uniform vec4 LightColor1;
uniform float FogStart;
uniform float FogEnd;
in vec3 viewPosition;
in vec3 viewNormal;
in vec2 texCoord;
in vec4 vertexColor;
out vec4 fragColor;

vec3 illuminate(vec4 source, vec4 tint, vec3 normal) {
    vec3 delta = source.xyz - viewPosition;
    float distance = length(delta);
    float radial = max(0.0, 1.0 - distance / max(source.w, 0.001));
    float lambert = max(0.0, dot(normal, delta / max(distance, 0.0001)));
    return tint.rgb * tint.a * lambert * radial * radial;
}
void main() {
    vec4 surface = texture(Sampler0, texCoord) * vertexColor * ColorModulator;
    if (surface.a < 0.1) discard;
    vec3 normal = normalize(viewNormal);
    vec3 lighting = illuminate(LightPosition0, LightColor0, normal) + illuminate(LightPosition1, LightColor1, normal);
    float fog = 1.0 - smoothstep(FogStart, max(FogStart + 0.001, FogEnd), length(viewPosition));
    fragColor = vec4(surface.rgb * lighting * fog, surface.a);
}
