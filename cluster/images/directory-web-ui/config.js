const host = window.location.hostname;
const cluster = "${CN_APP_DIRECTORY_UI_CLUSTER}";
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
      url: `https://directory.sv-1.svc.${cluster}/api/v0/directory`,
    },
    wallet: {
      // URL of the web-ui, used to forward payment workflows to wallet
      uiUrl: window.location.origin.replace("directory", "wallet"),
    },
    jsonApi: {
      // URL of the JSON API for the participant
      url: "https://" + window.location.hostname + "/api/json-api/",
    },
  },
};
