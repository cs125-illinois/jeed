import { Request, Response, SourceLocation } from "@cs124/jeed-types"

export function getOriginalLine(request: Request, line: number, source?: string): string {
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

export function compilerWarnings(response: Response): string | undefined {
  if (!(response.completed.compilation || response.completed.kompilation)) {
    return
  }
  return (
    (response.completed.compilation || response.completed.kompilation)?.messages
      .filter(({ kind }) => kind === "WARNING")
      .map((error) => {
        const { location, message } = error
        if (location) {
          const { source, line, column } = location as SourceLocation
          const originalLine = getOriginalLine(response.request, line, source)
          const firstErrorLine = message.split("\n").slice(0, 1).join()
          const restOfError = message
            .split("\n")
            .slice(1)
            .filter((line) => {
              return !(source === "" && line.trim().startsWith("location: class"))
            })
            .join("\n")
          return `${source === "" ? "Line " : `${source}:`}${line}: error: ${firstErrorLine}
${originalLine ? originalLine + "\n" + new Array(column).join(" ") + "^" : ""}${restOfError ? "\n" + restOfError : ""}`
        } else {
          return message
        }
      })
      .join("\n") || ""
  )
}

export interface TerminalOutput {
  output: string
  level: "success" | "warning" | "error"
}
export function terminalOutput(response: Response): TerminalOutput {
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
    return {
      output: `${output}
  ${errorCount} error${errorCount > 1 ? "s" : ""}`,
      level: "error",
    }
  } else if (response.failed.compilation || response.failed.kompilation) {
    const output =
      (response.failed.compilation || response.failed.kompilation)?.errors
        .map((error) => {
          const { location, message } = error
          if (location) {
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
${originalLine ? originalLine + "\n" + new Array(column).join(" ") + "^" : ""}${restOfError ? "\n" + restOfError : ""}`
          } else {
            return message
          }
        })
        .join("\n") || ""
    const errorCount = Object.keys((response.failed.compilation || response.failed.kompilation)?.errors || {}).length
    return {
      output: `${output}
  ${errorCount} error${errorCount > 1 ? "s" : ""}`,
      level: "error",
    }
  } else if (response.failed.checkstyle) {
    const output =
      response.failed.checkstyle?.errors
        .map(({ location: { source, line }, message }) => {
          return `${source === "" ? "Line " : `${source}:`}${line}: checkstyle error: ${message}`
        })
        .join("\n") || ""
    const errorCount = Object.keys(response.failed.checkstyle?.errors || {}).length
    return {
      output: `${output}
  ${errorCount} error${errorCount > 1 ? "s" : ""}`,
      level: "error",
    }
  } else if (response.failed.ktlint) {
    const output =
      response.failed.ktlint?.errors
        .map(({ location: { source, line }, detail }) => {
          return `${source === "" ? "Line " : `${source}:`}${line}: ktlint error: ${detail}`
        })
        .join("\n") || ""
    const errorCount = Object.keys(response.failed.ktlint?.errors || {}).length
    return {
      output: `${output}
  ${errorCount} error${errorCount > 1 ? "s" : ""}`,
      level: "error",
    }
  } else if (response.failed.execution || response.failed.cexecution) {
    const failed = response.failed.execution || response.failed.cexecution
    if (failed?.classNotFound) {
      return { output: `Error: could not find class ${failed?.classNotFound}`, level: "error" }
    } else if (failed?.methodNotFound) {
      return { output: `Error: could not find method ${failed?.methodNotFound}`, level: "error" }
    } else {
      return { output: `Something unexpected went wrong. Please report a bug.`, level: "error" }
    }
  }

  if (Object.keys(response.failed).length === 0) {
    if (response.completed.execution || response.completed.cexecution) {
      let level: "success" | "error" | "warning" = "success"
      const completed = response.completed.execution || response.completed.cexecution
      const output = completed?.outputLines
        ? completed.outputLines.length > 0
          ? completed.outputLines.map(({ line }) => line)
          : [`(Completed without output)`]
        : []
      if (response.completed.execution?.threw) {
        level = "error"
        output.push(response.completed.execution?.threw.stacktrace)
      } else if (completed?.timeout) {
        level = "error"
        output.push("(Program timed out)")
      }
      if (completed?.truncatedLines || 0 > 0) {
        level = "warning"
        output.push(`(${completed?.truncatedLines} lines were truncated)`)
      }
      return { output: output.join("\n"), level }
    } else if (response.completed.checkstyle) {
      return { output: `No checkstyle errors found`, level: "success" }
    } else if (response.completed.ktlint) {
      return { output: `No ktlint errors found`, level: "success" }
    } else if (response.completed.complexity) {
      const results = response.completed.complexity.results
      const output = []
      for (const result of results) {
        const totalComplexity = result.classes.map(({ complexity }) => complexity).reduce((c, n) => n + c, 0)
        const name = result.source === "" ? "Entire snippet" : result.source
        output.push(`${name} has complexity ${totalComplexity}`)
        for (const klass of result.classes) {
          if (klass.name === "") {
            continue
          }
          output.push(`  Class ${klass.name} has complexity ${klass.complexity}`)
        }
        for (const method of result.methods) {
          const methodName = method.name === "" ? "Loose code" : `Method ${method.name}`
          output.push(`  ${methodName} has complexity ${method.complexity}`)
        }
      }
      return { output: output.join("\n"), level: "success" }
    }
    if (response.completed.features) {
      const { results, allFeatures } = response.completed.features
      const output = []
      for (const result of results) {
        const fileFeatures: { [key: string]: boolean } = {}
        for (const klass of result.classes) {
          for (const feature of Object.keys(klass.features.featureMap)) {
            fileFeatures[feature] = true
          }
        }
        const name = result.source === "" ? "Entire snippet" : result.source
        output.push(
          `${name} uses features ${Object.keys(fileFeatures)
            .map((feature) => allFeatures[feature])
            .sort()}`
        )
      }
      return { output: output.join("\n"), level: "success" }
    }
  }
  throw Error("Can't generate output for this result")
}
