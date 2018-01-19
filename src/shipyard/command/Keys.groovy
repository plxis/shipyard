package shipyard.command

import shipyard.*

public class Keys extends Command {
  public String getDescription() { "Lists all keys stored in a given storage path." }
  public List getArgs() { [new Arg("path", "Storage path to list. Ex: secret/password", true)]}

  public int execute(StorageService storageSvc, Properties props) {
    int exitCode
    try {
      output = storageSvc.list(props.path) 
    } catch (StorageException e) {
      exitCode = e.errorCode
    }
    return exitCode
  }
}