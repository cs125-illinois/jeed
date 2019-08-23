import React, { Component } from 'react'
import PropTypes from 'prop-types'

import axios from 'axios'

const backends = {}
export default class ExampleComponent extends Component {
  static propTypes = {
    text: PropTypes.string
  }

  constructor (props) {
    super(props)

    this.state = { connected: false, config: null }

    const { backend } = props
    if (!(backend in backends)) {
      backends[backend] = axios.get(backend)
    }
    backends[backend].then(config => { this.setState({ connected: true, config }) })
  }

  render () {
    const { connected, config } = this.state
    if (!connected) {
      return (<div>Waiting</div>)
    }
    return (
      <div>{JSON.stringify(config, null, 2)}</div>
    )
  }
}
