import babel from 'rollup-plugin-babel'
import builtins from 'rollup-plugin-node-builtins'
import external from 'rollup-plugin-peer-deps-external'
// import commonjs from 'rollup-plugin-commonjs'
// import resolve from 'rollup-plugin-node-resolve'
// import svgr from '@svgr/rollup'
// import url from 'rollup-plugin-url'

import pkg from './package.json'

export default {
  input: 'src/index.js',
  output: [
    {
      file: pkg.main,
      format: 'cjs',
      sourcemap: true
    },
    {
      file: pkg.module,
      format: 'es',
      sourcemap: true
    }
  ],
  plugins: [
    babel({ exclude: 'node_modules/**' }),
    builtins(),
    external(),
    // url(),
    // svgr(),
    // resolve(),
    // commonjs()
  ]
}
