import React from "react"
import PropTypes from "prop-types"

const Lead: React.FC = ({ children }) => {
  return <div style={{ fontSize: "1.3rem", marginBottom: "1.2rem" }}>{children}</div>
}
Lead.propTypes = {
  children: PropTypes.node.isRequired,
}
export default Lead
