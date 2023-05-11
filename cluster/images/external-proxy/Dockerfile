FROM nginx

# Select one or more sets of services to proxy from those available in conf-modes

ENV ENABLE_INGRESS_MODES="full"

COPY 90-install-mode-conf-files.sh /docker-entrypoint.d/
RUN chmod +x /docker-entrypoint.d/90-install-mode-conf-files.sh

COPY conf-modes/ /conf-modes/

COPY conf/* /etc/nginx/conf.d/

