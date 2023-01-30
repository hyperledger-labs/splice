ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

EXPOSE 5103

COPY target/directory-service-0.1.0.dar target/splitwise-0.1.0.dar /app/
COPY app.conf bootstrap.sc /app/
