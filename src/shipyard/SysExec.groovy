package shipyard

import util.*

public class SysExec {
  private static Log log = Log.getLogger(SysExec)

  private StringBuffer errorBuffer
  private StringBuffer outputBuffer

  public SysExec() {
    errorBuffer = new StringBuffer()
    outputBuffer = new StringBuffer()
  }

  /** Mask values of sensitive keys */
  def scrubEnv(envMap) {
    envMap.collectEntries { k, v ->
      def scrubbedVal = k?.toUpperCase().contains("SECRET") ? "******" : v
      [k, scrubbedVal]
    }
  }

  public int run(List<String> cmdAndArgs, Map<String,String> envVars, String workingDir, int timeoutMs, OutputStream out = null) {
    log.info("Executing command", [cmd:cmdAndArgs,env:scrubEnv(envVars),workingDir:workingDir,timeout:timeoutMs])
    def pb = new ProcessBuilder(cmdAndArgs)
    pb.environment() << envVars
    if (workingDir) pb.directory(new File(workingDir).getAbsoluteFile())
    Process proc = pb.start()

    def sem = new Object()
    def finished = false
    def outputThread = Thread.start("sysexec-reader", {
      while (!finished) {
        synchronized(sem) { sem.wait(50) }
        consumeStreams(proc, out)
      }
      consumeStreams(proc, out)
    })

    proc.waitForOrKill(timeoutMs)
    finished = true
    synchronized(sem) { sem.notify() }
    outputThread.join()

    int exitCode = proc.exitValue()
    log.info("Command exited", [exitCode:exitCode])
    if (errorBuffer.size() > 0) log.info("Command contained error output; exitCode=${exitCode}; error=${errorText}")
    return exitCode
  }

  public String getErrorText() { 
    errorBuffer.toString()
  }

  public String getOutputText() { 
    outputBuffer.toString()
  }

  def consumeStreams(Process proc, OutputStream out) {
    consumeStream(proc.inputStream, outputBuffer, out)
    consumeStream(proc.errorStream, errorBuffer, out)
  }

  def consumeStream(InputStream stream, StringBuffer buf, OutputStream out) {
    int avail = 0
    try {
      avail = stream.available()
    } catch (IOException e) {
      // stream closed
    }
    if (avail > 0) {
      def bytes = new byte[avail]
      stream.read(bytes, 0, avail)
      def tmpbuf = new String(bytes, 'UTF-8')
      buf.append(tmpbuf)
      if (out) out << tmpbuf
    }
  }
}