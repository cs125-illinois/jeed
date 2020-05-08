import React, { useState, useEffect, useContext } from "react"
import PropTypes from "prop-types"

import { ServerStatus, Request, Response } from "./types"

require("es6-promise").polyfill()
require("isomorphic-fetch")

export interface JeedContext {
  connected: boolean
  status: ServerStatus | undefined
  run: (request: Request, validate?: boolean) => Promise<Response>
}
export const JeedContext = React.createContext<JeedContext>({
  connected: false,
  status: undefined,
  run: (): Promise<Response> => {
    throw new Error("Jeed Context not available")
  },
})

async function getStatus(server: string, validate = true): Promise<ServerStatus> {
  const status = await (await fetch(server)).json()
  return validate ? ServerStatus.check(status) : (status as ServerStatus)
}

export async function postRequest(server: string, request: Request, validate = true): Promise<Response> {
  request = validate ? Request.check(request) : request
  const response = await (
    await fetch(server, {
      method: "post",
      body: JSON.stringify(request),
      headers: { "Content-Type": "application/json" },
      credentials: "include",
    })
  ).json()
  return validate ? Response.check(response) : (response as Response)
}

interface JeedProviderProps {
  server: string
  children: React.ReactNode
}

export const JeedProvider: React.FC<JeedProviderProps> = ({ server, children }) => {
  const [status, setStatus] = useState<ServerStatus | undefined>(undefined)

  useEffect(() => {
    getStatus(server)
      .then((status) => setStatus(status))
      .catch(() => setStatus(undefined))
  }, [])

  const run = async (request: Request, validate = true): Promise<Response> => {
    return postRequest(server, request, validate).then((response) => {
      setStatus(response.status)
      return response
    })
  }

  return (
    <JeedContext.Provider value={{ status, connected: status !== undefined, run }}>{children}</JeedContext.Provider>
  )
}
JeedProvider.propTypes = {
  server: PropTypes.string.isRequired,
  children: PropTypes.node.isRequired,
}

export const useJeed = (): JeedContext => {
  return useContext(JeedContext)
}

export {
  Task,
  FlatSource,
  Permission,
  FileType,
  Location,
  SourceRange,
  SourceLocation,
  Interval,
  ServerStatus,
  SnippetArguments,
  CompilationArguments,
  KompilationArguments,
  CheckstyleArguments,
  KtLintArguments,
  ClassLoaderConfiguration,
  SourceExecutionArguments,
  TaskArguments,
  Request,
  Snippet,
  TemplatedSourceResult,
  CompilationMessage,
  CompiledSourceResult,
  CheckstyleError,
  CheckstyleResults,
  KtlintError,
  KtlintResults,
  FlatClassComplexity,
  FlatMethodComplexity,
  FlatComplexityResult,
  FlatComplexityResults,
  ThrownException,
  Console,
  OutputLine,
  PermissionRequest,
  SourceTaskResults,
  CompletedTasks,
  TemplatingError,
  TemplatingFailed,
  SnippetTransformationError,
  SnippetTransformationFailed,
  CompilationError,
  CompilationFailed,
  CheckstyleFailed,
  KtlintFailed,
  SourceError,
  ComplexityFailed,
  ExecutionFailedResult,
  FailedTasks,
  Response,
} from "./types"

export { terminalOutput } from "./output"
