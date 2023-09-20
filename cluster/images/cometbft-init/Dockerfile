FROM debian:stable-slim

# Install curl and jq to make API requests to cometbft RPC
RUN apt-get update \
   && DEBIAN_FRONTEND=noninteractive apt-get install -y curl jq \
   && apt-get clean \
   && rm -rf /var/cache/apt/archives

ENV STATE_SYNC_RPC_SERVERS=""
COPY configure-state-sync.sh /
RUN chmod +x /configure-state-sync.sh
