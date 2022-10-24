module.exports = {
  basePath: process.env.NEXT_PUBLIC_BASE_PATH ?? "",
  ...(process.env.NEXT_PUBLIC_BASE_PATH && { assetPrefix: process.env.NEXT_PUBLIC_BASE_PATH }),
  reactStrictMode: true,
}
