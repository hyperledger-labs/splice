#!/bin/sh

echo "Installing ingress modes: ${ENABLE_INGRESS_MODES}"

for modename in ${ENABLE_INGRESS_MODES}; do
    echo "Installing mode: ${modename}"

    cp -v /conf-modes/"${modename}"/* /etc/nginx/conf.d/
done
echo "Done installing ingres modes."
