// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React from "react";
import { Table } from "semantic-ui-react";
import { QueryResult } from "@daml/react";
import { Coin } from "@daml.js/canton-coin/lib/CC/Coin";
import { OpenMiningRound } from "@daml.js/canton-coin/lib/CC/Round";
import { CreateEvent } from "@daml/ledger";
import prettyMilliseconds from "pretty-ms";
import { ccToUsd, CYCLE_FREQUENCY, getCurrentValue, getExpiresIn, shortenContractId } from "../Coin";

type Props = {
  coins: QueryResult<Coin, undefined, typeof Coin.templateId>;
  currentRound: OpenMiningRound | undefined;
};



type CoinProps = {
  coin: CreateEvent<Coin>,
  currentRound: OpenMiningRound,
}

const CoinRow: React.FC<CoinProps> = ({ coin, currentRound }) => {
  const currentValue: number = getCurrentValue(currentRound, coin.payload.quantity);
  const expiresIn: number = getExpiresIn(currentRound, coin.payload.quantity);
  return (
    <Table.Row key={coin.contractId}>
      <Table.Cell>
        {shortenContractId(coin.contractId)}
      </Table.Cell>
      <Table.Cell textAlign="right">
        {coin.payload.quantity.createdAt.number}
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
    </Table.Row>
  );
};

const CoinList: React.FC<Props> = ({
  coins,
  currentRound,
}) => {
  if (!currentRound || coins.loading) {
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
          </Table.Row>
        </Table.Header>
        <Table.Body>
          {[...coins.contracts]
            .sort((x, y) => parseInt(y.payload.quantity.createdAt.number) - parseInt(x.payload.quantity.createdAt.number))
            .map(coin => (
              <CoinRow key={coin.contractId} coin={coin} currentRound={currentRound} />
            ))}
        </Table.Body>
      </Table>
    );
  }
};

export default CoinList;
