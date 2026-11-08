#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
out vec3 viewPosition;
out vec3 viewNormal;
out vec2 texCoord;
out vec4 vertexColor;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    viewPosition = view.xyz;
    // ModelPart has already transformed normals by the posed part's normal matrix.
    viewNormal = mat3(ModelViewMat) * Normal;
    texCoord = UV0;
    vertexColor = Color;
    gl_Position = ProjMat * view;
}
