import React from "react";
import { AbsoluteFill } from "remotion";
import { colors, FONT } from "../theme";
import { SceneShell, FadeUp } from "../components/Primitives";
import { TabletFrame } from "../components/DeviceFrame";

export const Scene7Tablet: React.FC<{ durationInFrames: number }> = ({
  durationInFrames,
}) => {
  return (
    <SceneShell durationInFrames={durationInFrames}>
      <AbsoluteFill style={{ alignItems: "center" }}>
        <div style={{ position: "absolute", top: 118, textAlign: "center" }}>
          <FadeUp delay={4}>
            <div style={{ fontFamily: FONT, fontSize: 27, fontWeight: 700, color: colors.red, letterSpacing: 3 }}>
              双形态适配
            </div>
          </FadeUp>
          <FadeUp delay={14}>
            <div style={{ fontFamily: FONT, fontSize: 62, fontWeight: 800, color: colors.ink, marginTop: 16, letterSpacing: -1 }}>
              手机，平板，一样出色
            </div>
          </FadeUp>
          <FadeUp delay={26}>
            <div style={{ fontFamily: FONT, fontSize: 28, color: colors.inkSoft, marginTop: 18 }}>
              平板横屏分栏布局，大屏更尽兴
            </div>
          </FadeUp>
        </div>

        <div style={{ position: "absolute", top: 320 }}>
          <FadeUp delay={30} y={56}>
            <TabletFrame src="shots/home_tablet.jpg" width={900} />
          </FadeUp>
        </div>
      </AbsoluteFill>
    </SceneShell>
  );
};
