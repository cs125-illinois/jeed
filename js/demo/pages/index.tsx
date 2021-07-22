import { terminalOutput } from "@cs124/jeed-output"
import { JeedProvider, useJeed } from "@cs124/jeed-react"
import { Request, Response, Task, TaskArguments } from "@cs124/jeed-types"
import { GoogleLoginProvider, useGoogleLogin, WithGoogleTokens } from "@cs124/react-google-login"
import dynamic from "next/dynamic"
import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { IAceEditor } from "react-ace/lib/types"

const AceEditor = dynamic(() => import("react-ace"), { ssr: false })

const DEFAULT_JAVA_SNIPPET = `System.out.println("Hello, Java!");`
const DEFAULT_KOTLIN_SNIPPET = `println("Hello, Kotlin!")`
const DEFAULT_JAVA_CLASS = `public class Example {
  public static void main() {
    System.out.println("Hello, Java!");
  }
}
`
const DEFAULT_KOTLIN_CLASS = `fun main() {
  println("Hello, Kotlin!")
}
`

const LoginButton: React.FC = () => {
  const { isSignedIn, auth, ready } = useGoogleLogin()
  if (!ready) {
    return null
  }
  return (
    <button onClick={() => (isSignedIn ? auth?.signOut() : auth?.signIn())}>{isSignedIn ? "Signout" : "Signin"}</button>
  )
}
const JeedDemo: React.FC = () => {
  const [value, setValue] = useState("")
  const [mode, setMode] = useState<"java" | "kotlin">("java")
  const [snippet, setSnippet] = useState(true)
  const [response, setResponse] = useState<{ response?: Response; error?: string } | undefined>()
  const { run: runJeed } = useJeed()
  const aceRef = useRef<IAceEditor>()

  const run = useCallback(
    async (job: "run" | "lint" | "complexity" | "features") => {
      if (!aceRef.current) {
        return
      }
      const content = aceRef.current.getValue()
      if (content.trim() === "") {
        setResponse(undefined)
        return
      }
      let tasks: Task[]
      let args: TaskArguments = { snippet: { indent: 2 } }
      if (job === "run") {
        tasks = mode === "java" ? ["compile", "execute"] : ["kompile", "execute"]
      } else if (job === "lint") {
        tasks = mode === "java" ? ["checkstyle"] : ["ktlint"]
      } else if (job === "complexity") {
        tasks = ["complexity"]
      } else if (job === "features") {
        if (mode !== "java") {
          throw Error("features not yet supported for Kotlin")
        }
        tasks = ["features"]
      } else {
        throw Error(mode)
      }
      if (tasks.includes("checkstyle")) {
        args.snippet!.indent = 2
        args.checkstyle = { failOnError: true }
      }
      if (tasks.includes("ktlint")) {
        args.ktlint = { failOnError: true }
      }
      if (mode === "kotlin") {
        args.snippet!.fileType = "KOTLIN"
      }
      const request: Request = {
        label: "demo",
        tasks,
        ...(snippet && { snippet: content }),
        ...(!snippet && { sources: [{ path: `Example.${mode === "java" ? "java" : "kt"}`, contents: content }] }),
        arguments: args,
      }
      try {
        const response = await runJeed(request, true)
        setResponse({ response })
      } catch (error) {
        setResponse({ error })
      }
    },
    [mode, snippet, runJeed]
  )

  const output = useMemo(
    () =>
      response?.response
        ? terminalOutput(response.response)
        : response?.error
        ? { output: response?.error, level: "error" }
        : undefined,
    [response]
  )

  const commands = useMemo(() => {
    return [
      {
        name: "gotoline",
        exec: (): boolean => {
          return false
        },
        bindKey: { win: "", mac: "" },
      },
      {
        name: "run",
        bindKey: { win: "Ctrl-Enter", mac: "Ctrl-Enter" },
        exec: () => run("run"),
        readOnly: true,
      },
      {
        name: "close",
        bindKey: { win: "Esc", mac: "Esc" },
        exec: () => setResponse(undefined),
        readOnly: true,
      },
    ]
  }, [run])

  useEffect(() => {
    commands.forEach((command) => {
      if (!aceRef.current) {
        return
      }
      aceRef.current?.commands.addCommand(command)
    })
  }, [commands])

  useEffect(() => {
    if (mode === "java") {
      setValue(snippet ? DEFAULT_JAVA_SNIPPET : DEFAULT_JAVA_CLASS)
    } else {
      setValue(snippet ? DEFAULT_KOTLIN_SNIPPET : DEFAULT_KOTLIN_CLASS)
    }
    setResponse(undefined)
  }, [mode, snippet])

  return (
    <>
      <AceEditor
        mode={mode}
        theme="github"
        width="100%"
        height="16rem"
        minLines={16}
        maxLines={Infinity}
        value={value}
        showPrintMargin={false}
        onBeforeLoad={(ace) => {
          ace.config.set("basePath", `https://cdn.jsdelivr.net/npm/ace-builds@${ace.version}/src-min-noconflict`)
        }}
        onLoad={(ace) => {
          aceRef.current = ace
        }}
        onChange={setValue}
        commands={commands}
        setOptions={{ tabSize: 2 }}
      />
      <div style={{ marginTop: 8 }}>
        <button
          onClick={() => {
            run("run")
          }}
          style={{ marginRight: 8 }}
        >
          Run
        </button>
        <button
          onClick={() => {
            run("lint")
          }}
          style={{ marginRight: 8 }}
        >
          {mode === "java" ? "checkstyle" : "ktlint"}
        </button>
        <button
          onClick={() => {
            run("complexity")
          }}
          style={{ marginRight: 8 }}
        >
          Complexity
        </button>
        {mode === "java" && (
          <button
            onClick={() => {
              run("features")
            }}
            style={{ marginRight: 8 }}
          >
            Features
          </button>
        )}
        <div style={{ float: "right" }}>
          <button style={{ marginRight: 8 }} onClick={() => setSnippet(!snippet)}>
            {snippet ? "Source" : "Snippet"}
          </button>
          <button onClick={() => (mode === "java" ? setMode("kotlin") : setMode("java"))}>
            {mode === "java" ? "Kotlin" : "Java"}
          </button>
        </div>
      </div>
      {output !== undefined && (
        <div className="output">
          <span className={output.level}>{output.output}</span>
        </div>
      )}
      {response?.response && (
        <AceEditor
          readOnly
          theme="github"
          mode="json"
          height="32rem"
          value={JSON.stringify(response.response, null, 2)}
        />
      )}
    </>
  )
}

export default function Home() {
  return (
    <GoogleLoginProvider clientConfig={{ client_id: process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID as string }}>
      <WithGoogleTokens>
        {({ idToken }) => (
          <JeedProvider googleToken={idToken} server={process.env.NEXT_PUBLIC_JEED_SERVER as string}>
            <h2>Jeed Demo</h2>
            <div style={{ marginBottom: 8 }}>
              <LoginButton />
            </div>
            <JeedDemo />
          </JeedProvider>
        )}
      </WithGoogleTokens>
    </GoogleLoginProvider>
  )
}
