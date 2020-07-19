export const pathPrefix = "/jeed"

import { name, description } from "../package.json"

export const siteMetadata = { title: name, description }
export const plugins = [
  "@cs125/gatsby-theme-cs125",
  "gatsby-plugin-typescript",
  {
    resolve: "gatsby-alias-imports",
    options: {
      aliases: {
        react: "./node_modules/react",
        "@cs125/jeed": "..",
      },
    },
  },
]
