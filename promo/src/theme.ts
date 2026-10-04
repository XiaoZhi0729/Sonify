// 全局常量与主题
export const FPS = 30;
export const WIDTH = 1920;
export const HEIGHT = 1080;
export const TRANSITION = 12; // 场景交叉淡入淡出帧数

export const colors = {
  bg: "#F6F7FB",
  ink: "#16171D",
  inkSoft: "#5C606D",
  red: "#FF3B30",
  redDeep: "#E02E1F",
  pink: "#FF7A8A",
  orange: "#FFB340",
  blue: "#5AC8FA",
  violet: "#A78BFA",
};

export const FONT =
  '"Microsoft YaHei UI","Microsoft YaHei","PingFang SC","Noto Sans SC",-apple-system,sans-serif';

// 各场景主体时长（帧），合计 1200 = 40s
export const SEGMENTS = [120, 120, 180, 180, 150, 150, 150, 150];

export const segmentFrom = (i: number): number =>
  SEGMENTS.slice(0, i).reduce((a, b) => a + b, 0);
