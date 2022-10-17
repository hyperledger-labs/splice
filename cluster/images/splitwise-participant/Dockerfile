ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

EXPOSE 5201
EXPOSE 5202

COPY coin.conf .
COPY bootstrap.scala .
