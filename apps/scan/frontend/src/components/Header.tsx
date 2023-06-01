import { useGetRoundOfLatestData } from 'common-frontend/scan-api';

import { Stack, Typography } from '@mui/material';

const Header: React.FC = () => {
  const { data: latestRound } = useGetRoundOfLatestData();

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
        <Typography variant="body2">
          The content on this page is computed as of round: {latestRound?.round || '--'}.
        </Typography>
      </Stack>
    </Stack>
  );
};

export default Header;
