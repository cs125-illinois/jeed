import "react-app-polyfill/ie11"
import "babel-polyfill"
import React from "react"
import ReactDOM from "react-dom"
import { JeedProvider, withJeed } from "@cs125/react-jeed"

const HelloWorld: React.FC = () => {
  const { run } = withJeed()
  run({
    snippet: 'System.out.println("Hello, world!");',
    tasks: ["compile", "execute"],
    label: "test",
  })
  return <div>Hello, world!</div>
}
const App: React.SFC = () => (
  <JeedProvider server="http://localhost:8888">
    <HelloWorld />
  </JeedProvider>
)

ReactDOM.render(<App />, document.getElementById("root"))
