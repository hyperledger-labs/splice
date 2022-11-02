import { Outlet, useNavigate } from 'react-router-dom';

import { Box, Button } from '@mui/material';

const Home: React.FC = () => {
  const navigate = useNavigate();
  return (
    <>
      <Box sx={{ borderBottom: 1, borderColor: 'divider', marginBottom: 5, width: 1.0 }}>
        <Button
          id="coins-button"
          onClick={() => {
            navigate('/coins');
          }}
        >
          Coins
        </Button>
        <Button
          id="app-payment-channels-button"
          onClick={() => {
            navigate('/app-payment-channels');
          }}
        >
          Payment Channels
        </Button>
        <Button
          id="subscriptions-button"
          onClick={() => {
            navigate('/subscriptions');
          }}
        >
          Subscriptions
        </Button>
        <Button
          id="app-payment-requests-button"
          onClick={() => {
            navigate('/app-payment-requests');
          }}
        >
          App Payment Requests
        </Button>
        <Outlet />
      </Box>
    </>
  );
};

export default Home;
