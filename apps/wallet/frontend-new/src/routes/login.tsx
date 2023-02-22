import * as React from 'react';
import { theme } from 'common-frontend';

import { Person } from '@mui/icons-material';
import { Button, Divider, InputAdornment, OutlinedInput, Stack, Typography } from '@mui/material';
import Container from '@mui/material/Container';

const Login: React.FC = () => {
  return (
    <Container maxWidth="xs">
      <Stack alignItems="center" paddingTop={16} spacing={4}>
        <Typography variant="h5">CANTON WALLET</Typography>
        <Button variant="pill" fullWidth size="large">
          Log In with OAuth2
        </Button>
        <Divider flexItem>OR</Divider>
        <UsernameInput />
        <Button variant="pill" fullWidth size="large">
          Log In
        </Button>
      </Stack>
    </Container>
  );
};

const UsernameInput: React.FC = () => {
  return (
    <OutlinedInput
      fullWidth
      placeholder="Username for test login"
      startAdornment={
        // https://stackoverflow.com/questions/69554151/how-to-change-material-ui-textfield-inputadornment-background-color
        <InputAdornment
          sx={{
            padding: theme.spacing(3.5, 2.5),
            backgroundColor: theme.palette.colors.neutral[20],
            borderRadius: '9999px 0 0 9999px',
          }}
          position="start"
        >
          <Person />
        </InputAdornment>
      }
      sx={{ borderRadius: '9999px', paddingLeft: 0 }}
    />
  );
};

export default Login;
