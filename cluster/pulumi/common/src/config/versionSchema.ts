// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { z } from 'zod';

import { CnChartVersion } from '../artifacts';
import { spliceEnvConfig } from './envConfig';

export const CnChartVersionSchema = z.string().transform<CnChartVersion>((version, ctx) => {
  if (version == 'local' && spliceEnvConfig.optionalEnv('SPLICE_OPERATOR_DEPLOYMENT')) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: `Using a local version for the operator deployment is not supported.`,
    });
    return z.NEVER;
  } else {
    return CnChartVersion.parse(version);
  }
});
