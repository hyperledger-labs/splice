// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

const pollIntervalSchema = z.preprocess(x => (x ? Number(x) : undefined), z.number().optional());

export { pollIntervalSchema };
