import {downAllTheStacks} from "./pulumiHelper";

downAllTheStacks().catch(e => {
  console.error('Failed to run destroy');
  console.error(e);
  process.exit(1);
});
