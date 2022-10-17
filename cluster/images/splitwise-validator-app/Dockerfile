ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

EXPOSE 5203

COPY coin.conf .
COPY target/splitwise-0.1.0.dar .
