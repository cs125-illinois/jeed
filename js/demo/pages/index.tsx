import { terminalOutput } from "@cs124/jeed-output"
import { JeedProvider, useJeed } from "@cs124/jeed-react"
import dynamic from "next/dynamic"
import { useCallback, useRef, useState } from "react"
import { IAceEditor, ICommand } from "react-ace/lib/types"
import { Request, Task } from "../../types/dist"

const AceEditor = dynamic(() => import("react-ace"), { ssr: false })

const JeedDemo: React.FC = () => {
  const [mode, setMode] = useState<"java" | "kotlin">("java")
  const [output, setOutput] = useState("")
  const { run: runJeed } = useJeed()
  const aceRef = useRef<IAceEditor>()

  const run = useCallback(async () => {
    if (!aceRef.current) {
      return
    }
    const tasks: Task[] = mode === "java" ? ["compile", "execute"] : ["kompile", "execute"]
    const request: Request = {
      label: "demo",
      tasks,
      snippet: aceRef.current.getValue(),
    }
    const response = await runJeed(request, true)
    const output = terminalOutput(response)
    setOutput(output)
  }, [])

  const commands = [
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
      exec: run,
      readOnly: true,
    },
    {
      name: "close",
      bindKey: { win: "Esc", mac: "Esc" },
      exec: () => setOutput(""),
      readOnly: true,
    },
  ]

  return (
    <>
      <AceEditor
        mode={mode}
        theme="github"
        width="100%"
        height="16rem"
        minLines={16}
        maxLines={Infinity}
        showPrintMargin={false}
        defaultValue={`System.out.println("Hello, world!");`}
        onBeforeLoad={(ace) => {
          ace.config.set("basePath", `https://cdn.jsdelivr.net/npm/ace-builds@${ace.version}/src-min-noconflict`)
        }}
        onLoad={(ace) => {
          aceRef.current = ace
        }}
        commands={commands}
      />
      <button onClick={run} style={{ marginTop: 8 }}>
        Run
      </button>
      {output !== "" && <pre>{output}</pre>}
    </>
  )
}

export default function Home() {
  return (
    <JeedProvider server={process.env.NEXT_PUBLIC_JEED_SERVER as string}>
      <h2>Jeed Demo</h2>
      <JeedDemo />
    </JeedProvider>
  )
}
