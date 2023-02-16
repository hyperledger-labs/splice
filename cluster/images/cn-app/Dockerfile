# This Dockerfile is a modified version of Canton's Dockerfile
FROM openjdk:11-jdk-slim

# Install screen for running the console in a headless server,
# and curl for getting onboarding secrets.
RUN apt-get update \
   && DEBIAN_FRONTEND=noninteractive apt-get install -y screen curl \
   && apt-get clean \
   && rm -rf /var/cache/apt/archives

# create and switch to a working directory
RUN mkdir /app
WORKDIR /app

# move tarball to a static name
ADD target/coin-0.1.0-SNAPSHOT.tar.gz .

COPY empty.conf target/entrypoint.sh target/bootstrap-entrypoint.sc target/tools.sc /app/

RUN ln -s coin-0.1.0-SNAPSHOT/bin/coin cn-image-bin

# point entrypoint to the coin executable
ENTRYPOINT /app/entrypoint.sh
