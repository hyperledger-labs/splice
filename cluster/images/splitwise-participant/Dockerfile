ARG base_version

FROM --platform=linux/amd64 canton:${base_version}

EXPOSE 5201
EXPOSE 5202

COPY canton.conf .
COPY bootstrap.scala .
