import { Request, Response, SourceLocation } from "./types"

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
export function terminalOutput(response: Response | undefined): string {
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
        .filter(({ location }) => location !== undefined)
        .map((error) => {
          const { location, message } = error
          const { source, line, column } = location as SourceLocation
          const originalLine = getOriginalLine(request, line, source)
          const firstErrorLine = message.split("\n").slice(0, 1).join()
          const restOfError = message
            .split("\n")
            .slice(1)
            .filter((line) => {
              return !(source === "" && line.trim().startsWith("location: class"))
            })
            .join("\n")
          return `${source === "" ? "Line " : `${source}:`}${line}: error: ${firstErrorLine}
  ${originalLine ? originalLine + "\n" + new Array(column).join(" ") + "^" : ""}${
            restOfError ? "\n" + restOfError : ""
          }`
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
  } else if (response.failed.execution || response.failed.cexecution) {
    const failed = response.failed.execution || response.failed.cexecution
    if (failed?.classNotFound) {
      return `Error: could not find class ${failed?.classNotFound}`
    } else if (failed?.methodNotFound) {
      return `Error: could not find method ${failed?.methodNotFound}`
    } else {
      return `Something unexpected went wrong...`
    }
  }

  if (Object.keys(response.failed).length === 0 && (response.completed.execution || response.completed.cexecution)) {
    const completed = response.completed.execution || response.completed.cexecution
    const output = completed?.outputLines.map(({ line }) => line) || []
    if (response.completed.execution?.threw) {
      output.push(response.completed.execution?.threw.stacktrace)
    } else if (completed?.timeout) {
      output.push("(Program timed out)")
    }
    if (completed?.truncatedLines || 0 > 0) {
      output.push(`(${completed?.truncatedLines} lines were truncated)`)
    }
    return output.join("\n")
  }

  console.error(`Nothing failed but no success result either...`)
  return ""
}
