const host = window.location.hostname;
const cluster = "${CN_APP_SPLITWELL_UI_CLUSTER}";
window.splice_config = {
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
      // URL of the splitwell backend
      url: `https://splitwell.${cluster}/api/splitwell`,
    },
    jsonApi: {
      // URL of the JSON API for the participant
      url: "https://" + window.location.hostname + "/api/json-api/",
    },
    scan: {
      // URL of the scan app's HTTP API
      url: `https://scan.sv-2.${cluster}/api/scan`,
    },
  },
};
