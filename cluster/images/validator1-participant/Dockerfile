ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

EXPOSE 5101
EXPOSE 5102

COPY coin.conf .
COPY bootstrap.scala .
