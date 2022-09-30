ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

EXPOSE 5101
EXPOSE 5102

COPY validator1-participant.conf .
COPY bootstrap.scala .

CMD [ "daemon", "--no-tty", "--log-level-stdout=DEBUG", "--log-level-canton=DEBUG", "--log-file-appender=off", "--config", "validator1-participant.conf", "--bootstrap", "bootstrap.scala" ]
