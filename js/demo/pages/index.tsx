import dynamic from "next/dynamic"

const AceEditor = dynamic(() => import("react-ace"), { ssr: false })

/*
const SingleEditorStream: React.FC = () => {
  const [records, setRecords] = useState<AceRecord[]>([])

  return (
    <>
      <h2>Single Editor Stream Demo</h2>
      <p>Live edits are streamed below the editor.</p>
      <AceEditor
        mode="java"
        theme="github"
        width="100%"
        height="16rem"
        minLines={16}
        maxLines={Infinity}
        showPrintMargin={false}
        onBeforeLoad={(ace) => {
          ace.config.set("basePath", `https://cdn.jsdelivr.net/npm/ace-builds@${ace.version}/src-min-noconflict`)
        }}
        onLoad={(ace) => {
          stream(ace, (record: AceRecord) => {
            setRecords((current) => [record, ...current])
          })
        }}
      />
      <button onClick={() => setRecords([])} style={{ marginTop: 8 }}>
        Clear
      </button>
      <div style={{ height: "16rem", overflow: "scroll", marginTop: 8 }}>
        {records.map((record, i) => (
          <pre key={i}>{JSON.stringify(record, null, 2)}</pre>
        ))}
      </div>
    </>
  )
}
*/

export default function Home() {
  return (
    <>
      <h2>Jeed Demo</h2>
      <AceEditor
        mode="java"
        theme="github"
        width="100%"
        height="16rem"
        minLines={16}
        maxLines={Infinity}
        showPrintMargin={false}
        onBeforeLoad={(ace) => {
          ace.config.set("basePath", `https://cdn.jsdelivr.net/npm/ace-builds@${ace.version}/src-min-noconflict`)
        }}
      />
      <button style={{ marginTop: 8 }}>
        Run
      </button>
    </>
  )
}
