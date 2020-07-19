import replace from "@rollup/plugin-replace"
import typescript from "rollup-plugin-typescript2"
import resolve from "rollup-plugin-node-resolve"
import commonJS from "@rollup/plugin-commonjs"

export default ["cjs", "es"].map((format) => ({
  input: "./src/index.tsx",
  output: {
    format,
    file: `./dist/index.${format}.js`,
    sourcemap: true,
    strict: false,
  },
  plugins: [
    typescript({
      tsconfigDefaults: {
        include: ["./src/**/*"],
        compilerOptions: { declaration: true },
      },
    }),
    replace({
      "process.env.npm_package_version": `"${process.env.npm_package_version}"`,
      "process.env.GIT_COMMIT": `"${process.env.GIT_COMMIT}"`,
    }),
    resolve({ browser: true, preferBuiltins: true }),
    commonJS({
      include: "node_modules/**",
    }),
  ],
  external: ["react", "prop-types"],
  onwarn: (warning, next) => {
    if (warning.code === "CIRCULAR_DEPENDENCY") return
    if (warning.code === "EVAL") return
    next(warning)
  },
}))
