window.canton_network_config = {
  auth: {
    algorithm: "hs-256-unsafe",
    secret: "test",
  },
  services: {
    directory: {
      // URL of the gRPC-Web envoy proxy, proxying to the directory gRPC API
      grpcUrl: "https://" + window.location.hostname + "/api/v0/directory",
    },
    ledgerApi: {
      // URL of the gRPC-Web envoy proxy, proxying the user’s ledger API
      grpcUrl: "https://" + window.location.hostname + "/api/v0/ledger-api",
    },
  },
  wallet: {
    // URL of the web-ui, used to forward payment workflows to wallet
    uiUrl: window.location.origin.replace("directory", "wallet"),
  },
};
