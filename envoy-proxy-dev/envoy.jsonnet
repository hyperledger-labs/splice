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
      utils.listener("alice_wallet", 6204),
      utils.listener("bob_wallet", 6304),
      utils.listener("splitwell", 6113),
      utils.listener("scan", 6012),
      utils.listener("alice_lapi", 6201),
      utils.listener("bob_lapi", 6301),
    ],
    clusters: [
      cluster("alice_wallet", 5204),
      cluster("bob_wallet", 5304),
      cluster("splitwell", 5113),
      cluster("scan", 5012),
      cluster("alice_lapi", 5201),
      cluster("bob_lapi", 5301),
    ],
  },
}
