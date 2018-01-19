package shipyard.command

import util.*
import shipyard.*

public class Restore extends Command {
  private static Log log = Log.getLogger(Restore)

  public String getDescription() { "Restores the storage backend from a backup file. WARNING - This operation cannot be undone!" }
  public List getArgs() { [new Arg("file", "Name of the backup file located inside the archive directory", true)]}

  public int execute(StorageService storageSvc, Properties props) {
    int exitCode = 0
    int delayMs = props?.restoreDelayMs as Integer ?: 0

    try {
      log.warn("FATAL OPERATION WARNING - Restore operation cannot be undone and will begin in ${delayMs} milliseconds.")
      sleep(delayMs)

      storageSvc.restore(props.file) 
    } catch (StorageException e) {
      log.error("Restore operation failed", [errorCode:e.errorCode, reason:e.message])
      exitCode = e.errorCode
    }
    return exitCode
  }

  @Override
  public boolean shouldLock() {
    return true
  }
}