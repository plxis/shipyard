package shipyard.command

import org.junit.*
import org.junit.rules.ExpectedException

class DeploymentModuleTest {

  @Test
  void should_create_module_for_type() {
    def module = DeploymentModule.forType("ansible", "mod1", new ModuleContext())
    assert module
    assert module instanceof AnsibleModule
  }

  @Test(expected=IllegalArgumentException)
  void should_fail_to_create_module_for_unknown_type() {
    DeploymentModule.forType("not_a_real_module", "mod1", new ModuleContext())
  }

  class DummyModule extends DeploymentModule {
    def DummyModule(String name, ModuleContext ctx) {
      super(name, ctx)
    }

    @Override
    String getType() {
      return "dummy"
    }

    @Override
    String getVersion() {
      return "1"
    }

    @Override
    String getModuleInstallPackageSuffix() {
      return "-dummy"
    }

    @Override
    def executeDeploy() {
      return 0
    }

    @Override
    def executeUndeploy() {
      return 0
    }
  }

  @Test
  void should_export_data_to_json() {
    def outFile
    def ctx = new ModuleContext([:], [:], null, new Properties())
    try {
      def mod = new DummyModule('dummy', ctx)
      def data = ['some': 'key']
      outFile = mod.exportJsonToFile(data)
      assert outFile.text == '{"some":"key"}'
    } finally {
      outFile?.delete()
    }
  }

  @Test
  void should_export_data_to_json_with_header() {
    def outFile
    def ctx = new ModuleContext([:], [:], null, new Properties())
    try {
      def mod = new DummyModule('dummy', ctx)
      def data = ['some2': 'key2']
      outFile = mod.exportJsonToFile(data, "test-prefix", "test-suffix",
        "header", "footer")
      assert outFile.text == 'header{"some2":"key2"}footer'
    } finally {
      outFile?.delete()
    }
  }

  @Test
  void should_export_data_to_json_with_header_no_body() {
    def outFile
    def ctx = new ModuleContext([:], [:], null, new Properties())
    try {
      def mod = new DummyModule('dummy', ctx)
      outFile = mod.exportJsonToFile(null, "test-prefix", "test-suffix",
              "header", "footer")
      assert outFile.text == 'headerfooter'
    } finally {
      outFile?.delete()
    }
  }

}