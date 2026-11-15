package com.example.duofoldwallpaper

object ShaderCode {

    const val DUO_SHADER = """
        uniform shader uImage;
        uniform shader uImageBlur;
        uniform vec2   uUvOffset;
        uniform vec2   uUvScale;
        uniform vec2   uPixel;
        uniform vec2   uGrad;
        uniform float  uMotion;
        uniform float  uFold;
        uniform float  uRadiusMax;
        uniform float  uBlurScale;
        uniform float  uHingePos;
        uniform float  uEyeRatio;

        // Golden angle for high-quality spiral bokeh blur (prevents pixelation)
        const int   TAPS         = 36;
        const float GOLDEN_ANGLE = 2.39996323;
        const float TWO_PI       = 6.28318531;

        float ign(vec2 fragCoord) {
            vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
            return fract(magic.z * fract(dot(fragCoord, magic.xy)));
        }

        half3 diskBlur(vec2 centerUv, float radiusPx, float rotation, vec2 uvMin, vec2 uvMax, bool useMip) {
            vec3 acc = vec3(0.0);
            float wSum = 0.0;
            for (int i = 0; i < TAPS; i++) {
                float t = (float(i) + 0.5) / float(TAPS);
                float r = sqrt(t);
                float theta = float(i) * GOLDEN_ANGLE + rotation;
                vec2 dir = vec2(cos(theta), sin(theta));
                vec2 sampleUv = clamp(centerUv + dir * (r * radiusPx) * uPixel, uvMin, uvMax);
                float w = exp(-2.2 * t);
                vec2 coord = sampleUv / uPixel;
                vec3 c = useMip ? uImageBlur.eval(coord / uBlurScale).rgb : uImage.eval(coord).rgb;
                acc += c * w;
                wSum += w;
            }
            return acc / max(wSum, 0.0001);
        }

        half4 main(vec2 p) {
            vec2 uv = uUvOffset + p * uUvScale;
            vec2 origUV = uv;

            if (uFold > 0.0001 && uv.x < uHingePos) {
                float c = cos(uFold);
                float s = sin(uFold);
                float d = uHingePos - uv.x;
                float z = d * s;
                float perspScale = uEyeRatio / (uEyeRatio + z);
                float warpedX = uHingePos - d * c * perspScale;
                uv.x = clamp(warpedX, 0.0, 1.0);
            }

            float edge = (origUV.x - uGrad.x) / (uGrad.y - uGrad.x);
            float blurG  = clamp(edge, 0.0, 1.0);
            float darkG  = clamp((edge - 0.15) / 0.85, 0.0, 1.0);
            float effect = uMotion * pow(darkG, 1.35);
            float radius = uRadiusMax * uMotion * pow(blurG, 1.35);

            vec2 aa  = uPixel * 0.5;
            vec2 cov = smoothstep(-aa, aa, uv)
                     * (1.0 - smoothstep(vec2(1.0) - aa, vec2(1.0) + aa, uv));

            vec2 sampleCoord = uv / uPixel;
            half3 color = uImage.eval(sampleCoord).rgb * cov.x * cov.y;

            if (radius > 0.5) {
                bool useMip = (uBlurScale > 1.0 && radius > 3.0);
                float rotation = ign(p) * TWO_PI;
                vec2 uvMin = vec2(0.0, 0.0);
                vec2 uvMax = vec2(1.0, 1.0);
                color = half3(diskBlur(uv, radius, rotation, uvMin, uvMax, useMip));
            }

            return half4(color * (1.0 - min(1.0, effect * 2.0)), 1.0);
        }
    """
}