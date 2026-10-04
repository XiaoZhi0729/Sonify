import React from "react";
import { AbsoluteFill } from "remotion";
import { colors, FONT } from "../theme";
import { SceneShell, FadeUp } from "../components/Primitives";
import { GlassCard } from "../components/GlassCard";

const IconWave = (
  <svg width="34" height="34" viewBox="0 0 24 24" fill="none">
    <path d="M4 10v4M9 6v12M14 9v6M19 4v16" stroke={colors.red} strokeWidth="2.4" strokeLinecap="round" />
  </svg>
);
const IconClock = (
  <svg width="34" height="34" viewBox="0 0 24 24" fill="none">
    <circle cx="12" cy="12" r="8" stroke={colors.red} strokeWidth="2.2" />
    <path d="M12 8v4l3 2" stroke={colors.red} strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);
const IconList = (
  <svg width="34" height="34" viewBox="0 0 24 24" fill="none">
    <path d="M8 6h12M8 12h12M8 18h12" stroke={colors.red} strokeWidth="2.2" strokeLinecap="round" />
    <circle cx="4" cy="6" r="1.4" fill={colors.red} />
    <circle cx="4" cy="12" r="1.4" fill={colors.red} />
    <circle cx="4" cy="18" r="1.4" fill={colors.red} />
  </svg>
);
const IconHeadphone = (
  <svg width="34" height="34" viewBox="0 0 24 24" fill="none">
    <path d="M4 14v-1a8 8 0 0 1 16 0v1" stroke={colors.red} strokeWidth="2.2" strokeLinecap="round" />
    <rect x="3" y="14" width="4.2" height="6.4" rx="1.8" stroke={colors.red} strokeWidth="2.2" />
    <rect x="16.8" y="14" width="4.2" height="6.4" rx="1.8" stroke={colors.red} strokeWidth="2.2" />
  </svg>
);

const FEATURES = [
  { icon: IconWave, title: "多音质切换", desc: "标准到超高音质，切换前探测，失败自动回退" },
  { icon: IconClock, title: "倍速 · 睡眠定时", desc: "0.5–2 倍速自由调节，定时自动停止" },
  { icon: IconList, title: "队列管理", desc: "播放队列拖拽编辑，最近播放随时找回" },
  { icon: IconHeadphone, title: "后台播放", desc: "Media3 稳定内核，通知栏与蓝牙线控" },
];

export const Scene6Features: React.FC<{ durationInFrames: number }> = ({
  durationInFrames,
}) => {
  return (
    <SceneShell durationInFrames={durationInFrames}>
      <AbsoluteFill style={{ alignItems: "center" }}>
        <div style={{ position: "absolute", top: 128, textAlign: "center" }}>
          <FadeUp delay={4}>
            <div style={{ fontFamily: FONT, fontSize: 27, fontWeight: 700, color: colors.red, letterSpacing: 3 }}>
              为聆听而生
            </div>
          </FadeUp>
          <FadeUp delay={14}>
            <div style={{ fontFamily: FONT, fontSize: 64, fontWeight: 800, color: colors.ink, marginTop: 16, letterSpacing: -1 }}>
              功能，一应俱全
            </div>
          </FadeUp>
        </div>

        <div style={{ position: "absolute", top: 440, display: "flex", gap: 30 }}>
          {FEATURES.map((f, i) => (
            <FadeUp key={i} delay={24 + i * 12} y={46}>
              <GlassCard style={{ width: 360, height: 330, padding: 38, display: "flex", flexDirection: "column" }}>
                <div style={{
                  width: 72, height: 72, borderRadius: 22,
                  background: "rgba(255,59,48,0.1)",
                  display: "flex", alignItems: "center", justifyContent: "center",
                  marginBottom: 26,
                }}>
                  {f.icon}
                </div>
                <div style={{ fontFamily: FONT, fontSize: 32, fontWeight: 800, color: colors.ink, marginBottom: 14 }}>
                  {f.title}
                </div>
                <div style={{ fontFamily: FONT, fontSize: 23, color: colors.inkSoft, lineHeight: 1.55 }}>
                  {f.desc}
                </div>
              </GlassCard>
            </FadeUp>
          ))}
        </div>
      </AbsoluteFill>
    </SceneShell>
  );
};
