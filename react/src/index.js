import React, { Component } from 'react'

import axios from 'axios'

const backends = {}
function jeedWrapper (WrappedComponent) {
  class JeedWrapper extends Component {
    constructor (props) {
      super(props)
      this.state = { config: null, connected: null }
    }

    componentDidMount() {
      const { backend } = this.props
      if (!(backend in backends)) {
        backends[backend] = axios.get(backend)
      }
      backends[backend].then(config => {
        this.setState({ config, connected: true })
      }).catch(() => {
        this.setState({ connected: false })
      })
    }

    render () {
      return <WrappedComponent jeed={this.state} { ...this.props } />
    }
  }

  JeedWrapper.displayName =
    `JeedWrapper(${
      WrappedComponent.displayName || WrappedComponent.name || 'Component'
    })`

  return JeedWrapper
}

function run (backend, job) {
  const cancellationHandle = axios.CancelToken.source()

  return {
    response: axios.post(backend, job, {
      cancelToken: cancellationHandle.token
    }),
    cancel: () => {
      cancellationHandle.cancel()
    }
  }
}

class Result extends Component {
  constructor(props) {
    super(props)
    this.state = { request: null, result: null, err: null, completed: false }
  }

  componentDidUpdate() {
    const { config } = this.props.jeed
    if (!config) {
      return
    }
    if (this.state.request) {
      return
    }

    const { backend, job } = this.props
    const request = run (backend, job)

    this.setState({ request })
    request.response.then(response => {
      this.setState({ result: response.data, completed: true })
    }).catch(err => {
      this.setState({ err, completed: true })
    })
  }

  componentWillUnmount() {
    if (!this.state.request || this.state.completed) {
      return
    }
    this.state.request.cancel()
  }

  render() {
    const { result, err } = this.state
    const Output = this.props.output || TerminalOutput
    return ( <Output job={ this.props.job } { ...this.state } /> )
  }
}

const TerminalOutput = (props) => {
  const { job, result } = props
  if (!result) {
    return null
  }

  let output = ""
  if (result.failed.snippet) {
    const { errors } = result.failed.snippet
    output += errors.map(error => {
      const { line, column, message } = error
      const originalLine = job.snippet.split("\n")[line - 1]
      return `Line ${ line }: error: ${ message }
${ originalLine }
${ new Array(column).join(" ") }^`
    })

    const errorCount = Object.keys(errors).length
    output += `
${ errorCount } error${ errorCount > 1 ? "s" : "" }`
  } else if (result.failed.compilation) {
    const { errors } = result.failed.compilation
    output += errors.map(error => {
      const { source, line, column } = error.location
      const originalLine = source === "" ?
        job.snippet.split("\n")[line - 1] :
        job.sources[source].split("\n")[line - 1]
      const firstErrorLine = error.message.split("\n").slice(0, 1).join("\n")
      const restError = error.message.split("\n").slice(1).filter(errorLine => {
        if (source === "" && errorLine.trim().startsWith("location: class")) {
          return false
        } else {
          return true
        }
      }).join("\n")
      return `${ source === "" ? "Line " : source }${ line }: error: ${ firstErrorLine }
${ originalLine }
${ new Array(column).join(" ") }^
${ restError }`
    }).join("\n")
    const errorCount = Object.keys(errors).length
    output += `
${ errorCount } error${ errorCount > 1 ? "s" : "" }`
  } else if (result.failed.execution) {
    output += result.failed.execution
  }

  if (Object.keys(result.failed).length === 0) {
    if (result.completed.execution) {
      output += result.completed.execution.outputLines.map(outputLine => {
        return outputLine.line
      }).join("\n")
    }
  }

  return (
    <pre>
        { JSON.stringify(result, null, 2) }<br />
        { output }
    </pre>)
}

const JeedResult = jeedWrapper(Result)
export { JeedResult, jeedWrapper }

