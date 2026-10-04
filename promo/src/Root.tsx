import React from "react";
import { Composition } from "remotion";
import { Video } from "./Video";
import { FPS, WIDTH, HEIGHT } from "./theme";

export const RemotionRoot: React.FC = () => {
  return (
    <>
      <Composition
        id="SonifyPromo"
        component={Video}
        durationInFrames={1200}
        fps={FPS}
        width={WIDTH}
        height={HEIGHT}
      />
    </>
  );
};
