ARG base_version
FROM --platform=linux/amd64 cn-app:${base_version}

FROM nginx
COPY default.conf /etc/nginx/conf.d
COPY --from=0 app/coin-0.1.0-SNAPSHOT/web-uis/wallet /usr/share/nginx/html/
COPY config.js /usr/share/nginx/html/
