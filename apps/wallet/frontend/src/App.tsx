import { useAuth0 } from '@auth0/auth0-react';
import { useEffect, useState } from 'react';

import { AppBar, Box, Button, Container, CssBaseline, Toolbar, Typography } from '@mui/material';

import './App.css';
import ErrorBoundary from './utils/ErrorBoundary';
import Home from './views/Home';
import Login from './views/Login';

const App: React.FC = () => {
  // TODO(i701) -- create a React context to manage auth state
  const [damlUserId, setDamlUserId] = useState<string>();
  const { user, logout } = useAuth0();

  useEffect(() => {
    if (user) {
      setDamlUserId(user.sub);
    }
  }, [user]);

  return (
    <ErrorBoundary>
      <Box height="100%" sx={{ display: 'flex', flexDirection: 'column' }}>
        <CssBaseline />
        <AppBar position="static">
          <Toolbar>
            <Typography variant="h6" sx={{ flexGrow: 1 }}>
              CC Wallet
            </Typography>
            {damlUserId && (
              <Button
                color="inherit"
                onClick={() => {
                  setDamlUserId(undefined);
                  logout();
                }}
              >
                Log Out
              </Button>
            )}
          </Toolbar>
        </AppBar>
        <Container style={{ height: '100%', flex: '1' }}>
          {damlUserId ? <Home userId={damlUserId} /> : <Login onLogin={setDamlUserId} />}
        </Container>
      </Box>
    </ErrorBoundary>
  );
};

export default App;
