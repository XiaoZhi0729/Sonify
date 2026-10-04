import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";

type Blob = {
  size: number;
  color: string;
  base: { x: number; y: number };
  amp: number;
  speed: number;
  phase: number;
  opacity: number;
};

const BLOBS: Blob[] = [
  { size: 820, color: "#FF6B6B", base: { x: 18, y: 12 }, amp: 70, speed: 0.22, phase: 0, opacity: 0.42 },
  { size: 720, color: "#FFB340", base: { x: 72, y: 8 }, amp: 90, speed: 0.16, phase: 2.1, opacity: 0.36 },
  { size: 880, color: "#5AC8FA", base: { x: 78, y: 62 }, amp: 80, speed: 0.19, phase: 4.0, opacity: 0.38 },
  { size: 760, color: "#C4B5FD", base: { x: 8, y: 66 }, amp: 75, speed: 0.24, phase: 1.2, opacity: 0.34 },
];

export const Background: React.FC = () => {
  const frame = useCurrentFrame();
  const t = frame;

  return (
    <AbsoluteFill
      style={{
        background:
          "linear-gradient(135deg,#FCFCFE 0%,#F3F5FA 48%,#EEF1F8 100%)",
      }}
    >
      {BLOBS.map((b, i) => {
        const dx = Math.sin(t * 0.01 * b.speed * 6 + b.phase) * b.amp;
        const dy = Math.cos(t * 0.01 * b.speed * 5 + b.phase) * b.amp;
        const left = `${b.base.x}%`;
        const top = `${b.base.y}%`;
        return (
          <div
            key={i}
            style={{
              position: "absolute",
              width: b.size,
              height: b.size,
              left,
              top,
              marginLeft: -b.size / 2,
              marginTop: -b.size / 2,
              borderRadius: "50%",
              background: b.color,
              filter: "blur(110px)",
              opacity: b.opacity,
              transform: `translate(${dx}px,${dy}px) scale(${
                1 + Math.sin(t * 0.02 + b.phase) * 0.08
              })`,
            }}
          />
        );
      })}

      {/* 细网格质感，非常淡 */}
      <AbsoluteFill
        style={{
          backgroundImage:
            "linear-gradient(rgba(20,20,40,0.025) 1px,transparent 1px),linear-gradient(90deg,rgba(20,20,40,0.025) 1px,transparent 1px)",
          backgroundSize: "64px 64px",
        }}
      />
      {/* 四周轻微白色晕影，聚焦中心 */}
      <AbsoluteFill
        style={{
          background:
            "radial-gradient(ellipse at center,transparent 55%,rgba(246,247,251,0.85) 100%)",
        }}
      />
    </AbsoluteFill>
  );
};
