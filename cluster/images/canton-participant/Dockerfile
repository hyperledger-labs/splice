ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

EXPOSE 5001
EXPOSE 5002

COPY coin.conf .
COPY bootstrap.scala .
