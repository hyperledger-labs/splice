# This Dockerfile is a modified version of Canton's Dockerfile
FROM openjdk:11-jdk-slim

# Install screen for running the console in a headless server
RUN apt-get update \
   && DEBIAN_FRONTEND=noninteractive apt-get install -y screen \
   && apt-get clean \
   && rm -rf /var/cache/apt/archives

# create and switch to a working directory
RUN mkdir /app
WORKDIR /app

# use this config until example configs are fixed
COPY empty.conf .
COPY target/entrypoint.sh .

# move tarball to a static name
ADD target/coin-0.1.0-SNAPSHOT.tar.gz .

RUN ln -s coin-0.1.0-SNAPSHOT/bin/coin cn-image-bin

# point entrypoint to the coin executable
ENTRYPOINT /app/entrypoint.sh
