FROM nginx

ENV ENABLE_INGRESS_MODES="sv full"

COPY 90-install-mode-conf-files.sh /docker-entrypoint.d/
RUN chmod +x /docker-entrypoint.d/90-install-mode-conf-files.sh

COPY conf-modes/ /conf-modes/

COPY conf/* /etc/nginx/conf.d/

