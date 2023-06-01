module.exports = {
  versionGroups: [
    {
      /**
       * Ignore version syncing of daml codegens because syncpack complains that the versions
       * "file:daml.js/directory-service-0.1.0"
       * and
       * "file:../../common/frontend/daml.js/directory-service-0.1.0"
       * are different
       */
      label: "Daml codegen",
      packages: ["**"],
      dependencies: ["@daml.js/directory", "@daml.js/wallet-payments"],
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
