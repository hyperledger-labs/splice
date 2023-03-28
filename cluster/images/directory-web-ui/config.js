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
    authority: "${CN_APP_DIRECTORY_UI_AUTH_URL}",
    client_id: "${CN_APP_DIRECTORY_UI_AUTH_CLIENT_ID}",
    token_audience: "https://canton.network.global",
    token_scope: "daml_ledger_api",
  },
  services: {
    directory: {
      // URL of the directory backend.
      url: `https://directory.${cluster}`,
      jsonApiUrl: "https://" + window.location.hostname + "/api/json-api/",
    },
    wallet: {
      // URL of the web-ui, used to forward payment workflows to wallet
      uiUrl: window.location.origin.replace("directory", "wallet"),
    },
  },
};
