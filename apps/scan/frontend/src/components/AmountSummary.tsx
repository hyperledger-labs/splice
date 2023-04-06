import BigNumber from 'bignumber.js';
import { AmountDisplay } from 'common-frontend';

import { Card, CardContent, Typography } from '@mui/material';

const NetworkTotal: React.FC<{ title: string; amount: BigNumber }> = ({ title, amount }) => {
  return (
    <Card>
      <CardContent>
        <Typography variant="h5" textTransform="uppercase" fontWeight="700" mb={2}>
          {title}
        </Typography>
        <Typography variant="h1" mb={1}>
          <AmountDisplay amount={amount} currency="CC" />
        </Typography>
        <Typography variant="h4">
          <AmountDisplay amount={amount} currency="USD" />
        </Typography>
      </CardContent>
    </Card>
  );
};

export default NetworkTotal;
