import _ from 'lodash'

import React from 'react'
import PropTypes from 'prop-types'

import Children from 'react-children-utilities'

import AceEditor from 'react-ace'

import brace from 'brace' // eslint-disable-line no-unused-vars
import 'brace/mode/java'
import 'brace/theme/github'

import styled from 'styled-components'

import axios from 'axios';

import log from 'loglevel'

const logger = log.getLogger("Jeed")

const JeedContext = React.createContext({ serverInfo: undefined })

class Jeed extends React.Component {
  constructor({ children, mode: passedMode, tasks }) {
    super()

    this.originalSources = {}
    Children.deepForEach(children, child => {
      const { props } = child
      if (props.originalType !== "code") {
        return
      }
      const filename = props.filename || ""
      if (filename in this.originalSources) {
        throw Error(`Duplicate key in Jeed component sources: ${ filename }`)
      }
      try {
        this.originalSources[filename] = Children.onlyText(child.props.children).trim() + "\n"
      } catch (error) { }
    })

    var mode = passedMode
    if (mode === "auto") {
      if (_.keys(this.originalSources).length === 1 && "" in this.originalSources) {
        mode = "snippet"
      } else {
        mode = "sources"
      }
    }

    this.state = {
      output: null, mode, sources: _.clone(this.originalSources), serverInfo, tasks
    }
  }

  run = async () => {
    const { server } = this.props
    const { sources, mode, tasks } = this.state
    const request = { tasks }
    if (mode === "snippet") {
      request.snippet = sources[""]
    }
    const response = await axios.post(server, request)
    console.log(response)
    this.setState({ response })
  }

  render() {
    const { Wrapper, ace, RunButton, RunIcon, Output } = this.props
    const { serverInfo, sources, response } = this.state

    return (
      <Wrapper>
        <AceEditor
          value={ sources[""] }
          editorProps={{ $blockScrolling: true }}
          onLoad={ editor => {
            editor.gotoLine(1, 0)
            editor.commands.addCommand({
              name: "run code",
              bindKey: { win: "Ctrl-Enter", mac: "Ctrl-Enter"},
              exec: () => { this.run() }
            })
          }}
          onChange={ contents => { sources[""] = contents }}
          { ...ace }
        />
        <RunButton serverInfo={ serverInfo } onClick={ () => { this.run() }} />
        <Results { ...response } />
      </Wrapper>
    )
  }
}

class Results extends React.PureComponent {
  static Output = styled.output``
  render() {
    console.log(this.props)

    /*
    const output = _.map(result.data.completed.execution.outputLines, (line, key) =>
      <span key={ key } style={{ color: line.console === "STDERR" ? "red" : "black" }}>{ line.line }</span>
    )
    */
    return (
      <Results.Output />
    )
  }
}

class RunButton extends React.PureComponent {
  static Button = styled.button`
    position: absolute
    bottom: 0
    right: 0
    z-index: 1
    border: none
    background: none
    cursor: pointer
    padding-bottom: 4px
    padding-right: 4px
  `
  render() {
    const { serverInfo, onClick, children } = this.props
    return <RunButton.Button onClick={ onClick }>{ children }</RunButton.Button>
  }
}

const RunIcon = () =>
  <svg width='16px' height='16px' viewBox="0 0 60 60">
    <polygon points="0,0 50,30 0,60" style={{ "fill": "green" }} />
  </svg>


Jeed.propTypes = {
  server: PropTypes.string.isRequired
}

Jeed.defaultProps = {
  Wrapper: styled.div`
    display: flex
    flex-direction: column
    position: relative
    border: 1px solid LightGrey
  `,
  RunButton,
  RunIcon,
  Results,
  ace: {
    mode: "java",
    theme: "github",
    width: "100%",
    highlightActiveLine: false,
    showPrintMargin: false,
    minLines: 4,
    maxLines: Infinity,
  },
  mode: "auto",
  tasks: [ "execute" ]
}

function withServer(server, defaultProps={}) {
  axios.get(server).then(response => {
    try {
      if ("jeed" in response.data.versions) {
        serverInfo = response.data
      }
    } catch (err) {
      console.log(err)
      serverInfo = undefined
    }
  })
  return class extends React.Component {
    render() {
      return <Jeed server={ server } { ...defaultProps } { ...this.props } />
    }
  }
}

export default withServer
