ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

COPY coin.conf .
COPY bootstrap.scala .
COPY target/directory-service-0.1.0.dar .
