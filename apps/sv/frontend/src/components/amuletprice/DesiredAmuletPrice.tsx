// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AmountDisplay,
  DateDisplay,
  Loading,
  PartyId,
  AmuletPriceVote,
  DsoInfo,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { useMutation, UseQueryResult, UseMutationResult } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import React, { useCallback, useState } from 'react';

import EditIcon from '@mui/icons-material/Edit';
import {
  IconButton,
  Stack,
  Table,
  TableContainer,
  TableHead,
  TableBody,
  TableCell,
  TableRow,
  Typography,
  TextField,
  Button,
} from '@mui/material';

import { Numeric, Optional, Party } from '@daml/types';

import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { useDsoInfos } from '../../contexts/SvContext';
import { useAmuletPriceVotes } from '../../hooks/useAmuletPriceVotes';
import { useSvConfig } from '../../utils';

interface DesiredAmuletPriceProps {
  canEditVote: boolean;
}

const maybeBigNumber = (maybeNumeric: Optional<Numeric>) => {
  return maybeNumeric !== null ? new BigNumber(maybeNumeric) : undefined;
};

const DesiredAmuletPrice: React.FC<DesiredAmuletPriceProps> = ({ canEditVote }) => {
  const config = useSvConfig();
  const amuletPriceVotesQuery = useAmuletPriceVotes();
  const dsoInfosQuery = useDsoInfos();
  const { updateDesiredAmuletPrice } = useSvAdminClient();

  const [isReadyToEdit, setIsReadyToEdit] = useState<boolean>(false);
  const [curPriceText, setCurPriceText] = useState<string>('0');
  const isInvalidPrice = BigNumber(curPriceText).lte(0.0);

  const updateDesiredAmuletPriceMutation = useMutation({
    mutationFn: () => {
      return updateDesiredAmuletPrice(BigNumber(curPriceText));
    },
    onSettled: async () => {
      setIsReadyToEdit(false);
    },
  });

  const getMemberName = useCallback(
    (partyId: string) => {
      const member = dsoInfosQuery.data?.dsoRules.payload.svs.get(partyId);
      return member ? member.name : '';
    },
    [dsoInfosQuery.data]
  );

  if (amuletPriceVotesQuery.isLoading || dsoInfosQuery.isLoading) {
    return <Loading />;
  }

  if (amuletPriceVotesQuery.isError || dsoInfosQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const svPartyId = dsoInfosQuery.data!.svPartyId;

  const filteredVotes = canEditVote
    ? amuletPriceVotesQuery.data.filter(v => v.sv !== svPartyId)
    : amuletPriceVotesQuery.data;
  const otherAmuletPriceVotes = filteredVotes.sort((a, b) => {
    return b.lastUpdatedAt.valueOf() - a.lastUpdatedAt.valueOf();
  });

  const amuletName = config.spliceInstanceNames.amuletName;

  return (
    <>
      {canEditVote && (
        <EditDesiredPriceSection
          svPartyId={svPartyId}
          amuletName={amuletName}
          isReadyToEdit={isReadyToEdit}
          curPriceText={curPriceText}
          isInvalidPrice={isInvalidPrice}
          setIsReadyToEdit={setIsReadyToEdit}
          setCurPriceText={setCurPriceText}
          dsoInfosQuery={dsoInfosQuery}
          updateDesiredAmuletPriceMutation={updateDesiredAmuletPriceMutation}
        />
      )}

      <Typography mt={6} variant="h4">
        Desired {amuletName} Prices of Other Super Validators
      </Typography>
      <TableContainer>
        <Table style={{ tableLayout: 'fixed' }} className="sv-amulet-price-table">
          <TableHead>
            <TableRow>
              <TableCell>Super Validator</TableCell>
              <TableCell>Super Validator Party ID</TableCell>
              <TableCell>Desired {amuletName} Price</TableCell>
              <TableCell>Last Updated At</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {otherAmuletPriceVotes.map(amuletPriceVote => {
              return (
                <OtherAmuletPricesRow
                  key={amuletPriceVote.sv}
                  sv={amuletPriceVote.sv}
                  svName={getMemberName(amuletPriceVote.sv)}
                  amuletPrice={maybeBigNumber(amuletPriceVote.amuletPrice)!}
                  lastUpdatedAt={amuletPriceVote.lastUpdatedAt}
                />
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </>
  );
};

interface EditDesiredPriceSectionProps {
  svPartyId: string;
  amuletName: string;
  isReadyToEdit: boolean;
  curPriceText: string;
  isInvalidPrice: boolean;
  setIsReadyToEdit: (isReadyToEdit: boolean) => void;
  setCurPriceText: (curPriceText: string) => void;
  dsoInfosQuery: UseQueryResult<DsoInfo, unknown>;
  updateDesiredAmuletPriceMutation: UseMutationResult<void, unknown, void, unknown>;
}

const EditDesiredPriceSection: React.FC<EditDesiredPriceSectionProps> = ({
  svPartyId,
  amuletName,
  isReadyToEdit,
  curPriceText,
  isInvalidPrice,
  setIsReadyToEdit,
  setCurPriceText,
  dsoInfosQuery,
  updateDesiredAmuletPriceMutation,
}) => {
  const amuletPriceVotesQuery = useAmuletPriceVotes();

  if (amuletPriceVotesQuery.isLoading || dsoInfosQuery.isLoading) {
    return <Loading />;
  }

  if (amuletPriceVotesQuery.isError || dsoInfosQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const curSvAmuletPriceVote: AmuletPriceVote | undefined = amuletPriceVotesQuery.data.find(
    v => v.sv === svPartyId
  );

  return (
    <>
      <Typography mt={6} variant="h4">
        {`Your Desired ${amuletName} Price`}
      </Typography>
      {isReadyToEdit ? (
        updateDesiredAmuletPriceMutation.isLoading ? (
          <Loading />
        ) : (
          <Stack direction="row">
            <TextField
              error={isInvalidPrice}
              label="Amount"
              onChange={event => setCurPriceText(event.target.value)}
              value={curPriceText}
              type="text"
              id="desired-amulet-price-field"
            />
            <Button
              variant="contained"
              disabled={isInvalidPrice}
              onClick={() => updateDesiredAmuletPriceMutation.mutate()}
              id="update-amulet-price-button"
            >
              Update
            </Button>
            <Button
              variant="contained"
              color="warning"
              disabled={false}
              onClick={() => setIsReadyToEdit(false)}
              id="cancel-amulet-price-button"
            >
              Cancel
            </Button>
          </Stack>
        )
      ) : (
        <Typography id="cur-sv-amulet-price-usd" variant="h6">
          {curSvAmuletPriceVote?.amuletPrice ? (
            <AmountDisplay
              amount={maybeBigNumber(curSvAmuletPriceVote.amuletPrice)!}
              currency="USDUnit"
            />
          ) : (
            'Not Set'
          )}
          <IconButton
            onClick={() => {
              // set initial value for editing
              setCurPriceText(
                curSvAmuletPriceVote?.amuletPrice
                  ? curSvAmuletPriceVote.amuletPrice!
                  : // TODO(M3-73) If desired price is not yet set in this SV
                    // Current median value would be a better choice than 0.0 as a initial value for editing.
                    '0'
              );
              setIsReadyToEdit(true);
            }}
          >
            <EditIcon id="edit-amulet-price-button" fontSize={'small'} />
          </IconButton>
        </Typography>
      )}
    </>
  );
};

interface OtherAmuletPricesRowProps {
  svName: string;
  sv: Party;
  amuletPrice: BigNumber | undefined;
  lastUpdatedAt: Date;
}

const OtherAmuletPricesRow: React.FC<OtherAmuletPricesRowProps> = ({
  svName,
  sv,
  amuletPrice,
  lastUpdatedAt,
}) => {
  return (
    <TableRow className="amulet-price-table-row">
      <TableCell>{svName}</TableCell>
      <TableCell>
        <PartyId partyId={sv} className="sv-party" />
      </TableCell>
      <TableCell className="amulet-price">
        {amuletPrice ? <AmountDisplay amount={amuletPrice} currency="USDUnit" /> : 'Not Set'}
      </TableCell>
      <TableCell>
        <DateDisplay datetime={lastUpdatedAt.toISOString()} />
      </TableCell>
    </TableRow>
  );
};

export default DesiredAmuletPrice;
