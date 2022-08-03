ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

COPY svc.conf .
COPY svc.canton .

# default arguments if none are specified by user
CMD ["daemon", "--no-tty", "--log-level-stdout", "INFO", "--config", "svc.conf", "--bootstrap", "svc.canton" ]
