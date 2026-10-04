import React from "react";
import { AbsoluteFill, Img, interpolate, spring, staticFile, useCurrentFrame } from "remotion";
import { colors, FONT, FPS } from "../theme";
import { FadeUp, Pill, SceneShell } from "../components/Primitives";

export const Scene1Logo: React.FC<{ durationInFrames: number }> = ({
  durationInFrames,
}) => {
  const frame = useCurrentFrame();
  const pop = spring({ frame, fps: FPS, config: { damping: 13, mass: 0.9, stiffness: 110 } });
  const scale = interpolate(pop, [0, 1], [0.35, 1]);
  const rotate = interpolate(pop, [0, 1], [-14, 0]);

  return (
    <SceneShell durationInFrames={durationInFrames}>
      <AbsoluteFill
        style={{ alignItems: "center", justifyContent: "center", flexDirection: "column" }}
      >
        <div
          style={{
            transform: `scale(${scale}) rotate(${rotate}deg)`,
            opacity: pop,
            marginBottom: 40,
          }}
        >
          <Img
            src={staticFile("shots/logo.jpg")}
            style={{
              width: 156,
              height: 156,
              borderRadius: 36,
              boxShadow:
                "0 40px 80px -24px rgba(255,59,48,0.6), 0 20px 40px -20px rgba(224,46,31,0.5)",
            }}
          />
        </div>

        <FadeUp delay={14}>
          <div
            style={{
              fontFamily: FONT,
              fontSize: 92,
              fontWeight: 800,
              color: colors.ink,
              letterSpacing: -2,
              lineHeight: 1,
            }}
          >
            Sonify
          </div>
        </FadeUp>

        <FadeUp delay={26} style={{ marginTop: 22 }}>
          <div style={{ fontFamily: FONT, fontSize: 34, color: colors.inkSoft, fontWeight: 500 }}>
            液态玻璃 · 音乐播放器
          </div>
        </FadeUp>

        <FadeUp delay={42} style={{ marginTop: 48 }}>
          <Pill>Android · Jetpack Compose · Media3</Pill>
        </FadeUp>
      </AbsoluteFill>
    </SceneShell>
  );
};
