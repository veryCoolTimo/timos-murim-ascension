#version 150
uniform sampler2D DiffuseSampler;
uniform float Strength;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    float envelope = clamp(Strength, 0.0, 1.0);
    vec4 centre = texture(DiffuseSampler, texCoord);
    vec2 ghostUv = clamp(texCoord + vec2(0.012 * envelope, 0.0), vec2(0.0), vec2(1.0));
    vec4 ghost = texture(DiffuseSampler, ghostUv);
    fragColor = vec4(mix(centre.rgb, ghost.rgb, 0.45 * envelope), centre.a);
}
