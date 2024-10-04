// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DecoderError } from '@mojotech/json-type-validation/dist/types/decoder';
import { useMutation } from '@tanstack/react-query';
import { ActionView, DisableConditionally, SvClientProvider } from 'common-frontend';
import { getUTCWithOffset } from 'common-frontend-utils';
import { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React, { useCallback, useEffect, useState } from 'react';

import {
  Box,
  Button,
  Card,
  CardContent,
  FormControl,
  NativeSelect,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { DesktopDateTimePicker } from '@mui/x-date-pickers/DesktopDateTimePicker';

import { RelTime } from '@daml.js/b70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946/lib/DA/Time/Types';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { useDsoInfos } from '../../contexts/SvContext';
import { useListDsoRulesVoteRequests } from '../../hooks/useListVoteRequests';
import { useSvConfig } from '../../utils';
import { Alerting, AlertState } from '../../utils/Alerting';
import {
  isExpirationBeforeEffectiveDate,
  isScheduleDateTimeValid,
  VoteRequestValidity,
} from '../../utils/validations';
import SvListVoteRequests from './SvListVoteRequests';
import AddFutureAmuletConfigSchedule from './actions/AddFutureAmuletConfigSchedule';
import GrantFeaturedAppRight from './actions/GrantFeaturedAppRight';
import OffboardSv from './actions/OffboardSv';
import RemoveFutureAmuletConfigSchedule from './actions/RemoveFutureAmuletConfigSchedule';
import RevokeFeaturedAppRight from './actions/RevokeFeaturedAppRight';
import SetDsoRulesConfig from './actions/SetDsoRulesConfig';
import UpdateFutureAmuletConfigSchedule from './actions/UpdateFutureAmuletConfigSchedule';
import UpdateSvRewardWeight from './actions/UpdateSvRewardWeight';

dayjs.extend(utc);

export type ActionFromForm = ActionRequiringConfirmation | { formError: DecoderError };

export function actionFromFormIsError(
  action: ActionFromForm
): action is { formError: DecoderError } {
  return !!(action as { formError: DecoderError }).formError;
}

export const CreateVoteRequest: React.FC = () => {
  // States related to vote requests
  const [actionName, setActionName] = useState('SRARC_OffboardSv');
  const [summary, setSummary] = useState<string>('');
  const [url, setUrl] = useState<string>('');
  const [expiration, setExpiration] = useState<Dayjs | null>(null);

  // States related to constraints from vote requests
  const [
    maxDateTimeIfAddFutureAmuletConfigSchedule,
    setMaxDateTimeIfAddFutureAmuletConfigSchedule,
  ] = useState<Dayjs | undefined>(undefined);
  const [alertMessage, setAlertMessage] = useState<AlertState>({});

  const dsoInfosQuery = useDsoInfos();
  const listVoteRequestsQuery = useListDsoRulesVoteRequests();

  function getDefaultExpiration(): Dayjs {
    switch (actionName) {
      case 'CRARC_RemoveFutureAmuletConfigSchedule':
      case 'CRARC_UpdateFutureAmuletConfigSchedule':
      case 'CRARC_AddFutureAmuletConfigSchedule': {
        return dayjs();
      }
      default: {
        const microseconds =
          parseInt(dsoInfosQuery.data?.dsoRules.payload.config.voteRequestTimeout.microseconds!) /
          1000;
        return dayjs().add(Math.floor(microseconds), 'milliseconds');
      }
    }
  }

  useEffect(() => {
    setExpiration(getDefaultExpiration);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dsoInfosQuery.isSuccess]);

  useEffect(() => {
    setExpiration(getDefaultExpiration);
    setMaxDateTimeIfAddFutureAmuletConfigSchedule(undefined);
    setUrl('');
    setSummary('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [actionName, setActionName]); // same than above

  const actionNameOptions = [
    { name: 'Offboard Member', value: 'SRARC_OffboardSv' },
    { name: 'Feature Application', value: 'SRARC_GrantFeaturedAppRight' },
    { name: 'Unfeature Application', value: 'SRARC_RevokeFeaturedAppRight' },
    { name: 'Set DsoRules Configuration', value: 'SRARC_SetConfig' },
    { name: 'Add DSO App Configuration Schedule', value: 'CRARC_AddFutureAmuletConfigSchedule' },
    {
      name: 'Remove DSO App Configuration Schedule',
      value: 'CRARC_RemoveFutureAmuletConfigSchedule',
    },
    {
      name: 'Update DSO App Configuration Schedule',
      value: 'CRARC_UpdateFutureAmuletConfigSchedule',
    },
    { name: 'Update SV Reward Weight', value: 'SRARC_UpdateSvRewardWeight' },
  ];

  const [action, setAction] = useState<ActionFromForm | undefined>(undefined);
  const chooseAction = useCallback(
    (action: ActionFromForm) => {
      try {
        ActionRequiringConfirmation.encode(action as ActionRequiringConfirmation);
        setAction(action);
      } catch (error) {
        console.log('Caught expected DecoderError in case of null values: ', error);
      }
      const max = (time1: Dayjs, time2: Dayjs) => (time1 > time2 ? time1 : time2);
      if (!actionFromFormIsError(action)) {
        if (action.tag === 'ARC_AmuletRules') {
          switch (action.value.amuletRulesAction.tag) {
            case 'CRARC_AddFutureAmuletConfigSchedule': {
              setMaxDateTimeIfAddFutureAmuletConfigSchedule(
                max(dayjs(), dayjs(action.value.amuletRulesAction.value.newScheduleItem._1))
              );
              return;
            }
            case 'CRARC_UpdateFutureAmuletConfigSchedule': {
              setMaxDateTimeIfAddFutureAmuletConfigSchedule(
                max(dayjs(), dayjs(action.value.amuletRulesAction.value.scheduleItem._1))
              );
              return;
            }
            case 'CRARC_RemoveFutureAmuletConfigSchedule': {
              setMaxDateTimeIfAddFutureAmuletConfigSchedule(
                max(dayjs(), dayjs(action.value.amuletRulesAction.value.scheduleTime))
              );
              return;
            }
          }
        }
      }
    },
    [setAction, setMaxDateTimeIfAddFutureAmuletConfigSchedule]
  );

  function validateAction(action: ActionRequiringConfirmation) {
    if (action?.tag !== 'ARC_AmuletRules') {
      setAlertMessage({});
      return true;
    }

    const amuletRulesAction = action.value.amuletRulesAction;
    let effectiveDate: string;

    switch (amuletRulesAction.tag) {
      case 'CRARC_AddFutureAmuletConfigSchedule':
        effectiveDate = amuletRulesAction.value.newScheduleItem._1;
        break;
      case 'CRARC_UpdateFutureAmuletConfigSchedule':
        effectiveDate = amuletRulesAction.value.scheduleItem._1;
        break;
      case 'CRARC_RemoveFutureAmuletConfigSchedule':
        effectiveDate = amuletRulesAction.value.scheduleTime;
        break;
      default:
        setAlertMessage({});
        return true;
    }

    const scheduleValidity: VoteRequestValidity = isScheduleDateTimeValid(
      listVoteRequestsQuery.data!,
      effectiveDate
    );
    if (!scheduleValidity.isValid) {
      setAlertMessage(scheduleValidity.alertMessage);
    }

    const dateValidity = isExpirationBeforeEffectiveDate(dayjs(effectiveDate), expiration);
    if (!dateValidity.isValid) {
      setAlertMessage(dateValidity.alertMessage);
    }

    return scheduleValidity.isValid && dateValidity.isValid;
  }

  const { createVoteRequest } = useSvAdminClient();
  const createVoteRequestMutation = useMutation({
    mutationFn: async () => {
      const requester = dsoInfosQuery.data?.svPartyId!;

      const duration: RelTime = {
        microseconds: BigInt(expiration!.diff(dayjs(), 'milliseconds') * 1000).toString(),
      };

      if (
        action &&
        actionNameOptions.map(e => e.value).includes(actionName) &&
        !actionFromFormIsError(action) &&
        validateAction(action)
      ) {
        return await createVoteRequest(requester, action, url, summary, duration)
          .then(() => setUrl(''))
          .then(() => setSummary(''))
          .then(() => setActionName('SRARC_OffboardSv'))
          .then(() => setAction(undefined))
          .then(() => setExpiration(getDefaultExpiration))
          .then(() => setMaxDateTimeIfAddFutureAmuletConfigSchedule(undefined))
          .then(() => setAlertMessage({}));
      }
    },

    onError: error => {
      // TODO (#5491): show an error to the user.
      console.error(`Failed to send vote request to dso`, error);
    },
  });

  // used and valid only for dsoRules-based actions
  let expiresAt;
  try {
    expiresAt = expiration?.toISOString();
  } catch (error) {
    expiresAt = undefined;
  }

  // TODO (#4966): add a popup to ask confirmation
  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={4} variant="h4">
        Create Vote Request
      </Typography>
      <Card variant="elevation">
        <CardContent sx={{ paddingX: '64px' }}>
          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h5">Action</Typography>
            <FormControl fullWidth>
              <NativeSelect
                inputProps={{
                  id: 'display-actions',
                  // @ts-ignore
                  'data-testid': 'display-actions',
                }}
                value={actionName}
                onChange={e => setActionName(e.target.value)}
              >
                {actionNameOptions.map((actionName, index) => (
                  <option key={'action-option-' + index} value={actionName.value}>
                    {actionName.name}
                  </option>
                ))}
              </NativeSelect>
            </FormControl>
          </Stack>
          {actionName === 'SRARC_OffboardSv' && <OffboardSv chooseAction={chooseAction} />}
          {actionName === 'SRARC_GrantFeaturedAppRight' && (
            <GrantFeaturedAppRight chooseAction={chooseAction} />
          )}
          {actionName === 'SRARC_RevokeFeaturedAppRight' && (
            <RevokeFeaturedAppRight chooseAction={chooseAction} />
          )}
          {actionName === 'SRARC_SetConfig' && <SetDsoRulesConfig chooseAction={chooseAction} />}
          {actionName === 'CRARC_AddFutureAmuletConfigSchedule' && (
            <AddFutureAmuletConfigSchedule chooseAction={chooseAction} />
          )}
          {actionName === 'CRARC_RemoveFutureAmuletConfigSchedule' && (
            <RemoveFutureAmuletConfigSchedule chooseAction={chooseAction} />
          )}
          {actionName === 'CRARC_UpdateFutureAmuletConfigSchedule' && (
            <UpdateFutureAmuletConfigSchedule chooseAction={chooseAction} />
          )}
          {actionName === 'SRARC_UpdateSvRewardWeight' && (
            <UpdateSvRewardWeight chooseAction={chooseAction} action={action} />
          )}
          <Typography variant="h5">Proposal</Typography>

          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">Summary</Typography>
            <TextField
              error={!summary}
              id="create-reason-summary"
              rows={2}
              multiline
              onChange={e => setSummary(e.target.value)}
              value={summary}
            />
          </Stack>

          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">URL</Typography>
            <Box display="flex">
              <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
                <TextField
                  id="create-reason-url"
                  onChange={e => setUrl(e.target.value)}
                  value={url}
                />
              </FormControl>
              <Button size={'small'} onClick={() => window.open(url, '_blank')}>
                Open
              </Button>
            </Box>
          </Stack>
          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h5" mt={4}>
              Vote Request Expires At
            </Typography>
            <DesktopDateTimePicker
              label={`Enter time in local timezone (${getUTCWithOffset()})`}
              value={expiration}
              minDateTime={dayjs()}
              maxDateTime={maxDateTimeIfAddFutureAmuletConfigSchedule}
              readOnly={false}
              onChange={(newValue: Dayjs | null) => setExpiration(newValue)}
              slotProps={{
                textField: {
                  id: 'datetime-picker-vote-request-expiration',
                },
              }}
              closeOnSelect
            />
          </Stack>
          {action && (
            <Stack direction="column" mb={4} spacing={1}>
              <Typography variant="h5">Review vote request</Typography>
              <ActionView
                action={
                  ActionRequiringConfirmation.encode(
                    action as ActionRequiringConfirmation
                  ) as ActionRequiringConfirmation
                }
                effectiveAt={expiresAt}
              />
            </Stack>
          )}
          <Alerting alertState={alertMessage} />

          <Stack direction="column" mb={4} spacing={1}>
            <DisableConditionally
              conditions={[
                { disabled: createVoteRequestMutation.isLoading, reason: 'Loading...' },
                {
                  disabled: !action || actionFromFormIsError(action),
                  reason: !action
                    ? 'No action'
                    : `Action is not valid: ${
                        actionFromFormIsError(action) && JSON.stringify(action.formError)
                      }`,
                },
                { disabled: summary === '', reason: 'No summary' },
              ]}
            >
              <Button
                id="create-voterequest-submit-button"
                fullWidth
                type={'submit'}
                size="large"
                onClick={() => {
                  createVoteRequestMutation.mutate();
                }}
              >
                Send request to collective
              </Button>
            </DisableConditionally>
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  );
};

const VoteRequestWithContexts: React.FC = () => {
  const config = useSvConfig();

  return (
    <SvClientProvider url={config.services.sv.url}>
      <CreateVoteRequest />
      <SvListVoteRequests />
    </SvClientProvider>
  );
};

export default VoteRequestWithContexts;
