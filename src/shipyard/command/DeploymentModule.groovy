package shipyard.command

import util.Log
import shipyard.StorageService
import shipyard.SysExec
import com.sun.xml.internal.ws.api.server.Module
import groovy.json.JsonOutput

/**
 * Base class for deployment mechanisms (Ansible, Terraform, etc)
 */
abstract class DeploymentModule {
  private static Log log = Log.getLogger(DeploymentModule)

  def moduleName

  /** Module execution context */
  ModuleContext ctx

  public DeploymentModule(String name, ModuleContext ctx) {
    assert name
    assert ctx
    this.moduleName = name
    this.ctx = ctx
  }

  /** Type of module (ansible, terraform, etc) */
  abstract String getType()

  /** Version of product to be deployed */
  abstract String getVersion()

  /**
   * Module package suffix (ie: -deployment, -provisioning)
   * Used to install the package containing files used by this DeploymentModule implementation.
   */
  abstract String getModuleInstallPackageSuffix()

  /** Execute deployment. Return 0 for success, non-zero for failure */
  abstract executeDeploy()

  /** Undeploy the module. Return 0 for success or no-op, non-zero for failure */
  abstract executeUndeploy()

  static DeploymentModule forType(String type, String name, ModuleContext ctx) {
    def pkg = DeploymentModule.class.getPackage().name
    def className = "${type?.capitalize()}Module"
    def moduleClassFqn = "${pkg}.${className}"
    def moduleClass
    try {
      moduleClass = Class.forName(moduleClassFqn)
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("Class for module type '${type}' (${moduleClassFqn}) does not exist " +
              "or could not be loaded")
    }
    moduleClass.newInstance(name, ctx)
  }

  /**
   * Converts a data structure to JSON and writes it to a temporary file,
   * with optional file header and footer.
   * @return Temporary file
   */
  protected File exportJsonToFile(data, prefix="shipyard-temp", suffix="", fileHeader=null, fileFooter=null) {
    def out = File.createTempFile(prefix, suffix)
    out.setReadable(true, true)
    out.setExecutable(true, true)

    StringBuilder sb = new StringBuilder()
    if(fileHeader) {
      sb.append(fileHeader)
    }
    if(data != null) {
      sb.append(JsonOutput.toJson(data))
    }
    if(fileFooter) {
      sb.append(fileFooter)
    }
    out.text = sb.toString()
    out
  }

}
