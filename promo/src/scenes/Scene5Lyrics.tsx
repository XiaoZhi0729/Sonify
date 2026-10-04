import React from "react";
import { AbsoluteFill, interpolate, spring, useCurrentFrame } from "remotion";
import { FPS } from "../theme";
import { SceneShell, Eyebrow, Title, Point, FadeUp } from "../components/Primitives";
import { PhoneFrame } from "../components/DeviceFrame";

export const Scene5Lyrics: React.FC<{ durationInFrames: number }> = ({
  durationInFrames,
}) => {
  const frame = useCurrentFrame();
  const slide = spring({ frame, fps: FPS, config: { damping: 200, mass: 1, stiffness: 85 } });
  const x = interpolate(slide, [0, 1], [160, 0]);

  return (
    <SceneShell durationInFrames={durationInFrames}>
      <AbsoluteFill>
        <div style={{ position: "absolute", left: 150, top: "50%", transform: "translateY(-50%)", width: 660 }}>
          <FadeUp delay={6}><Eyebrow text="逐行歌词 · 支持译文" /></FadeUp>
          <FadeUp delay={16}><Title size={72}>唱到哪，<br />亮到哪</Title></FadeUp>
          <div style={{ marginTop: 44 }}>
            <FadeUp delay={30}><Point text="逐行高亮，跟唱不迷路" /></FadeUp>
            <FadeUp delay={40}><Point text="外文歌词，支持译文显示" /></FadeUp>
            <FadeUp delay={50}><Point text="状态栏歌词（需兼容 Hook 环境）" /></FadeUp>
          </div>
        </div>

        <div style={{ position: "absolute", right: 150, top: "50%", transform: `translateY(-50%) translateX(${x}px)`, opacity: slide }}>
          <PhoneFrame src="shots/player_lyrics.jpg" height={846} />
        </div>
      </AbsoluteFill>
    </SceneShell>
  );
};
