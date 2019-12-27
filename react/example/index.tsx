import "react-app-polyfill/ie11"
import "babel-polyfill"
import "semantic-ui-css/semantic.min.css"

import React from "react"
import ReactDOM from "react-dom"
import PropTypes from "prop-types"

import { Container } from "semantic-ui-react"
import styled from "styled-components"

import { MDXProvider } from "@mdx-js/react"
import Content from "./index.mdx"

import SyntaxHighlighter from "react-syntax-highlighter/dist/esm/default-highlight"

import { JeedProvider } from "@cs125/react-jeed"
import { JeedAce } from "./components"

interface CodeBlockProps {
  className?: string
  jeed?: boolean
  children: React.ReactNode
}
const CodeBlock: React.FC<CodeBlockProps> = ({ className, jeed, children }) => {
  const language = className?.replace(/language-/, "") || ""
  if (jeed) {
    return <JeedAce mode={language}>{children}</JeedAce>
  } else {
    return <SyntaxHighlighter language={language}>{children}</SyntaxHighlighter>
  }
}
CodeBlock.propTypes = {
  className: PropTypes.string,
  jeed: PropTypes.bool,
  children: PropTypes.node.isRequired,
}
CodeBlock.defaultProps = {
  className: "",
  jeed: false,
}
const components = {
  code: CodeBlock,
}
const PaddedContainer = styled(Container)({
  paddingTop: 16,
})
const App: React.SFC = () => (
  <JeedProvider server="http://localhost:8888">
    <PaddedContainer text>
      <MDXProvider components={components}>
        <Content />
      </MDXProvider>
    </PaddedContainer>
  </JeedProvider>
)

ReactDOM.render(<App />, document.getElementById("root"))
