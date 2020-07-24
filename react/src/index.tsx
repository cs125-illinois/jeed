import React, { useState, useEffect, useContext, useCallback } from "react"
import PropTypes from "prop-types"

import { ServerStatus, Request, Response } from "./types"

// eslint-disable-next-line @typescript-eslint/no-var-requires
require("es6-promise").polyfill()
require("isomorphic-fetch")

export interface JeedContext {
  available: boolean
  connected: boolean
  status: ServerStatus | undefined
  run: (request: Request, validate?: boolean) => Promise<Response>
}

interface JeedProviderProps {
  googleToken?: string | undefined
  server: string
  children: React.ReactNode
}

export const JeedProvider: React.FC<JeedProviderProps> = ({ googleToken, server, children }) => {
  const [status, setStatus] = useState<ServerStatus | undefined>(undefined)

  useEffect(() => {
    getStatus(server)
      .then((status) => setStatus(status))
      .catch(() => setStatus(undefined))
  }, [server])

  const run = useCallback(
    async (request: Request, validate = true): Promise<Response> => {
      if (googleToken) {
        request.authToken = googleToken
      }
      return postRequest(server, request, validate)
        .then((response) => {
          setStatus(response.status)
          return response
        })
        .catch((err) => {
          setStatus(undefined)
          throw err
        })
    },
    [googleToken, server]
  )

  return (
    <JeedContext.Provider value={{ available: true, status, connected: status !== undefined, run }}>
      {children}
    </JeedContext.Provider>
  )
}
JeedProvider.propTypes = {
  googleToken: PropTypes.string,
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

export { terminalOutput, getOriginalLine } from "./output"

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

export const JeedContext = React.createContext<JeedContext>({
  available: false,
  connected: false,
  status: undefined,
  run: (): Promise<Response> => {
    throw new Error("Jeed Context not available")
  },
})
