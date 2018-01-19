package shipyard.command

import util.Log
import shipyard.Shipyard
import shipyard.ShipyardException
import shipyard.StorageService
import shipyard.SysExec
import groovy.json.JsonSlurper

/**
 * Base class for commands dealing with modules
 */
abstract class ModuleCommand extends Command {
  private static Log log = Log.getLogger(ModuleCommand)

  public static final String MODULE_NAME_KEY="moduleName"

  /** Apply command logic to supplied module */
  abstract def handleModule(DeploymentModule module)

  protected def readEnvironment(StorageService storageSvc, Properties props) {
    if (!props.env) throw new ShipyardException("env property must be specified")
    def metadata = storageSvc.readKey("${props.envPrefix}${props.env}", "metadata")
    return new JsonSlurper().parseText(metadata)
  }

  /** Creates a DeploymentModule instance of the type corresponding to the module */
  protected def createDeploymentModule(moduleName, moduleCtx) {
    def moduleType = determineModuleType(moduleCtx)
    DeploymentModule.forType(moduleType, moduleName, moduleCtx)
  }

  /**
   * Determines the type of deployment module by inspecting the context.
   * If an explicit 'type' key is present in the module environment, use that.
   * Otherwise, look for type-specific keys (ie: 'inventory' implies an ansible module).
   */
  protected def determineModuleType(ModuleContext ctx) {
    def type = ctx.moduleEnv?.get('type')
    if(type) {
      log.debug("Module type specified directly", ['type': type])
    }

    // Presence of an 'inventory' key implies an ansible module
    if(!type && ctx.moduleEnv?.inventory) {
      log.info("Module type inferred to be ansible. Consider specifying directly ('type: \"ansible\"')");
      type = "ansible"
    }

    if(!type) {
      throw new IllegalArgumentException("Unable to determine type for module")
    }

    type
  }

  static def run(ArrayList<String> cmd, Map<String,String> env, String homeDir, int timeout, OutputStream out = null) {
    def exec = new SysExec()
    int exitCode = exec.run(cmd, env, homeDir, timeout, out)
    return exitCode == 0 ? null : (exec.errorText ?: "non-zero exit code(${exitCode})".toString())
  }

  public int execute(StorageService storageSvc, Properties props) {
    def environment = readEnvironment(storageSvc, props)
    output =  executeModules(props, environment, storageSvc)
    return output ? 1 : 0
  }

  /** Installs product deployment RPM for a module */
  protected def installDeploymentPackage(DeploymentModule module, ModuleContext moduleCtx) {
    yumCmd("install", module, moduleCtx)
  }

  /** Removes product deployment RPM for a module */
  protected def removeDeploymentPackage(DeploymentModule module, ModuleContext moduleCtx) {
    yumCmd("remove", module, moduleCtx)
  }

  protected def getPackageName(module, moduleCtx) {
    moduleCtx.moduleEnv.packageName ?: "${module.moduleName}${module.moduleInstallPackageSuffix}"
  }

  protected def yumCmd(action, DeploymentModule module, ModuleContext moduleCtx) {
    def cmd = []
    cmd << "sudo"
    cmd << "yum"
    def repository = moduleCtx.globalEnv.repository
    if (repository) {
      cmd << "--disablerepo=*".toString()
      cmd << "--enablerepo=${repository}".toString()
    }
    cmd << action
    cmd << "-y"
    def packageName = getPackageName(module, moduleCtx)
    def version = module.version
    def versionSuffix = version && !version.equalsIgnoreCase('latest') ? '-'+version : ''
    cmd << "${packageName}${versionSuffix}".toString()
    return run(cmd, [:], Shipyard.homeDir,
            moduleCtx.runtimeProps.cmdTimeout?.toInteger(),
            Boolean.parseBoolean(moduleCtx.runtimeProps.noconsole) ? null : System.out)
  }

  protected def executeModules(Properties runtimeProps, globalEnvironment, storageSvc) {
    String errorOutput
    for (def moduleEnv : getModulesInfo(globalEnvironment)) {
      def moduleName = moduleEnv[MODULE_NAME_KEY]
      if (!runtimeProps.module || (runtimeProps.module.equalsIgnoreCase(moduleName))) {

        def moduleCtx = new ModuleContext(globalEnvironment, moduleEnv, storageSvc, runtimeProps)
        def module = createDeploymentModule(moduleName, moduleCtx)
        assert module

        errorOutput = handleModule(module)

        if (errorOutput) {
          log.error("Aborting deployment due to error while executing module",[reason:errorOutput])
          break
        }
      }
    }
    return errorOutput
  }

  /**
   * Extract module info from global environment.
   * Supports declarations of modules in either an (unordered) map, or
   * an order list of modules (each much contain a 'moduleName' key).
   */
  protected List getModulesInfo(globalEnvironment) {
    def moduleInfo = globalEnvironment.modules
    List modules
    if(moduleInfo instanceof Map) {
      log.debug("Converting modules map into list")
      modules = modulesMapToList(moduleInfo)
    } else if(moduleInfo instanceof List) {
      modules = moduleInfo
    }

    if(modules == null) {
      throw new IllegalStateException("Could not find module information")
    }

    // Verify that each module has a name defined
    if(modules.find { m -> m[MODULE_NAME_KEY] == null}) {
      throw new IllegalStateException("Module has no name")
    }

    return modules
  }

  /** Translate from map entries to a list */
  private List modulesMapToList(Map moduleInfo) {
    def modules = []
    for (def moduleEntry : moduleInfo) {
      def moduleName = moduleEntry.key
      def moduleEnv = moduleEntry.value
      // Verify that moduleEnv doesn't already have a moduleName key
      if(moduleEnv[MODULE_NAME_KEY]) {
        throw new IllegalStateException("Module defined in map already contains a ${MODULE_NAME_KEY} key")
      }
      moduleEnv[MODULE_NAME_KEY] = moduleName
      modules << moduleEnv
    }
    return modules
  }
}
