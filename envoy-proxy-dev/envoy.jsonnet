local utils = import "envoy-util.libsonnet";

function(hostname="host.docker.internal") {

    local cluster(cluster_name, port) = utils.cluster(cluster_name, hostname, port),


  "admin": {
    "access_log_path": "../log/envoy-admin.log",
    "address": {
      "socket_address": {
        "address": "0.0.0.0",
        "port_value": 9901
      }
    }
  },
  "static_resources": {
    "listeners": [
      utils.listener("alice_validator", 6203),
      utils.listener("alice_wallet", 6204),
      utils.listener("bob_validator", 6303),
      utils.listener("bob_wallet", 6304),
      utils.listener("splitwise", 6113),
      utils.listener("scan" , 6012),
      utils.listener("directory", 6110),
      utils.listener("alice_lapi", 6201),
      utils.listener("bob_lapi", 6301)
    ],
    "clusters": [
      cluster("alice_validator", 5203),
      cluster("alice_wallet", 5204),
      cluster("bob_validator", 5303),
      cluster("bob_wallet", 5304),
      cluster("splitwise", 5113),
      cluster("scan", 5012),
      cluster("directory", 5110),
      cluster("alice_lapi", 5201),
      cluster("bob_lapi", 5301)
    ]
  }
}
