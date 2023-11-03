// TODO(#7579) -- remove duplication from default config
const config = {
  services: {
    scan: {
      // URL of scan backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://scan.sv-1.svc.TARGET_CLUSTER.network.canton.global/api/scan',
    },
    directory: {
      // URL of the directory backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://directory.sv-1.svc.TARGET_CLUSTER.network.canton.global/api/v0/directory',
    },
  },
};

export { config };
