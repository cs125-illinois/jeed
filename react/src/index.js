import React, { Component } from 'react'

import axios from 'axios'

const backends = {}
function jeedBackendWrapper (WrappedComponent) {
  class JeedBackendWrapper extends Component {
    constructor (props) {
      super(props)
      this.state = { config: null }
    }

    componentDidMount() {
      const { backend } = this.props
      if (!(backend in backends)) {
        backends[backend] = axios.get(backend)
      }
      backends[backend].then(config => {
        this.setState({ config })
      })
    }

    render () {
      return <WrappedComponent jeed={this.state} { ...this.props } />
    }
  }

  JeedBackendWrapper.displayName =
    `JeedBackendWrapper(${
      WrappedComponent.displayName || WrappedComponent.name || 'Component'
    })`

  return JeedBackendWrapper
}

class JeedResultInner extends Component {
  constructor(props) {
    super(props)
    this.state = { request: null, result: null }
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
      this.setState({ result: response.data })
    }).catch(err => {
      console.error(err)
    })
  }

  componentWillUnmount() {
    if (!this.state.request || this.state.response) {
      return
    }
    this.state.request.cancel()
  }

  render() {
    const { config } = this.props.jeed
    const { result } = this.state

    if (!config) {
      const { connecting } = this.props
      return ( connecting || <div>Connecting...</div> )
    }
    if (!result) {
      return ( <pre>{ JSON.stringify(config, null, 2) }</pre> )
    }
    return ( <pre>{ JSON.stringify(result, null, 2) }</pre> )
  }
}

const JeedResult = jeedBackendWrapper(JeedResultInner)
export { JeedResult }

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

