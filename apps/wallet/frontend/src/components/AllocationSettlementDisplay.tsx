// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Stack } from '@mui/material';
import Typography from '@mui/material/Typography';
import { DateWithDurationDisplay } from '@lfdecentralizedtrust/splice-common-frontend';
import BftAnsEntry from './BftAnsEntry';
import MetaDisplay from './MetaDisplay';
import { SettlementInfo } from '@daml.js/splice-api-token-allocation/lib/Splice/Api/Token/AllocationV1/module';

const AllocationSettlementDisplay: React.FC<{
  settlement: SettlementInfo;
}> = ({ settlement }) => {
  const {
    settleBefore,
    requestedAt,
    allocateBefore,
    settlementRef,
    executor,
    meta: settlementMeta,
  } = settlement;

  return (
    <>
      <Stack direction="row">
        <Stack width="100%">
          {settlementRef.id ? (
            <Stack maxWidth="md">
              <Typography className="allocation-request-id" variant="body2" noWrap>
                SettlementRef id: {settlementRef.id}
              </Typography>
            </Stack>
          ) : null}
          {settlementRef.cid ? (
            <Stack maxWidth="md">
              <Typography className="allocation-request-cid" variant="body2" noWrap>
                SettlementRef cid: {settlementRef.cid}
              </Typography>
            </Stack>
          ) : null}
        </Stack>
        {/*TODO (#1413): uncomment and implement callback*/}
        {/*<Button variant="pill" size="small" className="allocation-request-reject">*/}
        {/*  Reject*/}
        {/*</Button>*/}
      </Stack>
      <Stack direction="row" spacing={2}>
        <Stack direction="row" alignItems="center" spacing={1}>
          <Typography variant="body2">Executor:</Typography>
          <BftAnsEntry partyId={executor} className="allocation-executor" />
        </Stack>
        <Stack>
          <Typography variant="body2">
            Requested at: <DateWithDurationDisplay datetime={requestedAt} enableDuration />
          </Typography>
          <Typography variant="body2">
            Allocate before: <DateWithDurationDisplay datetime={allocateBefore} enableDuration />
          </Typography>
          <Typography variant="body2">
            Settle before: <DateWithDurationDisplay datetime={settleBefore} enableDuration />
          </Typography>
        </Stack>
      </Stack>
      {Object.keys(settlementMeta.values).length > 0 ? (
        <>
          <Typography variant="h5">Settlement Meta</Typography>
          <MetaDisplay meta={settlementMeta.values} />
        </>
      ) : null}
    </>
  );
};

export default AllocationSettlementDisplay;
