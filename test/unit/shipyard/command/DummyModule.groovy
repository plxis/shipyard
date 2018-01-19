package shipyard.command

class DummyModule extends DeploymentModule {

  def DummyModule(Object name, ModuleContext ctx, version = null, packageSuffix=null) {
    super(name, ctx)
    this.version = version
    this.packageSuffix = packageSuffix
  }

  def deployClosure
  def undeployClosure
  def version
  def packageSuffix

  @Override
  String getType() {
    return "dummy"
  }

  @Override
  String getVersion() {
    return version ?: "TestVer"
  }

  @Override
  String getModuleInstallPackageSuffix() {
    return packageSuffix ?: "-dummy"
  }

  @Override
  def executeDeploy() {
    return deployClosure(this)
  }

  @Override
  def executeUndeploy() {
    return undeployClosure(this)
  }
}
