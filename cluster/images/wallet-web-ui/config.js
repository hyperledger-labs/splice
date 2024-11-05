const host = window.location.hostname;
window.splice_config = {
  auth: {
    algorithm: "rs-256",
    authority: "${SPLICE_APP_UI_AUTH_URL}",
    client_id: "${SPLICE_APP_UI_AUTH_CLIENT_ID}",
    token_audience: "${SPLICE_APP_UI_AUTH_AUDIENCE}",
  },
  services: {
    validator: {
      url: "https://" + window.location.host + "/api/validator",
    },
  },
  pollInterval: "${SPLICE_APP_UI_POLL_INTERVAL}",
  spliceInstanceNames: {
    networkName: "${SPLICE_APP_UI_NETWORK_NAME}",
    networkFaviconUrl: "${SPLICE_APP_UI_NETWORK_FAVICON_URL}",
    amuletName: "${SPLICE_APP_UI_AMULET_NAME}",
    amuletNameAcronym: "${SPLICE_APP_UI_AMULET_NAME_ACRONYM}",
    nameServiceName: "${SPLICE_APP_UI_NAME_SERVICE_NAME}",
    nameServiceNameAcronym: "${SPLICE_APP_UI_NAME_SERVICE_NAME_ACRONYM}",
  },
};
