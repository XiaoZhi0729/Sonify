import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { colors, FONT } from "../theme";
import { Eyebrow, FadeUp, SceneShell } from "../components/Primitives";

const Waveform: React.FC = () => {
  const frame = useCurrentFrame();
  const bars = 33;
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 9, height: 90 }}>
      {Array.from({ length: bars }).map((_, i) => {
        const dist = Math.abs(i - (bars - 1) / 2) / ((bars - 1) / 2);
        const env = 1 - dist * 0.55;
        const h =
          (22 + Math.sin(frame * 0.18 + i * 0.55) * 18 + 18) * env + 12;
        return (
          <div
            key={i}
            style={{
              width: 7,
              height: Math.max(8, h),
              borderRadius: 4,
              background:
                i % 2 === 0
                  ? "linear-gradient(180deg,#FF6B5B,#FF3B30)"
                  : "linear-gradient(180deg,#FFB340,#FF7A4D)",
              opacity: 0.85,
            }}
          />
        );
      })}
    </div>
  );
};

export const Scene2Slogan: React.FC<{ durationInFrames: number }> = ({
  durationInFrames,
}) => {
  return (
    <SceneShell durationInFrames={durationInFrames}>
      <AbsoluteFill
        style={{ alignItems: "center", justifyContent: "center", flexDirection: "column" }}
      >
        <FadeUp delay={2}>
          <Eyebrow text="SONIFY" center />
        </FadeUp>

        <FadeUp delay={10}>
          <div
            style={{
              fontFamily: FONT,
              fontSize: 92,
              fontWeight: 800,
              color: colors.ink,
              lineHeight: 1.22,
              letterSpacing: -2,
              textAlign: "center",
            }}
          >
            让封面的色彩
          </div>
        </FadeUp>

        <FadeUp delay={24}>
          <div
            style={{
              fontFamily: FONT,
              fontSize: 92,
              fontWeight: 800,
              lineHeight: 1.22,
              letterSpacing: -2,
              textAlign: "center",
              background: "linear-gradient(90deg,#FF3B30,#FF8A3D)",
              WebkitBackgroundClip: "text",
              backgroundClip: "text",
              color: "transparent",
            }}
          >
            随歌声流动
          </div>
        </FadeUp>

        <FadeUp delay={40} style={{ marginTop: 56 }}>
          <Waveform />
        </FadeUp>
      </AbsoluteFill>
    </SceneShell>
  );
};
