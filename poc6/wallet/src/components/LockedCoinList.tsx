// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React from "react";
import { Table } from "semantic-ui-react";
import { QueryResult } from "@daml/react";
import { LockedCoin } from "@daml.js/canton-coin/lib/CC/Coin";
import { OpenMiningRound } from "@daml.js/canton-coin/lib/CC/Round";
import { CreateEvent } from "@daml/ledger";
import prettyMilliseconds from "pretty-ms";
import { ccToUsd, CYCLE_FREQUENCY, getCurrentValue, getExpiresIn, shortenContractId, shortenParty } from "../Coin";

type Props = {
  lockedCoins: QueryResult<LockedCoin, undefined, typeof LockedCoin.templateId>;
  currentRound: OpenMiningRound | undefined;
};

type LockedCoinProps = {
  coin: CreateEvent<LockedCoin>,
  currentRound: OpenMiningRound,
}

const CoinRow: React.FC<LockedCoinProps> = ({ coin, currentRound }) => {
  const currentValue: number = getCurrentValue(currentRound, coin.payload.coin.quantity);
  const expiresIn: number = getExpiresIn(currentRound, coin.payload.coin.quantity);
  return (
    <Table.Row key={coin.contractId}>
      <Table.Cell>
        {shortenContractId(coin.contractId)}
      </Table.Cell>
      <Table.Cell textAlign="right">
        {coin.payload.coin.quantity.createdAt.number}
      </Table.Cell>
      <Table.Cell textAlign="right">
        {currentValue} CC
      </Table.Cell>
      <Table.Cell>
        (~ {ccToUsd(currentRound, currentValue)}$)
      </Table.Cell>
      <Table.Cell textAlign="right">
        {expiresIn} rounds
      </Table.Cell>
      <Table.Cell>
        (~ {prettyMilliseconds(expiresIn * CYCLE_FREQUENCY)})
      </Table.Cell>
      <Table.Cell>
        {coin.payload.lock.expiresAt}
      </Table.Cell>
      <Table.Cell>
        {shortenParty(coin.payload.lock.holder)}
      </Table.Cell>
    </Table.Row>
  );
};

const LockedCoinList: React.FC<Props> = ({
  lockedCoins,
  currentRound,
}) => {
  if (!currentRound || lockedCoins.loading) {
    return <div>Loading …</div>
  } else {
    return (
      <Table basic>
        <Table.Header>
          <Table.Row>
            <Table.HeaderCell>ID</Table.HeaderCell>
            <Table.HeaderCell>Created in</Table.HeaderCell>
            <Table.HeaderCell colSpan={2} textAlign="center">Amount</Table.HeaderCell>
            <Table.HeaderCell colSpan={2} textAlign="center">Expires in</Table.HeaderCell>
            <Table.HeaderCell>Locked until</Table.HeaderCell>
            <Table.HeaderCell>Lock holder</Table.HeaderCell>
          </Table.Row>
        </Table.Header>
        <Table.Body>
          {[...lockedCoins.contracts]
            .sort((x, y) => new Date(y.payload.lock.expiresAt).getTime() - new Date(x.payload.lock.expiresAt).getTime())
            .map(coin => (
              <CoinRow key={coin.contractId} coin={coin} currentRound={currentRound} />
            ))}
        </Table.Body>
      </Table>
    );
  }
};

export default LockedCoinList;

