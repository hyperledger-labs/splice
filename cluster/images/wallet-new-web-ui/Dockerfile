ARG base_version
FROM --platform=linux/amd64 cn-app:${base_version}

FROM nginx
RUN apt-get update && \
    apt-get install gettext -y
COPY default.conf /etc/nginx/conf.d
COPY --from=0 app/coin-0.1.0-SNAPSHOT/web-uis/wallet-new /usr/share/nginx/html/

COPY config.js /tmpl/config.js.tmpl

COPY docker-entrypoint.sh /custom-docker-entrypoint.sh
RUN chmod 500 /custom-docker-entrypoint.sh
ENTRYPOINT ["/custom-docker-entrypoint.sh"]
