#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 texCoord0;
out vec4 vertexColor;
out vec4 fxParams;
out float fxTaper;

void main() {
    texCoord0 = UV0;
    vertexColor = Color;
    // FxDust packs one puff's own numbers into the two short pairs: seed and erosion in UV1, roll (radians) and kind in UV2.
    fxParams = vec4(float(UV1.x) / 10000.0, float(UV1.y) / 10000.0, float(UV2.x) / 1000.0, float(UV2.y));
    // tail: how pointed the puff ends on its trailing side (0 round .. 1 a torn flame-like tip), untransformed
    fxTaper = Normal.x;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
