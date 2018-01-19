package shipyard.command

import shipyard.StorageService
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.commons.io.FileUtils
import org.junit.*

class TerraformModuleTest {

  @Before
  void setup() {
    TerraformModule.metaClass.getSystemEnv { -> [:] }
  }

  @After
  void teardown() {
    File.metaClass = null
    System.metaClass = null
    TerraformModule.metaClass = null
    TfModuleAdapter.metaClass = null
    ModuleCommand.metaClass = null
  }

  @Test
  void it_should_determine_the_product_version() {
    def moduleEnv = [ 'version': '8.24']
    def mod = new TfModuleAdapter(moduleEnv)
    assert "8.24" == mod.getVersion()
  }

  @Test
  void it_should_get_the_temporary_copy_of_terraform_config_directory() {
    File.metaClass.exists = { -> true }
    def moduleEnv = [ 'tf-dir': 'someDir']
    def mod = new TerraformModule("myMod", new ModuleContext([:], moduleEnv, null, new Properties())) {
      @Override
      protected copyTerraformFilesToTempDir() {
        return new File("/tmp/someOtherDir")
      }
    }
    def dir = mod.getTfWorkingDirectory()
    assert "/tmp/someOtherDir" == dir.getPath()
    assert "/tmp/someOtherDir" == moduleEnv['tf-dir-local']
  }


  @Test
  void it_should_use_specified_terraform_config_directory() {
    File.metaClass.exists = { -> delegate.absolutePath?.startsWith("/path") }
    def mod = new TfModuleAdapter(['tf-dir': '/path/to/tf/files'])
    def dir = mod.getTerraformFilesSourceDir()
    assert "/path/to/tf/files" == dir?.absolutePath
  }

  @Test
  void it_should_use_default_terraform_config_directory_if_not_specified() {
    File.metaClass.exists = { -> delegate.absolutePath?.startsWith("/opt") }
    def mod = new TfModuleAdapter()
    def dir = mod.getTerraformFilesSourceDir()
    assert "/opt/shipyard-modules/myMod-provisioning/current/terraform" == dir?.absolutePath
  }

  @Test
  void it_should_fail_if_terraform_config_directory_is_not_specified_and_default_does_not_exist() {
    File.metaClass.exists = { -> false }
    def mod = new TfModuleAdapter()
    try {
      mod.getTerraformFilesSourceDir()
      Assert.fail("Did not throw expected exception")
    } catch(IllegalArgumentException e){
      assert e.message.contains("does not exist")
      assert e.message.contains(TerraformModule.TF_DIR_KEY)
    }
  }

  @Test
  void it_should_fail_if_terraform_config_directory_does_not_exist() {
    File.metaClass.exists = { -> false }
    def moduleEnv = [ 'tf-dir': 'someDir']
    def mod = new TerraformModule("myMod", new ModuleContext([:], moduleEnv, null, new Properties()))
    try {
      mod.getTfWorkingDirectory()
      Assert.fail("Did not throw expected exception")
    } catch(IllegalArgumentException e){
      assert e.message.contains("does not exist")
    }
  }

  @Test
  void it_should_generate_the_tf_backend_configuration_file() {
    def capturedText
    File.metaClass.setText = { text ->
      capturedText = text
    }
    def moduleEnv = [ 'tf-dir': 'someDir',
                      'aws_access_key_id': 'keyId',
                      'aws_secret_access_key': 'secretKey',
                      'aws_region': 'moon-base-1',
                      'tf-state-bucket': 'florida']
    def mod = new TfModuleAdapter(moduleEnv)
    mod.generateBackendConfigurationFile(new File("/tmp"))
    assert capturedText
    def config = new JsonSlurper().parseText(capturedText)
    assert config
    def backend = config.terraform.backend.s3
    assert backend.region == "moon-base-1"
    assert backend.bucket == "florida"
    assert backend.access_key == "keyId"
    assert backend.key == "terraform-states/myMod/terraform.tfstate"
  }

  @Test
  void it_should_generate_the_tf_backend_configuration_file_with_context_name_as_state_key() {
    def capturedText
    File.metaClass.setText = { text ->
      capturedText = text
    }
    def moduleEnv = [ 'tf-dir': 'someDir',
                      'aws_access_key_id': 'keyId',
                      'aws_secret_access_key': 'secretKey',
                      'aws_region': 'moon-base-1',
                      'tf-state-bucket': 'florida',
                      'vars': ['context': 'my-context']]
    def mod = new TfModuleAdapter(moduleEnv)
    mod.generateBackendConfigurationFile(new File("/tmp"))
    assert capturedText
    def config = new JsonSlurper().parseText(capturedText)
    assert config
    def backend = config.terraform.backend.s3
    assert backend.region == "moon-base-1"
    assert backend.bucket == "florida"
    assert backend.access_key == "keyId"
    assert backend.key == "terraform-states/my-context/terraform.tfstate"
  }

  @Test
  void it_should_generate_the_tf_backend_configuration_file_with_custom_state_key() {
    def capturedText
    File.metaClass.setText = { text ->
      capturedText = text
    }
    def moduleEnv = [ 'tf-dir': 'someDir',
                      'aws_access_key_id': 'keyId',
                      'aws_secret_access_key': 'secretKey',
                      'aws_region': 'moon-base-1',
                      'tf-state-bucket': 'florida',
                      'tf-state-key': 'my/state/key']
    def mod = new TfModuleAdapter(moduleEnv)
    mod.generateBackendConfigurationFile(new File("/tmp"))
    assert capturedText
    def config = new JsonSlurper().parseText(capturedText)
    assert config
    def backend = config.terraform.backend.s3
    assert backend.region == "moon-base-1"
    assert backend.bucket == "florida"
    assert backend.access_key == "keyId"
    assert backend.key == "my/state/key"
  }

  @Test
  void it_should_use_existing_tf_backend_configuration_file() {
    def capturedText
    File.metaClass.exists = { -> true }
    File.metaClass.setText = { text ->
      capturedText = text
      throw new IllegalStateException("Should have used existing file")
    }
    def mod = new TfModuleAdapter()
    mod.generateBackendConfigurationFile(new File("/tmp"))
    assert !capturedText
  }

  @Test
  void it_should_overwrite_existing_tf_backend_configuration_file() {
    def capturedText
    File.metaClass.exists = { -> true }
    File.metaClass.setText = { text ->
      capturedText = text
    }
    def moduleEnv = [ 'tf-dir': 'someDir', 'aws_region': 'moon-base-1', 'tf-state-bucket': 'florida',
                      'aws_profile' : 'low']
    def mod = new TfModuleAdapter(moduleEnv)
    mod.generateBackendConfigurationFile(new File("/tmp"), true)
    def config = new JsonSlurper().parseText(capturedText)
    assert config
    def backend = config.terraform.backend.s3
    assert backend.region == "moon-base-1"
  }

  def configTestContext(key, props=true, sysEnv=true, modVar=true, module=true, global=true) {
    def runtimeProps = new Properties()
    if(props) {
      runtimeProps.setProperty(key, "val_runtimeProp")
    }
    TerraformModule.metaClass.getSystemEnv = {
      def e = sysEnv ? [(key.toUpperCase()): "val_sysEnv"] : [:]
      return e
    }
    def moduleEnv = [:]
    if(modVar) {
      moduleEnv += ['vars': [key: 'val_moduleEnv_vars']]
    }
    if(module) {
      moduleEnv += [key: 'val_moduleEnv']
    }
    def globalEnv = [:]
    if(global) {
      globalEnv += [key: 'val_globalEnv']
    }
    return new ModuleContext(globalEnv, moduleEnv, null, runtimeProps)
  }

  @Test
  void should_get_config_val_from_runtime_properties() {
    def mod = new TerraformModule("myMod", configTestContext('key'))
    assert mod.getConfigValue('key', 'test') == 'val_runtimeProp'
  }

  @Test
  void should_get_config_val_from_sysEnv() {
    def mod = new TerraformModule("myMod", configTestContext('key', false, true))
    assert mod.getConfigValue('key', 'test') == 'val_sysEnv'
  }

  @Test
  void should_get_config_val_from_moduleVars() {
    def mod = new TerraformModule("myMod", configTestContext('key', false, false, true))
    assert mod.getConfigValue('key', 'test') == 'val_moduleEnv_vars'
  }

  @Test
  void should_get_config_val_from_moduleEnv() {
    def mod = new TerraformModule("myMod", configTestContext('key', false, false, false, true))
    assert mod.getConfigValue('key', 'test') == 'val_moduleEnv'
  }

  @Test
  void should_get_config_val_from_globalEnv() {
    def mod = new TerraformModule("myMod", configTestContext('key', false, false, false, false, true))
    assert mod.getConfigValue('key', 'test') == 'val_globalEnv'
  }

  @Test(expected = IllegalStateException)
  void should_fail_if_config_val_not_found() {
    new TerraformModule("myMod", new ModuleContext()).getConfigValue("not_there", "test")
  }

  @Test
  void should_return_null_if_config_val_not_found() {
    assert null == new TerraformModule("myMod", new ModuleContext()).getConfigValue("not_there", "test", false)
  }

  @Test
  void should_use_aws_profile() {
    File.metaClass.exists = { -> true }
    def moduleEnv = ['aws_shared_credentials_file': '/credsFile','aws_profile':'myProfile']
    def creds = new TfModuleAdapter(moduleEnv).awsCredentials
    assert creds['shared_credentials_file'] == "/credsFile"
    assert creds['profile'] == "myProfile"
  }

  @Test
  void should_use_access_key_system_env() {
    TfModuleAdapter.metaClass.getSystemEnv = {
      return ['AWS_ACCESS_KEY_ID': "myAccessKey", 'AWS_SECRET_ACCESS_KEY': "mySecretKey"]
    }
    def creds = new TfModuleAdapter().awsCredentials
    assert creds['access_key'] == "myAccessKey"
    assert creds['secret_key'] == "mySecretKey"
  }

  @Test
  void should_use_access_key_module_env() {
    TerraformModule.metaClass.getSystemEnv = { return [:] }
    def modEnv = ['aws_access_key_id': 'modAccessKey', 'aws_secret_access_key': 'modSecretKey']
    def creds = new TfModuleAdapter(modEnv).awsCredentials
    assert creds['access_key'] == "modAccessKey"
    assert creds['secret_key'] == "modSecretKey"
  }

  @Test
  void should_use_access_key_global_env() {
    TerraformModule.metaClass.getSystemEnv = { return [:] }
    def globalEnv = ['aws_access_key_id': 'globalAccessKey', 'aws_secret_access_key': 'globalSecretKey']
    def modCtx = new ModuleContext(globalEnv, [:], null, new Properties())
    def creds = new TerraformModule("myMod", modCtx).awsCredentials
    assert creds['access_key'] == "globalAccessKey"
    assert creds['secret_key'] == "globalSecretKey"
  }

  @Test
  void should_fail_if_no_creds_available() {
    TerraformModule.metaClass.getSystemEnv = { return [:] }
    try {
      new TfModuleAdapter().awsCredentials
      Assert.fail("Did not throw expected exception")
    } catch(IllegalStateException e) {
      assert e.message.contains("No AWS credentials")
    }
  }

  @Test
  void should_map_terraform_provider_vars_to_aws_env_vars() {
    def mod = new TfModuleAdapter()
    def inputMap = [
            'bogus': 'nothing',
            'access_key': 'myAccessKey',
            'secret_key': 'mySecretKey',
            'profile': 'myProfile',
            'shared_credentials_file': 'myCredsFile'
    ]
    def envMap = mod.mapAwsCredsToEnvVars(inputMap)
    assert envMap == [
            'AWS_ACCESS_KEY_ID': 'myAccessKey',
            'AWS_SECRET_ACCESS_KEY': 'mySecretKey',
            'AWS_PROFILE': 'myProfile',
            'AWS_SHARED_CREDENTIALS_FILE': 'myCredsFile'
    ]
  }

  @Test
  void should_invoke_terraform_init() {
    def props = new Properties()
    props['cmdTimeout'] = "100"
    props['noconsole'] = 'false'
    def modCtx = new ModuleContext([:], ['tf-dir': 'blarg'], null, props)
    File.metaClass.exists = { -> true }
    def capturedCmd
    def capturedEnv
    def mod = new TerraformModule("myMod", modCtx) {
      @Override
      Map getAwsCredentials() {
        return ['profile': 'myProfile']
      }

      @Override
      File getTfWorkingDirectory() {
        return new File("/tmp")
      }
    }
    ModuleCommand.metaClass.static.run = { ArrayList<String> cmd, Map<String, String> env, String homeDir, int timeout, OutputStream out ->
      capturedCmd = cmd
      capturedEnv = env
    }
    mod.executeTerraform('init', '/tmp/tf', '/tmp/varfile', "extra_arg1", "extra_arg2")
    assert capturedCmd == ['terraform', 'init', '-no-color',
                           '-plugin-dir=/tf-plugins',
                           'extra_arg1', 'extra_arg2']
    assert capturedEnv == [ 'AWS_PROFILE': 'myProfile', 'TF_IN_AUTOMATION':"1" ]
  }

  @Test
  void should_invoke_terraform_plan() {
    def props = new Properties()
    props['cmdTimeout'] = "100"
    props['noconsole'] = 'false'
    def modCtx = new ModuleContext([:], ['tf-dir': 'blarg'], null, props)
    File.metaClass.exists = { -> true }
    def capturedCmd
    def capturedEnv
    def mod = new TerraformModule("myMod", modCtx) {
      @Override
      Map getAwsCredentials() {
        return ['profile': 'myProfile']
      }

      @Override
      File getTfWorkingDirectory() {
        return new File("/tmp")
      }
    }
    ModuleCommand.metaClass.static.run = { ArrayList<String> cmd, Map<String, String> env, String homeDir, int timeout, OutputStream out ->
      capturedCmd = cmd
      capturedEnv = env
    }
    mod.executeTerraform('plan', '/tmp/tf', '/tmp/varfile', "extra_arg1", "extra_arg2")
    assert capturedCmd == ['terraform', 'plan', '-no-color',
                           '--var-file', '/tmp/varfile', '-input=false',
                           'extra_arg1', 'extra_arg2', '/tmp/tf'
    ]
    assert capturedEnv == [ 'AWS_PROFILE': 'myProfile', 'TF_IN_AUTOMATION':"1" ]
  }


  static class TfModExecTestAdapter extends TfModuleAdapter {
    def terraformActions = []
    def terraformExtraArgs = []
    def deleted = []

    TfModExecTestAdapter() {
      super(['tf-working-dir': '/tmp/tf-work'])
    }
    @Override
    protected File exportJsonToFile(Object data, Object prefix, Object suffix) {
      return new File('/tmp/varfile')
    }

    @Override
    File getTfWorkingDirectory() {
      return new File("/tmp/tf-copy")
    }

    @Override
    def generateBackendConfigurationFile(File targetDir, overwrite) {
      new File("/tmp/backend")
    }

    @Override
    protected deleteFileOrDir(Object f) {
      if(f != null) { deleted << f }
    }

    @Override
    def executeTerraformWithOutputStream(Object action, Object tfDirOrPlan, Object varFile, OutputStream out, String... additionalArgs) {
      terraformActions << action
      terraformExtraArgs.addAll(additionalArgs)
      if(action == 'output' && out != null) {
        out.write("{}".getBytes("UTF-8"))
        out.flush()
        out.close()
      }
      return null
    }
  }

  @Test
  void should_deploy_module() {
    File.metaClass.exists = { -> true }
    def mod = new TfModExecTestAdapter()
    assert null == mod.executeDeploy()
    assert mod.terraformActions == ['init', 'plan', 'apply', 'output']
    assert mod.deleted.size() == 4
    assert !mod.terraformExtraArgs.contains("-destroy")
  }

  @Test
  void should_undeploy_module() {
    File.metaClass.exists = { -> true }
    def mod = new TfModExecTestAdapter()
    assert null == mod.executeUndeploy()
    assert mod.terraformActions == ['init', 'plan', 'apply', 'output']
    assert mod.deleted.size() == 4
    assert mod.terraformExtraArgs.contains("-destroy")
  }

  @Test
  void should_delete_working_files() {
    def deleted = []
    def mod = new TfModuleAdapter() {
      @Override
      protected deleteFileOrDir(Object f) {
        if(f != null) { deleted << f.absolutePath }
      }
    }
    mod.deleteWorkingFiles(new File("/backend"), new File("/varFile"), new File("/plan"), new File("/working"))
    assert deleted == ["/varFile", "/plan", "/backend", "/working"]
  }

  @Test
  void should_preserve_working_dir() {
    def deleted = []
    def mod = new TfModuleAdapter(['preserve-working-dir': true]) {
      @Override
      protected deleteFileOrDir(Object f) {
        if(f != null) { deleted << f.absolutePath }
      }
    }
    mod.deleteWorkingFiles(new File("/backend"), new File("/varFile"), new File("/plan"), new File("/working"))
    assert deleted == ["/varFile", "/plan"]
  }

  @Test
  void should_use_specified_working_dir() {
    File.metaClass.exists = { -> true }
    File.metaClass.mkdirs = { -> }
    File.metaClass.static.createTempDir = { String prefix, String suffix -> throw RuntimeException("Should not create temp dir") }
    File.metaClass.setWritable = { boolean writable, boolean owner-> }
    FileUtils.metaClass.static.copyDirectory = {File src, File dst -> }

    def mod = new TfModuleAdapter(['tf-dir': '/tmp/tf-src', 'tf-working-dir': '/tmp/tf-work'])

    def dir = mod.copyTerraformFilesToTempDir()
    assert dir?.absolutePath == '/tmp/tf-work'
  }

  @Test
  void should_use_temporary_working_dir() {
    File.metaClass.exists = { -> true }
    File.metaClass.mkdirs = { -> }
    File.metaClass.static.createTempDir = { String prefix, String suffix -> new File("/tmp/tf-random-work") }
    File.metaClass.setWritable = { boolean writable, boolean owner-> }
    FileUtils.metaClass.static.copyDirectory = {File src, File dst -> }

    def mod = new TfModuleAdapter(['tf-dir': '/tmp/tf-src'])

    def dir = mod.copyTerraformFilesToTempDir()
    assert dir?.absolutePath == '/tmp/tf-random-work'
  }

  @Test
  void should_not_apply_if_init_failed() {
    File.metaClass.exists = { -> true }
    def mod = new TfModExecTestAdapter() {
      @Override
      def executeTerraform(Object action, Object tfDirOrPlan, Object varFile, String... additionalArgs) {
        terraformActions << action
        return action == 'init' ? "init failure": 0
      }
    }
    assert 'init failure' == mod.executeDeploy()
    assert mod.terraformActions == ['init']
  }

  @Test
  void should_not_apply_if_dryRun() {
    File.metaClass.exists = { -> true }
    def mod = new TfModExecTestAdapter()
    mod.ctx.runtimeProps['dryrun'] = true
    assert mod.executeDeploy()?.contains("dry run")
    assert mod.terraformActions == ['init','plan']
  }

  @Test
  void should_not_apply_if_plan_file_not_created() {
    File.metaClass.exists = { -> !delegate.absolutePath.endsWith('.tfplan') }
    def mod = new TfModExecTestAdapter()
    assert mod.executeDeploy()?.contains("did not produce a plan file")
    assert mod.terraformActions == ['init','plan']
  }

  @Test
  void should_not_apply_if_user_declined_prompt() {
    File.metaClass.exists = { -> true }
    def mod = new TfModExecTestAdapter() {
      @Override
      boolean promptForPlanConfirmation() {
        return false
      }
    }
    assert mod.executeDeploy()?.contains("Aborted by user")
    assert mod.terraformActions == ['init','plan']
  }

  @Test
  void should_save_sensitive_outputs_to_vault() {
    def mod = new TfModuleAdapter() {
      @Override
      def executeTerraformWithOutputStream(Object action, Object tfDirOrPlan, Object varFile, OutputStream out, String... additionalArgs) {
        if(action == 'output' && out != null) {
          def js = JsonOutput.toJson([
                  "output1": ["sensitive": false, "value": "val1"],
                  "output2": ["sensitive": true, "value": "val2"]
          ])
          out.write(js.getBytes("UTF-8"))
          out.flush()
          out.close()
        }
        return null
      }
    }

    def capturedPath
    def capturedValues
    def stg = [ write: { path, values ->
      capturedPath = path
      capturedValues = values
    }
    ] as StorageService
    mod.ctx.storageSvc = stg

    mod.props['env']='myEnv'
    mod.props['envPrefix']='prefix-'


    mod.saveSensitiveOutputs()
    assert capturedPath=="prefix-myEnv/modules/myMod/secrets/tf-outputs"
    assert capturedValues==['output2':'val2']
  }

  @Test
  void should_skip_prompt_when_no_console_available() {
    System.metaClass.static.console = { -> null}
    def mod = new TfModuleAdapter()
    assert mod.promptForPlanConfirmation() == true
  }

  @Test
  void should_skip_prompt_when_force_is_specified() {
    System.metaClass.static.console = { -> new Object()}
    def mod = new TfModuleAdapter()
    mod.ctx.runtimeProps.force = "true"
    assert mod.promptForPlanConfirmation() == true
  }

  @Test
  void should_prompt_when_console_available() {
    def runWithResponse = { resp ->
      def console = [ 'readLine': { prompt -> resp }] as Object
      def usedMock = false
      System.metaClass.static.console = { -> usedMock=true; console }
      def mod = new TfModuleAdapter()
      def result = mod.promptForPlanConfirmation()
      assert usedMock
      return result
    }
    ["Y","y","YES","yes","yEs"].each {
      assert runWithResponse(it)
    }
    ["N","n","NO", "no", "No", "nO", "maybe"].each {
      assert !runWithResponse(it)
    }
  }

  @Test
  void should_return_plugin_directory() {
    def mod = new TfModuleAdapter()
    assert null == mod.getPluginDirectory()

    // Use default directory
    try {
      File.metaClass.exists = { -> true }
      def dir = mod.getPluginDirectory()
      assert "/tf-plugins" == dir.absolutePath
    } finally {
      File.metaClass = null
    }

    // User-specified directory
    try {
      File.metaClass.exists = { -> true }
      mod.ctx.moduleEnv['plugins-dir'] = '/path/to/plugins'
      def dir = mod.getPluginDirectory()
      assert "/path/to/plugins" == dir.absolutePath
    } finally {
      File.metaClass = null
    }

  }

}

class TfModuleAdapter extends TerraformModule {
  def props = new Properties()

  TfModuleAdapter(moduleEnv) {
    super("myMod", new ModuleContext([:], moduleEnv, null, null))
    this.ctx.runtimeProps = props
  }

  TfModuleAdapter() {
    this([:])
  }

  @Override
  File getTfWorkingDirectory() {
    return new File("/tmp/tf-copy")
  }

}
