# tag should match the version in nix/cometbft-driver-sources.json
ARG cometbft_version
ARG cometbft_sha
FROM digitalasset-canton-enterprise-docker.jfrog.io/cometbft-canton-network:$cometbft_version@sha256:cometbft_sha

ARG cometbft_version
LABEL org.opencontainers.image.base.name="digitalasset-canton-enterprise-docker.jfrog.io/cometbft-canton-network:$cometbft_version"

# Copy the configuration files
COPY configs /

ENV sv_idx=0

COPY target/LICENSE .

ENTRYPOINT ["sh", "-c", "cometbft-canton-network start --home /sv${sv_idx}" ]
