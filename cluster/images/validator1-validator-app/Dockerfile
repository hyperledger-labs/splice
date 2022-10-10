ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

EXPOSE 5103

COPY validator1-validator.conf .
COPY bootstrap.scala .
COPY target/directory-service-0.1.0.dar .

# default arguments if none are specified by user
CMD [ "daemon", "--no-tty", "--log-level-stdout=DEBUG", "--log-level-canton=DEBUG", "--log-file-appender=off", "--config", "validator1-validator.conf", "--bootstrap", "bootstrap.scala" ]
