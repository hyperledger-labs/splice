import { useMutation } from '@tanstack/react-query';
import { Loading, SvClientProvider } from 'common-frontend';
import React, { useEffect, useState } from 'react';

import { Button, Stack, Table, TableBody, Typography } from '@mui/material';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';
import { useElectionContext, useSvcInfos } from '../contexts/SvContext';
import { config } from '../utils';
import { Alerting, AlertState } from '../utils/Alerting';
import RankingForm, { User } from '../utils/RankingForm';

const ElectionRequest: React.FC = () => {
  const [ranking, setRanking] = useState<User[]>();
  const svcInfosQuery = useSvcInfos();
  const { createElectionRequest } = useSvAdminClient();
  const [alertState, setAlertState] = useState<AlertState>({});

  const electionContextQuery = useElectionContext();

  const cs: User[] = [];
  svcInfosQuery.data?.svcRules.payload.members.forEach((_v, a) =>
    cs.push({ id: cs.length + 1, name: a })
  );

  useEffect(() => {
    setRanking(cs);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [svcInfosQuery.isSuccess]);

  const createElectionRequestMutation = useMutation({
    mutationFn: async () => {
      const requester = svcInfosQuery.data?.svPartyId!;
      const userList = ranking?.map(v => v.name)!;
      return await createElectionRequest(requester, userList);
    },

    onSuccess: () => {
      setAlertState({ severity: 'success', message: 'Successfully updated the ranking.' });
      setTimeout(() => {
        setAlertState({
          severity: 'info',
          message: 'You already submitted a ranking for this epoch.',
        });
      }, 5000);
    },

    onError: error => {
      console.error(`Failed to send vote request to svc`, error);
      setAlertState({ severity: 'error', message: 'Fail to update ranking.' });
      setTimeout(() => {
        setAlertState({});
      }, 5000);
    },
  });

  if (!ranking || !electionContextQuery?.data) {
    return <Loading />;
  }

  // TODO (#4966): add a popup to ask confirmation
  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={4} variant="h4">
        Ranking of your Leader Preferences for next epoch
      </Typography>
      <Table>
        <TableBody>
          <TableRow>
            <TableCell>Current Epoch:</TableCell>
            <TableCell id={'leader-election-epoch'}>
              {svcInfosQuery.data?.svcRules.payload.epoch}
            </TableCell>
          </TableRow>
          <TableRow>
            <TableCell>Current Leader:</TableCell>
            <TableCell id={'leader-election-current-leader'}>
              {svcInfosQuery.data?.svcRules.payload.leader}
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
      <RankingForm users={ranking} updateRanking={setRanking} />
      <Alerting alertState={alertState} />
      <Button
        id={'submit-ranking-leader-election'}
        fullWidth
        type={'submit'}
        size="large"
        onClick={() => {
          createElectionRequestMutation.mutate();
        }}
        disabled={electionContextQuery.data?.exists}
      >
        Submit Ranking
      </Button>
    </Stack>
  );
};

const ElectionRequestWithContexts: React.FC = () => {
  // TODO(#7134) List in-flight election requests
  return (
    <SvClientProvider url={config.services.sv.url}>
      <ElectionRequest />
    </SvClientProvider>
  );
};

export default ElectionRequestWithContexts;
