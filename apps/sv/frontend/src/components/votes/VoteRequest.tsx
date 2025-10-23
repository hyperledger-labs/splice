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
import { LocalizationProvider } from '@mui/x-date-pickers';
import { AdapterDayjs } from '@mui/x-date-pickers/AdapterDayjs';

import {
  AlertColor,
  Box,
  Button,
  Card,
  CardContent,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
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
import { isValidUrl } from '../../utils/validations';
import SvListVoteRequests from './SvListVoteRequests';
import CreateUnallocatedUnclaimedActivityRecord from './actions/CreateUnallocatedUnclaimedActivityRecord';
import GrantFeaturedAppRight from './actions/GrantFeaturedAppRight';
import OffboardSv from './actions/OffboardSv';
import RevokeFeaturedAppRight from './actions/RevokeFeaturedAppRight';
import SetAmuletRulesConfig from './actions/SetAmuletRulesConfig';
import SetDsoRulesConfig from './actions/SetDsoRulesConfig';
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
  // using this for the alert dialog. Should not be used for anything else
  const [nextActionName, setNextActionName] = useState('SRARC_OffboardSv');
  const [summary, setSummary] = useState<string>('');
  const [url, setUrl] = useState<string>('');
  const [isEffective, setIsEffective] = useState(true);
  const [effectivity, setEffectivity] = useState<Dayjs>(dayjs());
  const [expiration, setExpiration] = useState<Dayjs>(dayjs());
  const [confirmDialogOpen, setConfirmDialogOpen] = useState(false);
  const [disableProceed, setDisableProceed] = useState(false);

  const [alertMessage, setAlertMessage] = useState<AlertState>({});
  const [openActionChangeDialog, setOpenActionChangeDialog] = React.useState(false);

  const dsoInfosQuery = useDsoInfos();
  const voteRequestQuery = useListDsoRulesVoteRequests();

  const expirationFromVoteRequestTimeout = dayjs().add(
    Math.floor(
      parseInt(dsoInfosQuery.data?.dsoRules.payload.config.voteRequestTimeout.microseconds!) / 1000
    ),
    'milliseconds'
  );

  const [isValidSynchronizerPauseTime, setIsValidSynchronizerPauseTime] = useState<boolean>(true);
  const [isValidAmount, setIsValidAmount] = useState<boolean>(true);

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

  const resetForm = () => {
    setExpiration(expirationFromVoteRequestTimeout);
    setIsEffective(true);
    setEffectivity(expirationFromVoteRequestTimeout.add(1, 'day'));
    setUrl('');
    setSummary('');
    setActionName(nextActionName);
  };

  const actionNameOptions = [
    { name: 'Offboard Member', value: 'SRARC_OffboardSv' },
    { name: 'Feature Application', value: 'SRARC_GrantFeaturedAppRight' },
    { name: 'Unfeature Application', value: 'SRARC_RevokeFeaturedAppRight' },
    { name: 'Set Dso Rules Configuration', value: 'SRARC_SetConfig' },
    { name: 'Set Amulet Rules Configuration', value: 'CRARC_SetConfig' },
    { name: 'Update SV Reward Weight', value: 'SRARC_UpdateSvRewardWeight' },
    {
      name: 'Create Unclaimed Activity Record',
      value: 'SRARC_CreateUnallocatedUnclaimedActivityRecord',
    },
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
    },
    [setAction]
  );

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
        !actionFromFormIsError(action)
      ) {
        return await createVoteRequest(
          requester,
          action,
          url,
          summary,
          duration,
          isEffective ? effectivity?.toDate() : undefined
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
      // TODO (DACH-NY/canton-network-node#5491): show an error to the user.
      console.error(`Failed to send vote request to dso`, error);
    },
  });

  // used and valid only for dsoRules-based actions
  let expiresAt;
  try {
    expiresAt = expiration?.toISOString();
  } catch (error) {
    console.log('Failed to convert expiration to ISO string', error);
    expiresAt = undefined;
  }

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

  const submissionConditions: { disabled: boolean; reason: string; severity?: AlertColor }[] = [
    { disabled: createVoteRequestMutation.isPending, reason: 'Loading...' },
    { disabled: summary === '', reason: 'No summary', severity: 'warning' },
    { disabled: !isValidUrl(url), reason: 'Invalid URL', severity: 'warning' },
    {
      disabled: !isValidSynchronizerPauseTime,
      reason: 'Synchronizer upgrade time is before the expiry/effective date',
      severity: 'warning',
    },
    { disabled: summary === '', reason: 'No summary', severity: 'warning' as AlertColor },
    {
      disabled: !isValidUrl(url),
      reason: 'Invalid URL',
      severity: 'warning' as AlertColor,
    },
    {
      disabled: !isValidSynchronizerPauseTime,
      reason: 'Synchronizer upgrade time is before the expiry/effective date',
      severity: 'warning' as AlertColor,
    },
    {
      disabled: !isValidAmount,
      reason: 'Amount must be a positive number',
      severity: 'warning' as AlertColor,
    },
    // keep this as the last condition
    {
      disabled: !action || actionFromFormIsError(action),
      reason: !action
        ? 'No action'
        : `Action is not valid: ${
            actionFromFormIsError(action) && JSON.stringify(action.formError)
          }`,
    },
    {
      disabled: isEffective && expiration.isAfter(effectivity),
      reason: 'Expiration must be set before effectivity.',
      severity: 'warning' as AlertColor,
    },
    {
      disabled: conflicts.hasConflict,
      reason: `A Vote Request aiming to change similar fields already exists. You are therefore not allowed to modify the fields: ${conflicts.intersection}`,
      severity: 'warning' as AlertColor,
    },
  ];

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
                  // @ts-expect-error this is not part of mui but required for testing to work
                  'data-testid': 'display-actions',
                }}
                value={actionName}
                onChange={e => {
                  setNextActionName(e.target.value);
                  setOpenActionChangeDialog(true);
                }}
              >
                {actionNameOptions.map((actionName, index) => (
                  <option key={'action-option-' + index} value={actionName.value}>
                    {actionName.name}
                  </option>
                ))}
              </NativeSelect>
            </FormControl>
          </Stack>
          <Stack>
            <Stack direction="column" mb={4} spacing={1}>
              <Typography variant="h6" mt={4}>
                Vote Request Expires At
              </Typography>
              <LocalizationProvider dateAdapter={AdapterDayjs}>
                <DesktopDateTimePicker
                  label={`Enter time in local timezone (${getUTCWithOffset()})`}
                  value={expiration}
                  ampm={false}
                  format="YYYY-MM-DD HH:mm"
                  minDateTime={dayjs()}
                  maxDateTime={effectivity}
                  readOnly={false}
                  onChange={d => handleExpirationDateChange(d)}
                  className="datetime-picker-vote-request-expiration-root"
                  slotProps={{
                    textField: {
                      id: 'datetime-picker-vote-request-expiration',
                      inputProps: {
                        'data-testid': 'datetime-picker-vote-request-expiration',
                      },
                    },
                    openPickerButton: {
                      'data-testid': 'datetime-picker-vote-request-expiration-button',
                    } as Record<string, string>,
                  }}
                  closeOnSelect
                />
              </LocalizationProvider>
              <Typography variant="body2" mt={1}>
                Expires{' '}
                <DateWithDurationDisplay
                  id="vote-request-expiration-duration"
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
                <LocalizationProvider dateAdapter={AdapterDayjs}>
                  <DesktopDateTimePicker
                    label={`Enter time in local timezone (${getUTCWithOffset()})`}
                    value={effectivity}
                    minDateTime={dayjs()}
                    ampm={false}
                    format="YYYY-MM-DD HH:mm"
                    readOnly={false}
                    onChange={d => handleEffectivityDateChange(d)}
                    className="datetime-picker-vote-request-effectivity-root"
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
                </LocalizationProvider>
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
          {actionName === 'SRARC_OffboardSv' && <OffboardSv chooseAction={chooseAction} />}
          {actionName === 'SRARC_GrantFeaturedAppRight' && (
            <GrantFeaturedAppRight chooseAction={chooseAction} />
          )}
          {actionName === 'SRARC_RevokeFeaturedAppRight' && (
            <RevokeFeaturedAppRight chooseAction={chooseAction} />
          )}
          {actionName === 'SRARC_SetConfig' && (
            <SetDsoRulesConfig
              chooseAction={chooseAction}
              setIsValidSynchronizerPauseTime={setIsValidSynchronizerPauseTime}
              expiration={expiration}
              effectivity={effectivity}
              isEffective={isEffective}
            />
          )}
          {actionName === 'CRARC_SetConfig' && <SetAmuletRulesConfig chooseAction={chooseAction} />}
          {actionName === 'SRARC_UpdateSvRewardWeight' && (
            <UpdateSvRewardWeight chooseAction={chooseAction} action={action} />
          )}
          {actionName === 'SRARC_CreateUnallocatedUnclaimedActivityRecord' && (
            <CreateUnallocatedUnclaimedActivityRecord
              chooseAction={chooseAction}
              action={action}
              effectivity={effectivity}
              setIsValidAmount={setIsValidAmount}
              summary={summary}
            />
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
                effectiveAt={effectivity?.toDate()}
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
            <DisableConditionally conditions={submissionConditions}>
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

      <Dialog
        open={openActionChangeDialog}
        onClose={() => setOpenActionChangeDialog(false)}
        aria-labelledby="alert-dialog-title"
        aria-describedby="alert-dialog-description"
        data-testid="action-change-dialog"
      >
        <DialogTitle id="alert-dialog-title">Action Change</DialogTitle>
        <DialogContent>
          <DialogContentText id="alert-dialog-description">
            <strong>NOTE:</strong> All fields in this form will be reset to the default values for
            the new action
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button
            onClick={() => setOpenActionChangeDialog(false)}
            autoFocus
            data-testid="action-change-dialog-cancel"
          >
            Cancel
          </Button>
          <Button
            id="action-change-dialog-proceed"
            data-testid="action-change-dialog-proceed"
            onClick={() => {
              resetForm();
              setOpenActionChangeDialog(false);
            }}
          >
            Proceed
          </Button>
        </DialogActions>
      </Dialog>
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
