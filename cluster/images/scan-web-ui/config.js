const cluster = "${CN_APP_CNS_UI_CLUSTER}";
window.splice_config = {
  services: {
    scan: {
      // URL of scan backend.
      url: `https://${window.location.hostname}/api/scan`,
    },
  },
};
