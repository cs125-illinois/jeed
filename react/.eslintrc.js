module.exports = {
  ignorePatterns: ["dist"],
  parser: "babel-eslint",
  extends: ["plugin:react/recommended", "plugin:prettier/recommended"],
  parserOptions: {
    ecmaVersion: 2018,
    sourceType: "module",
  },
  settings: {
    react: {
      version: "detect",
    },
  },
  overrides: [
    {
      files: ["**/*.ts", "**/*.tsx"],
      parser: "@typescript-eslint/parser",
      extends: [
        "plugin:react/recommended",
        "plugin:@typescript-eslint/recommended",
        "prettier/@typescript-eslint",
        "plugin:prettier/recommended",
      ],
      rules: {
        "@typescript-eslint/camelcase": "off",
      },
    },
  ],
}
