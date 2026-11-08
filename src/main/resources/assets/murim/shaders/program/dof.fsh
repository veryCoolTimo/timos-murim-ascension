#version 150
uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform vec2 InSize;
uniform vec2 Direction;
uniform float FocusDistance;
uniform float FocusRange;
uniform float MaxBlur;
uniform float Near;
uniform float Far;
in vec2 texCoord;
out vec4 fragColor;
float coc(vec2 uv) {
    float z = texture(DepthSampler, uv).r * 2.0 - 1.0;
    float depth = 2.0 * Near * Far / (Far + Near - z * (Far - Near));
    return clamp((abs(depth - FocusDistance) - FocusRange) / max(FocusRange, 0.25), 0.0, 1.0) * MaxBlur;
}
void main() {
    float radius = coc(texCoord);
    vec4 sum = texture(DiffuseSampler, texCoord);
    float weight = 1.0;
    for (int i = -4; i <= 4; ++i) {
        if (i == 0) continue;
        vec2 uv = clamp(texCoord + Direction / InSize * radius * float(i) / 4.0, vec2(0.0), vec2(1.0));
        // Sharp foreground samples must not smear into the background.
        float w = exp(-float(i*i) / 8.0) * min(1.0, (coc(uv) + 0.01) / (radius + 0.01));
        sum += texture(DiffuseSampler, uv) * w;
        weight += w;
    }
    fragColor = sum / weight;
}
