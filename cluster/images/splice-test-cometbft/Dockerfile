# tag should match the version in nix/cometbft-driver-sources.json
# TODO(#15988): Don't hardcode the version here, get it from Nix instead
FROM digitalasset-canton-enterprise-docker.jfrog.io/cometbft-canton-network:3.2.0-snapshot.20241007.14241.0.v55249174-r1

# Copy the configuration files
COPY configs /

ENV sv_idx=0

COPY target/LICENSE .

ENTRYPOINT ["sh", "-c", "cometbft-canton-network start --home /sv${sv_idx}" ]
