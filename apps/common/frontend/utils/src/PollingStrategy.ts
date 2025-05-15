// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
const defaultInterval = 1000; // in ms

export class PollingStrategy {
  /** disable polling outright */
  static NONE = false as const;

  /** poll with the default interval forever */
  static FIXED: number = defaultInterval;
}
