package shipyard.command

import shipyard.Shipyard
import groovy.mock.interceptor.MockFor
import org.junit.*

class AnsibleModuleTest {

  @After
  void teardown() {
    ModuleCommand.metaClass = null
  }

  @Test
  void should_find_valid_targets() {
    def module = new AnsibleModule("testMod", new ModuleContext())
    def expected = "g2,h1"
    def inventory = [g0:[hosts:["h0","h1"]],g2:[hosts:["h3","h4"]]]
    def actual = module.findValidTargets("h1,g1,g2,h2", inventory)
    assert actual == expected
  }

  @Test
  void should_run_playbook() {
    def foundCmd, foundEnv, foundWorkDir, foundTimeout, foundOut

    def syMock = new MockFor(Shipyard)
    syMock.demand.getHomeDir(1..3) { "myhome" }

    def props = new Properties()
    props.cmdTimeout=7200000
    props.sshUser = "sherlock"
    props.nopass = "FalSE"
    props.diff = "TRUE"
    props.dryrun = "True"
    props.syntax = "true"
    props.verbose = "true"
    props.externalModuleDir = "/opt"
    props.targets = "host1,group2"
    props.parallel = "100%"
    def ctx = new ModuleContext([:],[:],null, props)
    ModuleCommand.metaClass.static.run = { ArrayList<String> cmd, Map<String,String> env, String homeDir, int timeout, OutputStream out ->
      foundCmd = cmd
      foundEnv = env
      foundWorkDir = homeDir
      foundTimeout = timeout
      foundOut = out
      return null
    }
    def mod = new AnsibleModule("mod1",ctx) {
      @Override
      protected def findValidTargets(requested, inventory) { return requested }
    }

    syMock.use {
      mod.runPlaybook([ "web-servers": [ "hosts": ["app-server-1", "app-server-2"]]])
    }
    int i = 0
    assert foundCmd[i++] == "ansible-playbook"
    assert foundCmd[i++] ==  "-i"
    assert foundCmd[i++] ==~ /.*superintendent-inventory.*\.sh/
    assert foundCmd[i++] == "/opt/mod1-deployment/current/ansible/site.yml"
    assert foundCmd[i++] ==  "-u"
    assert foundCmd[i++] ==  "sherlock"
    assert foundCmd[i++] ==  "--ask-pass"
    assert foundCmd[i++] ==  "--ask-become-pass"
    assert foundCmd[i++] ==  "--extra-vars=serial=100%"
    assert foundCmd[i++] ==  "--limit=host1,group2"
    assert foundCmd[i++] ==  "--check"
    assert foundCmd[i++] ==  "--diff"
    assert foundCmd[i++] ==  "--syntax-check"
    assert foundCmd[i++] ==  "--verbose"
    assert foundWorkDir == "myhome"
    assert foundTimeout == 7200000

    props.remove("nopass")
    syMock.use {
      mod.runPlaybook([ "web-servers": [ "hosts": ["app-server-1", "app-server-2"]]])
    }
    assert foundCmd.find { it == "--ask-pass" }
    assert foundOut

    foundOut = null
    props.nopass = "true"
    props.noconsole = "true"
    ctx.moduleEnv['ansible-extra-args'] = ['extra1', 'extra2']
    syMock.use {
      mod.runPlaybook([ "web-servers": [ "hosts": ["app-server-1", "app-server-2"]]])
    }
    assert !foundCmd.find { it == "--ask-pass" }
    ["extra1", "extra2"].each { cmd -> assert foundCmd.find { it == cmd } }
    assert foundOut == null

  }

  @Test
  void should_distribute_the_repository_variable() {
    def globalEnv = ['name':'mod1', 'type':'ansible', 'repository': 'fred' ]
    def moduleEnv = [
            'inventory': [
                    'someGroup': [
                            'hosts': ['host1', 'host2'],
                            'vars': [
                                    'varA': 'valA'
                            ]
                    ],
                    'someOtherGroup': [
                            'hosts': ['host3']
                    ]
            ]
    ]
    def ctx = new ModuleContext(globalEnv, moduleEnv, null, new Properties())
    def mod = new AnsibleModule("mod1", ctx)
    def inventory = mod.fetchInventory()
    assert inventory
    assert inventory.someGroup.vars.repository == "fred"
    assert inventory.someOtherGroup.vars.repository == "fred"
  }

  @Test
  void should_ignore_undeploy_request() {
    def mod = new AnsibleModule('myMod', new ModuleContext())
    assert null == mod.executeUndeploy()
  }

}
