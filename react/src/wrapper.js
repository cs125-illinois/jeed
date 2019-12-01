import React, { Component } from 'react'

import EventEmitter from 'events'
import axios from 'axios'

const backends = {}
function monitor(backend) {
  if (!(backend in backends)) {
    backends[backend] = {
      connected: null,
      config: null,
      events: new EventEmitter()
    }
  }
  connect(backend)
  return backends[backend]
}

function connect(backend) {
  axios.get(backend).then(config => {
    backends[backend].config = config
    backends[backend].connected = true
    backends[backend].events.emit('updated', backends[backend])
  }).catch(() => {
    backends[backend].config = null
    backends[backend].connected = false
    backends[backend].events.emit('updated', backends[backend])
  })
}

function run(backend, job) {
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

export default function (backend) {
  return (WrappedComponent) => {
    class JeedWrapper extends Component {
      constructor(props) {
        super(props)
        this.state = { config: null, connected: null }
        this.reconnect = () => { connect(backend) }
        this.run = (job) => { return run(backend, job) }
      }

      componentDidMount() {
        const { config, connected, events } = monitor(backend)
        this.setState({ config, connected })
        events.on('updated', ({ config, connected }) => {
          this.setState({ config, connected })
        })
      }

      render() {
        return <WrappedComponent jeed={{
          ...this.state, reconnect: this.reconnect, run: this.run
        }} {...this.props} />
      }
    }

    JeedWrapper.displayName = `JeedWrapper(${WrappedComponent.displayName || WrappedComponent.name || 'Component'})`

    return JeedWrapper
  }
}

export const TerminalOutput = (props) => {
  const { job, result } = props
  if (!result) {
    return null
  }

  console.debug(result)

  let output = ""
  if (result.failed.snippet) {
    const { errors } = result.failed.snippet
    output += errors.map(error => {
      const { line, column, message } = error
      const originalLine = job.snippet.split("\n")[line - 1]
      return `Line ${line}: error: ${message}
${ originalLine}
${ new Array(column).join(" ")}^`
    })

    const errorCount = Object.keys(errors).length
    output += `
${ errorCount} error${errorCount > 1 ? "s" : ""}`
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
      return `${source === "" ? "Line " : source}${line}: error: ${firstErrorLine}
${ originalLine}
${ new Array(column).join(" ")}^
${ restError}`
    }).join("\n")
    const errorCount = Object.keys(errors).length
    output += `
${ errorCount} error${errorCount > 1 ? "s" : ""}`
  } else if (result.failed.execution) {
    output += result.failed.execution
  }

  if (Object.keys(result.failed).length === 0) {
    if (result.completed.execution) {
      const { execution } = result.completed
      output += execution.outputLines.map(outputLine => {
        return outputLine.line
      }).join("\n")
      if (execution.timeout) {
        output += "\n(Program Timed Out)"
      }
      if (execution.truncatedLines > 0) {
        output += `\n(${execution.truncatedLines} lines were truncated)`
      }
    }
  }

  return (<div>{output}</div>)
}