import React, { Component } from "react"

import AceEditor, { IAceOptions } from "react-ace"
import "ace-builds/src-noconflict/mode-java"
import "ace-builds/src-noconflict/mode-kotlin"
import "ace-builds/src-noconflict/theme-chrome"

import { JeedContext } from "@cs125/react-jeed"

import Children from "react-children-utilities"

interface JeedAceProps extends IAceOptions {
  children?: string | React.ReactNode
}
interface JeedAceState {
  value: string
  busy: boolean
}
export class JeedAce extends Component<JeedAceProps, JeedAceState> {
  static contextType = JeedContext
  static defaultProps = {
    label: "ace-editor",
    mode: "java",
    theme: "chrome",
  }
  constructor(props: JeedAceProps) {
    super(props)
    this.state = {
      value: this.childrenToValue(props.children).trim(),
      busy: false,
    }
  }
  childrenToValue = (children: string | React.ReactNode): string => {
    if (children instanceof String) {
      return children.trim()
    } else {
      return Children.onlyText(children)
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
    const { label, mode, theme } = this.props
    const { value } = this.state

    return (
      <AceEditor
        name={label}
        width="100%"
        value={value}
        onChange={this.onChange}
        mode={mode}
        theme={theme}
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
