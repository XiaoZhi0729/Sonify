import React from "react";
import { AbsoluteFill, Sequence } from "remotion";
import { Background } from "./components/Background";
import { SEGMENTS, TRANSITION, segmentFrom } from "./theme";
import { Scene1Logo } from "./scenes/Scene1Logo";
import { Scene2Slogan } from "./scenes/Scene2Slogan";
import { Scene3Home } from "./scenes/Scene3Home";
import { Scene4Player } from "./scenes/Scene4Player";
import { Scene5Lyrics } from "./scenes/Scene5Lyrics";
import { Scene6Features } from "./scenes/Scene6Features";
import { Scene7Tablet } from "./scenes/Scene7Tablet";
import { Scene8Outro } from "./scenes/Scene8Outro";

const SCENES = [
  Scene1Logo,
  Scene2Slogan,
  Scene3Home,
  Scene4Player,
  Scene5Lyrics,
  Scene6Features,
  Scene7Tablet,
  Scene8Outro,
];

export const Video: React.FC = () => {
  return (
    <AbsoluteFill>
      {/* 背景贯穿全片，光斑连续流动 */}
      <Background />

      {SCENES.map((Scene, i) => {
        const dur = SEGMENTS[i] + (i < SCENES.length - 1 ? TRANSITION : 0);
        return (
          <Sequence key={i} from={segmentFrom(i)} durationInFrames={dur}>
            <Scene durationInFrames={dur} />
          </Sequence>
        );
      })}
    </AbsoluteFill>
  );
};
