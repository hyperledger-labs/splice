const host = window.location.hostname;
const cluster = "${CN_APP_CNS_UI_CLUSTER}";
window.canton_network_config = {
  auth: {
    algorithm: "rs-256",
    authority: "${CN_APP_CNS_UI_AUTH_URL}",
    client_id: "${CN_APP_CNS_UI_AUTH_CLIENT_ID}",
    token_audience: "${CN_APP_CNS_UI_AUTH_AUDIENCE}",
  },
  services: {
    scan: {
      // URL of the scan backend.
      url: `https://scan.sv-1.svc.${cluster}/api/scan`,
    },
    validator: {
      url: "https://" + window.location.hostname + "/api/validator",
    },
    wallet: {
      // URL of the web-ui, used to forward payment workflows to wallet
      uiUrl: window.location.origin.replace("cns", "wallet"),
    },
  },
};
