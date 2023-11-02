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
ADD target/cn-node-0.1.0-SNAPSHOT.tar.gz .

COPY target/entrypoint.sh target/bootstrap-entrypoint.sc target/tools.sh target/LICENSE.txt /app/

RUN ln -s cn-node-0.1.0-SNAPSHOT/bin/cn-node cn-image-bin

# point entrypoint to the cn-node executable
ENTRYPOINT /app/entrypoint.sh
