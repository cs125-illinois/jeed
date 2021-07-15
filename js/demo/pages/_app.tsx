import type { AppProps } from "next/app"
import "../styles/globals.scss"
import "../styles/reset.css"

function MyApp({ Component, pageProps }: AppProps) {
  return <Component {...pageProps} />
}

export default MyApp
