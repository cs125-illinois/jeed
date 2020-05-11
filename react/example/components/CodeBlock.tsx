import React from "react"
import PropTypes from "prop-types"

import Children from "react-children-utilities"

import PrismLight from "react-syntax-highlighter/dist/esm/prism-light"
import style from "react-syntax-highlighter/dist/esm/styles/prism/tomorrow"
import bash from "react-syntax-highlighter/dist/esm/languages/prism/bash"
PrismLight.registerLanguage("bash", bash)

interface CodeBlockProps {
  className?: string
  children: React.ReactNode
}
const CodeBlock: React.FC<CodeBlockProps> = (props) => {
  const { className, children } = props
  const language = className?.replace(/language-/, "") || ""
  if (language !== "bash") {
    return <React.Fragment>{children}</React.Fragment>
  }
  const contents = Children.onlyText(children).trim()
  return (
    <PrismLight style={style} language={language} customStyle={{ fontSize: "0.9rem" }}>
      {contents}
    </PrismLight>
  )
}
CodeBlock.propTypes = {
  className: PropTypes.string,
  children: PropTypes.node.isRequired,
}
CodeBlock.defaultProps = {
  className: "",
}
export default CodeBlock
