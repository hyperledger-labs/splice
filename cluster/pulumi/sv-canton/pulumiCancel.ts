import { cancelAllTheStacks } from "./pulumiHelper";

cancelAllTheStacks().catch(err => {
  console.error('Failed to run cancel');
  console.error(err);
  process.exit(1);
});
