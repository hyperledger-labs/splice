import { ErrorBoundary } from 'common-frontend';
import { generateLedgerApiToken } from 'common-frontend/lib/contexts/LedgerApiContext';
import { useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';

import { AppBar, Box, Button, Container, CssBaseline, Toolbar, Typography } from '@mui/material';

import './App.css';
import Home from './views/Home';
import Login from './views/Login';

// useAuth hook throws an error if used without a parent AuthProvider context,
// which is actually OK & expected if the app is running with a hs-256-unsafe auth config
const useAuthSafe = () => {
  try {
    return useAuth();
  } catch {
    return undefined;
  }
};

const App: React.FC = () => {
  const [damlUserId, setDamlUserId] = useState<string>();
  const { user, removeUser } = useAuthSafe() || {};
  const [ledgerApiToken, setLedgerApiToken] = useState<string | undefined>();

  useEffect(() => {
    if (user) {
      setDamlUserId(user?.profile?.sub);
    }
  }, [user]);

  useEffect(() => {
    const generateToken = async (userId: string | undefined) => {
      if (userId !== undefined) {
        const token = await generateLedgerApiToken(userId);
        setLedgerApiToken(token);
      }
    };
    generateToken(damlUserId);
  }, [damlUserId]);

  return (
    <ErrorBoundary>
      <Box height="100%" sx={{ display: 'flex', flexDirection: 'column' }}>
        <CssBaseline />
        <AppBar position="static">
          <Toolbar>
            <Typography variant="h6" sx={{ flexGrow: 1 }}>
              Canton Name Service
            </Typography>
            {damlUserId && (
              <Button
                color="inherit"
                onClick={() => {
                  setDamlUserId(undefined);
                  removeUser && removeUser();
                }}
              >
                Log Out
              </Button>
            )}
          </Toolbar>
        </AppBar>
        <Container style={{ height: '100%', flex: '1' }}>
          {damlUserId && ledgerApiToken ? (
            <Home userId={damlUserId} ledgerApiToken={ledgerApiToken} />
          ) : (
            <Login onLogin={setDamlUserId} />
          )}
        </Container>
      </Box>
    </ErrorBoundary>
  );
};

export default App;
