import React from "react"

import AceEditor from 'react-ace'

import brace from 'brace' // eslint-disable-line no-unused-vars
import 'brace/mode/java'
import 'brace/theme/github'

import styled from 'styled-components'

class Jeed extends React.Component {
  constructor() {
    super()
    this.state = {
      output: null
    }
  }
  render() {
    const { Wrapper, RunButton, RunIcon, Output } = this.props
    return (
      <Wrapper>
        <AceEditor { ...this.props.ace }/>
        <RunButton onClick={ () => { console.log("Here") }}>
          <RunIcon />
        </RunButton>
        <Output>
          { this.state.output }
        </Output>
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

const RunIcon = () =>
  <svg width='16px' height='16px' viewBox="0 0 60 60">
    <polygon points="0,0 50,30 0,60" />
  </svg>

const Output = styled.output``

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
    showPrintMargin: false
  }
}

export default Jeed
