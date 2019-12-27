import "react-app-polyfill/ie11"
import "babel-polyfill"
import "semantic-ui-css/semantic.min.css"

import React, { Component } from "react"
import ReactDOM from "react-dom"

import { Container } from "semantic-ui-react"
import styled from "styled-components"

import AceEditor from "react-ace"
import "ace-builds/src-noconflict/mode-java"
import "ace-builds/src-noconflict/mode-kotlin"
import "ace-builds/src-noconflict/theme-github"

import { JeedProvider, JeedContext } from "@cs125/react-jeed"

interface JeedAceProps {
  label: string
  mode?: string
  children?: string
}
interface JeedAceState {
  value: string
  busy: boolean
}
class JeedAce extends Component<JeedAceProps, JeedAceState> {
  static contextType = JeedContext
  static defaultProps = {
    mode: "java",
  }
  constructor(props: JeedAceProps) {
    super(props)
    const { children } = props

    this.state = {
      value: children?.trim() || "",
      busy: false,
    }
  }
  onChange = (value: string): void => {
    this.setState({ value })
  }
  runCode = (): void => {
    const { label } = this.props
    const { value, busy } = this.state
    const { run, connected } = this.context

    if (busy || !connected) {
      return
    }

    this.setState({ busy: true })
    run({
      label,
      snippet: value,
      tasks: ["compile", "execute"],
    }).then(() => {
      this.setState({ busy: false })
    })
  }
  render(): React.ReactNode {
    const { label, mode } = this.props
    const { value } = this.state

    return (
      <AceEditor
        name={label}
        width="100%"
        value={value}
        onChange={this.onChange}
        mode={mode}
        theme="github"
        commands={[
          {
            name: "run",
            bindKey: { win: "Ctrl-Enter", mac: "Ctrl-Enter" },
            exec: this.runCode,
          },
        ]}
      />
    )
  }
}

const PaddedContainer = styled(Container)({
  paddingTop: 16,
})
const App: React.SFC = () => (
  <JeedProvider server="http://localhost:8888">
    <PaddedContainer text>
      <JeedAce label="HelloWorld">
        {`
System.out.println("Hello, world");
`}
      </JeedAce>
    </PaddedContainer>
  </JeedProvider>
)

ReactDOM.render(<App />, document.getElementById("root"))
