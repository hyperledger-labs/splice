// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
export const DAML_TIMESTAMP_FORMAT = 'YYYY-MM-DDTHH:mm:ss.SSSSSSZ';
// eq to the above, but enforces 6 digits for microseconds (as expected in daml)
// dayjs doesn't, because JS dates don't support microsecond precision
export const DAML_TIMESTAMP_REGEX = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/;
export function isValidDamlTimestamp(str: string): boolean {
  return DAML_TIMESTAMP_REGEX.test(str);
}
// only call this if the timestamp is valid
// this is necessary only because JS doesn't support microsecond precision
export function damlTimestampToOpenApiTimestamp(str: string): number {
  const timestampWithMillisecondPrecision = new Date(str);
  // valueOf returns milliseconds since epoch
  const millis = timestampWithMillisecondPrecision.valueOf();
  // get the last 3 characters (microseconds), excluding the Z (timezone, UTC)
  const micros = Number(str.slice(str.length - 4, str.length - 1));
  return millis * 1000 + micros;
}
