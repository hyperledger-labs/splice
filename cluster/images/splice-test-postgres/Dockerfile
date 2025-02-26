FROM postgres:14
LABEL org.opencontainers.image.base.name="postgres:14"

COPY cmd.sh /usr/local/bin/
COPY target/LICENSE .

CMD ["cmd.sh"]
