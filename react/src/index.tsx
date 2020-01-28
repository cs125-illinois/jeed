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
  }),
  kompilation: io.partial({
    verbose: io.boolean,
    allWarningsAsErrors: io.boolean,
  }),
  checkstyle: io.partial({
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
  execute: null,
})
export type Task = io.TypeOf<typeof Task>

const Job = io.intersection([
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
export type Job = io.TypeOf<typeof Job>

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
  lastJob: io.union([Instant, io.undefined]),
  versions: io.partial({
    jeed: io.string,
    server: io.string,
    compiler: io.string,
  }),
  counts: io.partial({
    submittedJobs: io.Int,
    completedJobs: io.Int,
    savedJobs: io.Int,
  }),
  auth: io.partial({
    none: io.boolean,
    google: io.partial({
      hostedDomain: io.string,
      clientID: io.string,
    }),
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
const Result = io.intersection([
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
export type Result = io.TypeOf<typeof Result>

export interface JeedContext {
  status: ServerStatus | null
  connected: boolean
  run: (job: Job) => Promise<Result>
}
const runNothing = (): Promise<Result> => {
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
  children: React.ReactNode
}
export const JeedProvider: React.FC<JeedProviderProps> = ({ server, defaultArguments = {}, children }) => {
  const [connected, setConnected] = useState<boolean>(false)
  const [status, setStatus] = useState<ServerStatus | null>(null)

  const jeedDefaultArguments = pipe(
    TaskArguments.decode(defaultArguments || {}),
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

  const run = (job: Job): Promise<Result> => {
    job.arguments = Object.assign({}, job.arguments, jeedDefaultArguments)

    console.debug(job)

    const jeedJob = pipe(
      Job.decode(job),
      getOrElse<io.Errors, Job>(errors => {
        throw new Error("Invalid Jeed job:\n" + failure(errors).join("\n"))
      })
    )
    return fetch(server, {
      method: "post",
      body: JSON.stringify(jeedJob),
      headers: { "Content-Type": "application/json" },
    })
      .then(response => {
        return response.json()
      })
      .then(result => {
        console.debug(result)

        const jeedResult = pipe(
          Result.decode(result),
          getOrElse<io.Errors, Result>(errors => {
            throw new Error("Invalid Jeed result:\n" + failure(errors).join("\n"))
          })
        )

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
  children: PropTypes.node.isRequired,
}

export const useJeed = (): JeedContext => {
  return useContext(JeedContext)
}

interface Props {
  result: Result | undefined
}

function getOriginalLine(job: Job, line: number, source?: string): string {
  if (job.snippet) {
    return job.snippet.split("\n")[line - 1]
  }
  for (const { path, contents } of job.sources || []) {
    if (source === path || source === `/${path}`) {
      return contents.split("\n")[line - 1]
    }
  }
  throw new Error(`Couldn't find line ${line} in source ${source}`)
}
function resultToTerminalOutput(result: Result): string {
  const { job } = result
  if (result.failed.snippet) {
    const output = result.failed.snippet.errors
      .map(({ line, column, message }) => {
        const originalLine = getOriginalLine(job, line)
        return `Line ${line}: error: ${message}
${originalLine ? originalLine + "\n" + new Array(column).join(" ") + "^" : ""}`
      })
      .join("\n")
    const errorCount = Object.keys(result.failed.snippet.errors).length
    return `${output}
${errorCount} error${errorCount > 1 ? "s" : ""}`
  } else if (result.failed.compilation || result.failed.kompilation) {
    const output =
      (result.failed.compilation || result.failed.kompilation)?.errors
        .map(({ location: { source, line, column }, message }) => {
          const originalLine = getOriginalLine(job, line, source)
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
    const errorCount = Object.keys((result.failed.compilation || result.failed.kompilation)?.errors || {}).length
    return `${output}
${errorCount} error${errorCount > 1 ? "s" : ""}`
  } else if (result.failed.checkstyle) {
    const output =
      result.failed.checkstyle?.errors
        .map(({ location: { source, line }, message }) => {
          return `${source === "" ? "Line " : `${source}:`}${line}: checkstyle error: ${message}`
        })
        .join("\n") || ""
    const errorCount = Object.keys(result.failed.checkstyle?.errors || {}).length
    return `${output}
${errorCount} error${errorCount > 1 ? "s" : ""}`
  } else if (result.failed.execution) {
    if (result.failed.execution.classNotFound) {
      return `Error: could not find class ${result.failed.execution.classNotFound}`
    } else if (result.failed.execution.methodNotFound) {
      return `Error: could not find method ${result.failed.execution.methodNotFound}`
    } else if (result.failed.execution.threw) {
      return `Error: ${result.failed.execution.threw}`
    } else {
      return `Something unexpected went wrong...`
    }
  }

  if (Object.keys(result.failed).length === 0 && result.completed.execution) {
    const output = result.completed.execution.outputLines.map(({ line }) => line)
    if (result.completed.execution.timeout) {
      output.push("(Program timed out)")
    }
    if (result.completed.execution.truncatedLines > 0) {
      output.push(`(${result.completed.execution.truncatedLines} lines were truncated)`)
    }
    return output.join("\n")
  }

  console.error(`Nothing failed but no success result either...`)
  return ""
}
export const TerminalOutput: React.FC<Props> = ({ result }) => {
  return result === undefined ? null : <pre>{resultToTerminalOutput(result)}</pre>
}
TerminalOutput.propTypes = {
  result: (props, propName): Error | null => {
    if (!props[propName]) {
      return null
    }
    try {
      pipe(
        Result.decode(props[propName]),
        getOrElse<io.Errors, Result>(errors => {
          throw new Error("Invalid Jeed result:\n" + failure(errors).join("\n"))
        })
      )
    } catch (e) {
      return e
    }
    return null
  },
}
