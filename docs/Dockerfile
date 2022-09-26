FROM nginx
ARG version
COPY target/html /usr/share/nginx/html/
RUN echo $version > /usr/share/nginx/html/version
