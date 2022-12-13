window.canton_network_config = {
  auth: {
    algorithm: "rs-256",
    authority: "https://canton-network-dev.us.auth0.com",
    client_id: "5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK",
    token_audience: "https://canton.network.global",
  },
  services: {
    wallet: {
      // URL of the gRPC-Web envoy proxy, proxying to the wallet app gRPC API
      grpcUrl: "https://" + window.location.hostname + "/api/v0/wallet",
    },
    validator: {
      // URL of the gRPC-Web envoy proxy, proxying to the validator app gRPC API
      grpcUrl: "https://" + window.location.hostname + "/api/v0/validator",
    },
    directory: {
      // URL of the gRPC-Web envoy proxy, proxying to the directory app gRPC API
      grpcUrl: "https://" + window.location.hostname + "/api/v0/directory",
    },
    scan: {
      // URL of the gRPC-Web envoy proxy, proxying to the scan app gRPC API
      grpcUrl: "https://" + window.location.hostname + "/api/v0/scan",
    },
  },
};
