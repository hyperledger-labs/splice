// TODO(#7579) -- remove duplication from default config
const config = {
  services: {
    scan: {
      // URL of scan backend.
      // Edit this to the cluster you're trying to connect on.
      url: 'https://scan.sv-2.TARGET_HOSTNAME/api/scan',
    },
  },
};

export { config };
