#!/bin/sh

echo "Installing ingress modes: ${ENABLE_INGRESS_MODES}"

for modename in ${ENABLE_INGRESS_MODES}; do
    echo "Installing mode: ${modename}"

    mode_directory="/conf-modes/${modename}"

    if [ ! -d "$mode_directory" ]; then
        echo "Directory for mode ${modename} missing, mode not supported. ($mode_directory)"
        exit 1
    fi

    for file in "${mode_directory}"/*; do
        echo "Installing file $file"

        basefile=$(basename "$file")
        newfile="/etc/nginx/conf.d/$basefile"
        # shellcheck disable=SC2016 disable=SC2086
        envsubst '${INGRESS_CONFIG_SV_NAMESPACE}' < "$file" > "$newfile"

        echo "envsubst used, created config $newfile:"
        cat "$newfile"
    done
done
echo "Done installing ingres modes."
