#!/usr/bin/env bash
set -eou pipefail
source "${TOOLS_LIB}/libcli.source"

# This script does three things:
#   1. It downloads a particular version of `cert-manager`'s deployment manifest, in YAML form, from their GitHub releases page, and stores it in this directory
#   2. It mirrors the `cert-manager` container images hosted on quay.io to our private artifact registry
#   3. It substitutes image references in the manifest with links to our mirrored images instead

# This script should not have to be run frequently, unless your intention is to bump cert-manager's version

CERT_MANAGER_VERSION="1.10.0"
OUT_FILE="cert-manager.yaml"

SOURCE_REPO_PREFIX="quay.io/jetstack"
DEST_REPO_PREFIX="${CLOUDSDK_COMPUTE_REGION}-docker.pkg.dev/da-cn-images/cert-manager-images"

IMAGE_NAMES=(
    "cert-manager-cainjector"
    "cert-manager-controller"
    "cert-manager-webhook"
)

function download_cert_manager_manifest() {
    local manifest_url="https://github.com/cert-manager/cert-manager/releases/download/v$CERT_MANAGER_VERSION/$OUT_FILE"
    _log "Downloading $OUT_FILE from $manifest_url"

    curl -sSL "$manifest_url" --output "$OUT_FILE"
}

function copy_image() {
    local name="$1"
    local source_tag="$SOURCE_REPO_PREFIX/$name:v$CERT_MANAGER_VERSION"
    local dest_tag="$DEST_REPO_PREFIX/$name:v$CERT_MANAGER_VERSION"

    docker pull --platform linux/amd64 "$source_tag"
    docker tag "$source_tag" "$dest_tag"
    docker push "$dest_tag"
}

function mirror_container_images() {
    for img in "${IMAGE_NAMES[@]}"; do
        _log "Mirroring image $img to GCP artifact repo..."
        copy_image "$img"
    done
}

function rewrite_image_refs() {
    for name in "${IMAGE_NAMES[@]}"; do
        _log "Rewriting image reference for $name in $OUT_FILE..."
        local source_tag="$SOURCE_REPO_PREFIX/$name:v$CERT_MANAGER_VERSION"
        local dest_tag="$DEST_REPO_PREFIX/$name:v$CERT_MANAGER_VERSION"

        sed -i "s@$source_tag@$dest_tag@g" $OUT_FILE
    done
}

download_cert_manager_manifest
mirror_container_images
rewrite_image_refs
