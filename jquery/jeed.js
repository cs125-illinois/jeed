(function($) {
  function runWithJeed(snippet, language) {
    const tasks = { execute: true };
    if (language === "java") {
      tasks.compile = true;
    } else if (language === "kotlin") {
      tasks.kompile = true;
    } else {
      throw Error("Invalid Jeed language " + language);
    }
    const request = {
      label: "",
      snippet: snippet + "\n",
      arguments: {
        snippet: {
          indent: 2
        }
      }
    };
    request.tasks = Object.keys(tasks);
    console.debug(request);
    return $.ajax({
      url: process.env.JEED,
      type: "POST",
      data: JSON.stringify(request),
      contentType: "application/json; charset=utf-8",
      xhrFields: { withCredentials: true },
      crossDomain: true,
      dataType: "json"
    });
  }

  function formatJeedResult(result) {
    const request = result.request;
    let resultOutput = "";
    if (result.failed.snippet) {
      const { errors } = result.failed.snippet;
      resultOutput += errors
        .map(error => {
          const { line, column, message } = error;
          const originalLine = request.snippet.split("\n")[line - 1];
          return `Line ${line}: error: ${message}
${originalLine}
${new Array(column).join(" ")}^`;
        })
        .join("\n");

      const errorCount = Object.keys(errors).length;
      resultOutput += `
${errorCount} error${errorCount > 1 ? "s" : ""}`;
    } else if (result.failed.compilation || result.failed.kompilation) {
      const { errors } = result.failed.compilation || result.failed.kompilation;
      resultOutput += errors
        .map(error => {
          const { source, line, column } = error.location;
          const originalLine =
            source === ""
              ? request.snippet.split("\n")[line - 1]
              : request.sources[0].contents.split("\n")[line - 1];
          const firstErrorLine = error.message
            .split("\n")
            .slice(0, 1)
            .join("\n");
          const restError = error.message
            .split("\n")
            .slice(1)
            .filter(errorLine => {
              if (
                source === "" &&
                errorLine.trim().startsWith("location: class")
              ) {
                return false;
              } else {
                return true;
              }
            })
            .join("\n");
          return `${
            source === "" ? "Line " : `${source}:`
          }${line}: error: ${firstErrorLine}
${originalLine}
${new Array(column).join(" ")}^
${restError}`;
        })
        .join("\n");
      const errorCount = Object.keys(errors).length;
      resultOutput += `
${errorCount} error${errorCount > 1 ? "s" : ""}`;
    } else if (result.failed.checkstyle) {
      const { errors } = result.failed.checkstyle;
      resultOutput += errors
        .map(error => {
          const { source, line } = error.location;
          return `${
            source === "" ? "Line " : `${source}:`
          }${line}: checkstyle error: ${error.message}`;
        })
        .join("\n");
      const errorCount = Object.keys(errors).length;
      resultOutput += `
${errorCount} error${errorCount > 1 ? "s" : ""}`;
    } else if (result.failed.execution) {
      if (result.failed.execution.classNotFound) {
        resultOutput += `Error: could not find class ${result.failed.execution.classNotFound.klass}`;
      } else if (result.failed.execution.methodNotFound) {
        resultOutput += `Error: could not find method ${result.failed.execution.methodNotFound.method}`;
      } else if (result.failed.execution.threw) {
        resultOutput += result.failed.execution.threw;
      } else {
        resultOutput += "Something went wrong...";
      }
    }

    if (Object.keys(result.failed).length === 0) {
      if (result.completed.execution) {
        const { execution } = result.completed;
        const executionLines = execution.outputLines.map(outputLine => {
          return outputLine.line;
        });
        if (execution.timeout) {
          executionLines.push("(Program Timed Out)");
        }
        if (execution.truncatedLines > 0) {
          executionLines.push(
            `(${execution.truncatedLines} lines were truncated)`
          );
        }
        resultOutput += executionLines.join("\n");
      }
    }
    return resultOutput.trim();
  }

  let outputIndex = 0;

  $.fn.jeed = function(language) {
    this.each(function(index, elem) {
      $(elem)
        .parent("pre")
        .css({ position: "relative" });

      const wrapper = $('<div class="jeed wrapper"></div>').attr({
        id: "jeed-wrapper-" + outputIndex
      });
      $(elem)
        .parent("pre")
        .wrap(wrapper);

      const output = $('<pre class="jeed output"></pre>')
        .css({
          display: "none"
        })
        .attr({
          id: "jeed-output-" + outputIndex++
        });

      const button = $('<button class="jeed play">Play</button>')
        .css({
          position: "absolute",
          right: 0,
          bottom: 0
        })
        .on("click", function() {
          runWithJeed(
            $(this)
              .prev()
              .text(),
            language
          )
            .done(result => {
              const jeedOutput = formatJeedResult(result);
              if (jeedOutput !== "") {
                $(output).text(formatJeedResult(result));
              } else {
                $(output).html(
                  '<span class="jeed blank">(No output produced)</span>'
                );
              }
              $(output).css({ display: "block" });
            })
            .fail((xhr, status, error) => {
              console.error("Request failed");
              console.error(JSON.stringify(xhr, null, 2));
              console.error(JSON.stringify(status, null, 2));
              console.error(JSON.stringify(error, null, 2));
              $(output).html(
                '<span class="jeed error">An error occurred</span>'
              );
            });
        });

      $(elem)
        .parent("pre")
        .append(button);
      $(elem)
        .parents("div.jeed.wrapper")
        .append(output);
    });
  };
})(jQuery);
