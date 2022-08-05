ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

EXPOSE 5001
EXPOSE 5002

COPY svc-participant.conf .
COPY bootstrap.scala .

CMD [ "daemon", "--no-tty", "--log-level-stdout=INFO", "--log-level-canton=INFO", "--log-file-appender=off", "--config", "svc-participant.conf", "--bootstrap", "bootstrap.scala" ]
