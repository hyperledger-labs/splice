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
    authority: "${CN_APP_WALLET_UI_AUTH_URL}",
    client_id: "${CN_APP_WALLET_UI_AUTH_CLIENT_ID}",
    token_audience: "https://canton.network.global",
  },
  services: {
    wallet: {
      // URL of the envoy proxy, proxying to the wallet app HTTP API
      url: "https://" + window.location.hostname + "/api/v0/validator",
    },
    validator: {
      // URL of the envoy proxy, proxying to the validator app HTTP API
      url: "https://" + window.location.hostname + "/api/v0/validator",
    },
    directory: {
      // URL of the directory backend.
      url: `https://directory.${cluster}`,
    },
    scan: {
      // URL of the scan app's HTTP API
      url: `https://scan.${cluster}`,
    },
  },
};
