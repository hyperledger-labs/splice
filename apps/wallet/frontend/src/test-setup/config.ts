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
      url: 'http://localhost:5003',
    },
    // BEGIN_WALLET_CLUSTER_BACKEND_CONFIG
    directory: {
      // URL of the directory backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://directory.sv-1.svc.TARGET_CLUSTER.network.canton.global/api/v0/directory',
    },
    scan: {
      // URL of scan backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://scan.sv-1.svc.TARGET_CLUSTER.network.canton.global/api/v0/scan',
    },
    // END_WALLET_CLUSTER_BACKEND_CONFIG
  },
};

export { config };
