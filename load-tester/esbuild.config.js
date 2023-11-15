require("esbuild").build({
  entryPoints: ["./src/**/*.ts"],
  sourcemap: true,
  bundle: true,
  target: "node18",
  platform: "node",
  outdir: "dist",
  external: ["k6*"],
});
