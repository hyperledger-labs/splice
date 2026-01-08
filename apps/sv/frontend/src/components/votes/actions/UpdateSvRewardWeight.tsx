// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import React, { useCallback, useEffect, useMemo } from 'react';

import { FormControl, NativeSelect, Stack, TextField, Typography } from '@mui/material';

import {
  ActionRequiringConfirmation,
  DsoRules_UpdateSvRewardWeight,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { useDsoInfos } from '../../../contexts/SvContext';
import { ActionFromForm, actionFromFormIsError } from '../VoteRequest';

function asUpdateSvRewardWeight(
  action?: ActionFromForm
): DsoRules_UpdateSvRewardWeight | undefined {
  return action &&
    !actionFromFormIsError(action) &&
    action.tag === 'ARC_DsoRules' &&
    action.value.dsoAction.tag === 'SRARC_UpdateSvRewardWeight'
    ? action.value.dsoAction.value
    : undefined;
}

const UpdateSvRewardWeight: React.FC<{
  chooseAction: (action: ActionRequiringConfirmation) => void;
  action?: ActionFromForm;
}> = ({ chooseAction, action }) => {
  const updateSvRewardWeightAction = asUpdateSvRewardWeight(action);

  const dsoInfosQuery = useDsoInfos();

  const svs = useMemo(
    () => dsoInfosQuery.data?.dsoRules.payload.svs.entriesArray() || [],
    [dsoInfosQuery.data]
  );

  const updateAction = useCallback(
    (newSv: string, newWeight: string) => {
      chooseAction({
        tag: 'ARC_DsoRules',
        value: {
          dsoAction: {
            tag: 'SRARC_UpdateSvRewardWeight',
            value: { svParty: newSv, newRewardWeight: newWeight },
          },
        },
      });
    },
    [chooseAction]
  );

  useEffect(() => {
    if (svs.length > 0 && !updateSvRewardWeightAction) {
      const [partyId, svInfo] = svs[0];
      updateAction(partyId, svInfo.svRewardWeight);
    }
  }, [svs, updateSvRewardWeightAction, updateAction]);

  if (svs.length < 1 || !updateSvRewardWeightAction) {
    return <Loading />;
  }

  if (dsoInfosQuery.isError) {
    return <p>Error: {JSON.stringify(dsoInfosQuery.error)}</p>;
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Member</Typography>
      <FormControl fullWidth>
        <NativeSelect
          inputProps={{ id: 'display-members' }}
          value={updateSvRewardWeightAction.svParty}
          onChange={e => updateAction(e.target.value, updateSvRewardWeightAction.newRewardWeight)}
        >
          {svs &&
            svs.map(([partyId, svInfo]) => (
              <option key={'member-option-' + partyId} value={partyId}>
                {svInfo.name}
              </option>
            ))}
        </NativeSelect>
      </FormControl>
      <Typography variant="h6">New Weight</Typography>
      <FormControl fullWidth>
        <TextField
          id="reward-weight"
          type="number"
          value={updateSvRewardWeightAction.newRewardWeight || '0000'}
          onChange={e => updateAction(updateSvRewardWeightAction.svParty, e.target.value)}
        />
      </FormControl>
    </Stack>
  );
};

export default UpdateSvRewardWeight;
