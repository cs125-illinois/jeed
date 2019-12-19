import typescript from "rollup-plugin-typescript2"
import resolve from "rollup-plugin-node-resolve"
import commonjs from "rollup-plugin-commonjs"

export default {
  input: "./src/index.tsx",
  output: {
    format: "cjs",
    file: "./dist/index.cjs.js",
    sourcemap: true
  },
  plugins: [typescript(), resolve(), commonjs()],
  external: ["react", "prop-types"],
  onwarn: ( warning, next ) => {
    if ( warning.code === 'THIS_IS_UNDEFINED' ) return
    next( warning )
  },
}
