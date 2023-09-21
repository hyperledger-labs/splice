import { useMutation } from '@tanstack/react-query';
import { getUTCWithOffset, SvClientProvider } from 'common-frontend';
import { Dayjs } from 'dayjs';
import React, { useEffect, useState } from 'react';

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

import { RelTime } from '@daml.js/733e38d36a2759688a4b2c4cec69d48e7b55ecc8dedc8067b815926c917a182a/lib/DA/Time/Types';
import { ActionRequiringConfirmation } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { useSvcInfos } from '../../contexts/SvContext';
import { useListSvcRulesVoteRequests } from '../../hooks/useListVoteRequests';
import { config } from '../../utils';
import { Alerting, AlertState } from '../../utils/Alerting';
import {
  isExpirationBeforeEffectiveDate,
  isScheduleDateTimeValid,
  VoteRequestValidity,
} from '../../utils/validations';
import ListVoteRequests from './ListVoteRequests';
import AddFutureCoinConfigSchedule from './actions/AddFutureCoinConfigSchedule';
import GrantFeaturedAppRight from './actions/GrantFeaturedAppRight';
import RemoveFutureCoinConfigSchedule from './actions/RemoveFutureCoinConfigSchedule';
import RemoveMember from './actions/RemoveMember';
import RevokeFeaturedAppRight from './actions/RevokeFeaturedAppRight';
import SetCoinRulesEnabledChoices from './actions/SetCoinRulesEnabledChoices';
import SetSvcRulesConfig from './actions/SetSvcRulesConfig';
import UpdateFutureCoinConfigSchedule from './actions/UpdateFutureCoinConfigSchedule';

const dayjs = require('dayjs');
const utc = require('dayjs/plugin/utc');
dayjs.extend(utc);

const VoteRequest: React.FC = () => {
  // States related to vote requests
  const [actionName, setActionName] = useState('SRARC_RemoveMember');
  const [summary, setSummary] = useState<string>('');
  const [url, setUrl] = useState<string>('');
  const [expiration, setExpiration] = useState<Dayjs | null>(null);

  // States related to constraints from vote requests
  const [maxDateTimeIfAddFutureCoinConfigSchedule, setMaxDateTimeIfAddFutureCoinConfigSchedule] =
    useState<Dayjs | undefined>(undefined);
  const [alertMessage, setAlertMessage] = useState<AlertState>({});

  const svcInfosQuery = useSvcInfos();
  const listVoteRequestsQuery = useListSvcRulesVoteRequests();

  const defaultExpiration: Dayjs = dayjs().add(
    Math.floor(
      parseInt(svcInfosQuery.data?.svcRules.payload.config.voteRequestTimeout.microseconds!) / 1000
    ),
    'milliseconds'
  );

  useEffect(() => {
    setExpiration(defaultExpiration);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [svcInfosQuery.isSuccess]);

  useEffect(() => {
    setExpiration(defaultExpiration);
    setMaxDateTimeIfAddFutureCoinConfigSchedule(undefined);
    setUrl('');
    setSummary('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [actionName, setActionName]); // same than above

  const actionNameOptions = [
    { name: 'Remove Member', value: 'SRARC_RemoveMember' },
    { name: 'Feature Application', value: 'SRARC_GrantFeaturedAppRight' },
    { name: 'Unfeature Application', value: 'SRARC_RevokeFeaturedAppRight' },
    { name: 'Set SvcRules Configuration', value: 'SRARC_SetConfig' },
    { name: 'Set CoinRules Enabled Choices', value: 'CRARC_SetEnabledChoices' },
    { name: 'Add Coin Configuration Schedule', value: 'CRARC_AddFutureCoinConfigSchedule' },
    { name: 'Remove Coin Configuration Schedule', value: 'CRARC_RemoveFutureCoinConfigSchedule' },
    { name: 'Update Coin Configuration Schedule', value: 'CRARC_UpdateFutureCoinConfigSchedule' },
  ];

  const [action, setAction] = useState<ActionRequiringConfirmation | undefined>(undefined);

  function max(time1: Dayjs, time2: Dayjs): Dayjs {
    return time1 > time2 ? time1 : time2;
  }

  const chooseAction = (action: ActionRequiringConfirmation) => {
    setAction(action);
    if (action.tag === 'ARC_CoinRules') {
      switch (action.value.coinRulesAction.tag) {
        case 'CRARC_AddFutureCoinConfigSchedule': {
          setMaxDateTimeIfAddFutureCoinConfigSchedule(
            max(dayjs(), dayjs(action.value.coinRulesAction.value.newScheduleItem._1))
          );
          return;
        }
        case 'CRARC_UpdateFutureCoinConfigSchedule': {
          setMaxDateTimeIfAddFutureCoinConfigSchedule(
            max(dayjs(), dayjs(action.value.coinRulesAction.value.scheduleItem._1))
          );
          return;
        }
        case 'CRARC_RemoveFutureCoinConfigSchedule': {
          setMaxDateTimeIfAddFutureCoinConfigSchedule(
            max(dayjs(), dayjs(action.value.coinRulesAction.value.scheduleTime))
          );
          return;
        }
      }
    }
  };

  function validateAction(action: ActionRequiringConfirmation) {
    if (action?.tag !== 'ARC_CoinRules') {
      setAlertMessage({});
      return true;
    }

    const coinRulesAction = action.value.coinRulesAction;
    let effectiveDate: string;

    switch (coinRulesAction.tag) {
      case 'CRARC_AddFutureCoinConfigSchedule':
        effectiveDate = coinRulesAction.value.newScheduleItem._1;
        break;
      case 'CRARC_UpdateFutureCoinConfigSchedule':
        effectiveDate = coinRulesAction.value.scheduleItem._1;
        break;
      case 'CRARC_RemoveFutureCoinConfigSchedule':
        effectiveDate = coinRulesAction.value.scheduleTime;
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
      const requester = svcInfosQuery.data?.svPartyId!;

      const duration: RelTime = {
        microseconds: BigInt(expiration!.diff(dayjs(), 'milliseconds') * 1000).toString(),
      };

      if (actionNameOptions.map(e => e.value).includes(actionName) && validateAction(action!)) {
        return await createVoteRequest(requester, action!, url, summary, duration)
          .then(() => setUrl(''))
          .then(() => setSummary(''))
          .then(() => setActionName('SRARC_RemoveMember'))
          .then(() => setAction(undefined))
          .then(() => setExpiration(defaultExpiration))
          .then(() => setMaxDateTimeIfAddFutureCoinConfigSchedule(undefined))
          .then(() => setAlertMessage({}));
      }
    },

    onError: error => {
      // TODO (#5491): show an error to the user.
      console.error(`Failed to send vote request to svc`, error);
    },
  });

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
                inputProps={{ id: 'display-actions' }}
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
          {actionName === 'SRARC_RemoveMember' && <RemoveMember chooseAction={chooseAction} />}
          {actionName === 'SRARC_GrantFeaturedAppRight' && (
            <GrantFeaturedAppRight chooseAction={chooseAction} />
          )}
          {actionName === 'SRARC_RevokeFeaturedAppRight' && (
            <RevokeFeaturedAppRight chooseAction={chooseAction} />
          )}
          {actionName === 'SRARC_SetConfig' && <SetSvcRulesConfig chooseAction={chooseAction} />}
          {actionName === 'CRARC_SetEnabledChoices' && (
            <SetCoinRulesEnabledChoices chooseAction={chooseAction} />
          )}
          {actionName === 'CRARC_AddFutureCoinConfigSchedule' && (
            <AddFutureCoinConfigSchedule chooseAction={chooseAction} />
          )}
          {actionName === 'CRARC_RemoveFutureCoinConfigSchedule' && (
            <RemoveFutureCoinConfigSchedule chooseAction={chooseAction} />
          )}
          {actionName === 'CRARC_UpdateFutureCoinConfigSchedule' && (
            <UpdateFutureCoinConfigSchedule chooseAction={chooseAction} />
          )}
          <Typography variant="h5">Reason</Typography>

          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">Summary</Typography>
            <TextField
              id="create-reason-summary"
              rows={2}
              multiline
              onChange={e => setSummary(e.target.value)}
              value={summary}
            />
          </Stack>

          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">URL:</Typography>
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
            <Typography variant="h5">Expires At</Typography>
            <DesktopDateTimePicker
              label={`Enter time in local timezone (${getUTCWithOffset()})`}
              value={expiration}
              minDateTime={dayjs()}
              maxDateTime={maxDateTimeIfAddFutureCoinConfigSchedule}
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
          <Alerting alertState={alertMessage} />
          <Button
            id="create-voterequest-submit-button"
            fullWidth
            type={'submit'}
            size="large"
            onClick={() => {
              createVoteRequestMutation.mutate();
            }}
            disabled={createVoteRequestMutation.isLoading || action === undefined}
          >
            Send request to collective
          </Button>
        </CardContent>
      </Card>
    </Stack>
  );
};

const VoteRequestWithContexts: React.FC = () => {
  return (
    <SvClientProvider url={config.services.sv.url}>
      <VoteRequest />
      <ListVoteRequests />
    </SvClientProvider>
  );
};

export default VoteRequestWithContexts;
