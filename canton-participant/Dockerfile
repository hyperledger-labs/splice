FROM digitalasset/canton-open-source:2.2.0

EXPOSE 6864
EXPOSE 6865
EXPOSE 7000

COPY svc-participant.conf /canton
COPY bootstrap.scala /canton

ENTRYPOINT [ "bin/canton", "daemon", "--no-tty", "--log-level-stdout=INFO", "--log-level-canton=INFO", "--log-file-appender=off", "--config", "svc-participant.conf", "--bootstrap", "bootstrap.scala" ]