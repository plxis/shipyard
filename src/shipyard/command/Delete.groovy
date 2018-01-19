package shipyard.command

import shipyard.*

public class Delete extends Command {
  public String getDescription() { "Deletes a storage path." }
  public List getArgs() { [new Arg("path", "Storage path to delete. Ex: secret/password", true)]}

  public int execute(StorageService storageSvc, Properties props) {
    int exitCode = 0
    try {
      storageSvc.delete(props.path) 
    } catch (StorageException e) {
      exitCode = e.errorCode
    }
    return exitCode
  }
}