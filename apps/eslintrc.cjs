module.exports = {
  extends: ["react-app", "react-app/jest", "plugin:@tanstack/eslint-plugin-query/recommended"],
  rules: {
    "curly": ["error"],
    "@typescript-eslint/no-explicit-any": "warn",
    "@typescript-eslint/explicit-module-boundary-types": "warn",
    "@typescript-eslint/ban-types": "warn",
    "react/jsx-key": [
      "warn",
      {
        warnOnDuplicates: true,
        checkFragmentShorthand: true,
      },
    ],
    "react/jsx-boolean-value": "warn",
    "react/function-component-definition": [
      "warn",
      {
        namedComponents: "arrow-function",
        unnamedComponents: "arrow-function",
      },
    ],
  },
  plugins: ["@tanstack/query"]
};
