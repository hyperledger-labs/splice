import * as React from 'react';

import { Person } from '@mui/icons-material';
import { Divider, InputAdornment, OutlinedInput, Stack, Typography } from '@mui/material';
import Container from '@mui/material/Container';

import PillButton from '../components/PillButton';
import theme from '../theme';

const Login: React.FC = () => {
  return (
    <Container maxWidth="xs">
      <Stack alignItems="center" paddingTop={16} spacing={4}>
        <Typography variant="h5">CANTON WALLET</Typography>
        <PillButton fullWidth size="large">
          Log In with OAuth2
        </PillButton>
        <Divider flexItem>OR</Divider>
        <UsernameInput />
        <PillButton fullWidth size="large">
          Log In
        </PillButton>
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
            backgroundColor: '#2f3f56',
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
