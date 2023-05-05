import { Box } from '@mui/material';

import ValidatorLicenses from '../components/ValidatorLicenses';
import ValidatorOnboardingSecrets from '../components/ValidatorOnboardingSecrets';

const ValidatorOnboarding: React.FC = () => {
  return (
    <Box>
      <ValidatorOnboardingSecrets />
      <ValidatorLicenses />
    </Box>
  );
};

export default ValidatorOnboarding;
