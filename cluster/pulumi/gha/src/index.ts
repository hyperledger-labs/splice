import { installController } from './controller';
import { installRunnerScaleSets } from './runners';

const controller = installController();
installRunnerScaleSets(controller);
