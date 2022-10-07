ARG base_version
FROM --platform=linux/amd64 cn-app:${base_version}

FROM nginx
COPY --from=0 cn-app/coin-0.1.0-SNAPSHOT/web-uis/directory /usr/share/nginx/html/
COPY config.js /usr/share/nginx/html/
