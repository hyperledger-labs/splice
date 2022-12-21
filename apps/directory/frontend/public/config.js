window.canton_network_config = {
  // ledgerApi auth; we don't need authentication towards the directory backend
  // note that this gets overwritten via environment variables set in `start-frontends.sh`
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
    token_audience: 'https://canton.network.global',
    token_scope: 'daml_ledger_api',
  },
  // OIDC client configuration, see https://authts.github.io/oidc-client-ts/interfaces/UserManagerSettings.html
  //   auth: {
  //     algorithm: 'rs-256',
  //     authority: "",
  //     client_id: "",
  //     token_audience: 'https://canton.network.global',
  //     token_scope: 'daml_ledger_api',
  //   },
  services: {
    directory: {
      // URL of the gRPC-Web envoy proxy, proxying to the directory gRPC API
      grpcUrl: 'http://localhost:6110',
    },
    ledgerApi: {
      // URL of the gRPC-Web envoy proxy, proxying the user’s ledger API
      grpcUrl: 'http://localhost:6201',
    },
    wallet: {
      // URL of the web-ui, used to forward payment workflows to wallet
      grpcUrl: "http://localhost:6004",
      uiUrl: "http://localhost:3000",
    },
  },
};
