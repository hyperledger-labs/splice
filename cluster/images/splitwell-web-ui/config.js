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
      uiUrl: window.location.origin.replace("splitwell", "wallet"),
    },
    splitwell: {
      // URL of the gRPC-Web envoy proxy, proxying the splitwell gRPC API
      url: `https://splitwell.${cluster}`,
    },
    jsonApi: {
      // URL of the JSON API for the participant
      url: "https://" + window.location.hostname + "/api/json-api/",
    },
    directory: {
      // URL of the directory backend. Note that this is not (yet) exposed over TLS.
      url: `https://directory.sv-1.svc.${cluster}/api/v0/directory`,
    },
    scan: {
      // URL of the scan app's HTTP API
      url: `https://scan.sv-1.svc.${cluster}/api/v0/scan`,
    },
  },
};
