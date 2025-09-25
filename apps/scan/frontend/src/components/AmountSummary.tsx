// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { AmountDisplay, DateDisplay } from '@lfdecentralizedtrust/splice-common-frontend';
import BigNumber from 'bignumber.js';

import { Card, CardContent, Stack, Typography } from '@mui/material';

const NetworkTotal: React.FC<{
  title: string;
  amount: BigNumber;
  asOf?: Date;
  idCC: string;
  idUSD: string;
  amuletPrice: BigNumber;
}> = ({ title, amount, asOf, idCC, idUSD, amuletPrice }) => {
  return (
    <Card>
      <CardContent>
        <Stack direction="row" spacing={1}>
          <Typography variant="h5" textTransform="uppercase" fontWeight="700" mb={2}>
            {title}
          </Typography>
          {asOf ? (
            <Typography>
              as of <DateDisplay datetime={asOf} />
            </Typography>
          ) : null}
        </Stack>
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
