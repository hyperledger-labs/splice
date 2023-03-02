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
      utils.listener("alice_lapi", 6201),
      utils.listener("alice_participant_admin", 6202),
      utils.listener("bob_lapi", 6301),
      utils.listener("bob_participant_admin", 6302),
      utils.listener("preflight_lapi", 8001),
    ],
    clusters: [
      cluster("splitwell", 5113),
      cluster("alice_lapi", 5201),
      cluster("alice_participant_admin", 5202),
      cluster("bob_lapi", 5301),
      cluster("bob_participant_admin", 5302),
      cluster("preflight_lapi", 6001),
    ],
  },
}
