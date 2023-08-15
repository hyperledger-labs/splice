ARG base_version

FROM --platform=linux/amd64 canton:${base_version}

EXPOSE 5008
EXPOSE 5009
EXPOSE 10013
ENV EXTRA_CLASSPATH=/app/cometbft-driver.jar

COPY target/driver.jar cometbft-driver.jar
COPY app.conf /app/
