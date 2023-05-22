FROM nginx

COPY 90-install-conf-files.sh /docker-entrypoint.d/
RUN chmod +x /docker-entrypoint.d/90-install-conf-files.sh

COPY conf/ /conf/
