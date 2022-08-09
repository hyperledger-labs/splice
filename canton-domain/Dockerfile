ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

EXPOSE 5008
EXPOSE 5009

COPY svc-domain.conf .
CMD [ "daemon", "--no-tty", "--log-level-stdout", "DEBUG", "--log-level-canton", "DEBUG", "--log-file-appender", "off", "--config", "svc-domain.conf" ]
