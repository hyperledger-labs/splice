import { PollingStrategy } from 'common-frontend';
import { useGetRoundOfLatestData } from 'common-frontend/scan-api';
import { useMemo } from 'react';

import { Stack, Typography } from '@mui/material';

const Header: React.FC = () => {
  const { data: latestRound, error } = useGetRoundOfLatestData(PollingStrategy.FIXED);

  const round = useMemo(() => {
    if (error) {
      if (
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (error as any).code === 404 &&
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (error as any).body.error === 'No data has been made available yet'
      ) {
        // Backend is working, but no round data is available
        return '??';
      } else {
        return '--';
      }
    }
    return latestRound?.round || '--';
  }, [latestRound, error]);

  return (
    <Stack direction="row" justifyContent="space-between" alignItems="center">
      <Typography
        variant="h1"
        textTransform="uppercase"
        fontFamily={theme => theme.fonts.monospace.fontFamily}
        fontWeight={theme => theme.fonts.monospace.fontWeight}
      >
        Canton Coin Scan
      </Typography>
      <Stack direction="row" alignItems="center">
        <div id="as-of-round">
          <Typography variant="body2">
            The content on this page is computed as of round: {round}
          </Typography>
        </div>
      </Stack>
    </Stack>
  );
};

export default Header;
