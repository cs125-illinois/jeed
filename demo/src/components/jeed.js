import _ from 'lodash'

import React from 'react'
import PropTypes from 'prop-types'

import Children from 'react-children-utilities'

import AceEditor from 'react-ace'

import brace from 'brace' // eslint-disable-line no-unused-vars
import 'brace/mode/java'
import 'brace/theme/github'

import styled from 'styled-components'

import log from 'loglevel'
const logger = log.getLogger("Jeed")

class Jeed extends React.Component {
  constructor({ children, mode: passedMode }) {
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
    const enabled = _.find(this.originalSources, source => source.trim().length > 0) !== undefined

    var mode = passedMode
    if (mode === "auto") {
      if (_.keys(this.originalSources).length === 1 && "" in this.originalSources) {
        mode = "snippet"
      } else {
        mode = "sources"
      }
    }

    this.state = {
      output: null, enabled, mode
    }
  }

  render() {
    const { Wrapper, RunButton, RunIcon, Output } = this.props
    const { output, enabled } = this.state
    const { originalSources, mode } = this

    return (
      <Wrapper>
        <AceEditor
          defaultValue={ originalSources[""] }
          editorProps={{ $blockScrolling: true }}
          { ...this.props.ace }
        />
        <RunButton onClick={ () => { console.log("Here") }}>
          <RunIcon enabled={ enabled }/>
        </RunButton>
        <Output>{ output }</Output>
      </Wrapper>
    )
  }
}

const RunButton = styled.button`
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

const RunIcon = ({ enabled }) =>
  <svg width='16px' height='16px' viewBox="0 0 60 60">
    <polygon points="0,0 50,30 0,60"
      style={{
        "fill": enabled ? "green" : "grey"
      }}
    />
  </svg>

const Output = styled.output``

Jeed.propTypes = {
  server: PropTypes.string.isRequired
}

Jeed.defaultProps = {
  Wrapper: styled.div`
    display: flex
    position: relative
    border: 1px solid LightGrey
  `,
  RunButton,
  RunIcon,
  Output,
  ace: {
    mode: "java",
    theme: "github",
    width: "100%",
    highlightActiveLine: false,
    showPrintMargin: false,
    minLines: 4,
    maxLines: Infinity
  },
  mode: "auto"
}

function withServer(server) {
  return class extends React.Component {
    render() {
      return <Jeed server={ server } { ...this.props } />
    }
  }
}

export default withServer
