ARG base_version
FROM --platform=linux/amd64 cn-app:${base_version}

FROM nginx
COPY --from=0 app/cn-node-0.1.0-SNAPSHOT/web-uis/directory /usr/share/nginx/html/

COPY config.js /tmpl/config.js.tmpl

COPY docker-entrypoint.sh /custom-docker-entrypoint.sh
RUN chmod 500 /custom-docker-entrypoint.sh
ENTRYPOINT ["/custom-docker-entrypoint.sh"]
