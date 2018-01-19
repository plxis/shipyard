package shipyard.command

import shipyard.*

public class Write extends Command {
  public String getDescription() { "Writes a value to a key at a given storage path." }
  public List getArgs() { [new Arg("path", "Storage path to write into. Ex: secret/password", true),
                           new Arg("key", "Specific key to write the new value", true),
                           new Arg("value", "Value to write into the key location", true, ["file"]),
                           new Arg("file", "File path containing the value to write", true, ["value"])] }

  public int execute(StorageService storageSvc, Properties props) {
    int exitCode = 0
    String value
    if (props.file) {
      def file = new File(props.file)
      if (file.isFile()) {
        value = file.text
      } else {
        throw new IllegalArgumentException("file does not exist")
      }
    } else {
      value = props.value
    }
    try {
      storageSvc.write(props.path, props.key, value?.trim())
    } catch (StorageException e) {
      exitCode = e.errorCode
    }
    return exitCode
  }
}