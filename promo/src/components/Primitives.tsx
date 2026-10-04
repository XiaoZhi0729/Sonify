import React from "react";
import {
  AbsoluteFill,
  interpolate,
  spring,
  useCurrentFrame,
} from "remotion";
import { FPS, TRANSITION, colors, FONT } from "../theme";

// 入场：淡入 + 上移
export const FadeUp: React.FC<
  React.PropsWithChildren<{
    delay?: number;
    y?: number;
    style?: React.CSSProperties;
  }>
> = ({ delay = 0, y = 34, children, style }) => {
  const frame = useCurrentFrame();
  const p = spring({
    frame: frame - delay,
    fps: FPS,
    config: { damping: 200, mass: 0.9, stiffness: 120 },
  });
  return (
    <div
      style={{
        opacity: p,
        transform: `translateY(${interpolate(p, [0, 1], [y, 0])}px)`,
        ...style,
      }}
    >
      {children}
    </div>
  );
};

// 场景外壳：统一处理进/出场交叉淡入淡出
export const SceneShell: React.FC<
  React.PropsWithChildren<{ durationInFrames: number }>
> = ({ durationInFrames, children }) => {
  const frame = useCurrentFrame();
  const enter = interpolate(frame, [0, TRANSITION], [0, 1], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const exit = interpolate(
    frame,
    [durationInFrames - TRANSITION, durationInFrames],
    [1, 0],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp" }
  );
  const opacity = Math.min(enter, exit);
  const y =
    interpolate(enter, [0, 1], [26, 0]) + interpolate(exit, [0, 1], [0, -20]);

  return (
    <AbsoluteFill style={{ opacity, transform: `translateY(${y}px)` }}>
      {children}
    </AbsoluteFill>
  );
};

// 小标签（红色 + 短横）
export const Eyebrow: React.FC<{ text: string; center?: boolean }> = ({
  text,
  center,
}) => (
  <div
    style={{
      display: "flex",
      alignItems: "center",
      justifyContent: center ? "center" : "flex-start",
      fontFamily: FONT,
      fontSize: 27,
      fontWeight: 700,
      color: colors.red,
      letterSpacing: 3,
      marginBottom: 22,
    }}
  >
    <div
      style={{
        width: 42,
        height: 6,
        borderRadius: 3,
        background: colors.red,
        marginRight: 16,
      }}
    />
    {text}
  </div>
);

export const Title: React.FC<
  React.PropsWithChildren<{ size?: number; center?: boolean; style?: React.CSSProperties }>
> = ({ children, size = 66, center, style }) => (
  <div
    style={{
      fontFamily: FONT,
      fontSize: size,
      fontWeight: 800,
      color: colors.ink,
      lineHeight: 1.18,
      letterSpacing: -1,
      textAlign: center ? "center" : "left",
      ...style,
    }}
  >
    {children}
  </div>
);

const CheckDot: React.FC = () => (
  <div
    style={{
      width: 28,
      height: 28,
      borderRadius: "50%",
      background: "rgba(255,59,48,0.13)",
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      flexShrink: 0,
    }}
  >
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none">
      <path
        d="M5 12.5l4.5 4.5L19 7.5"
        stroke={colors.red}
        strokeWidth="3.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  </div>
);

export const Point: React.FC<{ text: string }> = ({ text }) => (
  <div
    style={{
      display: "flex",
      alignItems: "center",
      gap: 15,
      marginBottom: 22,
      fontFamily: FONT,
      fontSize: 30,
      color: colors.inkSoft,
      fontWeight: 500,
    }}
  >
    <CheckDot />
    {text}
  </div>
);

// 玻璃小药丸标签
export const Pill: React.FC<React.PropsWithChildren<{ style?: React.CSSProperties }>> = ({
  children,
  style,
}) => (
  <div
    style={{
      display: "inline-flex",
      alignItems: "center",
      gap: 10,
      padding: "14px 28px",
      borderRadius: 999,
      fontFamily: FONT,
      fontSize: 26,
      fontWeight: 600,
      color: colors.ink,
      background: "rgba(255,255,255,0.6)",
      backdropFilter: "blur(20px)",
      WebkitBackdropFilter: "blur(20px)",
      border: "1px solid rgba(255,255,255,0.85)",
      boxShadow: "0 14px 34px -16px rgba(30,30,60,0.3)",
      ...style,
    }}
  >
    {children}
  </div>
);
