import React, { useRef } from "react"

import { MDXProvider } from "@mdx-js/react"
import Content from "./index.mdx"

import { GoogleLoginProvider, WithGoogleTokens } from "@cs125/react-google-login"
import { MaceProvider } from "@cs125/mace"
import { JeedProvider } from "@cs125/react-jeed"
import { ElementTracker } from "@cs125/element-tracker"
import { UpdateHash, SidebarMenu } from "@cs125/semantic-ui"
import { Container, Segment, Responsive, Rail, Sticky, Ref } from "semantic-ui-react"

import { String } from "runtypes"
const GOOGLE_CLIENT_ID = String.check(process.env.AUTH_GOOGLE_CLIENTID)
const MACE_SERVER = String.check(process.env.MACE_SERVER)
const JEED_SERVER = String.check(process.env.JEED_SERVER)
const ET_SERVER = String.check(process.env.ET_SERVER)

import { Highlighted, headings } from "@cs125/semantic-ui"

const components = {
  code: Highlighted,
  ...headings,
}

const App: React.SFC = () => {
  const contextRef = useRef()

  return (
    <GoogleLoginProvider
      // eslint-disable-next-line @typescript-eslint/camelcase
      clientConfig={{ client_id: GOOGLE_CLIENT_ID }}
    >
      <WithGoogleTokens>
        {({ idToken }): JSX.Element => {
          return (
            <MaceProvider server={MACE_SERVER} googleToken={idToken}>
              <JeedProvider server={JEED_SERVER}>
                <ElementTracker server={ET_SERVER} tags={["h1", "h2", "h3", "h4"]} googleToken={idToken}>
                  <UpdateHash tags={["h2"]} />
                  <Container text style={{ paddingTop: 16, paddingBottom: 16 }}>
                    <Ref innerRef={contextRef}>
                      <Segment basic>
                        <Responsive minWidth={1200}>
                          <Rail position="right">
                            <Sticky context={contextRef}>
                              <Segment basic style={{ paddingTop: 64 }}>
                                <SidebarMenu />
                              </Segment>
                            </Sticky>
                          </Rail>
                        </Responsive>
                        <MDXProvider components={components}>
                          <Content />
                        </MDXProvider>
                      </Segment>
                    </Ref>
                  </Container>
                </ElementTracker>
              </JeedProvider>
            </MaceProvider>
          )
        }}
      </WithGoogleTokens>
    </GoogleLoginProvider>
  )
}
export default App
