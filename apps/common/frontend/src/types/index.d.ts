// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Config } from '../config';

export {};

declare global {
  interface Window {
    splice_config: Config;
  }
  type Conversion = 'CCtoUSD' | 'USDtoCC';
}
