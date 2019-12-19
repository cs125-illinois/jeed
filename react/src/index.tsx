import React, { useState, useEffect } from "react"
import PropTypes from "prop-types"

import * as io from "io-ts"
import excess from "io-ts-excess"
import { failure } from "io-ts/lib/PathReporter"
import { pipe } from "fp-ts/lib/pipeable"
import { either, getOrElse } from "fp-ts/lib/Either"

const JeedFlatSource = excess(
  io.type({
    path: io.string,
    contents: io.string,
  })
)
export type JeedFlatSource = io.TypeOf<typeof JeedFlatSource>

const JeedTaskArguments = excess(
  io.type({
    snippet: io.union([
      excess(
        io.type({
          indent: io.union([io.number, io.null]),
        })
      ),
      io.null,
      io.undefined,
    ]),
    compilation: io.union([
      excess(
        io.type({
          wError: io.union([io.boolean, io.null]),
          Xlint: io.union([io.string, io.null]),
        })
      ),
      io.null,
      io.undefined,
    ]),
    kompilation: io.union([
      excess(
        io.type({
          verbose: io.union([io.boolean, io.null]),
          allWarningsAsErrors: io.union([io.boolean, io.null]),
        })
      ),
      io.null,
      io.undefined,
    ]),
    checkstyle: io.union([
      excess(
        io.type({
          sources: io.union([io.array(io.string), io.null, io.undefined]),
          failOnError: io.union([io.boolean, io.null]),
        })
      ),
      io.null,
      io.undefined,
    ]),
    execution: io.union([
      excess(
        io.type({
          klass: io.union([io.string, io.null]),
          method: io.union([io.string, io.null]),
          timeout: io.union([io.number, io.null]),
          permissions: io.union([io.array(io.string), io.null]),
          maxExtraThreads: io.union([io.number, io.null]),
          maxOutputLines: io.union([io.number, io.null]),
          classLoaderConfiguration: io.union([
            excess(
              io.type({
                whitelistedClasses: io.union([io.array(io.string), io.null]),
                blacklistedClasses: io.union([io.array(io.string), io.null]),
                unsafeExceptions: io.union([io.array(io.string), io.null]),
              })
            ),
            io.null,
            io.undefined,
          ]),
        })
      ),
      io.null,
      io.undefined,
    ]),
  })
)
export type JeedTaskArguments = io.TypeOf<typeof JeedTaskArguments>

const JeedJob = excess(
  io.type({
    tasks: io.array(
      io.keyof({
        template: null,
        snippet: null,
        compile: null,
        kompile: null,
        checkstyle: null,
        execute: null,
      })
    ),
    snippet: io.union([io.string, io.undefined]),
    source: io.union([io.array(JeedFlatSource), io.undefined]),
    templates: io.union([io.array(JeedFlatSource), io.undefined]),
    arguments: io.union([JeedTaskArguments, io.undefined]),
    authToken: io.union([io.string, io.undefined]),
    label: io.string,
    waitForSave: io.union([io.boolean, io.undefined]),
  })
)
export type JeedJob = io.TypeOf<typeof JeedJob>

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

const JeedServerStatus = excess(
  io.type({
    started: DateFromString,
    lastJob: io.union([DateFromString, io.undefined]),
    versions: excess(
      io.type({
        jeed: io.string,
        server: io.string,
        compiler: io.string,
      })
    ),
    counts: excess(
      io.type({
        submittedJobs: io.Int,
        completedJobs: io.Int,
        savedJobs: io.Int,
      })
    ),
    auth: excess(
      io.type({
        none: io.boolean,
        google: excess(
          io.type({
            hostedDomain: io.union([io.string, io.undefined]),
            clientID: io.union([io.string, io.undefined]),
          })
        ),
      })
    ),
  })
)
export type JeedServerStatus = io.TypeOf<typeof JeedServerStatus>

interface JeedContext {
  status: JeedServerStatus | null
  connected: boolean
}
const JeedContext = React.createContext<JeedContext>({
  status: null,
  connected: false,
})
interface JeedProviderProps {
  server: string
  defaultArguments?: JeedTaskArguments
  children: React.ReactNode
}
export const JeedProvider: React.FC<JeedProviderProps> = ({ server, defaultArguments = {}, children }) => {
  const [connected, setConnected] = useState<boolean>(false)
  const [status, setStatus] = useState<JeedServerStatus | null>(null)

  const jeedDefaultArguments = pipe(
    JeedTaskArguments.decode(defaultArguments),
    getOrElse<io.Errors, JeedTaskArguments>(errors => {
      throw new Error("Invalid Jeed default arguments:\n" + failure(errors).join("\n"))
    })
  )
  console.debug(jeedDefaultArguments)

  useEffect(() => {
    fetch(server)
      .then(response => response.json())
      .then(status => {
        const jeedServerStatus = pipe(
          JeedServerStatus.decode(status),
          getOrElse<io.Errors, JeedServerStatus>(errors => {
            throw new Error("Invalid Jeed server status response:\n" + failure(errors).join("\n"))
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

  return <JeedContext.Provider value={{ status, connected }}>{children}</JeedContext.Provider>
}
JeedProvider.propTypes = {
  server: PropTypes.string.isRequired,
  defaultArguments: PropTypes.exact({
    snippet: PropTypes.exact({
      indent: PropTypes.number,
    }),
    compilation: PropTypes.exact({
      wError: PropTypes.bool,
      Xlint: PropTypes.string,
    }),
    kompilation: PropTypes.exact({
      verbose: PropTypes.bool,
      allWarningsAsErrors: PropTypes.bool,
    }),
    checkstyle: PropTypes.exact({
      sources: PropTypes.arrayOf(PropTypes.string.isRequired),
      failOnError: PropTypes.bool,
    }),
    execution: PropTypes.exact({
      klass: PropTypes.string,
      method: PropTypes.string,
      timeout: PropTypes.number,
      permissions: PropTypes.arrayOf(PropTypes.string.isRequired),
      maxExtraThreads: PropTypes.number,
      maxOutputLines: PropTypes.number,
      classLoaderConfiguration: PropTypes.exact({
        whitelistedClasses: PropTypes.arrayOf(PropTypes.string.isRequired),
        blacklistedClasses: PropTypes.arrayOf(PropTypes.string.isRequired),
        unsafeExceptions: PropTypes.arrayOf(PropTypes.string.isRequired),
      }),
    }),
  }),
  children: PropTypes.node.isRequired,
}
