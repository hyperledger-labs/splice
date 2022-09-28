local utils = import "envoy-util.libsonnet";

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
      utils.listener("bob_wallet", 6304)
    ],
    "clusters": [
      utils.cluster("alice_wallet", "host.docker.internal", 5204),
      utils.cluster("bob_wallet", "host.docker.internal", 5304)
    ]
  }
}
