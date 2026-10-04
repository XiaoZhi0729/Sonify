import React from "react";
import { AbsoluteFill, Img, interpolate, spring, staticFile, useCurrentFrame } from "remotion";
import { colors, FONT, FPS } from "../theme";
import { FadeUp, Pill, SceneShell } from "../components/Primitives";

export const Scene8Outro: React.FC<{ durationInFrames: number }> = ({
  durationInFrames,
}) => {
  const frame = useCurrentFrame();
  const pop = spring({ frame, fps: FPS, config: { damping: 13, mass: 0.9, stiffness: 110 } });
  const scale = interpolate(pop, [0, 1], [0.4, 1]);

  return (
    <SceneShell durationInFrames={durationInFrames}>
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", flexDirection: "column" }}>
        <div style={{ transform: `scale(${scale})`, opacity: pop, marginBottom: 34 }}>
          <Img
            src={staticFile("shots/logo.jpg")}
            style={{
              width: 128, height: 128, borderRadius: 30,
              boxShadow: "0 34px 70px -22px rgba(255,59,48,0.6)",
            }}
          />
        </div>

        <FadeUp delay={12}>
          <div style={{ fontFamily: FONT, fontSize: 84, fontWeight: 800, color: colors.ink, letterSpacing: -2, lineHeight: 1 }}>
            Sonify
          </div>
        </FadeUp>

        <FadeUp delay={26} style={{ marginTop: 44 }}>
          <Pill style={{ fontSize: 28, padding: "18px 36px" }}>
            Android 6.0+　·　GitHub 搜索 Sonify 立即下载
          </Pill>
        </FadeUp>

        <FadeUp delay={42} style={{ marginTop: 40 }}>
          <div style={{ fontFamily: FONT, fontSize: 23, color: colors.inkSoft }}>
            开源 GPL-3.0 · 仅供学习交流，请支持正版音乐
          </div>
        </FadeUp>
      </AbsoluteFill>
    </SceneShell>
  );
};
