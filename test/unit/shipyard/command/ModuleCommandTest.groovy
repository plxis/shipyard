package shipyard.command

import shipyard.StorageService
import shipyard.SysExec
import groovy.json.JsonSlurper
import org.junit.Assert
import org.junit.Test

class ModuleCommandTest {

  static class ModuleCommandAdapter extends ModuleCommand {
    @Override
    def handleModule(DeploymentModule module) {
      return module.executeDeploy()
    }

    @Override
    String getDescription() {
      return null
    }

    @Override
    List getArgs() {
      return []
    }

  }

  def createCommand(deployClosure = { mod -> }, undeployClosure = { mod -> }) {
    def deploymentMod
    def cmd = new ModuleCommandAdapter() {
      @Override
      protected createDeploymentModule(moduleName, ctx) {
        def mod = new DummyModule(moduleName, ctx)
        deploymentMod = mod
        mod.deployClosure = deployClosure
        mod.undeployClosure = undeployClosure
        return mod
      }
    }
    cmd
  }


  @Test
  void should_determine_module_type() {
    def cmd = createCommand()

    def ctx = { modEnv -> new ModuleContext([:],modEnv, null, new Properties()) }

    // Not enough info
    try {
      cmd.determineModuleType(ctx([:]))
      Assert.fail("Did not throw expected exception")
    } catch(IllegalArgumentException e) {
      assert e
    }

    // explicit type
    assert "myType" == cmd.determineModuleType(ctx([type: 'myType']))

    // Inferred ansible
    assert "ansible" == cmd.determineModuleType(ctx([inventory: [x:2]]))
  }

  @Test
  void should_create_module() {
    def cmd = new ModuleCommandAdapter()

    def ctx = { modEnv -> new ModuleContext([:],modEnv, null, new Properties()) }
    def mod = cmd.createDeploymentModule('myMod', ctx(['type': 'terraform']))
    assert mod instanceof TerraformModule
  }

  @Test
  void should_fail_even_if_no_stderr() {
    SysExec.metaClass.run = { List cmd, Map env, String workDir, int timeout, OutputStream out = null ->
      return 1
    }
    SysExec.metaClass.getErrorText { return "" }

    try {
      def output = ModuleCommand.run(["c","a"], [k:"val"], "myhome", 123, null)
      assert output == "non-zero exit code(1)"
    } finally {
      SysExec.metaClass = null
    }
  }

  @Test
  public void should_filter_to_specific_module() {
    def deployedModules = []
    def metadataRaw = '''
    {
      "name":"bar",,
      "modules":{
        "mod1":{
          "inventory":{
            "foo":"b1"
          }
        }, 
        "mod2":{
          "inventory":{
            "foo":"b2",
            "group1": {
              "hosts": ["s1","s2"],
              "vars": { 
                "key1":"var1"
              }
            }
          }
        }
      }
    }'''
    def metadata = new JsonSlurper().parseText(metadataRaw)
    def props = new Properties()
    props.env = "test"
    props.module = "mod2"
    def svc = new Object() {
      Object readKey(String path, String key) { metadataRaw }
    }
    def cmd = createCommand({ mod ->
      deployedModules << mod; null
    })
    cmd.execute(svc as StorageService, props)
    assert deployedModules.size() == 1
    deployedModules.collect { it.moduleName }.sort() == ['mod2']
  }

  @Test
  void should_fail_if_no_modules() {
    def metadataRaw = '''
    {
      "name":"bar"
    }'''
    def globalEnv = new JsonSlurper().parseText(metadataRaw)
    def cmd = createCommand()
    try {
      cmd.getModulesInfo(globalEnv)
      Assert.fail("Did not throw expected ex")
    } catch(IllegalStateException e) {
      assert e.message?.contains("Could not find")
    }
  }

  @Test
  void should_fail_if_module_with_no_name() {
    def metadataRaw = '''
    {
      "name":"bar",
      "modules": [
        {"moduleName": "mod1", "inventory": {"a1":"b1"}}, 
        {"inventory": {"a1":"b1"}}
      ]
    }'''
    def globalEnv = new JsonSlurper().parseText(metadataRaw)
    def cmd = createCommand()
    try {
      cmd.getModulesInfo(globalEnv)
      Assert.fail("Did not throw expected ex")
    } catch(IllegalStateException e) {
      assert e.message?.contains("Module has no name")
    }
  }

  @Test
  void should_convert_module_map_to_list() {
    def metadataRaw = '''
    {
      "name":"bar",,
      "modules":{
        "mod1":{
          "inventory":{
            "foo":"b1"
          }
        }, 
        "mod2":{
          "inventory":{
            "foo":"b2",
            "group1": {
              "hosts": ["s1","s2"],
              "vars": { 
                "key1":"var1"
              }
            }
          }
        }
      }
    }'''
    def globalEnv = new JsonSlurper().parseText(metadataRaw)
    def cmd = createCommand()
    def modules = cmd.getModulesInfo(globalEnv)
    assert modules.size() == 2
    def mod1 = modules.find { it.moduleName == 'mod1'}
    assert mod1.inventory?.foo == "b1"
    def mod2 = modules.find { it.moduleName == 'mod2'}
    assert mod2.inventory?.foo == "b2"
  }

  @Test
  void should_support_ordered_list_of_modules() {
    def metadataRaw = '''
    {
      "name":"bar",
      "modules": [
        {
          "moduleName": "mod1",
          "inventory":{
            "foo":"b1"
          }
        }, 
        {
          "moduleName": "mod2",
          "inventory":{
            "foo":"b2"
          }
        }
      ]
    }'''
    def globalEnv = new JsonSlurper().parseText(metadataRaw)
    def cmd = createCommand()
    def modules = cmd.getModulesInfo(globalEnv)
    assert modules.size() == 2
    def mod1 = modules[0]
    assert mod1.moduleName == 'mod1'
    assert mod1.inventory?.foo == "b1"
    def mod2 = modules[1]
    assert mod2.moduleName == 'mod2'
    assert mod2.inventory?.foo == "b2"
  }

  @Test
  void should_fail_to_convert_module_map_if_name_already_defined() {
    def metadataRaw = '''
    {
      "name":"bar",,
      "modules":{
        "mod1":{
          "moduleName": "myMod",
          "inventory":{
            "foo":"b1"
          }
        }
      }
    }'''
    def globalEnv = new JsonSlurper().parseText(metadataRaw)
    def cmd = createCommand()
    try {
      cmd.getModulesInfo(globalEnv)
      Assert.fail("Did not throw expected ex")
    } catch(IllegalStateException e) {
      assert e.message?.contains("already contains a moduleName")
    }
  }

  @Test
  void should_execute_yum_install() {
    def capturedCmd = []
    ModuleCommand.metaClass.static.run = {  cmd, env, homeDir, timeout, out = null ->
      capturedCmd = cmd
    }
    try {
      def cmd = new ModuleCommandAdapter() {

      }

      def ctx = new ModuleContext([:], [:], null, new Properties())

      cmd.installDeploymentPackage(new DummyModule("myMod", ctx, "latest"), ctx)
      assert capturedCmd == ["sudo","yum", "install", "-y", "myMod-dummy"]

      cmd.installDeploymentPackage(new DummyModule("myMod", ctx, null, null), ctx)
      assert capturedCmd == ["sudo","yum", "install", "-y", "myMod-dummy-TestVer"]


      cmd.installDeploymentPackage(new DummyModule("myMod", ctx, "myVer", "-suffix"), ctx)
      assert capturedCmd == ["sudo","yum", "install", "-y", "myMod-suffix-myVer"]

      ctx = new ModuleContext([:], [packageName:'package-foo'], null, new Properties())
      cmd.installDeploymentPackage(new DummyModule("myMod", ctx, "latest", null), ctx)
      assert capturedCmd == ["sudo","yum", "install", "-y", "package-foo"]

    } finally {
      ModuleCommand.metaClass = null
    }
  }

}
