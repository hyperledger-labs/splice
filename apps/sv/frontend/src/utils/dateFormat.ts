// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import dayjs from 'dayjs';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';

export const formatDatetimeWithOffset = (d: dayjs.ConfigType): string =>
  dayjs(d).format(`${dateTimeFormatISO} [(UTC]Z[)]`);
