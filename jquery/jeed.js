(function ($) {
  function runWithJeed(server, snippet, language) {
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
          indent: 2,
        },
      },
    };
    request.tasks = Object.keys(tasks);
    return $.ajax({
      url: server,
      type: "POST",
      data: JSON.stringify(request),
      contentType: "application/json; charset=utf-8",
      xhrFields: { withCredentials: true },
      crossDomain: true,
      dataType: "json",
    });
  }

  function formatJeedResult(result) {
    const request = result.request;
    let resultOutput = "";
    if (result.failed.snippet) {
      const { errors } = result.failed.snippet;
      resultOutput += errors
        .map((error) => {
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
        .map((error) => {
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
            .filter((errorLine) => {
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
        .map((error) => {
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
        const executionLines = execution.outputLines.map((outputLine) => {
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

  const defaultCloseButton =
    '<button class="jeed close" style="position: absolute; right: 2px; top: 2px;">Close</button>';
  const defaultRunButton =
    '<button class="jeed play" style="position: absolute; right: 2px; bottom: 2px;">Run</button>';
  const defaultRunningBanner =
    '<div class="jeed running" style="display: none;"><pre>Running...</pre></div>';

  $.fn.jeed = function (server, options = {}) {
    this.each(function (index, elem) {
      if ($(elem).children("code").length !== 1) {
        return;
      }
      const code = $(elem).children("code").eq(0);
      if (!(code.hasClass("lang-java") || code.hasClass("lang-kotlin"))) {
        return;
      }

      let language;
      if (code.hasClass("lang-java")) {
        language = "java";
      } else if (code.hasClass("lang-kotlin")) {
        language = "kotlin";
      }

      $(elem).css({ position: "relative" });

      const outputWrapper = $(
        '<div class="jeed output" style="position: relative;"><pre></pre></div>'
      ).css({
        display: "none",
      });
      const runningBanner = $(options.runningBanner || defaultRunningBanner);
      outputWrapper.append(runningBanner);

      const closeButton = $(options.closeButton || defaultCloseButton).on(
        "click",
        function () {
          $(this).parent().css({ display: "none" });
        }
      );
      outputWrapper.append(closeButton);

      const output = $(outputWrapper).children("pre").eq(0);
      output.css({ display: "none" });

      let timer;
      const runButton = $(options.runButton || defaultRunButton).on(
        "click",
        function () {
          $(output).text("");
          timer = setTimeout(() => {
            $(outputWrapper).css({ display: "block" });
            runningBanner.css({ display: "block" });
          }, 100);
          runWithJeed(server, $(this).prev("code").text(), language)
            .done((result) => {
              $(outputWrapper).css({ display: "block" });
              const jeedOutput = formatJeedResult(result);
              if (jeedOutput !== "") {
                $(output).text(formatJeedResult(result));
              } else {
                $(output).html(
                  '<span class="jeed blank">(No output produced)</span>'
                );
              }
              clearTimeout(timer);
              output.css({ display: "block" });
              runningBanner.css({ display: "none" });
            })
            .fail((xhr, status, error) => {
              console.error("Request failed");
              console.error(JSON.stringify(xhr, null, 2));
              console.error(JSON.stringify(status, null, 2));
              console.error(JSON.stringify(error, null, 2));
              $(output).html(
                '<span class="jeed error">An error occurred</span>'
              );
              clearTimeout(timer);
              output.css({ display: "block" });
              runningBanner.css({ display: "none" });
            });
        }
      );

      $(elem).append(runButton);
      $(elem).append(outputWrapper);
    });
  };
})(jQuery);
