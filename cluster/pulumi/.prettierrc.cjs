module.exports = {
    printWidth: 100,
    singleQuote: true,
    trailingComma: "es5",
    arrowParens: "avoid",
    importOrder: ["^@mui.*", "^@daml.*", "^[./]"],
    importOrderGroupNamespaceSpecifiers: true,
    importOrderSeparation: true,
    "plugins": ["@trivago/prettier-plugin-sort-imports"]
};
