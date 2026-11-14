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
out float fxTail;

void main() {
    texCoord0 = UV0;
    vertexColor = Color;
    // FxStroke packs the stroke's own numbers into the two short pairs: seed and erosion in UV1, head and length in UV2.
    fxParams = vec4(float(UV1.x) / 10000.0, float(UV2.x) / 10000.0, float(UV1.y) / 10000.0, float(UV2.y) / 100.0);
    // where the stroke now begins (0..1): a stroke that has left its start travels as a dash
    fxTail = Normal.x;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
