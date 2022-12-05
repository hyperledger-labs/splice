window.canton_network_config = {
  // ledgerApi auth; we don't need authentication towards the directory backend
  // note that this gets overwritten via environment variables set in `start-frontends.sh`
  auth: {
    algorithm: 'hs-256-unsafe',
    secret: 'test',
  },
  // OIDC client configuration, see https://authts.github.io/oidc-client-ts/interfaces/UserManagerSettings.html
  //   auth: {
  //     algorithm: 'rs-256'
  //     authority: "",
  //     client_id: "",
  //     redirect_uri: window.location.origin,
  //   },
  directory: {
    // URL of the gRPC-Web envoy proxy, proxying to the directory gRPC API
    grpcUrl: 'http://localhost:6110',
  },
  ledgerApi: {
    // URL of the gRPC-Web envoy proxy, proxying the user’s ledger API
    grpcUrl: 'http://localhost:6201',
  },
};
