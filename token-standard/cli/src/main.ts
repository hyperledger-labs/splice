// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { createProgram } from "./cli";

try {
  createProgram().parse();
} catch (e) {
  console.error("Failed to run CLI", e);
  throw e;
}
