import React from "react"
import styled, { ThemeProvider } from 'styled-components'

import Jeed from "../components/jeed"

const theme = {
  fontSizes: [12, 14, 16, 20, 24, 32]
}

const Main = styled.main`
  max-width: 800px
  margin-left: auto
  margin-right: auto
`

const IndexPage = () => (
  <ThemeProvider theme={theme}>
    <Main>
      <Jeed />
    </Main>
  </ThemeProvider>
)

export default IndexPage
