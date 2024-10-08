# Note: the genesis file as a whole needs to be the same for all nodes joining the same network.
# chain id is limited to 50 chars
{{- define "genesisJson" }}
{
  "genesis_time": "2023-02-27T13:07:44.448442974488Z",
  {{- if .Values.genesis.chainIdSuffix }}
  "chain_id": "{{ printf "%s-%s" $.Values.genesis.chainId $.Values.genesis.chainIdSuffix | trunc 50 }}",
  {{- else }}
  "chain_id": "{{ printf "%s-%s" $.Values.genesis.chainId $.Chart.Version | trunc 50 }}",
  {{- end }}
  "initial_height": "0",
  "consensus_params": {
    "block": {
      "max_bytes": "22020096",
      "max_gas": "-1",
      "time_iota_ms": "1000"
    },
    "evidence": {
      "max_age_num_blocks": "100000",
      "max_age_duration": "172800000000000",
      "max_bytes": "1048576"
    },
    "validator": {
      "pub_key_types": ["ed25519"]
    },
    "version": {}
  },
  "validators": [
    {
      "address": "{{ $.Values.sv1.keyAddress }}",
      "pub_key": {
        "type": "tendermint/PubKeyEd25519",
        "value": "{{ $.Values.sv1.publicKey }}"
      },
      "power": "10",
      "name": ""
    }
  ],
  "app_hash": "",
  "app_state": {
    "sv_node_id": "Digital-Asset-2",
    "governance_keys": [
      {
        "pub_key": "m16haLzv/d/Ok04Sm39ABk0f0HsSWYNZxrIUiyQ+cK8=",
        "key_id": "12202b5d36b909489e4e00464ae7b558183da96fabc9eca3ddc5e34fbdba246a4be6"
      }
    ],
    "sequencing_keys": [
      {
        "pub_key": "dummy/key/here/replace/with/real/oneiyQ+cK8="
      }
    ]
  }
}
{{- end }}

{{- define "nodeKeyJson" }}
{
  "priv_key": {
    "type": "tendermint/PrivKeyEd25519",
    "value": "{{ . }}"
  }
}
{{- end }}

{{- define "privValidatorKeyJson" }}
{
  "address": "{{ $.Values.node.validator.keyAddress }}",
  "pub_key": {
    "type": "tendermint/PubKeyEd25519",
    "value": "{{ $.Values.node.validator.publicKey }}"
  },
  "priv_key": {
    "type": "tendermint/PrivKeyEd25519",
    "value": "{{ $.Values.node.validator.privateKey }}"
  }
}
{{- end }}
