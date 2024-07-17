# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# FROM https://github.com/pulumi/pulumi-kubernetes-operator/blob/master/Dockerfile
# used to control he underlyign pulumi version
ARG pulumi_version

# use the nix version back once it's >3.113 (has required ts support)
FROM pulumi/pulumi:3.113.3

RUN apt-get install tini
ENTRYPOINT ["tini", "--", "/usr/local/bin/pulumi-kubernetes-operator"]

# install operator binary
RUN wget -O - -c "https://github.com/pulumi/pulumi-kubernetes-operator/releases/download/v1.15.0/pulumi-kubernetes-operator_.1.15.0_.Linux_x86_64.tar.gz" | tar -xz -C /usr/local/bin/

COPY pulumi-override.sh /usr/bin/pulumi-override.sh
RUN useradd -m pulumi-kubernetes-operator
RUN mkdir -p /home/pulumi-kubernetes-operator/.ssh \
    && touch /home/pulumi-kubernetes-operator/.ssh/known_hosts \
    && chmod 700 /home/pulumi-kubernetes-operator/.ssh \
    && chown -R pulumi-kubernetes-operator:pulumi-kubernetes-operator /home/pulumi-kubernetes-operator/.ssh \
    && mv /usr/bin/pulumi /usr/bin/pulumi-original \
    && mv /usr/bin/pulumi-override.sh /usr/bin/pulumi \
    && chown pulumi-kubernetes-operator:pulumi-kubernetes-operator /usr/bin/pulumi \
    && chmod +x /usr/bin/pulumi

USER pulumi-kubernetes-operator

ENV XDG_CONFIG_HOME=/tmp/.config
ENV XDG_CACHE_HOME=/tmp/.cache
ENV XDG_CONFIG_CACHE=/tmp/.cache
ENV GOCACHE=/tmp/.cache/go-build
ENV GOPATH=/tmp/.cache/go
