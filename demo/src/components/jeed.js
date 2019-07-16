import React from "react"

import AceEditor from 'react-ace'

import brace from 'brace' // eslint-disable-line no-unused-vars
import 'brace/mode/java'
import 'brace/theme/github'

import styled from 'styled-components'
import { space } from 'styled-system'

const Wrapper = styled.div`
  display: flex
  position: relative
  border: 1px solid LightGrey
`
const Output = styled.output``

class Jeed extends React.Component {
  constructor() {
    super()
    this.state = {
      output: null
    }
  }
  render() {
    return (
      <Wrapper>
        <AceEditor {...this.props} style={{ "flex": 1 }}/>
        <Run />
        <Output>{ this.state.output }</Output>
      </Wrapper>
    )
  }
}

Jeed.defaultProps = {
  mode: "java",
  theme: "github",
  width: "100%",
  highlightActiveLine: false,
  showPrintMargin: false
}

const Button = styled.button`
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
const Run = () => {
  return (
    <Button>
      <svg width='16px' height='16px' viewBox="0 0 60 60">
        <polygon points="0,0 50,30 0,60" />
      </svg>
    </Button>
  )
}

export default Jeed
