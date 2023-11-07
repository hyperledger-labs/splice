ARG cometbft_version
FROM digitalasset-canton-enterprise-docker.jfrog.io/cometbft-canton-network:$cometbft_version
COPY configure-state-sync.sh /cometbft/
RUN chmod +x /cometbft/configure-state-sync.sh
