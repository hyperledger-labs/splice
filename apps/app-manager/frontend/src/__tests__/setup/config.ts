// TODO(#7579) -- remove duplication from default config
const config = {
  // HMAC256-based auth with browser self-signed tokens
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
    token_audience: 'https://canton.network.global',
  },
  // OIDC client configuration, see https://authts.github.io/oidc-client-ts/interfaces/UserManagerSettings.html
  //   auth: {
  //     algorithm: 'rs-256',
  //     authority: "",
  //     client_id: "",
  //     token_audience: "https://canton.network.global",
  //     token_scope: "daml_ledger_api",
  //   },
  services: {
    validator: {
      // URL of the validator app HTTP API
      url: 'http://localhost:5003/api/validator',
    },
    wallet: {
      uiUrl: 'http://wallet',
    },
    scan: {
      url: 'http://scan',
    },
  },
};

export { config };
