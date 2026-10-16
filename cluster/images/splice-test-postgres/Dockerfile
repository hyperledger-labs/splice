# 14.24-bookworm@sha256:5a05f61a534e50e679d27fb45953afec7821f2ffc2aa6c738b94515ca0891ac2
FROM postgres@sha256:5a05f61a534e50e679d27fb45953afec7821f2ffc2aa6c738b94515ca0891ac2
LABEL org.opencontainers.image.base.name="postgres:14.24-bookworm"

COPY cmd.sh /usr/local/bin/
COPY target/LICENSE .

CMD ["cmd.sh"]
