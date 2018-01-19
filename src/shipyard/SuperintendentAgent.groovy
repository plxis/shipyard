package shipyard

import shipyard.command.*
import util.*
import util.Wait
import util.TimeoutException

import java.util.concurrent.Callable

public class SuperintendentAgent implements Callable {
  protected static Log log = Log.getLogger(SuperintendentAgent)

  private def props
  private StorageService storageSvc
  private String output = ""

  public SuperintendentAgent() {
  }
  
  public void init(Properties props) {
    if (!props) {
      throw new IllegalArgumentException("Properties not specified")
    }
    this.props = props

    storageSvc = StorageServiceFactory.create(props)

    log.info("Initialized agent", [storageSvc:storageSvc.class.name])
  }

  public String getOutput() {
    return output
  }

  public Object call() {
    int result = 1

    def cmd = Command.instantiate(lookupCommand(props))
    output = cmd.validateArgs(props)

    if (!output) {
      try {
        int timeoutMs = props?.lockTimeoutMs as Integer ?: 0

        Wait.on { -> !isLocked() }.every(1).atMostMs(timeoutMs).start()

        withLock(cmd) { ->
          result = cmd.execute(storageSvc, props)
          output = cmd.output
        }
      } catch (TimeoutException to) {
        def message = "Timed out while waiting to acquire lock"
        log.error(message, [command:cmd.class.simpleName, lockFile:lockFile])
        output = message
        result = 2
      }
    }

    return result
  }

  protected void withLock(Command command, Closure closure) {
    if (command.shouldLock()) {
      log.info("locking process", [command:command.class.simpleName, lockFile:lockFile])
      lock()
    }
    try {
      closure()
    } finally {
      if (command.shouldLock()) {
        log.info("unlocking process", [command:command.class.simpleName, lockFile:lockFile])
        unlock()
      }
    }    
  }

  protected boolean isLocked() {
    lockFile.exists()
  }

  protected void lock() {
    lockFile.write(System.currentTimeMillis().toString())
  }

  protected void unlock() {
    lockFile.delete()
  }

  protected File getLockFile() {
    new File(System.getProperty("java.io.tmpdir"), "superintendent.lock")    
  }

  protected lookupCommand(Properties props) {
    Command.lookup(props.command)
  }
}
