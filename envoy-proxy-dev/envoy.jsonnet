local utils = import "envoy-util.libsonnet";

local docker_cluster(cluster_name, port) = utils.cluster(cluster_name, "host.docker.internal", port);

{
  "admin": {
    "access_log_path": "/tmp/admin_access.log",
    "address": {
      "socket_address": {
        "address": "0.0.0.0",
        "port_value": 9901
      }
    }
  },
  "static_resources": {
    "listeners": [
      utils.listener("alice_wallet", 6204),
      utils.listener("bob_wallet", 6304),
      utils.listener("splitwise", 8082),
      utils.listener("scan" , 8083),
      utils.listener("directory", 8084),
      utils.listener("alice_lapi", 8085),
      utils.listener("bob_lapi", 8086)
    ],
    "clusters": [
      docker_cluster("alice_wallet", 5204),
      docker_cluster("bob_wallet", 5304),
      docker_cluster("splitwise", 5113),
      docker_cluster("scan", 5012),
      docker_cluster("directory", 5110),
      docker_cluster("alice_lapi", 5201),
      docker_cluster("bob_lapi", 5301)
    ]
  }
}
