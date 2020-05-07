import React from "react"
import { hot } from "react-hot-loader"

import { Container } from "semantic-ui-react"

import { MDXProvider } from "@mdx-js/react"
import Content from "./index.mdx"

import { GoogleLoginProvider, WithGoogleTokens } from "@cs125/react-google-login"
import { JeedProvider } from "@cs125/react-jeed"
import { MaceProvider } from "@cs125/mace"
import { String } from "runtypes"

const JEED_SERVER = String.check(process.env.JEED_SERVER)
const MACE_SERVER = String.check(process.env.MACE_SERVER)
const GOOGLE_CLIENT_ID = String.check(process.env.AUTH_GOOGLE_CLIENTID)

const App: React.SFC = () => (
  <GoogleLoginProvider
    // eslint-disable-next-line @typescript-eslint/camelcase
    clientConfig={{ client_id: GOOGLE_CLIENT_ID }}
  >
    <WithGoogleTokens>
      {({ idToken }): JSX.Element => {
        return (
          <MaceProvider server={MACE_SERVER} googleToken={idToken}>
            <JeedProvider server={JEED_SERVER}>
              <Container text style={{ paddingTop: 16 }}>
                <MDXProvider>
                  <Content />
                </MDXProvider>
              </Container>
            </JeedProvider>
          </MaceProvider>
        )
      }}
    </WithGoogleTokens>
  </GoogleLoginProvider>
)
export default hot(module)(App)
