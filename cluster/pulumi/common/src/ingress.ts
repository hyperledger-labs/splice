export function ingressPort(
  name: string,
  port: number
): { name: string; port: number; targetPort: number; protocol: string } {
  return {
    name: name,
    port: port,
    targetPort: port,
    protocol: 'TCP',
  };
}
