// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { AmountDisplay } from '@lfdecentralizedtrust/splice-common-frontend';
import BigNumber from 'bignumber.js';

import { Card, CardContent, Typography } from '@mui/material';

const NetworkTotal: React.FC<{
  title: string;
  amount: BigNumber;
  idCC: string;
  idUSD: string;
  amuletPrice: BigNumber;
}> = ({ title, amount, idCC, idUSD, amuletPrice }) => {
  return (
    <Card>
      <CardContent>
        <Typography variant="h5" textTransform="uppercase" fontWeight="700" mb={2}>
          {title}
        </Typography>
        <Typography variant="h1" mb={1} id={idCC} data-testid="amulet-circulating-supply">
          <AmountDisplay amount={amount} currency="AmuletUnit" />
        </Typography>
        <Typography variant="h4" id={idUSD} data-testid="usd-circulating-supply">
          <AmountDisplay
            amount={amount}
            currency="AmuletUnit"
            convert="CCtoUSD"
            amuletPrice={amuletPrice}
          />
        </Typography>
      </CardContent>
    </Card>
  );
};

export default NetworkTotal;
