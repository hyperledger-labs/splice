ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

EXPOSE 5003
EXPOSE 10013

COPY app.conf bootstrap.sc pre-bootstrap.sh /app/
