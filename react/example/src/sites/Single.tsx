import React from "react"
import PropTypes from "prop-types"

import { SingleQuery } from "../../graphql-types"
import { useStaticQuery } from "gatsby"
import { graphql } from "gatsby"
import { FixedObject } from "gatsby-image"

import { Single as SingleLayout } from "@cs125/gatsby-theme-cs125/src/layouts"

export const Single: React.FC = ({ children }) => {
  const data: SingleQuery = useStaticQuery(graphql`
    query Single {
      site {
        siteMetadata {
          title
          description
        }
      }
      file(relativePath: { eq: "logo.png" }, sourceInstanceName: { eq: "images" }) {
        childImageSharp {
          fixed(width: 48, height: 48) {
            base64
            width
            height
            src
            srcSet
          }
        }
      }
    }
  `)
  return (
    <SingleLayout
      title={data.site?.siteMetadata?.title as string}
      description={data.site?.siteMetadata?.description as string}
      logo={data.file?.childImageSharp?.fixed as FixedObject}
    >
      {children}
    </SingleLayout>
  )
}
Single.propTypes = {
  children: PropTypes.node.isRequired,
}
