import React, { Component, ReactElement, createRef } from "react"
import PropTypes from "prop-types"

import Children from "react-children-utilities"
import { MaceEditor, withMaceConnected } from "@cs125/mace"
import { withGoogleTokens } from "@cs125/react-google-login"

import { IAceOptions } from "react-ace"
import "ace-builds/src-noconflict/mode-java"
import "ace-builds/src-noconflict/mode-kotlin"
import "ace-builds/src-noconflict/theme-chrome"

import ace from "ace-builds/src-noconflict/ace"
const CDN = "https://cdn.jsdelivr.net/npm/ace-builds@1.4.11/src-min-noconflict"
ace.config.set("basePath", CDN)

import { JeedContext, Task, Request, Response, terminalOutput, TaskArguments } from "@cs125/react-jeed"

import { Button, Icon, Dimmer, Container, Loader, Segment, Label, Popup } from "semantic-ui-react"
import styled from "styled-components"

export const enum ExampleLanguage {
  Java = "java",
  Kotlin = "kotlin",
}
export interface ExampleProps extends IAceOptions {
  id: string
  path: string | undefined
  tasks: Array<Task>
  jeedArguments: TaskArguments | undefined
  complete: boolean
  complexity: boolean
  maxOutputLines: number
  children: React.ReactNode
}
interface ExampleState {
  value: string
  busy: boolean
  response?: Response
  output?: ReactElement | string
  outputLines: number
  annotations: AceAnnotation[]
  showOutput: boolean
  saved: boolean
  saving: boolean
}
interface AceAnnotation {
  row: number
  column: number
  type: string
  text: string
}

class Example extends Component<ExampleProps & { connected: boolean; authToken: string | undefined }, ExampleState> {
  static contextType = JeedContext
  declare context: React.ContextType<typeof JeedContext>

  static propTypes = {
    id: PropTypes.string.isRequired,
    path: PropTypes.string,
    tasks: PropTypes.array.isRequired,
    jeedArguments: (props: ExampleProps): Error | void => {
      if (props.arguments === undefined) {
        return
      }
      try {
        TaskArguments.check(props.arguments)
      } catch (e) {
        return e
      }
    },
    complete: PropTypes.bool,
    complexity: PropTypes.bool,
    maxOutputLines: PropTypes.number,
    children: PropTypes.node.isRequired,
  }

  static defaultProps = {
    children: "",
    name: "ace-editor",
    mode: "java",
    theme: "chrome",
    complete: false,
    complexity: false,
    maxOutputLines: 16,
  }

  private maceRef = createRef<MaceEditor>()
  private originalValue: string
  private minLines: number | undefined
  private savedValue: string
  private saveTimer: NodeJS.Timeout | undefined

  constructor(props: ExampleProps & { connected: boolean; authToken: string | undefined }) {
    super(props)
    this.originalValue = Children.onlyText(props.children)
    this.minLines = this.originalValue.split("\n").length + 1
    this.savedValue = this.originalValue
    this.state = {
      value: this.originalValue,
      busy: false,
      showOutput: false,
      outputLines: 0,
      annotations: [],
      saved: true,
      saving: false,
    }
  }
  runCode = (): void => {
    const { id, path, tasks, jeedArguments, maxOutputLines, complete } = this.props
    const { value, busy, outputLines } = this.state
    const { run, connected } = this.context

    if (busy || !connected) {
      return
    }

    this.save()
    this.setState({
      busy: true,
      showOutput: true,
      output: Array(outputLines).join("\n"),
    })

    const taskArguments =
      path === undefined ? Object.assign({}, jeedArguments, { snippet: { indent: 2 } }) : jeedArguments
    const request: Request =
      path === undefined
        ? { label: id, tasks, snippet: value, arguments: taskArguments }
        : {
            label: id,
            tasks,
            arguments: taskArguments,
            sources: [{ path, contents: value }],
          }

    run(request)
      .then((response) => {
        const output = complete ? JSON.stringify(response, null, 2) : terminalOutput(response)
        let annotations: AceAnnotation[] = []
        if (response.completed.complexity) {
          annotations = response.completed.complexity.results[0].methods
            .filter((m) => m.name !== "")
            .map((m) => {
              return {
                row: m.range.start.column,
                column: 0,
                type: "info",
                text: `${m.name}: complexity ${m.complexity}`,
              }
            })
        }
        this.setState({
          busy: false,
          response,
          output: output !== "" ? output : <span style={{ color: "green" }}>{"(No Output)"}</span>,
          outputLines: Math.min(output.split("\n").length, maxOutputLines),
          annotations,
        })
      })
      .catch(() => {
        this.setState({ busy: false })
      })
  }
  save = (): void => {
    if (this.state.saved) {
      return
    }
    this.setState({ saving: true })
    this.maceRef?.current?.save()
  }
  reload = (): void => {
    const saved = this.originalValue === this.savedValue
    this.setState({ value: this.originalValue, saved })
    this.startSaveTimer(saved)
  }
  startSaveTimer = (saved: boolean): void => {
    if (this.saveTimer) {
      clearTimeout(this.saveTimer)
    }
    if (saved) {
      return
    }
    this.saveTimer = global.setTimeout(() => {
      this.save()
    }, 1000)
  }
  render(): React.ReactNode {
    const { onChange, minLines, tasks, complete, complexity, ...aceProps } = this.props // eslint-disable-line @typescript-eslint/no-unused-vars
    const { value, annotations, outputLines } = this.state
    const { status } = this.context

    const commands = [
      {
        name: "run",
        bindKey: { win: "Ctrl-Enter", mac: "Ctrl-Enter" },
        exec: this.runCode,
      },
      {
        name: "save",
        bindKey: { win: "Ctrl-s", mac: "Ctrl-s" },
        exec: this.save,
      },
      {
        name: "gotoline",
        exec: (): boolean => {
          return false
        },
      },
    ]
    const empty = this.state.value.trim().length === 0

    let saveLabel = "Saved!"
    if (this.state.saving) {
      saveLabel = "Saving..."
    } else if (!this.state.saved) {
      saveLabel = "Click to Save"
    }

    const jeedVersion = `Jeed ${status?.versions.jeed}`
    let compilerVersion
    if (status && tasks.includes("compile")) {
      compilerVersion = `Java ${status.versions.compiler.split("_")[1]}`
    } else if (status && tasks.includes("kompile")) {
      compilerVersion = `Kotlin ${status.versions.kompiler}`
    }
    const { busy, showOutput, output, saving, saved } = this.state
    return (
      <div>
        <RelativeContainer>
          <div style={{ display: "flex", flexDirection: "row", position: "absolute", bottom: 8, right: 0, zIndex: 10 }}>
            <Popup
              disabled={value === this.originalValue}
              position="top center"
              content={"Reload"}
              trigger={
                <div>
                  <Button icon circular disabled={value === this.originalValue} size="tiny" onClick={this.reload}>
                    <Icon name="repeat" />
                  </Button>
                </div>
              }
            />
            <Popup
              position="top center"
              content={saveLabel}
              trigger={
                <div>
                  <Button icon circular disabled={saved} size="tiny" loading={saving} onClick={this.save}>
                    <Icon name="check circle" />
                  </Button>
                </div>
              }
            />
            <Popup
              position="top center"
              content={"Run Your Code"}
              trigger={
                <Button
                  icon
                  positive
                  circular
                  disabled={!this.context.connected || empty}
                  size="tiny"
                  loading={busy}
                  onClick={this.runCode}
                >
                  <Icon name="play" />
                </Button>
              }
            />
          </div>
          <MaceEditor
            ref={this.maceRef}
            annotations={complexity ? annotations : []}
            width={"100%"}
            highlightActiveLine={false}
            showPrintMargin={false}
            maxLines={Infinity}
            height={"100px"}
            tabSize={2}
            useSoftTabs
            {...aceProps}
            value={value}
            onExternalUpdate={({ value }): void => {
              this.savedValue = value
              this.setState({ value, saved: true })
            }}
            onSave={(value: string): void => {
              this.savedValue = value
              this.setState({ saving: false, saved: true })
            }}
            onChange={(value: string): void => {
              const saved = value === this.savedValue
              this.setState({ value, saved })
              this.startSaveTimer(saved)
            }}
            commands={commands}
            minLines={this.minLines}
          />
        </RelativeContainer>
        {status && (
          <div style={{ display: "flex", flexDirection: "row", justifyContent: "flex-end" }}>
            <Label basic size="mini">
              {jeedVersion}
            </Label>
            <Label basic size="mini">
              {compilerVersion}
            </Label>
          </div>
        )}
        {showOutput && (
          <Dimmer.Dimmable as={Segment} inverted style={{ padding: 0, marginBottom: "1em" }}>
            <Dimmer active={busy} inverted>
              <Loader size="small" />
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
            <Segment
              inverted
              style={{
                maxHeight: `${1.5 * outputLines + 2}em`,
                minHeight: "4em",
                overflow: "auto",
                margin: 0,
              }}
            >
              <SnugPre>{output}</SnugPre>
            </Segment>
          </Dimmer.Dimmable>
        )}
      </div>
    )
  }
}

const RelativeContainer = styled(Container)({
  position: "relative",
  marginBottom: "1px",
})
const SnugLabel = styled(Label)({
  top: "0!important",
  right: "0!important",
})
const SnugPre = styled.pre`
  margin-top: 0;
  margin-bottom: 0;
`
const ExampleWrapper: React.FC<ExampleProps> = (props) => {
  const connected = withMaceConnected()
  const { idToken } = withGoogleTokens()
  return <Example connected={connected} authToken={idToken} {...props} style={{ marginTop: "1em" }} />
}
export default ExampleWrapper
