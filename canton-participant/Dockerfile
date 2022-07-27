FROM digitalasset/canton-open-source:2.2.0

EXPOSE 5001
EXPOSE 5002

COPY svc-participant.conf /canton
COPY bootstrap.scala /canton

ENTRYPOINT [ "bin/canton", "daemon", "--no-tty", "--log-level-stdout=INFO", "--log-level-canton=INFO", "--log-file-appender=off", "--config", "svc-participant.conf", "--bootstrap", "bootstrap.scala" ]
