package shipyard.command

import shipyard.*

public class Read extends Command {
  public String getDescription() { "Reads a key or all keys from a given storage path." }
  public List getArgs() { [new Arg("path", "Storage path to read. Ex: secret/password", true),
                           new Arg("key", "Specific key to read from the given path", false),
                           new Arg("file", "File path in which to write the value", false)]}

  public int execute(StorageService storageSvc, Properties props) {
    int exitCode = 0
    try {
      if (props.key) {
        output = storageSvc.readKey(props.path, props.key) 
      } else {
        output = storageSvc.readAll(props.path) 
      }

      output = output ?: ""

      if (props.file) {
        def file = new File(props.file)
        file.text = output
        output = null
      } else {
        output = output ?: "(empty)"
      }
    } catch (StorageException e) {
      exitCode = e.errorCode
    }
    return exitCode
  }
}