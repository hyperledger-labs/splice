document.addEventListener("DOMContentLoaded", () => {
  document.querySelectorAll(".splice-cluster").forEach((span) => {
    // The value here will be substituted by a envsubst in the pod entrypoint to reflect the cluster on which it is deployed
    span.textContent = "${SPLICE_CLUSTER}";
  });
  document.querySelectorAll(".splice-url-prefix").forEach((span) => {
    // The value here will be substituted by a envsubst in the pod entrypoint to reflect the cluster on which it is deployed
    span.textContent = "${SPLICE_URL_PREFIX}";
  });
});
