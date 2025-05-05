// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  ActionView,
  Alerting,
  AlertState,
  DateWithDurationDisplay,
  DisableConditionally,
  SvClientProvider,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { getUTCWithOffset } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { DecoderError } from '@mojotech/json-type-validation/dist/types/decoder';
import { useMutation } from '@tanstack/react-query';
import dayjs, { Dayjs } from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React, { useCallback, useEffect, useState } from 'react';

import {
  Box,
  Button,
  Card,
  CardContent,
  Checkbox,
  FormControl,
  NativeSelect,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { DesktopDateTimePicker } from '@mui/x-date-pickers/DesktopDateTimePicker';

import { RelTime } from '@daml.js/daml-stdlib-DA-Time-Types-1.0.0/lib/DA/Time/Types/module';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { useDsoInfos } from '../../contexts/SvContext';
import { useListDsoRulesVoteRequests } from '../../hooks';
import { useSvConfig } from '../../utils';
import { hasConflictingFields } from '../../utils/configDiffs';
import {
  isExpirationBeforeEffectiveDate,
  isScheduleDateTimeValid,
  isValidUrl,
  isValidVoteRequestUrl,
  VoteRequestValidity,
} from '../../utils/validations';
import SvListVoteRequests from './SvListVoteRequests';
import AddFutureAmuletConfigSchedule from './actions/AddFutureAmuletConfigSchedule';
import GrantFeaturedAppRight from './actions/GrantFeaturedAppRight';
import OffboardSv from './actions/OffboardSv';
import RemoveFutureAmuletConfigSchedule from './actions/RemoveFutureAmuletConfigSchedule';
import RevokeFeaturedAppRight from './actions/RevokeFeaturedAppRight';
import SetAmuletRulesConfig from './actions/SetAmuletRulesConfig';
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

export const CreateVoteRequest: React.FC<{ supportsVoteEffectivityAndSetConfig: boolean }> = ({
  supportsVoteEffectivityAndSetConfig,
}) => {
  // States related to vote requests
  const [actionName, setActionName] = useState('SRARC_OffboardSv');
  const [summary, setSummary] = useState<string>('');
  const [url, setUrl] = useState<string>('');
  const [isEffective, setIsEffective] = useState(true);
  const [effectivity, setEffectivity] = useState<Dayjs>(dayjs());
  const [expiration, setExpiration] = useState<Dayjs>(dayjs());
  const [confirmDialogOpen, setConfirmDialogOpen] = useState(false);
  const [disableProceed, setDisableProceed] = useState(false);

  // States related to constraints from vote requests
  const [
    maxDateTimeIfAddFutureAmuletConfigSchedule,
    setMaxDateTimeIfAddFutureAmuletConfigSchedule,
  ] = useState<Dayjs | undefined>(undefined);
  const [alertMessage, setAlertMessage] = useState<AlertState>({});

  const dsoInfosQuery = useDsoInfos();
  const voteRequestQuery = useListDsoRulesVoteRequests();

  const expirationFromVoteRequestTimeout = dayjs().add(
    Math.floor(
      parseInt(dsoInfosQuery.data?.dsoRules.payload.config.voteRequestTimeout.microseconds!) / 1000
    ),
    'milliseconds'
  );

  const [isValidSynchronizerPauseTime, setIsValidSynchronizerPauseTime] = useState<boolean>(true);

  useEffect(() => {
    setExpiration(expirationFromVoteRequestTimeout);
    setEffectivity(expirationFromVoteRequestTimeout.add(1, 'day'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dsoInfosQuery.isInitialLoading]);

  const handleExpirationDateChange = (newDate: Dayjs | null) => {
    setExpiration(newDate ?? dayjs());
  };

  const handleEffectivityDateChange = (newDate: Dayjs | null) => {
    setEffectivity(newDate ?? dayjs());
  };

  const handleActionNameChange = (newActionName: string) => {
    setExpiration(expirationFromVoteRequestTimeout);
    setIsEffective(true);
    setEffectivity(expirationFromVoteRequestTimeout.add(1, 'day'));
    setUrl('');
    setSummary('');
    setActionName(newActionName);
  };

  const actionNameOptions1 = [
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

  const actionNameOptions2 = [
    { name: 'Offboard Member', value: 'SRARC_OffboardSv' },
    { name: 'Feature Application', value: 'SRARC_GrantFeaturedAppRight' },
    { name: 'Unfeature Application', value: 'SRARC_RevokeFeaturedAppRight' },
    { name: 'Set Dso Rules Configuration', value: 'SRARC_SetConfig' },
    { name: 'Set Amulet Rules Configuration', value: 'CRARC_SetConfig' },
    { name: 'Update SV Reward Weight', value: 'SRARC_UpdateSvRewardWeight' },
  ];

  const actionNameOptions = supportsVoteEffectivityAndSetConfig
    ? actionNameOptions2
    : actionNameOptions1;

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
      voteRequestQuery.data!,
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
        return await createVoteRequest(
          requester,
          action,
          url,
          summary,
          duration,
          supportsVoteEffectivityAndSetConfig && isEffective ? effectivity?.toDate() : undefined
        )
          .then(() => setUrl(''))
          .then(() => setSummary(''))
          .then(() => setActionName('SRARC_OffboardSv'))
          .then(() => setAction(undefined))
          .then(() => setExpiration(expirationFromVoteRequestTimeout))
          .then(() => setEffectivity(effectivity))
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

  const expirationInDays = dayjs(expiresAt).diff(dayjs(), 'day');

  const handleConfirmationAccept = () => {
    createVoteRequestMutation.mutate();
    setConfirmDialogOpen(false);
  };

  const conflicts = hasConflictingFields(action, voteRequestQuery.data);

  useEffect(() => {
    if (conflicts.hasConflict) {
      setDisableProceed(true);
    } else {
      setDisableProceed(false);
    }
  }, [conflicts]);

  // @ts-ignore
  const conditions: { disabled: boolean; reason: string; severity?: AlertColor }[] = [
    { disabled: createVoteRequestMutation.isLoading, reason: 'Loading...' },
    {
      disabled: !action || actionFromFormIsError(action),
      reason: !action
        ? 'No action'
        : `Action is not valid: ${
            actionFromFormIsError(action) && JSON.stringify(action.formError)
          }`,
    },
    { disabled: summary === '', reason: 'No summary', severity: 'warning' },
    { disabled: !isValidVoteRequestUrl(url), reason: 'Invalid URL', severity: 'warning' },
    {
      disabled: !isValidSynchronizerPauseTime,
      reason: 'Synchronizer upgrade time is before the expiry/effective date',
      severity: 'warning',
    },
  ].concat(
    supportsVoteEffectivityAndSetConfig
      ? [
          {
            disabled: isEffective && expiration.isAfter(effectivity),
            reason: 'Expiration must be set before effectivity.',
            severity: 'warning',
          },
          {
            disabled: conflicts.hasConflict,
            reason: `A Vote Request aiming to change similar fields already exists. You are therefore not allowed to modify the fields: ${conflicts.intersection}`,
            severity: 'warning',
          },
        ]
      : []
  );

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
                onChange={e => handleActionNameChange(e.target.value)}
              >
                {actionNameOptions.map((actionName, index) => (
                  <option key={'action-option-' + index} value={actionName.value}>
                    {actionName.name}
                  </option>
                ))}
              </NativeSelect>
            </FormControl>
          </Stack>
          {supportsVoteEffectivityAndSetConfig ? (
            <Stack>
              <Stack direction="column" mb={4} spacing={1}>
                <Typography variant="h6" mt={4}>
                  Vote Request Expires At
                </Typography>
                <DesktopDateTimePicker
                  label={`Enter time in local timezone (${getUTCWithOffset()})`}
                  value={expiration}
                  ampm={false}
                  format="YYYY-MM-DD HH:mm"
                  minDateTime={dayjs()}
                  maxDateTime={effectivity}
                  readOnly={false}
                  onChange={d => handleExpirationDateChange(d)}
                  slotProps={{
                    textField: {
                      id: 'datetime-picker-vote-request-expiration',
                      inputProps: {
                        'data-testid': 'datetime-picker-vote-request-expiration',
                      },
                    },
                  }}
                  closeOnSelect
                />
                <Typography variant="body2" mt={1}>
                  Expires{' '}
                  <DateWithDurationDisplay
                    datetime={expiration?.toDate()}
                    enableDuration
                    onlyDuration
                  />
                </Typography>
              </Stack>

              <Stack direction="row" mb={1}>
                <Checkbox
                  sx={{ pl: 0 }}
                  checked={!isEffective}
                  onChange={e => setIsEffective(!e.target.checked)}
                  id={'checkbox-set-effective-at-threshold'}
                  data-testid="checkbox-set-effective-at-threshold"
                />
                <Typography variant="h6" mt={1}>
                  Effective at threshold
                </Typography>
              </Stack>

              {isEffective && (
                <Stack direction="column" mb={4} spacing={1}>
                  <Typography variant="h6" mt={4}>
                    Vote Request Effective At
                  </Typography>
                  <DesktopDateTimePicker
                    label={`Enter time in local timezone (${getUTCWithOffset()})`}
                    value={effectivity}
                    minDateTime={dayjs()}
                    ampm={false}
                    format="YYYY-MM-DD HH:mm"
                    readOnly={false}
                    onChange={d => handleEffectivityDateChange(d)}
                    slotProps={{
                      textField: {
                        id: 'datetime-picker-vote-request-effectivity',
                        inputProps: {
                          'data-testid': 'datetime-picker-vote-request-effectivity',
                        },
                      },
                    }}
                    closeOnSelect
                  />
                  <Typography variant="body2" mt={1}>
                    Effective{' '}
                    <DateWithDurationDisplay
                      datetime={effectivity?.toDate()}
                      enableDuration
                      onlyDuration
                    />
                  </Typography>
                </Stack>
              )}
            </Stack>
          ) : (
            <Stack direction="column" mb={4} spacing={1}>
              <Typography variant="h6" mt={4}>
                Vote Request Expires At
              </Typography>
              <DesktopDateTimePicker
                label={`Enter time in local timezone (${getUTCWithOffset()})`}
                value={expiration}
                ampm={false}
                format="YYYY-MM-DD HH:mm"
                minDateTime={dayjs()}
                maxDateTime={maxDateTimeIfAddFutureAmuletConfigSchedule}
                readOnly={false}
                onChange={d => handleExpirationDateChange(d)}
                slotProps={{
                  textField: {
                    id: 'datetime-picker-vote-request-expiration',
                  },
                }}
                closeOnSelect
              />
              <Typography variant="body2" mt={1}>
                Expires{' '}
                <DateWithDurationDisplay
                  datetime={expiration?.toDate()}
                  enableDuration
                  onlyDuration
                />
              </Typography>
            </Stack>
          )}
          {actionName === 'SRARC_OffboardSv' && <OffboardSv chooseAction={chooseAction} />}
          {actionName === 'SRARC_GrantFeaturedAppRight' && (
            <GrantFeaturedAppRight chooseAction={chooseAction} />
          )}
          {actionName === 'SRARC_RevokeFeaturedAppRight' && (
            <RevokeFeaturedAppRight chooseAction={chooseAction} />
          )}
          {actionName === 'SRARC_SetConfig' && (
            <SetDsoRulesConfig
              supportsVoteEffectivityAndSetConfig={supportsVoteEffectivityAndSetConfig}
              chooseAction={chooseAction}
              setIsValidSynchronizerPauseTime={setIsValidSynchronizerPauseTime}
              expiration={expiration}
              effectivity={effectivity}
              isEffective={isEffective}
            />
          )}
          {actionName === 'CRARC_SetConfig' && <SetAmuletRulesConfig chooseAction={chooseAction} />}
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
              inputProps={{ 'data-testid': 'create-reason-summary' }}
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
                  autoComplete="off"
                  error={!isValidUrl(url)}
                  id="create-reason-url"
                  inputProps={{ 'data-testid': 'create-reason-url' }}
                  onChange={e => setUrl(e.target.value)}
                  value={url}
                />
              </FormControl>
              <Button size={'small'} onClick={() => window.open(url, '_blank')}>
                Open
              </Button>
            </Box>
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
                expiresAt={new Date(expiresAt!)}
                effectiveAt={
                  // TODO(#16139): get rid of logic to set expiration date for request that don't define effectivity
                  supportsVoteEffectivityAndSetConfig ? effectivity?.toDate() : new Date(expiresAt!)
                }
                expirationInDays={expirationInDays}
                confirmationDialogProps={{
                  showDialog: confirmDialogOpen,
                  onAccept: handleConfirmationAccept,
                  onClose: () => setConfirmDialogOpen(false),
                  title: 'Confirm Your Vote Request',
                  attributePrefix: 'vote',
                  children: null,
                  disableProceed: disableProceed,
                }}
              />
            </Stack>
          )}
          <Alerting alertState={alertMessage} />

          <Stack direction="column" mb={4} spacing={1}>
            <DisableConditionally conditions={conditions}>
              <Button
                id="create-voterequest-submit-button"
                data-testid="create-voterequest-submit-button"
                fullWidth
                type={'submit'}
                size="large"
                onClick={() => {
                  setConfirmDialogOpen(true);
                }}
              >
                Send Request to Super Validators
              </Button>
            </DisableConditionally>
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  );
};

const VoteRequestWithContexts: React.FC<{ supportsVoteEffectivityAndSetConfig: boolean }> = ({
  supportsVoteEffectivityAndSetConfig,
}) => {
  const config = useSvConfig();

  return (
    <SvClientProvider url={config.services.sv.url}>
      <CreateVoteRequest
        supportsVoteEffectivityAndSetConfig={supportsVoteEffectivityAndSetConfig}
      />
      <SvListVoteRequests
        supportsVoteEffectivityAndSetConfig={supportsVoteEffectivityAndSetConfig}
      />
    </SvClientProvider>
  );
};

export default VoteRequestWithContexts;
