// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useSearchParams } from 'react-router-dom';
import { createProposalActions } from '../utils/governance';
import { SelectAction } from '../components/forms/SelectAction';
import { SupportedActionTag } from '../utils/types';
import { UpdateSvRewardWeightForm } from '../components/forms/UpdateSvRewardWeightForm';
import { OffboardSvForm } from '../components/forms/OffboardSvForm';
import { GrantRevokeFeaturedAppForm } from '../components/forms/GrantRevokeFeaturedAppForm';
import { SetDsoConfigRulesForm } from '../components/forms/SetDsoConfigRulesForm';
import { SetAmuletConfigRulesForm } from '../components/forms/SetAmuletConfigRulesForm';

export const CreateProposal: React.FC = () => {
  const [searchParams, _] = useSearchParams();
  const action = searchParams.get('action');
  const selectedAction = createProposalActions.find(a => a.value === action);

  if (selectedAction) {
    const a = selectedAction.value as SupportedActionTag;
    switch (a) {
      case 'SRARC_UpdateSvRewardWeight':
        return <UpdateSvRewardWeightForm />;
      case 'SRARC_OffboardSv':
        return <OffboardSvForm />;
      case 'SRARC_GrantFeaturedAppRight':
        return <GrantRevokeFeaturedAppForm selectedAction={'SRARC_GrantFeaturedAppRight'} />;
      case 'SRARC_RevokeFeaturedAppRight':
        return <GrantRevokeFeaturedAppForm selectedAction={'SRARC_RevokeFeaturedAppRight'} />;
      case 'SRARC_SetConfig':
        return <SetDsoConfigRulesForm />;
      case 'CRARC_SetConfig':
        return <SetAmuletConfigRulesForm />;
    }
  } else {
    return <SelectAction />;
  }
};
