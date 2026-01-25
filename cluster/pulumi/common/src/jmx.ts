// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
/** Returns Java command line options required to enable remote JMX connections on the given port */
export function jmxOptions(port = 9010): string {
  return [
    '-Dcom.sun.management.jmxremote=true',
    `-Dcom.sun.management.jmxremote.port=${port}`,
    `-Dcom.sun.management.jmxremote.rmi.port=${port}`,
    '-Dcom.sun.management.jmxremote.local.only=false',
    '-Dcom.sun.management.jmxremote.authenticate=false', // No security
    '-Dcom.sun.management.jmxremote.ssl=false', // No security
    '-Djava.rmi.server.hostname=127.0.0.1', // To be used with port forwarding
  ].join(' ');
}
