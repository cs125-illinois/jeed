import React, { Component } from 'react'

import Children from 'react-children-utilities'
import SyntaxHighlighter from 'react-syntax-highlighter'
import { github } from 'react-syntax-highlighter/dist/esm/styles/hljs'
import jeedWrapper, { TerminalOutput } from 'jeed'

import { Box, Container, IconButton, CircularProgress, Tooltip, Paper } from '@material-ui/core/'
import PlayCircleFilled from '@material-ui/icons/PlayCircleFilled'
import Warning from '@material-ui/icons/Warning'

const backend = process.env.REACT_APP_JEED_BACKEND

class Code extends Component {
  constructor(props) {
    super(props)

    this.state = { request: null, result: null, err: null, completed: false }

    this.source = Children.onlyText(this.props.children).trim() + "\n"
    this.job = { label: "test", tasks: props.tasks || [ "execute" ] }
    if (props.snippet) {
      this.job.snippet = this.source
    } else {
      this.job.sources = { [props.filename || "Example.java"]: this.source }
    }
  }

  run = () => {
    const { config, run, reconnect } = this.props.jeed

    if (!config) {
      return
    }

    const { completed } = this.state
    if (this.state.request && !completed) {
      return
    }

    const request = run(this.job)
    this.setState({ request })
    request.response.then(response => {
      this.setState({ result: response.data, completed: true })
    }).catch(err => {
      reconnect()
      this.setState({ err, completed: true })
    })
  }

  componentWillUnmount() {
    try { this.state.request.cancel() } catch (err) { }
  }

  render () {
    const { connected, reconnect } = this.props.jeed
    let button
    if (connected === true) {
      button =
        <Tooltip title="Run">
          <IconButton size="small" edge="end" onClick={() => { this.run() }}>
            <PlayCircleFilled size="small" color="primary" />
          </IconButton>
        </Tooltip>
    } else if (connected === false) {
      button =
        <Tooltip title="Disconnected (Click to Retry)">
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
      <Paper>
        <Box style={{ position: 'relative' }}>
          <Box style={{ position: 'absolute', bottom: 0, right: 2 }}>
              { button }
          </Box>
          <SyntaxHighlighter language="java" style={github}>{
            this.source
          }</SyntaxHighlighter>
        </Box>
        <Box style={{ maxHeight: 200, overflowY: 'scroll' }}>
          <pre style={{ padding: 4 }}>
            <TerminalOutput job={ this.job } { ...this.state } />
          </pre>
        </Box>
      </Paper>
    )
  }
}

const JeedCode = jeedWrapper(backend)(Code)

export default class App extends Component {
  render () {
    return (
      <Container maxWidth="md">
        <JeedCode snippet="true">{`
import java.util.Random;
int value = new Random().nextInt();
for (long i = 0; i < 10000000L; i++) {
  System.out.println(i + " " + value);
}
        `}</JeedCode>
      </Container>
    )
  }
}
