import React, { useState, useEffect, useContext } from "react"
import PropTypes from "prop-types"

import * as io from "io-ts"
import excess from "io-ts-excess"
import { failure } from "io-ts/lib/PathReporter"
import { pipe } from "fp-ts/lib/pipeable"
import { either, getOrElse } from "fp-ts/lib/Either"

const FlatSource = excess(
  io.type({
    path: io.string,
    contents: io.string,
  })
)
export type FlatSource = io.TypeOf<typeof FlatSource>

const Permission = io.intersection([
  io.type({
    klass: io.string,
    name: io.string,
  }),
  io.partial({
    actions: io.union([io.string, io.null]),
  }),
])
const TaskArguments = excess(
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
          permissions: io.union([io.array(Permission), io.null]),
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
export type TaskArguments = io.TypeOf<typeof TaskArguments>

const Task = io.keyof({
  template: null,
  snippet: null,
  compile: null,
  kompile: null,
  checkstyle: null,
  execute: null,
})
const Job = io.intersection([
  io.type({
    label: io.string,
    tasks: io.array(Task),
  }),
  io.partial({
    snippet: io.string,
    source: io.array(FlatSource),
    templates: io.array(FlatSource),
    arguments: TaskArguments,
    authToken: io.string,
    waitForSave: io.boolean,
  }),
])
export type Job = io.TypeOf<typeof Job>

const Instant = new io.Type<Date, string, unknown>(
  "DateFromString",
  (unknown): unknown is Date => unknown instanceof Date,
  (toDecode, context) =>
    either.chain(io.string.validate(toDecode, context), string => {
      const date = new Date(string)
      return isNaN(date.getTime()) ? io.failure(toDecode, context) : io.success(date)
    }),
  toEncode => toEncode.toISOString()
)

const ServerStatus = excess(
  io.type({
    started: Instant,
    lastJob: io.union([Instant, io.undefined]),
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
export type ServerStatus = io.TypeOf<typeof ServerStatus>

const Location = io.type({ line: io.number, column: io.number })
const SourceRange = io.intersection([
  io.partial({
    source: io.string,
  }),
  io.type({
    start: Location,
    end: Location,
  }),
])
const Interval = io.type({ start: Instant, end: Instant })
const SourceLocation = io.type({
  source: io.string,
  line: io.number,
  column: io.number,
})
const CompilationMessage = io.type({
  kind: io.string,
  location: SourceLocation,
  message: io.string,
})
const CompiledSourceResult = io.type({
  messages: io.array(CompilationMessage),
  interval: Interval,
  compilerName: io.string,
})
const CheckstyleError = io.type({
  severity: io.string,
  location: SourceLocation,
  message: io.string,
})
const ThrownException = io.intersection([io.type({ klass: io.string }), io.partial({ message: io.string })])
const Console = io.keyof({
  STDOUT: null,
  STDERR: null,
})
const OutputLine = io.type({
  console: Console,
  line: io.string,
  timestamp: Instant,
  thread: io.number,
})
const PermissionRequest = io.type({
  permission: Permission,
  granted: io.boolean,
})
const CompilationFailed = io.type({
  errors: io.array(io.type({ location: SourceLocation, message: io.string })),
})
const JeedResult = io.intersection([
  io.type({
    job: Job,
    status: ServerStatus,
    completed: io.partial({
      snippet: io.type({
        sources: io.record(io.string, io.string),
        originalSource: io.string,
        rewrittenSource: io.string,
        snippetRange: SourceRange,
        wrappedClassName: io.string,
        looseCodeMethodName: io.string,
      }),
      template: io.type({
        sources: io.record(io.string, io.string),
        originalSources: io.record(io.string, io.string),
      }),
      compilation: CompiledSourceResult,
      kompilation: CompiledSourceResult,
      checkstyle: io.type({
        errors: io.array(CheckstyleError),
      }),
      execution: io.intersection([
        io.partial({
          returned: io.string,
          threw: ThrownException,
        }),
        io.type({
          timeout: io.boolean,
          interval: Interval,
          executionInterval: Interval,
          truncatedLines: io.number,
          outputLines: io.array(OutputLine),
          permissionRequests: io.array(PermissionRequest),
        }),
      ]),
    }),
    completedTasks: io.array(Task),
    failed: io.partial({
      template: io.type({
        errors: io.array(io.type({ name: io.string, line: io.number, column: io.number, message: io.string })),
      }),
      snippet: io.type({
        errors: io.array(io.type({ line: io.number, column: io.number, message: io.string })),
      }),
      compilation: CompilationFailed,
      kompilation: CompilationFailed,
      checkstyle: io.type({
        errors: io.array(io.type({ severity: io.string, location: SourceLocation, message: io.string })),
      }),
      execution: io.partial({
        classNotFound: io.string,
        methodNotFound: io.string,
        threw: io.string,
      }),
    }),
    failedTasks: io.array(Task),
    interval: Interval,
  }),
  io.partial({
    email: io.string,
  }),
])
export type JeedResult = io.TypeOf<typeof JeedResult>

interface JeedContext {
  status: ServerStatus | null
  connected: boolean
  run: (job: Job) => void
}
const runNothing = (): void => {} // eslint-disable-line @typescript-eslint/no-empty-function
const JeedContext = React.createContext<JeedContext>({
  status: null,
  connected: false,
  run: runNothing,
})
interface JeedProviderProps {
  server: string
  defaultArguments?: TaskArguments
  children: React.ReactNode
}
export const JeedProvider: React.FC<JeedProviderProps> = ({ server, defaultArguments = {}, children }) => {
  const [connected, setConnected] = useState<boolean>(false)
  const [status, setStatus] = useState<ServerStatus | null>(null)

  const jeedDefaultArguments = pipe(
    TaskArguments.decode(defaultArguments),
    getOrElse<io.Errors, TaskArguments>(errors => {
      throw new Error("Invalid Jeed default arguments:\n" + failure(errors).join("\n"))
    })
  )

  useEffect(() => {
    fetch(server)
      .then(response => response.json())
      .then(status => {
        const jeedServerStatus = pipe(
          ServerStatus.decode(status),
          getOrElse<io.Errors, ServerStatus>(errors => {
            throw new Error("Invalid Jeed server status response:\n" + failure(errors).join("\n"))
          })
        )
        setConnected(true)
        setStatus(jeedServerStatus as ServerStatus)
      })
      .catch(err => {
        console.error(err)
        setConnected(false)
        setStatus(null)
      })
  }, [])

  const run = (job: Job): void => {
    job.arguments = Object.assign({}, job.arguments, jeedDefaultArguments)

    console.debug(job)

    const jeedJob = pipe(
      Job.decode(job),
      getOrElse<io.Errors, Job>(errors => {
        throw new Error("Invalid Jeed job:\n" + failure(errors).join("\n"))
      })
    )
    fetch(server, {
      method: "post",
      body: JSON.stringify(jeedJob),
      headers: { "Content-Type": "application/json" },
    })
      .then(response => {
        return response.json()
      })
      .then(data => {
        const jeedResult = pipe(
          JeedResult.decode(data),
          getOrElse<io.Errors, JeedResult>(errors => {
            throw new Error("Invalid Jeed result:\n" + failure(errors).join("\n"))
          })
        )
        console.debug(jeedResult)
        return jeedResult
      })
  }

  return (
    <JeedContext.Provider value={{ status, connected, run: connected ? run : runNothing }}>
      {children}
    </JeedContext.Provider>
  )
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
      permissions: PropTypes.arrayOf(
        PropTypes.exact({
          klass: PropTypes.string.isRequired,
          name: PropTypes.string.isRequired,
          actions: PropTypes.string,
        }).isRequired
      ),
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

export const withJeed = (): JeedContext => {
  return useContext(JeedContext)
}
