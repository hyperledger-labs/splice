import { cancelAllCantonStacks } from './pulumiCancel';

cancelAllCantonStacks().catch(err => {
  console.error('Failed to run cancel');
  console.error(err);
  process.exit(1);
});
