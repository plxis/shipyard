package shipyard.command

import shipyard.*
import util.*

public class Deploy extends ModuleCommand {
  private static Log log = Log.getLogger(Deploy)

  public String getDescription() { "Initiates an environment deployment." }
  public List getArgs() {
    [new Arg("env", "Name of the target environment, e.g. -env=some-environment", true),
     new Arg("allmodules", "Applies all modules to the environment", true, ["module"]),
     new Arg("module", "Specify the module to deploy, e.g. -module=xyz", true, ["allmodules"]),
     new Arg("targets", "Specify the target hosts and/or host groups to deploy, e.g. -targets=xyz-servers,host1.foo", false),
     new Arg("parallel", "Specify the number of hosts to deploy concurrently (can be integer or percentage, defaults to 1), e.g. -parallel=100%", false),
     new Arg("nopass", "Do not prompt for SSH password to remote host(s)", false),
     new Arg("dryrun", "Outputs what would have changed but will not actually make the change", false),
     new Arg("diff", "Outputs template differences, useful with -dryrun", false),
     new Arg("syntax", "Check the playbook syntax but will not run anything", false),
     new Arg("verbose", "Provides more detailed deployment output", false),
     new Arg("noconsole", "Directs deployment output only to the logging system, not to the console", false),
     new Arg("force", "Does not prompt before making changes (if module type supports prompts", false)]
  }

  /**
   * Deploys the specified module
   */
  def handleModule(DeploymentModule module) {
    log.info("Deploying module '${module.moduleName}' (${module.type}), version ${module.version ?: 'latest'}")
    installDeploymentPackage(module, module.ctx)
    return module.executeDeploy()
  }

}
