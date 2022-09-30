ARG base_version

FROM --platform=linux/amd64 cn-app:${base_version}

EXPOSE 5104

COPY validator1-wallet.conf .

# default arguments if none are specified by user
CMD ["daemon", "--no-tty", "--log-level-stdout", "DEBUG", "--log-level-canton=DEBUG", "--config", "validator1-wallet.conf" ]
