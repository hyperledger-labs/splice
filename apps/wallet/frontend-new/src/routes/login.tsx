import * as React from 'react';

import { Person } from '@mui/icons-material';
import { Button, Divider, InputAdornment, OutlinedInput, Stack, Typography } from '@mui/material';
import Container from '@mui/material/Container';

import theme from '../theme';

const Login: React.FC = () => {
  return (
    <Container maxWidth="xs">
      <Stack alignItems="center" paddingTop={16} spacing={4}>
        <Typography variant="h5">CANTON WALLET</Typography>
        <LoginButton text={'Log In with OAuth2'} />
        <Divider flexItem>OR</Divider>
        <UsernameInput />
        <LoginButton text={'Log In'} />
      </Stack>
    </Container>
  );
};

const LoginButton: React.FC<{ text: string }> = ({ text }) => {
  return (
    <Button
      variant="contained"
      sx={{
        // most straightforward way of making "pill" buttons:
        // https://stackoverflow.com/questions/31617136/avoid-elliptical-shape-in-css-border-radius:
        borderRadius: '9999px',
        backgroundColor: '#577ff1',
        color: 'white',
      }}
      fullWidth
      size="large"
    >
      {text}
    </Button>
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
