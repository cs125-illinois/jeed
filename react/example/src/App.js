import React, { Component } from 'react'

import Children from 'react-children-utilities'

import { JeedResult } from 'jeed'

import Container from '@material-ui/core/Container'

const backend = process.env.REACT_APP_JEED_BACKEND

class JeedDiv extends Component {
  constructor(props) {
    super(props)
    this.state = { clicked: false }
    this.source = Children.onlyText(this.props.children).trim() + "\n"
    this.job = {
      tasks: props.tasks || [ "execute" ],
    }
    if (props.snippet) {
      this.job.snippet = this.source
    } else {
      const filename = props.filename || "Example.java"
      this.job.sources = {
        filename: this.source
      }
    }
  }
  render () {
    return (
      <div>
        <pre><code>{ this.source }</code></pre>
        <pre><code>{ JSON.stringify(this.job, null, 2) }</code></pre>
        <JeedResult backend={backend} job={this.job} />
      </div>
    )
  }
}

export default class App extends Component {
  render () {
    return (
      <Container maxWidth="md">
        <JeedDiv snippet="true">{`
for (long i = 0; i < 10000000L; i++) {
  System.out.println(i);
}
        `}</JeedDiv>
      </Container>
    )
  }
}
