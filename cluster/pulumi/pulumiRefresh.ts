import { runStacksRefresh } from "./pulumiHelper";

runStacksRefresh().catch(e => {
  console.error(e);
  process.exit(1);
});
