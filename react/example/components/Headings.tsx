import React, { useState, useMemo, useLayoutEffect } from "react"
import PropTypes from "prop-types"

import { List } from "semantic-ui-react"

import { useElementTracker, Component } from "@cs125/element-tracker"

import Children from "react-children-utilities"
import slugify from "slugify"

export const UpdateHash: React.FC<{ tags: string[] }> = ({ tags }) => {
  const { components } = useElementTracker()

  useMemo(() => {
    const firstVisible = components?.find((c) => tags.includes(c.tag) && c.visible)
    if (firstVisible) {
      history.replaceState(null, "", `#${firstVisible.id}`)
    }
  }, [components])

  return null
}

export const SidebarMenu: React.FC = () => {
  const { components } = useElementTracker()
  const [headers, setHeaders] = useState<(Component & { active: boolean })[]>([])

  useLayoutEffect(() => {
    if (!components) {
      setHeaders([])
      return
    }
    const newHeaders = components
      .filter((c) => c.tag === "h2")
      .map((c) => {
        return { ...c, active: false }
      })
    if (newHeaders.length === 0) {
      setHeaders([])
      return
    }
    const onScreenHeaders = newHeaders.filter((c) => c.top >= 0)
    const offScreenHeaders = newHeaders.filter((c) => c.top < 0)
    if (onScreenHeaders.length > 0 && onScreenHeaders[0].visible) {
      onScreenHeaders[0].active = true
    } else if (offScreenHeaders.length > 0) {
      offScreenHeaders[offScreenHeaders.length - 1].active = true
    } else {
      newHeaders[0].active = true
    }
    setHeaders(newHeaders)
  }, [components])

  return (
    <List size="large">
      {headers.map((header, i) => {
        const headerLocation = `${location.href.replace(location.hash, "")}#${header.id}`
        return (
          <List.Item
            onClick={(): void => {
              window.location.href = headerLocation
            }}
            key={i}
          >
            {header.active ? <strong>{header.text}</strong> : <span>{header.text}</span>}
          </List.Item>
        )
      })}
    </List>
  )
}

interface HeadingProps {
  id?: string
  children: React.ReactNode
}
const Heading = (tag: string): React.FC<HeadingProps> => {
  const WrappedHeading: React.FC<HeadingProps> = (props) => {
    const { children } = props
    const id = props.id || slugify(Children.onlyText(children), { lower: true })
    return React.createElement(tag, { id }, children)
  }
  WrappedHeading.propTypes = {
    id: PropTypes.string,
    children: PropTypes.node.isRequired,
  }
  return WrappedHeading
}

export const headings = {
  h1: Heading("h1"),
  h2: Heading("h2"),
  h3: Heading("h3"),
  h4: Heading("h4"),
  h5: Heading("h5"),
  h6: Heading("h6"),
}
