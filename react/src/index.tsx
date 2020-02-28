import React, { useState, useEffect, useContext } from "react"
import PropTypes from "prop-types"

import * as io from "io-ts"
import { failure } from "io-ts/lib/PathReporter"
import { pipe } from "fp-ts/lib/pipeable"
import { either, getOrElse } from "fp-ts/lib/Either"

const FlatSource = io.type({
  path: io.string,
  contents: io.string,
})
export type FlatSource = io.TypeOf<typeof FlatSource>

const Permission = io.intersection([
  io.type({
    klass: io.string,
    name: io.string,
  }),
  io.partial({
    actions: io.string,
  }),
])
const TaskArguments = io.partial({
  snippet: io.partial({
    indent: io.number,
  }),
  compilation: io.partial({
    wError: io.boolean,
    Xlint: io.string,
    useCache: io.boolean,
    waitForCache: io.boolean,
  }),
  kompilation: io.partial({
    verbose: io.boolean,
    allWarningsAsErrors: io.boolean,
    useCache: io.boolean,
    waitForCache: io.boolean,
  }),
  checkstyle: io.partial({
    sources: io.array(io.string),
    failOnError: io.boolean,
  }),
  ktlint: io.partial({
    sources: io.array(io.string),
    failOnError: io.boolean,
  }),
  execution: io.partial({
    klass: io.string,
    method: io.string,
    timeout: io.number,
    permissions: io.array(Permission),
    maxExtraThreads: io.number,
    maxOutputLines: io.number,
    classLoaderConfiguration: io.partial({
      whitelistedClasses: io.array(io.string),
      blacklistedClasses: io.array(io.string),
      unsafeExceptions: io.array(io.string),
    }),
  }),
})

const PartialTaskArguments = io.partial(TaskArguments.props)
export type TaskArguments = io.TypeOf<typeof PartialTaskArguments>

const Task = io.keyof({
  template: null,
  snippet: null,
  compile: null,
  kompile: null,
  checkstyle: null,
  ktlint: null,
  execute: null,
})
export type Task = io.TypeOf<typeof Task>

const Request = io.intersection([
  io.type({
    label: io.string,
    tasks: io.array(Task),
  }),
  io.partial({
    snippet: io.string,
    sources: io.array(FlatSource),
    templates: io.array(FlatSource),
    arguments: TaskArguments,
    authToken: io.string,
    waitForSave: io.boolean,
  }),
])
export type Request = io.TypeOf<typeof Request>

const Instant = new io.Type<Date, string, unknown>(
  "DateFromString",
  (unknown): unknown is Date => unknown instanceof Date,
  (toDecode, context) =>
    toDecode instanceof Date
      ? io.success(toDecode)
      : either.chain(io.string.validate(toDecode, context), string => {
          const date = new Date(string)
          return isNaN(date.getTime()) ? io.failure(toDecode, context) : io.success(date)
        }),
  toEncode => toEncode.toISOString()
)

const ServerStatus = io.partial({
  started: Instant,
  lastRequest: io.union([Instant, io.undefined]),
  versions: io.partial({
    jeed: io.string,
    server: io.string,
    compiler: io.string,
  }),
  counts: io.partial({
    submitted: io.Int,
    completed: io.Int,
    saved: io.Int,
  }),
  auth: io.partial({
    none: io.boolean,
    google: io.partial({
      hostedDomain: io.string,
      clientID: io.string,
    }),
  }),
  cache: io.partial({
    inUse: io.boolean,
    sizeInMB: io.Int,
    hitRate: io.number,
    evictionCount: io.Int,
    averageLoadPenalty: io.number,
  }),
})

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
  compiled: Instant,
  interval: Interval,
  compilerName: io.string,
  cached: io.boolean,
})
const CheckstyleError = io.type({
  severity: io.string,
  location: SourceLocation,
  message: io.string,
})
const KtLintError = io.type({
  ruleId: io.string,
  detail: io.string,
  location: SourceLocation,
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
const Response = io.intersection([
  io.type({
    request: Request,
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
      ktlint: io.type({
        errors: io.array(KtLintError),
      }),
      execution: io.intersection([
        io.partial({
          returned: io.string,
          threw: ThrownException,
        }),
        io.type({
          klass: io.string,
          method: io.string,
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
        errors: io.array(CheckstyleError),
      }),
      ktlint: io.type({
        errors: io.array(KtLintError),
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
export type Response = io.TypeOf<typeof Response>

export interface JeedContext {
  status: ServerStatus | null
  connected: boolean
  run: (request: Request) => Promise<Response>
}
const runNothing = (): Promise<Response> => {
  throw new Error("Jeed server not connected")
}
export const JeedContext = React.createContext<JeedContext>({
  status: null,
  connected: false,
  run: runNothing,
})
interface JeedProviderProps {
  server: string
  defaultArguments?: TaskArguments
  validate?: boolean
  children: React.ReactNode
}
export const JeedProvider: React.FC<JeedProviderProps> = ({
  server,
  defaultArguments = {},
  validate = false,
  children,
}) => {
  const [connected, setConnected] = useState<boolean>(false)
  const [status, setStatus] = useState<ServerStatus | null>(null)

  const jeedDefaultArguments = validate
    ? pipe(
        TaskArguments.decode(defaultArguments || {}),
        getOrElse<io.Errors, TaskArguments>(errors => {
          throw new Error("Invalid Jeed default arguments:\n" + failure(errors).join("\n"))
        })
      )
    : defaultArguments

  useEffect(() => {
    fetch(server)
      .then(response => response.json())
      .then(status => {
        const jeedServerStatus = validate
          ? pipe(
              ServerStatus.decode(status),
              getOrElse<io.Errors, ServerStatus>(errors => {
                throw new Error("Invalid Jeed server status response:\n" + failure(errors).join("\n"))
              })
            )
          : status
        setConnected(true)
        setStatus(jeedServerStatus as ServerStatus)
      })
      .catch(err => {
        console.error(err)
        setConnected(false)
        setStatus(null)
      })
  }, [])

  const run = (request: Request): Promise<Response> => {
    request.arguments = Object.assign({}, request.arguments, jeedDefaultArguments)

    console.debug(request)

    const jeedRequest = validate
      ? pipe(
          Request.decode(request),
          getOrElse<io.Errors, Request>(errors => {
            throw new Error("Invalid Jeed request:\n" + failure(errors).join("\n"))
          })
        )
      : request

    return fetch(server, {
      method: "post",
      body: JSON.stringify(jeedRequest),
      headers: { "Content-Type": "application/json" },
      credentials: "include",
    })
      .then(response => {
        return response.json()
      })
      .then(response => {
        console.debug(response)

        const jeedResponse = validate
          ? pipe(
              Response.decode(response),
              getOrElse<io.Errors, Response>(errors => {
                throw new Error("Invalid Jeed response:\n" + failure(errors).join("\n"))
              })
            )
          : response

        return jeedResponse
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
  defaultArguments: (props, propName): Error | null => {
    try {
      pipe(
        TaskArguments.decode(props[propName] || {}),
        getOrElse<io.Errors, TaskArguments>(errors => {
          throw new Error("Invalid Jeed task arguments:\n" + failure(errors).join("\n"))
        })
      )
    } catch (e) {
      return e
    }
    return null
  },
  validate: PropTypes.bool,
  children: PropTypes.node.isRequired,
}

export const useJeed = (): JeedContext => {
  return useContext(JeedContext)
}

function getOriginalLine(request: Request, line: number, source?: string): string {
  if (request.snippet) {
    return request.snippet.split("\n")[line - 1]
  }
  for (const { path, contents } of request.sources || []) {
    if (source === path || source === `/${path}`) {
      return contents.split("\n")[line - 1]
    }
  }
  throw new Error(`Couldn't find line ${line} in source ${source}`)
}
export function responseToTerminalOutput(response: Response | undefined): string {
  if (!response) {
    return ""
  }
  const { request } = response
  if (response.failed.snippet) {
    const output = response.failed.snippet.errors
      .map(({ line, column, message }) => {
        const originalLine = getOriginalLine(request, line)
        return `Line ${line}: error: ${message}
${originalLine ? originalLine + "\n" + new Array(column).join(" ") + "^" : ""}`
      })
      .join("\n")
    const errorCount = Object.keys(response.failed.snippet.errors).length
    return `${output}
${errorCount} error${errorCount > 1 ? "s" : ""}`
  } else if (response.failed.compilation || response.failed.kompilation) {
    const output =
      (response.failed.compilation || response.failed.kompilation)?.errors
        .map(({ location: { source, line, column }, message }) => {
          const originalLine = getOriginalLine(request, line, source)
          const firstErrorLine = message
            .split("\n")
            .slice(0, 1)
            .join()
          const restOfError = message
            .split("\n")
            .slice(1)
            .filter(line => {
              return !(source === "" && line.trim().startsWith("location: class"))
            })
            .join("\n")
          return `${source === "" ? "Line " : `${source}:`}${line}: error: ${firstErrorLine}
${originalLine ? originalLine + "\n" + new Array(column).join(" ") + "^" : ""}${restOfError ? "\n" + restOfError : ""}`
        })
        .join("\n") || ""
    const errorCount = Object.keys((response.failed.compilation || response.failed.kompilation)?.errors || {}).length
    return `${output}
${errorCount} error${errorCount > 1 ? "s" : ""}`
  } else if (response.failed.checkstyle) {
    const output =
      response.failed.checkstyle?.errors
        .map(({ location: { source, line }, message }) => {
          return `${source === "" ? "Line " : `${source}:`}${line}: checkstyle error: ${message}`
        })
        .join("\n") || ""
    const errorCount = Object.keys(response.failed.checkstyle?.errors || {}).length
    return `${output}
${errorCount} error${errorCount > 1 ? "s" : ""}`
  } else if (response.failed.ktlint) {
    const output =
      response.failed.ktlint?.errors
        .map(({ location: { source, line }, detail }) => {
          return `${source === "" ? "Line " : `${source}:`}${line}: ktlint error: ${detail}`
        })
        .join("\n") || ""
    const errorCount = Object.keys(response.failed.ktlint?.errors || {}).length
    return `${output}
${errorCount} error${errorCount > 1 ? "s" : ""}`
  } else if (response.failed.execution) {
    if (response.failed.execution.classNotFound) {
      return `Error: could not find class ${response.failed.execution.classNotFound}`
    } else if (response.failed.execution.methodNotFound) {
      return `Error: could not find method ${response.failed.execution.methodNotFound}`
    } else if (response.failed.execution.threw) {
      return `Error: ${response.failed.execution.threw}`
    } else {
      return `Something unexpected went wrong...`
    }
  }

  if (Object.keys(response.failed).length === 0 && response.completed.execution) {
    const output = response.completed.execution.outputLines.map(({ line }) => line)
    if (response.completed.execution.timeout) {
      output.push("(Program timed out)")
    }
    if (response.completed.execution.truncatedLines > 0) {
      output.push(`(${response.completed.execution.truncatedLines} lines were truncated)`)
    }
    return output.join("\n")
  }

  console.error(`Nothing failed but no success result either...`)
  return ""
}
