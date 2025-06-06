import { installController } from './controller';
import { installDockerRegistryMirror } from './dockerMirror';
import { installRunnerScaleSets } from './runners';

installDockerRegistryMirror();
const controller = installController();
installRunnerScaleSets(controller);
