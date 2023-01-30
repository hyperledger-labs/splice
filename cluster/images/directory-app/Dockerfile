ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

COPY target/directory-service-0.1.0.dar /app/
COPY app.conf bootstrap.sc /app/

