# This Dockerfile is a modified version of Canton's Dockerfile
FROM openjdk:11-jdk-slim

# Install screen for running the console in a headless server
RUN apt-get update \
   && DEBIAN_FRONTEND=noninteractive apt-get install -y screen \
   && apt-get clean \
   && rm -rf /var/cache/apt/archives

# allow the path we pull the coin tarball from to be overridden
ARG APP_PATH=target/coin-0.1.0-SNAPSHOT.tar.gz

# create and switch to a working directory
RUN mkdir /cn-app
WORKDIR /cn-app

# use this config until example configs are fixed
COPY empty.conf .

# move tarball to a static name
ADD ${APP_PATH} .

# point entrypoint to the coin executable
ENTRYPOINT [ "coin-0.1.0-SNAPSHOT/bin/coin" ]

# default arguments if none are specified by user
CMD ["daemon", "--no-tty", "--log-level-stdout", "DEBUG", "--log-level-canton=DEBUG" ]
