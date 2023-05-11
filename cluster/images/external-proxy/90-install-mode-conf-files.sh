#!/bin/sh

echo "Installing ingress modes: ${ENABLE_INGRESS_MODES}"

for modename in ${ENABLE_INGRESS_MODES}; do
    echo "Installing mode: ${modename}"

    mode_directory="/conf-modes/${modename}"

    if [ ! -d "$mode_directory" ]; then
        echo "Directory for mode ${modename} missing, mode not supported. ($mode_directory)"
        exit 1
    fi

    cp -v "$mode_directory"/* /etc/nginx/conf.d/
done
echo "Done installing ingres modes."
