import "react-app-polyfill/ie11"
import "babel-polyfill"
import React from "react"
import ReactDOM from "react-dom"
import { JeedProvider } from "@cs125/react-jeed"

const App: React.SFC = () => (
  <JeedProvider server="http://localhost:8888">
    <div>Hello, Jeed!</div>
  </JeedProvider>
)

ReactDOM.render(<App />, document.getElementById("root"))
