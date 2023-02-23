export const generateHslPalette = (
  hue: number,
  saturation: number,
  levels: number[]
): Record<string, string> =>
  levels
    .map(lightness => ({
      lightness,
      color: `hsl(${hue}, ${saturation}%, ${lightness}%)`,
    }))
    .reduce(
      (prev, { lightness, color }) => ({
        ...prev,
        [lightness]: color,
      }),
      {}
    );
