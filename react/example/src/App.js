import React, { Component } from 'react'

import { JeedResult } from 'jeed'

export default class App extends Component {
  render () {
    const backend = process.env.REACT_APP_JEED_BACKEND
    return (
      <div>
        <JeedResult backend={ backend } job={{
          tasks: [ "execute" ],
          snippet: `System.out.println("Hello, world!");`
        }} />
      </div>
    )
  }
}
