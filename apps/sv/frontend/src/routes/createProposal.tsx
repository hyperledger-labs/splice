// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useSearchParams } from 'react-router';
import { CreateUnallocatedUnclaimedActivityRecordForm } from '../components/forms/CreateUnallocatedUnclaimedActivityRecordForm';
import { GrantRevokeFeaturedAppForm } from '../components/forms/GrantRevokeFeaturedAppForm';
import { OffboardSvForm } from '../components/forms/OffboardSvForm';
import { SelectAction } from '../components/forms/SelectAction';
import { SetAmuletConfigRulesForm } from '../components/forms/SetAmuletConfigRulesForm';
import { SetDsoConfigRulesForm } from '../components/forms/SetDsoConfigRulesForm';
import { UpdateSvRewardWeightForm } from '../components/forms/UpdateSvRewardWeightForm';
import { createProposalActions } from '../utils/governance';
import type { SupportedActionTag } from '../utils/types';

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
      case 'SRARC_CreateUnallocatedUnclaimedActivityRecord':
        return <CreateUnallocatedUnclaimedActivityRecordForm />;
      case 'SRARC_SetConfig':
        return <SetDsoConfigRulesForm />;
      case 'CRARC_SetConfig':
        return <SetAmuletConfigRulesForm />;
    }
  } else {
    return <SelectAction />;
  }
};
