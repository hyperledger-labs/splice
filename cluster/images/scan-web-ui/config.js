const cluster = "${CN_APP_CNS_UI_CLUSTER}";
window.canton_network_config = {
  services: {
    scan: {
      // URL of scan backend.
      url: `https://${window.location.hostname}/api/scan`,
    },
  },
};
