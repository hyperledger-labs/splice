ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

EXPOSE 5203

COPY target/splitwell-0.1.0.dar /app/
COPY app.conf pre-bootstrap.sh /app/
