window.splice_config = {
  services: {
    scan: {
      // URL of scan backend.
      url: "https://" + window.location.host,
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
