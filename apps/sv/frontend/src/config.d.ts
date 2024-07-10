// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
export {};

declare global {
  interface Window {
    splice_config: import('utils/config.ts').Config;
  }
}
