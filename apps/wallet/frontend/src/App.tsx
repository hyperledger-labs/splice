import { useState } from 'react';

import { TabPanel, TabContext } from '@mui/lab';
import {
  AppBar,
  Box,
  Button,
  Container,
  CssBaseline,
  Tab,
  Tabs,
  Toolbar,
  Typography,
} from '@mui/material';

import './App.css';
import AppPaymentRequests from './AppPaymentRequests';
import Coins from './Coins';
import Login from './Login';
import PaymentChannels from './PaymentChannels';
import { WalletClientProvider } from './WalletServiceContext';

const App: React.FC = () => {
  // TODO(i701) -- create a React context to manage auth state
  const [damlUserId, setDamlUserId] = useState<string>();

  return (
    <Box height="100%" sx={{ display: 'flex', flexDirection: 'column' }}>
      <CssBaseline />
      <AppBar position="static">
        <Toolbar>
          <Typography variant="h6" sx={{ flexGrow: 1 }}>
            CC Wallet
          </Typography>
          {damlUserId && (
            <Button color="inherit" onClick={() => setDamlUserId(undefined)}>
              Log Out
            </Button>
          )}
        </Toolbar>
      </AppBar>
      <Container style={{ height: '100%', flex: '1' }}>
        {damlUserId ? <Main userId={damlUserId} /> : <Login onLogin={setDamlUserId} />}
      </Container>
    </Box>
  );
};

const Main: React.FC<{ userId: string }> = ({ userId }) => {
  const [tabValue, setTabValue] = useState<string>('coins');

  return (
    <WalletClientProvider url={process.env.REACT_APP_GRPC_URL || 'http://localhost:6004'}>
      <Box sx={{ borderBottom: 1, borderColor: 'divider', marginBottom: 5 }}>
        <Tabs value={tabValue} onChange={(_, value) => setTabValue(value)}>
          <Tab label="Coins" value="coins" />
          <Tab label="Payment channels" value="payment_channels" />
          <Tab label="App payment requests" value="app_payment_requests" />
        </Tabs>
      </Box>
      <TabContext value={tabValue}>
        <TabPanel value="coins">
          <Coins userId={userId} />
        </TabPanel>
        <TabPanel value="payment_channels">
          <PaymentChannels userId={userId} />
        </TabPanel>
        <TabPanel value="app_payment_requests">
          <AppPaymentRequests userId={userId} />
        </TabPanel>
      </TabContext>
    </WalletClientProvider>
  );
};

export default App;
