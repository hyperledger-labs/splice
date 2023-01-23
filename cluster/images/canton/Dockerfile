# This Dockerfile is a modified version of Canton's Dockerfile
FROM openjdk:11-jdk-slim

# Install screen for running the console in a headless server
RUN apt-get update \
   && DEBIAN_FRONTEND=noninteractive apt-get install -y screen \
   && apt-get clean \
   && rm -rf /var/cache/apt/archives

# Add our custom classpath
RUN mkdir /canton-classpath
WORKDIR /canton-classpath
ADD target/canton-classpath .
ENV CLASSPATH=/canton-classpath

# create and switch to a working directory
RUN mkdir /app
WORKDIR /app

# use this config until example configs are fixed
COPY empty.conf .
COPY target/entrypoint.sh .

# move tarball to a static name
ADD target/canton .

RUN ln -s bin/canton cn-image-bin

# point entrypoint to the coin executable
ENTRYPOINT /app/entrypoint.sh
