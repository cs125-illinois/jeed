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
import { JeedAce, JeedLanguage } from "./components"

interface CodeBlockProps {
  className?: string
  jeed?: boolean
  children: React.ReactNode
}
const CodeBlock: React.FC<CodeBlockProps> = props => {
  const { className, jeed, children, ...jeedProps } = props
  const language = className?.replace(/language-/, "") || ""
  if (jeed && ["java", "kotlin"].includes(language)) {
    return (
      <JeedAce
        mode={language as JeedLanguage}
        highlightActiveLine={false}
        showPrintMargin={false}
        width="100%"
        height="100px"
        maxLines={Infinity}
        autoMin
        tabSize={2}
        {...jeedProps}
      >
        {children}
      </JeedAce>
    )
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
const PreBlock: React.FC<{ children: React.ReactNode }> = props => <div>{props.children}</div>
PreBlock.propTypes = {
  children: PropTypes.node.isRequired,
}
const components = {
  code: CodeBlock,
  pre: PreBlock,
}
const PaddedContainer = styled(Container)({
  paddingTop: 16,
})
const App: React.SFC = () => (
  <JeedProvider
    server="http://localhost:8888"
    defaultArguments={{ snippet: { indent: 2 }, checkstyle: { failOnError: true } }}
  >
    <PaddedContainer text>
      <MDXProvider components={components}>
        <Content />
      </MDXProvider>
    </PaddedContainer>
  </JeedProvider>
)

ReactDOM.render(<App />, document.getElementById("root"))
