import { runStacksCancel } from './pulumiHelper';
import { cancelAllTheStacks } from './sv-canton/pulumiHelper';

runStacksCancel().catch(e => {
  console.error(e);
  process.exit(1);
});

cancelAllTheStacks().catch(e => {
  console.error(e);
  process.exit(1);
});
