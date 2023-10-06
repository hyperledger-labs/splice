const cluster = "${CN_APP_DIRECTORY_UI_CLUSTER}";
window.canton_network_config = {
  services: {
    scan: {
      // URL of scan backend.
      url: `https://${window.location.hostname}/api/v0/scan`,
    },
    directory: {
      // URL of the directory backend.
      url: `https://directory.sv-1.svc.${cluster}/api/v0/directory`,
    },
  },
};
