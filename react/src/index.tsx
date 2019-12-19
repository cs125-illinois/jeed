import React, { useState, useEffect } from "react"
import PropTypes from "prop-types"

import * as io from "io-ts"
import excess from "io-ts-excess"
import { failure } from "io-ts/lib/PathReporter"
import { pipe } from "fp-ts/lib/pipeable"
import { either, getOrElse } from "fp-ts/lib/Either"

export interface JeedJob {
  source?: Array<JeedFlatSource>
  templates?: Array<JeedFlatSource>
  snippet?: string
  tasks: Set<JeedTask>
}
export interface JeedFlatSource {
  path: string
  contents: string
}
enum JeedTask {
  template = "template",
  snippet = "snippet",
  compile = "compile",
  kompile = "kompile",
  checkstyle = "checkstyle",
  execute = "execute",
}
export interface JeedTaskArguments {
  snippet: {
    indent?: number
  }
  compilation: {
    wError?: boolean
    Xlint?: string
  }
  kompilation: {
    verbose?: boolean
    allWarningsAsErrors?: boolean
  }
  checkstyle: {
    sources?: Set<string> | null
    failOnError?: boolean
  }
  execution: {
    klass?: string | null
    method?: string
    timeout?: number
    permissions?: Set<JeedPermission>
    maxExtraThreads?: number
    maxOutputLines?: number
    classLoaderConfiguration?: {
      whitelistedClasses?: Set<string>
      blacklistedClasses?: Set<string>
      unsafeExceptions?: Set<string>
    }
  }
}
export interface JeedPermission {
  klass: string
  name: string
  actions: string | null
}

const DateFromString = new io.Type<Date, string, unknown>(
  "DateFromString",
  (unknown): unknown is Date => unknown instanceof Date,
  (toDecode, context) =>
    either.chain(io.string.validate(toDecode, context), string => {
      const date = new Date(string)
      return isNaN(date.getTime()) ? io.failure(toDecode, context) : io.success(date)
    }),
  toEncode => toEncode.toISOString()
)
const JeedServerVersions = excess(io.type({ jeed: io.string, server: io.string, compiler: io.string }))
export type JeedServerVersions = io.TypeOf<typeof JeedServerVersions>
const JeedServerCounts = excess(io.type({ submittedJobs: io.Int, completedJobs: io.Int, savedJobs: io.Int }))
export type JeedServerCounts = io.TypeOf<typeof JeedServerCounts>

const JeedServerAuthGoogle = excess(
  io.type({
    hostedDomain: io.union([io.string, io.undefined]),
    clientID: io.union([io.string, io.undefined]),
  })
)
const JeedServerAuth = excess(io.type({ none: io.boolean, google: JeedServerAuthGoogle }))

const JeedServerStatus = excess(
  io.type({
    started: DateFromString,
    lastJob: io.union([DateFromString, io.undefined]),
    versions: JeedServerVersions,
    counts: JeedServerCounts,
    auth: JeedServerAuth,
  })
)

export type JeedServerStatus = {
  started: Date
  lastJob: Date | undefined
  versions: JeedServerVersions
  counts: JeedServerCounts
}

interface JeedContext {
  status: JeedServerStatus | null
  connected: boolean
  setConnected: React.Dispatch<React.SetStateAction<boolean>> | null
}
const JeedContext = React.createContext<JeedContext>({
  status: null,
  connected: false,
  setConnected: null,
})
interface JeedProviderProps {
  server: string
  children: React.ReactNode
}
export const JeedProvider: React.FC<JeedProviderProps> = ({ server, children }) => {
  const [connected, setConnected] = useState<boolean>(false)
  const [status, setStatus] = useState<JeedServerStatus | null>(null)

  useEffect(() => {
    fetch(server)
      .then(response => response.json())
      .then(status => {
        const jeedServerStatus = pipe(
          JeedServerStatus.decode(status),
          getOrElse<io.Errors, JeedServerStatus>(errors => {
            throw new Error(failure(errors).join("\n"))
          })
        )
        setConnected(true)
        setStatus(jeedServerStatus as JeedServerStatus)
      })
      .catch(err => {
        console.error(err)
        setConnected(false)
        setStatus(null)
      })
  }, [])
  return <JeedContext.Provider value={{ status, connected, setConnected }}>{children}</JeedContext.Provider>
}
JeedProvider.propTypes = {
  server: PropTypes.string.isRequired,
  children: PropTypes.node.isRequired,
}
