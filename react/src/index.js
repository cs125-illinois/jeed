import React, { Component } from 'react'

import EventEmitter from 'events'
import axios from 'axios'

const backends = {}

function monitorBackend (backend) {
  if (!(backend in backends)) {
    backends[backend] = {
      connected: null,
      config: null,
      events: new EventEmitter()
    }
  }
  checkBackend(backend)
  return backends[backend]
}

function checkBackend (backend) {
  console.debug(`checking ${ backend }`)
  axios.get(backend).then(config => {
    console.debug(`${ backend } connected`)
    backends[backend].config = config
    backends[backend].connected = true
    backends[backend].events.emit('updated', backends[backend])
  }).catch(() => {
    console.debug(`${ backend } still not connected`)
    backends[backend].config = null
    backends[backend].connected = false
    backends[backend].events.emit('updated', backends[backend])
  })
}

function jeedWrapper (backend) {
  return (WrappedComponent) => {
    class JeedWrapper extends Component {
      constructor (props) {
        super(props)
        this.state = { config: null, connected: null }
        this.reconnect = () => { checkBackend(backend) }
      }

      componentDidMount() {
        const { config, connected, events } = monitorBackend(backend)
        this.setState({ config, connected })
        events.on('updated', ({ config, connected }) => {
          this.setState({ config, connected })
        })
      }

      render () {
        return <WrappedComponent jeed={{...this.state, backend, reconnect: this.reconnect }} { ...this.props } />
      }
    }

    JeedWrapper.displayName = `JeedWrapper(${ WrappedComponent.displayName || WrappedComponent.name || 'Component' })`

    return JeedWrapper
  }
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

class JeedResult extends Component {
  constructor(props) {
    super(props)
    this.state = { request: null, result: null, err: null, completed: false }
  }

  componentDidUpdate() {
    const { config, backend } = this.props.jeed
    if (!config) {
      return
    }
    if (this.state.request) {
      return
    }

    const { job } = this.props
    const request = run (backend, job)

    this.setState({ request })
    request.response.then(response => {
      this.setState({ result: response.data, completed: true })
    }).catch(err => {
      checkBackend(backend)
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
        { output }
    </pre>)
}

export { jeedWrapper, JeedResult }

