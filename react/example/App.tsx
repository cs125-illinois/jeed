import React from "react"
import { hot } from "react-hot-loader"

import { Container } from "semantic-ui-react"

import { MDXProvider } from "@mdx-js/react"
import Content from "./index.mdx"

import SyntaxHighlighter from "react-syntax-highlighter/dist/esm/default-highlight"

import { JeedProvider } from "@cs125/react-jeed"

const components = {
  code: SyntaxHighlighter,
}
const App: React.SFC = () => (
  <JeedProvider server={process.env.JEED as string}>
    <Container text style={{ paddingTop: 16 }}>
      <MDXProvider components={components}>
        <Content />
      </MDXProvider>
    </Container>
  </JeedProvider>
)
export default hot(module)(App)
