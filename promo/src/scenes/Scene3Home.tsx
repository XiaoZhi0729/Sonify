import React from "react";
import { AbsoluteFill, interpolate, spring, useCurrentFrame } from "remotion";
import { FPS } from "../theme";
import { SceneShell, Eyebrow, Title, Point, FadeUp } from "../components/Primitives";
import { PhoneFrame } from "../components/DeviceFrame";
import { GlassCard } from "../components/GlassCard";

export const Scene3Home: React.FC<{ durationInFrames: number }> = ({
  durationInFrames,
}) => {
  const frame = useCurrentFrame();
  const slide = spring({ frame, fps: FPS, config: { damping: 200, mass: 1, stiffness: 85 } });
  const x = interpolate(slide, [0, 1], [160, 0]);

  return (
    <SceneShell durationInFrames={durationInFrames}>
      <AbsoluteFill>
        {/* 左侧文案 */}
        <div style={{ position: "absolute", left: 150, top: "50%", transform: "translateY(-50%)", width: 660 }}>
          <FadeUp delay={6}><Eyebrow text="在线曲库 × 本地音乐" /></FadeUp>
          <FadeUp delay={16}><Title size={72}>想听的，<br />都在这里</Title></FadeUp>
          <div style={{ marginTop: 44 }}>
            <FadeUp delay={30}><Point text="酷狗音源搜索 · 精选歌单" /></FadeUp>
            <FadeUp delay={40}><Point text="每日推荐，常听常新" /></FadeUp>
            <FadeUp delay={50}><Point text="本地资料库，一键扫描" /></FadeUp>
          </div>
        </div>

        {/* 右侧手机 */}
        <div style={{ position: "absolute", right: 150, top: "50%", transform: `translateY(-50%) translateX(${x}px)`, opacity: slide }}>
          <PhoneFrame src="shots/home_phone.jpg" height={846} />
          <FadeUp delay={58}>
            <GlassCard style={{ position: "absolute", left: -78, bottom: 96, padding: "20px 26px", borderRadius: 24 }}>
              <div style={{ fontFamily: "inherit", fontSize: 21, color: "#5C606D" }}>继续收听</div>
              <div style={{ fontSize: 28, fontWeight: 800, color: "#16171D", marginTop: 2 }}>小喋日和</div>
            </GlassCard>
          </FadeUp>
        </div>
      </AbsoluteFill>
    </SceneShell>
  );
};
