import { Refresh } from '@mui/icons-material';
import { Button, Stack, Typography } from '@mui/material';

const Header: React.FC = () => {
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
        <Typography variant="body2">The content on this page is static.</Typography>
        <Button color="secondary" variant="text" startIcon={<Refresh />}>
          <Typography variant="body2">Refresh Page</Typography>
        </Button>
      </Stack>
    </Stack>
  );
};

export default Header;
