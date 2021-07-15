import { Request, Response, ServerStatus } from "@cs124/jeed-types"
import React, { useCallback, useContext, useEffect, useState } from "react"

export interface JeedContext {
  available: boolean
  connected: boolean
  status: ServerStatus | undefined
  run: (request: Request, validate?: boolean) => Promise<Response>
}

interface JeedProviderProps {
  server: string
  googleToken?: string | undefined
}

export const JeedProvider: React.FC<JeedProviderProps> = ({ googleToken, server, children }) => {
  const [status, setStatus] = useState<ServerStatus | undefined>(undefined)

  useEffect(() => {
    fetch(server)
      .then((response) => response.json())
      .then((response) => setStatus(response.status))
      .catch(() => setStatus(undefined))
  }, [server])

  const run = useCallback(
    async (request: Request, validate = false): Promise<Response> => {
      request = validate ? Request.check(request) : request
      const response = await fetch(server, {
        method: "post",
        body: JSON.stringify(request),
        headers: Object.assign(
          { "Content-Type": "application/json" },
          googleToken ? { "google-token": googleToken } : null
        ),
      })
        .then((response) => response.json())
        .then((response) => {
          setStatus(response.status)
          return response
        })
        .catch((err) => {
          setStatus(undefined)
          throw err
        })
      return validate ? Response.check(response) : (response as Response)
    },
    [googleToken, server]
  )

  return (
    <JeedContext.Provider value={{ available: true, status, connected: status !== undefined, run }}>
      {children}
    </JeedContext.Provider>
  )
}

export const useJeed = (): JeedContext => {
  return useContext(JeedContext)
}

export const JeedContext = React.createContext<JeedContext>({
  available: false,
  connected: false,
  status: undefined,
  run: () => {
    throw new Error("Jeed Context not available")
  },
})
