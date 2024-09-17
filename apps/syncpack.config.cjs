module.exports = {
  versionGroups: [
    {
      /**
       * Ignore version syncing of daml codegens because syncpack complains that the versions
       * "file:daml.js/directory-service-0.2.0"
       * and
       * "file:../../common/frontend/daml.js/directory-service-0.2.0"
       * are different
       */
      label: "Daml codegen",
      packages: ["**"],
      dependencies: [
        "@daml.js/splice-amulet",
        "@daml.js/ans",
        "@daml.js/splice-wallet",
        "@daml.js/splice-wallet-payments",
      ],
      isIgnored: true,
    },
    {
      label: "OpenAPI codegen",
      packages: ["**-openapi"],
      dependencies: ["**"],
      isIgnored: true,
    },
  ],
};
