// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useSearchParams } from 'react-router-dom';
import { createProposalActions } from '../utils/governance';
import { SelectAction } from '../components/forms/SelectAction';
import { CreateProposalForm } from '../components/forms/CreateProposalform';

export const CreateProposal: React.FC = () => {
  const [searchParams, _] = useSearchParams();
  const action = searchParams.get('action');
  const selectedAction = createProposalActions.find(a => a.value === action);
  if (selectedAction) {
    return <CreateProposalForm action={selectedAction} />;
  } else {
    return <SelectAction />;
  }
};
