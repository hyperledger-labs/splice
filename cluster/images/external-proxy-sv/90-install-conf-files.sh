#!/bin/sh

echo "Installing config files"
ls /conf/*

for file in /conf/*; do
    echo "Installing file $file"

    basefile=$(basename "$file")
    newfile="/etc/nginx/conf.d/$basefile"
    # shellcheck disable=SC2016 disable=SC2086
    envsubst '${INGRESS_CONFIG_SV_NAMESPACE}' < "$file" > "$newfile"

    echo "envsubst used, created config $newfile:"
    cat "$newfile"
done
