import React, { useState } from "react"

import { withGoogleLogin, GoogleAuth } from "@cs125/react-google-login"

import { Button, Icon } from "semantic-ui-react"

const loginOrOut = (
  { auth, isSignedIn }: { auth: GoogleAuth | null; isSignedIn: boolean | undefined },
  setBusy: (busy: boolean) => void = (): void => {} // eslint-disable-line @typescript-eslint/no-empty-function
) => async (): Promise<void> => {
  if (!auth) {
    return
  }
  setBusy(true)
  try {
    await (!isSignedIn ? auth.signIn() : auth.signOut())
  } finally {
    setBusy(false)
  }
}

const LoginButton: React.FC = () => {
  const [busy, setBusy] = useState<boolean>(false)

  const { ready, auth, isSignedIn, err } = withGoogleLogin()

  if (err) {
    return null
  }
  return (
    <Button
      floated="right"
      positive={!isSignedIn}
      loading={!ready || busy}
      disabled={!ready}
      onClick={loginOrOut({ auth, isSignedIn }, setBusy)}
    >
      <Icon name="google" /> {!isSignedIn ? "Login" : "Logout"}
    </Button>
  )
}

export default LoginButton
