import React from "react";

export const glassStyle: React.CSSProperties = {
  background: "rgba(255,255,255,0.55)",
  backdropFilter: "blur(26px)",
  WebkitBackdropFilter: "blur(26px)",
  border: "1px solid rgba(255,255,255,0.8)",
  boxShadow:
    "0 24px 60px -24px rgba(30,30,60,0.28), inset 0 1px 0 rgba(255,255,255,0.95)",
};

export const GlassCard: React.FC<
  React.PropsWithChildren<{ style?: React.CSSProperties; radius?: number }>
> = ({ children, style, radius = 28 }) => {
  return (
    <div style={{ ...glassStyle, borderRadius: radius, ...style }}>
      {children}
    </div>
  );
};
