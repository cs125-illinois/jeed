import React, { Component } from 'react'

import Children from 'react-children-utilities'
import SyntaxHighlighter from 'react-syntax-highlighter'
import { github } from 'react-syntax-highlighter/dist/esm/styles/hljs'
import { jeedWrapper, JeedResult } from 'jeed'

import { Box, Container, IconButton, CircularProgress, Tooltip } from '@material-ui/core/'
import PlayCircleFilled from '@material-ui/icons/PlayCircleFilled'
import Warning from '@material-ui/icons/Warning'

const backend = process.env.REACT_APP_JEED_BACKEND

class Code extends Component {
  constructor(props) {
    super(props)

    this.state = { clicked: false }

    this.source = Children.onlyText(this.props.children).trim() + "\n"
    this.job = { tasks: props.tasks || [ "execute" ] }
    if (props.snippet) {
      this.job.snippet = this.source
    } else {
      this.job.sources = { [props.filename || "Example.java"]: this.source }
    }
  }

  render () {
    const { connected, reconnect } = this.props.jeed
    let button
    if (connected === true) {
      button =
        <Tooltip title="Run">
          <IconButton size="small" edge="end">
            <PlayCircleFilled size="small" color="primary" />
          </IconButton>
        </Tooltip>
    } else if (connected === false) {
      button =
        <Tooltip title="Not Connected (Click to Retry)">
          <IconButton size="small" edge="end" onClick={() => { reconnect() }}>
            <Warning color="error" size="small" />
          </IconButton>
        </Tooltip>
    } else {
      button =
        <Tooltip title="Connecting...">
          <CircularProgress size={20} style={{ marginBottom: 2 }}/>
        </Tooltip>
    }

    return (
      <Box>
        <Box style={{ position: 'relative' }}>
          <Box style={{ position: 'absolute', bottom: 0, right: 2 }}>
              { button }
          </Box>
          <SyntaxHighlighter language="java" style={github}>{ this.source }</SyntaxHighlighter>
        </Box>
        <Box>
          <JeedResult jeed={ this.props.jeed } job={ this.job } />
        </Box>
      </Box>
    )
  }
}

const JeedCode = jeedWrapper(backend)(Code)

export default class App extends Component {
  render () {
    return (
      <Container maxWidth="md">
        <JeedCode snippet="true">{`
for (long i = 0; i < 10000000L; i++) {
  System.out.println(i);
}
        `}</JeedCode>
      </Container>
    )
  }
}
