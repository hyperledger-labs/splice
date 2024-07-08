document.addEventListener("DOMContentLoaded", () => {
  const host = window.location.hostname;
  const hostParts = host.split(".");
  if (hostParts.length === 4) {
    document.querySelectorAll(".cn-cluster").forEach((span) => {
      span.textContent = hostParts[0];
    });
  } else {
    console.debug(`Cannot set cluster, unexpected hostname: ${host}`);
  }
});
