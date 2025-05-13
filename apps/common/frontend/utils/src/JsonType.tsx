// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
export type JSONValue = string | number | boolean | JSONObject | JSONArray | null;

export interface JSONObject {
  [x: string]: JSONValue;
}

// eslint-disable-next-line  @typescript-eslint/no-empty-object-type
interface JSONArray extends Array<JSONValue> {}
