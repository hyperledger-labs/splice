const cluster = "${CN_APP_CNS_UI_CLUSTER}";
window.splice_config = {
  services: {
    scan: {
      // URL of scan backend.
      url: `https://${window.location.hostname}/api/scan`,
    },
  },
  spliceInstanceNames: {
    networkName: "${CN_APP_SCAN_UI_NETWORK_NAME}",
    amuletName: "${CN_APP_SCAN_UI_AMULET_NAME}",
    amuletNameAcronym: "${CN_APP_SCAN_UI_AMULET_NAME_ACRONYM}",
    nameServiceName: "${CN_APP_SCAN_UI_NAME_SERVICE_NAME}",
    nameServiceNameAcronym: "${CN_APP_SCAN_UI_NAME_SERVICE_NAME_ACRONYM}",
  },
};
