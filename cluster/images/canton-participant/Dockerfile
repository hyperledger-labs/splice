ARG base_version

FROM --platform=linux/amd64 canton:${base_version}

EXPOSE 5001
EXPOSE 5002
EXPOSE 10013

COPY canton.conf .
COPY bootstrap.scala .
