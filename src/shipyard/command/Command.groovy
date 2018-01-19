package shipyard.command

import shipyard.*

public abstract class Command {
  public static lookup(String name) {
    Class.forName(this.package.name + "." + name.capitalize())
  }

  public static instantiate(Class clazz) {
    clazz.newInstance()
  }

  public static List list() {
    // Could try to dyanmically lookup available classes
    def commands = []
    commands << Delete
    commands << Deploy
    commands << Keys
    commands << Read
    commands << Restore
    commands << Undeploy
    commands << Write
    return commands
  }

  protected String output = ""

  public String usage() {
    StringBuffer sb = new StringBuffer()
    sb.append(description)
    sb.append("\n\n")
    args.each { 
      sb.append("  ")
      sb.append(it) 
      sb.append("\n")
    }
    return sb.toString()
  }

  public def validateArgs(Properties props) {
    def failed
    args.each { arg ->
      if (arg.required) {
        if (!props[arg.name]) {
          if (arg.mutuallyExclusiveArgs) {
            if (!arg.mutuallyExclusiveArgs.find { props.containsKey(it) }) {
              failed = true
            }
          } else {
            failed = true
          }
        }
      }
    }
    if (failed) {
      return usage()
    }
    return null
  }

  public String getOutput() {
    return output
  }
  
  public boolean shouldLock() {
    return false
  }

  public abstract String getDescription()
  public abstract List getArgs()
  public abstract int execute(StorageService storageSvc, Properties props)
}