import _ from "lodash"

import { describe } from "mocha"
import fetch from "node-fetch"
import yaml from "js-yaml"
import fs from "fs"
import { String } from "runtypes"
import { diff } from "deep-diff"

import { ServerStatus, Request, Response, TaskArguments, postRequest } from "@cs125/react-jeed"

const server = String.check(process.env.JEED)

describe("ServerStatus", function () {
  this.timeout(1000)
  this.slow(1000)

  it("should accept a valid status", async function () {
    const status = await (await fetch(server)).json()
    try {
      ServerStatus.check(status)
    } catch (err) {
      console.log(err.key)
      console.log(JSON.stringify(status, null, 2))
      throw err
    }
  })
})

async function checkRequest(request: unknown, verbose = false): Promise<Response> {
  try {
    Request.check(request)
  } catch (err) {
    console.log(`Request problem ${err.key}`)
    if (verbose) {
      console.log(JSON.stringify(request, null, 2))
    }
    throw err
  }
  const validatedRequest = Request.check(request)
  const response = await postRequest(server, request as Request, false)
  try {
    Response.check(response)
  } catch (err) {
    console.log(`Response problem: ${err.key}`)
    if (verbose) {
      console.log(JSON.stringify(response, null, 2))
    }
    throw err
  }
  const requestTaskArguments = validatedRequest.arguments || {}
  const responseTaskArguments = TaskArguments.check(response.request.arguments)

  if (!_.isMatch(responseTaskArguments, requestTaskArguments)) {
    throw `Response arguments problem: ${diff(responseTaskArguments, requestTaskArguments)}`
  }
  return response
}

describe("Java", function () {
  this.timeout(1000)
  this.slow(1000)

  describe("snippets", function () {
    it("should accept a minimal request", function (done) {
      const request = yaml.safeLoad(fs.readFileSync(`${__dirname}/fixtures/requests/java/snippets/minimal.yml`, "utf8"))
      checkRequest(request, true)
        .then(() => done())
        .catch((err) => done(err))
    })
    it("should accept a complete request", function (done) {
      const request = yaml.safeLoad(
        fs.readFileSync(`${__dirname}/fixtures/requests/java/snippets/complete.yml`, "utf8")
      )
      checkRequest(request, true)
        .then(() => done())
        .catch((err) => done(err))
    })
  })
  describe("sources", function () {
    it("should accept a minimal request", function (done) {
      const request = yaml.safeLoad(fs.readFileSync(`${__dirname}/fixtures/requests/java/sources/minimal.yml`, "utf8"))
      checkRequest(request, true)
        .then(() => done())
        .catch((err) => done(err))
    })
  })
})
