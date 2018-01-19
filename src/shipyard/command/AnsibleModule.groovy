package shipyard.command

import util.Log
import shipyard.Shipyard
import shipyard.ShipyardException

/**
 * Executes deployment of an ansible module.
 * Expects the module environment to contain an 'inventory' key,
 * which contains the Ansible inventory:
 * <pre>
 *  {
 *    "name": "myProduct-environment",
 *    "modules": {
 *      "myMod": {
 *        "type": "ansible"
 *        "inventory": {
 *          "server-group-1": {
 *            "hosts": [ "some-host-1" ],
 *            "vars": {
 *              "myMod_package_version": "2.7.740",
 *              "etc": "...."
 *            }
 *          }
 *        }
 *      }
 *    }
 *  }
 *</pre>
 */
class AnsibleModule extends DeploymentModule {
  private static Log log = Log.getLogger(AnsibleModule)

  AnsibleModule(name, ModuleContext ctx) {
    super(name, ctx)
  }

  @Override
  String getType() { "ansible" }

  @Override
  String getVersion() {
    readVar(ctx.moduleEnv.inventory, "${moduleName}_package_version")
  }

  @Override
  String getModuleInstallPackageSuffix() {
    return "-deployment"
  }

  @Override
  def executeDeploy() {
    if (!ctx.runtimeProps.sshUser) throw new ShipyardException("sshUser property is missing from shipyard configuration")
    def inventory = fetchInventory()
    runPlaybook(inventory)
  }

  @Override
  def executeUndeploy() {
    log.warn("Ansible module '${moduleName}' does not support the 'undeploy' operation")
    return null
  }

  /** Finds the first declared value for a key across all server groups */
  protected def readVar(Map inventory, String key) {
    def value
    inventory.findAll { k, v -> v instanceof Map && v.vars }.each { serverGroup, serverMap ->
      if (serverMap.vars instanceof Map && !value) {
        value = serverMap.vars[key]
      }
    }
    return value
  }

  /** Adds a key/value pair to the "vars" section of every server group in the inventory */
  protected def addVar(Map inventory, String key, def value) {
    inventory.findAll { k, v -> v instanceof Map && v.hosts }.each { serverGroup, serverMap ->
      if (!serverMap.vars) {
        serverMap.vars = [:]
      }
      if (!serverMap.vars[key]) {
        serverMap.vars[key] = value
      }
    }
  }

  protected Map fetchInventory() {
    def inventory = ctx.moduleEnv.inventory
    // Distribute the repository value from the global environment (if any) to each server group
    def repository = ctx.globalEnv.repository
    if (inventory && repository) {
      addVar(inventory, "repository", repository)
    }
    return inventory
  }

  protected def findValidTargets(requested, inventory) {
    def targets = []

    def reqList = requested?.split(",") ?: []

    // Find all matching host groups
    targets.addAll(reqList?.findAll { target -> inventory.containsKey(target) })

    // Find all hosts that exist within any of the inventory groups
    inventory.each { group, map ->
      targets.addAll(reqList.findAll { target -> map.hosts?.contains(target) })
    }

    return targets.join(",")
  }

  protected def runPlaybook(Map inventory) {
    def targets = findValidTargets(ctx.runtimeProps.targets, inventory)
    if (ctx.runtimeProps.targets && !targets) {
      log.info("Skipping module due to unmatched targets; targets=${ctx.runtimeProps.targets}; module=${moduleName}")
      return ""
    }

    def inventoryScript
    try {
      inventoryScript = exportInventoryToScript(inventory)

      def props = ctx.runtimeProps
      def cmd = []
      cmd << "ansible-playbook"
      cmd << "-i"
      cmd << inventoryScript.absolutePath
      cmd << "${props.externalModuleDir}/${moduleName}${moduleInstallPackageSuffix}/current/ansible/site.yml".toString()
      cmd << "-u"
      cmd << props.sshUser
      if (!props.nopass || !Boolean.parseBoolean(props.nopass)) {
        cmd << "--ask-pass"        // for SSH connection
        cmd << "--ask-become-pass" // for sudo escalation
      }
      if (props.parallel) {
        cmd << "--extra-vars=serial=" + props.parallel
      }
      if (targets) cmd << "--limit=${targets}".toString()
      if (Boolean.parseBoolean(props.dryrun)) cmd << "--check"
      if (Boolean.parseBoolean(props.diff)) cmd << "--diff"
      if (Boolean.parseBoolean(props.syntax)) cmd << "--syntax-check"
      if (Boolean.parseBoolean(props.verbose)) cmd << "--verbose"

      def extraArgs = ctx.moduleEnv?.get('ansible-extra-args')
      if(extraArgs) {
        log.info("Adding additional ansible arguments (taken from module declaration): ${extraArgs}")
        cmd.addAll(extraArgs)
      }

      def out = Boolean.parseBoolean(props.noconsole) ? null : System.out
      return ModuleCommand.run(cmd, [:], Shipyard.homeDir, props.cmdTimeout?.toInteger(), out)
    } finally {
      inventoryScript?.delete()
    }
  }

  File exportInventoryToScript(Map inventory) {
    exportJsonToFile(inventory, "superintendent-inventory", ".sh",
            "#!/usr/bin/env bash\n\necho -n '", "'")
  }

}
