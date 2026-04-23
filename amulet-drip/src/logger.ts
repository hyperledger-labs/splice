// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import pino from "pino";

const isoTime = (): string =>
  `,"@timestamp":"${new Date(Date.now()).toISOString()}"`;

export const logger = pino({
  level: process.env.LOG_LEVEL || "info",
  base: null,
  formatters: {
    level: (label) => ({ level: label }),
  },
  timestamp: isoTime,
  messageKey: "message",
});
