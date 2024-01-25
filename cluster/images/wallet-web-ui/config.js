const host = window.location.hostname;
const cluster = "${CN_APP_WALLET_UI_CLUSTER}";
window.canton_network_config = {
  auth: {
    algorithm: "rs-256",
    authority: "${CN_APP_WALLET_UI_AUTH_URL}",
    client_id: "${CN_APP_WALLET_UI_AUTH_CLIENT_ID}",
    token_audience: "${CN_APP_WALLET_UI_AUTH_AUDIENCE}",
  },
  services: {
    validator: {
      url: "https://" + window.location.hostname + "/api/validator",
    },
  },
  clusterUrl: `https://${cluster}`,
};
