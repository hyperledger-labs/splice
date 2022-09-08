import { useState } from 'react';

import { Button, Grid, TextField, Typography } from '@mui/material';

const Login: React.FC<{ onLogin: (userId: string) => void }> = ({ onLogin }) => {
  const [userId, setUserId] = useState<string>('');
  return (
    <Grid
      height="100%"
      container
      spacing={0}
      direction="column"
      alignItems="center"
      justifyContent="center"
    >
      <Typography variant="h4">Wallet Log In</Typography>
      <TextField
        label="Daml User ID"
        required
        id="user-id-field"
        value={userId}
        onChange={uid => setUserId(uid.target.value)}
      ></TextField>
      <Button
        onClick={e => {
          e.preventDefault();
          onLogin(userId);
        }}
      >
        Log In
      </Button>
    </Grid>
  );
};

export default Login;
