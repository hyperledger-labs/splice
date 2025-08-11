// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { createFormHookContexts } from '@tanstack/react-form';

export const { fieldContext, useFieldContext, formContext, useFormContext } =
  createFormHookContexts();
