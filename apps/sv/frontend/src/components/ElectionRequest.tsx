// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  DisableConditionally,
  Loading,
  SvClientProvider,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useMutation } from '@tanstack/react-query';
import React, { useEffect, useState } from 'react';

import { Button, Stack, Table, TableBody, Typography } from '@mui/material';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';

import { ElectionRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';
import { useElectionContext, useDsoInfos } from '../contexts/SvContext';
import { useSvConfig } from '../utils';
import { Alerting, AlertState } from '../utils/Alerting';
import RankingForm, { User } from '../utils/RankingForm';

interface ListRankingsProps {
  electionRequest: Contract<ElectionRequest>[];
}

const ListRankings: React.FC<ListRankingsProps> = ({ electionRequest }) => {
  return (
    <Table>
      <TableBody>
        {electionRequest.map((e, key) => (
          <TableRow key={key}>
            <TableCell id={'requester-table-row'}>{e.payload.requester.split('::')[0]}</TableCell>
            <TableCell id={'ranking-table-row'}>
              {e.payload.ranking.map((e, index) => `${index + 1}: ${e.split('::')[0]}`).join(', ')}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
};

const ElectionRequests: React.FC = () => {
  const [ranking, setRanking] = useState<User[]>();
  const dsoInfosQuery = useDsoInfos();
  const { createElectionRequest } = useSvAdminClient();
  const [alertState, setAlertState] = useState<AlertState>({});

  const electionContextQuery = useElectionContext();

  const cs: User[] = [];
  dsoInfosQuery.data?.dsoRules.payload.svs.forEach((_v, a) =>
    cs.push({ id: cs.length + 1, name: a })
  );

  useEffect(() => {
    setRanking(cs);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dsoInfosQuery.isSuccess]);

  const createElectionRequestMutation = useMutation({
    mutationFn: async () => {
      const requester = dsoInfosQuery.data?.svPartyId!;
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
      console.error(`Failed to send vote request to dso`, error);
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
        Current Configuration
      </Typography>
      <Table>
        <TableBody>
          <TableRow>
            <TableCell>Epoch:</TableCell>
            <TableCell id={'delegate-election-epoch'}>
              {dsoInfosQuery.data?.dsoRules.payload.epoch}
            </TableCell>
          </TableRow>
          <TableRow>
            <TableCell>Delegate:</TableCell>
            <TableCell id={'delegate-election-current-delegate'}>
              {dsoInfosQuery.data?.dsoRules.payload.dsoDelegate}
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
      <Typography mt={4} variant="h4">
        Configuration for Epoch {parseInt(dsoInfosQuery.data?.dsoRules.payload.epoch!) + 1}
      </Typography>
      {electionContextQuery.data.ranking.length > 0 && (
        <Typography mt={4} variant="h5">
          In-flight Election Requests:
        </Typography>
      )}
      {electionContextQuery.data.ranking && (
        <ListRankings electionRequest={electionContextQuery.data!.ranking} />
      )}
      <Typography mt={4} variant="h5">
        Your Ranking:
      </Typography>
      <RankingForm users={ranking} updateRanking={setRanking} />
      <Alerting alertState={alertState} />
      <DisableConditionally
        conditions={[
          { disabled: createElectionRequestMutation.isLoading, reason: 'Submitting...' },
          {
            disabled:
              electionContextQuery.data!.ranking.find(
                e => e.payload.requester === dsoInfosQuery.data?.svPartyId!
              ) !== undefined,
            reason: `Party ${dsoInfosQuery.data?.svPartyId} has already submitted`,
          },
        ]}
      >
        <Button
          id={'submit-ranking-delegate-election'}
          type={'submit'}
          size="large"
          onClick={() => {
            createElectionRequestMutation.mutate();
          }}
        >
          Submit Ranking
        </Button>
      </DisableConditionally>
    </Stack>
  );
};

const ElectionRequestWithContexts: React.FC = () => {
  const config = useSvConfig();
  return (
    <SvClientProvider url={config.services.sv.url}>
      <ElectionRequests />
    </SvClientProvider>
  );
};

export default ElectionRequestWithContexts;
