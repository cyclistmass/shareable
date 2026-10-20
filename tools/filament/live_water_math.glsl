// Gerstner position/normal adaptation of Boujie Water Shader (MIT).
// Copyright © 2023-present Zach Bernal and contributors.
// See docs/licenses/MIT-water-boujie.txt. Directions are normalised and amplitudes
// bounded for a tabletop; CPU counterparts in LiveSeaMath.kt keep boats on the surface.
float coastDistance(float2 p) {
    float2 rg = textureLod(materialParams_coast, p / 32.0 + .5, 0.0).rg;
    return (rg.x * 65280.0 + rg.y * 255.0) / 65535.0 * 8.0 - 4.0;
}
float waveFade(float distance) {
    return .2 + .8 * smoothstep(.17,1.07,distance);
}
float3 waveComponent(float2 p, float t, float2 direction, float amplitude, float wavelength,
                     float speed, float phase, inout float2 slope) {
    float frequency = 6.28318530718 / wavelength;
    float angle = frequency * dot(direction,p) - speed*t + phase;
    slope += amplitude * frequency * direction * cos(angle);
    return float3(direction.x * amplitude * .35 * cos(angle), amplitude * sin(angle),
                  direction.y * amplitude * .35 * cos(angle));
}
float3 waterWaves(float2 p, float t, out float3 normal) {
    float2 slope = float2(0);
    float3 result = waveComponent(p,t,float2(.857493,.514496),.030,4.4,.82,0.0,slope);
    result += waveComponent(p,t,float2(-.393919,.919145),.020,3.1,1.04,1.7,slope);
    result += waveComponent(p,t,float2(.242536,-.970143),.010,2.2,1.23,3.2,slope);
    normal = normalize(float3(-slope.x,1.0,-slope.y));
    return result;
}
