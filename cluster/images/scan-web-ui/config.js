window.splice_config = {
  services: {
    scan: {
      // URL of scan backend.
      url: `https://${window.location.hostname}/api/scan`,
    },
  },
  spliceInstanceNames: {
    networkName: "${CN_APP_UI_NETWORK_NAME}",
    networkFaviconUrl: "${CN_APP_UI_NETWORK_FAVICON_URL}",
    amuletName: "${CN_APP_UI_AMULET_NAME}",
    amuletNameAcronym: "${CN_APP_UI_AMULET_NAME_ACRONYM}",
    nameServiceName: "${CN_APP_UI_NAME_SERVICE_NAME}",
    nameServiceNameAcronym: "${CN_APP_UI_NAME_SERVICE_NAME_ACRONYM}",
  },
};
