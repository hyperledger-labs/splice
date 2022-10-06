ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

COPY scan.conf .

# default arguments if none are specified by user
CMD [ "daemon", "--no-tty", "--log-level-stdout=DEBUG", "--log-level-canton=DEBUG", "--log-file-appender=off", "--config", "scan.conf" ]
