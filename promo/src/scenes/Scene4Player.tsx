import React from "react";
import { AbsoluteFill, interpolate, spring, useCurrentFrame } from "remotion";
import { FPS } from "../theme";
import { SceneShell, Eyebrow, Title, Point, FadeUp } from "../components/Primitives";
import { PhoneFrame } from "../components/DeviceFrame";
import { GlassCard } from "../components/GlassCard";

export const Scene4Player: React.FC<{ durationInFrames: number }> = ({
  durationInFrames,
}) => {
  const frame = useCurrentFrame();
  const slide = spring({ frame, fps: FPS, config: { damping: 200, mass: 1, stiffness: 85 } });
  const x = interpolate(slide, [0, 1], [-160, 0]);

  return (
    <SceneShell durationInFrames={durationInFrames}>
      <AbsoluteFill>
        {/* 左侧手机 */}
        <div style={{ position: "absolute", left: 150, top: "50%", transform: `translateY(-50%) translateX(${x}px)`, opacity: slide }}>
          <PhoneFrame src="shots/player_cover.jpg" height={846} />
          <FadeUp delay={58}>
            <GlassCard style={{ position: "absolute", right: -70, top: 150, padding: "20px 26px", borderRadius: 24 }}>
              <div style={{ fontSize: 21, color: "#5C606D" }}>实时取色</div>
              <div style={{ fontSize: 28, fontWeight: 800, color: "#16171D", marginTop: 2 }}>Liquid Glass</div>
            </GlassCard>
          </FadeUp>
        </div>

        {/* 右侧文案 */}
        <div style={{ position: "absolute", left: 1040, top: "50%", transform: "translateY(-50%)", width: 700 }}>
          <FadeUp delay={6}><Eyebrow text="液态玻璃界面" /></FadeUp>
          <FadeUp delay={16}><Title size={72}>实时磨砂，<br />光影流动</Title></FadeUp>
          <div style={{ marginTop: 44 }}>
            <FadeUp delay={30}><Point text="基于 Backdrop 的采样与模糊" /></FadeUp>
            <FadeUp delay={40}><Point text="迷你播放条 · 全屏播放页" /></FadeUp>
            <FadeUp delay={50}><Point text="封面色彩，随歌曲呼吸" /></FadeUp>
          </div>
        </div>
      </AbsoluteFill>
    </SceneShell>
  );
};
