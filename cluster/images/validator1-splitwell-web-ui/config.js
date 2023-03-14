const host = window.location.hostname;
const hostParts = host.split(".");
// Strip everything after the last 4 components so we get only the cluster name.
if (hostParts.length < 4) {
  console.error(`Unexpected hostname: ${hostParts}`);
}
const cluster = hostParts.slice(-4).join(".");
window.canton_network_config = {
  auth: {
    algorithm: "rs-256",
    authority: "${CN_APP_SPLITWELL_UI_AUTH_URL}",
    client_id: "${CN_APP_SPLITWELL_UI_AUTH_CLIENT_ID}",
    token_audience: "https://canton.network.global",
    token_scope: "daml_ledger_api",
  },
  services: {
    wallet: {
      // URL of the web-ui, used to forward payment workflows to wallet
      grpcUrl: "https://" + window.location.hostname + "/api/v0/wallet",
      uiUrl: window.location.origin.replace("splitwell", "wallet"),
    },
    splitwell: {
      // URL of the gRPC-Web envoy proxy, proxying the splitwell gRPC API
      grpcUrl: `https://splitwell.${cluster}`,
    },
    ledgerApi: {
      // URL of the gRPC-Web envoy proxy, proxying the user’s ledger API
      grpcUrl: "https://" + window.location.hostname + "/api/v0/ledger-api",
    },
    directory: {
      // URL of the directory backend. Note that this is not (yet) exposed over TLS.
      grpcUrl: `https://directory.${cluster}`,
    },
    scan: {
      // URL of the scan app's HTTP API
      grpcUrl: `https://scan.${cluster}`,
    },
    participantAdmin: {
      // URL of the gRPC-Web envoy proxy, proxying the user’s ledger API
      grpcUrl:
        "https://" + window.location.hostname + "/api/v0/participant-admin",
    },
  },
};
