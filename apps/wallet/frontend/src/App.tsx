import { ErrorBoundary, DirectoryEntry } from 'common-frontend';
import { useCallback, useEffect, useState } from 'react';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import {
  AppBar,
  Box,
  Button,
  CircularProgress,
  Container,
  CssBaseline,
  Toolbar,
  Typography,
} from '@mui/material';

import './App.css';
import { useUserState } from './contexts/UserContext';
import { useWalletClient } from './contexts/WalletServiceContext';
import AppPaymentRequests from './views/AppPaymentRequests';
import Coins from './views/Coins';
import ConfirmPayment from './views/ConfirmPayment';
import ConfirmSubscription from './views/ConfirmSubscription';
import Home from './views/Home';
import Login from './views/Login';
import Onboarding from './views/Onboarding';
import PaymentChannels from './views/PaymentChannels';
import Subscriptions from './views/Subscriptions';
import TransferOffers from './views/TransferOffers';

const App: React.FC = () => {
  const { isAuthenticated, primaryPartyId, logout } = useUserState();

  return (
    <ErrorBoundary>
      <Box height="100%" sx={{ display: 'flex', flexDirection: 'column' }}>
        <CssBaseline />
        <AppBar position="static">
          <Toolbar>
            <Typography variant="h6" sx={{ flexGrow: 1 }} id="app-title">
              CC Wallet
              {primaryPartyId && (
                <div id="logged-in-user">
                  <DirectoryEntry partyId={primaryPartyId} />
                </div>
              )}
            </Typography>
            {isAuthenticated && (
              <Button color="inherit" onClick={logout} id="logout-button">
                Log Out
              </Button>
            )}
          </Toolbar>
        </AppBar>
        <Container style={{ height: '100%', flex: '1' }}>
          {isAuthenticated ? <Content /> : <Login />}
        </Container>
      </Box>
    </ErrorBoundary>
  );
};

const Content = () => {
  const { updateStatus, isOnboarded, isAuthenticated, userId } = useUserState();
  const walletClient = useWalletClient();

  // show a loading spinner until we fully determine user status
  const [loading, setLoading] = useState(true);

  const routes = createBrowserRouter(
    createRoutesFromElements(
      <Route path="/" element={<Home />}>
        <Route index element={<Coins />} />
        <Route path="coins" element={<Coins />} />
        <Route path="app-payment-channels" element={<PaymentChannels />} />
        <Route path="transfer-offers" element={<TransferOffers />} />
        <Route path="subscriptions" element={<Subscriptions />}></Route>
        <Route path="app-payment-requests" element={<AppPaymentRequests />}></Route>
        <Route path="confirm-payment/:cid/" element={<ConfirmPayment />} />
        <Route path="confirm-subscription/:cid/" element={<ConfirmSubscription />} />
      </Route>
    )
  );

  const getUserStatus = useCallback(
    async (userId: string | undefined) => {
      if (userId === undefined) return;

      const status = await walletClient.userStatus();
      updateStatus(status);
      setLoading(false);
    },
    [walletClient, updateStatus]
  );

  // Fetch or refresh the onboarding status
  useEffect(() => {
    if (isAuthenticated && userId) {
      getUserStatus(userId).catch(console.error);
    }

    // Periodically when the user is not onboarded
    if (!isOnboarded) {
      const timer = setInterval(() => {
        getUserStatus(userId).catch(console.error);
      }, 5000);
      return () => clearInterval(timer);
    }
  }, [isOnboarded, isAuthenticated, userId, getUserStatus]);

  if (loading) {
    return (
      <Box
        sx={{
          width: '100%',
          height: '100%',
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
        }}
      >
        <CircularProgress sx={{ display: 'flex' }} />
      </Box>
    );
  }

  return isOnboarded ? (
    <RouterProvider router={routes} />
  ) : (
    <Onboarding onOnboard={() => getUserStatus(userId)} />
  );
};

export default App;
