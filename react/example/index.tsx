import "react-hot-loader"
import React from "react"
import ReactDOM from "react-dom"

import "semantic-ui-css/semantic.min.css"
import "react-app-polyfill/ie11"
import "babel-polyfill"

const render = (): void => {
  const App = require("./App").default
  ReactDOM.render(<App />, document.getElementById("root"))
}
render()

if (process.env.NODE_ENV === "development" && module.hot) {
  module.hot.accept("./App", render)
}
