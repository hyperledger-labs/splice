FROM --platform=linux/amd64 grafana/k6:0.48.0

COPY entrypoint.sh .
COPY target/test/* ./

ENTRYPOINT [ "/bin/sh" ]
CMD [ "/home/k6/entrypoint.sh" ]
