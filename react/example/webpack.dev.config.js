const webpack = require("webpack")
const path = require("path")

const HtmlWebpackPlugin = require("html-webpack-plugin")
const Dotenv = require("dotenv-webpack")

module.exports = {
  mode: "development",
  entry: path.resolve(__dirname, "index.tsx"),
  devServer: {
    port: 1234,
    hot: true,
  },
  module: {
    rules: [
      {
        test: /\.css$/,
        use: ["style-loader", "css-loader"],
      },
      {
        test: /\.(png|svg|jpg|gif|woff|woff2|eot|ttf|otf)$/,
        use: "file-loader",
      },
      {
        test: /\.ts(x?)$/,
        exclude: /node_modules/,
        use: "ts-loader",
      },
      {
        test: /\.js$/,
        exclude: /node_modules/,
        use: "babel-loader",
      },
      {
        test: /\.js$/,
        enforce: "pre",
        use: "source-map-loader",
      },
      {
        test: /\.mdx$/,
        use: ["babel-loader", "@mdx-js/loader"],
      },
    ],
  },
  resolve: {
    extensions: [".wasm", ".mjs", ".js", ".json", ".ts", ".tsx"],
    alias: {
      "react-dom": "@hot-loader/react-dom",
      "@cs125/react-jeed": path.resolve(__dirname, ".."),
    },
  },
  plugins: [
    new Dotenv(),
    new webpack.EnvironmentPlugin(["GIT_COMMIT", "npm_package_version", "npm_package_description"]),
    new HtmlWebpackPlugin({ template: path.resolve(__dirname, "index.html") }),
  ],
}
