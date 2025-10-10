// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { createFormHook } from '@tanstack/react-form';
import { DateField } from '../components/form-components/DateField';
import { fieldContext, formContext } from './formContext';
import { TextField } from '../components/form-components/TextField';
import { SelectField } from '../components/form-components/SelectField';
import { ConfigField } from '../components/form-components/ConfigField';
import { FormControls } from '../components/form-components/FormControls';
import { EffectiveDateField } from '../components/form-components/EffectiveDateField';
import { FormErrors } from '../components/form-components/FormErrors';
import { ProposalSummaryField } from '../components/form-components/ProposalSummaryField';

export const { useAppForm } = createFormHook({
  fieldComponents: {
    ConfigField,
    DateField,
    EffectiveDateField,
    SelectField,
    ProposalSummaryField,
    TextField,
  },
  formComponents: {
    FormControls,
    FormErrors,
  },
  fieldContext,
  formContext,
});
