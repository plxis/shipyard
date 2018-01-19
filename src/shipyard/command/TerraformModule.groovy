package shipyard.command

import util.Log
import shipyard.StorageService
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.commons.io.FileUtils

/**
 * Executes deployment for a Terraform module.
 * Expects the module environment to contain an 'inventory' key,
 * which contains the Ansible inventory:
 * <pre>
 *  {
 *    "name": "myProduct-environment",
 *    "modules": {
 *      "myMod": {
 *        "type": "terraform"
 *        "version: "1.2.3.456"
 *        "aws_region": "us-east-1",
 *        "vars": {
 *          "var1": "val1",
 *          "list_var": ["list_val1","list_val2"],
 *          "map_var": { "mapKey1": "mapVal1", "mapKey2": "mapVal2"}
 *        }
 *      }
 *    }
 *  }
 *</pre>
 *
 * @see <a href="https://www.terraform.io/guides/running-terraform-in-automation.html">Terraform in automation</a>
 */
class TerraformModule extends DeploymentModule {

  private static Log log = Log.getLogger(TerraformModule)
  /**
   * (Optional) Module configuration key indicating source of terraform config
   * files (typicaly from <product>-provisioning RPM. Defaults to
   * "/opt/shipyard-modules/${moduleName}-provisioning/current/terraform".
   */
  private static final String TF_DIR_KEY = "tf-dir"

  /**
   * (Optional) Module configuration key indicating terraform working directory.
   * TF files (from <product>-provisioning pacakge, usually) will be copied here and
   * terraform will use this as it's working directory.
   * Defaults to a random temporary directory.
   */
  private static final String TF_WORKING_DIR_KEY = "tf-working-dir"

  /**
   * (Optional) Module configuration key indicating a directory which holds
   * pre-installed terraform plugins.
   */
  private static final String PLUGINS_DIR_KEY = "plugins-dir"
  private static final String DEFAULT_TF_PLUGINS_DIRECTORY = "/tf-plugins"

  /**
   * (Optional) Module configuration key indicating that the working directory
   * should not be deleted after executing the module. Default is false.
   */
  private static final String TF_PRESERVE_WORKING_DIR_KEY = "preserve-working-dir"

  private static final String TF_BACKEND_CONFIG_FILE = "remote-state-backend.tf"
  private static final String TF_STATE_BUCKET = "tf-state-bucket"
  private static final String TF_STATE_KEY = "tf-state-key"

  /**
   * Module configuration key indicating temporary directory containing copy of terraform config files.
   * This config key should not be populated by end-users. It will be created at runtime when the copy is made.
   */
  private static final String TF_DIR_COPY_KEY = "tf-dir-local"

  // NOTE: These key names were explicitly chosen to match the system environment variables
  //       used by the AWS CLI. Do not change them.
  /** Module configuration key containing AWS region */
  private static final String AWS_REGION = "aws_region"
  /** Module configuration key containing AWS access key ID */
  private static final String AWS_ACCESS_KEY_ID = "aws_access_key_id"
  /** Module configuration key containing AWS secret access key */
  private static final String AWS_SECRET_ACCESS_KEY = "aws_secret_access_key"
  /** Module configuration key containing AWS profile name */
  private static final String AWS_PROFILE = "aws_profile"
  /** Module configuration key containing AWS shared credentials file */
  private static final String AWS_SHARED_CREDENTIALS_FILE = "aws_shared_credentials_file"

  /** Default AWS credentials file */
  private static final String AWS_DEFAULT_CREDENTIALS_FILE = "${System.getenv('HOME')}/.aws/credentials"

  TerraformModule(String name, ModuleContext ctx) {
    super(name, ctx)
  }

  @Override
  String getType() {
    return "terraform"
  }

  @Override
  String getVersion() {
    return ctx.moduleEnv?.version
  }

  @Override
  String getModuleInstallPackageSuffix() {
    return "-provisioning"
  }

  @Override
  def executeDeploy() {
    return prepAndRunTf("apply", "-auto-approve=true")
  }

  @Override
  def executeUndeploy() {
    return prepAndRunTf("destroy")
  }

  def prepAndRunTf(String action, String... extraTfArgs) {

    def returnValue
    def ifContinue = { closure ->
      if (returnValue == null) {
        returnValue = closure()
      } else {
        TerraformModule.log.info("Skipping step due to previous failure or abort")
      }
    }

    def tfDir = getTfWorkingDirectory()

    def vars = ctx.moduleEnv?.vars
    File backendConfigFile, varFile, planFile
    try {
      varFile = exportJsonToFile(vars, moduleName, ".tfvars.json")
      planFile = File.createTempFile("${moduleName}-plan", ".tfplan")

      // Generate the backend configuration file
      backendConfigFile = generateBackendConfigurationFile(tfDir, true)

      // Initialize terraform dir (downloads required plugins)
      returnValue = executeTerraform("init", tfDir, varFile , "-upgrade")

      // Generate intermediate plan, save it, display it
      ifContinue {
        def args = ["-out=${planFile.absolutePath}"]
        if(action == "destroy") { args << "-destroy" }
        returnValue = executeTerraform("plan", tfDir, varFile, *args)
      }

      // Abort if user requested a dry run
      ifContinue {
        if(ctx.runtimeProps?.dryrun) {
          returnValue = "Aborting plan execution, only doing a dry run"
        }
      }

      // Get confirmation from user before executing
      ifContinue {
        if(planFile.exists()) {
          if(!promptForPlanConfirmation()) {
            returnValue = "Aborted by user"
          }
        } else {
          returnValue = "Terraform plan did not produce a plan file"
        }
      }

      // Apply changes (from saved plan)
      // NOTE: A destroy operation still uses "apply" to apply a destruction plan
      ifContinue {
        TerraformModule.log.info("Applying plan")
        executeTerraform("apply", planFile, null, extraTfArgs)
      }

      // Save sensitive outputs to vault
      ifContinue {
        if("deploy" != action) {
          saveSensitiveOutputs()
        }
      }

    } finally {
      deleteWorkingFiles(backendConfigFile, varFile, planFile, tfDir)
    }
    return returnValue
  }

  def deleteWorkingFiles(File backendConfigFile, File varFile, File planFile, File tfDir) {
    [varFile, planFile].each { f -> deleteFileOrDir(f) }
    def preserve = getConfigValue(TF_PRESERVE_WORKING_DIR_KEY, "Preserve working dir", false)
    if (preserve == null || !preserve.toBoolean()) {
      deleteFileOrDir(backendConfigFile)
      deleteFileOrDir(tfDir)
    } else {
      log.info("Preserving terraform working directory", [dir: tfDir])
    }
  }

  protected def deleteFileOrDir(f) {
    try {
      if(f?.isDirectory()) {
        FileUtils.deleteDirectory(f)
      } else {
        f?.delete()
      }
    } catch(Exception e) {log.info("Unable to delete ${f}: ${e.toString()}")}
  }

  /**
   * Returns the directory containing TF files. This directory will a copied made from
   * the location specified in the 'tf-dir' module configuration key. A copy is made
   * because the 'tf-dir' location is typically installed via RPM and is not writable
   * by the user executing shipyard.
   * @return
   */
  File getTfWorkingDirectory() {
    File tfWorkingDir
    def modEnv = ctx.moduleEnv
    // If a copy hasn't been made yet, then make it now
    if(modEnv[TF_DIR_COPY_KEY]) {
      tfWorkingDir = new File(modEnv[TF_DIR_COPY_KEY])
    } else {
      tfWorkingDir = copyTerraformFilesToTempDir()
      modEnv[TF_DIR_COPY_KEY] = tfWorkingDir.absolutePath
    }

    if(!tfWorkingDir || !tfWorkingDir.exists()) {
      throw new IllegalStateException("Unable to locate terraform copy")
    }
    return tfWorkingDir
  }

  protected def copyTerraformFilesToTempDir() {
    def tfSrcDir = getTerraformFilesSourceDir()

    // If working dir is specified, use it. Otherwise create a temp dir
    def tfCopyDir
    def workingDirPath = getConfigValue(TF_WORKING_DIR_KEY, "terraform working directory", false)
    if(workingDirPath) {
      tfCopyDir = new File(workingDirPath)
      tfCopyDir.mkdirs()
    } else {
      tfCopyDir = File.createTempDir("${moduleName}-tf", "")
    }

    // Copy files to working directory
    tfCopyDir.setWritable(true, true)
    // Copy terraform files (recursively) to temp dir
    log.info("Copying terraform config files from ${tfSrcDir} to ${tfCopyDir}")
    FileUtils.copyDirectory(tfSrcDir, tfCopyDir)
    tfCopyDir
  }

  protected def getTerraformFilesSourceDir() {
    def tfSrcDirPath = ctx.moduleEnv.get(TF_DIR_KEY)
    File tfSrcDir = tfSrcDirPath ? new File(tfSrcDirPath) : null
    // If not specified directly, try a reasonable default
    if(!tfSrcDir?.exists()) {
      def defaultTfSrcPath = "/opt/shipyard-modules/${moduleName}${moduleInstallPackageSuffix}/current/terraform"
      tfSrcDir = new File(defaultTfSrcPath)
    }
    if(!tfSrcDir?.exists()) {
      throw new IllegalArgumentException("Terraform source directory ${tfSrcDir?.absolutePath} does " +
              "not exist. Use the ${TF_DIR_KEY} module configuration key to specify another location.")
    }
    tfSrcDir
  }

  /**
   * Creates a terraform file containing configuration for the backend state storage
   */
  def generateBackendConfigurationFile(File targetDir, overwriteExisting = false) {
    File backendConfigFile = targetDir.toPath().resolve(TF_BACKEND_CONFIG_FILE).toFile()
    // Use existing file if desired
    if(backendConfigFile.exists()) {
      if(!overwriteExisting) {
        log.info("Terraform backend configuration file already exists, using it.", [file: backendConfigFile.absolutePath])
        return backendConfigFile
      }
      log.warn("Overwriting existing backend configuration file", [file: backendConfigFile.absolutePath])
    }

    def configJson = generateBackendConfiguration()
    backendConfigFile.text = configJson
    return backendConfigFile
  }

  /**
   * Generates a JSON string with configuration object for S3 backend
   * @return
   */
  String generateBackendConfiguration() {
    // TODO: Allow backend creds to differ from creds used to execute changes (check in modEnv['tf-backend'])

    // If module has a "context" variable, use it as the state storage location, otherwise default to the module name
    def tfStateDir = ctx.moduleEnv.vars?.context ?: moduleName

    def tfStateKey = getConfigValue(TF_STATE_KEY, "S3 state file key", false) ?: "terraform-states/${tfStateDir}/terraform.tfstate"
    def backendConfig = [
            "region": getConfigValue(AWS_REGION, "AWS region"),
            "bucket": getConfigValue(TF_STATE_BUCKET, "S3 state bucket"),
            "key": tfStateKey,
    ]

    // Insert AWS credentials for backend
    backendConfig += getAwsCredentials()

    JsonOutput.toJson(['terraform': [ 'backend': ['s3': backendConfig]]])
  }

  /**
   * Reads a configuration value from either runtime properties, module tf vars, module env, or global env,
   * in that order (first found wins).
   * Note: Only useful for "top-level" config values. Can't read from nested structures.
   */
  def getConfigValue(key, description, required=true) {
    def val
    def src
    if(ctx.runtimeProps.containsKey(key)) {
      val = ctx.runtimeProps.get(key)
      src = "runtime properties"
    } else if(systemEnv.get(key.toUpperCase())) {
      val = systemEnv.get(key.toUpperCase())
      src = "system environment var"
    } else if(ctx.moduleEnv?.get('vars')?.containsKey(key)) {
      val = ctx.moduleEnv?.get('vars')?.get(key)
      src = "module vars"
    } else if(ctx.moduleEnv?.containsKey(key)) {
      val = ctx.moduleEnv.get(key)
      src = "global environment"
    } else if(ctx.globalEnv?.containsKey(key)) {
      val = ctx.globalEnv.get(key)
      src = "module environment"
    }
    if(!val && required) {
      throw new IllegalStateException("Unable to determine ${description}")
    }

    if(val) {
      log.info("Using ${description} from ${src}", ["${key}": val])
    }
    return val
  }

  protected def getSystemEnv() { System.getenv() }

  /**
   * Returns AWS credential data in the supplied map (intended for use as TF backend or provider config).
   */
  Map getAwsCredentials() {
    def credData = [:]

    // Use shared credentials_file and profile if configured
    credData += configureForAwsProfile()

    // Look for access key ID and secret key value
    if(!credData) {
      credData += configureForAwsAccessKey()
    }

    // We should have at least 1 data element, either a profile name (and optional creds file) or access key info
    if(!credData) {
      throw new IllegalStateException("No AWS credentials configuration could be located")
    }
    return credData
  }

  /** Add authentication data for using AWS profile if available. */
  def configureForAwsProfile() {
    def profileProps = [:]
    def credsFile = getSharedCredentialsFile()
    if (credsFile.exists()) {
      def profile = getConfigValue(AWS_PROFILE, "AWS profile name", false)
      if (profile) {
        log.info("Using AWS profile '${profile}' from ${credsFile.absolutePath}")
        profileProps += ['shared_credentials_file': credsFile.absolutePath, 'profile': profile]
      } else {
        log.debug("No ${AWS_PROFILE} value located")
      }
    } else {
      log.debug("No AWS shared credentials file exists at ${credsFile.absolutePath}")
    }
    profileProps
  }

  /** Add authentication data for specifying AWS access keys directly if available. */
  def configureForAwsAccessKey() {
    def accessKeyProps = [:]
    def credSrc
    def getAwsApiKey = { src, keyIdFn, secretKeyFn ->
      def keyId = keyIdFn()
      def secretKey = secretKeyFn()
      if (keyId && secretKey) {
        accessKeyProps += ['access_key': keyId, 'secret_key': secretKey]
        credSrc = src
      }
    }

    // Check system env vars
    if (!accessKeyProps) {
      getAwsApiKey("system env vars",
              { systemEnv[AWS_ACCESS_KEY_ID.toUpperCase()] },
              { systemEnv[AWS_SECRET_ACCESS_KEY.toUpperCase()] })
    }
    // Check module env
    if (!accessKeyProps) {
      getAwsApiKey("module environment",
              { ctx.moduleEnv.get(AWS_ACCESS_KEY_ID) },
              { ctx.moduleEnv.get(AWS_SECRET_ACCESS_KEY) })
    }
    // Check global env
    if (!accessKeyProps) {
      getAwsApiKey("global environment",
              { ctx.globalEnv.get(AWS_ACCESS_KEY_ID) },
              { ctx.globalEnv.get(AWS_SECRET_ACCESS_KEY) })
    }

    if (accessKeyProps && credSrc) {
      log.info("Using AWS access key credentials from ${credSrc}", [access_key: accessKeyProps['access_key']])
    }
    accessKeyProps
  }

  File getSharedCredentialsFile() {
    def credPath = getConfigValue(AWS_SHARED_CREDENTIALS_FILE, "AWS credentials file", false)
    if(!credPath) {
      credPath = AWS_DEFAULT_CREDENTIALS_FILE
    }
    new File(credPath)
  }

  /**
   * Maps AWS credential data to system env vars, for use when executing terraform
   * @see #getAwsCredentials()
   */
  Map mapAwsCredsToEnvVars(credData) {
    def envVars = [:]
    def mapVar = { mapKey, envVar ->
      if(credData[mapKey]) {
        envVars[envVar.toUpperCase()] = credData[mapKey]
      }
    }
    ['access_key': AWS_ACCESS_KEY_ID,
     'secret_key': AWS_SECRET_ACCESS_KEY,
     'profile': AWS_PROFILE,
     'shared_credentials_file': AWS_SHARED_CREDENTIALS_FILE].each { mapKey, envVar ->
      mapVar(mapKey, envVar)
    }
    envVars
  }

  /**
   * Returns the directory containing pre-installed TF plugins. Will return null if no directory is configured,
   * or if the configured directory does not exist.
   */
  File getPluginDirectory() {
    def dirPath = getConfigValue(PLUGINS_DIR_KEY, "Pre-installed plugins directory", false)
    if(dirPath) {
      log.debug("Checking configured plugins directory: ${dirPath}")
      if(!new File(dirPath).exists()) {
        log.warn("Directory configured for terraform plugins (${dirPath}) does not exist")
        return null
      }
    }
    if(!dirPath) {
      dirPath = DEFAULT_TF_PLUGINS_DIRECTORY
      log.debug("Checking default plugins directory: ${dirPath}")
    }
    def dir = new File(dirPath)
    return (dir.exists()) ? dir : null
  }

  boolean hasConsole() {
    System.console() != null
  }

  /**
   * If console input available, prompt user for plan acceptance.
   */
  boolean promptForPlanConfirmation() {
    def confirmed = false
    if(hasConsole() && !ctx.runtimeProps.force) {
      def resp = System.console().readLine("Inspect the execution plan above. Do you want to apply it? (Y/N): ")
      def respUc = resp?.toUpperCase()
      if(respUc && (respUc == "Y" || respUc == "YES" )) {
        confirmed = true
      }
    } else {
      confirmed = true
    }
    return confirmed
  }

  /**
   * Finds any sensitive terraform outputs and saves them to the storage service
   */
  def saveSensitiveOutputs() {
    // Get outputs (as json)
    def outStream = new ByteArrayOutputStream()
    def retVal = executeTerraformWithOutputStream("output", tfWorkingDirectory, null, outStream, "-json")
    def outText = outStream?.toString("UTF-8")
    if(retVal != null) {
      // Not an error if no outputs are defined
      if(outText && outText.contains("has no outputs defined")) {
        return null
      }
      return retVal
    }
    def outputs = new JsonSlurper().parseText(outText)

    def sensitiveOutputs = outputs.findAll { name, info -> info.sensitive }
      .collectEntries { name, info -> [(name): info.value]}

    // Store sensitive outputs in vault (under a secret key)
    if(sensitiveOutputs) {
      StorageService stg = ctx.storageSvc
      def props = ctx.runtimeProps
      def vaultPath = "${props.envPrefix}${props.env}/modules/${moduleName}/secrets/tf-outputs"
      sensitiveOutputs.each { outputName, info ->
        stg.write(vaultPath, sensitiveOutputs)
      }
    }

    return retVal
  }

  /**
   * Execute terraform
   * @param action Action to perform (plan, apply, destroy, etc)
   * @param tfDirOrPlan Directory containing terraform configurations or path to a saved plan file
   * @param varFile File containing values for tf variables (typically read from storage svc)
   * @param additionalArgs Extra arguments to pass to Terraform
   * @return
   */
  def executeTerraform(action, tfDirOrPlan, varFile, String... additionalArgs) {
    def out = Boolean.parseBoolean(ctx.runtimeProps.noconsole) ? null : System.out
    executeTerraformWithOutputStream(action, tfDirOrPlan, varFile, out, additionalArgs)
  }

  /**
   * Execute terraform
   * @param action Action to perform (plan, apply, destroy, etc)
   * @param tfDirOrPlan Directory containing terraform configurations or path to a saved plan file
   * @param varFile File containing values for tf variables (typically read from storage svc)
   * @param out Output stream to hold stdout+stderr, null if output should be ignored
   * @param additionalArgs Extra arguments to pass to Terraform
   * @return
   */
  def executeTerraformWithOutputStream(action, tfDirOrPlan, varFile, OutputStream out, String... additionalArgs) {
    def filePath = { f ->
      f instanceof File ? f.absolutePath : f.toString()
    }

    def cmd = []
    cmd << "terraform"
    cmd << action
    if(!hasConsole()) {
      cmd << "-no-color"
    }
    if(["plan", "apply", "destroy"].contains(action) && varFile) {
      cmd << "--var-file"
      cmd << filePath(varFile)
    }

    if("init" == action) {
      // Check for pre-installed plugins
      File pluginDir = getPluginDirectory()
      log.info("Using plugin dir: ${pluginDir}")
      if (pluginDir) {
        cmd << "-plugin-dir=${pluginDir.absolutePath}".toString()
      }
    }

    def targetDir
    if(["plan", "apply"].contains(action)) {
      assert tfDirOrPlan : "Must specify Terraform config directory or a saved plan file"
      cmd << "-input=false"
      targetDir = filePath(tfDirOrPlan)
    }

    if(additionalArgs) {
      cmd.addAll(additionalArgs)
    }

    if(targetDir) {
      cmd << targetDir
    }

    def props = ctx.runtimeProps

    def workingDir = tfWorkingDirectory.absolutePath

    // Map AWS creds to env vars
    // Why env vars instead of putting this in the provider config inside TF files? Because putting it there
    // makes it difficult to do local development (requires putting your AWS creds into a vars file). It's also
    // more consistent with how most AWS tools work (look for creds in standard places).
    def tfEnv = mapAwsCredsToEnvVars(getAwsCredentials())

    // Tell terraform it is running in an automation environment (see https://www.terraform.io/guides/running-terraform-in-automation.html)
    tfEnv['TF_IN_AUTOMATION']='1'

    // Enable TF debug logging if verbose flag enabled
    if (Boolean.parseBoolean(props.verbose)) {
      tfEnv['TF_LOG'] = 'DEBUG'
    }

    return ModuleCommand.run(cmd, tfEnv, workingDir , props.cmdTimeout?.toInteger(), out)
  }
}
