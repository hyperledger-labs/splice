window.canton_network_config = {
  auth: {
    algorithm: "rs-256",
    authority: "https://canton-network-dev.us.auth0.com",
    client_id: "5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK",
    token_audience: "https://canton.network.global",
    token_scope: "daml_ledger_api",
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
    wallet: {
      // URL of the web-ui, used to forward payment workflows to wallet
      grpcUrl: "https://" + window.location.hostname + "/api/v0/wallet",
      uiUrl: window.location.origin.replace("directory", "wallet"),
    },
  },
};
