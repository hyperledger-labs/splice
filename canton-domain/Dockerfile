FROM digitalasset/canton-open-source:2.2.0

EXPOSE 6018
EXPOSE 6019

COPY svc-domain.conf /canton
CMD [ "daemon", "--no-tty", "--log-level-stdout", "INFO", "--log-level-canton", "INFO", "--log-file-appender", "off", "--config", "svc-domain.conf" ]
