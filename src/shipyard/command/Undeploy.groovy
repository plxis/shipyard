package shipyard.command

import util.Log

/**
 * Removes previously installed modules
 */
class Undeploy extends ModuleCommand {
  private static Log log = Log.getLogger(Undeploy)

  @Override
  String getDescription() {
    return "Removes previously deployed modules"
  }

  @Override
  List getArgs() {
    [new Arg("env", "Name of the target environment, e.g. -env=some-environment", true),
     new Arg("allmodules", "Applies all modules to the environment", true, ["module"]),
     new Arg("module", "Specify the module to deploy, e.g. -module=xyz", true, ["allmodules"]),
     new Arg("dryrun", "Outputs what would have changed but will not actually make the change", false),
     new Arg("verbose", "Provides more detailed deployment output", false),
     new Arg("noconsole", "Directs deployment output only to the logging system, not to the console", false),
     new Arg("force", "Does not prompt before making changes (if module type supports prompts", false)]
  }

  @Override
  def handleModule(DeploymentModule module) {
    installDeploymentPackage(module, module.ctx)
    log.info("Undeploying module '${module.moduleName}' (${module.type})")
    def retVal = null
    try {
      retVal = module.executeUndeploy()
    } finally {
      removeDeploymentPackage(module, module.ctx)
    }
    return retVal
  }
}
