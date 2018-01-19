package shipyard.command

/**
 * Holds data relevant to executing a deployment module
 */
class ModuleContext {

  def globalEnv = [:]
  def moduleEnv = [:]
  def storageSvc
  def runtimeProps = new Properties()

  public ModuleContext() {}

  ModuleContext(globalEnv, moduleEnv, storageSvc, runtimeProps) {
    this.globalEnv = globalEnv
    this.moduleEnv = moduleEnv
    this.storageSvc = storageSvc
    this.runtimeProps = runtimeProps
  }

}
