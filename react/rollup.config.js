import typescript from "rollup-plugin-typescript2"
import resolve from "rollup-plugin-node-resolve"
import commonJS from "@rollup/plugin-commonjs"

export default {
  input: "./src/index.tsx",
  output: {
    format: "cjs",
    file: "./dist/index.cjs.js",
    sourcemap: true,
  },
  plugins: [
    typescript({
      tsconfigDefaults: {
        include: ["./src/**/*"],
        compilerOptions: { declaration: true },
      },
    }),
    resolve({ preferBuiltins: true }),
    commonJS({
      include: "node_modules/**",
      namedExports: {
        runtypes: ["Record", "Partial", "Number", "String", "Array", "Static", "Boolean", "Union", "Dictionary"],
      },
    }),
  ],
  external: ["react", "prop-types"],
  onwarn: (warning, next) => {
    if (warning.code === "CIRCULAR_DEPENDENCY") return
    if (warning.code === "EVAL") return
    next(warning)
  },
}
