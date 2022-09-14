ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

COPY directory.conf .
COPY directory-service-0.1.0.dar .
COPY bootstrap.scala .

# default arguments if none are specified by user
CMD ["daemon", "--no-tty", "--log-level-stdout", "INFO", "--config", "directory.conf", "--bootstrap", "bootstrap.scala" ]
