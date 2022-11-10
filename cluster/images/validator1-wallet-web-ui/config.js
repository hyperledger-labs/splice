window.canton_network_config = {
  auth: {
    algorithm: "hs-256-unsafe",
    secret: "test",
  },
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
};
