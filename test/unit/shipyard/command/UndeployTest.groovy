package shipyard.command

import org.junit.Test

class UndeployTest {

  @Test
  void shouldUndeployModule() {
    def installedDeploymentPackage = false
    def removedDeploymentPackage = false
    def undeployCalled = false
    def cmd = new Undeploy() {
      @Override
      protected installDeploymentPackage(DeploymentModule module, ModuleContext moduleCtx) {
        installedDeploymentPackage = true
        return null
      }

      @Override
      protected removeDeploymentPackage(DeploymentModule module, ModuleContext moduleCtx) {
        removedDeploymentPackage = true
        return null
      }
    }
    def mod = new DummyModule("myMod" , new ModuleContext())
    mod.undeployClosure = { m -> undeployCalled = true; return null }
    cmd.handleModule(mod)
    assert installedDeploymentPackage
    assert undeployCalled
    assert removedDeploymentPackage
  }
}
