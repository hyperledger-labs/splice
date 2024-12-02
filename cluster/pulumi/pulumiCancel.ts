import { runStacksCancel } from "./pulumiHelper";

runStacksCancel().catch(e => {
  console.error(e);
  process.exit(1);
});
