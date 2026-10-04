import React from "react";
import { Img, staticFile, useCurrentFrame } from "remotion";

// 手机外框
export const PhoneFrame: React.FC<{
  src: string; // public 下相对路径，如 "shots/home_phone.jpg"
  height?: number;
  float?: boolean;
  style?: React.CSSProperties;
}> = ({ src, height = 840, float = true, style }) => {
  const frame = useCurrentFrame();
  const fy = float ? Math.sin(frame * 0.05) * 9 : 0;
  const fr = float ? Math.sin(frame * 0.05) * 0.45 : 0;

  return (
    <div
      style={{
        transform: `translateY(${fy}px) rotate(${fr}deg)`,
        ...style,
      }}
    >
      <div
        style={{
          display: "inline-block",
          background: "linear-gradient(155deg,#33333A 0%,#101013 55%,#050506 100%)",
          padding: 13,
          borderRadius: 58,
          boxShadow:
            "0 60px 110px -30px rgba(25,25,50,0.45), 0 30px 60px -35px rgba(255,59,48,0.35), inset 0 1px 1px rgba(255,255,255,0.18)",
        }}
      >
        <Img
          src={staticFile(src)}
          style={{
            display: "block",
            height,
            width: "auto",
            borderRadius: 46,
          }}
        />
      </div>
    </div>
  );
};

// 平板 / 横屏设备外框
export const TabletFrame: React.FC<{
  src: string;
  width?: number;
  style?: React.CSSProperties;
}> = ({ src, width = 1180, style }) => {
  return (
    <div style={style}>
      <div
        style={{
          display: "inline-block",
          background: "linear-gradient(160deg,#2E2E34,#0A0A0C)",
          padding: 14,
          borderRadius: 30,
          boxShadow:
            "0 60px 120px -30px rgba(25,25,50,0.5), 0 25px 55px -30px rgba(90,120,250,0.3), inset 0 1px 1px rgba(255,255,255,0.16)",
          position: "relative",
        }}
      >
        <Img
          src={staticFile(src)}
          style={{ display: "block", width, height: "auto", borderRadius: 18 }}
        />
        {/* 前置摄像头小孔 */}
        <div
          style={{
            position: "absolute",
            top: "50%",
            right: 5,
            transform: "translateY(-50%)",
            width: 6,
            height: 6,
            borderRadius: "50%",
            background: "rgba(255,255,255,0.25)",
          }}
        />
      </div>
    </div>
  );
};
