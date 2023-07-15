local utils = import "envoy-util.libsonnet";

function(hostname="host.docker.internal") {

  local cluster(cluster_name, port) = utils.cluster(cluster_name, hostname, port),


  admin: {
    access_log_path: "../log/envoy-admin.log",
    address: {
      socket_address: {
        address: "0.0.0.0",
        port_value: 9901,
      },
    },
  },
  static_resources: {
    listeners: [
      utils.listener("splitwell", 6113),
    ],
    clusters: [
      cluster("splitwell", 5113),
    ],
  },
}
