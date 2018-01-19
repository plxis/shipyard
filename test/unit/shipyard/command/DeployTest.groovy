package shipyard.command

import shipyard.*
import org.junit.*
import groovy.mock.interceptor.MockFor
import groovy.json.JsonSlurper

public class DeployTest {

  @After
  void tearDown() {
    ModuleCommand.metaClass = null
  }

  @Test
  public void should_include_all_args() {
    def cmd = new Deploy()
    assert cmd.description
    assert cmd.args.find { it.name == "env" }
    assert cmd.args.find { it.name == "allmodules" }
    assert cmd.args.find { it.name == "module" }
    assert cmd.args.find { it.name == "targets" }
    assert cmd.args.find { it.name == "parallel" }
    assert cmd.args.find { it.name == "nopass" }
    assert cmd.args.find { it.name == "dryrun" }
    assert cmd.args.find { it.name == "diff" }
    assert cmd.args.find { it.name == "syntax" }
    assert cmd.args.find { it.name == "verbose" }
    assert cmd.args.find { it.name == "noconsole" }
  }

  def createDeployCommand(execClosure, installDeploymentClosure= { mod -> }) {
    def deploymentMod
    def cmd = new Deploy() {
      @Override
      protected createDeploymentModule(moduleName, ctx) {
        def mod = new DummyModule(moduleName, ctx)
        mod.deployClosure = execClosure
        deploymentMod = mod
        return mod
      }

      @Override
      protected installDeploymentPackage(DeploymentModule module, ModuleContext ctx) {
        return installDeploymentClosure(module)
      }
    }
    cmd
  }


  @Test
  public void should_deploy() {
    def foundPath, foundKey
    def installedDeploymentPackages = []
    def executedModules = []
    def metadataRaw = '''
    {
      "name":"bar",
      "repository":"xyz",
      "modules":{
        "mod1":{
          "inventory":{
            "foo":"b1",
            "group1": {
              "hosts": ["s1","s2"],
              "vars": { 
                "key1":"var1"
              }
            },
            "group2": {
              "hosts": ["a1"],
            },
            "group3": {
              "some":"thing"
            },
            "group4": {
              "hosts": ["s1","s2"],
              "vars": { 
                "key1":"var1",
                "repository":"abc"
              }
            },
          }
        }, 
        "mod2":{
          "inventory":{
            "foo":"b2"
          }
        }
      }
    }'''
    def props = new Properties()
    props.env = "test"
    props.envPrefix="secret/environment/"
    props.sshUser = "someone"
    def svc = new Object() {
      Object readKey(String path, String key) { foundPath = path; foundKey = key; return metadataRaw }
    }
    Deploy cmd = createDeployCommand({ mod -> executedModules << mod; null }, { mod -> installedDeploymentPackages << mod.moduleName; null })
    def actual = cmd.execute(svc as StorageService, props)
    assert foundPath == "secret/environment/test"
    assert foundKey == "metadata"
    assert installedDeploymentPackages.size() == 2
    assert installedDeploymentPackages.sort() == ['mod1', 'mod2']
    assert executedModules.size() == 2
    executedModules.collect { it.moduleName }.sort() == ['mod1', 'mod2']
    assert executedModules[0].ctx.globalEnv.name == 'bar'
  }


  @Test
  void should_install_deployment_package() {
    def foundCmd, foundEnv, foundWorkDir, foundTimeout, foundOut

    def syMock = new MockFor(Shipyard)
    syMock.demand.getHomeDir { "myhome" }

    ModuleCommand.metaClass.static.run = { ArrayList<String> cmd, Map<String,String> env, String homeDir, int timeout, OutputStream out ->
      foundCmd = cmd
      foundEnv = env
      foundWorkDir = homeDir
      foundTimeout = timeout
      foundOut = out
      return null
    }

    def props = new Properties()
    props.cmdTimeout=7200000
    def cmd = new Deploy()
    def moduleEnv = ['x':'y']
    def globalEnv = ['a':'b']
    def ctx = new ModuleContext(globalEnv, moduleEnv, null, props)
    def module = new DummyModule("mod1", ctx)
    syMock.use {
      cmd.installDeploymentPackage(module, ctx)
    }

    assert foundCmd == ["sudo", "yum", "install", "-y", "mod1-dummy-TestVer"]
    assert foundEnv == [:]
    assert foundWorkDir == "myhome"
    assert foundTimeout == 7200000 
    assert foundOut != null
  }

  @Test
  void should_pull_dependencies_custom_repo() {
    def foundCmd, foundEnv, foundWorkDir, foundTimeout, foundOut

    def syMock = new MockFor(Shipyard)
    syMock.demand.getHomeDir { "myhome" }

    ModuleCommand.metaClass.static.run = { ArrayList<String> cmd, Map<String,String> env, String homeDir, int timeout, OutputStream out ->
      foundCmd = cmd
      foundEnv = env
      foundWorkDir = homeDir
      foundTimeout = timeout
      foundOut = out
      return null
    }

    def props = new Properties()
    props.cmdTimeout=7200000
    def cmd = new Deploy()
    def moduleEnv = ['x':'y']
    def globalEnv = ['repository':'testRepo']
    def ctx = new ModuleContext(globalEnv, moduleEnv, null, props)
    def module = new DummyModule("mod1", ctx)
    syMock.use {
      cmd.installDeploymentPackage(module, ctx)
    }

    assert foundCmd == ["sudo", "yum", "--disablerepo=*", "--enablerepo=testRepo", "install", "-y", "mod1-dummy-TestVer"]
    assert foundEnv == [:]
    assert foundWorkDir == "myhome"
    assert foundTimeout == 7200000
    assert foundOut != null

  }


  @Test
  public void should_abort_if_module_fails() {
    def foundPath, foundKey
    def installedDeploymentPackages = []
    def executedModules = []
    def metadataRaw = '''
    {
      "name":"bar",
      "repository":"xyz",
      "modules":{
        "mod1":{
          "inventory":{
            "group1": {
              "hosts": ["s1","s2"],
              "vars": { 
                "key1":"var1"
              }
            },
            "group2": {
              "hosts": ["a1"],
            }
          }
        }, 
        "mod2":{
          "inventory":{
            "foo":"b2"
          }
        }
      }
    }'''
    def props = new Properties()
    props.env = "test"
    props.envPrefix="secret/environment/"
    props.sshUser = "someone"
    def svc = new Object() {
      Object readKey(String path, String key) { return metadataRaw }
    }
    Deploy cmd = createDeployCommand({ mod -> executedModules << mod; "Forced failure" }, { mod -> installedDeploymentPackages << mod.moduleName; null })
    def actual = cmd.execute(svc as StorageService, props)
    assert installedDeploymentPackages.size() == 1
    assert executedModules.size() == 1
    assert executedModules[0].ctx.globalEnv.name == 'bar'
  }

}