import React from 'react'

export const TerminalOutput = (props) => {
    const { job, result } = props
    if (!result) {
        return null
    }

    let output = ""
    if (result.failed.snippet) {
        const { errors } = result.failed.snippet
        output += errors.map(error => {
            const { line, column, message } = error
            const originalLine = job.snippet.split("\n")[line - 1]
            return `Line ${line}: error: ${message}
  ${ originalLine}
  ${ new Array(column).join(" ")}^`
        })

        const errorCount = Object.keys(errors).length
        output += `
  ${ errorCount} error${errorCount > 1 ? "s" : ""}`
    } else if (result.failed.compilation) {
        const { errors } = result.failed.compilation
        output += errors.map(error => {
            const { source, line, column } = error.location
            const originalLine = source === "" ?
                job.snippet.split("\n")[line - 1] :
                job.sources[source].split("\n")[line - 1]
            const firstErrorLine = error.message.split("\n").slice(0, 1).join("\n")
            const restError = error.message.split("\n").slice(1).filter(errorLine => {
                if (source === "" && errorLine.trim().startsWith("location: class")) {
                    return false
                } else {
                    return true
                }
            }).join("\n")
            return `${source === "" ? "Line " : source}${line}: error: ${firstErrorLine}
  ${ originalLine}
  ${ new Array(column).join(" ")}^
  ${ restError}`
        }).join("\n")
        const errorCount = Object.keys(errors).length
        output += `
  ${ errorCount} error${errorCount > 1 ? "s" : ""}`
    } else if (result.failed.execution) {
        output += result.failed.execution
    }

    if (Object.keys(result.failed).length === 0) {
        if (result.completed.execution) {
            const { execution } = result.completed
            output += execution.outputLines.map(outputLine => {
                return outputLine.line
            }).join("\n")
            if (execution.timeout) {
                output += "\n(Program Timed Out)"
            }
            if (execution.truncatedLines > 0) {
                output += `\n(${execution.truncatedLines} lines were truncated)`
            }
        }
    }

    return (<div>{output}</div>)
}