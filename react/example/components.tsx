import React, { Component } from "react"

import AceEditor, { IAceOptions } from "react-ace"
import "ace-builds/src-noconflict/mode-java"
import "ace-builds/src-noconflict/mode-kotlin"
import "ace-builds/src-noconflict/theme-chrome"

import { JeedContext, Result, Job, Task, TerminalOutput } from "@cs125/react-jeed"

import Children from "react-children-utilities"
import { Button, Icon, Dimmer, Container, Loader, Segment, Label } from "semantic-ui-react"
import styled from "styled-components"

export const enum JeedLanguage {
  Java = "java",
  Kotlin = "kotlin",
}
interface JeedAceProps extends IAceOptions {
  mode: JeedLanguage
  children: string | React.ReactNode
  autoMin?: boolean
  autoPadding?: number
  snippet?: boolean
  nocheckstyle?: boolean
}
interface JeedAceState {
  value: string
  busy: boolean
  result?: Result
  showOutput: boolean
}
const RelativeContainer = styled(Container)({
  position: "relative",
})
const SnugLabel = styled(Label)({
  top: "0!important",
  right: "0!important",
})
export class JeedAce extends Component<JeedAceProps, JeedAceState> {
  static contextType = JeedContext
  declare context: React.ContextType<typeof JeedContext>

  static defaultProps = {
    name: "ace-editor",
    mode: "java",
    theme: "chrome",
    autoMin: false,
    autoPadding: 2,
    snippet: false,
    nocheckstyle: false,
  }

  private originalValue: string
  private minLines: number | undefined

  constructor(props: JeedAceProps) {
    super(props)

    this.originalValue = this.childrenToValue(props.children).trim()
    this.minLines = props.autoMin
      ? this.originalValue.split("\n").length + (props.autoPadding || JeedAce.defaultProps.autoPadding)
      : props.minLines
    this.state = {
      value: this.originalValue,
      busy: false,
      showOutput: false,
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
    const { name: label, mode, snippet, nocheckstyle } = this.props
    const { value, busy } = this.state
    const { run, connected } = this.context

    if (busy || !connected) {
      return
    }

    this.setState({ busy: true, showOutput: true })

    const tasks = [mode == "java" ? "compile" : "kompile", "execute"] as Array<Task>
    if (mode == "java" && !nocheckstyle) {
      tasks.push("checkstyle")
    }
    const job: Job = snippet
      ? { label, tasks, snippet: value }
      : { label, tasks, sources: [{ path: mode == "java" ? "Main.java" : "Main.kt", contents: value }] }

    run(job).then(result => {
      this.setState({ busy: false, result })
    })
  }
  render(): React.ReactNode {
    const { onChange, value, minLines, ...aceProps } = this.props // eslint-disable-line @typescript-eslint/no-unused-vars
    const commands = (this.props.commands || []).concat([
      {
        name: "run",
        bindKey: { win: "Ctrl-Enter", mac: "Ctrl-Enter" },
        exec: this.runCode,
      },
    ])

    const { busy, showOutput, result } = this.state
    return (
      <RelativeContainer>
        <div style={{ position: "absolute", top: 8, right: 8, zIndex: 10 }}>
          <Button icon positive circular disabled={!this.context.connected} loading={busy} onClick={this.runCode}>
            <Icon name="play" />
          </Button>
        </div>
        <AceEditor
          {...aceProps}
          value={this.state.value}
          onChange={this.onChange}
          commands={commands}
          minLines={this.minLines}
        />
        {showOutput && (
          <Dimmer.Dimmable as={Segment} inverted>
            <Dimmer active={busy} inverted>
              <Loader />
            </Dimmer>
            <SnugLabel
              size="mini"
              corner="right"
              onClick={(): void => {
                this.setState({ showOutput: false })
              }}
            >
              <Icon size="tiny" name="close" />
            </SnugLabel>
            {result && <TerminalOutput result={result} />}
          </Dimmer.Dimmable>
        )}
      </RelativeContainer>
    )
  }
}
